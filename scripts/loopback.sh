#!/bin/bash
# loopback.sh — single-machine end-to-end test for alt-p2p-http.
#
# Spawns: coordination server (alt-p2p) + a python HTTP server (the "app") +
# `serve` + `connect`, then fetches through the tunnel with curl and byte-compares.
# Exercises the mux with concurrent requests. Tears everything down.
#
# Usage:
#   scripts/loopback.sh
#
# Env knobs:
#   JAR=...        alt-p2p-http jar (default: newest target/alt-p2p-http-*.jar)
#   COORD_JAR=...  alt-p2p jar for the coord (default: newest ../alt-p2p/target jar)
#   PORT=9100      coord UDP port (TCP relay = PORT+1)
#   PSK=secret     pre-shared key
#   RELAY=1        add --force-relay to both peers (tests the relay carrier)
#   BUILD=1        run `mvn -q -DskipTests package` first
#   TIMEOUT=60     seconds to wait for the tunnel to come up
#
# Exit: 0 = all checks PASS, non-zero otherwise.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PORT="${PORT:-9100}"
PSK="${PSK:-secret}"
SESSION="${SESSION:-$(openssl rand -hex 16)}"
TIMEOUT="${TIMEOUT:-60}"
LOGDIR="$(mktemp -d /tmp/p2p-http-loopback.XXXX)"
WEBROOT="$(mktemp -d /tmp/p2p-http-webroot.XXXX)"
LOCAL_PORT="${LOCAL_PORT:-18080}"

if [ "${BUILD:-0}" = "1" ]; then
  echo "==> mvn -q -DskipTests package"; mvn -q -DskipTests package || { echo "build failed"; exit 1; }
fi

JAR="${JAR:-$(ls -t "$ROOT"/target/alt-p2p-http-*.jar 2>/dev/null | grep -v original- | head -1)}"
COORD_JAR="${COORD_JAR:-$(ls -t "$ROOT"/../alt-p2p/target/alt-p2p-*-SNAPSHOT.jar 2>/dev/null | grep -v original- | head -1)}"
[ -n "${JAR:-}" ] && [ -f "$JAR" ] || { echo "no alt-p2p-http jar (run with BUILD=1)"; exit 1; }
[ -n "${COORD_JAR:-}" ] && [ -f "$COORD_JAR" ] || { echo "no alt-p2p jar for coord (build ../alt-p2p)"; exit 1; }

EXTRA=()
[ "${RELAY:-0}" = "1" ] && EXTRA+=(--allow-relay --force-relay)

echo "JAR       : $JAR"
echo "COORD_JAR : $COORD_JAR"
echo "SESSION   : $SESSION   PORT: $PORT   RELAY: ${RELAY:-0}"
echo "LOGS      : $LOGDIR"
echo

# --- test content: a text file, a binary blob, a nested path -----------------
echo "hello over p2p" > "$WEBROOT/hello.txt"
mkdir -p "$WEBROOT/nested/deep"
echo "nested content" > "$WEBROOT/nested/deep/page.txt"
head -c 2097152 /dev/urandom > "$WEBROOT/blob.bin"     # 2 MB binary

COORD_PID=""; HTTP_PID=""; POST_PID=""; SERVE_PID=""; CONNECT_PID=""
SERVE2_PID=""; CONNECT2_PID=""
cleanup() {
  for p in "$CONNECT_PID" "$CONNECT2_PID" "$SERVE_PID" "$SERVE2_PID" "$HTTP_PID" "$POST_PID" "$COORD_PID"; do
    [ -n "$p" ] && kill "$p" 2>/dev/null
  done
}
trap cleanup EXIT

lsof -ti :"$PORT" 2>/dev/null | xargs kill 2>/dev/null || true

echo "==> coord server"
java -jar "$COORD_JAR" server --psk "$PSK" --port "$PORT" >"$LOGDIR/coord.log" 2>&1 &
COORD_PID=$!
sleep 1.5
kill -0 "$COORD_PID" 2>/dev/null || { echo "coord failed:"; cat "$LOGDIR/coord.log"; exit 1; }

echo "==> local HTTP server (the app being shared)"
APP_PORT=18123
( cd "$WEBROOT" && exec python3 -m http.server "$APP_PORT" --bind 127.0.0.1 ) >"$LOGDIR/http.log" 2>&1 &
HTTP_PID=$!

echo "==> POST echo server (port 18130)"
python3 "$ROOT/scripts/post-echo.py" >"$LOGDIR/post.log" 2>&1 &
POST_PID=$!
sleep 1
curl -sf -m 30 "http://127.0.0.1:$APP_PORT/hello.txt" >/dev/null || { echo "app server failed"; exit 1; }

echo "==> serve"
java -jar "$JAR" serve --port "$APP_PORT" -s "$SESSION" --psk "$PSK" \
  --server "127.0.0.1:$PORT" ${EXTRA[@]+"${EXTRA[@]}"} >"$LOGDIR/serve.log" 2>&1 &
SERVE_PID=$!
sleep 0.5

echo "==> connect"
java -jar "$JAR" connect --local-port "$LOCAL_PORT" -s "$SESSION" --psk "$PSK" \
  --server "127.0.0.1:$PORT" ${EXTRA[@]+"${EXTRA[@]}"} >"$LOGDIR/connect.log" 2>&1 &
CONNECT_PID=$!

