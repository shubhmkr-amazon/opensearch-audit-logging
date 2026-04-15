# Developer Guide

## Prerequisites

- Java 21 (Amazon Corretto 21 recommended)
- An OpenSearch distribution matching the version in `build.gradle` (`opensearch_version`)

```bash
export JAVA_HOME=/path/to/java-21
```

## Build

```bash
./gradlew build
```

Output: `build/distributions/opensearch-audit-logging.zip`

## Local Setup with OpenSearch

### 1. Download OpenSearch

Download the OpenSearch distribution matching the `opensearch_version` in `build.gradle`:

```bash
# Check the version
grep opensearch_version build.gradle

# Download (replace <VERSION> with the version from build.gradle, e.g. 3.5.0)
wget https://ci.opensearch.org/ci/dbc/distribution-build-opensearch/<VERSION>/latest/linux/x64/tar/dist/opensearch/opensearch-<VERSION>-SNAPSHOT-linux-x64.tar.gz
tar -xzf opensearch-<VERSION>-SNAPSHOT-linux-x64.tar.gz
cd opensearch-<VERSION>-SNAPSHOT
```

### 2. Install the plugin

```bash
bin/opensearch-plugin install file:///absolute/path/to/opensearch-audit-logging/build/distributions/opensearch-audit-logging.zip
```

### 3. Configure

Add to `config/opensearch.yml`:

```yaml
plugins.audit.enabled: true
plugins.audit.enable_rest: true
plugins.audit.enable_transport: true
plugins.audit.sink.log4j.enabled: true
plugins.audit.sink.index.enabled: true
plugins.audit.sink.index.name: "audit-"

# Performance tuning (optional)
plugins.audit.ring_buffer.capacity: 8192
plugins.audit.ring_buffer.batch_size: 256
plugins.audit.ring_buffer.flush_interval_ms: 100
plugins.audit.search_aggregation.enabled: true
plugins.audit.search_aggregation.stale_threshold_ms: 30000
```

### 4. Start OpenSearch

```bash
./bin/opensearch
```

### 5. Verify

```bash
# Check config
curl -s localhost:9200/_plugins/_audit/config | jq .

# Check sink health
curl -s localhost:9200/_plugins/_audit/health | jq .

# Generate some events
curl -s -X PUT localhost:9200/test-index/_doc/1 -H 'Content-Type: application/json' -d '{"msg":"hello"}'
curl -s localhost:9200/test-index/_search | jq .

# Check stats
curl -s localhost:9200/_plugins/_audit/stats | jq .

# View audit index (if index sink enabled)
curl -s "localhost:9200/audit-*/_search?pretty&size=5"
```

### 6. Uninstall

```bash
bin/opensearch-plugin remove opensearch-audit-logging
```

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    AuditLoggingPlugin                    │
│                  (Plugin entry point)                    │
├──────────┬──────────────────┬───────────────────────────┤
│          │                  │                           │
│  Interceptors          Sink Layer              REST API │
│          │                  │                           │
│  AuditActionFilter     AuditRingBuffer    /config (GET) │
│  (REST/transport)         │               /health (GET) │
│          │            SinkRouter          /stats  (GET) │
│  AuditIndexing-       (drain thread)                    │
│  OperationListener        │                             │
│  (doc writes)         ┌───┴───┐                         │
│          │            │       │                         │
│  AuditSearch-     Log4jSink IndexSink                   │
│  OperationListener    │    (bulk API)                   │
│       │               │       │                         │
│  SearchAudit-     CustomSinkLoader                      │
│  Aggregator       (reflection)                          │
└──────────┴──────────────────┴───────────────────────────┘
```

### Packages

```
org.opensearch.audit
├── AuditLoggingPlugin.java          # Plugin entry point, wires all components
├── config/
│   └── AuditConfig.java             # All settings, parsed from opensearch.yml
├── event/
│   ├── AuditCategory.java           # 11 event categories (REST_REQUEST, DOCUMENT_WRITE, etc.)
│   ├── AuditEvent.java              # Immutable event model with builder, JSON/Map serialization
│   └── AuditEventPriority.java      # CRITICAL/HIGH/NORMAL/LOW priority per category
├── interceptor/
│   ├── AuditActionFilter.java       # ActionFilter — intercepts REST and transport actions
│   ├── AuditIndexingOperationListener.java  # Shard-level document write/delete listener
│   ├── AuditSearchOperationListener.java    # Shard-level search listener → feeds aggregator
│   └── SearchAuditAggregator.java   # Collapses N shard events into 1 per search request
├── sink/
│   ├── AuditSink.java               # Sink interface (store, storeBatch, isHealthy, getName)
│   ├── AuditRingBuffer.java         # Lock-free ring buffer with priority backpressure
│   ├── SinkRouter.java              # Drain thread batches events from ring buffer to sinks
│   ├── Log4jSink.java               # Writes events as JSON to a Log4j logger
│   ├── IndexSink.java               # Writes events to daily-rolling OpenSearch index (bulk API)
│   └── CustomSinkLoader.java        # Loads third-party sinks via reflection
└── rest/
    ├── AuditConfigRestAction.java   # GET /_plugins/_audit/config
    ├── AuditHealthRestAction.java   # GET /_plugins/_audit/health
    └── AuditStatsRestAction.java    # GET /_plugins/_audit/stats
