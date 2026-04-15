/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.audit.config;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.opensearch.audit.event.AuditCategory;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;

/**
 * Holds all audit plugin configuration, read from opensearch.yml settings.
 */
public class AuditConfig {

    // Master switch
    public static final Setting<Boolean> ENABLED = Setting.boolSetting("plugins.audit.enabled", true, Setting.Property.NodeScope);

    // Layer control
    public static final Setting<Boolean> ENABLE_REST = Setting.boolSetting(
        "plugins.audit.enable_rest",
        true,
        Setting.Property.NodeScope
    );
    public static final Setting<Boolean> ENABLE_TRANSPORT = Setting.boolSetting(
        "plugins.audit.enable_transport",
        true,
        Setting.Property.NodeScope
    );

    // Category filtering
    public static final Setting<List<String>> DISABLED_REST_CATEGORIES = Setting.listSetting(
        "plugins.audit.disabled_rest_categories",
        Collections.emptyList(),
        s -> s,
        Setting.Property.NodeScope
    );
    public static final Setting<List<String>> DISABLED_TRANSPORT_CATEGORIES = Setting.listSetting(
        "plugins.audit.disabled_transport_categories",
        Collections.emptyList(),
        s -> s,
        Setting.Property.NodeScope
    );

    // User/request filtering
    public static final Setting<List<String>> IGNORE_USERS = Setting.listSetting(
        "plugins.audit.ignore_users",
        Collections.emptyList(),
        s -> s,
        Setting.Property.NodeScope
    );
    public static final Setting<List<String>> IGNORE_REQUESTS = Setting.listSetting(
        "plugins.audit.ignore_requests",
        Collections.emptyList(),
        s -> s,
        Setting.Property.NodeScope
    );

    // Request body and index resolution
    public static final Setting<Boolean> LOG_REQUEST_BODY = Setting.boolSetting(
        "plugins.audit.log_request_body",
        true,
        Setting.Property.NodeScope
    );
    public static final Setting<Boolean> RESOLVE_INDICES = Setting.boolSetting(
        "plugins.audit.resolve_indices",
        true,
        Setting.Property.NodeScope
    );
    public static final Setting<Boolean> EXCLUDE_SENSITIVE_HEADERS = Setting.boolSetting(
        "plugins.audit.exclude_sensitive_headers",
        true,
        Setting.Property.NodeScope
    );

    // Log4j sink settings
    public static final Setting<Boolean> LOG4J_SINK_ENABLED = Setting.boolSetting(
        "plugins.audit.sink.log4j.enabled",
        true,
        Setting.Property.NodeScope
    );
    public static final Setting<String> LOG4J_LOGGER_NAME = Setting.simpleString(
        "plugins.audit.sink.log4j.logger_name",
        "opensearch.audit",
        Setting.Property.NodeScope
    );

    // Index sink settings
    public static final Setting<Boolean> INDEX_SINK_ENABLED = Setting.boolSetting(
        "plugins.audit.sink.index.enabled",
        false,
        Setting.Property.NodeScope
    );
    public static final Setting<String> INDEX_NAME = Setting.simpleString(
        "plugins.audit.sink.index.name",
        "audit-",
        Setting.Property.NodeScope
    );

    private final boolean enabled;
    private final boolean enableRest;
    private final boolean enableTransport;
    private final Set<AuditCategory> disabledCategories;
    private final List<String> ignoreUsers;
    private final List<String> ignoreRequests;
    private final boolean logRequestBody;
    private final boolean resolveIndices;
    private final boolean excludeSensitiveHeaders;
    private final boolean log4jSinkEnabled;
    private final String log4jLoggerName;
    private final boolean indexSinkEnabled;
    private final String indexName;

    public AuditConfig(Settings settings) {
        this.enabled = ENABLED.get(settings);
        this.enableRest = ENABLE_REST.get(settings);
        this.enableTransport = ENABLE_TRANSPORT.get(settings);
        this.logRequestBody = LOG_REQUEST_BODY.get(settings);
        this.resolveIndices = RESOLVE_INDICES.get(settings);
        this.excludeSensitiveHeaders = EXCLUDE_SENSITIVE_HEADERS.get(settings);
        this.ignoreUsers = IGNORE_USERS.get(settings);
        this.ignoreRequests = IGNORE_REQUESTS.get(settings);
        this.log4jSinkEnabled = LOG4J_SINK_ENABLED.get(settings);
        this.log4jLoggerName = LOG4J_LOGGER_NAME.get(settings);
        this.indexSinkEnabled = INDEX_SINK_ENABLED.get(settings);
        this.indexName = INDEX_NAME.get(settings);

        Set<AuditCategory> disabled = EnumSet.noneOf(AuditCategory.class);
        for (String cat : DISABLED_REST_CATEGORIES.get(settings)) {
            try {
                disabled.add(AuditCategory.valueOf(cat));
            } catch (IllegalArgumentException e) {
                // ignore unknown categories
            }
        }
        for (String cat : DISABLED_TRANSPORT_CATEGORIES.get(settings)) {
            try {
                disabled.add(AuditCategory.valueOf(cat));
            } catch (IllegalArgumentException e) {
                // ignore unknown categories
            }
        }
        this.disabledCategories = Collections.unmodifiableSet(disabled);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isEnableRest() {
        return enableRest;
    }

    public boolean isEnableTransport() {
        return enableTransport;
    }

    public Set<AuditCategory> getDisabledCategories() {
        return disabledCategories;
    }

    public List<String> getIgnoreUsers() {
        return ignoreUsers;
    }

    public List<String> getIgnoreRequests() {
        return ignoreRequests;
    }

    public boolean isLogRequestBody() {
        return logRequestBody;
    }

    public boolean isResolveIndices() {
        return resolveIndices;
    }

    public boolean isExcludeSensitiveHeaders() {
        return excludeSensitiveHeaders;
    }

    public boolean isLog4jSinkEnabled() {
        return log4jSinkEnabled;
    }

    public String getLog4jLoggerName() {
        return log4jLoggerName;
    }

    public boolean isIndexSinkEnabled() {
        return indexSinkEnabled;
    }

    public String getIndexName() {
        return indexName;
    }

    /**
     * Returns all settings registered by this plugin.
     */
    public static List<Setting<?>> getSettings() {
        return List.of(
            ENABLED,
            ENABLE_REST,
            ENABLE_TRANSPORT,
            DISABLED_REST_CATEGORIES,
            DISABLED_TRANSPORT_CATEGORIES,
            IGNORE_USERS,
            IGNORE_REQUESTS,
            LOG_REQUEST_BODY,
            RESOLVE_INDICES,
            EXCLUDE_SENSITIVE_HEADERS,
            LOG4J_SINK_ENABLED,
            LOG4J_LOGGER_NAME,
            INDEX_SINK_ENABLED,
            INDEX_NAME
        );
    }
}
