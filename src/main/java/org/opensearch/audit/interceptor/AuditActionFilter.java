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
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.transport.TransportAddress;
import org.opensearch.tasks.Task;
import org.opensearch.threadpool.ThreadPool;

/**
 * ActionFilter that intercepts all REST and transport actions to generate audit events.
 * <p>
 * For anonymous/VPC deployments (no security plugin), the source IP is the primary
 * identifier for tracing who made a request. This filter captures it from ThreadContext.
 * <p>
 * When the security plugin is present, user identity is also read from ThreadContext.
 */
public class AuditActionFilter implements ActionFilter {

    private static final Logger log = LogManager.getLogger(AuditActionFilter.class);
    private static final String SECURITY_USER_KEY = "_opendistro_security_user";
    private static final String REMOTE_ADDRESS_KEY = "_remote_address";

    private final Settings settings;
    private final SinkRouter sinkRouter;
    private final ThreadPool threadPool;

    public AuditActionFilter(Settings settings, SinkRouter sinkRouter, ThreadPool threadPool) {
        this.settings = settings;
        this.sinkRouter = sinkRouter;
        this.threadPool = threadPool;
    }

    @Override
    public int order() {
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

            // Remote address — critical for anonymous/VPC deployments
            enrichWithRemoteAddress(builder);

            // User identity — from security plugin via ThreadContext, or <anonymous>
            enrichWithSecurityContext(builder, task);

            // Target indices
            enrichWithIndices(builder, request);

            sinkRouter.route(builder.build());
        } catch (Exception e) {
            log.debug("Error creating audit event for action [{}]", action, e);
        }

        chain.proceed(task, action, request, listener);
    }

    private void enrichWithRemoteAddress(AuditEvent.Builder builder) {
        try {
            ThreadContext threadContext = threadPool.getThreadContext();
            Object remoteAddress = threadContext.getTransient(REMOTE_ADDRESS_KEY);
            if (remoteAddress instanceof TransportAddress) {
                builder.remoteAddress(((TransportAddress) remoteAddress).getAddress());
            } else if (remoteAddress != null) {
                builder.remoteAddress(remoteAddress.toString());
            }
        } catch (Exception e) {
            log.trace("Could not extract remote address", e);
        }
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
        return !action.startsWith("indices:") && !action.startsWith("cluster:") && !action.startsWith("internal:");
    }

    private <Request extends ActionRequest> void enrichWithIndices(AuditEvent.Builder builder, Request request) {
        try {
            java.lang.reflect.Method method = request.getClass().getMethod("indices");
            Object result = method.invoke(request);
            if (result instanceof String[]) {
                builder.traceIndices(Arrays.stream((String[]) result).collect(Collectors.toList()));
            }
        } catch (NoSuchMethodException e) {
            // Request doesn't have indices - that's fine
        } catch (Exception e) {
            log.trace("Could not extract indices from request", e);
        }
    }
}
