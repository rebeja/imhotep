package com.indeed.imhotep;


import com.indeed.flamdex.query.Query;
import com.indeed.imhotep.api.FTGSIterator;
import com.indeed.imhotep.api.FTGSParams;
import com.indeed.imhotep.api.GroupStatsIterator;
import com.indeed.imhotep.api.ImhotepCommand;
import com.indeed.imhotep.api.ImhotepOutOfMemoryException;
import com.indeed.imhotep.api.ImhotepSession;
import com.indeed.imhotep.api.PerformanceStats;
import com.indeed.imhotep.commands.GetGroupStats;
import com.indeed.imhotep.commands.IntOrRegroup;
import com.indeed.imhotep.commands.MultiRegroup;
import com.indeed.imhotep.commands.MetricRegroup;
import com.indeed.imhotep.commands.RandomMetricMultiRegroup;
import com.indeed.imhotep.commands.RandomMetricRegroup;
import com.indeed.imhotep.commands.RandomMultiRegroup;
import com.indeed.imhotep.commands.RandomRegroup;
import com.indeed.imhotep.commands.RegexRegroup;
import com.indeed.imhotep.commands.Regroup;
import com.indeed.imhotep.commands.RegroupUncondictional;
import com.indeed.imhotep.commands.StringOrRegroup;
import com.indeed.imhotep.io.RequestTools;
import com.indeed.imhotep.protobuf.GroupMultiRemapMessage;
import com.indeed.imhotep.protobuf.StatsSortOrder;
import it.unimi.dsi.fastutil.longs.LongIterators;
import javafx.util.Pair;
import org.apache.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class BatchRemoteImhotepSession implements ImhotepSession {

    final RemoteImhotepMultiSession remoteImhotepMultiSession;

    private static final Logger log = Logger.getLogger(BatchRemoteImhotepSession.class);

    private final List<ImhotepCommand> commands = new ArrayList<ImhotepCommand>();

    private <T> T executeBatch() throws ImhotepOutOfMemoryException {
        if (commands.isEmpty()) {
            return null;
        }

        final ImhotepCommand<T> lastCommand = commands.get(commands.size() - 1);
        final T[] buffer = lastCommand.getExecutionBuffer(remoteImhotepMultiSession.sessions.length);

        remoteImhotepMultiSession.sendImhotepBatchRequest(buffer, commands, lastCommand);
        commands.clear();
        return lastCommand.combine(buffer);
    }

    public <T> T executeBatchNoMemoryException() {
        try {
            return executeBatch();
        } catch (ImhotepOutOfMemoryException e) {
            throw new RuntimeException(e);
        }
    }

    BatchRemoteImhotepSession(final RemoteImhotepMultiSession remoteImhotepMultiSession) {
        this.remoteImhotepMultiSession = remoteImhotepMultiSession;
    }

    @Override
    public String getSessionId() {
        return remoteImhotepMultiSession.getSessionId();
    }

    @Override
    public long getTotalDocFreq(final String[] intFields, final String[] stringFields) {
        executeBatchNoMemoryException();
        return remoteImhotepMultiSession.getTotalDocFreq(intFields, stringFields);
    }

    @Override
    public long[] getGroupStats(final List<String> stat) throws ImhotepOutOfMemoryException {
        commands.add(new GetGroupStats(stat));
        final GroupStatsIterator groupStatsIterator = (GroupStatsIterator) executeBatch();
        return LongIterators.unwrap(groupStatsIterator, groupStatsIterator.getNumGroups());
    }

    @Override
    public GroupStatsIterator getGroupStatsIterator(final List<String> stat) throws ImhotepOutOfMemoryException {
        commands.add(new GetGroupStats(stat));
        return (GroupStatsIterator) executeBatch();
    }

    @Override
    public FTGSIterator getFTGSIterator(final String[] intFields, final String[] stringFields, @Nullable final List<List<String>> stats) throws ImhotepOutOfMemoryException {
        executeBatch();
        return remoteImhotepMultiSession.getFTGSIterator(intFields, stringFields, stats);
    }

    @Override
    public FTGSIterator getFTGSIterator(final String[] intFields, final String[] stringFields, final long termLimit, @Nullable final List<List<String>> stats) throws ImhotepOutOfMemoryException {
        executeBatch();
        return remoteImhotepMultiSession.getFTGSIterator(intFields, stringFields, termLimit, stats);
    }

    @Override
    public FTGSIterator getFTGSIterator(final String[] intFields, final String[] stringFields, final long termLimit, final int sortStat, @Nullable final List<List<String>> stats, final StatsSortOrder statsSortOrder) throws ImhotepOutOfMemoryException {
        executeBatch();
        return remoteImhotepMultiSession.getFTGSIterator(intFields, stringFields, termLimit, sortStat, stats, statsSortOrder);
    }

    @Override
    public FTGSIterator getSubsetFTGSIterator(final Map<String, long[]> intFields, final Map<String, String[]> stringFields, @Nullable final List<List<String>> stats) throws ImhotepOutOfMemoryException {
        executeBatch();
        return remoteImhotepMultiSession.getSubsetFTGSIterator(intFields, stringFields, stats);
    }

    @Override
    public FTGSIterator getFTGSIterator(final FTGSParams params) throws ImhotepOutOfMemoryException {
        executeBatch();
        return remoteImhotepMultiSession.getFTGSIterator(params);
    }

    @Override
    public GroupStatsIterator getDistinct(final String field, final boolean isIntField) {
        executeBatchNoMemoryException();
        return remoteImhotepMultiSession.getDistinct(field, isIntField);
    }

    @Override
    public int regroup(final GroupMultiRemapRule[] rawRules) throws ImhotepOutOfMemoryException {
        commands.add(MultiRegroup.creatMultiRegroupCommand(rawRules, null));
        return -999;
    }

    @Override
    public int regroup(final int numRawRules, final Iterator<GroupMultiRemapRule> rawRules) throws ImhotepOutOfMemoryException {
        commands.add(MultiRegroup.createMultiRegroupCommand(numRawRules, rawRules, null));
        return -999;
    }

    @Override
    public int regroup(final GroupMultiRemapRule[] rawRules, final boolean errorOnCollisions) throws ImhotepOutOfMemoryException {
        commands.add(MultiRegroup.creatMultiRegroupCommand(rawRules, errorOnCollisions));
        return -999;
    }

    @Override
    public int regroupWithProtos(final GroupMultiRemapMessage[] rawRuleMessages, final boolean errorOnCollisions) throws ImhotepOutOfMemoryException {
        commands.add(MultiRegroup.createMultiRegreoupCommand(rawRuleMessages, errorOnCollisions));
        return -999;
    }

    @Override
    public int regroup(final int numRawRules, final Iterator<GroupMultiRemapRule> rawRules, final boolean errorOnCollisions) throws ImhotepOutOfMemoryException {
        commands.add(MultiRegroup.createMultiRegroupCommand(numRawRules, rawRules, errorOnCollisions));
        return -999;
    }

    @Override
    public int regroup(final GroupRemapRule[] rawRules) throws ImhotepOutOfMemoryException {
        commands.add(Regroup.createRegroup(rawRules));
        return -999;
    }

    @Override
    public int regroup2(final int numRawRules, final Iterator<GroupRemapRule> iterator) throws ImhotepOutOfMemoryException {
        commands.add(Regroup.createRegroup(numRawRules, iterator));
        return -999;
    }

    @Override
    public int regroup(final QueryRemapRule rule) throws ImhotepOutOfMemoryException {
        executeBatch();
        return remoteImhotepMultiSession.regroup(rule);
    }

    @Override
    public void intOrRegroup(final String field, final long[] terms, final int targetGroup, final int negativeGroup, final int positiveGroup) throws ImhotepOutOfMemoryException {
        commands.add(new IntOrRegroup(field, terms, targetGroup, negativeGroup, positiveGroup));
    }

    @Override
    public void stringOrRegroup(final String field, final String[] terms, final int targetGroup, final int negativeGroup, final int positiveGroup) throws ImhotepOutOfMemoryException {
        commands.add(new StringOrRegroup(field, terms, targetGroup, negativeGroup, positiveGroup));
    }

    @Override
    public void regexRegroup(final String field, final String regex, final int targetGroup, final int negativeGroup, final int positiveGroup) throws ImhotepOutOfMemoryException {
        commands.add(new RegexRegroup(field, regex, targetGroup, negativeGroup, positiveGroup));
    }

    @Override
    public void randomRegroup(final String field, final boolean isIntField, final String salt, final double p, final int targetGroup, final int negativeGroup, final int positiveGroup) throws ImhotepOutOfMemoryException {
        commands.add(new RandomRegroup(field, isIntField, salt, p, targetGroup, negativeGroup, positiveGroup));
    }

    @Override
    public void randomMultiRegroup(final String field, final boolean isIntField, final String salt, final int targetGroup, final double[] percentages, final int[] resultGroups) throws ImhotepOutOfMemoryException {
        commands.add(new RandomMultiRegroup(field, isIntField, salt, targetGroup, percentages, resultGroups));
    }

    @Override
    public void randomMetricRegroup(final List<String> stat, final String salt, final double p, final int targetGroup, final int negativeGroup, final int positiveGroup) throws ImhotepOutOfMemoryException {
        commands.add(new RandomMetricRegroup(stat, salt, p, targetGroup, negativeGroup, positiveGroup));
    }

    @Override
    public void randomMetricMultiRegroup(final List<String> stat, final String salt, final int targetGroup, final double[] percentages, final int[] resultGroups) throws ImhotepOutOfMemoryException {
        commands.add(new RandomMetricMultiRegroup(stat, salt, targetGroup, percentages, resultGroups));
    }

    @Override
    public int metricRegroup(final List<String> stat, final long min, final long max, final long intervalSize) throws ImhotepOutOfMemoryException {
        commands.add(MetricRegroup.createMetricRegroup(stat, min, max, intervalSize));
        return -999;
    }

    @Override
    public int metricRegroup(final List<String> stat, final long min, final long max, final long intervalSize, final boolean noGutters) throws ImhotepOutOfMemoryException {
        commands.add(MetricRegroup.createMetricRegroup(stat, min, max, intervalSize, noGutters));
        return -999;
    }

    @Override
    public int regroup(final int[] fromGroups, final int[] toGroups, final boolean filterOutNotTargeted) throws ImhotepOutOfMemoryException {
        commands.add(new RegroupUncondictional(fromGroups, toGroups, filterOutNotTargeted));
        return -999;
    }

    @Override
    public int metricFilter(final List<String> stat, final long min, final long max, final boolean negate) throws ImhotepOutOfMemoryException {
        executeBatch();
        return remoteImhotepMultiSession.metricFilter(stat, min, max, negate);
    }

    @Override
    public int metricFilter(final List<String> stat, final long min, final long max, final int targetGroup, final int negativeGroup, final int positiveGroup) throws ImhotepOutOfMemoryException {
        executeBatch();
        return remoteImhotepMultiSession.metricFilter(stat, min, max, targetGroup, negativeGroup, positiveGroup);
    }

    @Override
    public List<TermCount> approximateTopTerms(final String field, final boolean isIntField, final int k) {
        executeBatchNoMemoryException();
        return remoteImhotepMultiSession.approximateTopTerms(field, isIntField, k);
    }

    @Override
    public int pushStat(final String statName) throws ImhotepOutOfMemoryException {
        executeBatch();
        return remoteImhotepMultiSession.pushStat(statName);
    }

    @Override
    public int pushStats(final List<String> statNames) throws ImhotepOutOfMemoryException {
        executeBatch();
        return remoteImhotepMultiSession.pushStats(statNames);
    }

    @Override
    public int popStat() {
        executeBatchNoMemoryException();
        return remoteImhotepMultiSession.popStat();
    }

    @Override
    public int getNumStats() {
        executeBatchNoMemoryException();
        return remoteImhotepMultiSession.getNumStats();
    }

    @Override
    public int getNumGroups() {
        executeBatchNoMemoryException();
        return remoteImhotepMultiSession.getNumGroups();
    }

    @Override
    public void createDynamicMetric(final String name) throws ImhotepOutOfMemoryException {
        executeBatch();
        remoteImhotepMultiSession.createDynamicMetric(name);
    }

    @Override
    public void updateDynamicMetric(final String name, final int[] deltas) throws ImhotepOutOfMemoryException {
        executeBatch();
        remoteImhotepMultiSession.updateDynamicMetric(name, deltas);
    }

    @Override
    public void conditionalUpdateDynamicMetric(final String name, final RegroupCondition[] conditions, final int[] deltas) {
        executeBatchNoMemoryException();
        remoteImhotepMultiSession.conditionalUpdateDynamicMetric(name, conditions, deltas);
    }

    @Override
    public void groupConditionalUpdateDynamicMetric(final String name, final int[] groups, final RegroupCondition[] conditions, final int[] deltas) {
        executeBatchNoMemoryException();
        remoteImhotepMultiSession.groupConditionalUpdateDynamicMetric(name, groups, conditions, deltas);
    }

    @Override
    public void groupQueryUpdateDynamicMetric(final String name, final int[] groups, final Query[] conditions, final int[] deltas) throws ImhotepOutOfMemoryException {
        executeBatch();
        remoteImhotepMultiSession.groupQueryUpdateDynamicMetric(name, groups, conditions, deltas);
    }

    @Override
    public void close() {
        if (!commands.isEmpty()) {
            log.warn("Requested to close a session without using the regroup calls");
        }
        commands.clear();
        remoteImhotepMultiSession.close();
    }

    @Override
    public void resetGroups() throws ImhotepOutOfMemoryException {
        commands.clear();
        remoteImhotepMultiSession.resetGroups();
    }

    @Override
    public void rebuildAndFilterIndexes(final List<String> intFields, final List<String> stringFields) throws ImhotepOutOfMemoryException {
        executeBatch();
        remoteImhotepMultiSession.rebuildAndFilterIndexes(intFields, stringFields);
    }

    @Override
    public long getNumDocs() {
        executeBatchNoMemoryException();
        return remoteImhotepMultiSession.getNumDocs();
    }

    @Override
    public PerformanceStats getPerformanceStats(final boolean reset) {
        return remoteImhotepMultiSession.getPerformanceStats(reset);
    }

    @Override
    public PerformanceStats closeAndGetPerformanceStats() {
        return remoteImhotepMultiSession.closeAndGetPerformanceStats();
    }

    @Override
    public void addObserver(final Instrumentation.Observer observer) {
        remoteImhotepMultiSession.addObserver(observer);
    }

    @Override
    public void removeObserver(final Instrumentation.Observer observer) {
        remoteImhotepMultiSession.removeObserver(observer);
    }

    public long getTempFilesBytesWritten() {
        return remoteImhotepMultiSession.getTempFilesBytesWritten();
    }

    public int regroupWithRuleSender(final RequestTools.GroupMultiRemapRuleSender ruleSender, final boolean errorOnCollisions) throws ImhotepOutOfMemoryException {
        commands.add(MultiRegroup.createMultiRegroupCommand(ruleSender, errorOnCollisions));
        return -999;
    }
}
