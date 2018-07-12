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

package com.indeed.imhotep.shardmaster;

import com.google.common.base.Function;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.indeed.imhotep.ShardDir;
import com.indeed.imhotep.ShardInfo;
import com.indeed.imhotep.client.Host;
import com.indeed.imhotep.shardmaster.model.ShardAssignmentInfo;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.Duration;

import javax.annotation.Nonnull;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 *
 */
class InMemoryShardAssignmentInfoDao implements ShardAssignmentInfoDao {
    private static final Logger LOGGER = Logger.getLogger(InMemoryShardAssignmentInfoDao.class);
    private final LoadingCache<String, ShardAssignments> datasetAssignments;
    private final LoadingCache<Host, NodeAssignments> nodeAssignments;
    private final Map<String, List<Host>> shardAssignments;

    private static class TimestampedAssignment {
        final long updateTime;
        final ShardAssignmentInfo assignmentInfo;

        TimestampedAssignment(final long updateTime, final ShardAssignmentInfo assignmentInfo) {
            this.updateTime = updateTime;
            this.assignmentInfo = assignmentInfo;
        }
    }

    private class NodeAssignments {
        private final Set<TimestampedAssignment> assignments = new HashSet<>();

        void remove(final TimestampedAssignment assignment) {
            assignments.remove(assignment);
        }

        void add(final TimestampedAssignment assignment) {
            assignments.add(assignment);
        }

        Iterable<ShardAssignmentInfo> getAssignments() {
            return FluentIterable.from(assignments).transform(new Function<TimestampedAssignment, ShardAssignmentInfo>() {
                @Override
                public ShardAssignmentInfo apply(final TimestampedAssignment input) {
                    return input.assignmentInfo;
                }
            }).toSet();
        }
    }

    private class ShardAssignments {
        private final long stalenessThreshold;
        private final Queue<TimestampedAssignment> assignments = new LinkedList<>();

        ShardAssignments(final long stalenessThreshold) {
            this.stalenessThreshold = stalenessThreshold;
        }

        void addAll(final long timestamp, final Iterable<ShardAssignmentInfo> elements) {
            for (final ShardAssignmentInfo element : elements) {
                final TimestampedAssignment timestampedAssignment = new TimestampedAssignment(timestamp, element);
                assignments.add(timestampedAssignment);
                try {
                    nodeAssignments.get(element.getAssignedNode()).add(timestampedAssignment);
                } catch (final ExecutionException e) {
                    LOGGER.warn("Failed to add assignment " + element, e);
                }
            }

            while (!assignments.isEmpty()) {
                final TimestampedAssignment head = assignments.element();
                if (timestamp >= (head.updateTime + stalenessThreshold)) {
                    final TimestampedAssignment staleAssignment = assignments.poll();
                    try {
                        nodeAssignments.get(staleAssignment.assignmentInfo.getAssignedNode()).remove(staleAssignment);
                    } catch (final ExecutionException e) {
                        LOGGER.warn("Failed to delete assignment " + staleAssignment.assignmentInfo, e);
                    }

                } else {
                    break;
                }
            }
        }
    }

    InMemoryShardAssignmentInfoDao(final Duration stalenessThreshold) {
        datasetAssignments = CacheBuilder
                .newBuilder().build(new CacheLoader<String, ShardAssignments>() {
                    @Override
                    public ShardAssignments load(@Nonnull final String key) {
                        return new ShardAssignments(stalenessThreshold.getMillis());
                    }
                });
        nodeAssignments = CacheBuilder
                .newBuilder().build(new CacheLoader<Host, NodeAssignments>() {
                    @Override
                    public NodeAssignments load(@Nonnull final Host key) {
                        return new NodeAssignments();
                    }
                });
        shardAssignments = new HashMap<>();

    }

    @Override
    public synchronized Iterable<ShardAssignmentInfo> getAssignments(final Host node) {
        try {
            return nodeAssignments.get(node).getAssignments();
        } catch (final ExecutionException e) {
            throw new IllegalStateException("Unexpected error while getting assignments for " + node, e);
        }
    }

    @Override
    public synchronized void updateAssignments(final String dataset, final DateTime timestamp, final Iterable<ShardAssignmentInfo> assignmentInfos) {
        try {
            datasetAssignments.get(dataset).addAll(timestamp.getMillis(), assignmentInfos);
            for(ShardAssignmentInfo info: assignmentInfos) {
                if(!shardAssignments.containsKey(info.getShardPath())) {
                    shardAssignments.put(info.getShardPath(), new ArrayList<>());
                }
                shardAssignments.get(info.getShardPath()).add(info.getAssignedNode());
            }
        } catch (final ExecutionException e) {
            LOGGER.warn("Unexpected error while updating assignments for " + dataset, e);
        }
    }

    @Override
    public List<Host> getAssignments(String path) {
        System.out.println(shardAssignments);
        return shardAssignments.get(path);
    }
}
