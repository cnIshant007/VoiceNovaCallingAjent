#!/bin/bash
# ============================================================
# tests/compare_ollama_models.sh
# Compares Ollama-backed models on latency and repeated-prompt behavior.
# Usage:
#   ./tests/compare_ollama_models.sh qwen
#   ./tests/compare_ollama_models.sh gemma
#   ./tests/compare_ollama_models.sh all
# ============================================================

set -euo pipefail

BASE_URL="${VOICENOVA_URL:-http://127.0.0.1:8080}"
TARGET="${1:-all}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

http_post_json() {
  local url="$1"
  local payload="$2"
  local body_file="$3"
  curl -sS -o "$body_file" -w "%{time_total}" \
    -X POST "$url" \
    -H "Content-Type: application/json" \
    -d "$payload"
}

normalize_text() {
  python3 -c 'import re,sys; text=sys.stdin.read().strip().lower(); text=re.sub(r"[^a-z0-9\u0900-\u097f\s]", " ", text); text=re.sub(r"\s+", " ", text).strip(); print(text)'
}

extract_json_field() {
  local field="$1"
  python3 -c "import json,sys; data=json.load(sys.stdin); print(data.get('$field',''))"
}

run_model_suite() {
  local profile_id="$1"
  local label="$2"
  local pricing_prompt="$3"
  local conversation_language="$4"
  local raw_probe="$5"

  local switch_file="$TMP_DIR/${profile_id}_switch.json"
  local conv_file="$TMP_DIR/${profile_id}_conversation.json"
  local msg1_file="$TMP_DIR/${profile_id}_msg1.json"
  local msg2_file="$TMP_DIR/${profile_id}_msg2.json"
  local msg3_file="$TMP_DIR/${profile_id}_msg3.json"
  local msg4_file="$TMP_DIR/${profile_id}_msg4.json"
  local probe_file="$TMP_DIR/${profile_id}_probe.json"

  echo ""
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "Model: $label"
  echo "Profile: $profile_id"

  http_post_json \
    "$BASE_URL/api/v1/system/llm" \
    "{\"llm_profile_id\":\"$profile_id\"}" \
    "$switch_file" >/dev/null

  local active_model
  active_model=$(cat "$switch_file" | extract_json_field "llm_model")
  if [ -z "$active_model" ]; then
    echo "❌ Could not switch to $label"
    cat "$switch_file"
    return 1
  fi
  echo "Active model: $active_model"

  http_post_json \
    "$BASE_URL/api/v1/test/conversation" \
    "{\"language\":\"$conversation_language\",\"caller_number\":\"+919876543210\",\"purpose\":\"support\"}" \
    "$conv_file" >/dev/null

  local session_id
  session_id=$(cat "$conv_file" | extract_json_field "session_id")
  if [ -z "$session_id" ]; then
    echo "❌ Could not create test conversation"
    cat "$conv_file"
    return 1
  fi

  local time1
  time1=$(http_post_json \
    "$BASE_URL/api/v1/test/message" \
    "{\"session_id\":\"$session_id\",\"message\":\"$pricing_prompt\"}" \
    "$msg1_file")
  local reply1
  reply1=$(cat "$msg1_file" | extract_json_field "agent_reply")

  local time2
  time2=$(http_post_json \
    "$BASE_URL/api/v1/test/message" \
    "{\"session_id\":\"$session_id\",\"message\":\"$pricing_prompt\"}" \
    "$msg2_file")
  local reply2
  reply2=$(cat "$msg2_file" | extract_json_field "agent_reply")

  local time3
  time3=$(http_post_json \
    "$BASE_URL/api/v1/test/message" \
    "{\"session_id\":\"$session_id\",\"message\":\"Can you also tell me about the refund policy in English?\"}" \
    "$msg3_file")
  local reply3
  reply3=$(cat "$msg3_file" | extract_json_field "agent_reply")

  local time4
  time4=$(http_post_json \
    "$BASE_URL/api/v1/test/message" \
    "{\"session_id\":\"$session_id\",\"message\":\"How do I reset my password?\"}" \
    "$msg4_file")
  local reply4
  reply4=$(cat "$msg4_file" | extract_json_field "agent_reply")

  local probe_time
  probe_time=$(http_post_json \
    "$BASE_URL/api/v1/system/llm/test" \
    "{\"prompt\":\"$raw_probe\",\"max_tokens\":100}" \
    "$probe_file")
  local probe_reply
  probe_reply=$(cat "$probe_file" | extract_json_field "reply")

  curl -sS -X POST "$BASE_URL/api/v1/test/end" \
    -H "Content-Type: application/json" \
    -d "{\"session_id\":\"$session_id\",\"quality_score\":4.0}" >/dev/null

  local norm1 norm2 repeated avg_latency
  norm1=$(printf '%s' "$reply1" | normalize_text)
  norm2=$(printf '%s' "$reply2" | normalize_text)
  if [ "$norm1" = "$norm2" ] && [ -n "$norm1" ]; then
    repeated="yes"
  else
    repeated="no"
  fi

  avg_latency=$(python3 -c "times=[float('$time1'), float('$time2'), float('$time3'), float('$time4')]; print(f'{sum(times)/len(times):.2f}s')")

  echo "Avg latency     : $avg_latency"
  echo "Repeated reply  : $repeated"
  echo "Pricing #1      : $reply1"
  echo "Pricing #2      : $reply2"
  echo "Refund          : $reply3"
  echo "Password        : $reply4"
  echo "Raw probe       : $probe_reply"
  echo "Raw probe time  : ${probe_time}s"
}

echo ""
echo "━━━ VoiceNova Ollama Comparison Test ━━━"
echo "Server: $BASE_URL"
echo "Target: $TARGET"

case "$TARGET" in
  qwen)
    run_model_suite \
      "qwen25_15b_ollama" \
      "Qwen 2.5 1.5B" \
      "Mujhe Pro plan ke baare mein jaankari chahiye, kitna price hai?" \
      "hi-IN" \
      "Reply in one short Hindi support sentence: our Pro plan costs 2999 rupees per month and includes unlimited features."
    ;;
  gemma)
    run_model_suite \
      "gemma2_2b_ollama" \
      "Gemma 2B" \
      "Tell me about your Pro plan pricing." \
      "en-IN" \
      "Reply in one short English support sentence: our Pro plan costs 2999 rupees per month and includes unlimited features."
    ;;
  all)
    run_model_suite \
      "qwen25_15b_ollama" \
      "Qwen 2.5 1.5B" \
      "Mujhe Pro plan ke baare mein jaankari chahiye, kitna price hai?" \
      "hi-IN" \
      "Reply in one short Hindi support sentence: our Pro plan costs 2999 rupees per month and includes unlimited features."
    run_model_suite \
      "gemma2_2b_ollama" \
      "Gemma 2B" \
      "Tell me about your Pro plan pricing." \
      "en-IN" \
      "Reply in one short English support sentence: our Pro plan costs 2999 rupees per month and includes unlimited features."
    ;;
  *)
    echo "Unknown target: $TARGET"
    echo "Use one of: qwen | gemma | all"
    exit 1
    ;;
esac

echo ""
echo "✅ Ollama comparison test complete."
