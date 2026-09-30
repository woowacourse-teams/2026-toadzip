#!/bin/sh
set -eu

: "${RENEWED_LINEAGE:?Run as a Certbot deploy hook with RENEWED_LINEAGE set}"

# Inspect only container state and mount paths, never application environment values.
candidates=$(docker ps --all \
  --filter label=com.docker.compose.service=frontend \
  --filter label=com.docker.compose.oneoff=False \
  --format '{{.ID}}')

matching_count=0
running_count=0
running_container=
for candidate in $candidates; do
  metadata=$(docker inspect \
    --format '{{.State.Running}}{{range .Mounts}}{{printf "\n%s" .Source}}{{end}}' \
    "$candidate")
  if ! printf '%s\n' "$metadata" | sed '1d' | grep -Fxq -- "$RENEWED_LINEAGE"; then
    continue
  fi

  matching_count=$((matching_count + 1))
  if [ "$(printf '%s\n' "$metadata" | sed -n '1p')" = true ]; then
    running_count=$((running_count + 1))
    running_container=$candidate
  fi
done

# A host-wide hook can also receive renewals unrelated to this application.
if [ "$matching_count" -eq 0 ]; then
  echo "No frontend uses the renewed certificate; skipping Nginx reload."
  exit 0
fi

if [ "$running_count" -ne 1 ]; then
  echo "Expected one running frontend using the renewed certificate; found $running_count. Nginx was not reloaded." >&2
  exit 1
fi

docker exec "$running_container" nginx -c /tmp/https.conf -t
docker exec "$running_container" nginx -c /tmp/https.conf -s reload