```

### Event Flow

1. **Interceptor** captures an operation (REST call, transport action, doc write, search)
2. For searches: `AuditSearchOperationListener` feeds shard callbacks into `SearchAuditAggregator`, which emits one event per search request on context free
3. `SinkRouter.route()` checks enabled/disabled, updates stats, writes event into `AuditRingBuffer`
4. Ring buffer applies **priority backpressure** — drops LOW events first under load
5. **Drain thread** wakes up, batches events via `drainTo()`, calls `storeBatch()` on each sink
6. Sinks write to their destination (Log4j file, OpenSearch bulk index, custom)

### Ring Buffer Backpressure

| Buffer Fill | Dropped | Guaranteed |
|---|---|---|
| < 50% | Nothing | All events |
| 50–75% | LOW (DOCUMENT_READ) | NORMAL and above |
| 75–90% | LOW + NORMAL | HIGH and above |
| 90–100% | LOW + NORMAL + HIGH | CRITICAL only |

CRITICAL = FAILED_LOGIN, MISSING_PRIVILEGES, SSL_EXCEPTION, BAD_HEADERS

## Adding a New Sink

1. Implement `AuditSink`:

```java
public class MyCustomSink implements AuditSink {
    @Override
    public void init(Map<String, String> config) {
        // Read endpoint, credentials, etc. from config map
    }

    @Override
    public boolean store(AuditEvent event) {
        // Send event.toJson() to your destination
        return true;
    }

    @Override
    public int storeBatch(Collection<AuditEvent> events) {
        // Batch write for better throughput (optional override)
        return events.size();
    }

    @Override
    public boolean isHealthy() { return true; }

    @Override
    public String getName() { return "my-custom"; }

    @Override
    public void close() { }
}
```

2. Package as a JAR on the OpenSearch classpath

3. Configure in `opensearch.yml`:

```yaml
plugins.audit.sink.custom.my-sink.type: "com.example.MyCustomSink"
plugins.audit.sink.custom.my-sink.config.endpoint: "https://..."
plugins.audit.sink.custom.my-sink.config.token: "${MY_TOKEN}"
```

## Adding a New Audit Category

1. Add the value to `AuditCategory.java`
2. Assign a priority in `AuditEventPriority.java`
3. Emit events with the new category from an interceptor
4. Add tests

## Adding a New REST Endpoint

1. Create a class extending `BaseRestHandler` in `org.opensearch.audit.rest`
2. Register it in `AuditLoggingPlugin.getRestHandlers()`
3. Add tests

## Configuration Reference

| Setting | Default | Description |
|---|---|---|
| `plugins.audit.enabled` | `true` | Master switch |
| `plugins.audit.enable_rest` | `true` | Audit REST layer |
| `plugins.audit.enable_transport` | `true` | Audit transport layer |
| `plugins.audit.disabled_rest_categories` | `[]` | Categories to skip (REST) |
| `plugins.audit.disabled_transport_categories` | `[]` | Categories to skip (transport) |
| `plugins.audit.ignore_users` | `[]` | Users to exclude from auditing |
| `plugins.audit.ignore_requests` | `[]` | Request patterns to exclude |
| `plugins.audit.log_request_body` | `true` | Include request body in events |
| `plugins.audit.resolve_indices` | `true` | Resolve index aliases |
| `plugins.audit.exclude_sensitive_headers` | `true` | Strip auth headers |
| `plugins.audit.sink.log4j.enabled` | `true` | Enable Log4j sink |
| `plugins.audit.sink.log4j.logger_name` | `opensearch.audit` | Log4j logger name |
| `plugins.audit.sink.index.enabled` | `false` | Enable index sink |
| `plugins.audit.sink.index.name` | `audit-` | Index name prefix (daily rolling) |
| `plugins.audit.ring_buffer.capacity` | `8192` | Ring buffer size (power of 2) |
| `plugins.audit.ring_buffer.batch_size` | `256` | Max events per drain batch |
| `plugins.audit.ring_buffer.flush_interval_ms` | `100` | Drain thread sleep when idle |
| `plugins.audit.search_aggregation.enabled` | `true` | Aggregate shard-level search events |
| `plugins.audit.search_aggregation.stale_threshold_ms` | `30000` | Evict abandoned search accumulators after this |

## Testing

See [TESTING.md](TESTING.md) for how to run tests, test class descriptions, and testing patterns.
