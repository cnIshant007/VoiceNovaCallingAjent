#!/bin/bash
# scripts/view-calls.sh — View recent calls with transcripts
BASE_URL="${VOICENOVA_URL:-http://localhost:8080}"
LIMIT="${1:-5}"

echo "━━━ Recent Calls (last $LIMIT) ━━━"
curl -s "$BASE_URL/api/v1/calls?limit=$LIMIT" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    calls = data if isinstance(data, list) else data.get('calls', [])
    if not calls:
        print('No calls yet. Run: ./tests/test_call_simulation.sh')
    for c in calls:
        dur = c.get('duration_sec', 0)
        print(f\"\\n{'─'*50}\")
        print(f\"ID: {c.get('id','?')} | {c.get('detected_language','?')} | {c.get('status','?')}\")
        print(f\"Duration: {dur}s | Score: {c.get('quality_score','N/A')} | Resolved: {c.get('resolved','?')}\")
        msgs = c.get('transcript', [])
        for m in msgs[-6:]:
            role = '🤖' if m.get('role') == 'assistant' else '👤'
            print(f\"  {role} {m.get('content','')[:80]}\")
except Exception as e:
    print('Could not parse response:', e)
" 2>/dev/null || echo "Server not reachable"
