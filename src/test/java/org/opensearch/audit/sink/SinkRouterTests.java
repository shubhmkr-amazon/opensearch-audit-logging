/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.audit.sink;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
        AtomicInteger storeCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        AuditSink mockSink = new AuditSink() {
            @Override
            public boolean store(AuditEvent event) {
                storeCount.incrementAndGet();
                latch.countDown();
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

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, storeCount.get());
        assertEquals(1, router.getTotalEvents());
    }

    public void testDisabledAuditSkipsRouting() {
        AuditConfig config = new AuditConfig(Settings.builder().put("plugins.audit.enabled", false).build());
        SinkRouter router = new SinkRouter(List.of(), threadPool, config);

        AuditEvent event = AuditEvent.builder(AuditCategory.REST_REQUEST).build();
        router.route(event);

        assertEquals(0, router.getTotalEvents());
    }

    public void testDisabledCategorySkipsRouting() {
        AuditConfig config = new AuditConfig(
            Settings.builder().putList("plugins.audit.disabled_rest_categories", "AUTHENTICATED").build()
        );
        SinkRouter router = new SinkRouter(List.of(), threadPool, config);

        AuditEvent event = AuditEvent.builder(AuditCategory.AUTHENTICATED).build();
        router.route(event);

        assertEquals(0, router.getTotalEvents());
    }
}
