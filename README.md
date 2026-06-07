# ⚡ TGSPDCL AI Voice Call Assistant

[![FastAPI](https://img.shields.io/badge/FastAPI-009688?style=for-the-badge&logo=FastAPI&logoColor=white)](https://fastapi.tiangolo.com)
[![Python](https://img.shields.io/badge/Python-3776AB?style=for-the-badge&logo=python&logoColor=white)](https://www.python.org)
[![Kotlin](https://img.shields.io/badge/Kotlin-0095D5?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Google Gemini](https://img.shields.io/badge/Google_Gemini-8E75C2?style=for-the-badge&logo=google-gemini&logoColor=white)](https://deepmind.google/technologies/gemini/)
[![Sarvam AI](https://img.shields.io/badge/Sarvam_AI-orange?style=for-the-badge)](https://www.sarvam.ai/)
[![Exotel](https://img.shields.io/badge/Exotel_Telephony-blue?style=for-the-badge)](https://exotel.com/)

👉 **GitHub Repository**: [https://github.com/svasidhar/lineman-ai-assistant](https://github.com/svasidhar/lineman-ai-assistant)

An AI-powered cloud telephony voice agent and background call-screening companion designed to assist TGSPDCL (Telangana State Southern Power Distribution Company Limited) linemen. The system automatically intercepts unknown consumer calls, interacts with callers in polite, regional Telugu, validates power outage queries against a live SQLite grid, and routes complex requests or emergencies to substation operators asynchronously.

---

## 🏗️ System Architecture & Workflow

The diagram below outlines the full-duplex speech and database lookup loops established between Exotel Telephony, Sarvam AI, Google Gemini, and the FastAPI application layer:

```mermaid
sequenceDiagram
    autonumber
    actor Consumer as 📞 Caller (Consumer)
    participant Exotel as 📡 Exotel Cloud Gateway
    participant Server as ⚡ FastAPI Backend (Render)
    participant Sarvam as 🗣️ Sarvam AI (STT/TTS)
    participant Gemini as 🧠 Google Gemini 2.5 Flash
    participant DB as 💾 SQLite Outages DB
    actor Operator as 🤵 Substation Operator

    Consumer->>Exotel: Dial Lineman / Virtual Number
    Exotel->>Server: WebSocket Handshake (/exotel-stream/voice)
    Server-->>Exotel: Accept Socket (Full-Duplex PCM Stream)
    Server->>Sarvam: Generate Telugu Welcome Speech (TTS)
    Sarvam-->>Server: Return 8kHz PCM Audio bytes
    Server->>Exotel: Send base64-encoded Audio JSON frame
    Exotel->>Consumer: Play greeting: "టి జి ఎస్ పి డి సి ఎల్ సహాయకుడికి స్వాగతం..."

    Consumer->>Exotel: Speaks query (e.g., "రామన్నపేట కరెంట్ ఎప్పుడు వస్తుంది?")
    Exotel->>Server: Stream raw binary PCM audio bytes over WebSocket
    Server->>Server: Accumulate audio frames into voice buffer (~1.5s)
    Server->>Sarvam: POST audio buffer formatted with WAV header (STT)
    Sarvam-->>Server: Return transcript: "రామన్నపేట కరెంట్ ఎప్పుడు వస్తుంది?"
    Server->>Gemini: Prompt: Extract area name from transcript
    Gemini-->>Server: Return: "Ramanapet"
    Server->>DB: Query outages table WHERE area = "Ramanapet"
    
    alt Outage Record Found
        DB-->>Server: Return Outage details (Issue: Line Breakdown, ETA: 30m)
        Server->>Sarvam: Synthesize response: "రామన్నపేటలో ప్రస్తుతం లైన్ బ్రేక్ డౌన్ సమస్య ఉంది..." (TTS)
        Sarvam-->>Server: Return audio bytes
        Server->>Exotel: Stream audio frames back to handset
    else Area Unknown / Critical Emergency
        DB-->>Server: Record Missing or Escalation Flagged
        Server->>Server: Trigger trigger_safety_escalation() (Async)
        Server->>Sarvam: Synthesize fallback text: "క్షమించండి, మీ ఏరియా పేరు అర్థం కాలేదు..." (TTS)
        Sarvam-->>Server: Return audio bytes
        Server->>Exotel: Play fallback audio message
        Server->>Exotel: POST to connect.json (Route Caller to Substation)
        Server-->>Exotel: Close WebSocket connection (code=1000)
        Exotel->>Operator: Route caller directly to operator line
    end
```

---

## 🔥 Key Technical Highlights

### ⚡ Asynchronous Voice Streaming Backend
* **Full-Duplex WebSocket Pipeline**: Mounts `/exotel-stream/voice` directly on FastAPI, handling real-time linear PCM audio frames.
* **Modern `google-genai` Async SDK**: Utilizes non-blocking async clients (`client.aio.models.generate_content`) to call **Gemini 2.5 Flash** for rapid location extraction in under 1 second.
* **Non-Blocking Safety Escalations**: Fallbacks and substation routing triggers use `httpx.AsyncClient` to prevent blocking the event loop and avoid carrier silence timeouts.
* **Robust Telemetry & Guards**: Telemetry tracking loggers track packet stream ingestion, and Starlette `RuntimeError` hooks gracefully handle abrupt carrier disconnects.
* **Regional Speech Integration**: Connects to Sarvam AI's `bulbul:v3` model (with TTS WAV container parsing and linear PCM header-stripping) and standard STT configurations.

### 📱 Android Lineman Companion Client (Kotlin & Compose)
* **Silent Interception Service**: Background BroadcastReceiver and foreground `CallScreeningService` capture incoming unknown calls, mute ringers automatically, and answer hands-free.
* **Audio Routing Manager**: Utilizes TelecomManager APIs to programmatically accept calls and routes audio dynamically into speakerphone/earpiece paths (`AudioDeviceInfo.TYPE_BUILTIN_SPEAKER`).
* **Bilingual UI Dashboard**: Sleek glassmorphic interface built with Jetpack Compose featuring real-time call logging logs, audio playback controllers, and manual override quick replies.

---

## 📂 Project Directory Structure

```plaintext
lineman-ai-assistant/
├── api/
│   ├── routes.py             # Core REST API endpoints (GET /incoming webhooks, auth, user queries)
│   ├── staff_routes.py       # Lineman voice update endpoints (Milestone 1)
│   └── websocket_handler.py  # Asynchronous Live WebSocket voicebot stream engine
├── config/
│   └── settings.py           # Environment variables configuration and startup key validation
├── models/
│   ├── database.py           # Thread-safe SQLite DatabaseManager (outages, memory, logs, settings)
│   └── nlp_model.py          # Rule-based fallback NLP classifiers & entity extractor triggers
├── static/
│   ├── app.js                # Frontend portal companion (simulates calls, lists logs, edits live grid)
│   ├── index.html            # Dashboard HTML portal
│   └── style.css             # Glassmorphic dashboard UI styling sheet
├── mobile/                   # Android Client (Kotlin, Gradle, Jetpack Compose)
├── main.py                   # Central FastAPI app instantiation and server startup node
├── requirements.txt          # Python ecosystem package dependencies
└── README.md                 # Project documentation
```

---

## 🛠️ Installation & Setup

### 1. Prerequisites
Ensure you have Python 3.10+ installed.

### 2. Install Dependencies
Clone the repository and install packages from the root directory:
```bash
pip install -r requirements.txt
```

### 3. Configure Credentials
Create a `.env` file in the root folder and define the required API keys (all telephony and AI keys are securely loaded on startup):
```env
GEMINI_API_KEY="your-google-gemini-api-key"
SARVAM_API_KEY="your-sarvam-ai-api-key"
OPENAI_API_KEY="your-openai-api-key"

DATABASE_URL="sqlite:///./outage_data.db"
LOG_LEVEL="INFO"

# Exotel Configuration
EXOTEL_ACCOUNT_SID="your-account-sid"
EXOTEL_API_KEY="your-api-key"
EXOTEL_API_TOKEN="your-api-token"
EXOTEL_EXOPHONE="your-exophone-number"
```

### 4. Run the Server
Launch the local Uvicorn development server:
```bash
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```
Verify the startup diagnostics output in your terminal:
```plaintext
📁 Static directory mounted successfully under /static URL layout.
✅ Environmental Configuration Validated: AI Media Stream Drivers Initialized.
```

---

## 🧪 Testing the Voice Simulator

If you don't have an active Exotel carrier webhook configured, you can test the system end-to-end using the built-in **Equal AI Simulator Dashboard**:

1. Start your server and navigate to `http://localhost:8000/` in your browser.
2. The dashboard simulates both the Lineman's mobile app and the consumer's phone call.
3. Under the **Simulate Consumer Call** section, select a preset query (e.g. *"Cherlapally current eppudu vastundi?"*) or type a custom question.
4. Click **Call AI Assistant** to trigger the WebSocket voice interaction loop.
5. Inspect the live transcriptions, database lookups, and AI status updates in real time on the lineman's chat log view.

---

## 📄 License
This project is proprietary and built for utility automation testing. All rights reserved.