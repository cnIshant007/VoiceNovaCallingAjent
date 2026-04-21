#!/bin/bash
# scripts/reload-context.sh — Hot-reload knowledge base without restarting
BASE_URL="${VOICENOVA_URL:-http://localhost:8080}"

echo "↻ Reloading knowledge base..."
RESULT=$(curl -s -X POST "$BASE_URL/api/v1/training/reload")

if [ $? -ne 0 ]; then
  echo "❌ Could not reach server at $BASE_URL"
  exit 1
fi

CHUNKS=$(echo $RESULT | python3 -c "import sys,json; print(json.load(sys.stdin).get('chunks_loaded',0))" 2>/dev/null)
DOCS=$(echo $RESULT | python3 -c "import sys,json; print(json.load(sys.stdin).get('documents',0))" 2>/dev/null)

echo "✅ Reloaded: $CHUNKS chunks from $DOCS documents"
echo ""
echo "Agent will use the new context on the next call."
