/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.audit.config;

import org.opensearch.audit.event.AuditCategory;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchTestCase;

public class AuditConfigTests extends OpenSearchTestCase {

    public void testDefaults() {
        AuditConfig config = new AuditConfig(Settings.EMPTY);
        assertTrue(config.isEnabled());
        assertTrue(config.isEnableRest());
        assertTrue(config.isEnableTransport());
        assertTrue(config.isLogRequestBody());
        assertTrue(config.isResolveIndices());
        assertTrue(config.isExcludeSensitiveHeaders());
        assertTrue(config.isLog4jSinkEnabled());
        assertFalse(config.isIndexSinkEnabled());
        assertEquals("opensearch.audit", config.getLog4jLoggerName());
        assertEquals("audit-", config.getIndexName());
        assertTrue(config.getDisabledCategories().isEmpty());
        assertTrue(config.getIgnoreUsers().isEmpty());
    }

    public void testDisabledCategories() {
        Settings settings = Settings.builder()
            .putList("plugins.audit.disabled_rest_categories", "AUTHENTICATED", "GRANTED_PRIVILEGES")
            .build();
        AuditConfig config = new AuditConfig(settings);

        assertTrue(config.getDisabledCategories().contains(AuditCategory.AUTHENTICATED));
        assertTrue(config.getDisabledCategories().contains(AuditCategory.GRANTED_PRIVILEGES));
        assertFalse(config.getDisabledCategories().contains(AuditCategory.REST_REQUEST));
    }

    public void testCustomSinkSettings() {
        Settings settings = Settings.builder()
            .put("plugins.audit.sink.log4j.enabled", false)
            .put("plugins.audit.sink.index.enabled", true)
            .put("plugins.audit.sink.index.name", "custom-audit-")
            .build();
        AuditConfig config = new AuditConfig(settings);

        assertFalse(config.isLog4jSinkEnabled());
        assertTrue(config.isIndexSinkEnabled());
        assertEquals("custom-audit-", config.getIndexName());
    }

    public void testIgnoreUsers() {
        Settings settings = Settings.builder().putList("plugins.audit.ignore_users", "kibanaserver", "admin").build();
        AuditConfig config = new AuditConfig(settings);

        assertEquals(2, config.getIgnoreUsers().size());
        assertTrue(config.getIgnoreUsers().contains("kibanaserver"));
    }
}
