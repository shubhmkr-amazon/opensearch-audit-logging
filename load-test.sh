#!/bin/bash
# Load test for the OpenSearch Audit Logging Plugin
# Tests ring buffer backpressure and search aggregation under high throughput
#
# Usage:
#   ./load-test.sh                    # defaults: localhost:9200, 10 clients, 30s
#   ./load-test.sh <host> <clients> <duration_seconds>

set -euo pipefail

HOST="${1:-localhost:9200}"
CLIENTS="${2:-10}"
DURATION="${3:-30}"
INDEX="audit-loadtest"

echo "=== Audit Plugin Load Test ==="
echo "Host:     $HOST"
echo "Clients:  $CLIENTS parallel"
echo "Duration: ${DURATION}s"
echo ""

# Check connectivity
if ! curl -sf "$HOST" > /dev/null 2>&1; then
  echo "ERROR: Cannot reach $HOST"
  exit 1
fi

# Snapshot stats before
STATS_BEFORE=$(curl -s "$HOST/_plugins/_audit/stats")
TOTAL_BEFORE=$(echo "$STATS_BEFORE" | python3 -c "import sys,json; print(json.load(sys.stdin)['total_events'])")
FAILED_BEFORE=$(echo "$STATS_BEFORE" | python3 -c "import sys,json; print(json.load(sys.stdin)['failed_events'])")

# Create test index with multiple shards
echo "--- Setup ---"
curl -s -X DELETE "$HOST/$INDEX" > /dev/null 2>&1 || true
curl -s -X PUT "$HOST/$INDEX" -H 'Content-Type: application/json' -d '{
  "settings": {"number_of_shards": 5, "number_of_replicas": 0}
}' > /dev/null
echo "Created index '$INDEX' with 5 shards"

# Seed some documents
for i in $(seq 1 50); do
  curl -s -X PUT "$HOST/$INDEX/_doc/$i" -H 'Content-Type: application/json' \
    -d "{\"msg\":\"seed doc $i\",\"value\":$i}" > /dev/null
done
curl -s -X POST "$HOST/$INDEX/_refresh" > /dev/null
echo "Seeded 50 documents"
echo ""

# --- Write load ---
write_worker() {
  local id=$1
  local end=$((SECONDS + DURATION))
  local count=0
  while [ $SECONDS -lt $end ]; do
    curl -s -X POST "$HOST/$INDEX/_doc" -H 'Content-Type: application/json' \
      -d "{\"worker\":$id,\"ts\":\"$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)\",\"val\":$count}" > /dev/null
    count=$((count + 1))
  done
  echo "$count" > "/tmp/audit-loadtest-write-$id"
}

# --- Search load ---
search_worker() {
  local id=$1
  local end=$((SECONDS + DURATION))
  local count=0
  while [ $SECONDS -lt $end ]; do
    curl -s "$HOST/$INDEX/_search" -H 'Content-Type: application/json' \
      -d '{"query":{"match_all":{}},"size":5}' > /dev/null
    count=$((count + 1))
  done
  echo "$count" > "/tmp/audit-loadtest-search-$id"
}

# --- Bulk write load ---
bulk_worker() {
  local id=$1
  local end=$((SECONDS + DURATION))
  local count=0
  while [ $SECONDS -lt $end ]; do
    local body=""
    for j in $(seq 1 20); do
      body="${body}{\"index\":{}}\n{\"worker\":$id,\"batch\":$count,\"item\":$j}\n"
    done
    echo -e "$body" | curl -s -X POST "$HOST/$INDEX/_bulk" -H 'Content-Type: application/x-ndjson' --data-binary @- > /dev/null
    count=$((count + 20))
  done
  echo "$count" > "/tmp/audit-loadtest-bulk-$id"
}

echo "--- Running load test (${DURATION}s) ---"
echo "Starting $CLIENTS write workers, $CLIENTS search workers, $((CLIENTS / 2 > 0 ? CLIENTS / 2 : 1)) bulk workers..."
echo ""

# Clean up temp files
rm -f /tmp/audit-loadtest-*

# Launch workers
HALF_CLIENTS=$((CLIENTS / 2 > 0 ? CLIENTS / 2 : 1))
for i in $(seq 1 "$CLIENTS"); do
  write_worker "$i" &
  search_worker "$i" &
