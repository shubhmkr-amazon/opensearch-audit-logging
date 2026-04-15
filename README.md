# OpenSearch Audit Logging Plugin

A standalone audit logging plugin for OpenSearch that provides audit logging as an independent, first-class capability — decoupled from the security plugin.

## Overview

Today, audit logging is tightly embedded inside the [security plugin](https://github.com/opensearch-project/security). It only works when the security plugin is installed and fine-grained access control (FGAC) is enabled. This plugin extracts audit logging into a separate plugin that:

- Works **with or without** the security plugin — no FGAC dependency
- Captures data-plane operations: REST requests, transport actions, document reads/writes
- Enriches events with user identity when the security plugin is present (via `ThreadContext`)
- Provides an **extensible sink architecture** — built-in Log4j and OpenSearch index sinks, plus custom sinks loadable via reflection
- Runs **alongside** the existing security plugin with zero breaking changes

**Proposal:** [opensearch-project/.github#501](https://github.com/opensearch-project/.github/issues/501)

## Building

```bash
./gradlew build
```

## Installation

```bash
bin/opensearch-plugin install file:///path/to/opensearch-audit-logging/build/distributions/opensearch-audit-logging.zip
```

## Configuration

Add to `opensearch.yml`:

```yaml
# Master switch
plugins.audit.enabled: true

# Layer control
plugins.audit.enable_rest: true
plugins.audit.enable_transport: true

# Category filtering
plugins.audit.disabled_rest_categories:
  - AUTHENTICATED
  - GRANTED_PRIVILEGES

# User/request filtering
plugins.audit.ignore_users:
  - kibanaserver

# Sink: Log4j (file-based logging)
plugins.audit.sink.log4j.enabled: true
plugins.audit.sink.log4j.logger_name: "opensearch.audit"

# Sink: Internal OpenSearch index
plugins.audit.sink.index.enabled: false
plugins.audit.sink.index.name: "audit-"
```

## REST API

| Endpoint | Method | Description |
|---|---|---|
| `/_plugins/_audit/config` | GET | Current audit configuration |
| `/_plugins/_audit/health` | GET | Sink health status |
| `/_plugins/_audit/stats` | GET | Event counts per category and per sink |

## Audit Event Categories

| Category | Layer | Description |
|---|---|---|
| `REST_REQUEST` | REST | Any REST API call received |
| `TRANSPORT_ACTION` | Transport | Internal transport actions between nodes |
| `INDEX_EVENT` | Transport | Index administration (create, delete, alias) |
| `DOCUMENT_WRITE` | Index | Document indexed, updated, or deleted |
| `DOCUMENT_READ` | Index | Document read during search/get |
| `FAILED_LOGIN` | REST/Transport | Authentication failure (security plugin) |
| `AUTHENTICATED` | REST/Transport | Successful authentication (security plugin) |
| `MISSING_PRIVILEGES` | Transport | Authorization failure (security plugin) |
| `GRANTED_PRIVILEGES` | Transport | Successful authorization (security plugin) |
| `SSL_EXCEPTION` | REST/Transport | TLS/SSL errors |
| `BAD_HEADERS` | REST/Transport | Spoofed or invalid headers |

## Extensible Sink Interface

Third-party developers can implement custom sinks:

```java
public interface AuditSink extends Closeable {
    boolean store(AuditEvent event);
    boolean isHealthy();
    String getName();
}
```

## License

This project is licensed under the [Apache License, Version 2.0](LICENSE.txt).

## Copyright

Copyright OpenSearch Contributors. See [NOTICE](NOTICE.txt) for details.
