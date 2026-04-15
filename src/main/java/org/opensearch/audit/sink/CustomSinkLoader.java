/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.audit.sink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.opensearch.common.settings.Settings;

/**
 * Loads custom audit sinks via reflection from opensearch.yml configuration.
 * <p>
 * Configuration format:
 * <pre>
 * plugins.audit.sink.custom.&lt;name&gt;.type: "com.example.MyAuditSink"
 * plugins.audit.sink.custom.&lt;name&gt;.config.endpoint: "https://..."
 * plugins.audit.sink.custom.&lt;name&gt;.config.token: "${ENV_VAR}"
 * </pre>
 * <p>
 * The sink class must implement {@link AuditSink} and have a no-arg constructor.
 * After construction, {@link AuditSink#init(Map)} is called with the config map.
 */
public class CustomSinkLoader {

    private static final Logger log = LogManager.getLogger(CustomSinkLoader.class);
    private static final String CUSTOM_SINK_PREFIX = "plugins.audit.sink.custom.";

    /**
     * Load all custom sinks defined in settings.
     */
    public static List<AuditSink> loadCustomSinks(Settings settings) {
        List<AuditSink> sinks = new ArrayList<>();
        Settings customSettings = settings.getByPrefix(CUSTOM_SINK_PREFIX);

        // Find all unique sink names (the key between custom. and .type)
        for (String key : customSettings.keySet()) {
            if (key.endsWith(".type")) {
                String sinkName = key.substring(0, key.length() - ".type".length());
                String className = customSettings.get(key);
                Map<String, String> config = extractSinkConfig(customSettings, sinkName);

                try {
                    AuditSink sink = instantiate(className, sinkName, config);
                    sinks.add(sink);
                    log.info("Loaded custom audit sink [{}] of type [{}]", sinkName, className);
                } catch (Exception e) {
                    log.error("Failed to load custom audit sink [{}] of type [{}]", sinkName, className, e);
                }
            }
        }

        return sinks;
    }

    private static Map<String, String> extractSinkConfig(Settings customSettings, String sinkName) {
        Map<String, String> config = new HashMap<>();
        String configPrefix = sinkName + ".config.";
        for (String key : customSettings.keySet()) {
            if (key.startsWith(configPrefix)) {
                config.put(key.substring(configPrefix.length()), customSettings.get(key));
            }
        }
        return config;
    }

    @SuppressWarnings("unchecked")
    private static AuditSink instantiate(String className, String sinkName, Map<String, String> config) throws Exception {
        Class<?> clazz = Class.forName(className);
        if (!AuditSink.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException("Class [" + className + "] does not implement AuditSink");
        }
        AuditSink sink = (AuditSink) clazz.getDeclaredConstructor().newInstance();
        sink.init(config);
        return sink;
    }
}
