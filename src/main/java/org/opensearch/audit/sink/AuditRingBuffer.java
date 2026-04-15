/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.audit.sink;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.audit.event.AuditEvent;
import org.opensearch.audit.event.AuditEventPriority;

/**
 * Lock-free ring buffer with priority-aware backpressure for audit events.
 * <p>
 * Producers (interceptors) write events into the ring via {@link #offer(AuditEvent)}.
 * A single drain thread calls {@link #drainTo(List, int)} to batch-flush to sinks.
 * <p>
 * <b>Priority backpressure:</b> As the buffer fills, low-priority events are shed first:
 * <ul>
 *   <li>Below 50% full: all events accepted</li>
 *   <li>50-75% full: LOW priority events dropped</li>
 *   <li>75-90% full: LOW and NORMAL events dropped</li>
 *   <li>90-100% full: only CRITICAL events accepted</li>
 * </ul>
 * CRITICAL events (FAILED_LOGIN, MISSING_PRIVILEGES, SSL_EXCEPTION, BAD_HEADERS)
 * are never dropped unless the buffer is completely full.
 */
public class AuditRingBuffer implements Closeable {

    private static final Logger log = LogManager.getLogger(AuditRingBuffer.class);

    private final AtomicReferenceArray<AuditEvent> buffer;
    private final int capacity;
    private final int mask;
    private final AtomicLong writeSequence = new AtomicLong(0);
    private final AtomicLong readSequence = new AtomicLong(0);
    private final AtomicLong droppedEvents = new AtomicLong(0);
    private final AtomicLong droppedByPriority = new AtomicLong(0);

    /**
     * @param capacity must be a power of 2
     */
    public AuditRingBuffer(int capacity) {
        if (Integer.bitCount(capacity) != 1) {
            // Round up to next power of 2
            capacity = Integer.highestOneBit(capacity - 1) << 1;
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.buffer = new AtomicReferenceArray<>(capacity);
    }

    /**
     * Offer an event to the ring buffer with priority-aware backpressure.
     *
     * @return true if accepted, false if dropped due to backpressure
     */
    public boolean offer(AuditEvent event) {
        AuditEventPriority priority = AuditEventPriority.forCategory(event.getCategory());
        int size = size();
        double fillRatio = (double) size / capacity;

        // Priority-aware shedding
        if (fillRatio >= 0.5 && priority == AuditEventPriority.LOW) {
            droppedByPriority.incrementAndGet();
            return false;
        }
        if (fillRatio >= 0.75 && priority == AuditEventPriority.NORMAL) {
            droppedByPriority.incrementAndGet();
            return false;
        }
        if (fillRatio >= 0.9 && priority == AuditEventPriority.HIGH) {
            droppedByPriority.incrementAndGet();
            return false;
        }

        // Try to claim a slot
        long seq = writeSequence.getAndIncrement();
        int index = (int) (seq & mask);

        // If slot is still occupied (reader hasn't caught up), drop
        if (buffer.get(index) != null) {
            droppedEvents.incrementAndGet();
            return false;
        }

        buffer.set(index, event);
        return true;
    }

    /**
     * Drain up to maxEvents from the buffer into the provided list.
     * Called by the single drain thread.
     *
     * @return number of events drained
     */
    public int drainTo(List<AuditEvent> target, int maxEvents) {
        int drained = 0;
        long readSeq = readSequence.get();

        while (drained < maxEvents) {
            int index = (int) (readSeq & mask);
            AuditEvent event = buffer.getAndSet(index, null);
            if (event == null) {
                break;
            }
            target.add(event);
            readSeq++;
            drained++;
        }

        readSequence.set(readSeq);
        return drained;
    }

    public int size() {
        long write = writeSequence.get();
        long read = readSequence.get();
        long diff = write - read;
        return (int) Math.min(diff, capacity);
    }

    public int getCapacity() {
        return capacity;
    }

    public long getDroppedEvents() {
        return droppedEvents.get();
    }

    public long getDroppedByPriority() {
        return droppedByPriority.get();
    }

    @Override
    public void close() {
        // No resources to release — buffer is GC'd
    }
}
