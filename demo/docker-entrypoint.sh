#!/bin/sh
set -eu

DATA_DIR="/opt/glowroot-demo/target"
mkdir -p "$DATA_DIR"

ADMIN_JSON="$DATA_DIR/admin.json"
# Default embedded bind is 127.0.0.1 — unreachable from outside the container.
if [ ! -f "$ADMIN_JSON" ]; then
  printf '%s\n' '{"web":{"port":4000,"bindAddress":"0.0.0.0"}}' > "$ADMIN_JSON"
fi

# Facsimile demo defaults (overwrite so an old Sandbox volume does not stick).
printf '%s\n' '{"transactions":{"profilingIntervalMillis":100},"ui":{"defaultTransactionType":"Web"}}' \
  > "$DATA_DIR/config.json"

cd /opt/glowroot-demo
# shellcheck disable=SC2086
exec java ${JAVA_OPTS} \
  -Dglowroot.agent.port=4000 \
  -Dglowroot.demo=true \
  -cp "test-classes:classes:lib/*" \
  org.glowroot.agent.ui.sandbox.UiSandboxMain
