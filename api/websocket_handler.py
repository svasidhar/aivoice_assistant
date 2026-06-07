# api/websocket_handler.py
import json
import base64
import asyncio
import httpx
import logging
from google import genai
from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from models.database import DatabaseManager
from config.settings import Config

# ─── NEW GENAI SDK IMPLEMENTATION ───
# Initialize the unified GenAI production client 
# It will automatically inherit GEMINI_API_KEY from your .env file
client = genai.Client()
logger = logging.getLogger("uvicorn.error")

ws_router = APIRouter(tags=["Exotel WebSocket Media Stream"])
db_manager = DatabaseManager()

# Initializing genai_client is handled in the model calling functions

# Sarvam AI Regional Indian Audio Processing Endpoints
SARVAM_STT_URL = "https://api.sarvam.ai/speech-to-text"
SARVAM_TTS_URL = "https://api.sarvam.ai/text-to-speech"

class AudioStreamSession:
    """Manages call state and binary audio buffers for individual active callers."""
    def __init__(self, stream_sid: str, caller_phone: str, call_sid: str = ""):
        self.stream_sid = stream_sid
        self.caller_phone = caller_phone
        self.call_sid = call_sid
        self.audio_buffer = bytearray()
        self.is_speaking = False

def add_wav_header(pcm_data: bytes, sample_rate: int = 8000, num_channels: int = 1, bits_per_sample: int = 16) -> bytes:
    """Prepends a standard 44-byte RIFF WAV header to raw PCM data so STT engines parse it successfully."""
    num_samples = len(pcm_data)
    byte_rate = sample_rate * num_channels * bits_per_sample // 8
    block_align = num_channels * bits_per_sample // 8
    
    header = bytearray(44)
    # RIFF Identifier
    header[0:4] = b'RIFF'
    # Overall Size (36 + SubChunk2Size)
    header[4:8] = (36 + num_samples).to_bytes(4, byteorder='little')
    # WAVE Format
    header[8:12] = b'WAVE'
    # Format Subchunk Identifier
    header[12:16] = b'fmt '
    # Subchunk Size (16 for PCM)
    header[16:20] = (16).to_bytes(4, byteorder='little')
    # Audio Format (1 for PCM)
    header[20:22] = (1).to_bytes(2, byteorder='little')
    # Number of Channels (Mono)
    header[22:24] = num_channels.to_bytes(2, byteorder='little')
    # Sample Rate (8000 Hz)
    header[24:28] = sample_rate.to_bytes(4, byteorder='little')
    # Byte Rate
    header[28:32] = byte_rate.to_bytes(4, byteorder='little')
    # Block Align
    header[32:34] = block_align.to_bytes(2, byteorder='little')
    # Bits Per Sample (16 bit)
    header[34:36] = bits_per_sample.to_bytes(2, byteorder='little')
    # Data Subchunk Identifier
    header[36:40] = b'data'
    # Data Size
    header[40:44] = num_samples.to_bytes(4, byteorder='little')
    
    return bytes(header) + pcm_data

