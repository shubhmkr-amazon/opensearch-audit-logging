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
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.engine.Engine;
import org.opensearch.index.shard.IndexingOperationListener;

/**
 * Listens for document-level index and delete operations at the shard level.
 * This captures every document write/delete — critical for compliance auditing
 * (SOC2, HIPAA, PCI-DSS) even in anonymous/VPC deployments.
 */
public class AuditIndexingOperationListener implements IndexingOperationListener {

    private static final Logger log = LogManager.getLogger(AuditIndexingOperationListener.class);

    private final SinkRouter sinkRouter;

    public AuditIndexingOperationListener(SinkRouter sinkRouter) {
        this.sinkRouter = sinkRouter;
    }

    @Override
    public void postIndex(ShardId shardId, Engine.Index index, Engine.IndexResult result) {
        if (result.getFailure() != null) {
            return;
        }
        try {
            AuditEvent event = AuditEvent.builder(AuditCategory.DOCUMENT_WRITE)
                .origin("INDEX")
                .requestAction("indices:data/write/index")
                .traceIndices(List.of(shardId.getIndexName()))
                .requestBody(shardId.getIndexName() + "/" + index.id())
                .build();
            sinkRouter.route(event);
        } catch (Exception e) {
            log.debug("Error auditing index operation on [{}]", shardId, e);
        }
    }

    @Override
    public void postDelete(ShardId shardId, Engine.Delete delete, Engine.DeleteResult result) {
        if (result.getFailure() != null) {
            return;
        }
        try {
            AuditEvent event = AuditEvent.builder(AuditCategory.DOCUMENT_WRITE)
                .origin("INDEX")
                .requestAction("indices:data/write/delete")
                .traceIndices(List.of(shardId.getIndexName()))
                .requestBody(shardId.getIndexName() + "/" + delete.id())
                .build();
            sinkRouter.route(event);
        } catch (Exception e) {
            log.debug("Error auditing delete operation on [{}]", shardId, e);
        }
    }
}
