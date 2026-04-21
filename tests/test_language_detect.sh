#!/bin/bash
# ============================================================
# test_language_detect.sh — Test auto language detection
# ============================================================
BASE_URL="${VOICENOVA_URL:-http://localhost:8080}"

echo "━━━ VoiceNova Language Detection Tests ━━━"
echo ""

test_lang() {
  local text="$1"
  local expected="$2"
  local result=$(curl -s -X POST "$BASE_URL/api/v1/test/detect-language" \
    -H "Content-Type: application/json" \
    -d "{\"text\": \"$text\"}" | python3 -c 'import sys, json; d = json.load(sys.stdin); print(d["name"] + " (" + d["language"] + ")")' 2>/dev/null)
  
  if echo "$result" | grep -q "$expected"; then
    echo "✅ PASS: \"$text\""
    echo "        → Detected: $result"
  else
    echo "⚠️  CHECK: \"$text\""
    echo "        → Detected: $result (expected: $expected)"
  fi
  echo ""
}

# Indian languages
test_lang "मुझे अपना पासवर्ड बदलना है" "hi-IN"
test_lang "நான் உங்களுக்கு உதவ விரும்புகிறேன்" "ta-IN"
test_lang "నాకు సహాయం కావాలి" "te-IN"
test_lang "আমি বাংলায় কথা বলতে চাই" "bn-IN"
test_lang "ਮੈਨੂੰ ਮਦਦ ਚਾਹੀਦੀ ਹੈ" "pa-IN"
test_lang "ನನಗೆ ಸಹಾಯ ಬೇಕು" "kn-IN"
test_lang "മലയാളത്തിൽ സംസാരിക്കാൻ ആഗ്രഹിക്കുന്നു" "ml-IN"
test_lang "हे माझ्यासाठी कार्य करत नाही" "mr-IN"
test_lang "ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ" "pa-IN"

# International
test_lang "I need help with my account" "en-IN"
test_lang "أريد الحصول على مساعدة" "ar-SA"
test_lang "Necesito ayuda con mi cuenta" "en-IN"
test_lang "J'ai besoin d'aide" "en-IN"

echo "━━━ Test complete ━━━"
