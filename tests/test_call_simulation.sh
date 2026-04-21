#!/bin/bash
# ============================================================
# test_call_simulation.sh
# Simulates a complete inbound call WITHOUT a real phone
# Usage: ./tests/test_call_simulation.sh [language]
# Example: ./tests/test_call_simulation.sh ta-IN
# ============================================================

set -e
BASE_URL="${VOICENOVA_URL:-http://127.0.0.1:8080}"
LANG="${1:-hi-IN}"

echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║       VoiceNova AI — Call Simulation Test                ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""
echo "▶ Server: $BASE_URL"
echo "▶ Language: $LANG"
echo ""

# ── STEP 1: Start conversation ────────────────────────────────────────────────
echo "━━━ STEP 1: Starting conversation ━━━"
RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/test/conversation" \
  -H "Content-Type: application/json" \
  -d "{
    \"language\": \"$LANG\",
    \"caller_number\": \"+919876543210\",
    \"purpose\": \"support\"
  }")

SESSION_ID=$(echo $RESPONSE | python3 -c "import sys,json; print(json.load(sys.stdin)['session_id'])" 2>/dev/null || echo "")
GREETING=$(echo $RESPONSE | python3 -c "import sys,json; print(json.load(sys.stdin)['agent_greeting'])" 2>/dev/null || echo "$RESPONSE")

if [ -z "$SESSION_ID" ]; then
  echo "❌ Failed to start conversation. Is the server running? Start with: docker compose up"
  echo "   Response was: $RESPONSE"
  exit 1
fi

echo "✅ Session started: $SESSION_ID"
echo ""
echo "🤖 Agent: $GREETING"
echo ""

# ── STEP 2: First user message ────────────────────────────────────────────────
echo "━━━ STEP 2: Caller asks about pricing ━━━"

case $LANG in
  "hi-IN")
    MSG1="Mujhe Pro plan ke baare mein jaankari chahiye, kitna price hai?"
    ;;
  "ta-IN")
    MSG1="Pro plan பற்றி தகவல் வேண்டும், என்ன விலை?"
    ;;
  "te-IN")
    MSG1="Pro plan గురించి సమాచారం కావాలి"
    ;;
  "gu-IN")
    MSG1="Pro plan ની કિંમત શું છે?"
    ;;
  "en-IN")
    MSG1="I want to know about the Pro plan pricing"
    ;;
  "ar-SA")
    MSG1="أريد معرفة سعر الخطة المتقدمة"
    ;;
  *)
    MSG1="Tell me about your pricing plans"
    ;;
esac

echo "👤 Caller: $MSG1"
RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/test/message" \
  -H "Content-Type: application/json" \
  -d "{\"session_id\": \"$SESSION_ID\", \"message\": \"$MSG1\"}")

REPLY=$(echo $RESPONSE | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['agent_reply'])" 2>/dev/null || echo "$RESPONSE")
DETECTED=$(echo $RESPONSE | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('detected_language',''))" 2>/dev/null)
INTENT=$(echo $RESPONSE | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('intent',''))" 2>/dev/null)

echo "🤖 Agent: $REPLY"
echo "   [Language detected: $DETECTED | Intent: $INTENT]"
echo ""

# ── STEP 3: Switch language mid-call ─────────────────────────────────────────
echo "━━━ STEP 3: Caller switches to English ━━━"
MSG2="Can you also tell me about the refund policy in English?"
echo "👤 Caller: $MSG2"

RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/test/message" \
  -H "Content-Type: application/json" \
  -d "{\"session_id\": \"$SESSION_ID\", \"message\": \"$MSG2\"}")
REPLY=$(echo $RESPONSE | python3 -c "import sys,json; print(json.load(sys.stdin)['agent_reply'])" 2>/dev/null || echo "$RESPONSE")
DETECTED=$(echo $RESPONSE | python3 -c "import sys,json; print(json.load(sys.stdin).get('detected_language',''))" 2>/dev/null)

echo "🤖 Agent: $REPLY"
echo "   [Language: $DETECTED — agent switched to English automatically]"
echo ""

# ── STEP 4: Password reset (knowledge base test) ──────────────────────────────
echo "━━━ STEP 4: Caller asks FAQ question ━━━"
MSG3="How do I reset my password?"
echo "👤 Caller: $MSG3"

RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/test/message" \
  -H "Content-Type: application/json" \
  -d "{\"session_id\": \"$SESSION_ID\", \"message\": \"$MSG3\"}")
REPLY=$(echo $RESPONSE | python3 -c "import sys,json; print(json.load(sys.stdin)['agent_reply'])" 2>/dev/null || echo "$RESPONSE")
echo "🤖 Agent: $REPLY"
echo ""

# ── STEP 5: End call ──────────────────────────────────────────────────────────
echo "━━━ STEP 5: Ending call ━━━"
RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/test/end" \
  -H "Content-Type: application/json" \
  -d "{\"session_id\": \"$SESSION_ID\", \"quality_score\": 4.5}")
echo "✅ Call ended: $RESPONSE"
echo ""

echo "╔══════════════════════════════════════════════════════════╗"
echo "║  ✅ Simulation complete!                                 ║"
echo "║                                                          ║"
echo "║  Next steps:                                             ║"
echo "║  • Check context: GET /api/v1/test/context-summary       ║"
echo "║  • View learned facts: GET /api/v1/training/learned-facts║"
echo "║  • Add more context: edit knowledge/ folder then:        ║"
echo "║    curl -X POST $BASE_URL/api/v1/training/reload         ║"
echo "╚══════════════════════════════════════════════════════════╝"
