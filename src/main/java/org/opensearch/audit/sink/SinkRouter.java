/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.audit.sink;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.audit.config.AuditConfig;
import org.opensearch.audit.event.AuditCategory;
import org.opensearch.audit.event.AuditEvent;
import org.opensearch.threadpool.ThreadPool;

/**
 * Routes audit events to all enabled sinks via a lock-free ring buffer.
 * <p>
 * Instead of dispatching each event to the generic thread pool, events are
 * written into an {@link AuditRingBuffer} and a single drain thread batches
 * them to sinks. This eliminates per-event thread overhead and enables
 * priority-aware backpressure under load.
 */
public class SinkRouter implements Closeable {

    private static final Logger log = LogManager.getLogger(SinkRouter.class);

    private final List<AuditSink> sinks;
    private final AuditConfig config;
    private final AuditRingBuffer ringBuffer;
    private final Map<AuditCategory, AtomicLong> categoryStats = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sinkStats = new ConcurrentHashMap<>();
    private final AtomicLong totalEvents = new AtomicLong(0);
    private final AtomicLong failedEvents = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private static final ThreadLocal<Boolean> IN_AUDIT = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final Thread drainThread;
    private final int batchSize;
    private final long flushIntervalNanos;
    private volatile Runnable periodicTask;

    public SinkRouter(List<AuditSink> sinks, ThreadPool threadPool, AuditConfig config) {
        this.sinks = sinks;
        this.config = config;
        this.ringBuffer = new AuditRingBuffer(config.getRingBufferCapacity());
        this.batchSize = config.getRingBufferBatchSize();
        this.flushIntervalNanos = config.getRingBufferFlushIntervalMs() * 1_000_000L;

        // Pre-populate stats maps so hot path never allocates
        for (AuditCategory cat : AuditCategory.values()) {
            categoryStats.put(cat, new AtomicLong(0));
        }
        for (AuditSink sink : sinks) {
            sinkStats.put(sink.getName(), new AtomicLong(0));
        }

        this.drainThread = new Thread(this::drainLoop, "audit-drain");
        this.drainThread.setDaemon(true);
        this.drainThread.start();
    }

    /**
     * Route an audit event into the ring buffer.
     * Non-blocking — returns immediately. Events may be dropped under backpressure.
     */
    public void route(AuditEvent event) {
        if (!config.isEnabled()) {
            return;
        }
        if (IN_AUDIT.get()) {
            return;
        }
        IN_AUDIT.set(Boolean.TRUE);
        try {
            if (config.getDisabledCategories().contains(event.getCategory())) {
                return;
            }

            totalEvents.incrementAndGet();
            categoryStats.get(event.getCategory()).incrementAndGet();

            if (!ringBuffer.offer(event)) {
                failedEvents.incrementAndGet();
            }
        } finally {
            IN_AUDIT.set(Boolean.FALSE);
        }
    }

    /**
     * Register a task to run periodically during drain idle cycles.
     * Used to wire in SearchAuditAggregator.evictStale().
     */
    public void setPeriodicTask(Runnable task) {
        this.periodicTask = task;
    }

    private void drainLoop() {
        List<AuditEvent> batch = new ArrayList<>(batchSize);
        long lastPeriodicRun = System.nanoTime();
        long periodicIntervalNanos = 10_000_000_000L; // 10 seconds
        while (running.get()) {
            batch.clear();
            int drained = ringBuffer.drainTo(batch, batchSize);

            if (drained > 0) {
                flushBatch(batch);
            } else {
                LockSupport.parkNanos(flushIntervalNanos);
            }

            // Run periodic task (e.g., stale eviction) every ~10 seconds
            long now = System.nanoTime();
            if (periodicTask != null && now - lastPeriodicRun > periodicIntervalNanos) {
                try {
                    periodicTask.run();
                } catch (Exception e) {
                    log.debug("Periodic task failed", e);
                }
                lastPeriodicRun = now;
            }
        }

        // Final drain on shutdown
        batch.clear();
        ringBuffer.drainTo(batch, ringBuffer.getCapacity());
        if (!batch.isEmpty()) {
            flushBatch(batch);
        }
    }

    private void flushBatch(List<AuditEvent> batch) {
        for (AuditSink sink : sinks) {
            try {
                int stored = sink.storeBatch(batch);
                sinkStats.get(sink.getName()).addAndGet(stored);
                long failed = batch.size() - stored;
                if (failed > 0) {
                    failedEvents.addAndGet(failed);
                }
            } catch (Exception e) {
                failedEvents.addAndGet(batch.size());
                log.warn("Failed to flush batch to sink [{}]", sink.getName(), e);
            }
        }
    }

    public AuditRingBuffer getRingBuffer() {
        return ringBuffer;
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
        running.set(false);
        LockSupport.unpark(drainThread);
        try {
            drainThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (AuditSink sink : sinks) {
            try {
                sink.close();
            } catch (IOException e) {
                log.warn("Error closing sink [{}]", sink.getName(), e);
            }
        }
    }
}
