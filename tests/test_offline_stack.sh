#!/bin/bash
# ============================================================
# tests/test_offline_stack.sh
# Verifies the offline VoiceNova stack: LLM, STT/TTS config, and chat flow.
# ============================================================

set -e
BASE_URL="${VOICENOVA_URL:-http://127.0.0.1:8080}"
PROMPT="${1:-What is your Pro plan price?}"

echo ""
echo "━━━ VoiceNova Offline Stack Test ━━━"
echo "Server: $BASE_URL"
echo ""

LLM_JSON=$(curl -sS "$BASE_URL/api/v1/system/llm")
TTS_JSON=$(curl -sS "$BASE_URL/api/v1/system/tts-provider")

LLM_MODEL=$(printf '%s\n' "$LLM_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('llm_model','Unknown'))" 2>/dev/null)
LLM_READY=$(printf '%s\n' "$LLM_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('ready',False))" 2>/dev/null)
SELECTED_PROFILE=$(printf '%s\n' "$LLM_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('selected_local_profile_id','custom'))" 2>/dev/null)
TTS_PROVIDER=$(printf '%s\n' "$TTS_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('tts_provider','Unknown'))" 2>/dev/null)
TTS_READY=$(printf '%s\n' "$TTS_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('ready',False))" 2>/dev/null)

echo "LLM Profile : $SELECTED_PROFILE"
echo "LLM Model   : $LLM_MODEL"
echo "LLM Ready   : $LLM_READY"
echo "TTS Provider: $TTS_PROVIDER"
echo "TTS Ready   : $TTS_READY"
echo ""

if [ "$LLM_READY" != "True" ] && [ "$LLM_READY" != "true" ]; then
  echo "❌ LLM is not ready. Check your local model server."
  exit 1
fi

echo "Running quick chat test..."
TEST_JSON=$(curl -sS -X POST "$BASE_URL/api/v1/system/llm/test" \
  -H "Content-Type: application/json" \
  -d "{\"prompt\":\"$PROMPT\",\"max_tokens\":120}")
TEST_REPLY=$(printf '%s\n' "$TEST_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('reply',''))" 2>/dev/null)

if [ -z "$TEST_REPLY" ]; then
  echo "❌ LLM test reply was empty."
  exit 1
fi

echo "LLM Reply   : $TEST_REPLY"
echo ""
echo "Running full call simulation..."
bash ./tests/test_call_simulation.sh hi-IN
