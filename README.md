# 🎙 VoiceNova AI Calling Agent

A production-ready AI calling agent that speaks naturally in **40+ languages** (all 22 Indian + international), learns from context you provide, and improves itself from every call.

---

## 📦 What's Inside

```
voicenova-agent/
├── README.md                        ← You are here
├── docker-compose.yml               ← One-command start
├── .env.example                     ← Copy to .env and fill keys
│
├── src/main/kotlin/com/voicenova/
│   ├── Application.kt               ← Ktor server entry point
│   ├── routes/
│   │   ├── TwilioRoutes.kt          ← Handles incoming calls
│   │   └── ApiRoutes.kt             ← REST API + test endpoints
│   ├── services/
│   │   ├── CallService.kt           ← Core call brain
│   │   ├── LanguageService.kt       ← Auto language detection
│   │   └── TrainingService.kt       ← Reads your context files
│   ├── ai/
│   │   ├── LLMClient.kt             ← Local/cloud LLM integration
│   │   ├── STTClient.kt             ← Speech-to-text
│   │   └── TTSClient.kt             ← Text-to-speech voices
│   ├── models/
│   │   └── Models.kt                ← All data classes
│   └── config/
│       └── AppConfig.kt             ← Config loader
│
├── knowledge/                       ← ⭐ DROP YOUR CONTEXT HERE
│   ├── company/
│   │   └── about.md                 ← Who your company is
│   ├── products/
│   │   └── catalog.md               ← What you sell
│   ├── faq/
│   │   └── common-questions.md      ← Answers agent should know
│   └── scripts/
│       └── sales-script.md          ← Call scripts by purpose
│
├── tests/
│   ├── test_call_simulation.sh      ← Simulate a full call (no phone needed)
│   ├── test_language_detect.sh      ← Test Hindi/Tamil/etc detection
│   ├── test_context_load.sh         ← Verify agent loaded your context
│   └── test_conversation.http       ← VS Code REST Client file
│
└── scripts/
    ├── start.sh                     ← Start everything
    ├── reload-context.sh            ← Hot-reload knowledge base
    └── view-calls.sh                ← See recent call transcripts
```

---

## ⚡ Quick Start (5 Minutes)

### Step 1 — Prerequisites

```bash
# You need: Docker, Docker Compose, and a local model server
# such as Ollama, LM Studio, vLLM, llama.cpp server, or TGI
docker --version        # Docker 20+
docker compose version  # Compose V2

# Minimum required model to start:
# - Local model served through Ollama or an OpenAI-compatible endpoint
# Optional for real calls:
# - Twilio account + phone number
# - ElevenLabs or Google Cloud TTS
```

### Step 2 — Setup

```bash
git clone https://github.com/your-org/voicenova-agent
cd voicenova-agent

# Copy and fill environment file
cp .env.example .env
nano .env   # or use any editor
```

