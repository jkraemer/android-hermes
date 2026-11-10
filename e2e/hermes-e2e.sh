#!/usr/bin/env bash
# Disposable Hermes Agent for end-to-end tests.
#   hermes-e2e.sh up    check out, build the image (once), configure, install the hermes-app plugin,
#                       start the Hermes and ntfy containers, write the env file
#   hermes-e2e.sh down  stop the containers
#   hermes-e2e.sh env   print the env file path
# Model credentials come from E2E_PROVIDER, E2E_MODEL and E2E_API_KEY.
set -euo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE="$E2E_DIR/.state"
SRC="$STATE/src"
HOME_DIR="$STATE/home"
ENV_FILE="$STATE/env"
REPO_URL="https://github.com/NousResearch/hermes-agent"
PORT=19119
USERNAME=e2e
CONTAINER=hermes-e2e
# A local ntfy that the hermes-app plugin pushes to: Hermes reaches it by name on a shared
# network, the host (where the tests poll it) through a loopback port.
NETWORK=hermes-e2e
NTFY_CONTAINER=hermes-e2e-ntfy
NTFY_IMAGE=docker.io/binwiederhier/ntfy:v2.34.0
NTFY_PORT=19120

die() { echo "hermes-e2e: $*" >&2; exit 1; }

container_running() { [[ "$(podman inspect -f '{{.State.Running}}' "${1:-$CONTAINER}" 2>/dev/null)" == true ]]; }

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
  rm -rf "$SRC" "$STATE/src-ref"
  git init -q "$SRC"
  git -C "$SRC" fetch -q --depth 1 "$REPO_URL" "$ref"
  git -C "$SRC" -c advice.detachedHead=false checkout -q FETCH_HEAD
  echo "$ref" > "$STATE/src-ref"
}

build_image() {
  podman image exists "$IMAGE" && return
  echo "building $IMAGE (first build takes a while)"
  # buildah rejects the symbolic `COPY --chmod=a+rX,go-w` the upstream Dockerfile uses
  # (docker accepts it); apply the same permissions with a chmod step instead.
  sed 's|^COPY --link --chmod=a+rX,go-w \. \.$|COPY . .\nRUN chmod -R a+rX,go-w /opt/hermes|' "$SRC/Dockerfile" > "$STATE/Dockerfile.podman"
  grep -q '^RUN chmod -R a+rX,go-w /opt/hermes' "$STATE/Dockerfile.podman" || die "upstream Dockerfile changed; adapt build_image"
  podman build -q -f "$STATE/Dockerfile.podman" -t "$IMAGE" "$SRC" >> "$STATE/build.log" 2>&1 \
    || die "image build failed, see $STATE/build.log"
}

write_config() {
  mkdir -p "$HOME_DIR"
  cat > "$HOME_DIR/config.yaml" <<YAML
model:
  default: "$E2E_MODEL"
  provider: "$E2E_PROVIDER"
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
plugins:
  enabled: [hermes-app]
YAML
  chmod 600 "$HOME_DIR/config.yaml"
}

# Hermes reads provider keys from its credential pool (or the provider's env var), not
# from config.yaml's model section, so the key goes in through `hermes auth add`.
# The provider's existing entries are removed first (this home holds only ours) to keep
# `up` repeatable; `auth remove` takes an index because identical labels are ambiguous.
add_model_key() {
  while "$E2E_DIR/hermes-cli" auth remove "$E2E_PROVIDER" 1 > /dev/null 2>&1; do :; done
  "$E2E_DIR/hermes-cli" auth add "$E2E_PROVIDER" --type api-key --label e2e --api-key "$E2E_API_KEY" > /dev/null \
    || die "could not add the $E2E_PROVIDER key (is E2E_PROVIDER a provider id that takes an API key?)"
}

# Hermes loads plugins when its processes start, so this runs before the container starts.
install_plugin() {
  "$E2E_DIR/../server/install.sh" "$HOME_DIR" > /dev/null || die "could not install the hermes-app plugin"
}

ensure_network() {
  podman network exists "$NETWORK" || podman network create "$NETWORK" > /dev/null
}

