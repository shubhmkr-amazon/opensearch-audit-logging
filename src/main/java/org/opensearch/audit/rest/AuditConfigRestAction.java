/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.audit.rest;

import java.util.List;

import org.opensearch.audit.config.AuditConfig;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.BytesRestResponse;
import org.opensearch.rest.RestRequest;
import org.opensearch.transport.client.node.NodeClient;

/**
 * REST handler for GET /_plugins/_audit/config — returns current audit configuration.
 */
public class AuditConfigRestAction extends BaseRestHandler {

    private final AuditConfig config;

    public AuditConfigRestAction(AuditConfig config) {
        this.config = config;
    }

    @Override
    public String getName() {
        return "audit_config_action";
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route(RestRequest.Method.GET, "/_plugins/_audit/config"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        return channel -> {
            var builder = channel.newBuilder();
            builder.startObject();
            builder.field("enabled", config.isEnabled());
            builder.field("enable_rest", config.isEnableRest());
            builder.field("enable_transport", config.isEnableTransport());
            builder.field("disabled_categories", config.getDisabledCategories());
            builder.field("ignore_users", config.getIgnoreUsers());
            builder.field("ignore_requests", config.getIgnoreRequests());
            builder.field("log_request_body", config.isLogRequestBody());
            builder.field("resolve_indices", config.isResolveIndices());
            builder.field("exclude_sensitive_headers", config.isExcludeSensitiveHeaders());
            builder.startObject("sinks");
            builder.startObject("log4j");
            builder.field("enabled", config.isLog4jSinkEnabled());
            builder.field("logger_name", config.getLog4jLoggerName());
            builder.endObject();
            builder.startObject("index");
            builder.field("enabled", config.isIndexSinkEnabled());
            builder.field("name", config.getIndexName());
            builder.endObject();
            builder.endObject();
            builder.endObject();
            channel.sendResponse(new BytesRestResponse(RestStatus.OK, builder));
        };
    }
}
