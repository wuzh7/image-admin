#!/usr/bin/env bash
set -Eeuo pipefail

prod_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
publisher="$prod_root/publish-prod.sh"

bash -n "$publisher"
"$publisher" --help >/dev/null
if "$publisher" --version '../invalid' >/dev/null 2>&1; then
    echo 'Invalid version was accepted.' >&2
    exit 1
fi

source_text=$(<"$publisher")
[[ $(grep -o -- '--platform linux/amd64' <<< "$source_text" | wc -l | tr -d ' ') = 3 ]]
[[ $(grep -o -- '--format.*Architecture' <<< "$source_text" | wc -l | tr -d ' ') = 3 ]]
[[ $(grep -o 'image-admin-\(backend\|frontend\|render-worker\):rollback' <<< "$source_text" | wc -l | tr -d ' ') = 3 ]]
[[ $source_text != *'docker image prune'* && $source_text != *'docker system prune'* ]]

echo 'PASS: macOS Bash syntax, argument validation, amd64 checks, and scoped image retention.'
