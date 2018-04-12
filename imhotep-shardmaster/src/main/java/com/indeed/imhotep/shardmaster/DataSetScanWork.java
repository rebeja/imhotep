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

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.indeed.imhotep.fs.RemoteCachingPath;
import org.apache.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

/**
 * @author kenh
 */

class DataSetScanWork implements Callable<DataSetScanWork.Result> {
    private static final Logger LOGGER = Logger.getLogger(DataSetScanWork.class);

    private final RemoteCachingPath datasetsDir;
    private final ShardFilter shardFilter;
    private final ExecutorService taskExecutorService;
    private final ShardScanWork.Builder shardScanBuilder;

    DataSetScanWork(final RemoteCachingPath datasetsDir, final ShardFilter shardFilter,
                    final ExecutorService taskExecutorService,
                    final ShardScanWork.Builder shardScanBuilder) {
        this.datasetsDir = datasetsDir;
        this.shardFilter = shardFilter;
        this.taskExecutorService = taskExecutorService;
        this.shardScanBuilder = shardScanBuilder.copy();
    }

    @Override
    public Result call() {
        LOGGER.info("Scanning " + datasetsDir + " for all datasets");
        final ImmutableMap.Builder<String, ListenableFuture<ShardScanWork.Result>> datasetShards = ImmutableMap.builder();
        for (final RemoteCachingPath datasetPath : new DataSetScanner(datasetsDir, shardFilter)) {

            final ListenableFutureTask<ShardScanWork.Result> scanShardTask = ListenableFutureTask.create(
                    shardScanBuilder
                            .setDatasetDir(datasetPath)
                            .setShardFilter(shardFilter)
                            .build()
            );

            taskExecutorService.submit(
                    scanShardTask
            );
            datasetShards.put(datasetPath.getFileName().toString(), scanShardTask);
        }
        LOGGER.info("Scanned " + datasetsDir + " for all datasets");
        return new Result(datasetShards.build());
    }

    static class Result {
        private final Map<String, ListenableFuture<ShardScanWork.Result>> datasetShards;

        Result(final Map<String, ListenableFuture<ShardScanWork.Result>> datasetShards) {
            this.datasetShards = datasetShards;
        }

        ListenableFuture<List<ShardScanWork.Result>> getAllShards() {
            return Futures.allAsList(datasetShards.values());
        }
    }
}
