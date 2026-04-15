/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.audit.interceptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.opensearch.audit.config.AuditConfig;
import org.opensearch.audit.event.AuditCategory;
import org.opensearch.audit.event.AuditEvent;
import org.opensearch.audit.sink.AuditSink;
import org.opensearch.audit.sink.SinkRouter;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;

public class SearchAuditAggregatorTests extends OpenSearchTestCase {

    private ThreadPool threadPool;
    private final List<SinkRouter> routers = new ArrayList<>();

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool("aggregator-test");
    }

    @Override
    public void tearDown() throws Exception {
        for (SinkRouter router : routers) {
            router.close();
        }
        routers.clear();
        ThreadPool.terminate(threadPool, 10, TimeUnit.SECONDS);
        super.tearDown();
    }

    private SinkRouter createRouter(List<AuditEvent> captured) {
        AuditSink captureSink = new AuditSink() {
            @Override
            public boolean store(AuditEvent event) {
                synchronized (captured) {
                    captured.add(event);
                }
                return true;
            }

            @Override
            public boolean isHealthy() {
                return true;
            }

            @Override
            public String getName() {
                return "capture";
            }

            @Override
            public void close() {}
        };

        AuditConfig config = new AuditConfig(Settings.builder().put("plugins.audit.enabled", true).build());
        SinkRouter router = new SinkRouter(List.of(captureSink), threadPool, config);
        routers.add(router);
        return router;
    }

    public void testAggregatesMultipleShardsIntoOneEvent() throws Exception {
        List<AuditEvent> captured = new ArrayList<>();
        SinkRouter router = createRouter(captured);
        SearchAuditAggregator aggregator = new SearchAuditAggregator(router, 30000);

        long searchId = 42L;

        // Simulate 5 shards reporting query phase
        aggregator.onShardQuery(searchId, "index-a", "{\"match_all\":{}}", 1_000_000);
        aggregator.onShardQuery(searchId, "index-a", null, 2_000_000);
        aggregator.onShardQuery(searchId, "index-b", null, 1_500_000);
        aggregator.onShardQuery(searchId, "index-a", null, 800_000);
        aggregator.onShardQuery(searchId, "index-b", null, 1_200_000);

        // Simulate 5 shards reporting fetch phase
        for (int i = 0; i < 5; i++) {
            aggregator.onShardFetch(searchId, "index-a", 500_000);
        }

        assertEquals(1, aggregator.getActiveRequestCount());

        // Complete the search — should emit exactly one event
        aggregator.complete(searchId);

        // Give drain thread time to flush
        assertBusy(() -> {
            synchronized (captured) {
                assertFalse(captured.isEmpty());
            }
        }, 5, TimeUnit.SECONDS);

        assertEquals(0, aggregator.getActiveRequestCount());

        synchronized (captured) {
            assertEquals(1, captured.size());
            AuditEvent event = captured.get(0);
            assertEquals(AuditCategory.DOCUMENT_READ, event.getCategory());
            assertEquals("indices:data/read/search", event.getRequestAction());
            assertTrue(event.getTraceIndices().contains("index-a"));
            assertTrue(event.getTraceIndices().contains("index-b"));
            assertTrue(event.getRequestBody().contains("\"shard_count\":5"));
            assertTrue(event.getRequestBody().contains("\"fetch_shards\":5"));
        }
    }

    public void testStaleEviction() throws Exception {
        List<AuditEvent> captured = new ArrayList<>();
        SinkRouter router = createRouter(captured);
        SearchAuditAggregator aggregator = new SearchAuditAggregator(router, 1);

        aggregator.onShardQuery(99L, "stale-index", "query", 1_000_000);
        assertEquals(1, aggregator.getActiveRequestCount());

        Thread.sleep(50);
        aggregator.evictStale();

        assertEquals(0, aggregator.getActiveRequestCount());
        assertBusy(() -> {
            synchronized (captured) {
                assertFalse(captured.isEmpty());
            }
        }, 5, TimeUnit.SECONDS);
    }

    public void testCompleteWithUnknownIdIsNoOp() throws Exception {
        List<AuditEvent> captured = new ArrayList<>();
        SinkRouter router = createRouter(captured);
        SearchAuditAggregator aggregator = new SearchAuditAggregator(router, 30000);

        aggregator.complete(999L);
        assertEquals(0, aggregator.getActiveRequestCount());
    }

    public void testFetchWithoutQueryEmitsStandalone() throws Exception {
        List<AuditEvent> captured = new ArrayList<>();
        SinkRouter router = createRouter(captured);
        SearchAuditAggregator aggregator = new SearchAuditAggregator(router, 30000);

        aggregator.onShardFetch(123L, "orphan-index", 500_000);

        assertBusy(() -> {
            synchronized (captured) {
                assertFalse(captured.isEmpty());
            }
        }, 5, TimeUnit.SECONDS);

        synchronized (captured) {
            assertEquals(1, captured.size());
            assertEquals("indices:data/read/search[phase/fetch]", captured.get(0).getRequestAction());
        }
    }
}
