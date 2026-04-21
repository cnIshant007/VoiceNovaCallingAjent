# VoiceNova AI Agent Setup Guide

Follow these steps to connect your local AI agent to a real phone number and start answering calls live.

## Prerequisites

1.  **Twilio Account**: Go to [twilio.com](https://twilio.com) and sign up.
2.  **Twilio Phone Number**: Purchase a voice-capable phone number in the Twilio Console.
3.  **ngrok**: Install ngrok to expose your local server to the internet. [Download here](https://ngrok.com/download).

---

## Step 1: Start your Local Server

Run the following command in your project root:

```bash
./gradlew run
```

The server should be running on `http://localhost:8080`.

If your `.env` uses a local open-source LLM, make sure that model server is
already running before you test calls:

- `LLM_API_FORMAT=ollama`: start Ollama and load your model first.
- `LLM_API_FORMAT=openai`: point `LLM_API_URL` at your local OpenAI-compatible
  endpoint, such as a server hosting `Qwen/Qwen2.5-1.5B-Instruct`,
  `meta-llama/Llama-3.2-3B-Instruct`, or `sarvamai/sarvam-1`.

Recommended offline presets:

```bash
./scripts/use-llm-profile.sh qwen-ollama
# or
./scripts/use-llm-profile.sh gemma-ollama
# or
./scripts/use-llm-profile.sh llama-ollama
```

On macOS, this script also sets `TTS_PROVIDER=macos_say`, which gives you a
free built-in local voice without installing Piper.

---

## Step 2: Start ngrok

Open a new terminal window and run:

```bash
ngrok http 8080
```

Copy the **Forwarding URL** (e.g., `https://a1b2-c3d4.ngrok-free.app`).

---

## Step 3: Configure Environment Variables

Update your `.env` file with the following values:

```env
# Change this to your new ngrok Forwarding URL
PUBLIC_BASE_URL=https://your-ngrok-id.ngrok-free.app

# Twilio Credentials
TWILIO_ACCOUNT_SID=your_account_sid
TWILIO_AUTH_TOKEN=your_auth_token
TWILIO_PHONE_NUMBER=+1234567890

# Escalation Number (Where to transfer if a human is requested)
TWILIO_ESCALATION_NUMBER=+1987654321

# Enable real telephony
MOCK_TELEPHONY=false
```

> [!IMPORTANT]
> Save the `.env` file and restart your server if it's already running.

---

## Step 4: Configure Twilio Webhook

1.  Log in to the [Twilio Console](https://console.twilio.com).
2.  Navigate to **Phone Numbers** > **Manage** > **Active Numbers**.
3.  Click on your Twilio number.
4.  Scroll down to the **Voice & Fax** section.
5.  Under **A CALL COMES IN**, select **Webhook**.
6.  Paste your ngrok URL followed by `/webhooks/twilio/inbound`:
    `https://your-ngrok-id.ngrok-free.app/webhooks/twilio/inbound`
7.  Set the method to **HTTP POST**.
8.  Click **Save configuration**.

## Step 5: If You Want Calls To Your Existing Number

If you want people to call your current personal or business number and have the AI answer first, forward that number to your Twilio voice number.

1.  Keep the Twilio number configured with the inbound webhook above.
2.  Enable call forwarding on your existing phone number through your carrier or PBX provider.
3.  Forward those incoming calls to your Twilio number.
4.  When someone calls your normal number, the call will land on Twilio, and the AI agent will answer through `/webhooks/twilio/inbound`.

---

## Step 6: Test the Setup

1.  Call your Twilio phone number from your personal phone.
2.  The AI agent should answer and greet you.
3.  Tell it "I want to talk to a manager" to test the escalation (transfer) feature.
4.  Check the server logs or the `/api/v1/status` endpoint to see the session details.

For a fully local smoke test before calling from a real phone:

```bash
./tests/test_offline_stack.sh
```

For Ollama model-only testing:

```bash
./tests/test_ollama_models.sh qwen
./tests/test_ollama_models.sh gemma
./tests/test_ollama_models.sh llama
./tests/test_ollama_models.sh all
```

For repetition and latency comparison between `Qwen` and `Gemma`:

```bash
./tests/compare_ollama_models.sh qwen
./tests/compare_ollama_models.sh gemma
./tests/compare_ollama_models.sh all
```

---

## Troubleshooting

-   **I don't hear anything**: On macOS, try `TTS_PROVIDER=macos_say`. Otherwise check that `TTS_PROVIDER=piper` points at an installed Piper binary and a downloaded voice model, or use `TTS_PROVIDER=twilio_basic` as the fallback.
-   **Webhook Error**: Ensure ngrok is running and your `PUBLIC_BASE_URL` matches.
-   **Agent keeps repeating**: This is usually due to latency. Try speaking more clearly or check the `LLM_MAX_TOKENS` setting.
