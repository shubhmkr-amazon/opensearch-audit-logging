/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.audit.sink;

import java.util.ArrayList;
import java.util.List;

import org.opensearch.audit.event.AuditCategory;
import org.opensearch.audit.event.AuditEvent;
import org.opensearch.test.OpenSearchTestCase;

public class AuditRingBufferTests extends OpenSearchTestCase {

    public void testBasicOfferAndDrain() {
        AuditRingBuffer buffer = new AuditRingBuffer(64);
        AuditEvent event = AuditEvent.builder(AuditCategory.REST_REQUEST).requestAction("test").build();

        assertTrue(buffer.offer(event));
        assertEquals(1, buffer.size());

        List<AuditEvent> drained = new ArrayList<>();
        int count = buffer.drainTo(drained, 10);
        assertEquals(1, count);
        assertEquals(1, drained.size());
        assertEquals("test", drained.get(0).getRequestAction());
        assertEquals(0, buffer.size());
    }

    public void testCapacityRoundsUpToPowerOfTwo() {
        AuditRingBuffer buffer = new AuditRingBuffer(100);
        assertEquals(128, buffer.getCapacity());

        AuditRingBuffer buffer2 = new AuditRingBuffer(64);
        assertEquals(64, buffer2.getCapacity());
    }

    public void testPriorityBackpressureDropsLowFirst() {
        // Small buffer so we can fill it easily
        AuditRingBuffer buffer = new AuditRingBuffer(16);

        // Fill to >50% with CRITICAL events (these always get in)
        for (int i = 0; i < 9; i++) {
            assertTrue(buffer.offer(AuditEvent.builder(AuditCategory.FAILED_LOGIN).build()));
        }

        // Buffer is >50% full — LOW priority (DOCUMENT_READ) should be dropped
        boolean accepted = buffer.offer(AuditEvent.builder(AuditCategory.DOCUMENT_READ).build());
        assertFalse(accepted);
        assertTrue(buffer.getDroppedByPriority() > 0);

        // CRITICAL should still be accepted
        assertTrue(buffer.offer(AuditEvent.builder(AuditCategory.FAILED_LOGIN).build()));
    }

    public void testPriorityBackpressureDropsNormalAt75Percent() {
        AuditRingBuffer buffer = new AuditRingBuffer(16);

        // Fill to >75% (13 of 16)
        for (int i = 0; i < 13; i++) {
            buffer.offer(AuditEvent.builder(AuditCategory.FAILED_LOGIN).build());
        }

        // NORMAL priority (DOCUMENT_WRITE) should be dropped at >75%
        assertFalse(buffer.offer(AuditEvent.builder(AuditCategory.DOCUMENT_WRITE).build()));

        // CRITICAL still accepted
        assertTrue(buffer.offer(AuditEvent.builder(AuditCategory.FAILED_LOGIN).build()));
    }

    public void testPriorityBackpressureDropsHighAt90Percent() {
        AuditRingBuffer buffer = new AuditRingBuffer(16);

        // Fill to >90% (15 of 16)
        for (int i = 0; i < 15; i++) {
            buffer.offer(AuditEvent.builder(AuditCategory.FAILED_LOGIN).build());
        }

        // HIGH priority (INDEX_EVENT) should be dropped at >90%
        assertFalse(buffer.offer(AuditEvent.builder(AuditCategory.INDEX_EVENT).build()));

        // CRITICAL still accepted (last slot)
        assertTrue(buffer.offer(AuditEvent.builder(AuditCategory.FAILED_LOGIN).build()));
    }

    public void testDrainRespectsMaxEvents() {
        AuditRingBuffer buffer = new AuditRingBuffer(64);
        for (int i = 0; i < 10; i++) {
            buffer.offer(AuditEvent.builder(AuditCategory.REST_REQUEST).build());
        }

        List<AuditEvent> drained = new ArrayList<>();
        int count = buffer.drainTo(drained, 5);
        assertEquals(5, count);
        assertEquals(5, drained.size());
        assertEquals(5, buffer.size());
    }

    public void testDrainEmptyBuffer() {
        AuditRingBuffer buffer = new AuditRingBuffer(64);
        List<AuditEvent> drained = new ArrayList<>();
        int count = buffer.drainTo(drained, 10);
        assertEquals(0, count);
        assertTrue(drained.isEmpty());
    }

    public void testAllEventsAcceptedBelowHalfFull() {
        AuditRingBuffer buffer = new AuditRingBuffer(64);

        // Fill to <50% (30 of 64)
        for (int i = 0; i < 30; i++) {
            assertTrue(buffer.offer(AuditEvent.builder(AuditCategory.DOCUMENT_READ).build()));
        }
        assertEquals(0, buffer.getDroppedByPriority());
    }
}