Fill in `.env`:
```env
# REQUIRED (minimum to test without real calls)
# Option A: Ollama + Qwen 2.5 1.5B (best budget default)
LLM_PROVIDER=local
LLM_MODEL=qwen2.5:1.5b
LLM_API_URL=http://127.0.0.1:11434/api/chat
LLM_API_FORMAT=ollama

# Option B: Ollama + Gemma 2B (smallest English-first option)
# LLM_PROVIDER=local
# LLM_MODEL=gemma:2b
# LLM_API_URL=http://127.0.0.1:11434/api/chat
# LLM_API_FORMAT=ollama

# Option C: Ollama + Llama 3.2 3B (better quality)
# LLM_PROVIDER=local
# LLM_MODEL=llama3.2:3b
# LLM_API_URL=http://127.0.0.1:11434/api/chat
# LLM_API_FORMAT=ollama

# Option D: Hugging Face Qwen served locally
# through an OpenAI-compatible endpoint
# LLM_PROVIDER=local
# LLM_MODEL=Qwen/Qwen2.5-1.5B-Instruct
# LLM_API_URL=http://127.0.0.1:8000/v1/chat/completions
# LLM_API_FORMAT=openai

# Option E: Hugging Face Llama served locally
# through an OpenAI-compatible endpoint
# LLM_PROVIDER=local
# LLM_MODEL=meta-llama/Llama-3.2-3B-Instruct
# LLM_API_URL=http://127.0.0.1:8000/v1/chat/completions
# LLM_API_FORMAT=openai

# Optional older profile:
# LLM_PROVIDER=local
# LLM_MODEL=sarvamai/sarvam-1
# LLM_API_URL=http://127.0.0.1:8000/v1/chat/completions
# LLM_API_FORMAT=openai

# OPTIONAL (for real phone calls)
TWILIO_ACCOUNT_SID=ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
TWILIO_AUTH_TOKEN=your_auth_token_here
TWILIO_PHONE_NUMBER=+1234567890

# OPTIONAL (for human-like voice)
ELEVENLABS_API_KEY=el_xxxxxxxx
ELEVENLABS_VOICE_ID=JBFqnCBsd6RMkjVDRZzb
ELEVENLABS_MODEL_ID=eleven_multilingual_v2
ELEVENLABS_OUTPUT_FORMAT=mp3_44100_128
# OR use Google Cloud TTS:
GOOGLE_CLOUD_KEY=your_google_api_key

# OPTIONAL (for speech recognition on real calls)
GOOGLE_SPEECH_KEY=your_google_api_key
```

Start Ollama and pull the local model before running the app:

```bash
ollama pull qwen2.5:1.5b
# or
ollama pull gemma:2b
# or
ollama pull llama3.2:3b
ollama serve
```

To switch quickly between offline presets, use:

```bash
./scripts/use-llm-profile.sh qwen-ollama
# or
./scripts/use-llm-profile.sh gemma-ollama
# or
./scripts/use-llm-profile.sh llama-ollama
```

On macOS, the script now switches TTS to `macos_say` automatically so you get
free local speech without installing Piper first.

If you want to use Hugging Face checkpoints locally instead, serve them through
any OpenAI-compatible local endpoint and switch `.env` to the matching profile
above. VoiceNova's `local` provider supports both Ollama-style and
OpenAI-compatible local model servers.

`Qwen 2.5 1.5B` is still the best default here for bilingual phone support.
`gemma:2b` is useful as a lighter English-first option, but I would not treat it
as the highest-accuracy translation model.

### Step 3 — Add Your Context (IMPORTANT)

Before starting, tell the agent who it is. Edit these files:

**`knowledge/company/about.md`**
```markdown
# Company: ABC Technologies Pvt Ltd

## What we do
We sell cloud software for small businesses in India.

## Our agent's name
Priya

## Agent personality
Friendly, professional, speaks in the caller's language.
Addresses customers as "aap" in Hindi, formally in English.

## Office hours
Monday to Saturday, 9 AM to 7 PM IST.
After hours: take a callback request.

## Contact
Email: support@abc.com
WhatsApp: +91 98765 43210
```

**`knowledge/products/catalog.md`**
```markdown
# Products

## Basic Plan — ₹999/month
- Up to 5 users
- Cloud storage 10GB
- Email support

## Pro Plan — ₹2,999/month  
- Unlimited users
- Cloud storage 100GB
- Priority phone support
- API access

## Enterprise — Custom pricing
- On-premise option
- Dedicated account manager
- SLA guarantee
```

**`knowledge/faq/common-questions.md`**
```markdown
# Frequently Asked Questions

Q: How do I reset my password?
A: Go to login page, click "Forgot Password", enter your email. 
   You will receive a reset link within 2 minutes.

Q: Can I get a refund?
A: Yes, within 30 days of purchase with no questions asked.

Q: Do you support Hindi?
A: Yes, our software interface is available in Hindi, Tamil, and 10 other Indian languages.

Q: What payment methods do you accept?
A: UPI, Net Banking, Credit/Debit cards, and EMI options on cards.
```

