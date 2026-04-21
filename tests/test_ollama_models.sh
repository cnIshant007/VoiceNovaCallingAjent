#!/bin/bash
# ============================================================
# tests/test_ollama_models.sh
# Switches between Ollama-backed local models and runs quick checks.
# Usage:
#   ./tests/test_ollama_models.sh qwen
#   ./tests/test_ollama_models.sh gemma
#   ./tests/test_ollama_models.sh llama
#   ./tests/test_ollama_models.sh all
# ============================================================

set -euo pipefail

BASE_URL="${VOICENOVA_URL:-http://127.0.0.1:8080}"
TARGET="${1:-qwen}"

switch_profile() {
  local profile_id="$1"
  local label="$2"
  local prompt="$3"

  echo ""
  echo "━━━ Testing $label ━━━"

  local switch_json
  switch_json=$(curl -sS -X POST "$BASE_URL/api/v1/system/llm" \
    -H "Content-Type: application/json" \
    -d "{\"llm_profile_id\":\"$profile_id\"}")

  local active_model
  active_model=$(printf '%s\n' "$switch_json" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('llm_model',''))" 2>/dev/null || true)
  if [ -z "$active_model" ]; then
    echo "❌ Could not switch to $label."
    echo "Response: $switch_json"
    return 1
  fi

  echo "Profile: $profile_id"
  echo "Model  : $active_model"

  local test_json
  test_json=$(curl -sS -X POST "$BASE_URL/api/v1/system/llm/test" \
    -H "Content-Type: application/json" \
    -d "{\"prompt\":\"$prompt\",\"max_tokens\":100}")

  local reply
  reply=$(printf '%s\n' "$test_json" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('reply',''))" 2>/dev/null || true)
  if [ -z "$reply" ]; then
    echo "❌ Model test failed for $label."
    echo "Response: $test_json"
    return 1
  fi

  echo "Reply  : $reply"
}

echo ""
echo "━━━ VoiceNova Ollama Model Test ━━━"
echo "Server: $BASE_URL"
echo "Target: $TARGET"

STATUS_JSON=$(curl -sS "$BASE_URL/api/v1/system/llm")
CURRENT_MODEL=$(printf '%s\n' "$STATUS_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('llm_model','Unknown'))" 2>/dev/null)
echo "Current model: $CURRENT_MODEL"

case "$TARGET" in
  qwen)
    switch_profile \
      "qwen25_15b_ollama" \
      "Qwen 2.5 1.5B" \
      "Reply in one short Hindi support sentence: our Pro plan costs 2999 rupees per month."
    ;;
  gemma)
    switch_profile \
      "gemma2_2b_ollama" \
      "Gemma 2B" \
      "Reply in one short English support sentence: our Pro plan costs 2999 rupees per month."
    ;;
  llama)
    switch_profile \
      "llama32_3b_ollama" \
      "Llama 3.2 3B" \
      "Reply in one short Hindi support sentence: our Pro plan costs 2999 rupees per month."
    ;;
  all)
    switch_profile \
      "qwen25_15b_ollama" \
      "Qwen 2.5 1.5B" \
      "Reply in one short Hindi support sentence: our Pro plan costs 2999 rupees per month."
    switch_profile \
      "gemma2_2b_ollama" \
      "Gemma 2B" \
      "Reply in one short English support sentence: our Pro plan costs 2999 rupees per month."
    switch_profile \
      "llama32_3b_ollama" \
      "Llama 3.2 3B" \
      "Reply in one short Hindi support sentence: our Pro plan costs 2999 rupees per month."
    ;;
  *)
    echo "Unknown target: $TARGET"
    echo "Use one of: qwen | gemma | llama | all"
    exit 1
    ;;
esac

echo ""
echo "✅ Ollama model test complete."