# Tests poll ntfy about once a second, which its per-visitor request limit would soon refuse;
# this ntfy only serves the tests, so no visitor is limited.
start_ntfy() {
  if container_running "$NTFY_CONTAINER"; then echo "$NTFY_CONTAINER already running"; return; fi
  podman run -d --rm --name "$NTFY_CONTAINER" --network "$NETWORK" \
    -p "127.0.0.1:$NTFY_PORT:80" \
    -e NTFY_VISITOR_REQUEST_LIMIT_EXEMPT_HOSTS=0.0.0.0/0 \
    "$NTFY_IMAGE" serve > /dev/null
}

wait_for_ntfy() {
  local url="http://127.0.0.1:$NTFY_PORT/v1/health"
  for _ in $(seq 1 30); do
    container_running "$NTFY_CONTAINER" || die "ntfy exited, see: podman logs $NTFY_CONTAINER"
    if curl -fsS -o /dev/null "$url" 2>/dev/null; then return; fi
    sleep 1
  done
  die "ntfy did not answer $url within 30 s"
}

write_env_file() {
  cat > "$ENV_FILE" <<ENV
HERMES_URL=http://127.0.0.1:$PORT
HERMES_USER=$USERNAME
HERMES_PASSWORD=$(persistent_secret password)
HERMES_HOME=$HOME_DIR
HERMES_BIN=$E2E_DIR/hermes-cli
NTFY_URL=http://127.0.0.1:$NTFY_PORT
NTFY_URL_FROM_HERMES=http://$NTFY_CONTAINER
ENV
  chmod 600 "$ENV_FILE"
}

# One container runs the gateway (the image's main program) and, supervised by
# s6, the dashboard. Hermes and everything the agent executes stay inside it; only
# HERMES_HOME is shared with the host, at the same path so tests can inspect it.
start_container() {
  if container_running; then echo "$CONTAINER already running"; return; fi
  # keep-id maps the host user to the image's own "hermes" user (uid/gid 10000), so files
  # the agent writes to HERMES_HOME belong to the host user; --user root keeps the
  # image's root bootstrap working.
  # :Z relabels the home directory for SELinux, which would otherwise deny writes.
  podman run -d --rm --name "$CONTAINER" --network "$NETWORK" \
    --userns=keep-id:uid=10000,gid=10000 --user root \
    -p "127.0.0.1:$PORT:$PORT" \
    -v "$HOME_DIR:$HOME_DIR:Z" -e "HERMES_HOME=$HOME_DIR" \
    -e HERMES_DASHBOARD=1 -e HERMES_DASHBOARD_HOST=0.0.0.0 -e "HERMES_DASHBOARD_PORT=$PORT" \
    -e HERMES_YOLO_MODE= \
    "$IMAGE" gateway run > /dev/null
}

wait_for_dashboard() {
  local url="http://127.0.0.1:$PORT/api/auth/providers"
  for _ in $(seq 1 180); do
    container_running || die "container exited, see: podman logs $CONTAINER"
    if curl -fsS -o /dev/null "$url" 2>/dev/null; then return; fi
    sleep 1
  done
  die "dashboard did not answer $url within 180 s"
}

up() {
  : "${E2E_PROVIDER:?set E2E_PROVIDER (model provider id, e.g. openrouter)}"
  : "${E2E_MODEL:?set E2E_MODEL (model name)}"
  : "${E2E_API_KEY:?set E2E_API_KEY (API key for the provider)}"
  command -v podman > /dev/null || die "podman is required"
  mkdir -p "$STATE"
  checkout_source
  IMAGE="hermes-e2e:$(cat "$STATE/src-ref")"
  build_image
  write_config
  install_plugin
  ensure_network
  start_ntfy
  wait_for_ntfy
  start_container
  wait_for_dashboard
  add_model_key
  write_env_file
  echo "hermes e2e up; environment in $ENV_FILE"
}

down() {
  podman stop -t 20 "$CONTAINER" > /dev/null 2>&1 || true
  podman rm -f "$CONTAINER" > /dev/null 2>&1 || true
  podman stop -t 5 "$NTFY_CONTAINER" > /dev/null 2>&1 || true
  podman rm -f "$NTFY_CONTAINER" > /dev/null 2>&1 || true
  podman network rm "$NETWORK" > /dev/null 2>&1 || true
  echo "hermes e2e down"
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  env) echo "$ENV_FILE" ;;
  *) die "usage: $0 up|down|env" ;;
esac
