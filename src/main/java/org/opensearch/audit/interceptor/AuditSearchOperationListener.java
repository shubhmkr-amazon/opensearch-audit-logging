/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.audit.interceptor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.index.shard.SearchOperationListener;
import org.opensearch.search.internal.ReaderContext;
import org.opensearch.search.internal.SearchContext;

/**
 * Listens for search and fetch operations at the shard level and feeds them
 * into the {@link SearchAuditAggregator} instead of emitting per-shard events.
 * <p>
 * This reduces audit event volume from O(shards) to O(1) per search request.
 */
public class AuditSearchOperationListener implements SearchOperationListener {

    private static final Logger log = LogManager.getLogger(AuditSearchOperationListener.class);

    private final SearchAuditAggregator aggregator;

    public AuditSearchOperationListener(SearchAuditAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @Override
    public void onQueryPhase(SearchContext searchContext, long tookInNanos) {
        try {
            String index = searchContext.shardTarget() != null ? searchContext.shardTarget().getIndex() : "unknown";
            String source = searchContext.request() != null && searchContext.request().source() != null
                ? searchContext.request().source().toString()
                : null;
            long contextId = searchContext.id().getId();

            aggregator.onShardQuery(contextId, index, source, tookInNanos);
        } catch (Exception e) {
            log.debug("Error recording search query phase for aggregation", e);
        }
    }

    @Override
    public void onFetchPhase(SearchContext searchContext, long tookInNanos) {
        try {
            String index = searchContext.shardTarget() != null ? searchContext.shardTarget().getIndex() : "unknown";
            long contextId = searchContext.id().getId();

            aggregator.onShardFetch(contextId, index, tookInNanos);
        } catch (Exception e) {
            log.debug("Error recording search fetch phase for aggregation", e);
        }
    }

    @Override
    public void onFreeReaderContext(ReaderContext readerContext) {
        try {
            aggregator.complete(readerContext.id().getId());
        } catch (Exception e) {
            log.debug("Error completing search aggregation on context free", e);
        }
    }

    @Override
    public void onFreeScrollContext(ReaderContext readerContext) {
        try {
            aggregator.complete(readerContext.id().getId());
        } catch (Exception e) {
            log.debug("Error completing search aggregation on scroll free", e);
        }
    }
}
