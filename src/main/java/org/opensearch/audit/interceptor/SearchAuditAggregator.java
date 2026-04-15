/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.audit.interceptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.opensearch.audit.event.AuditCategory;
import org.opensearch.audit.event.AuditEvent;
import org.opensearch.audit.sink.SinkRouter;

/**
 * Aggregates shard-level search audit events into a single event per search request.
 * <p>
 * Without aggregation, a search hitting N shards generates 2N audit events
 * (one query + one fetch per shard). This aggregator collapses them into one
 * event per search request, reducing audit volume by O(shard_count).
 * <p>
 * <b>Patent-relevant mechanism:</b> Request-scoped audit aggregation in a distributed
 * search engine. Each shard-level callback feeds into a per-request accumulator keyed
 * by the search context ID. When the last shard reports, the aggregator emits a single
 * consolidated event containing shard count, index list, total latency, and the original
 * query source.
 * <p>
 * Stale entries are evicted after a configurable timeout to prevent memory leaks
 * from abandoned searches.
 */
public class SearchAuditAggregator {

    private final SinkRouter sinkRouter;
    private final long staleThresholdMs;
    private final Map<Long, RequestAccumulator> activeRequests = new ConcurrentHashMap<>();

    public SearchAuditAggregator(SinkRouter sinkRouter, long staleThresholdMs) {
        this.sinkRouter = sinkRouter;
        this.staleThresholdMs = staleThresholdMs;
    }

    /**
     * Record a shard query phase completion.
     */
    public void onShardQuery(long searchContextId, String index, String querySource, long tookNanos) {
        RequestAccumulator acc = activeRequests.computeIfAbsent(searchContextId, k -> new RequestAccumulator());
        acc.addIndex(index);
        acc.addQueryNanos(tookNanos);
        acc.queryShards.incrementAndGet();
        if (querySource != null && acc.querySource == null) {
            acc.querySource = querySource;
        }
    }

    /**
     * Record a shard fetch phase completion.
     */
    public void onShardFetch(long searchContextId, String index, long tookNanos) {
        RequestAccumulator acc = activeRequests.get(searchContextId);
        if (acc == null) {
            // Query phase was already evicted or never recorded — emit standalone event
            emitStandalone(index, tookNanos);
            return;
        }
        acc.addFetchNanos(tookNanos);
        acc.fetchShards.incrementAndGet();
    }

    /**
     * Called when a search request completes. Emits the aggregated audit event.
     */
    public void complete(long searchContextId) {
        RequestAccumulator acc = activeRequests.remove(searchContextId);
        if (acc == null) {
            return;
        }
        emitAggregated(acc);
    }

    /**
     * Evict stale entries from abandoned or timed-out searches.
     * Should be called periodically (e.g., from the drain thread).
     */
    public void evictStale() {
        long now = System.currentTimeMillis();
        var it = activeRequests.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            RequestAccumulator acc = entry.getValue();
            if (now - acc.createdAt > staleThresholdMs) {
                it.remove();
                // Emit what we have before evicting
                emitAggregated(acc);
            }
        }
    }

    private void emitAggregated(RequestAccumulator acc) {
        long totalNanos = acc.totalQueryNanos.get() + acc.totalFetchNanos.get();
        int totalShards = acc.queryShards.get();

        AuditEvent event = AuditEvent
            .builder(AuditCategory.DOCUMENT_READ)
            .origin("SEARCH")
            .requestAction("indices:data/read/search")
            .traceIndices(acc.getIndices())
            .requestBody(buildAggregatedBody(acc, totalNanos, totalShards))
            .build();
        sinkRouter.route(event);
    }

    private void emitStandalone(String index, long tookNanos) {
        AuditEvent event = AuditEvent
            .builder(AuditCategory.DOCUMENT_READ)
            .origin("SEARCH")
            .requestAction("indices:data/read/search[phase/fetch]")
            .traceIndices(List.of(index))
            .build();
        sinkRouter.route(event);
    }

    private String buildAggregatedBody(RequestAccumulator acc, long totalNanos, int totalShards) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"shard_count\":").append(totalShards);
        sb.append(",\"query_time_ms\":").append(acc.totalQueryNanos.get() / 1_000_000);
        sb.append(",\"fetch_time_ms\":").append(acc.totalFetchNanos.get() / 1_000_000);
        sb.append(",\"fetch_shards\":").append(acc.fetchShards.get());
        if (acc.querySource != null) {
            sb.append(",\"source\":\"").append(escapeJson(acc.querySource)).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    public int getActiveRequestCount() {
        return activeRequests.size();
    }

    /**
     * Per-request accumulator that collects shard-level metrics.
     */
    static class RequestAccumulator {
        final long createdAt = System.currentTimeMillis();
        final AtomicInteger queryShards = new AtomicInteger(0);
        final AtomicInteger fetchShards = new AtomicInteger(0);
        final AtomicLong totalQueryNanos = new AtomicLong(0);
        final AtomicLong totalFetchNanos = new AtomicLong(0);
        volatile String querySource;
        private final ConcurrentHashMap<String, Boolean> indices = new ConcurrentHashMap<>();

        void addIndex(String index) {
            indices.put(index, Boolean.TRUE);
        }

        void addQueryNanos(long nanos) {
            totalQueryNanos.addAndGet(nanos);
        }

        void addFetchNanos(long nanos) {
            totalFetchNanos.addAndGet(nanos);
        }

        List<String> getIndices() {
            return new ArrayList<>(indices.keySet());
        }
    }
}
