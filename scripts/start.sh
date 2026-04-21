#!/bin/bash
# ============================================================
# scripts/start.sh — Self-healing startup for VoiceNova stack
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

log() { echo "[INFO] $*"; }
warn() { echo "[WARN] $*"; }
err() { echo "[ERROR] $*" >&2; }

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    err "Missing required command: $1"
    exit 1
  fi
}

wait_for_url() {
  local url="$1"
  local attempts="$2"
  local delay="$3"
  local name="$4"
  local i
  for i in $(seq 1 "${attempts}"); do
    if curl -sf "${url}" >/dev/null 2>&1; then
      log "${name} is ready"
      return 0
    fi
    sleep "${delay}"
  done
  return 1
}

extract_ngrok_public_url() {
  curl -sf "http://127.0.0.1:4040/api/tunnels" 2>/dev/null | tr -d '\n' | sed -n 's/.*"public_url":"\([^"]*\)".*/\1/p'
}

start_ngrok_if_enabled() {
  local server_port="$1"
  local enable="${START_NGROK:-true}"
  if [ "${enable}" != "true" ]; then
    return 0
  fi
  if ! command -v ngrok >/dev/null 2>&1; then
    warn "ngrok not found. Skipping public tunnel setup."
    return 0
  fi

  local ngrok_url
  ngrok_url="$(extract_ngrok_public_url || true)"
  if [ -z "${ngrok_url}" ]; then
    log "Starting ngrok tunnel for port ${server_port}..."
    nohup ngrok http "${server_port}" >/tmp/voicenova-ngrok.log 2>&1 &
    sleep 3
    ngrok_url="$(extract_ngrok_public_url || true)"
  fi

  if [ -n "${ngrok_url}" ]; then
    echo ""
    echo "Public Webhooks (Twilio):"
    echo "  Voice URL:  ${ngrok_url}/webhooks/twilio/inbound"
    echo "  Status URL: ${ngrok_url}/webhooks/twilio/status"
  else
    warn "ngrok started but tunnel URL was not detected. Check /tmp/voicenova-ngrok.log"
  fi
}

start_ollama_if_needed() {
  if [ "${LLM_PROVIDER:-local}" != "local" ] || [ "${LLM_API_FORMAT:-ollama}" != "ollama" ]; then
    return 0
  fi

  require_cmd ollama
  local model="${LLM_MODEL:-qwen2.5:1.5b}"
  log "Using local Ollama model: ${model}"

  if ! curl -sf "http://127.0.0.1:11434/api/tags" >/dev/null 2>&1; then
    if pgrep -f "ollama serve" >/dev/null 2>&1; then
      log "Ollama process exists, waiting for API..."
    else
      log "Starting Ollama server in background..."
      nohup ollama serve >/tmp/voicenova-ollama.log 2>&1 &
      sleep 2
    fi
  fi

  if ! wait_for_url "http://127.0.0.1:11434/api/tags" 30 2 "Ollama"; then
    err "Ollama API did not start. Check /tmp/voicenova-ollama.log"
    return 1
  fi

  if ! ollama list | awk '{print $1}' | grep -Fxq "${model}"; then
    log "Model not found locally. Pulling ${model}..."
    ollama pull "${model}"
  fi
}

print_failure_diagnostics() {
  warn "Startup failed. Collecting diagnostics..."
  docker compose ps || true
  echo ""
  warn "Recent api logs:"
  docker compose logs --tail=120 api || true
  echo ""
  warn "Recent db logs:"
  docker compose logs --tail=80 db || true
  echo ""
  warn "Recent cache logs:"
  docker compose logs --tail=80 cache || true
}

echo ""
echo "============================================================"
echo " VoiceNova AI Calling Agent: automated startup"
echo "============================================================"

require_cmd docker
require_cmd curl

if [ ! -f .env ]; then
  warn ".env file not found. Creating from .env.example"
  cp .env.example .env
fi

set -a
source .env
set +a

case "${LLM_PROVIDER:-local}" in
  local)
    start_ollama_if_needed
    ;;
  anthropic)
    if [ -z "${ANTHROPIC_API_KEY:-}" ] || [ "${ANTHROPIC_API_KEY:-}" = "sk-ant-api03-REPLACE_WITH_YOUR_KEY" ]; then
      err "Please set ANTHROPIC_API_KEY in .env"
      exit 1
    fi
    ;;
  openai|google)
    if [ -z "${LLM_API_KEY:-${OPENAI_API_KEY:-}}" ]; then
      err "Please set LLM_API_KEY or OPENAI_API_KEY in .env"
      exit 1
    fi
    ;;
  *)
    err "Unsupported LLM_PROVIDER=${LLM_PROVIDER:-}"
    exit 1
    ;;
esac

mkdir -p knowledge/company knowledge/products knowledge/faq knowledge/scripts

log "Starting Docker services..."
if ! docker compose up --build -d --remove-orphans; then
  print_failure_diagnostics
  exit 1
fi

SERVER_PORT="${SERVER_PORT:-8080}"
HEALTH_URL="http://127.0.0.1:${SERVER_PORT}/health"
log "Waiting for API health check at ${HEALTH_URL}"
if ! wait_for_url "${HEALTH_URL}" 60 2 "VoiceNova API"; then
  print_failure_diagnostics
  exit 1
fi

echo ""
echo "============================================================"
echo " VoiceNova is running"
echo " API:       http://localhost:${SERVER_PORT}"
echo " Dashboard: http://localhost:${SERVER_PORT}/dashboard"
echo " Logs:      docker compose logs -f api"
echo " Test:      ./tests/test_call_simulation.sh"
echo "============================================================"

start_ngrok_if_enabled "${SERVER_PORT}"
