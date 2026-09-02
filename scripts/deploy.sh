#!/usr/bin/env bash
# Deployment script for de.zolynski.kv
set -euo pipefail

REPO_DIR="${DEPLOY_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"
LOCK="/tmp/kv-deploy.lock"

exec 9>"$LOCK"
if ! flock -n 9; then
	echo "deploy: another deploy is already running - skipping" >&2
	exit 0
fi

cd "$REPO_DIR"

after="$(git rev-parse --short HEAD)"
if git fetch --quiet origin main 2>/dev/null; then
	before="$after"
	git reset --hard --quiet origin/main
	after="$(git rev-parse --short HEAD)"
	[ "$before" = "$after" ] || echo "deploy: $before -> $after"
else
	echo "deploy: WARNING - cannot reach origin. Deploying the images anyway;" >&2
	echo "deploy:   docker-compose.yml and deploy/kv.caddy may be out of date." >&2
fi

echo "deploy: pulling images"
docker compose pull --quiet

echo "deploy: restarting and waiting for health"
if ! docker compose up -d --wait --wait-timeout 180; then
	echo "deploy: stack did not come up healthy - see 'docker compose ps' and 'docker compose logs'" >&2
	exit 1
fi

docker image prune -f >/dev/null 2>&1 || true
echo "deploy: done ($after)"
