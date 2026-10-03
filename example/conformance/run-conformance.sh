#!/usr/bin/env bash
# Starts the conformance server, runs the official conformance suite for the three supported protocol revisions
# against it (with expected-failures.yml as baseline) and stops the server. Needs sbt, java, node and npx (the suite
# is downloaded by npx). The exit code is not 0 if a scenario fails that is not in the baseline (or the other way
# round: if a baselined scenario passes).
#
#   PORT         port of the server (default 3000)
#   SUITE        npm package of the suite (default @modelcontextprotocol/conformance@0.2.0-alpha.12)
#   OUT_DIR      where the results (checks.json per scenario) are written (default: a temporary directory)
#   SERVER_URL   test a server that is already running instead of starting the example (no sbt needed)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASELINE="$ROOT/example/conformance/expected-failures.yml"
PORT="${PORT:-3000}"
SUITE="${SUITE:-@modelcontextprotocol/conformance@0.2.0-alpha.12}"
OUT_DIR="${OUT_DIR:-$(mktemp -d -t mcp-conformance.XXXXXX)}"
URL="${SERVER_URL:-http://127.0.0.1:$PORT/mcp}"
SERVER_PID=""
mkdir -p "$OUT_DIR"

stop_server() {
  if [[ -n "$SERVER_PID" ]]; then
    kill "$SERVER_PID" 2>/dev/null
    wait "$SERVER_PID" 2>/dev/null
  fi
}
trap stop_server EXIT

if [[ -z "${SERVER_URL:-}" ]]; then
  echo "Building the conformance server ..."
  CLASSPATH_LINE="$(cd "$ROOT" && sbt -batch -error "export exampleConformance/Runtime/fullClasspath" | tail -n 1)"
  if [[ -z "$CLASSPATH_LINE" ]]; then
    echo "Could not get the classpath of the conformance server" >&2
    exit 2
  fi
  java -cp "$CLASSPATH_LINE" ch.linkyard.mcp.example.conformance.ConformanceMcpServer "$PORT" \
    > "$OUT_DIR/server.log" 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 1 60); do
    # any http answer means that the server is up
    if curl -s -o /dev/null "$URL"; then break; fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      echo "The server stopped, see $OUT_DIR/server.log" >&2
      exit 2
    fi
    sleep 1
  done
fi

FAILED=0
run_suite() {
  local label="$1"
  shift
  echo
  echo "=== $label ==="
  if ! (cd "$OUT_DIR" && npx --yes "$SUITE" server --url "$URL" --expected-failures "$BASELINE" \
    --output-dir "$OUT_DIR/$label" "$@"); then
    echo "FAILED: $label"
    FAILED=1
  fi
}

run_suite "2026-07-28-all" --spec-version 2026-07-28 --suite all
run_suite "2026-07-28-requirements" --requirements 2026-07-28
run_suite "2025-11-25" --spec-version 2025-11-25
run_suite "2025-06-18" --spec-version 2025-06-18

echo
echo "Results: $OUT_DIR"
exit "$FAILED"
