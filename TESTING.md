# Testing Guide

## Prerequisites

- **Java 21** (Amazon Corretto 21 recommended)
- Gradle 8.14 (bundled via `gradlew`)

Set `JAVA_HOME` if Java 21 is not your default:

```bash
export JAVA_HOME=/path/to/java-21
```

## Running Tests

### All tests

```bash
./gradlew build
```

This runs compilation, unit tests, spotless formatting check, and Jacoco coverage in one step.

### Tests only (skip other checks)

```bash
./gradlew test
```

### Single test class

```bash
./gradlew test --tests 'org.opensearch.audit.sink.AuditRingBufferTests'
```

### Single test method

```bash
./gradlew test --tests 'org.opensearch.audit.sink.AuditRingBufferTests.testPriorityBackpressureDropsLowFirst'
```

### Reproduce a specific failure

Test output includes a `REPRODUCE WITH` line containing the random seed. Copy and run it:

```bash
./gradlew ':test' --tests 'org.opensearch.audit.sink.SinkRouterTests' \
  -Dtests.seed=415DB57DC73958E4 \
  -Dtests.security.manager=false
```

## Code Formatting

The project uses [Spotless](https://github.com/diffplug/spotless) with Eclipse formatter rules.

```bash
# Check formatting
./gradlew spotlessCheck

# Auto-fix formatting
./gradlew spotlessApply
```

Run `spotlessApply` before committing — the build will fail if formatting is off.

## Coverage

Jacoco coverage reports are generated automatically during `./gradlew build`:

```
build/reports/tests/test/index.html    # Test results
build/jacoco/test.exec                 # Raw coverage data
```

## Test Classes

### `AuditConfigTests` — Configuration parsing

| Test | What it verifies |
|---|---|
| `testDefaults` | All settings have correct defaults when `opensearch.yml` is empty |
| `testDisabledCategories` | `disabled_rest_categories` setting correctly parses into `EnumSet` |
| `testCustomSinkSettings` | Log4j/index sink enable/disable and custom index name |
| `testIgnoreUsers` | `ignore_users` list parsing |

### `AuditEventTests` — Event model

| Test | What it verifies |
|---|---|
| `testBuilderAndToJson` | Builder populates all fields; `toJson()` produces valid JSON with format version 5 |
| `testToMap` | `toMap()` produces correct key-value pairs for index sink |
| `testDefaultTimestamp` | Timestamp defaults to `Instant.now()` when not set |
| `testNullFieldsOmittedFromMap` | Null/empty fields are excluded from serialized output |

### `AuditRingBufferTests` — Ring buffer and backpressure

| Test | What it verifies |
|---|---|
| `testBasicOfferAndDrain` | Single event offer → drain round-trip |
| `testCapacityRoundsUpToPowerOfTwo` | Non-power-of-2 capacity is rounded up (100 → 128) |
| `testPriorityBackpressureDropsLowFirst` | LOW priority (DOCUMENT_READ) dropped at >50% fill |
| `testPriorityBackpressureDropsNormalAt75Percent` | NORMAL priority (DOCUMENT_WRITE) dropped at >75% fill |
| `testPriorityBackpressureDropsHighAt90Percent` | HIGH priority (INDEX_EVENT) dropped at >90% fill |
| `testDrainRespectsMaxEvents` | `drainTo(list, 5)` returns exactly 5 even when more are available |
| `testDrainEmptyBuffer` | Draining an empty buffer returns 0 without error |
| `testAllEventsAcceptedBelowHalfFull` | All priorities accepted when buffer is <50% full |

### `SinkRouterTests` — Event routing pipeline

| Test | What it verifies |
|---|---|
| `testRouteToSink` | Event flows through ring buffer → drain thread → sink; stats updated |
| `testDisabledAuditSkipsRouting` | `plugins.audit.enabled: false` prevents any event processing |
| `testDisabledCategorySkipsRouting` | Disabled categories are filtered before entering the ring buffer |

These tests use `assertBusy()` to wait for the async drain thread to flush events.

### `SearchAuditAggregatorTests` — Shard-level aggregation

| Test | What it verifies |
|---|---|
| `testAggregatesMultipleShardsIntoOneEvent` | 5 query + 5 fetch shard callbacks → 1 aggregated event with correct shard count, indices, and timing |
| `testStaleEviction` | Abandoned search entries are evicted after threshold and still emit partial events |
| `testCompleteWithUnknownIdIsNoOp` | Completing a non-existent search ID does nothing |
| `testFetchWithoutQueryEmitsStandalone` | Orphan fetch (no prior query) emits a standalone event instead of silently dropping |

## Test Patterns

**Base class:** All tests extend `OpenSearchTestCase`, which provides randomized testing, `assertBusy()` for async assertions, and thread leak detection.

**Async testing:** The `SinkRouter` uses a background drain thread. Tests use `assertBusy(() -> assertion, timeout, unit)` to poll until the drain thread flushes events. Thread-safe captured event lists use `synchronized` blocks.

**Thread cleanup:** Tests that create a `SinkRouter` must call `router.close()` in `tearDown()` to stop the drain thread. The test framework detects leaked threads and fails the test class if any remain.

**Mock sinks:** Tests use inline `AuditSink` implementations that capture events into a list, avoiding external dependencies.

## Adding New Tests

1. Create a test class in the matching `src/test/java/org/opensearch/audit/` subpackage
2. Extend `OpenSearchTestCase`
3. Name test methods `testXxx` (JUnit 3 convention — no `@Test` annotation needed)
4. If your test creates a `SinkRouter`, close it in `tearDown()` to avoid thread leaks
5. Run `./gradlew spotlessApply` before committing