### Step 4 — Start

```bash
chmod +x scripts/start.sh
./scripts/start.sh

# OR manually:
docker compose up --build
```

Server starts at: `http://localhost:8080`

---

## 🧪 How to Test (Without a Real Phone)

### Test 1 — Simulate a Full Inbound Call

This mimics what happens when someone calls your Twilio number:

```bash
chmod +x tests/test_call_simulation.sh
./tests/test_call_simulation.sh
```

For the offline stack specifically:

```bash
chmod +x tests/test_offline_stack.sh
./tests/test_offline_stack.sh
```

### Test 2 — Test Ollama Models Separately

Use this when you want to compare local models without running the full call flow:

```bash
chmod +x tests/test_ollama_models.sh
./tests/test_ollama_models.sh qwen
./tests/test_ollama_models.sh gemma
./tests/test_ollama_models.sh llama
./tests/test_ollama_models.sh all
```

This script:
- switches the backend to the selected Ollama profile
- runs a quick model prompt
- prints the active model and reply so you can compare outputs

If you are testing the fresh local backend on another port, use:

```bash
VOICENOVA_URL=http://127.0.0.1:8081 ./tests/test_ollama_models.sh all
```

### Test 3 — Compare Repetition And Latency

Use this when you want to rank `Qwen` vs `Gemma` inside the real VoiceNova flow:

```bash
chmod +x tests/compare_ollama_models.sh
./tests/compare_ollama_models.sh qwen
./tests/compare_ollama_models.sh gemma
./tests/compare_ollama_models.sh all
```

This script:
- switches to each model profile
- sends repeated pricing prompts
- measures average response latency
- shows whether the repeated answer stayed identical
- prints refund, password, and raw probe replies for quick comparison

If you are testing the fresh local backend on another port, use:

```bash
VOICENOVA_URL=http://127.0.0.1:8081 ./tests/compare_ollama_models.sh all
```

Or manually with curl:

```bash
# Step 1: Simulate call arriving (caller says nothing yet)
curl -X POST http://localhost:8080/webhooks/twilio/inbound \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "CallSid=TEST_CALL_001&From=%2B919876543210&To=%2B911800000000&CallStatus=ringing"

# Response: TwiML XML with greeting in Hindi (auto-detected from +91 number)
```

```bash
# Step 2: Caller speaks — simulate transcription result
curl -X POST http://localhost:8080/webhooks/twilio/transcription \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "CallSid=TEST_CALL_001" \
  --data-urlencode "SpeechResult=Mujhe apna password reset karna hai" \
  -d "Confidence=0.95&LanguageCode=hi-IN"

# Response: TwiML XML with agent's Hindi reply + instructions for next Gather
```

```bash
# Step 3: Continue the conversation
curl -X POST http://localhost:8080/webhooks/twilio/transcription \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "CallSid=TEST_CALL_001" \
  --data-urlencode "SpeechResult=Main apna email bhi bhool gaya hoon" \
  -d "Confidence=0.91"

# Step 4: End call
curl -X POST http://localhost:8080/webhooks/twilio/status \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "CallSid=TEST_CALL_001&CallStatus=completed"
```

---

## Save Money

- Keep `LLM_PROVIDER=local` and start with `qwen2.5:1.5b` for the cheapest setup.
- On macOS, use `TTS_PROVIDER=macos_say` for free local speech; on Linux, use `TTS_PROVIDER=piper`.
- Start with `qwen2.5:1.5b` for low RAM and low latency; move to `llama3.2:3b` only if quality needs it.
- Keep `LLM_MAX_TOKENS` around `160-300` for phone calls and keep prompt answers short.
- Put business facts in `knowledge/` and let retrieval ground answers instead of paying to fine-tune a hosted model.

