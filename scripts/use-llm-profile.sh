#!/bin/bash
# ============================================================
# scripts/use-llm-profile.sh
# Switches VoiceNova to a supported offline LLM profile.
# ============================================================

set -euo pipefail

ENV_FILE="${VOICENOVA_ENV_FILE:-.env}"
PROFILE="${1:-}"

if [ -z "$PROFILE" ]; then
  echo "Usage: ./scripts/use-llm-profile.sh <profile>"
  echo ""
  echo "Available profiles:"
  echo "  qwen-ollama     -> qwen2.5:1.5b through Ollama"
  echo "  gemma-ollama    -> gemma:2b through Ollama"
  echo "  llama-ollama    -> llama3.2:3b through Ollama"
  echo "  qwen-openai     -> Qwen/Qwen2.5-1.5B-Instruct through a local OpenAI-compatible server"
  echo "  llama-openai    -> meta-llama/Llama-3.2-3B-Instruct through a local OpenAI-compatible server"
  exit 1
fi

if [ ! -f "$ENV_FILE" ]; then
  cp .env.example "$ENV_FILE"
  echo "Created $ENV_FILE from .env.example"
fi

case "$PROFILE" in
  qwen-ollama)
    MODEL="qwen2.5:1.5b"
    API_URL="http://127.0.0.1:11434/api/chat"
    API_FORMAT="ollama"
    PULL_HINT="ollama pull qwen2.5:1.5b"
    ;;
  gemma-ollama)
    MODEL="gemma:2b"
    API_URL="http://127.0.0.1:11434/api/chat"
    API_FORMAT="ollama"
    PULL_HINT="ollama pull gemma:2b"
    ;;
  llama-ollama)
    MODEL="llama3.2:3b"
    API_URL="http://127.0.0.1:11434/api/chat"
    API_FORMAT="ollama"
    PULL_HINT="ollama pull llama3.2:3b"
    ;;
  qwen-openai)
    MODEL="Qwen/Qwen2.5-1.5B-Instruct"
    API_URL="http://127.0.0.1:8000/v1/chat/completions"
    API_FORMAT="openai"
    PULL_HINT="Serve Qwen/Qwen2.5-1.5B-Instruct locally with vLLM, TGI, LM Studio, or llama.cpp server"
    ;;
  llama-openai)
    MODEL="meta-llama/Llama-3.2-3B-Instruct"
    API_URL="http://127.0.0.1:8000/v1/chat/completions"
    API_FORMAT="openai"
    PULL_HINT="Serve meta-llama/Llama-3.2-3B-Instruct locally with vLLM, TGI, LM Studio, or llama.cpp server"
    ;;
  *)
    echo "Unknown profile: $PROFILE"
    exit 1
    ;;
esac

update_env() {
  local key="$1"
  local value="$2"
  if grep -q "^${key}=" "$ENV_FILE"; then
    perl -0pi -e "s#^${key}=.*#${key}=${value}#m" "$ENV_FILE"
  else
    printf '\n%s=%s\n' "$key" "$value" >> "$ENV_FILE"
  fi
}

if command -v say >/dev/null 2>&1; then
  TTS_PROVIDER="macos_say"
else
  TTS_PROVIDER="piper"
fi

update_env "LLM_PROVIDER" "local"
update_env "LLM_MODEL" "$MODEL"
update_env "LLM_API_URL" "$API_URL"
update_env "LLM_API_FORMAT" "$API_FORMAT"
update_env "STT_PROVIDER" "vosk"
update_env "TTS_PROVIDER" "$TTS_PROVIDER"

echo "Updated $ENV_FILE"
echo "  LLM_PROVIDER=local"
echo "  LLM_MODEL=$MODEL"
echo "  LLM_API_URL=$API_URL"
echo "  LLM_API_FORMAT=$API_FORMAT"
echo "  STT_PROVIDER=vosk"
echo "  TTS_PROVIDER=$TTS_PROVIDER"
echo ""
echo "Next:"
echo "  $PULL_HINT"
echo "  Start or keep your local model server running, then run ./scripts/start.sh"