echo "==> waiting for tunnel (timeout ${TIMEOUT}s)…"
elapsed=0
until grep -q "tunnel up" "$LOGDIR/connect.log" 2>/dev/null; do
  sleep 1; elapsed=$((elapsed+1))
  kill -0 "$CONNECT_PID" 2>/dev/null || { echo "connect died:"; tail -20 "$LOGDIR/connect.log"; tail -10 "$LOGDIR/serve.log"; exit 2; }
  [ "$elapsed" -ge "$TIMEOUT" ] && { echo "TIMEOUT"; tail -20 "$LOGDIR/connect.log"; tail -10 "$LOGDIR/serve.log"; exit 2; }
done
echo "    tunnel up after ~${elapsed}s"

FAIL=0
check() { if [ "$1" = "0" ]; then echo "  PASS: $2"; else echo "  FAIL: $2"; FAIL=1; fi; }

echo "==> checks through http://127.0.0.1:$LOCAL_PORT"

# 1. text file byte-identical
curl -sf -m 30 "http://127.0.0.1:$LOCAL_PORT/hello.txt" -o "$LOGDIR/hello.got"
cmp -s "$WEBROOT/hello.txt" "$LOGDIR/hello.got"; check $? "text file identical"

# 2. nested path
curl -sf -m 30 "http://127.0.0.1:$LOCAL_PORT/nested/deep/page.txt" -o "$LOGDIR/page.got"
cmp -s "$WEBROOT/nested/deep/page.txt" "$LOGDIR/page.got"; check $? "nested path"

# 3. 2 MB binary byte-identical
curl -sf -m 30 "http://127.0.0.1:$LOCAL_PORT/blob.bin" -o "$LOGDIR/blob.got"
cmp -s "$WEBROOT/blob.bin" "$LOGDIR/blob.got"; check $? "2 MB binary identical"

# 4. 404 passes through as a 404 (status codes are not invented by the tunnel)
code=$(curl -s -m 30 -o /dev/null -w "%{http_code}" "http://127.0.0.1:$LOCAL_PORT/absent")
[ "$code" = "404" ]; check $? "404 passes through (got $code)"

# 5. directory listing (the index page)
curl -sf -m 30 "http://127.0.0.1:$LOCAL_PORT/" | grep -q "hello.txt"; check $? "index page lists content"

# 6. concurrent requests exercise the mux (8 parallel binary fetches)
# NOTE: wait only for the curls — a bare `wait` blocks on the daemons too.
CONC_FAIL=0
CURL_PIDS=()
for i in 1 2 3 4 5 6 7 8; do
  curl -sf -m 60 "http://127.0.0.1:$LOCAL_PORT/blob.bin" -o "$LOGDIR/conc.$i" &
  CURL_PIDS+=($!)
done
wait "${CURL_PIDS[@]}"
for i in 1 2 3 4 5 6 7 8; do
  cmp -s "$WEBROOT/blob.bin" "$LOGDIR/conc.$i" || CONC_FAIL=1
done
check $CONC_FAIL "8 concurrent fetches all identical"

# 7. keep-alive reuse: two requests over one curl connection
curl -sf -m 30 "http://127.0.0.1:$LOCAL_PORT/hello.txt" "http://127.0.0.1:$LOCAL_PORT/hello.txt" -o /dev/null -o /dev/null
check $? "connection reuse (keep-alive)"

# 8-10. POST through a second session (the POST echo answers sha256+len of the body,
# so the assertion is end-to-end integrity, not just a 200)
POST_SESSION="${SESSION}p"
java -jar "$JAR" serve --port 18130 -s "$POST_SESSION" --psk "$PSK" \
  --server "127.0.0.1:$PORT" ${EXTRA[@]+"${EXTRA[@]}"} >"$LOGDIR/serve-post.log" 2>&1 &
SERVE2_PID=$!
sleep 0.5
java -jar "$JAR" connect --local-port $((LOCAL_PORT+1)) -s "$POST_SESSION" --psk "$PSK" \
  --server "127.0.0.1:$PORT" ${EXTRA[@]+"${EXTRA[@]}"} >"$LOGDIR/connect-post.log" 2>&1 &
CONNECT2_PID=$!
elapsed=0
until grep -q "tunnel up" "$LOGDIR/connect-post.log" 2>/dev/null; do
  sleep 1; elapsed=$((elapsed+1))
  [ "$elapsed" -ge "$TIMEOUT" ] && break
done
PU="http://127.0.0.1:$((LOCAL_PORT+1))/"

want=$(shasum -a 256 "$WEBROOT/blob.bin" | awk '{print $1}')
got=$(curl -sf -m 60 -X POST --data-binary @"$WEBROOT/blob.bin" \
      -H "Content-Type: application/octet-stream" "$PU" | awk '{print $1}')
[ "$got" = "$want" ]; check $? "POST 2 MB body arrives intact (sha256)"

got=$(curl -sf -m 60 -X POST --data-binary @"$WEBROOT/blob.bin" \
      -H "Transfer-Encoding: chunked" -H "Content-Type: application/octet-stream" "$PU" | awk '{print $1}')
[ "$got" = "$want" ]; check $? "POST chunked encoding arrives intact"

code=$(curl -s -m 60 -o /dev/null -w "%{http_code}" -F "file=@$WEBROOT/blob.bin" "$PU")
[ "$code" = "200" ]; check $? "POST multipart upload accepted (got $code)"

echo
if [ "$FAIL" = "0" ]; then
  echo "RESULT: PASS (relay=${RELAY:-0})"
else
  echo "RESULT: FAIL — logs in $LOGDIR"
  exit 1
fi