@ws_router.websocket("/exotel-stream/voice")
async def handle_exotel_voice_stream(websocket: WebSocket):
    """Core full-duplex WebSocket endpoint managing raw binary audio exchange with Exotel."""
    await websocket.accept()
    logger.info("🔌 Exotel Telephony WebSocket Connection Handshake Accepted.")
    
    session: AudioStreamSession = None
    media_packet_count = 0

    try:
        while True:
            try:
                raw_message = await websocket.receive_text()
            except RuntimeError as starlette_err:
                if "not connected" in str(starlette_err).lower():
                    logger.info("🔌 Call disconnected by client or telecom carrier while processing. Exiting loop cleanly.")
                    break
                raise starlette_err
            packet = json.loads(raw_message)
            event_type = packet.get("event")

            if event_type == "connected":
                logger.info("✅ Network connection established. Awaiting stream initialization...")
                
            elif event_type == "start":
                start_meta = packet["start"]
                stream_sid = packet["stream_sid"]
                caller_num = start_meta.get("from", "Unknown")
                call_sid = start_meta.get("callSid", "")
                
                session = AudioStreamSession(stream_sid, caller_num, call_sid)
                logger.info(f"📞 Live Call Stream Started! ID: {stream_sid} | From: {caller_num}")
                
                # Instantly play a welcoming greeting to the user
                await trigger_initial_greeting(websocket, session)

            elif event_type == "media":
                if not session:
                    continue
                
                media_packet_count += 1
                # Every 20 audio frames (~2 seconds of speech), log telemetry to prove the server is listening
                if media_packet_count % 20 == 0:
                    logger.info(f"🎙️ [Stream Active] Successfully processed {media_packet_count} inbound voice packets from caller.")
                
                raw_payload = packet["media"]["payload"]
                pcm_data = base64.b64decode(raw_payload)
                session.audio_buffer.extend(pcm_data)
                
                # If buffer accumulates ~1.5 seconds of raw 8kHz audio, check for speech processing
                if len(session.audio_buffer) >= 24000 and not session.is_speaking:
                    try:
                        await process_user_speech_turn(websocket, session)
                    except Exception as stt_packet_err:
                        # Guard filter: Log error cleanly to keep the call line active during network recovery
                        logger.error(f"⚠️ Buffered STT packet skip or processing error: {str(stt_packet_err)}")

            elif event_type == "clear":
                logger.info("🛑 User barge-in detected. Flushing active output buffers.")
                if session:
                    session.audio_buffer.clear()

            elif event_type == "stop":
                logger.info(f"❌ Call session terminated by carrier network line drop: {packet.get('stream_sid')}")
                break

    except WebSocketDisconnect:
        logger.info("🔌 WebSocket disconnected from remote client side.")
    except Exception as e:
        logger.error(f"💥 Stream Engine Crash: {str(e)}")
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
        logger.error(f"💥 Failed to package or send audio frame to Exotel: {str(e)}")

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
        # Package raw pcm bytes inside a standard WAV header file layout
        wav_data = add_wav_header(bytes(session.audio_buffer))
        files = {"file": ("caller_voice.wav", wav_data, "audio/wav")}
        data = {"language_code": "te-IN"}
        
        logger.info("📡 Sending accumulated WAV voice buffer to Sarvam STT REST API...")
        async with httpx.AsyncClient() as client:
            stt_response = await client.post(SARVAM_STT_URL, headers=headers, files=files, data=data, timeout=7.0)
            
        if stt_response.status_code != 200:
            logger.error(f"❌ Sarvam STT failed with status code: {stt_response.status_code}")
            logger.error(f"📄 STT Diagnostic Details: {stt_response.text}")
            raise Exception("Sarvam STT Hub dropped the audio frame processing packet.")
            
        user_transcript = stt_response.json().get("transcript", "").strip()
        logger.info(f"🗣️ Villager Said: \"{user_transcript}\"")
        
        if not user_transcript:
            session.audio_buffer.clear()
            session.is_speaking = False
            return

        # ─── STAGE 2: GEMINI AREA EXTRACTION ───
        extracted_area = await extract_area_from_text(user_transcript)
        logger.info(f"📍 Extracted Target Area Element: {extracted_area}")
        
        # ─── STAGE 3: DB LOOKUP & FALLBACK VALIDATION ───
        outage_msg, can_escalate = query_outage_database(extracted_area)

        if can_escalate:
            await trigger_safety_escalation(websocket, session)
            logger.info("🚨 Safety escalation parameters triggered. Severing streaming session.")
            await websocket.close(code=1000)
            return
        else:
            # ─── STAGE 4: TEXT-TO-SPEECH TRANSMISSION ───
            await render_and_send_tts(websocket, session, outage_msg)

    except Exception as e:
        logger.error(f"⚠️ Live AI process loop caught execution barrier: {str(e)}")
        error_msg = "క్షమించండి, సమాచారం సేకరించడంలో లోపం దొరికింది. ఆపరేటర్ కి కనెక్ట్ చేస్తున్నాము."
        await render_and_send_tts(websocket, session, error_msg)
        await websocket.close(code=1000)
        
    finally:
        session.audio_buffer.clear()
        session.is_speaking = False

