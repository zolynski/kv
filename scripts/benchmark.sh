#!/usr/bin/env bash
# Runs the JMH suite and writes results to benchmarks/.
#   scripts/benchmark.sh              # default: 1 and 8 threads, all benchmarks
#   scripts/benchmark.sh 'get.*' 4    # a filter and a thread count
set -euo pipefail

cd "$(dirname "$0")/.."
FILTER="${1:-.*}"
THREADS="${2:-}"

mvn -B -q install -DskipTests -pl .,kv-core,kv-shard
mvn -B -q package -DskipTests -pl kv-benchmarks
mvn -B -q -pl kv-benchmarks dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=runtime

CP="kv-benchmarks/target/classes:kv-core/target/classes:kv-shard/target/classes:$(cat kv-benchmarks/target/cp.txt)"
mkdir -p benchmarks

run() {
  local threads="$1"
  echo "=== ${threads} thread(s) ==="
  java -cp "$CP" org.openjdk.jmh.Main "$FILTER" \
       -t "$threads" -rf json -rff "benchmarks/results-t${threads}.json" \
    | tee "benchmarks/results-t${threads}.txt"
}

if [[ -n "$THREADS" ]]; then
  run "$THREADS"
else
  run 1
  run 8
fi
