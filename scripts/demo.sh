#!/usr/bin/env bash
# Opens the deployed cluster on localhost.
set -euo pipefail

NS="${KV_NAMESPACE:-kv}"
UI_PORT="${KV_UI_PORT:-4200}"
ROUTER_PORT="${KV_ROUTER_PORT:-18080}"

cleanup() { kill $(jobs -p) 2>/dev/null || true; }
trap cleanup EXIT INT TERM

kubectl -n "$NS" rollout status deployment/kv-ui --timeout=120s
kubectl -n "$NS" rollout status deployment/kv-router --timeout=120s

kubectl -n "$NS" port-forward svc/kv-ui "$UI_PORT:8080" >/dev/null 2>&1 &
kubectl -n "$NS" port-forward svc/kv-router "$ROUTER_PORT:8080" >/dev/null 2>&1 &
sleep 3

cat <<INFO

  UI       http://localhost:${UI_PORT}
  API      http://localhost:${ROUTER_PORT}/api/v1

  curl -X PUT http://localhost:${ROUTER_PORT}/api/v1/keys/greeting \\
       -H 'Content-Type: text/plain;charset=UTF-8' --data-binary 'hello' -i
  curl http://localhost:${ROUTER_PORT}/api/v1/topology

  Ctrl-C to stop.

INFO
wait