async def trigger_safety_escalation(websocket: WebSocket, session: AudioStreamSession):
    """
    Handles unknown areas by playing a clear audio fallback message 
    and handing off routing without blocking the main thread.
    """
    logger.info("🚨 Safety escalation parameters triggered asynchronously.")
    
    try:
        # 1. Say something back to the user instead of leaving them in silence
        fallback_text = "క్షమించండి, మీ ఏరియా పేరు స్పష్టంగా అర్థం కాలేదు. దయచేసి లైన్ లోనే ఉండండి, మా ప్రతినిధికి కనెక్ట్ చేస్తున్నాము."
        await render_and_send_tts(websocket, session, fallback_text)
    except Exception as tts_err:
        logger.error(f"⚠️ Fallback TTS play failed: {str(tts_err)}")
        
    try:
        # 2. Trigger Exotel Connect API call routing asynchronously (if configured)
        api_key = Config.EXOTEL_API_KEY
        api_token = Config.EXOTEL_API_TOKEN
        account_sid = Config.EXOTEL_ACCOUNT_SID
        exophone = Config.EXOTEL_EXOPHONE
        
        caller_number = session.caller_phone
        
        if not api_key or not api_token or not account_sid or not exophone:
            logger.warning("Exotel credentials not fully configured in environment. Escalation simulated.")
            return
            
        settings = db_manager.get_assistant_state()
        if settings.get("operator_available", True):
            destination_number = settings.get("lineman_phone", "+91 9876543210")
        else:
            destination_number = settings.get("backup_operator_phone", "+91 9876543211")
            
        url = f"https://api.in.exotel.com/v1/Accounts/{account_sid}/Calls/connect.json"
        data = {
            "From": caller_number,
            "To": destination_number,
            "CallerId": exophone,
            "Record": "true"
        }
        
        logger.info(f"📡 Triggering Exotel Connect API: routing {caller_number} to {destination_number}...")
        async with httpx.AsyncClient() as client:
            response = await client.post(url, auth=(api_key, api_token), data=data, timeout=6.0)
            
        if response.status_code == 200:
            logger.info(f"✅ Exotel call routing initiated successfully: {response.json()}")
        else:
            logger.error(f"❌ Exotel API failed with status {response.status_code}: {response.text}")
            
    except Exception as e:
        logger.error(f"⚠️ Escalation routing failed: {str(e)}")

async def extract_area_from_text(user_speech_text: str) -> str:
    """
    Uses the modern GenAI SDK to async-parse the transcribed Telugu text
    and extract a clean target region string.
    """
    prompt = f"""
    You are an AI assistant for TGSPDCL. Extract the village or area name mentioned in this text.
    Text: "{user_speech_text}"
    Respond with ONLY the clean area name in English, or 'Unknown' if not found.
    """
    
    try:
        # Use client.aio for non-blocking execution inside your live WebSocket stream loop
        response = await client.aio.models.generate_content(
            model='gemini-2.5-flash',
            contents=prompt,
        )
        
        extracted_text = response.text.strip() if response.text else "Unknown"
        logger.info(f"📍 Modern GenAI Engine Extracted Area: {extracted_text}")
        return extracted_text
        
    except Exception as e:
        logger.error(f"💥 Modern Gemini SDK Error: {str(e)}")
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
        "speech_sample_rate": 8000,
        "output_audio_codec": "wav"
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
            logger.error(f"❌ Sarvam TTS Engine failed with status code: {response.status_code}")
            logger.error(f"📄 Diagnostic Details: {response.text}")
            
    except Exception as e:
        logger.error(f"💥 Failed to execute text-to-speech request: {str(e)}")
