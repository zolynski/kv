#!/usr/bin/env bash
# Makes locally built images visible to Docker Desktop's Kubernetes.
set -euo pipefail

NODE="${KV_K8S_NODE:-desktop-control-plane}"
IMAGES=("${@:-}")
if [[ -z "${IMAGES[0]:-}" ]]; then
  IMAGES=(kv-shard:1.0.0 kv-router:1.0.0 kv-ui:1.0.0)
fi

if ! docker inspect "$NODE" >/dev/null 2>&1; then
  echo "Kubernetes node container '$NODE' not found." >&2
  echo "Enable Kubernetes in Docker Desktop, or set KV_K8S_NODE to the node's container name." >&2
  exit 1
fi

for image in "${IMAGES[@]}"; do
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    echo "skipping $image (not built locally)"
    continue
  fi
  echo "loading $image into $NODE"
  docker save "$image" | docker exec -i "$NODE" ctr -n k8s.io images import --digests=false -
done

echo "done"
