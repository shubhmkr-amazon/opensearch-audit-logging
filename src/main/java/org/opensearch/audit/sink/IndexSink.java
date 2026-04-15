/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.audit.sink;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.opensearch.action.index.IndexRequest;
import org.opensearch.audit.config.AuditConfig;
import org.opensearch.audit.event.AuditEvent;
import org.opensearch.transport.client.Client;

/**
 * Writes audit events to a daily-rolling OpenSearch index (e.g. audit-2026.04.15).
 */
public class IndexSink implements AuditSink {

    private static final Logger log = LogManager.getLogger(IndexSink.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC);

    private final Client client;
    private final String indexPrefix;
    private volatile boolean healthy = true;

    public IndexSink(Client client, AuditConfig config) {
        this.client = client;
        this.indexPrefix = config.getIndexName();
    }

    @Override
    public boolean store(AuditEvent event) {
        try {
            String indexName = indexPrefix + DATE_FORMATTER.format(event.getTimestamp());
            IndexRequest request = new IndexRequest(indexName).source(event.toMap());
            client.index(request);
            return true;
        } catch (Exception e) {
            log.warn("Failed to index audit event", e);
            healthy = false;
            return false;
        }
    }

    @Override
    public boolean isHealthy() {
        return healthy;
    }

    @Override
    public String getName() {
        return "index";
    }

    @Override
    public void close() {
        // client lifecycle managed by OpenSearch
    }
}
