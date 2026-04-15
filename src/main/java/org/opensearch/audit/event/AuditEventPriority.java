/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.audit.event;

import java.util.EnumMap;
import java.util.Map;

/**
 * Priority levels for audit event categories, used by the ring buffer's
 * backpressure mechanism to decide which events to shed under load.
 * <p>
 * When the ring buffer fills up, events are selectively dropped starting
 * from the lowest priority. CRITICAL events are never dropped.
 */
public enum AuditEventPriority {

    /** Security-critical events — never dropped under backpressure */
    CRITICAL(0),

    /** Important operational events — dropped only under extreme pressure */
    HIGH(1),

    /** Standard audit events — dropped when buffer exceeds 75% */
    NORMAL(2),

    /** High-volume, low-criticality events — dropped first when buffer exceeds 50% */
    LOW(3);

    private final int level;

    private static final Map<AuditCategory, AuditEventPriority> CATEGORY_PRIORITY = new EnumMap<>(AuditCategory.class);

    static {
        CATEGORY_PRIORITY.put(AuditCategory.FAILED_LOGIN, CRITICAL);
        CATEGORY_PRIORITY.put(AuditCategory.MISSING_PRIVILEGES, CRITICAL);
        CATEGORY_PRIORITY.put(AuditCategory.SSL_EXCEPTION, CRITICAL);
        CATEGORY_PRIORITY.put(AuditCategory.BAD_HEADERS, CRITICAL);

        CATEGORY_PRIORITY.put(AuditCategory.AUTHENTICATED, HIGH);
        CATEGORY_PRIORITY.put(AuditCategory.GRANTED_PRIVILEGES, HIGH);
        CATEGORY_PRIORITY.put(AuditCategory.INDEX_EVENT, HIGH);

        CATEGORY_PRIORITY.put(AuditCategory.REST_REQUEST, NORMAL);
        CATEGORY_PRIORITY.put(AuditCategory.TRANSPORT_ACTION, NORMAL);
        CATEGORY_PRIORITY.put(AuditCategory.DOCUMENT_WRITE, NORMAL);

        CATEGORY_PRIORITY.put(AuditCategory.DOCUMENT_READ, LOW);
    }

    AuditEventPriority(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    /**
     * Get the priority for a given audit category.
     */
    public static AuditEventPriority forCategory(AuditCategory category) {
        return CATEGORY_PRIORITY.getOrDefault(category, NORMAL);
    }
}
