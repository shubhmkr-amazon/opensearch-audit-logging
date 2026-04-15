/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.audit.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.opensearch.test.OpenSearchTestCase;

public class AuditEventTests extends OpenSearchTestCase {

    public void testBuilderAndToJson() {
        AuditEvent event = AuditEvent
            .builder(AuditCategory.REST_REQUEST)
            .timestamp(Instant.parse("2026-04-15T00:00:00Z"))
            .origin("REST")
            .nodeId("node-1")
            .nodeName("opensearch-node-1")
            .clusterName("test-cluster")
            .effectiveUser("admin")
            .requestAction("indices:data/read/search")
            .traceIndices(List.of("my-index"))
            .build();

        String json = event.toJson();
        assertNotNull(json);
        assertTrue(json.contains("\"audit_format_version\":5"));
        assertTrue(json.contains("\"audit_category\":\"REST_REQUEST\""));
        assertTrue(json.contains("\"audit_request_effective_user\":\"admin\""));
        assertTrue(json.contains("\"audit_node_name\":\"opensearch-node-1\""));
        assertTrue(json.contains("\"audit_trace_indices\":[\"my-index\"]"));
    }

    public void testToMap() {
        AuditEvent event = AuditEvent
            .builder(AuditCategory.DOCUMENT_WRITE)
            .requestAction("indices:data/write/index")
            .effectiveUser("writer")
            .build();

        Map<String, Object> map = event.toMap();
        assertEquals(5, map.get("audit_format_version"));
        assertEquals("DOCUMENT_WRITE", map.get("audit_category"));
        assertEquals("writer", map.get("audit_request_effective_user"));
        assertEquals("indices:data/write/index", map.get("audit_request_action"));
    }

    public void testDefaultTimestamp() {
        Instant before = Instant.now();
        AuditEvent event = AuditEvent.builder(AuditCategory.TRANSPORT_ACTION).build();
        Instant after = Instant.now();

        assertNotNull(event.getTimestamp());
        assertFalse(event.getTimestamp().isBefore(before));
        assertFalse(event.getTimestamp().isAfter(after));
    }

    public void testNullFieldsOmittedFromMap() {
        AuditEvent event = AuditEvent.builder(AuditCategory.INDEX_EVENT).build();
        Map<String, Object> map = event.toMap();

        assertFalse(map.containsKey("audit_request_effective_user"));
        assertFalse(map.containsKey("audit_request_body"));
        assertFalse(map.containsKey("audit_node_id"));
    }
}
