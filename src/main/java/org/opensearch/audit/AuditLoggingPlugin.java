/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.audit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import org.opensearch.action.support.ActionFilter;
import org.opensearch.audit.config.AuditConfig;
import org.opensearch.audit.interceptor.AuditActionFilter;
import org.opensearch.audit.interceptor.AuditIndexingOperationListener;
import org.opensearch.audit.interceptor.AuditSearchOperationListener;
import org.opensearch.audit.interceptor.SearchAuditAggregator;
import org.opensearch.audit.rest.AuditConfigRestAction;
import org.opensearch.audit.rest.AuditHealthRestAction;
import org.opensearch.audit.rest.AuditStatsRestAction;
import org.opensearch.audit.sink.CustomSinkLoader;
import org.opensearch.audit.sink.IndexSink;
import org.opensearch.audit.sink.Log4jSink;
import org.opensearch.audit.sink.SinkRouter;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.IndexScopedSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.settings.SettingsFilter;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.index.IndexModule;
import org.opensearch.plugins.ActionPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.rest.RestController;
import org.opensearch.rest.RestHandler;
import org.opensearch.script.ScriptService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;
import org.opensearch.watcher.ResourceWatcherService;

/**
 * Standalone audit logging plugin for OpenSearch.
 * Captures REST, transport, and document-level operations independently of the security plugin.
 */
public class AuditLoggingPlugin extends Plugin implements ActionPlugin {

    private final Settings settings;
    private SinkRouter sinkRouter;
    private AuditConfig auditConfig;
    private ThreadPool threadPool;
    private SearchAuditAggregator searchAggregator;

    public AuditLoggingPlugin(final Settings settings) {
        this.settings = settings;
    }

    @Override
    public Collection<Object> createComponents(
        Client client,
        ClusterService clusterService,
        ThreadPool threadPool,
        ResourceWatcherService resourceWatcherService,
        ScriptService scriptService,
        NamedXContentRegistry xContentRegistry,
        Environment environment,
        NodeEnvironment nodeEnvironment,
        NamedWriteableRegistry namedWriteableRegistry,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<RepositoriesService> repositoriesServiceSupplier
    ) {
        this.auditConfig = new AuditConfig(settings);
        this.threadPool = threadPool;

        List<org.opensearch.audit.sink.AuditSink> sinks = new ArrayList<>();
        if (auditConfig.isLog4jSinkEnabled()) {
            sinks.add(new Log4jSink(auditConfig));
        }
        if (auditConfig.isIndexSinkEnabled()) {
            sinks.add(new IndexSink(client, auditConfig));
        }

        // Load custom sinks via reflection (CloudTrail, Splunk, Datadog, etc.)
        sinks.addAll(CustomSinkLoader.loadCustomSinks(settings));

        this.sinkRouter = new SinkRouter(sinks, threadPool, auditConfig);

        if (auditConfig.isSearchAggregationEnabled()) {
            this.searchAggregator = new SearchAuditAggregator(sinkRouter, auditConfig.getSearchAggregationStaleMs());
            this.sinkRouter.setPeriodicTask(searchAggregator::evictStale);
        }

        return List.of(sinkRouter, auditConfig);
    }

    @Override
    public List<ActionFilter> getActionFilters() {
        return Collections.singletonList(new AuditActionFilter(settings, sinkRouter, threadPool, auditConfig.getIndexName()));
    }

    @Override
    public List<RestHandler> getRestHandlers(
        Settings settings,
        RestController restController,
        ClusterSettings clusterSettings,
        IndexScopedSettings indexScopedSettings,
        SettingsFilter settingsFilter,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<DiscoveryNodes> nodesInCluster
    ) {
        return List.of(new AuditConfigRestAction(auditConfig), new AuditHealthRestAction(sinkRouter), new AuditStatsRestAction(sinkRouter));
    }

    @Override
    public List<Setting<?>> getSettings() {
        return AuditConfig.getSettings();
    }

    @Override
    public void onIndexModule(IndexModule indexModule) {
        if (sinkRouter != null) {
            indexModule.addIndexOperationListener(new AuditIndexingOperationListener(sinkRouter, auditConfig.getIndexName()));
            if (searchAggregator != null) {
                indexModule.addSearchOperationListener(new AuditSearchOperationListener(searchAggregator));
            } else {
                indexModule
                    .addSearchOperationListener(
                        new AuditSearchOperationListener(new SearchAuditAggregator(sinkRouter, auditConfig.getSearchAggregationStaleMs()))
                    );
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (sinkRouter != null) {
            sinkRouter.close();
        }
    }
}
