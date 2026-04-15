/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.audit.event;

/**
 * Categories of audit events, compatible with the security plugin's audit event categories.
 */
public enum AuditCategory {
    REST_REQUEST,
    TRANSPORT_ACTION,
    INDEX_EVENT,
    DOCUMENT_WRITE,
    DOCUMENT_READ,
    FAILED_LOGIN,
    AUTHENTICATED,
    MISSING_PRIVILEGES,
    GRANTED_PRIVILEGES,
    SSL_EXCEPTION,
    BAD_HEADERS
}
