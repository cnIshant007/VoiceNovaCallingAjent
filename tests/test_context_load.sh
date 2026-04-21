#!/bin/bash
# ============================================================
# tests/test_context_load.sh
# Verifies the agent has loaded your knowledge base correctly
# ============================================================
BASE_URL="${VOICENOVA_URL:-http://127.0.0.1:8080}"

echo ""
echo "━━━ VoiceNova Context Load Test ━━━"
echo ""

TMP_RESPONSE=$(mktemp)
HTTP_CODE=$(curl -sS -o "$TMP_RESPONSE" -w "%{http_code}" "$BASE_URL/api/v1/test/context-summary" 2>/dev/null || true)
RESULT=$(cat "$TMP_RESPONSE" 2>/dev/null)
rm -f "$TMP_RESPONSE"

if [ "$HTTP_CODE" != "200" ] || [ -z "$RESULT" ]; then
  echo "❌ Cannot reach server at $BASE_URL"
  echo "   Make sure it's running: ./scripts/start.sh"
  exit 1
fi

AGENT=$(printf '%s\n' "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('agentName','Unknown'))" 2>/dev/null)
COMPANY=$(printf '%s\n' "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('company','Unknown'))" 2>/dev/null)
DOCS=$(printf '%s\n' "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('documentsLoaded',0))" 2>/dev/null)
CHUNKS=$(printf '%s\n' "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('totalChunks',0))" 2>/dev/null)
LANGS=$(printf '%s\n' "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(', '.join(d.get('languagesConfigured',[])))" 2>/dev/null)
TOPICS=$(printf '%s\n' "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); topics=d.get('topicsCovered',[]); print(', '.join(topics[:8]))" 2>/dev/null)

echo "Agent Name  : $AGENT"
echo "Company     : $COMPANY"
echo "Documents   : $DOCS"
echo "Chunks      : $CHUNKS"
echo "Languages   : $LANGS"
echo "Topics      : $TOPICS"
echo ""

if [ "$CHUNKS" = "0" ] || [ -z "$CHUNKS" ]; then
  echo "⚠️  WARNING: No context loaded!"
  echo ""
  echo "   To fix this:"
  echo "   1. Edit files in the knowledge/ folder"
  echo "   2. Run: curl -X POST $BASE_URL/api/v1/training/reload"
  echo "   3. Re-run this test"
else
  echo "✅ Context loaded successfully!"
  echo ""
  echo "Next: test the agent"
  echo "  ./tests/test_call_simulation.sh"
fi
