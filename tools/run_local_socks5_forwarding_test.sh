#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROXY_SCRIPT="$ROOT_DIR/tools/local_socks5_proxy.py"
PROXY_BIND_HOST="0.0.0.0"
PROXY_HOST_FOR_TEST="10.0.2.2"
PROXY_PORT="19027"
TEST_CLASS="com.infinitezerone.pratice.VpnProxyForwardingTest"
LOG_FILE="$ROOT_DIR/build/local-socks5-proxy.log"
declare -a EXTRA_GRADLE_ARGS=()

usage() {
  cat <<'EOF'
Usage:
  tools/run_local_socks5_forwarding_test.sh [options] [-- <extra gradle args>]

Options:
  --test-class <class>        AndroidTest class to run
  --proxy-port <port>         Local SOCKS5 proxy port (default: 19027)
  --proxy-host <host>         Host passed to instrumentation (default: 10.0.2.2)
  --proxy-bind-host <host>    Host used by local SOCKS5 server bind (default: 0.0.0.0)
  -h, --help                  Show help

Examples:
  tools/run_local_socks5_forwarding_test.sh
  tools/run_local_socks5_forwarding_test.sh --test-class com.infinitezerone.pratice.VpnHttpProxyForwardingTest
EOF
}

while (($# > 0)); do
  case "$1" in
    --test-class)
      TEST_CLASS="${2:?missing value for --test-class}"
      shift 2
      ;;
    --proxy-port)
      PROXY_PORT="${2:?missing value for --proxy-port}"
      shift 2
      ;;
    --proxy-host)
      PROXY_HOST_FOR_TEST="${2:?missing value for --proxy-host}"
      shift 2
      ;;
    --proxy-bind-host)
      PROXY_BIND_HOST="${2:?missing value for --proxy-bind-host}"
      shift 2
      ;;
    --)
      shift
      EXTRA_GRADLE_ARGS=("$@")
      break
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ ! -x "$PROXY_SCRIPT" ]]; then
  echo "Proxy script not executable: $PROXY_SCRIPT" >&2
  exit 1
fi

if ! [[ "$PROXY_PORT" =~ ^[0-9]+$ ]] || ((PROXY_PORT < 1 || PROXY_PORT > 65535)); then
  echo "Invalid --proxy-port: $PROXY_PORT" >&2
  exit 1
fi

mkdir -p "$ROOT_DIR/build"
: > "$LOG_FILE"

cleanup() {
  if [[ -n "${PROXY_PID:-}" ]] && kill -0 "$PROXY_PID" 2>/dev/null; then
    kill "$PROXY_PID" 2>/dev/null || true
    wait "$PROXY_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

echo "Starting local SOCKS5 proxy on ${PROXY_BIND_HOST}:${PROXY_PORT} ..."
python3 -u "$PROXY_SCRIPT" --host "$PROXY_BIND_HOST" --port "$PROXY_PORT" >"$LOG_FILE" 2>&1 &
PROXY_PID=$!

for _ in {1..50}; do
  if grep -q "listening on" "$LOG_FILE"; then
    break
  fi
  if ! kill -0 "$PROXY_PID" 2>/dev/null; then
    echo "Failed to start local SOCKS5 proxy. Log:" >&2
    cat "$LOG_FILE" >&2
    exit 1
  fi
  sleep 0.2
done

if ! grep -q "listening on" "$LOG_FILE"; then
  echo "Timed out waiting for SOCKS5 proxy startup. Log:" >&2
  cat "$LOG_FILE" >&2
  exit 1
fi

echo "Running connected test: $TEST_CLASS"
GRADLE_CMD=(
  "$ROOT_DIR/gradlew" :app:connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS"
  -Pandroid.testInstrumentationRunnerArguments.proxyHost="$PROXY_HOST_FOR_TEST"
  -Pandroid.testInstrumentationRunnerArguments.proxyPort="$PROXY_PORT"
)

if ((${#EXTRA_GRADLE_ARGS[@]} > 0)); then
  GRADLE_CMD+=("${EXTRA_GRADLE_ARGS[@]}")
fi

"${GRADLE_CMD[@]}"

echo "Done. Proxy log: $LOG_FILE"
