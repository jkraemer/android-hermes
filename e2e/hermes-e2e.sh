#!/usr/bin/env bash
# Disposable Hermes Agent for end-to-end tests.
#   hermes-e2e.sh up    clone/install (once), configure, start dashboard + gateway, write the env file
#   hermes-e2e.sh down  stop both processes
#   hermes-e2e.sh env   print the env file path
# Model credentials come from E2E_PROVIDER, E2E_MODEL and E2E_API_KEY.
set -euo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE="$E2E_DIR/.state"
SRC="$STATE/src"
VENV="$STATE/venv"
HOME_DIR="$STATE/home"
ENV_FILE="$STATE/env"
REPO_URL="https://github.com/NousResearch/hermes-agent"
PORT=19119
USERNAME=e2e
PROCESSES=(dashboard gateway)

die() { echo "hermes-e2e: $*" >&2; exit 1; }

pid_file() { echo "$STATE/$1.pid"; }

is_running() {
  local file; file="$(pid_file "$1")"
  [[ -f "$file" ]] && kill -0 "$(cat "$file")" 2>/dev/null
}

# A random value generated on first use and kept, so repeated `up` runs agree.
persistent_secret() {
  local file="$STATE/$1"
  [[ -f "$file" ]] || { head -c 24 /dev/urandom | base64 | tr -d '/+=\n' > "$file"; chmod 600 "$file"; }
  cat "$file"
}

checkout_source() {
  local ref; ref="$(tr -d '[:space:]' < "$E2E_DIR/hermes-version")"
  [[ -n "$ref" ]] || die "e2e/hermes-version is empty"
  if [[ "$(cat "$STATE/src-ref" 2>/dev/null || true)" == "$ref" ]]; then return; fi
  echo "checking out hermes-agent $ref"
  rm -rf "$SRC" "$STATE/src-ref" "$STATE/venv-ref"
  git init -q "$SRC"
  git -C "$SRC" fetch -q --depth 1 "$REPO_URL" "$ref"
  git -C "$SRC" -c advice.detachedHead=false checkout -q FETCH_HEAD
  echo "$ref" > "$STATE/src-ref"
}

install_source() {
  [[ "$(cat "$STATE/venv-ref" 2>/dev/null || true)" == "$(cat "$STATE/src-ref")" ]] && return
  echo "installing hermes-agent into $VENV"
  # The upstream lock file is the resolution Hermes is tested with; a plain
  # `uv pip install` of the same source fails on conflicting pins.
  UV_PROJECT_ENVIRONMENT="$VENV" uv sync -q --project "$SRC" --frozen --extra web \
    >> "$STATE/install.log" 2>&1 || die "install failed, see $STATE/install.log"
  cp "$STATE/src-ref" "$STATE/venv-ref"
}

write_config() {
  mkdir -p "$HOME_DIR"
  cat > "$HOME_DIR/config.yaml" <<YAML
model:
  default: "$E2E_MODEL"
  provider: "$E2E_PROVIDER"
  api_key: "$E2E_API_KEY"
approvals:
  mode: manual
  timeout: 300
dashboard:
  # A public host makes the dashboard demand a login even on the loopback bind.
  public_url: http://hermes-e2e.invalid:$PORT
  basic_auth:
    username: $USERNAME
    password: "$(persistent_secret password)"
    secret: "$(persistent_secret signing-secret)"
YAML
  chmod 600 "$HOME_DIR/config.yaml"
}

write_env_file() {
  cat > "$ENV_FILE" <<ENV
HERMES_URL=http://127.0.0.1:$PORT
HERMES_USER=$USERNAME
HERMES_PASSWORD=$(persistent_secret password)
HERMES_HOME=$HOME_DIR
HERMES_BIN=$VENV/bin/hermes
ENV
  chmod 600 "$ENV_FILE"
}

start_process() {
  local name="$1"; shift
  if is_running "$name"; then echo "$name already running"; return; fi
  # setsid gives each process its own group so `down` can stop its children too.
  HERMES_HOME="$HOME_DIR" HERMES_YOLO_MODE= setsid "$VENV/bin/hermes" "$@" \
    >> "$STATE/$name.log" 2>&1 < /dev/null &
  echo $! > "$(pid_file "$name")"
}

wait_for_dashboard() {
  local url="http://127.0.0.1:$PORT/api/auth/providers"
  for _ in $(seq 1 120); do
    is_running dashboard || die "dashboard exited, see $STATE/dashboard.log"
    if curl -fsS -o /dev/null "$url" 2>/dev/null; then return; fi
    sleep 1
  done
  die "dashboard did not answer $url within 120 s, see $STATE/dashboard.log"
}

up() {
  : "${E2E_PROVIDER:?set E2E_PROVIDER (model provider id, e.g. openrouter)}"
  : "${E2E_MODEL:?set E2E_MODEL (model name)}"
  : "${E2E_API_KEY:?set E2E_API_KEY (API key for the provider)}"
  command -v uv > /dev/null || die "uv is required"
  mkdir -p "$STATE"
  checkout_source
  install_source
  write_config
  start_process dashboard dashboard --host 127.0.0.1 --port "$PORT" --no-open
  start_process gateway gateway run
  wait_for_dashboard
  write_env_file
  echo "hermes e2e up; environment in $ENV_FILE"
}

down() {
  local name file pid
  for name in "${PROCESSES[@]}"; do
    file="$(pid_file "$name")"
    [[ -f "$file" ]] || continue
    pid="$(cat "$file")"
    if kill -0 "$pid" 2>/dev/null; then
      kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
      for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
      if kill -0 "$pid" 2>/dev/null; then kill -9 -- "-$pid" 2>/dev/null || kill -9 "$pid" 2>/dev/null || true; fi
    fi
    rm -f "$file"
  done
  echo "hermes e2e down"
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  env) echo "$ENV_FILE" ;;
  *) die "usage: $0 up|down|env" ;;
esac
