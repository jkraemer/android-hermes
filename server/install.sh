#!/bin/sh
# Installs the hermes-app plugin into a Hermes home: install.sh [HERMES_HOME]
set -eu

source_dir=$(cd "$(dirname "$0")" && pwd)/hermes-app
hermes_home=${1:-${HERMES_HOME:-$HOME/.hermes}}
target=$hermes_home/plugins/hermes-app

rm -rf "$target"
mkdir -p "$target"
(cd "$source_dir" && find . -name __pycache__ -prune -o -type f -print | while read -r file; do
    mkdir -p "$target/$(dirname "$file")"
    cp "$file" "$target/$file"
done)

cat <<STEPS
Installed hermes-app into $target

Next steps:
  1. Enable the plugin:  hermes plugins enable hermes-app
     (or add hermes-app to plugins.enabled in $hermes_home/config.yaml)
  2. Restart both the Hermes dashboard and the Hermes gateway.
STEPS