## Train On Your Details

VoiceNova is designed to learn your business details through grounding first, not full model fine-tuning:

- Add product, policy, FAQ, and script documents under `knowledge/`.
- Reload with `curl -X POST http://localhost:8080/api/v1/training/reload`.
- Let successful calls create reusable facts, then review them at `/api/v1/training/learned-facts`.

This is the cheapest path and usually the right one for support and calling agents. Fine-tuning should come later only if you need a custom speaking style or domain behavior that prompting plus knowledge retrieval cannot reach.

### Test 2 — Direct Conversation API (Easiest)

No Twilio needed. Talk to the agent directly via REST:

```bash
# Start a new conversation
curl -X POST http://localhost:8080/api/v1/test/conversation \
  -H "Content-Type: application/json" \
  -d '{
    "language": "hi-IN",
    "caller_number": "+919876543210",
    "purpose": "support"
  }'

# Response:
# {
#   "session_id": "sess_abc123",
#   "agent_greeting": "नमस्ते! मैं Priya हूं। आप कैसे हैं? मैं आपकी कैसे मदद कर सकती हूं?",
#   "detected_language": "hi-IN"
# }
```

```bash
# Send a message (replace SESSION_ID)
curl -X POST http://localhost:8080/api/v1/test/message \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "sess_abc123",
    "message": "Mujhe Pro plan ke baare mein jaankari chahiye"
  }'

# Response:
# {
#   "agent_reply": "Bilkul! Hamara Pro Plan ₹2,999 mahine mein aata hai...",
#   "detected_language": "hi-IN",
#   "intent": "PRODUCT_INQUIRY",
#   "confidence": 0.94
# }
```

```bash
# Try switching language mid-conversation
curl -X POST http://localhost:8080/api/v1/test/message \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "sess_abc123",
    "message": "Can you tell me in English about the refund policy?"
  }'

# Agent automatically switches to English for this response
```

---

### Test 3 — Language Detection Test

```bash
./tests/test_language_detect.sh

# OR manually test each language:
curl -X POST http://localhost:8080/api/v1/test/detect-language \
  -H "Content-Type: application/json" \
  -d '{"text": "నాకు సహాయం కావాలి"}'
# → {"language": "te-IN", "name": "Telugu", "confidence": 0.97}

curl -X POST http://localhost:8080/api/v1/test/detect-language \
  -H "Content-Type: application/json" \
  -d '{"text": "ਮੈਨੂੰ ਮਦਦ ਚਾਹੀਦੀ ਹੈ"}'
# → {"language": "pa-IN", "name": "Punjabi", "confidence": 0.96}

curl -X POST http://localhost:8080/api/v1/test/detect-language \
  -H "Content-Type: application/json" \
  -d '{"text": "أريد الحصول على المساعدة"}'
# → {"language": "ar-SA", "name": "Arabic", "confidence": 0.99}
```

---

### Test 4 — Verify Context Was Loaded

Check what the agent knows from your knowledge folder:

```bash
curl http://localhost:8080/api/v1/test/context-summary

# Response:
# {
#   "agent_name": "Priya",
#   "company": "ABC Technologies Pvt Ltd",
#   "documents_loaded": 4,
#   "total_chunks": 47,
#   "topics_covered": ["password reset", "refund policy", "pricing", "payment methods"],
#   "languages_configured": ["hi-IN", "ta-IN", "en-IN", "gu-IN"]
# }
```

---

### Test 5 — Make a Real Outbound Test Call

If you have Twilio configured:

```bash
# This will call YOUR phone and connect you to the AI agent
curl -X POST http://localhost:8080/api/v1/calls/outbound \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer test_token" \
  -d '{
    "to": "+91YOUR_PHONE_NUMBER",
    "agent_config": {
      "name": "Priya",
      "purpose": "demo",
      "language": "hi-IN"
    }
  }'
```

---

## 🧠 How to Train the Agent (Context System)

