/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.audit.sink;

import java.io.Closeable;

import org.opensearch.audit.event.AuditEvent;

/**
 * Interface for audit event sinks. Third-party developers can implement custom sinks
 * and load them via reflection.
 */
public interface AuditSink extends Closeable {

    /**
     * Store an audit event. Returns true if successfully stored.
     */
    boolean store(AuditEvent event);

    /**
     * Check if the sink is healthy and accepting events.
     */
    boolean isHealthy();

    /**
     * Returns the name of this sink for identification in health/stats APIs.
     */
    String getName();
}
