/*
 * Copyright (C) 2018 Indeed Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
 package com.indeed.imhotep;

import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.indeed.imhotep.api.CommandSerializationParameters;
import com.indeed.imhotep.api.FTGAIterator;
import com.indeed.imhotep.api.FTGSIterator;
import com.indeed.imhotep.api.FTGSParams;
import com.indeed.imhotep.api.GroupStatsIterator;
import com.indeed.imhotep.api.HasSessionId;
import com.indeed.imhotep.api.ImhotepCommand;
import com.indeed.imhotep.api.ImhotepOutOfMemoryException;
import com.indeed.imhotep.api.PerformanceStats;
import com.indeed.imhotep.api.RegroupParams;
import com.indeed.imhotep.client.Host;
import com.indeed.imhotep.connection.ImhotepConnectionPool;
import com.indeed.imhotep.connection.ImhotepConnectionPoolWrapper;
import com.indeed.imhotep.io.BlockInputStream;
import com.indeed.imhotep.io.ImhotepProtobufShipping;
import com.indeed.imhotep.io.LimitedBufferedOutputStream;
import com.indeed.imhotep.io.RequestTools;
import com.indeed.imhotep.io.RequestTools.GroupMultiRemapRuleSender;
import com.indeed.imhotep.io.RequestTools.ImhotepRequestSender;
import com.indeed.imhotep.io.Streams;
import com.indeed.imhotep.io.TempFileSizeLimitExceededException;
import com.indeed.imhotep.io.WriteLimitExceededException;
import com.indeed.imhotep.marshal.ImhotepClientMarshaller;
import com.indeed.imhotep.protobuf.DocStat;
import com.indeed.imhotep.protobuf.HostAndPort;
import com.indeed.imhotep.protobuf.ImhotepRequest;
import com.indeed.imhotep.protobuf.ImhotepResponse;
import com.indeed.imhotep.protobuf.IntFieldAndTerms;
import com.indeed.imhotep.protobuf.MultiFTGSRequest;
import com.indeed.imhotep.protobuf.Operator;
import com.indeed.imhotep.protobuf.QueryRemapMessage;
import com.indeed.imhotep.protobuf.ShardBasicInfoMessage;
import com.indeed.imhotep.protobuf.StringFieldAndTerms;
import com.indeed.imhotep.protobuf.TracingMap;
import com.indeed.imhotep.protobuf.TracingMapOnly;
import com.indeed.imhotep.tracing.ProtoTracingInjector;
import com.indeed.imhotep.tracing.TracingUtil;
import com.indeed.imhotep.utils.tempfiles.ImhotepTempFiles;
import com.indeed.imhotep.utils.tempfiles.TempFile;
import com.indeed.util.core.Pair;
import com.indeed.util.core.io.Closeables2;
import io.opentracing.ActiveSpan;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.util.GlobalTracer;
import it.unimi.dsi.fastutil.longs.LongIterators;
import org.apache.log4j.Logger;

import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.indeed.imhotep.utils.ImhotepExceptionUtils.buildExceptionAfterSocketTimeout;
import static com.indeed.imhotep.utils.ImhotepExceptionUtils.buildIOExceptionFromResponse;
import static com.indeed.imhotep.utils.ImhotepExceptionUtils.buildImhotepKnownExceptionFromResponse;

/**
 * @author jsgroth
 *
 * an ImhotepSession for talking to a remote ImhotepDaemon over a Socket using protobufs
 */
