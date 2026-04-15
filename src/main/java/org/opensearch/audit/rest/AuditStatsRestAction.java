/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.audit.rest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.opensearch.audit.event.AuditCategory;
import org.opensearch.audit.sink.SinkRouter;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.BytesRestResponse;
import org.opensearch.rest.RestRequest;
import org.opensearch.transport.client.node.NodeClient;

/**
 * REST handler for GET /_plugins/_audit/stats — returns event counts per category and per sink.
 */
public class AuditStatsRestAction extends BaseRestHandler {

    private final SinkRouter sinkRouter;

    public AuditStatsRestAction(SinkRouter sinkRouter) {
        this.sinkRouter = sinkRouter;
    }

    @Override
    public String getName() {
        return "audit_stats_action";
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route(RestRequest.Method.GET, "/_plugins/_audit/stats"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        return channel -> {
            var builder = channel.newBuilder();
            builder.startObject();
            builder.field("total_events", sinkRouter.getTotalEvents());
            builder.field("failed_events", sinkRouter.getFailedEvents());

            builder.startObject("by_category");
            for (Map.Entry<AuditCategory, AtomicLong> entry : sinkRouter.getCategoryStats().entrySet()) {
                builder.field(entry.getKey().name(), entry.getValue().get());
            }
            builder.endObject();

            builder.startObject("by_sink");
            for (Map.Entry<String, AtomicLong> entry : sinkRouter.getSinkStats().entrySet()) {
                builder.field(entry.getKey(), entry.getValue().get());
            }
            builder.endObject();

            builder.endObject();
            channel.sendResponse(new BytesRestResponse(RestStatus.OK, builder));
        };
    }
}