The agent uses **Retrieval-Augmented Generation (RAG)**. You don't need to fine-tune any model. Just drop files into `knowledge/` and reload.

### Method 1 — File Drop (Easiest)

Drop `.md`, `.txt`, or `.pdf` files into any subfolder under `knowledge/`:

```
knowledge/
├── company/
│   ├── about.md           ← Company info, agent name/personality
│   └── policies.md        ← Return policy, warranty, SLA
├── products/
│   ├── catalog.md         ← Products with prices
│   └── features.md        ← Detailed feature descriptions  
├── faq/
│   ├── billing.md         ← Billing questions
│   ├── technical.md       ← Tech support questions
│   └── onboarding.md      ← New customer questions
├── scripts/
│   ├── sales.md           ← Outbound sales call script
│   ├── support.md         ← Support call flow
│   └── complaint.md       ← How to handle complaints
└── custom/
    └── anything.md        ← Any additional context
```

**After adding files:**
```bash
./scripts/reload-context.sh

# Or via API:
curl -X POST http://localhost:8080/api/v1/training/reload
```

### Method 2 — API Upload

```bash
# Upload a document directly
curl -X POST http://localhost:8080/api/v1/training/upload \
  -H "Authorization: Bearer your_token" \
  -F "file=@your-product-manual.pdf" \
  -F "category=products" \
  -F "language=hi"

# Upload raw text
curl -X POST http://localhost:8080/api/v1/training/text \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer your_token" \
  -d '{
    "category": "faq",
    "title": "Shipping Policy",
    "content": "We ship within 2 business days. Free shipping above ₹500. Cash on delivery available.",
    "language": "en"
  }'
```

### Method 3 — Self-Learning from Calls

Every completed call is auto-saved. Enable self-learning:

```env
# In .env
ENABLE_SELF_LEARNING=true
MIN_CALL_SCORE_FOR_LEARNING=4.0   # Only learn from good calls (1-5 scale)
```

When a call ends with a positive outcome (resolved, purchase, etc.), the system:
1. Extracts successful Q&A pairs from the transcript
2. Adds them to the knowledge base
3. Tags them with language and topic

```bash
# View what the agent has learned
curl http://localhost:8080/api/v1/training/learned-facts?limit=20

# Manually approve/reject a learned fact
curl -X PATCH http://localhost:8080/api/v1/training/facts/fact_123 \
  -H "Content-Type: application/json" \
  -d '{"approved": true}'
```

### Method 4 — Define the Agent's Personality via YAML

Create `knowledge/agent-config.yaml`:

```yaml
agent:
  name: "Priya"
  gender: female
  
  personality:
    - professional
    - warm and friendly
    - patient with elderly customers
    - uses simple language, avoids jargon
    
  tone_by_caller_type:
    angry: "Very calm and empathetic. Never defensive."
    confused: "Patient, slow, use simple words."
    happy: "Match their energy, be conversational."
    
  languages:
    primary: hi-IN
    secondary: [en-IN, ta-IN, gu-IN, mr-IN]
    auto_detect: true
    
  call_rules:
    - "Always confirm the caller's name at the start"
    - "Never promise a refund without checking policy"
    - "If issue cannot be solved in 5 minutes, offer callback"
    - "Never put caller on hold for more than 60 seconds"
    - "Always end with: 'Kya main aur kuch madad kar sakti hoon?'"
    
  escalation:
    trigger_phrases: ["manager", "consumer court", "fraud", "FIR"]
    action: transfer_to_human
    transfer_message: "Main aapko apne senior se connect karti hoon."
```

---

## 📞 Connect to Real Phone Calls (Twilio Setup)

### Step 1 — Get Twilio Phone Number

