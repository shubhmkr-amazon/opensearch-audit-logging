/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.audit.sink;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.audit.config.AuditConfig;
import org.opensearch.audit.event.AuditEvent;
import org.opensearch.transport.client.Client;

/**
 * Writes audit events to a daily-rolling OpenSearch index (e.g. audit-2026.04.15).
 * Uses the bulk API for batch writes to avoid per-event round trips.
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
    public int storeBatch(Collection<AuditEvent> events) {
        if (events.isEmpty()) {
            return 0;
        }
        try {
            BulkRequest bulkRequest = new BulkRequest();
            for (AuditEvent event : events) {
                String indexName = indexPrefix + DATE_FORMATTER.format(event.getTimestamp());
                bulkRequest.add(new IndexRequest(indexName).source(event.toMap()));
            }
            BulkResponse response = client.bulk(bulkRequest).actionGet();
            healthy = true;
            if (response.hasFailures()) {
                int failed = (int) java.util.Arrays.stream(response.getItems()).filter(i -> i.isFailed()).count();
                log.warn("Bulk index had {} failures: {}", failed, response.buildFailureMessage());
                return events.size() - failed;
            }
            return events.size();
        } catch (Exception e) {
            log.warn("Failed to bulk index audit events", e);
            healthy = false;
            return 0;
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
