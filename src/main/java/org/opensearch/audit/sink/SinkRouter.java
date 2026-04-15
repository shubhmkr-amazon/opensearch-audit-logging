/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.audit.sink;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.opensearch.audit.config.AuditConfig;
import org.opensearch.audit.event.AuditCategory;
import org.opensearch.audit.event.AuditEvent;
import org.opensearch.threadpool.ThreadPool;

/**
 * Routes audit events to all enabled sinks asynchronously via the generic thread pool.
 * Tracks per-category and per-sink statistics.
 */
public class SinkRouter implements Closeable {

    private static final Logger log = LogManager.getLogger(SinkRouter.class);

    private final List<AuditSink> sinks;
    private final ThreadPool threadPool;
    private final AuditConfig config;
    private final Map<AuditCategory, AtomicLong> categoryStats = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sinkStats = new ConcurrentHashMap<>();
    private final AtomicLong totalEvents = new AtomicLong(0);
    private final AtomicLong failedEvents = new AtomicLong(0);

    public SinkRouter(List<AuditSink> sinks, ThreadPool threadPool, AuditConfig config) {
        this.sinks = sinks;
        this.threadPool = threadPool;
        this.config = config;
    }

    /**
     * Route an audit event to all enabled sinks. Dispatched asynchronously.
     */
    public void route(AuditEvent event) {
        if (!config.isEnabled()) {
            return;
        }
        if (config.getDisabledCategories().contains(event.getCategory())) {
            return;
        }

        totalEvents.incrementAndGet();
        categoryStats.computeIfAbsent(event.getCategory(), k -> new AtomicLong(0)).incrementAndGet();

        threadPool.generic().execute(() -> {
            for (AuditSink sink : sinks) {
                try {
                    if (sink.store(event)) {
                        sinkStats.computeIfAbsent(sink.getName(), k -> new AtomicLong(0)).incrementAndGet();
                    } else {
                        failedEvents.incrementAndGet();
                    }
                } catch (Exception e) {
                    failedEvents.incrementAndGet();
                    log.warn("Failed to store audit event in sink [{}]", sink.getName(), e);
                }
            }
        });
    }

    public List<AuditSink> getSinks() {
        return Collections.unmodifiableList(sinks);
    }

    public Map<AuditCategory, AtomicLong> getCategoryStats() {
        return Collections.unmodifiableMap(categoryStats);
    }

    public Map<String, AtomicLong> getSinkStats() {
        return Collections.unmodifiableMap(sinkStats);
    }

    public long getTotalEvents() {
        return totalEvents.get();
    }

    public long getFailedEvents() {
        return failedEvents.get();
    }

    @Override
    public void close() throws IOException {
        for (AuditSink sink : sinks) {
            try {
                sink.close();
            } catch (IOException e) {
                log.warn("Error closing sink [{}]", sink.getName(), e);
            }
        }
    }
}
