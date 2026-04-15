/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.audit.sink;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.audit.config.AuditConfig;
import org.opensearch.audit.event.AuditEvent;

/**
 * Writes audit events to a Log4j logger. The logger name is configurable.
 */
public class Log4jSink implements AuditSink {

    private final Logger auditLogger;

    public Log4jSink(AuditConfig config) {
        this.auditLogger = LogManager.getLogger(config.getLog4jLoggerName());
    }

    @Override
    public boolean store(AuditEvent event) {
        auditLogger.info(event.toJson());
        return true;
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public String getName() {
        return "log4j";
    }

    @Override
    public void close() {
        // nothing to close
    }
}
