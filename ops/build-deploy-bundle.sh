#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 2 || ! $1 =~ ^[a-f0-9]{40}$ || ! -f $2 ]]; then
    printf 'Usage: %s GIT_SHA BACKEND_JAR\n' "$0" >&2
    exit 2
fi

revision=$1
staging=$(mktemp -d)
trap 'rm -rf -- "$staging"' EXIT
install -m 0644 "$2" "$staging/app.jar"
git archive "$revision" ai-service/app ai-service/requirements.txt ai-service/requirements-models.txt |
    tar -xf - -C "$staging"
printf '%s\n' "$revision" >"$staging/REVISION"
tar -czf "lawground-$revision.tar.gz" -C "$staging" REVISION app.jar ai-service
