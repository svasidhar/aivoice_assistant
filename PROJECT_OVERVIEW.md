# TGSPDCL AI Voice Call Assistant - System Documentation

This document compiles the complete overview of the **TGSPDCL AI Voice Call Assistant**, detailing what has been implemented, the technology stack, the architectural flows, and key issue resolutions.

---

## 1. Project Objectives
The main goal of this application is to automate the screening and routing of incoming calls from unknown numbers on a lineman's phone. 
- **Hands-Free Operation:** The lineman's phone intercepts unknown calls silently in the background, answering programmatically without taking over the lineman's screen.
- **AI-Powered Screening:** An AI assistant screens callers in friendly Telugu, checking for power outage queries in their area.
- **Smart Call Routing:** If the query is complex or no active outage is logged, the call is forwarded to the substation operator via **Exotel Cloud Routing**, and the lineman's phone hangs up programmatically.
- **Call Logging Dashboard:** Logs transcripts, audio, and call status in a beautiful dashboard on the lineman's app.

---

## 2. Tech Stack & Technologies Used

### Mobile Client (Android)
- **Programming Language:** Kotlin
- **UI Framework:** Jetpack Compose (Modern, declarative UI with glassmorphic cards, custom animations, and a tabbed navigation system).
- **Asynchronous Flow:** Kotlin Coroutines (`CoroutineScope`, `Dispatchers.Main`, `Dispatchers.IO`) for non-blocking API calls and service execution.
- **Network Interface:** Retrofit 2 & OkHttp (for RESTful API requests, multipart uploads, and form-urlencoded requests).
- **Speech Technologies:**
  - **SpeechRecognizer:** Android's native speech-to-text API (configured for Telugu `te-IN`).
  - **TextToSpeech:** Android's native text-to-speech engine configured for Telugu speech synthesis.
- **Telephony & Audio Routing APIs:**
  - **BroadcastReceiver:** Monitors phone states (`RINGING`, `OFFHOOK`, `IDLE`).
  - **TelecomManager:** Uses the `ANSWER_PHONE_CALLS` permission to auto-answer calls and `endCall()` to programmatically hang up calls.
  - **AudioManager:** Manages speakerphone routing via `setCommunicationDevice` (API 31+) and legacy `isSpeakerphoneOn`.
- **Media Player:** `MediaPlayer` for streaming call logs' audio recordings directly from the backend server.

### Backend Server (API)
- **Framework:** FastAPI (Python) for asynchronous, high-performance web service endpoints.
- **Database:** SQLite (SQLAlchemy ORM) for storing outage status, lineman profiles, active settings, and call logs.
- **AI Core:**
  - **Google Gemini API:** Dynamically evaluates consumer questions, checks database outage records, and generates friendly Telugu replies in JSON.
  - **OpenAI Whisper API:** (Alternative/Voice Update processing) for transcribing staff voice notes.
- **Third-Party Telephony Integration:**
  - **Exotel Connect API:** Cloud telephony bridging to call the consumer back and connect them directly to the substation.
  - **Twilio API:** (Alternative/Available configuration) for messaging and telephony.

---

## 3. Features Implemented

### I. Silent Background Screening Service
- Runs inside a **Foreground Service** (`CallScreeningService`) declaring telephony and microphone foreground types.
- Displays a persistent notification: *"AI Screening Active: Intercepting and screening call..."*.
- Mutes the phone ringer instantly for unknown incoming calls, programmatically accepts the call, and switches the audio route to speakerphone.

### II. Polite & Friendly Telugu State Machine
- Interacts with consumers using a friendly Telugu dialect utilizing respect markers (**andi** instead of **sir**).
- Fallback flow handles greetings, queries about current outages, area-based lookups (e.g. Ramanapet, Cherlapally), and substation routing.

### III. Exotel Cloud Call Forwarding
- Once call transfer is triggered, the background service sends a request to the backend `/api/v1/route-call/` endpoint.
- The service immediately ends the active call on the lineman's phone (`TelecomManager.endCall()`).
- The backend triggers Exotel's Connect API, which dials the consumer's phone and bridges them directly to the substation operator's line.

### IV. Call Logs Dashboard & Audio Player
- Integrated a new **Call Logs** tab on the lineman's main screen.
- Fetches logs from the database, rendering glassmorphic call cards with expandable detail views.
- Visualizes transcripts using left-aligned/right-aligned chat bubbles for Consumer/AI.
- Built-in audio controller that streams, buffers, and plays back call recordings from the backend.

---

## 4. Key Issues Solved

### A. Microphone Lock & SpeechRecognizer Failure
- **Problem:** Running `MediaRecorder` (saving call audio) and `SpeechRecognizer` (processing AI dialogue) at the same time caused a resource conflict, locking the microphone and making `SpeechRecognizer` fail.
- **Solution:** Disabled active `MediaRecorder` audio capture during call screening. `SpeechRecognizer` now has exclusive microphone access and hears the caller perfectly.

### B. Infinite Recognition Error Loops
- **Problem:** SpeechRecognizer failures in the background triggered immediate restarts, causing an infinite loop that consumed 100% CPU.
- **Solution:** Added retry delays inside `onError` (500ms for timeouts/no matches, 2000ms for other transient errors) to stabilize resource recovery.

### C. Audio Path Routing
- **Problem:** Default TTS streams are muted or routed to the earpiece during telephony calls.
- **Solution:** Configured TTS `AudioAttributes` with `USAGE_VOICE_COMMUNICATION` and `CONTENT_TYPE_SPEECH` to route synthesized audio into the active call.

### D. Exotel Call Bridging
- **Problem:** Local call forwarding (`Intent.ACTION_CALL`) interrupted the lineman by opening the Dialer UI.
- **Solution:** Replaced with Exotel cloud routing. The app issues an API request and hangs up locally, while Exotel bridges the consumer to the substation.
