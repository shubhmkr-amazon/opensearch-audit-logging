/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.audit.event;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.ToXContentObject;
import org.opensearch.core.xcontent.XContentBuilder;

/**
 * Represents a single audit event. Format is compatible with the security plugin's audit event format (v5).
 */
public class AuditEvent implements ToXContentObject {

    public static final int FORMAT_VERSION = 5;

    private final Instant timestamp;
    private final AuditCategory category;
    private final String origin;
    private final String nodeId;
    private final String nodeName;
    private final String clusterName;
    private final String effectiveUser;
    private final List<String> effectiveUserRoles;
    private final String remoteAddress;
    private final String requestAction;
    private final List<String> traceIndices;
    private final List<String> resolvedIndices;
    private final String requestBody;
    private final Map<String, String> requestHeaders;

    private AuditEvent(Builder builder) {
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.category = builder.category;
        this.origin = builder.origin;
        this.nodeId = builder.nodeId;
        this.nodeName = builder.nodeName;
        this.clusterName = builder.clusterName;
        this.effectiveUser = builder.effectiveUser;
        this.effectiveUserRoles = builder.effectiveUserRoles != null ? builder.effectiveUserRoles : Collections.emptyList();
        this.remoteAddress = builder.remoteAddress;
        this.requestAction = builder.requestAction;
        this.traceIndices = builder.traceIndices != null ? builder.traceIndices : Collections.emptyList();
        this.resolvedIndices = builder.resolvedIndices != null ? builder.resolvedIndices : Collections.emptyList();
        this.requestBody = builder.requestBody;
        this.requestHeaders = builder.requestHeaders != null ? builder.requestHeaders : Collections.emptyMap();
    }

    public AuditCategory getCategory() {
        return category;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getOrigin() {
        return origin;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getNodeName() {
        return nodeName;
    }

    public String getClusterName() {
        return clusterName;
    }

    public String getEffectiveUser() {
        return effectiveUser;
    }

    public List<String> getEffectiveUserRoles() {
        return effectiveUserRoles;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public String getRequestAction() {
        return requestAction;
    }

    public List<String> getTraceIndices() {
        return traceIndices;
    }

    public List<String> getResolvedIndices() {
        return resolvedIndices;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public Map<String, String> getRequestHeaders() {
        return requestHeaders;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws java.io.IOException {
        builder.startObject();
        builder.field("audit_format_version", FORMAT_VERSION);
        builder.field("audit_timestamp", timestamp.toString());
        builder.field("audit_category", category.name());
        if (origin != null) builder.field("audit_origin", origin);
        if (nodeId != null) builder.field("audit_node_id", nodeId);
        if (nodeName != null) builder.field("audit_node_name", nodeName);
        if (clusterName != null) builder.field("audit_cluster_name", clusterName);
        if (effectiveUser != null) builder.field("audit_request_effective_user", effectiveUser);
        if (!effectiveUserRoles.isEmpty()) builder.field("audit_request_effective_user_roles", effectiveUserRoles);
        if (remoteAddress != null) builder.field("audit_request_remote_address", remoteAddress);
        if (requestAction != null) builder.field("audit_request_action", requestAction);
        if (!traceIndices.isEmpty()) builder.field("audit_trace_indices", traceIndices);
        if (!resolvedIndices.isEmpty()) builder.field("audit_trace_resolved_indices", resolvedIndices);
        if (requestBody != null) builder.field("audit_request_body", requestBody);
        if (!requestHeaders.isEmpty()) builder.field("audit_request_headers", requestHeaders);
        builder.endObject();
        return builder;
    }

    /**
     * Serialize this event to a JSON string.
     */
    public String toJson() {
        try {
            XContentBuilder builder = XContentFactory.jsonBuilder();
            toXContent(builder, EMPTY_PARAMS);
            return builder.toString();
        } catch (java.io.IOException e) {
            return "{\"error\":\"serialization_failed\"}";
        }
    }

    /**
     * Convert to a Map representation for index sink.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("audit_format_version", FORMAT_VERSION);
        map.put("audit_timestamp", timestamp.toString());
        map.put("audit_category", category.name());
        if (origin != null) map.put("audit_origin", origin);
        if (nodeId != null) map.put("audit_node_id", nodeId);
        if (nodeName != null) map.put("audit_node_name", nodeName);
        if (clusterName != null) map.put("audit_cluster_name", clusterName);
        if (effectiveUser != null) map.put("audit_request_effective_user", effectiveUser);
        if (!effectiveUserRoles.isEmpty()) map.put("audit_request_effective_user_roles", effectiveUserRoles);
        if (remoteAddress != null) map.put("audit_request_remote_address", remoteAddress);
        if (requestAction != null) map.put("audit_request_action", requestAction);
        if (!traceIndices.isEmpty()) map.put("audit_trace_indices", traceIndices);
        if (!resolvedIndices.isEmpty()) map.put("audit_trace_resolved_indices", resolvedIndices);
        if (requestBody != null) map.put("audit_request_body", requestBody);
        if (!requestHeaders.isEmpty()) map.put("audit_request_headers", requestHeaders);
        return map;
    }

    public static Builder builder(AuditCategory category) {
        return new Builder(category);
    }

    public static class Builder {

        private final AuditCategory category;
        private Instant timestamp;
        private String origin;
        private String nodeId;
        private String nodeName;
        private String clusterName;
        private String effectiveUser;
        private List<String> effectiveUserRoles;
        private String remoteAddress;
        private String requestAction;
        private List<String> traceIndices;
        private List<String> resolvedIndices;
        private String requestBody;
        private Map<String, String> requestHeaders;

        private Builder(AuditCategory category) {
            this.category = category;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder origin(String origin) {
            this.origin = origin;
            return this;
        }

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder nodeName(String nodeName) {
            this.nodeName = nodeName;
            return this;
        }

        public Builder clusterName(String clusterName) {
            this.clusterName = clusterName;
            return this;
        }

        public Builder effectiveUser(String effectiveUser) {
            this.effectiveUser = effectiveUser;
            return this;
        }

        public Builder effectiveUserRoles(List<String> effectiveUserRoles) {
            this.effectiveUserRoles = effectiveUserRoles;
            return this;
        }

        public Builder remoteAddress(String remoteAddress) {
            this.remoteAddress = remoteAddress;
            return this;
        }

        public Builder requestAction(String requestAction) {
            this.requestAction = requestAction;
            return this;
        }

        public Builder traceIndices(List<String> traceIndices) {
            this.traceIndices = traceIndices;
            return this;
        }

        public Builder resolvedIndices(List<String> resolvedIndices) {
            this.resolvedIndices = resolvedIndices;
            return this;
        }

        public Builder requestBody(String requestBody) {
            this.requestBody = requestBody;
            return this;
        }

        public Builder requestHeaders(Map<String, String> requestHeaders) {
            this.requestHeaders = requestHeaders;
            return this;
        }

        public AuditEvent build() {
            return new AuditEvent(this);
        }
    }
}
