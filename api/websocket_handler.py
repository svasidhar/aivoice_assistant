# api/websocket_handler.py
import json
import base64
import asyncio
import httpx
import google.generativeai as genai
from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from models.database import DatabaseManager
from config.settings import Config

ws_router = APIRouter(tags=["Exotel WebSocket Media Stream"])
db_manager = DatabaseManager()

# Configure Gemini API
genai.configure(api_key=Config.GEMINI_API_KEY)

# Sarvam AI Regional Indian Audio Processing Endpoints
SARVAM_STT_URL = "https://api.sarvam.ai/speech-to-text"
SARVAM_TTS_URL = "https://api.sarvam.ai/text-to-speech"

class AudioStreamSession:
    """Manages call state and binary audio buffers for individual active callers."""
    def __init__(self, stream_sid: str, caller_phone: str):
        self.stream_sid = stream_sid
        self.caller_phone = caller_phone
        self.audio_buffer = bytearray()
        self.is_speaking = False

@ws_router.websocket("/exotel-stream/voice")
async def handle_exotel_voice_stream(websocket: WebSocket):
    """Core full-duplex WebSocket endpoint managing raw binary audio exchange with Exotel."""
    await websocket.accept()
    print("🔌 Exotel Telephony WebSocket Connection Handshake Accepted.")
    
    session: AudioStreamSession = None

    try:
        while True:
            raw_message = await websocket.receive_text()
            packet = json.loads(raw_message)
            event_type = packet.get("event")

            if event_type == "connected":
                print("✅ Network connection established. Awaiting stream initialization...")
                
            elif event_type == "start":
                start_meta = packet["start"]
                stream_sid = packet["stream_sid"]
                caller_num = start_meta.get("from", "Unknown")
                
                session = AudioStreamSession(stream_sid, caller_num)
                print(f"📞 Live Call Stream Started! ID: {stream_sid} | From: {caller_num}")
                
                # Instantly play a welcoming greeting to the user
                await trigger_initial_greeting(websocket, session)

            elif event_type == "media":
                if not session:
                    continue
                raw_payload = packet["media"]["payload"]
                pcm_data = base64.b64decode(raw_payload)
                session.audio_buffer.extend(pcm_data)
                
                # If buffer accumulates ~1.5 seconds of raw 8kHz audio, check for speech processing
                if len(session.audio_buffer) >= 24000 and not session.is_speaking:
                    await process_user_speech_turn(websocket, session)

            elif event_type == "clear":
                print("🛑 User barge-in detected. Flushing active output buffers.")
                if session:
                    session.audio_buffer.clear()

            elif event_type == "stop":
                print(f"❌ Call session terminated by carrier network line drop: {packet.get('stream_sid')}")
                break

    except WebSocketDisconnect:
        print("🔌 WebSocket disconnected from remote client side.")
    except Exception as e:
        print(f"💥 Stream Engine Crash: {str(e)}")
    finally:
        pass

async def send_audio_chunk(websocket: WebSocket, pcm_bytes: bytes):
    """
    Encodes raw PCM audio bytes to base64 and wraps them in the 
    exact JSON text frame format required by Exotel's Media Stream.
    """
    try:
        # 1. Convert the raw binary audio bytes into a base64 string
        base64_audio = base64.b64encode(pcm_bytes).decode("utf-8")
        
        # 2. Build the structured media frame required by the telephony gateway
        exotel_media_frame = {
            "event": "media",
            "media": {
                "payload": base64_audio
            }
        }
        
        # 3. Send it as a text string frame, NOT as raw binary bytes
        await websocket.send_text(json.dumps(exotel_media_frame))
        
    except Exception as e:
        print(f"💥 Failed to package or send audio frame to Exotel: {str(e)}")

async def trigger_initial_greeting(websocket: WebSocket, session: AudioStreamSession):
    """Sends the initial prompt asking the villager for their location name."""
    greeting_text = "టి జి ఎస్ పి డి సి ఎల్ సహాయకుడికి స్వాగతం. మీరు ఏ గ్రామం లేదా ఏరియా నుండి మాట్లాడుతున్నారు?"
    await render_and_send_tts(websocket, session, greeting_text)

