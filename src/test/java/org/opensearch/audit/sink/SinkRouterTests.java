/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.audit.sink;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.opensearch.audit.config.AuditConfig;
import org.opensearch.audit.event.AuditCategory;
import org.opensearch.audit.event.AuditEvent;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;

public class SinkRouterTests extends OpenSearchTestCase {

    private ThreadPool threadPool;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool("sink-router-test");
    }

    @Override
    public void tearDown() throws Exception {
        ThreadPool.terminate(threadPool, 10, TimeUnit.SECONDS);
        super.tearDown();
    }

    public void testRouteToSink() throws Exception {
        List<AuditEvent> stored = new ArrayList<>();

        AuditSink mockSink = new AuditSink() {
            @Override
            public boolean store(AuditEvent event) {
                stored.add(event);
                return true;
            }

            @Override
            public boolean isHealthy() {
                return true;
            }

            @Override
            public String getName() {
                return "mock";
            }

            @Override
            public void close() {}
        };

        AuditConfig config = new AuditConfig(Settings.builder().put("plugins.audit.enabled", true).build());
        SinkRouter router = new SinkRouter(List.of(mockSink), threadPool, config);

        AuditEvent event = AuditEvent.builder(AuditCategory.REST_REQUEST).requestAction("test").build();
        router.route(event);

        // Wait for drain thread to flush
        assertBusy(() -> assertFalse(stored.isEmpty()), 5, TimeUnit.SECONDS);
        assertEquals(1, stored.size());
        assertEquals(1, router.getTotalEvents());

        router.close();
    }

    public void testDisabledAuditSkipsRouting() throws Exception {
        AuditConfig config = new AuditConfig(Settings.builder().put("plugins.audit.enabled", false).build());
        SinkRouter router = new SinkRouter(List.of(), threadPool, config);

        AuditEvent event = AuditEvent.builder(AuditCategory.REST_REQUEST).build();
        router.route(event);

        assertEquals(0, router.getTotalEvents());
        router.close();
    }

    public void testDisabledCategorySkipsRouting() throws Exception {
        AuditConfig config = new AuditConfig(Settings.builder().putList("plugins.audit.disabled_rest_categories", "AUTHENTICATED").build());
        SinkRouter router = new SinkRouter(List.of(), threadPool, config);

        AuditEvent event = AuditEvent.builder(AuditCategory.AUTHENTICATED).build();
        router.route(event);

        assertEquals(0, router.getTotalEvents());
        router.close();
    }
}