1. Go to [twilio.com](https://twilio.com) → Create free account
2. Buy an Indian number (+91): Console → Phone Numbers → Buy
3. Note your: Account SID, Auth Token, Phone Number

### Step 2 — Expose Local Server (for Testing)

```bash
# Install ngrok (free)
brew install ngrok  # Mac
# OR: download from ngrok.com

# Start tunnel
ngrok http 8080

# You'll get a URL like: https://abc123.ngrok.io
# Use this as your base URL for Twilio webhooks
```

### Step 3 — Configure Twilio Webhooks

In Twilio Console → Phone Numbers → Your Number:

| Field | Value |
|-------|-------|
| A call comes in | `https://abc123.ngrok.io/webhooks/twilio/inbound` |
| Method | HTTP POST |
| Call status changes | `https://abc123.ngrok.io/webhooks/twilio/status` |

### Step 4 — Make a Test Call

Call your Twilio number from any phone. The AI agent will answer!

---

## 🔍 Monitoring & Debugging

### View Live Call Logs

```bash
# Real-time logs
docker compose logs -f api

# View recent calls with transcripts
./scripts/view-calls.sh

# Or via API
curl http://localhost:8080/api/v1/calls?limit=10 \
  -H "Authorization: Bearer your_token"
```

### View a Specific Call Transcript

```bash
curl http://localhost:8080/api/v1/calls/CALL_SID/transcript \
  -H "Authorization: Bearer your_token"

# Response:
# {
#   "call_id": "CALL_SID",
#   "duration_seconds": 187,
#   "language_detected": "hi-IN",
#   "intent": "BILLING_QUERY",
#   "resolved": true,
#   "transcript": [
#     {"speaker": "agent", "text": "नमस्ते! मैं Priya हूं...", "time": 0},
#     {"speaker": "caller", "text": "Mera bill galat aaya hai", "time": 4},
#     {"speaker": "agent", "text": "Bilkul, main aapka account check karti hoon", "time": 5}
#   ]
# }
```

### Dashboard (built-in web UI)

Open: `http://localhost:8080/dashboard`

Shows:
- Live active calls
- Call volume chart (today/week)
- Language breakdown pie chart
- Top issues/intents
- Agent performance score

---

## 🚀 Deploy to Production

### Option A — Single Server (DigitalOcean / AWS EC2)

```bash
# On your server (Ubuntu 22.04)
git clone your-repo && cd voicenova-agent
cp .env.example .env && nano .env   # fill real keys
docker compose -f docker-compose.prod.yml up -d

# Set up nginx reverse proxy for HTTPS
# Point your Twilio webhooks to https://your-domain.com/webhooks/...
```

### Option B — Railway / Render (Easiest)

```bash
# Deploy to Railway in 2 minutes
railway login
railway init
railway up

# Get your public URL from Railway dashboard
# Set it as your Twilio webhook URL
```

---

## 💰 Monetisation Quick Start

Once working, here's how to turn it into a business:

1. **White-label for clients** — Change `knowledge/company/about.md` for each client, charge ₹15,000–50,000/month setup + ₹0.80/minute usage
2. **Add to your product** — Embed the REST API in any existing app
3. **Resell to agencies** — Partner with digital marketing or CRM agencies
4. **Marketplace listing** — List on AWS/Azure marketplace for inbound enterprise deals

---

## ❓ Troubleshooting

| Problem | Solution |
|---------|----------|
| Agent doesn't know about my product | Check files are in `knowledge/` folder, run `./scripts/reload-context.sh` |
| Agent responds in wrong language | Check `knowledge/agent-config.yaml` has correct language list |
| Twilio webhook not receiving | Make sure ngrok is running and URL is updated in Twilio console |
| TTS sounds robotic | Switch from Google TTS to ElevenLabs in `.env`: `TTS_PROVIDER=elevenlabs` |
| Agent says "I don't know" too much | Add more content to `knowledge/faq/` folder |
| High latency (>3 second delay) | Enable `STREAMING_TTS=true` in `.env` |

---

## 📝 License

MIT — Use freely for commercial projects.
