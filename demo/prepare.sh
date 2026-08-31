#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "Maven install (:glowroot-agent-ui-sandbox -am)..."
mvn -B -pl :glowroot-agent-ui-sandbox -am install -DskipTests

RUNTIME="$ROOT/demo/runtime"
LIB="$RUNTIME/lib"
rm -rf "$RUNTIME"
mkdir -p "$LIB"

echo "Copying dependencies to demo/runtime/lib..."
mvn -B -pl :glowroot-agent-ui-sandbox dependency:copy-dependencies \
  -DincludeScope=test \
  -DoutputDirectory="$LIB"

cp -a "$ROOT/agent/ui-sandbox/target/classes" "$RUNTIME/classes"
cp -a "$ROOT/agent/ui-sandbox/target/test-classes" "$RUNTIME/test-classes"

echo "Ready. From repo root:"
echo "  docker compose -f demo/docker-compose.yml up --build"
