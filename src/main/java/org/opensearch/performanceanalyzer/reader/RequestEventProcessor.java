/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.performanceanalyzer.reader;


import java.io.File;
import java.sql.Connection;
import java.util.Map;
import java.util.NavigableMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.BatchBindStep;
import org.opensearch.performanceanalyzer.commons.event_process.Event;
import org.opensearch.performanceanalyzer.commons.event_process.EventProcessor;
import org.opensearch.performanceanalyzer.commons.metrics.AllMetrics;
import org.opensearch.performanceanalyzer.commons.metrics.PerformanceAnalyzerMetrics;

public class RequestEventProcessor implements EventProcessor {

    private static final Logger LOG = LogManager.getLogger(RequestEventProcessor.class);

    private ShardRequestMetricsSnapshot rqSnap;
    private BatchBindStep handle;
    private long startTime;
    private long endTime;

    private RequestEventProcessor(ShardRequestMetricsSnapshot rqSnap) {
        this.rqSnap = rqSnap;
    }

    static RequestEventProcessor buildRequestMetricEventsProcessor(
            long currWindowStartTime,
            long currWindowEndTime,
            Connection conn,
            NavigableMap<Long, ShardRequestMetricsSnapshot> shardRqMetricsMap)
            throws Exception {
        if (shardRqMetricsMap.get(currWindowStartTime) == null) {
            ShardRequestMetricsSnapshot rqSnap =
                    new ShardRequestMetricsSnapshot(conn, currWindowStartTime);
            Map.Entry<Long, ShardRequestMetricsSnapshot> entry = shardRqMetricsMap.lastEntry();
            if (entry != null) {
                rqSnap.rolloverInflightRequests(entry.getValue());
            }

            shardRqMetricsMap.put(currWindowStartTime, rqSnap);
            return new RequestEventProcessor(rqSnap);
        } else {
            return new RequestEventProcessor(shardRqMetricsMap.get(currWindowStartTime));
        }
    }

    @Override
    public boolean shouldProcessEvent(Event event) {
        if (event.key.contains(PerformanceAnalyzerMetrics.sShardBulkPath)
                || event.key.contains(PerformanceAnalyzerMetrics.sShardFetchPath)
                || event.key.contains(PerformanceAnalyzerMetrics.sShardQueryPath)) {
            return true;
        }
        return false;
    }

    public void initializeProcessing(long startTime, long endTime) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.handle = rqSnap.startBatchPut();
    }

    public void finalizeProcessing() {
        if (handle.size() > 0) {
            handle.execute();
        }
        // LOG.info("Final request metrics {}", rqSnap.fetchAll());
    }

    public void processEvent(Event event) {
        handleOpenSearchMetrics(event);
        // Flush data to sqlite when batch size is 500
        if (handle.size() == 500) {
            handle.execute();
            handle = rqSnap.startBatchPut();
        }
    }

    @Override
    public void commitBatchIfRequired() {
        if (handle.size() > BATCH_LIMIT) {
            handle.execute();
            handle = rqSnap.startBatchPut();
        }
    }

    private void handleOpenSearchMetrics(Event entry) {
        // operation is of the form - shardBulk, shardSearch etc..
        // for (Event entry: dataEntries) {

        // For a key as : threads/29013/shardbulk/806214/finish
        // The elements in the items array will be
        // [threads, 29013, shardbulk, 806214, finish]
        // we are interested in the last element: start and finish.
        String[] items = entry.key.split(File.separatorChar == '\\' ? "\\\\" : File.separator);
        // This is for readability.
        String startOrEnd = items[4];
        Map<String, String> keyValueMap = ReaderMetricsProcessor.extractEntryData(entry.value);
        if (startOrEnd.equals(PerformanceAnalyzerMetrics.START_FILE_NAME)) {
            emitStartMetric(items, keyValueMap);
        } else if (startOrEnd.equals(PerformanceAnalyzerMetrics.FINISH_FILE_NAME)) {
            emitFinishMetric(items, keyValueMap);
        }
    }

    private void emitStartMetric(String[] metricKeyPathElements, Map<String, String> keyValueMap) {
        long startTime =
                Long.parseLong(keyValueMap.get(AllMetrics.ShardBulkMetric.START_TIME.toString()));
        long docCount =
                Long.parseLong(
                        keyValueMap.computeIfAbsent(
                                AllMetrics.ShardBulkMetric.ITEM_COUNT.toString(), k -> "0"));

        // Here missing key is okay and null is handled.
        String indexName = keyValueMap.get(AllMetrics.ShardBulkDimension.INDEX_NAME.toString());
        String shardId = keyValueMap.get(AllMetrics.ShardBulkDimension.SHARD_ID.toString());
        String primary =
                getPrimary(keyValueMap.get(AllMetrics.ShardBulkDimension.PRIMARY.toString()));

        // For a key as : threads/29013/shardbulk/806214/finish
        // The elements in the items array will be
        // [threads, 29013, shardbulk, 806214, finish]
        String threadId = metricKeyPathElements[1];
        String operation = metricKeyPathElements[2];
        String rid = metricKeyPathElements[3];
        handle.bind(
                shardId, indexName, rid, threadId, operation, primary, startTime, null, docCount);
    }

    private String getPrimary(String primary) {
        return primary == null ? "NA" : (primary.equals("true") ? "primary" : "replica");
    }

    private void emitFinishMetric(String[] metricKeyPathElements, Map<String, String> keyValueMap) {
        long finishTime =
                Long.parseLong(keyValueMap.get(AllMetrics.ShardBulkMetric.FINISH_TIME.toString()));
        String indexName = keyValueMap.get(AllMetrics.ShardBulkDimension.INDEX_NAME.toString());
        String shardId = keyValueMap.get(AllMetrics.ShardBulkDimension.SHARD_ID.toString());
        String primary =
                getPrimary(keyValueMap.get(AllMetrics.ShardBulkDimension.PRIMARY.toString()));
        // For a key as : threads/29013/shardbulk/806214/finish
        // The elements in the items array will be
        // [threads, 29013, shardbulk, 806214, finish]
        String threadId = metricKeyPathElements[1];
        String operation = metricKeyPathElements[2];
        String rid = metricKeyPathElements[3];
        handle.bind(shardId, indexName, rid, threadId, operation, primary, null, finishTime, null);
    }
}