async def process_user_speech_turn(websocket: WebSocket, session: AudioStreamSession):
    """Core audio logic loop: STT -> Gemini Extraction -> Database Lookup -> TTS Output."""
    session.is_speaking = True
    headers = {"API-Subscription-Key": Config.SARVAM_API_KEY}

    try:
        # ─── STAGE 1: SPEECH-TO-TEXT (STT) ───
        files = {"file": ("caller_voice.wav", bytes(session.audio_buffer), "audio/wav")}
        data = {"language_code": "te-IN"}
        
        async with httpx.AsyncClient() as client:
            stt_response = await client.post(SARVAM_STT_URL, headers=headers, files=files, data=data, timeout=7.0)
            
        if stt_response.status_code != 200:
            raise Exception("Sarvam STT Hub dropped the audio frame processing packet.")
            
        user_transcript = stt_response.json().get("transcript", "").strip()
        print(f"🗣️ Villager Said: \"{user_transcript}\"")
        
        if not user_transcript:
            session.audio_buffer.clear()
            session.is_speaking = False
            return

        # ─── STAGE 2: GEMINI AREA EXTRACTION ───
        extracted_area = await extract_area_with_gemini(user_transcript)
        print(f"📍 Extracted Target Area Element: {extracted_area}")
        
        # ─── STAGE 3: DB LOOKUP & FALLBACK VALIDATION ───
        outage_msg, can_escalate = query_outage_database(extracted_area)

        if can_escalate:
            reply_text = "మీ కాల్ మా ఒక సబ్స్టేషన్ ఆపరేటర్కు పంపుతున్నాము, వెయిట్ చేయండి. మీ సమస్య వారికి చెప్పండి."
        else:
            reply_text = outage_msg

        # ─── STAGE 4: TEXT-TO-SPEECH TRANSMISSION ───
        await render_and_send_tts(websocket, session, reply_text)
        
        if can_escalate:
            print("🚨 Safety escalation parameters triggered. Severing streaming session.")
            await websocket.close(code=1000)
            return

    except Exception as e:
        print(f"⚠️ Live AI process loop caught execution barrier: {str(e)}")
        error_msg = "క్షమించండి, సమాచారం సేకరించడంలో లోపం దొరికింది. ఆపరేటర్ కి కనెక్ట్ చేస్తున్నాము."
        await render_and_send_tts(websocket, session, error_msg)
        await websocket.close(code=1000)
        
    finally:
        session.audio_buffer.clear()
        session.is_speaking = False

async def extract_area_with_gemini(transcript: str) -> str:
    """Uses Gemini to securely distill the raw colloquial transcript down to a clean area name string."""
    try:
        model = genai.GenerativeModel("gemini-1.5-flash")
        prompt = f"""
        Analyze this spoken text from an electricity consumer in Telangana: "{transcript}"
        Identify the village, town, sub-station area, or neighborhood name they are asking about.
        Return ONLY the capitalized English word of that location (e.g., 'Ramanapet', 'Cherlapally').
        If no distinct area name or village is mentioned in the text, reply strictly with the word 'Unknown'.
        Do not add punctuation, formatting, or extra sentences.
        """
        response = model.generate_content(prompt)
        return response.text.strip()
    except Exception as e:
        print(f"Gemini Processing Error: {e}")
        return "Unknown"

def query_outage_database(area_name: str) -> tuple[str, bool]:
    """Queries the thread-safe local SQLite file for live matching rows."""
    if not area_name or area_name == "Unknown":
        return "", True
        
    conn = db_manager.get_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT issue, eta FROM outages WHERE LOWER(area) = LOWER(?)", (area_name.strip(),))
    row = cursor.fetchone()
    conn.close()
    
    if row:
        return f"{area_name} లో ప్రస్తుతం {row['issue']} సమస్య ఉంది. మన సిబ్బంది పని చేస్తున్నారు. సుమారుగా {row['eta']} పడుతుంది.", False
    return "", True

async def render_and_send_tts(websocket: WebSocket, session: AudioStreamSession, text_to_speak: str):
    """
    Invokes Sarvam AI Bulbul model to turn Telugu text into matching 
    8kHz linear PCM audio frames.
    """
    headers = {
        "api-subscription-key": Config.SARVAM_API_KEY,
        "Content-Type": "application/json"
    }
    
    # Corrected payload structure matching Sarvam's exact REST API schema
    payload = {
        "text": text_to_speak,
        "target_language_code": "te-IN",
        "speaker": "shubh",
        "model": "bulbul:v3",
        "speech_sample_rate": 8000,    # FIXED: Changed from sample_rate to speech_sample_rate
        "output_audio_codec": "wav"    # Explicitly requests clean WAV container
    }
    
    try:
        async with httpx.AsyncClient() as client:
            response = await client.post(SARVAM_TTS_URL, headers=headers, json=payload, timeout=6.0)
            
        if response.status_code == 200:
            raw_base64 = response.json().get("audios", [""])[0]
            audio_bytes = base64.b64decode(raw_base64)
            
            # Strip the 44-byte RIFF header to deliver pure PCM stream data to Exotel
            if audio_bytes.startswith(b'RIFF'):
                pcm_bytes = audio_bytes[44:]
            else:
                pcm_bytes = audio_bytes
                
            await send_audio_chunk(websocket, pcm_bytes)
        else:
            print(f"❌ Sarvam TTS Engine failed with status code: {response.status_code}")
            print(f"📄 Diagnostic Details: {response.text}")
            
    except Exception as e:
        print(f"💥 Failed to execute text-to-speech request: {str(e)}")