public class ImhotepRemoteSession
    extends AbstractImhotepSession
    implements HasSessionId, CommandSerializationParameters {
    private static final Logger log = Logger.getLogger(ImhotepRemoteSession.class);

    public static final int DEFAULT_MERGE_THREAD_LIMIT =
        ImhotepRequest.getDefaultInstance().getMergeThreadLimit();

    private static final ImhotepConnectionPool CONNECTION_POOL = ImhotepConnectionPoolWrapper.INSTANCE;

    public static final byte DEFAULT_PRIORITY = 0;

    public static final int DEFAULT_SOCKET_TIMEOUT = (int)TimeUnit.MINUTES.toMillis(30);

    public static final int CURRENT_CLIENT_VERSION = 2; // id to be incremented as changes to the client are done

    private final String host;
    private final int port;
    private final Host hostAndPort;
    private final int socketTimeout;
    private final AtomicLong tempFileSizeBytesLeft;
    private final boolean includeTracingInformation;
    private boolean closed = false;

    private int numStats = 0;

    private final long numDocs;

    public ImhotepRemoteSession(final String host, final int port, final String sessionId,
                                final AtomicLong tempFileSizeBytesLeft) {
        this(host, port, sessionId, tempFileSizeBytesLeft, DEFAULT_SOCKET_TIMEOUT);
    }

    public ImhotepRemoteSession(final String host, final int port, final String sessionId,
                                @Nullable final AtomicLong tempFileSizeBytesLeft,
                                final int socketTimeout) {
        this(host, port, sessionId, tempFileSizeBytesLeft, socketTimeout, 0, false);
    }

    public ImhotepRemoteSession(final String host, final int port, final String sessionId,
                                @Nullable final AtomicLong tempFileSizeBytesLeft,
                                final int socketTimeout, final long numDocs, final boolean includeTracingInformation) {
        super(sessionId);
        this.host = host;
        this.port = port;
        this.hostAndPort = new Host(host, port);
        this.socketTimeout = socketTimeout;
        this.tempFileSizeBytesLeft = tempFileSizeBytesLeft;
        this.numDocs = numDocs;
        this.includeTracingInformation = includeTracingInformation;
    }

    public static ImhotepStatusDump getStatusDump(final String host, final int port) throws IOException {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.GET_STATUS_DUMP)
                .build();

        final ImhotepRemoteSession session = new ImhotepRemoteSession(host, port, "", null);

        final ImhotepResponse response = session.sendRequest(createSender(request));

        return ImhotepStatusDump.fromProto(response.getStatusDump());
    }

    public static ImhotepRemoteSession openSession(final String host, final int port, final String dataset, final List<Shard> shards, @Nullable final String sessionId, final long sessionTimeout) throws ImhotepOutOfMemoryException, IOException {
        return openSession(host, port, dataset, shards, DEFAULT_MERGE_THREAD_LIMIT, getUsername(), false, -1, sessionId, -1, null, sessionTimeout);
    }

    public static ImhotepRemoteSession openSession(final String host, final int port, final String dataset, final List<Shard> shards,
                                                   final int mergeThreadLimit, @Nullable final String sessionId, final long sessionTimeout) throws ImhotepOutOfMemoryException, IOException {
        return openSession(host, port, dataset, shards, mergeThreadLimit, getUsername(), false, -1, sessionId, -1, null, sessionTimeout);
    }

    public static ImhotepRemoteSession openSession(final String host, final int port, final String dataset, final List<Shard> shards,
                                                   final int mergeThreadLimit, final String username,
                                                   final boolean optimizeGroupZeroLookups, final int socketTimeout,
                                                   @Nullable final String sessionId, final long tempFileSizeLimit,
                                                   @Nullable final AtomicLong tempFileSizeBytesLeft,
                                                   final long sessionTimeout) throws ImhotepOutOfMemoryException, IOException {
        return openSession(host, port, dataset, shards, mergeThreadLimit, username, optimizeGroupZeroLookups, socketTimeout, sessionId, tempFileSizeLimit, tempFileSizeBytesLeft, sessionTimeout, 0);
    }

    public static ImhotepRemoteSession openSession(final String host, final int port, final String dataset, final List<Shard> shards,
                                                   final int mergeThreadLimit, final String username,
                                                   final boolean optimizeGroupZeroLookups, final int socketTimeout,
                                                   @Nullable final String sessionId, final long tempFileSizeLimit,
                                                   @Nullable final AtomicLong tempFileSizeBytesLeft,
                                                   final long sessionTimeout, final long numDocs) throws ImhotepOutOfMemoryException, IOException {

        return openSession(host, port, dataset, shards, mergeThreadLimit, username, "", DEFAULT_PRIORITY,
                optimizeGroupZeroLookups, socketTimeout, sessionId, tempFileSizeLimit, tempFileSizeBytesLeft,
                sessionTimeout, false, numDocs, false, false, false);
    }


    public static ImhotepRemoteSession openSession(final String host, final int port, final String dataset, final List<Shard> shards,
                                                   final int mergeThreadLimit, final String username, final String clientName, final byte priority,
                                                   final boolean optimizeGroupZeroLookups, final int socketTimeout,
                                                   @Nullable String sessionId, final long tempFileSizeLimit,
                                                   @Nullable final AtomicLong tempFileSizeBytesLeft,
                                                   final long sessionTimeout,
                                                   boolean allowSessionForwarding, final long numDocs,
                                                   final boolean p2pCache, final boolean useFtgsPooledConnection, final boolean traceImhotepRequests) throws ImhotepOutOfMemoryException, IOException {
        final Socket socket = newSocket(host, port, socketTimeout);
        final OutputStream os = Streams.newBufferedOutputStream(socket.getOutputStream());
        final InputStream is = Streams.newBufferedInputStream(socket.getInputStream());
        final Tracer tracer = TracingUtil.tracerIfInActiveSpan();
        try (final ActiveSpan activeSpan = tracer.buildSpan("OPEN_SESSION").withTag("sessionid", sessionId).withTag("dataset", dataset).withTag("host", host + ":" + port).startActive()) {
            log.trace("sending open request to "+host+":"+port+" for shards "+shards);
            final ImhotepRequest openSessionRequest = getBuilderForType(ImhotepRequest.RequestType.OPEN_SESSION)
                    .setUsername(username)
                    .setClientName(clientName)
                    .setSessionPriority(priority)
                    .setDataset(dataset)
                    .setMergeThreadLimit(mergeThreadLimit)
                    .addAllShards(shards.stream().map(shard -> {
                                final ShardBasicInfoMessage.Builder builder = ShardBasicInfoMessage.newBuilder()
                                    .setShardName(shard.getFileName())
                                    .setNumDocs(shard.numDocs);
                                if (p2pCache) {
                                    builder.setShardOwner(
                                            HostAndPort.newBuilder()
                                                    .setHost(shard.getOwner().getHostname())
                                                    .setPort(shard.getOwner().getPort())
                                                    .build()
                                    );
                                }
                                return builder.build();
                            }
                        ).collect(Collectors.toList()))
                    .setOptimizeGroupZeroLookups(optimizeGroupZeroLookups)
                    .setClientVersion(CURRENT_CLIENT_VERSION)
                    .setSessionId(sessionId == null ? "" : sessionId)
                    .setTempFileSizeLimit(tempFileSizeLimit)
                    .setSessionTimeout(sessionTimeout)
                    .setAllowSessionForwarding(allowSessionForwarding)
                    .setUseFtgsPooledConnection(useFtgsPooledConnection)
                    .build();
            try {
                ImhotepProtobufShipping.sendProtobuf(openSessionRequest, os);

                log.trace("waiting for confirmation from "+host+":"+port);
                final ImhotepResponse response = ImhotepProtobufShipping.readResponse(is);
                if (response.getResponseCode() == ImhotepResponse.ResponseCode.OUT_OF_MEMORY) {
                    throw new ImhotepOutOfMemoryException(createMessageWithSessionId(
                            "OutOfMemory error when creating session",
                            (sessionId == null) ? "" : sessionId));
                }
                if (response.getResponseCode() == ImhotepResponse.ResponseCode.KNOWN_ERROR) {
                    throw buildImhotepKnownExceptionFromResponse(response, host, port, sessionId);
                }
                if (response.getResponseCode() == ImhotepResponse.ResponseCode.OTHER_ERROR) {
                    throw buildIOExceptionFromResponse(response, host, port, sessionId);
                }
                if (sessionId == null) {
                    sessionId = response.getSessionId();
                }

                final int actualPort;
                if (response.getNewPort() != 0) {
                    actualPort = response.getNewPort();
                } else {
                    actualPort = port;
                }

                log.trace("session created, id "+sessionId);
                return new ImhotepRemoteSession(host, actualPort, sessionId, tempFileSizeBytesLeft, socketTimeout, numDocs, traceImhotepRequests);
            } catch (final SocketTimeoutException e) {
                throw buildExceptionAfterSocketTimeout(e, host, port, sessionId);
            }
        } finally {
            closeSocket(socket);
        }
    }

    public static String getUsername() {
        return System.getProperty("user.name");
    }

    @Override
    public long getTotalDocFreq(final String[] intFields, final String[] stringFields) {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.GET_TOTAL_DOC_FREQ)
                .setSessionId(getSessionId())
                .addAllIntFields(Arrays.asList(intFields))
                .addAllStringFields(Arrays.asList(stringFields))
                .build();

        try {
            final ImhotepResponse response = sendRequest(request, socketTimeout);
            final long result = response.getTotalDocFreq();
            return result;
        } catch (final IOException e) {
            throw newRuntimeException(e);
        }
    }

    @Override
    public long[] getGroupStats(final String groupsName, final List<String> stat) throws ImhotepOutOfMemoryException {
        try (final GroupStatsIterator reader = getGroupStatsIterator(groupsName, stat)) {
            return LongIterators.unwrap(reader, reader.getNumGroups());
        } catch(final IOException e) {
            throw newRuntimeException(e);
        }
    }

    @Override
    public GroupStatsIterator getGroupStatsIterator(final String groupsName, final List<String> stat) throws ImhotepOutOfMemoryException {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.STREAMING_GET_GROUP_STATS)
                .setSessionId(getSessionId())
                .addDocStat(DocStat.newBuilder().addAllStat(stat))
                .setHasStats(true)
                .setInputGroups(groupsName)
                .build();
        return sendGroupStatsIteratorRequest(request);
    }

    @Override
    public FTGSIterator getFTGSIterator(final String groupsName, final FTGSParams params) throws ImhotepOutOfMemoryException {
        final ImhotepRequest.Builder requestBuilder = getBuilderForType(ImhotepRequest.RequestType.GET_FTGS_ITERATOR)
                .setSessionId(getSessionId())
                .addAllIntFields(Arrays.asList(params.intFields))
                .addAllStringFields(Arrays.asList(params.stringFields))
                .setTermLimit(params.termLimit)
                .setSortStat(params.sortStat)
                .setSortedFTGS(params.sorted)
                .setInputGroups(groupsName);

        if (params.stats != null) {
            requestBuilder
                    .addAllDocStat(params.stats.stream().map(x -> DocStat.newBuilder().addAllStat(x).build()).collect(Collectors.toList()))
                    .setHasStats(true);
        }

        requestBuilder.setStatsSortOrder(params.statsSortOrder);

        final ImhotepRequest request = requestBuilder.build();

        final FTGSIterator result = fileBufferedFTGSRequest(request, false);
        return result;
    }

    FTGAIterator multiFTGS(final MultiFTGSRequest proto) throws ImhotepOutOfMemoryException {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.MERGE_MULTI_FTGS_SPLIT)
                .setMultiFtgsRequest(proto)
                .build();

        try {
            final Pair<ImhotepResponse, InputStream> responseAndFile = sendRequestAndSaveResponseToFile(request, ImhotepTempFiles.Type.MULTI_FTGS, false);
            final int numStats = responseAndFile.getFirst().getNumStats();
            final int numGroups = responseAndFile.getFirst().getNumGroups();
            return new InputStreamFTGAIterator(responseAndFile.getSecond(), null, numStats, numGroups);
        } catch (final IOException e) {
            throw new RuntimeException(e); // TODO
        }
    }

    GroupStatsIterator aggregateDistinct(final MultiFTGSRequest proto) throws ImhotepOutOfMemoryException {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.MERGE_MULTI_DISTINCT_SPLIT)
                .setMultiFtgsRequest(proto)
                .build();
        return sendGroupStatsIteratorRequest(request);
    }

    @Override
    public FTGSIterator getSubsetFTGSIterator(
            final String groupsName,
            final Map<String, long[]> intFields,
            final Map<String, String[]> stringFields,
            @Nullable final List<List<String>> stats) throws ImhotepOutOfMemoryException {
        final ImhotepRequest.Builder requestBuilder = getBuilderForType(ImhotepRequest.RequestType.GET_SUBSET_FTGS_ITERATOR)
                .setSessionId(getSessionId())
                .setInputGroups(groupsName);
        addSubsetFieldsAndTermsToBuilder(intFields, stringFields, requestBuilder);
        if (stats != null) {
            for (final List<String> stat : stats) {
                requestBuilder.addDocStat(DocStat.newBuilder().addAllStat(stat));
            }
            requestBuilder.setHasStats(true);
        }
        final ImhotepRequest request = requestBuilder.build();
        final FTGSIterator result = fileBufferedFTGSRequest(request, false);
        return result;
    }

    private void addSubsetFieldsAndTermsToBuilder(final Map<String, long[]> intFields, final Map<String, String[]> stringFields, final ImhotepRequest.Builder requestBuilder) {
        for (final Map.Entry<String, long[]> entry : intFields.entrySet()) {
            final IntFieldAndTerms.Builder builder = IntFieldAndTerms.newBuilder().setField(entry.getKey());
            for (final long term : entry.getValue()) {
                builder.addTerms(term);
            }
            requestBuilder.addIntFieldsToTerms(builder);
        }
        for (final Map.Entry<String, String[]> entry : stringFields.entrySet()) {
            requestBuilder.addStringFieldsToTerms(
                    StringFieldAndTerms.newBuilder()
                            .setField(entry.getKey())
                            .addAllTerms(Arrays.asList(entry.getValue()))
            );
        }
    }

    public FTGSIterator getFTGSIteratorSplit(
            final String groupsName,
            final String[] intFields,
            final String[] stringFields,
            @Nullable final List<List<String>> stats,
            final int splitIndex,
            final int numSplits,
            final long termLimit,
            final boolean useFtgsPooledConnection) throws ImhotepOutOfMemoryException {
        checkSplitParams(splitIndex, numSplits);
        final ImhotepRequest.Builder builder = getBuilderForType(ImhotepRequest.RequestType.GET_FTGS_SPLIT)
                .setSessionId(getSessionId())
                .addAllIntFields(Arrays.asList(intFields))
                .addAllStringFields(Arrays.asList(stringFields))
                .setSplitIndex(splitIndex)
                .setNumSplits(numSplits)
                .setTermLimit(termLimit)
                .setInputGroups(groupsName)
                .setSortStat(-1) // never top terms
                .setSortedFTGS(true) // always sorted
                .setUseFtgsPooledConnection(useFtgsPooledConnection);
        if (stats != null) {
            builder
                .addAllDocStat(stats.stream().map(x -> DocStat.newBuilder().addAllStat(x).build()).collect(Collectors.toList()))
                .setHasStats(true);
        }
        final ImhotepRequest request = builder.build();
        final FTGSIterator result = fileBufferedFTGSRequest(request, useFtgsPooledConnection);
        return result;
    }

    public FTGSIterator getSubsetFTGSIteratorSplit(
            final String groupsName,
            final Map<String, long[]> intFields,
            final Map<String, String[]> stringFields,
            @Nullable final List<List<String>> stats,
            final int splitIndex,
            final int numSplits,
            final boolean useFtgsPooledConnection) throws ImhotepOutOfMemoryException {
        checkSplitParams(splitIndex, numSplits);
        final ImhotepRequest.Builder requestBuilder = getBuilderForType(ImhotepRequest.RequestType.GET_SUBSET_FTGS_SPLIT)
                .setSessionId(getSessionId())
                .setSplitIndex(splitIndex)
                .setNumSplits(numSplits)
                .setInputGroups(groupsName)
                .setUseFtgsPooledConnection(useFtgsPooledConnection);
        if (stats != null) {
            requestBuilder
                    .addAllDocStat(stats.stream().map(x -> DocStat.newBuilder().addAllStat(x).build()).collect(Collectors.toList()))
                    .setHasStats(true);
        }
        addSubsetFieldsAndTermsToBuilder(intFields, stringFields, requestBuilder);
        final ImhotepRequest request = requestBuilder.build();
        final FTGSIterator result = fileBufferedFTGSRequest(request, useFtgsPooledConnection);
        return result;
    }

    public FTGSIterator mergeFTGSSplit(
            final String groupsName,
            final FTGSParams params,
            final InetSocketAddress[] nodes,
            final int splitIndex) throws ImhotepOutOfMemoryException {
        checkSplitParams(splitIndex, nodes.length);
        final ImhotepRequest.Builder requestBuilder = getBuilderForType(ImhotepRequest.RequestType.MERGE_FTGS_SPLIT)
                .setSessionId(getSessionId())
                .addAllIntFields(Arrays.asList(params.intFields))
                .addAllStringFields(Arrays.asList(params.stringFields))
                .setSplitIndex(splitIndex)
                .setTermLimit(params.termLimit)
                .setSortStat(params.sortStat)
                .setSortedFTGS(params.sorted)
                .setStatsSortOrder(params.statsSortOrder)
                .setInputGroups(groupsName)
                .addAllNodes(Iterables.transform(Arrays.asList(nodes), new Function<InetSocketAddress, HostAndPort>() {
                    public HostAndPort apply(final InetSocketAddress input) {
                        return HostAndPort.newBuilder().setHost(input.getHostName()).setPort(input.getPort()).build();
                    }
                }));

        if (params.stats != null) {
            requestBuilder
                    .addAllDocStat(params.stats.stream().map(x -> DocStat.newBuilder().addAllStat(x).build()).collect(Collectors.toList()))
                    .setHasStats(true);
        }

        final ImhotepRequest request = requestBuilder.build();
        final FTGSIterator result = fileBufferedFTGSRequest(request, false);
        return result;
    }

    public GroupStatsIterator mergeDistinctSplit(final String groupsName,
                                                 final String field,
                                                 final boolean isIntField,
                                                 final InetSocketAddress[] nodes,
                                                 final int splitIndex) {
        checkSplitParams(splitIndex, nodes.length);
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.MERGE_DISTINCT_SPLIT)
                .setSessionId(getSessionId())
                .setField(field)
                .setIsIntField(isIntField)
                .setSplitIndex(splitIndex)
                .setInputGroups(groupsName)
                .addAllNodes(Iterables.transform(Arrays.asList(nodes), input -> HostAndPort.newBuilder().setHost(input.getHostName()).setPort(input.getPort()).build()))
                .build();
        try {
            return sendGroupStatsIteratorRequest(request);
        } catch (final ImhotepOutOfMemoryException e) {
            throw new RuntimeException("mergeDistinct expected to use no stats but threw IOOME", e);
        }
    }

    private GroupStatsIterator sendGroupStatsIteratorRequest(final ImhotepRequest request) throws ImhotepOutOfMemoryException {
        try {
            final Pair<ImhotepResponse, InputStream> responceAndFile = sendRequestAndSaveResponseToFile(request, ImhotepTempFiles.Type.GROUP_STATS_ITERATOR, false);
            return ImhotepProtobufShipping.readGroupStatsIterator(
                    responceAndFile.getSecond(),
                    responceAndFile.getFirst().getGroupStatSize(),
                    false);
        } catch(final IOException e) {
            throw newRuntimeException(e);
        }
    }

    public FTGSIterator mergeSubsetFTGSSplit(
            final String groupsName,
            final Map<String, long[]> intFields,
            final Map<String, String[]> stringFields,
            @Nullable final List<List<String>> stats,
            final InetSocketAddress[] nodes,
            final int splitIndex) throws ImhotepOutOfMemoryException {
        checkSplitParams(splitIndex, nodes.length);
        final ImhotepRequest.Builder requestBuilder = getBuilderForType(ImhotepRequest.RequestType.MERGE_SUBSET_FTGS_SPLIT)
                .setSessionId(getSessionId())
                .setSplitIndex(splitIndex)
                .setInputGroups(groupsName)
                .addAllNodes(Iterables.transform(Arrays.asList(nodes), new Function<InetSocketAddress, HostAndPort>() {
                    public HostAndPort apply(final InetSocketAddress input) {
                        return HostAndPort.newBuilder().setHost(input.getHostName()).setPort(input.getPort()).build();
                    }
                }));
        if (stats != null) {
            for (final List<String> stat : stats) {
                requestBuilder.addDocStatBuilder().addAllStat(stat);
            }
            requestBuilder.setHasStats(true);
        }
        addSubsetFieldsAndTermsToBuilder(intFields, stringFields, requestBuilder);
        final ImhotepRequest request = requestBuilder.build();
        final FTGSIterator result = fileBufferedFTGSRequest(request, false);
        return result;
    }

    @Override
    public GroupStatsIterator getDistinct(final String groupsName, final String field, final boolean isIntField) {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.GET_DISTINCT)
                .setSessionId(getSessionId())
                .setField(field)
                .setIsIntField(isIntField)
                .setInputGroups(groupsName)
                .build();
        try {
            return sendGroupStatsIteratorRequest(request);
        } catch (final ImhotepOutOfMemoryException e) {
            throw new RuntimeException("getDistinct should use no memory but threw ImhotepOutOfMemoryException", e);
        }
    }

    private FTGSIterator fileBufferedFTGSRequest(final ImhotepRequest request, final boolean useFtgsPooledConnection) throws ImhotepOutOfMemoryException {
        try {
            final Pair<ImhotepResponse, InputStream> responseAndFile = sendRequestAndSaveResponseToFile(request, ImhotepTempFiles.Type.FTGS, useFtgsPooledConnection);
            final int numStats = responseAndFile.getFirst().getNumStats();
            final int numGroups = responseAndFile.getFirst().getNumGroups();
            return new InputStreamFTGSIterator(responseAndFile.getSecond(), null, numStats, numGroups);
        } catch (final IOException e) {
            throw newRuntimeException(e);
        }
    }

    public static BufferedInputStream saveResponseToFileFromStream(
            final InputStream is,
            final ImhotepTempFiles.Type tempFileType,
            final AtomicLong tempFileSizeBytesLeft,
            final String sessionId
    ) throws IOException {
        TempFile tmp = null;
        try {
            tmp = ImhotepTempFiles.create(tempFileType, sessionId);
            final long start = System.currentTimeMillis();
            try (final OutputStream out = new LimitedBufferedOutputStream(tmp.outputStream(), tempFileSizeBytesLeft)) {
                ByteStreams.copy(is, out);
            } catch (final WriteLimitExceededException t) {
                final String messageWithSessionId = createMessageWithSessionId(
                        TempFileSizeLimitExceededException.MESSAGE, sessionId);
                throw new TempFileSizeLimitExceededException(messageWithSessionId, t);
            } finally {
                if(log.isDebugEnabled()) {
                    log.debug("[" + sessionId + "] time to copy split data to file: " + (System.currentTimeMillis()
                            - start) + " ms, file length: " + tmp.getFileLength());
                }
            }
            return tmp.bufferedInputStream();
        } finally {
            if (tmp != null) {
                try {
                    tmp.removeFileStillReferenced();
                } catch (final Exception e) {
                    log.warn("[" + sessionId + "] Failed to delete temp file " + tmp);
                }
            }
        }
    }

    public BufferedInputStream saveResponseToFileFromStream(
            final InputStream is,
            final ImhotepTempFiles.Type tempFileType
    ) throws IOException {
        return saveResponseToFileFromStream(is, tempFileType, tempFileSizeBytesLeft, getSessionId());
    }

    private Pair<ImhotepResponse, InputStream> sendRequestAndSaveResponseToFile(
            final ImhotepRequest request,
            final ImhotepTempFiles.Type tempFileType,
            final boolean useFtgsPooledConnection) throws IOException, ImhotepOutOfMemoryException {
        if (useFtgsPooledConnection) {
            return CONNECTION_POOL.withBufferedSocketStream(hostAndPort,
                    (ImhotepConnectionPool.SocketStreamUser2Throwings<Pair<ImhotepResponse, InputStream>, IOException, ImhotepOutOfMemoryException>)
                            (is, os) -> {
                                try {
                                    return sendRequestAndSaveResponseWithSocket(request, tempFileType, is, os, true);
                                } finally {
                                    Closeables2.closeAll(log, is, os);
                                }
                            });
        } else {
            Pair<ImhotepResponse, InputStream> result = null;
            try (final Socket socket = newSocket(host, port, socketTimeout)) {
                final InputStream is = Streams.newBufferedInputStream(socket.getInputStream());
                final OutputStream os = Streams.newBufferedOutputStream(socket.getOutputStream());
                result = sendRequestAndSaveResponseWithSocket(request, tempFileType, is, os, false);
                return result;
            } catch (final Throwable e) {
                // In case socket.close() threw an exception
                if (result != null) {
                    Closeables2.closeQuietly(result.getSecond(), log);
                }
                throw e;
            }
        }
    }

    private Pair<ImhotepResponse, InputStream> sendRequestAndSaveResponseWithSocket(
            final ImhotepRequest request,
            final ImhotepTempFiles.Type tempFileType,
            final InputStream is,
            final OutputStream os,
            final boolean fromPooledConnection) throws IOException, ImhotepOutOfMemoryException {
        final ImhotepResponse response = checkMemoryException(getSessionId(), sendRequest(createSender(request), is, os));
        final BufferedInputStream tempFileStream;
        if (fromPooledConnection) {
            try (final BlockInputStream blockIs = new BlockInputStream(is)) {
                tempFileStream = saveResponseToFileFromStream(blockIs, tempFileType);
            }
        } else {
            tempFileStream = saveResponseToFileFromStream(is, tempFileType);
        }
        return new Pair<>(response, tempFileStream);
    }

    @Override
    public int regroup(final RegroupParams regroupParams,
                       final GroupMultiRemapRule[] rawRules,
                       final boolean errorOnCollisions) throws ImhotepOutOfMemoryException {
        final GroupMultiRemapRuleSender ruleSender =
                GroupMultiRemapRuleSender.createFromRules(Arrays.asList(rawRules).iterator(), false);
        return regroupWithSender(regroupParams, ruleSender, errorOnCollisions);
    }

    public int regroupWithSender(final RegroupParams regroupParams,
                                 final GroupMultiRemapRuleSender ruleSender,
                                 final boolean errorOnCollisions) throws ImhotepOutOfMemoryException {
        try {
            final int result = sendMultisplitRegroupRequest(regroupParams, ruleSender, errorOnCollisions);
            return result;
        } catch (final IOException e) {
            throw newRuntimeException(e);
        }
    }

    @Override
    public int regroup(final RegroupParams regroupParams,
                       final int numRawRules,
                       final Iterator<GroupMultiRemapRule> rawRules,
                       final boolean errorOnCollisions) throws ImhotepOutOfMemoryException {
        final GroupMultiRemapRuleSender ruleSender = GroupMultiRemapRuleSender.createFromRules(rawRules, false);
        return regroupWithSender(regroupParams, ruleSender, errorOnCollisions);
    }

    @Override
    public int regroup(final RegroupParams regroupParams, final QueryRemapRule rule) throws ImhotepOutOfMemoryException {
        final QueryRemapMessage protoRule = ImhotepClientMarshaller.marshal(rule);

        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.QUERY_REGROUP)
                .setSessionId(getSessionId())
                .setQueryRemapRule(protoRule)
                .setInputGroups(regroupParams.getInputGroups())
                .setOutputGroups(regroupParams.getOutputGroups())
                .build();

        try {
            final ImhotepResponse response = sendRequestWithMemoryException(request, socketTimeout);
            final int result = response.getNumGroups();
            return result;
        } catch (final IOException e) {
            throw newRuntimeException(e);
        }
    }

    @Override
    public void intOrRegroup(
            final RegroupParams regroupParams,
            final String field,
            final long[] terms,
            final int targetGroup,
            final int negativeGroup,
            final int positiveGroup) throws ImhotepOutOfMemoryException {
        final ImhotepRequestSender request = createSender(buildIntOrRegroupRequest(regroupParams, field, terms, targetGroup, negativeGroup, positiveGroup));
        sendVoidRequest(request);
    }

    protected void sendVoidRequest(final ImhotepRequestSender request) throws ImhotepOutOfMemoryException {
        try {
            sendRequestWithMemoryException(request, socketTimeout);
        } catch (final IOException e) {
            throw newRuntimeException(e);
        }
    }

    protected ImhotepRequest buildIntOrRegroupRequest(final RegroupParams regroupParams, String field, long[] terms, int targetGroup, int negativeGroup, int positiveGroup) {
        return getBuilderForType(ImhotepRequest.RequestType.INT_OR_REGROUP)
                    .setSessionId(getSessionId())
                    .setField(field)
                    .addAllIntTerm(Longs.asList(terms))
                    .setTargetGroup(targetGroup)
                    .setNegativeGroup(negativeGroup)
                    .setPositiveGroup(positiveGroup)
                    .setInputGroups(regroupParams.getInputGroups())
                    .setOutputGroups(regroupParams.getOutputGroups())
                    .build();
    }

    @Override
    public void stringOrRegroup(
            final RegroupParams regroupParams,
            final String field,
            final String[] terms,
            final int targetGroup,
            final int negativeGroup,
            final int positiveGroup) throws ImhotepOutOfMemoryException {
        final ImhotepRequestSender request = createSender(buildStringOrRegroupRequest(regroupParams, field, terms, targetGroup, negativeGroup, positiveGroup));
        sendVoidRequest(request);
    }

    protected ImhotepRequest buildStringOrRegroupRequest(final RegroupParams regroupParams, String field, String[] terms, int targetGroup, int negativeGroup, int positiveGroup) {
        return getBuilderForType(ImhotepRequest.RequestType.STRING_OR_REGROUP)
                    .setSessionId(getSessionId())
                    .setField(field)
                    .addAllStringTerm(Arrays.asList(terms))
                    .setTargetGroup(targetGroup)
                    .setNegativeGroup(negativeGroup)
                    .setPositiveGroup(positiveGroup)
                    .setInputGroups(regroupParams.getInputGroups())
                    .setOutputGroups(regroupParams.getOutputGroups())
                    .build();
    }

    @Override
    public void regexRegroup(
            final RegroupParams regroupParams,
            final String field,
            final String regex,
            final int targetGroup,
            final int negativeGroup,
            final int positiveGroup) throws ImhotepOutOfMemoryException {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.REGEX_REGROUP)
                .setSessionId(getSessionId())
                .setField(field)
                .setRegex(regex)
                .setTargetGroup(targetGroup)
                .setNegativeGroup(negativeGroup)
                .setPositiveGroup(positiveGroup)
                .setInputGroups(regroupParams.getInputGroups())
                .setOutputGroups(regroupParams.getOutputGroups())
                .build();

        try {
            sendRequestWithMemoryException(request, socketTimeout);
        } catch (final IOException e) {
            throw newRuntimeException(e);
        }
    }

    @Override
    public void randomRegroup(
            final RegroupParams regroupParams,
            final String field,
            final boolean isIntField,
            final String salt,
            final double p,
            final int targetGroup,
            final int negativeGroup,
            final int positiveGroup) throws ImhotepOutOfMemoryException {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.RANDOM_REGROUP)
                .setSessionId(getSessionId())
                .setField(field)
                .setIsIntField(isIntField)
                .setSalt(salt)
                .setP(p)
                .setTargetGroup(targetGroup)
                .setNegativeGroup(negativeGroup)
                .setPositiveGroup(positiveGroup)
                .setInputGroups(regroupParams.getInputGroups())
                .setOutputGroups(regroupParams.getOutputGroups())
                .build();

        try {
            sendRequestWithMemoryException(request, socketTimeout);
        } catch (final IOException e) {
            throw newRuntimeException(e);
        }
    }

    @Override
    public void randomMetricRegroup(final RegroupParams regroupParams, final List<String> stat, final String salt, final double p, final int targetGroup, final int negativeGroup, final int positiveGroup) throws ImhotepOutOfMemoryException {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.RANDOM_METRIC_REGROUP)
                .setSessionId(getSessionId())
                .addDocStat(DocStat.newBuilder().addAllStat(stat))
                .setHasStats(true)
                .setSalt(salt)
                .setP(p)
                .setTargetGroup(targetGroup)
                .setNegativeGroup(negativeGroup)
                .setPositiveGroup(positiveGroup)
                .setInputGroups(regroupParams.getInputGroups())
                .setOutputGroups(regroupParams.getOutputGroups())
                .build();

        try {
            sendRequestWithMemoryException(request, socketTimeout);
        } catch (final IOException e) {
            throw newRuntimeException(e);
        }
    }

    @Override
    public void randomMetricMultiRegroup(final RegroupParams regroupParams, final List<String> stat, final String salt, final int targetGroup, final double[] percentages, final int[] resultGroups) throws ImhotepOutOfMemoryException {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.RANDOM_METRIC_MULTI_REGROUP)
                .setSessionId(getSessionId())
                .addDocStat(DocStat.newBuilder().addAllStat(stat))
                .setHasStats(true)
                .setSalt(salt)
                .setTargetGroup(targetGroup)
                .addAllPercentages(Doubles.asList(percentages))
                .addAllResultGroups(Ints.asList(resultGroups))
                .setInputGroups(regroupParams.getInputGroups())
                .setOutputGroups(regroupParams.getOutputGroups())
                .build();

        try {
            sendRequestWithMemoryException(request, socketTimeout);
        } catch (final IOException e) {
            throw newRuntimeException(e);
        }
    }

    @Override
    public int metricRegroup(final RegroupParams regroupParams, final List<String> stat, final long min, final long max, final long intervalSize, final boolean noGutters) throws ImhotepOutOfMemoryException {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.METRIC_REGROUP)
                .setSessionId(getSessionId())
                .setXStatDocstat(DocStat.newBuilder().addAllStat(stat))
                .setXMin(min)
                .setXMax(max)
                .setXIntervalSize(intervalSize)
                .setNoGutters(noGutters)
                .setInputGroups(regroupParams.getInputGroups())
                .setOutputGroups(regroupParams.getOutputGroups())
                .build();

        try {
            final ImhotepResponse response = sendRequestWithMemoryException(request, socketTimeout);
            final int result = response.getNumGroups();
            return result;
        } catch (final IOException e) {
            throw newRuntimeException(e);
        }
    }

    @Override
    public int regroup(final RegroupParams regroupParams, final int[] fromGroups, final int[] toGroups, final boolean filterOutNotTargeted) throws ImhotepOutOfMemoryException {
        final ImhotepRequestSender request = createSender(buildGroupRemapRequest(regroupParams, fromGroups, toGroups, filterOutNotTargeted));
        return sendRegroupRequest(request);
    }

    public ImhotepRequest buildGroupRemapRequest(final RegroupParams regroupParams, final int[] fromGroups, final int[] toGroups, final boolean filterOutNotTargeted) throws ImhotepOutOfMemoryException {
        return getBuilderForType(ImhotepRequest.RequestType.REMAP_GROUPS)
                .setSessionId(getSessionId())
                .addAllFromGroups(Ints.asList(fromGroups))
                .addAllToGroups(Ints.asList(toGroups))
                .setFilterOutNotTargeted(filterOutNotTargeted)
                .setInputGroups(regroupParams.getInputGroups())
                .setOutputGroups(regroupParams.getOutputGroups())
                .build();
    }

    public int sendRegroupRequest(final ImhotepRequestSender request) throws ImhotepOutOfMemoryException {
        try {
            final ImhotepResponse response = sendRequestWithMemoryException(request, socketTimeout);
            final int result = response.getNumGroups();
            return result;
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int metricFilter(final RegroupParams regroupParams, final List<String> stat, final long min, final long max, final boolean negate) throws ImhotepOutOfMemoryException {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.METRIC_FILTER)
                .setSessionId(getSessionId())
                .setXStatDocstat(DocStat.newBuilder().addAllStat(stat))
                .setXMin(min)
                .setXMax(max)
                .setNegate(negate)
                .setInputGroups(regroupParams.getInputGroups())
                .setOutputGroups(regroupParams.getOutputGroups())
                .build();
        try {
            final ImhotepResponse response = sendRequestWithMemoryException(request, socketTimeout);
            final int result = response.getNumGroups();
            return result;
        } catch (final IOException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public int metricFilter(final RegroupParams regroupParams, final List<String> stat, final long min, final long max, final int targetGroup, final int negativeGroup, final int positiveGroup) throws ImhotepOutOfMemoryException {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.METRIC_FILTER)
                .setSessionId(getSessionId())
                .setXStatDocstat(DocStat.newBuilder().addAllStat(stat))
                .setXMin(min)
                .setXMax(max)
                .setTargetGroup(targetGroup)
                .setPositiveGroup(positiveGroup)
                .setNegativeGroup(negativeGroup)
                .setInputGroups(regroupParams.getInputGroups())
                .setOutputGroups(regroupParams.getOutputGroups())
                .build();
        try {
            final ImhotepResponse response = sendRequestWithMemoryException(request, socketTimeout);
            final int result = response.getNumGroups();
            return result;
        } catch (final IOException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public List<TermCount> approximateTopTerms(final String field, final boolean isIntField, final int k) {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.APPROXIMATE_TOP_TERMS)
                .setSessionId(getSessionId())
                .setField(field)
                .setIsIntField(isIntField)
                .setK(k)
                .build();

        try {
            final ImhotepResponse response = sendRequest(request, socketTimeout);
            final List<TermCount> result =
                ImhotepClientMarshaller.marshal(response.getTopTermsList());
            return result;
        } catch (final IOException e) {
            throw newRuntimeException(e);
        }
    }

    @Override
    public void consolidateGroups(final List<String> inputGroups, final Operator operation, final String outputGroups) throws ImhotepOutOfMemoryException {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.CONSOLIDATE_GROUPS)
                .setSessionId(getSessionId())
                .addAllConsolidatedGroups(inputGroups)
                .setOutputGroups(outputGroups)
                .setGroupConsolidationOperation(operation)
                .build();

        try {
            sendRequestWithMemoryException(request, socketTimeout);
        } catch (final IOException e) {
            throw newRuntimeException(e);
        }
    }

    @Override
    public void deleteGroups(final List<String> groupsToDelete) {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.DELETE_GROUPS)
                .setSessionId(getSessionId())
                .addAllGroupsToDelete(groupsToDelete)
                .build();

        try {
            sendRequest(request, socketTimeout);
        } catch (final IOException e) {
            throw newRuntimeException(e);
        }
    }

    @Override
    public int pushStat(final String statName) throws ImhotepOutOfMemoryException {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.PUSH_STAT)
                .setSessionId(getSessionId())
                .setMetric(statName)
                .build();

        try {
            final ImhotepResponse response = sendRequestWithMemoryException(request, socketTimeout);
            numStats = response.getNumStats();
            return numStats;
        } catch (final IOException e) {
            throw newRuntimeException(e);
        }
    }

    @Override
    public int pushStats(final List<String> statNames) throws ImhotepOutOfMemoryException {
        for (final String statName : statNames) {
            this.pushStat(statName);
        }

        return numStats;
    }

    @Override
    public int popStat() {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.POP_STAT)
                .setSessionId(getSessionId())
                .build();

        try {
            final ImhotepResponse response = sendRequest(request, socketTimeout);
            numStats = response.getNumStats();
            return numStats;
        } catch (final IOException e) {
            throw newRuntimeException(e);
        }
    }

    @Override
    public int getNumStats() {
        // TODO: really should ask the remote session just to be sure.
        return numStats;
    }

    @Override
    public int getNumGroups(final String groupsName) {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.GET_NUM_GROUPS)
                .setSessionId(getSessionId())
                .setInputGroups(groupsName)
                .build();
        try {
            final ImhotepResponse response = sendRequest(request, socketTimeout);
            final int result = response.getNumGroups();
            return result;
        } catch (final IOException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public void close() {
        internalClose(false);
    }

    private PerformanceStats internalClose(final boolean getStats) {
        if(closed) {
            return null;
        }
        final ImhotepRequest.Builder builder = getBuilderForType(ImhotepRequest.RequestType.CLOSE_SESSION);
        builder.setSessionId(getSessionId());
        if(getStats) {
            // adding only if it's true to save bytes in request.
            builder.setReturnStatsOnClose(true);
        }

        final ImhotepRequest request = builder.build();

        PerformanceStats stats = null;
        try {
            final ImhotepResponse response = sendRequest(request, socketTimeout);
            if(response.hasPerformanceStats()) {
                stats = ImhotepClientMarshaller.marshal(response.getPerformanceStats());
            }
        } catch (final IOException e) {
            log.error("[" + getSessionId() + "] error closing session", e);
        } finally {
            closed = true;
        }
        return stats;
    }

    @Override
    public void resetGroups(final String groupsName) {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.RESET_GROUPS)
                .setSessionId(getSessionId())
                .setInputGroups(groupsName)
                .build();

        try {
            sendRequest(request, socketTimeout);
        } catch (final IOException e) {
            throw newRuntimeException(e);
        }
    }

    @Override
    public long getNumDocs() {
        return numDocs;
    }

    @Override
    public PerformanceStats getPerformanceStats(final boolean reset) {
        final ImhotepRequest request = getBuilderForType(ImhotepRequest.RequestType.GET_PERFORMANCE_STATS)
                .setSessionId(getSessionId())
                .setResetPerformanceStats(reset)
                .build();

        try {
            final ImhotepResponse response = sendRequest(request, socketTimeout);
            final PerformanceStats stats = ImhotepClientMarshaller.marshal(response.getPerformanceStats());
            return stats;
        } catch (final IOException e) {
            throw newRuntimeException(e);
        }
    }

    @Override
    public PerformanceStats closeAndGetPerformanceStats() {
        return internalClose(true);
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    @Override
    public AtomicLong getTempFileSizeBytesLeft() {
        return tempFileSizeBytesLeft;
    }

    private static ImhotepRequest.Builder getBuilderForType(final ImhotepRequest.RequestType requestType) {
        return ImhotepRequest.newBuilder().setRequestType(requestType);
    }

    private ImhotepResponse sendRequest(final ImhotepRequestSender request) throws IOException {
        return sendRequest(request, -1);
    }

    private ImhotepResponse sendRequest(final ImhotepRequestSender request, final int socketTimeout) throws IOException {
        final Socket socket = newSocket(host, port, socketTimeout);
        final InputStream is = Streams.newBufferedInputStream(socket.getInputStream());
        final OutputStream os = Streams.newBufferedOutputStream(socket.getOutputStream());
        try {
            return sendRequest(request, is, os);
        } finally {
            closeSocket(socket);
        }
    }

    private ImhotepResponse sendRequest(final ImhotepRequest request, final int socketTimeout) throws IOException {
        final Socket socket = newSocket(host, port, socketTimeout);
        final InputStream is = Streams.newBufferedInputStream(socket.getInputStream());
        final OutputStream os = Streams.newBufferedOutputStream(socket.getOutputStream());
        final ImhotepRequestSender requestSender = createSender(request);
        try {
            return sendRequest(requestSender, is, os);
        } finally {
            closeSocket(socket);
        }
    }

    public int sendMultisplitRegroupRequest(
            final RegroupParams regroupParams,
            final GroupMultiRemapRuleSender rulesSender,
            final boolean errorOnCollisions) throws IOException, ImhotepOutOfMemoryException {
        final ImhotepRequest initialRequest = getBuilderForType(ImhotepRequest.RequestType.EXPLODED_MULTISPLIT_REGROUP)
                .setLength(rulesSender.getRulesCount())
                .setSessionId(getSessionId())
                .setErrorOnCollisions(errorOnCollisions)
                .setInputGroups(regroupParams.getInputGroups())
                .setOutputGroups(regroupParams.getOutputGroups())
                .build();

        final Socket socket = newSocket(host, port, socketTimeout);
        final InputStream is = Streams.newBufferedInputStream(socket.getInputStream());
        final OutputStream os = Streams.newBufferedOutputStream(socket.getOutputStream());
        final Tracer tracer = TracingUtil.tracerIfInActiveSpan();
        final String sessionId = initialRequest.getSessionId();
        try (final ActiveSpan activeSpan = tracer.buildSpan(initialRequest.getRequestType().name()).withTag("sessionid", sessionId).withTag("host", host + ":" + port).startActive()) {
            ImhotepProtobufShipping.sendProtobufNoFlush(initialRequest, os);
            rulesSender.writeToStreamNoFlush(os);
            os.flush();
            final ImhotepResponse response = readResponseWithMemoryException(is, host, port);
            return response.getNumGroups();
        } catch (final IOException e) {
            log.error("[" + getSessionId() + "] error sending exploded multisplit regroup request to " + host + ":" + port, e);
            throw e;
        } finally {
            closeSocket(socket);
        }
    }

    private ImhotepResponse sendRequestWithMemoryException(
            final ImhotepRequestSender request,
            final int socketTimeout) throws IOException, ImhotepOutOfMemoryException {
        final ImhotepResponse response = sendRequest(request, socketTimeout);
        if (response.getResponseCode() == ImhotepResponse.ResponseCode.OUT_OF_MEMORY) {
            throw new ImhotepOutOfMemoryException();
        } else {
            return response;
        }
    }

    private static ImhotepResponse checkMemoryException(@Nullable final String sessionId, final ImhotepResponse response) throws ImhotepOutOfMemoryException {
        if (response.getResponseCode() == ImhotepResponse.ResponseCode.OUT_OF_MEMORY) {
            throw new ImhotepOutOfMemoryException(createMessageWithSessionId("OutOfMemory error", sessionId));
        } else {
            return response;
        }
    }

    private ImhotepResponse sendRequestWithMemoryException(
            final ImhotepRequest request,
            final int socketTimeout) throws IOException, ImhotepOutOfMemoryException {
        final ImhotepResponse response = sendRequest(request, socketTimeout);
        return checkMemoryException(request.getSessionId(), response);
    }

    private ImhotepResponse sendRequest(
            final ImhotepRequestSender request,
            final InputStream is,
            final OutputStream os) throws IOException {
        final Tracer tracer = TracingUtil.tracerIfEnabled(includeTracingInformation);
        final String sessionId = request.getSessionId();
        try (final ActiveSpan activeSpan = tracer.buildSpan(request.getRequestType().name()).withTag("sessionid", sessionId).withTag("host", host + ":" + port).startActive()) {
            final TracingMap.Builder tracingBuilder = TracingMap.newBuilder();
            tracer.inject(activeSpan.context(), Format.Builtin.TEXT_MAP, new TextMap() {
                @Override
                public Iterator<Map.Entry<String, String>> iterator() {
                    throw new UnsupportedOperationException("write-only adapter");
                }

                @Override
                public void put(final String key, final String value) {
                    tracingBuilder.addKeyValues(TracingMap.KeyValueString.newBuilder().setKey(key).setValue(value).build());
                }
            });
            final byte[] tracingBytes = TracingMapOnly.newBuilder().setTracingInfo(tracingBuilder).build().toByteArray();
            request.writeToStreamNoFlush(os, tracingBytes);
            os.flush();
            final ImhotepResponse response = ImhotepProtobufShipping.readResponse(is);
            if (response.getResponseCode() == ImhotepResponse.ResponseCode.KNOWN_ERROR) {
                throw buildImhotepKnownExceptionFromResponse(response, host, port, sessionId);
            }
            if (response.getResponseCode() == ImhotepResponse.ResponseCode.OTHER_ERROR) {
                throw buildIOExceptionFromResponse(response, host, port, sessionId);
            }
            return response;
        } catch (final SocketTimeoutException e) {
            throw buildExceptionAfterSocketTimeout(e, host, port, sessionId);
        } catch (final IOException e) {
            String errorMessage = "IO error with " + request.getRequestType() + " request to " + host + ":" + port;
            if (sessionId != null) {
                errorMessage = createMessageWithSessionId(errorMessage, sessionId);
            }
            log.error(errorMessage, e);
            throw new IOException(errorMessage, e);
        }
    }

    public static ImhotepResponse readResponseWithMemoryExceptionSessionId(
            final InputStream is,
            final String host,
            final int port,
            final String sessionId
    ) throws IOException, ImhotepOutOfMemoryException {
        final ImhotepResponse response = ImhotepProtobufShipping.readResponse(is);
        if (response.getResponseCode() == ImhotepResponse.ResponseCode.KNOWN_ERROR) {
            throw buildImhotepKnownExceptionFromResponse(response, host, port, sessionId);
        } else if (response.getResponseCode() == ImhotepResponse.ResponseCode.OTHER_ERROR) {
            throw buildIOExceptionFromResponse(response, host, port, sessionId);
        } else if (response.getResponseCode() == ImhotepResponse.ResponseCode.OUT_OF_MEMORY) {
            throw newImhotepOutOfMemoryException(sessionId);
        } else if (response.getResponseCode() == ImhotepResponse.ResponseCode.OK) {
            return response;
        } else {
            throw new IllegalStateException("Can't recognize ResponseCode of imhotepResponse " + response);
        }
    }

    private ImhotepResponse readResponseWithMemoryException(
            final InputStream is,
            final String host,
            final int port) throws IOException, ImhotepOutOfMemoryException {
        try {
            return readResponseWithMemoryExceptionSessionId(is, host, port, getSessionId());
        } catch (final SocketTimeoutException e) {
            throw buildExceptionAfterSocketTimeout(e, host, port, getSessionId());
        }
    }

    private static void closeSocket(final Socket socket) {
        Closeables2.closeQuietly( socket, log );
    }

    private static ImhotepRequestSender createSender(final ImhotepRequest request) {
        return new RequestTools.ImhotepRequestSender.Simple(request);
    }

    private static Socket newSocket(final String host, final int port) throws IOException {
        return newSocket(host, port, DEFAULT_SOCKET_TIMEOUT);
    }

    public static Socket newSocket(final String host, final int port, final int timeout) throws IOException {
        final Socket socket = new Socket(host, port);
        socket.setReceiveBufferSize(65536);
        socket.setSoTimeout(timeout >= 0 ? timeout : DEFAULT_SOCKET_TIMEOUT);
        socket.setTcpNoDelay(true);
        return socket;
    }

    public InetSocketAddress getInetSocketAddress() {
        return new InetSocketAddress(host, port);
    }

    public void setNumStats(final int numStats) {
        this.numStats = numStats;
    }

    public <T> T sendImhotepBatchRequest(final List<ImhotepCommand> firstCommands, final ImhotepCommand<T> lastCommand) throws IOException, ImhotepOutOfMemoryException {
        final ImhotepRequest batchRequestHeader = getBuilderForType(ImhotepRequest.RequestType.BATCH_REQUESTS)
                .setImhotepRequestCount(firstCommands.size() + 1)
                .setSessionId(getSessionId())
                .build();
        final ImhotepRequestSender imhotepRequestSender = new RequestTools.ImhotepRequestSender.Simple(batchRequestHeader);

        final Socket socket = newSocket(host, port, socketTimeout);
        final OutputStream os = Streams.newBufferedOutputStream(socket.getOutputStream());
        final InputStream is = Streams.newBufferedInputStream(socket.getInputStream());
        final Tracer tracer = TracingUtil.tracerIfEnabled(includeTracingInformation);

        try (final ActiveSpan activeSpan = tracer.buildSpan(imhotepRequestSender.getRequestType().name()).withTag("sessionid", getSessionId()).withTag("host", host + ":" + port).startActive()) {
            final List<String> commandClassNameList = Stream.concat(firstCommands.stream(), Stream.of(lastCommand)).map(BatchRemoteImhotepMultiSession::getCommandClassName).collect(Collectors.toList());
            activeSpan.log(Collections.singletonMap("commandNames", commandClassNameList));
            final ProtoTracingInjector protoTracingInjector = new ProtoTracingInjector();
            tracer.inject(activeSpan.context(), Format.Builtin.TEXT_MAP, protoTracingInjector);
            final byte[] tracingBytes = TracingMapOnly.newBuilder().setTracingInfo(protoTracingInjector.extract()).build().toByteArray();
            imhotepRequestSender.writeToStreamNoFlush(os, tracingBytes);
            os.flush();

            for (final ImhotepCommand command : firstCommands) {
                command.writeToOutputStream(os, this);
            }
            lastCommand.writeToOutputStream(os, this);
            os.flush();

            return lastCommand.readResponse(is, this);
        } catch (final SocketTimeoutException e) {
            throw newRuntimeException(buildExceptionAfterSocketTimeout(e, host, port, null));
        }
        finally {
            closeSocket(socket);
        }
    }

}
