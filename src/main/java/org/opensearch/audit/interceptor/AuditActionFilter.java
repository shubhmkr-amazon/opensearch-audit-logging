/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.audit.interceptor;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.support.ActionFilter;
import org.opensearch.action.support.ActionFilterChain;
import org.opensearch.audit.event.AuditCategory;
import org.opensearch.audit.event.AuditEvent;
import org.opensearch.audit.sink.SinkRouter;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.tasks.Task;

/**
 * ActionFilter that intercepts all REST and transport actions to generate audit events.
 * Reads user identity from ThreadContext when the security plugin is present.
 */
public class AuditActionFilter implements ActionFilter {

    private static final Logger log = LogManager.getLogger(AuditActionFilter.class);
    private static final String SECURITY_USER_KEY = "_opendistro_security_user";

    private final Settings settings;
    private final SinkRouter sinkRouter;

    public AuditActionFilter(Settings settings, SinkRouter sinkRouter) {
        this.settings = settings;
        this.sinkRouter = sinkRouter;
    }

    @Override
    public int order() {
        // Run after security plugin's filter (which is typically at order 0)
        return 10;
    }

    @Override
    public <Request extends ActionRequest, Response extends ActionResponse> void apply(
        Task task,
        String action,
        Request request,
        ActionListener<Response> listener,
        ActionFilterChain<Request, Response> chain
    ) {
        try {
            AuditCategory category = categorize(action);
            AuditEvent.Builder builder = AuditEvent.builder(category)
                .requestAction(action)
                .origin(isRestOrigin(action) ? "REST" : "TRANSPORT")
                .nodeName(settings.get("node.name", "unknown"))
                .clusterName(settings.get("cluster.name", "opensearch"));

            // Try to read user from ThreadContext (set by security plugin)
            enrichWithSecurityContext(builder, task);

            // Extract indices from request if available
            enrichWithIndices(builder, request);

            sinkRouter.route(builder.build());
        } catch (Exception e) {
            log.debug("Error creating audit event for action [{}]", action, e);
        }

        // Always continue the chain regardless of audit success/failure
        chain.proceed(task, action, request, listener);
    }

    private AuditCategory categorize(String action) {
        if (action.startsWith("indices:admin/")) {
            return AuditCategory.INDEX_EVENT;
        } else if (action.startsWith("indices:data/write/")) {
            return AuditCategory.DOCUMENT_WRITE;
        } else if (action.startsWith("indices:data/read/")) {
            return AuditCategory.DOCUMENT_READ;
        } else if (action.startsWith("cluster:") || action.startsWith("indices:")) {
            return AuditCategory.TRANSPORT_ACTION;
        }
        return AuditCategory.REST_REQUEST;
    }

    private boolean isRestOrigin(String action) {
        // Transport actions typically start with "indices:" or "cluster:"
        return !action.startsWith("indices:") && !action.startsWith("cluster:") && !action.startsWith("internal:");
    }

    private void enrichWithSecurityContext(AuditEvent.Builder builder, Task task) {
        try {
            String user = task.getHeader(SECURITY_USER_KEY);
            if (user != null) {
                builder.effectiveUser(user);
            } else {
                builder.effectiveUser("<anonymous>");
            }
        } catch (Exception e) {
            builder.effectiveUser("<anonymous>");
        }
    }

    private <Request extends ActionRequest> void enrichWithIndices(AuditEvent.Builder builder, Request request) {
        try {
            // Use reflection to call getIndices() if available on the request
            java.lang.reflect.Method method = request.getClass().getMethod("indices");
            Object result = method.invoke(request);
            if (result instanceof String[]) {
                List<String> indices = Arrays.stream((String[]) result).collect(Collectors.toList());
                builder.traceIndices(indices);
            }
        } catch (NoSuchMethodException e) {
            // Request doesn't have indices - that's fine
        } catch (Exception e) {
            log.trace("Could not extract indices from request", e);
        }
    }
}
