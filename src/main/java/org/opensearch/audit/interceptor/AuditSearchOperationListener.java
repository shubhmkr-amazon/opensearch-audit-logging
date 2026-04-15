/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.audit.interceptor;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.opensearch.audit.event.AuditCategory;
import org.opensearch.audit.event.AuditEvent;
import org.opensearch.audit.sink.SinkRouter;
import org.opensearch.index.shard.SearchOperationListener;
import org.opensearch.search.internal.SearchContext;

/**
 * Listens for search and fetch operations at the shard level.
 * Captures DOCUMENT_READ events for compliance — tracks which indices were queried.
 */
public class AuditSearchOperationListener implements SearchOperationListener {

    private static final Logger log = LogManager.getLogger(AuditSearchOperationListener.class);

    private final SinkRouter sinkRouter;

    public AuditSearchOperationListener(SinkRouter sinkRouter) {
        this.sinkRouter = sinkRouter;
    }

    @Override
    public void onQueryPhase(SearchContext searchContext, long tookInNanos) {
        try {
            String index = searchContext.shardTarget() != null ? searchContext.shardTarget().getIndex() : "unknown";
            String source = searchContext.request() != null && searchContext.request().source() != null
                ? searchContext.request().source().toString()
                : null;

            AuditEvent event = AuditEvent.builder(AuditCategory.DOCUMENT_READ)
                .origin("SEARCH")
                .requestAction("indices:data/read/search[phase/query]")
                .traceIndices(List.of(index))
                .requestBody(source)
                .build();
            sinkRouter.route(event);
        } catch (Exception e) {
            log.debug("Error auditing search query phase", e);
        }
    }

    @Override
    public void onFetchPhase(SearchContext searchContext, long tookInNanos) {
        try {
            String index = searchContext.shardTarget() != null ? searchContext.shardTarget().getIndex() : "unknown";

            AuditEvent event = AuditEvent.builder(AuditCategory.DOCUMENT_READ)
                .origin("SEARCH")
                .requestAction("indices:data/read/search[phase/fetch]")
                .traceIndices(List.of(index))
                .build();
            sinkRouter.route(event);
        } catch (Exception e) {
            log.debug("Error auditing search fetch phase", e);
        }
    }
}
