/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.audit.rest;

import java.util.List;

import org.opensearch.audit.sink.AuditSink;
import org.opensearch.audit.sink.SinkRouter;
import org.opensearch.transport.client.node.NodeClient;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.BytesRestResponse;
import org.opensearch.rest.RestRequest;

/**
 * REST handler for GET /_plugins/_audit/health — returns sink health status.
 */
public class AuditHealthRestAction extends BaseRestHandler {

    private final SinkRouter sinkRouter;

    public AuditHealthRestAction(SinkRouter sinkRouter) {
        this.sinkRouter = sinkRouter;
    }

    @Override
    public String getName() {
        return "audit_health_action";
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route(RestRequest.Method.GET, "/_plugins/_audit/health"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        return channel -> {
            boolean allHealthy = true;
            var builder = channel.newBuilder();
            builder.startObject();
            builder.startObject("sinks");
            for (AuditSink sink : sinkRouter.getSinks()) {
                boolean healthy = sink.isHealthy();
                builder.startObject(sink.getName());
                builder.field("healthy", healthy);
                builder.endObject();
                if (!healthy) allHealthy = false;
            }
            builder.endObject();
            builder.field("overall", allHealthy ? "GREEN" : "RED");
            builder.endObject();
            channel.sendResponse(new BytesRestResponse(allHealthy ? RestStatus.OK : RestStatus.SERVICE_UNAVAILABLE, builder));
        };
    }
}
