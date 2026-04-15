/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.audit.sink;

import java.io.Closeable;
import java.util.Collection;
import java.util.Map;

import org.opensearch.audit.event.AuditEvent;

/**
 * Interface for audit event sinks.
 * <p>
 * Implementations can target any audit/logging destination:
 * <ul>
 *   <li>Cloud services: AWS CloudTrail, Azure Monitor, Google Cloud Logging</li>
 *   <li>SIEM platforms: Splunk, Datadog, Elastic, IBM QRadar, Microsoft Sentinel</li>
 *   <li>Messaging systems: Kafka, Amazon Kinesis, Azure Event Hubs, Google Pub/Sub</li>
 *   <li>Storage: S3, Azure Blob, GCS, local files</li>
 *   <li>OpenSearch itself (built-in)</li>
 *   <li>Log frameworks: Log4j, syslog (built-in)</li>
 * </ul>
 * <p>
 * Custom sinks are loaded via reflection from the classpath. Implement this interface,
 * package as a JAR, place on the OpenSearch classpath, and configure in opensearch.yml:
 * <pre>
 * plugins.audit.sink.custom.type: "com.example.MySplunkAuditSink"
 * plugins.audit.sink.custom.config.endpoint: "https://splunk.example.com:8088"
 * plugins.audit.sink.custom.config.token: "${SPLUNK_HEC_TOKEN}"
 * </pre>
 */
public interface AuditSink extends Closeable {

    /**
     * Initialize the sink with its configuration.
     * Called once after construction, before any events are stored.
     *
     * @param config sink-specific configuration from opensearch.yml
     *              (e.g., endpoint, credentials, batch_size, format, etc.)
     */
    default void init(Map<String, String> config) {}

    /**
     * Store a single audit event.
     *
     * @param event the audit event to store
     * @return true if successfully stored or queued for delivery
     */
    boolean store(AuditEvent event);

    /**
     * Store a batch of audit events.
     * Default implementation calls {@link #store(AuditEvent)} for each event.
     * Override for sinks that support batch writes (most cloud APIs do).
     *
     * @param events the audit events to store
     * @return the number of events successfully stored
     */
    default int storeBatch(Collection<AuditEvent> events) {
        int success = 0;
        for (AuditEvent event : events) {
            if (store(event)) {
                success++;
            }
        }
        return success;
    }

    /**
     * Check if the sink is healthy and accepting events.
     * Used by the /_plugins/_audit/health REST endpoint.
     */
    boolean isHealthy();

    /**
     * Returns the name of this sink for identification in health/stats APIs.
     * Example: "log4j", "index", "cloudtrail", "splunk", "datadog"
     */
    String getName();
}