done
for i in $(seq 1 "$HALF_CLIENTS"); do
  bulk_worker "$i" &
done

# Monitor progress
START_TIME=$SECONDS
while [ $((SECONDS - START_TIME)) -lt "$DURATION" ]; do
  sleep 5
  ELAPSED=$((SECONDS - START_TIME))
  CURRENT=$(curl -s "$HOST/_plugins/_audit/stats" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(f\"events={d['total_events']} failed={d['failed_events']}\")" 2>/dev/null || echo "...")
  echo "  [${ELAPSED}s] $CURRENT"
done

# Wait for all workers
wait

# Tally results
sleep 2  # let drain thread flush
TOTAL_WRITES=0
TOTAL_SEARCHES=0
TOTAL_BULKS=0
for i in $(seq 1 "$CLIENTS"); do
  TOTAL_WRITES=$((TOTAL_WRITES + $(cat "/tmp/audit-loadtest-write-$i" 2>/dev/null || echo 0)))
  TOTAL_SEARCHES=$((TOTAL_SEARCHES + $(cat "/tmp/audit-loadtest-search-$i" 2>/dev/null || echo 0)))
done
for i in $(seq 1 "$HALF_CLIENTS"); do
  TOTAL_BULKS=$((TOTAL_BULKS + $(cat "/tmp/audit-loadtest-bulk-$i" 2>/dev/null || echo 0)))
done

# Snapshot stats after
STATS_AFTER=$(curl -s "$HOST/_plugins/_audit/stats")
TOTAL_AFTER=$(echo "$STATS_AFTER" | python3 -c "import sys,json; print(json.load(sys.stdin)['total_events'])")
FAILED_AFTER=$(echo "$STATS_AFTER" | python3 -c "import sys,json; print(json.load(sys.stdin)['failed_events'])")

NEW_EVENTS=$((TOTAL_AFTER - TOTAL_BEFORE))
NEW_FAILED=$((FAILED_AFTER - FAILED_BEFORE))
EVENTS_PER_SEC=$((NEW_EVENTS / DURATION))

echo ""
echo "=== Results ==="
echo ""
echo "Operations performed:"
echo "  Writes:   $TOTAL_WRITES"
echo "  Searches: $TOTAL_SEARCHES (each hits 5 shards)"
echo "  Bulk:     $TOTAL_BULKS docs"
echo ""
echo "Audit events:"
echo "  Total new:  $NEW_EVENTS"
echo "  Failed:     $NEW_FAILED"
echo "  Throughput: ~${EVENTS_PER_SEC} events/sec"
echo ""

# Detailed category breakdown
echo "By category:"
echo "$STATS_AFTER" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for cat, count in sorted(d['by_category'].items()):
    print(f'  {cat}: {count}')
"

echo ""
echo "Sink stats:"
echo "$STATS_AFTER" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for sink, count in d['by_sink'].items():
    print(f'  {sink}: {count}')
"

# Health check
echo ""
echo "Health:"
curl -s "$HOST/_plugins/_audit/health" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(f'  Overall: {d[\"overall\"]}')
for sink, info in d['sinks'].items():
    print(f'  {sink}: {\"healthy\" if info[\"healthy\"] else \"UNHEALTHY\"}')"

# Search aggregation effectiveness
RAW_SEARCH_EVENTS=$((TOTAL_SEARCHES * 5 * 2))  # shards * (query + fetch)
ACTUAL_READ=$(echo "$STATS_AFTER" | python3 -c "import sys,json; print(json.load(sys.stdin)['by_category'].get('DOCUMENT_READ', 0))")
echo ""
echo "Search aggregation:"
echo "  Raw shard events (without aggregation): ~$RAW_SEARCH_EVENTS"
echo "  Actual DOCUMENT_READ events:            $ACTUAL_READ"
if [ "$RAW_SEARCH_EVENTS" -gt 0 ]; then
  REDUCTION=$(python3 -c "print(f'{(1 - $ACTUAL_READ / $RAW_SEARCH_EVENTS) * 100:.0f}%')")
  echo "  Reduction:                                $REDUCTION"
fi

# Cleanup
echo ""
echo "--- Cleanup ---"
curl -s -X DELETE "$HOST/$INDEX" > /dev/null
rm -f /tmp/audit-loadtest-*
echo "Done."
