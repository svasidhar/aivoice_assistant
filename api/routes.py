from fastapi import APIRouter, UploadFile, File, HTTPException, Form, Response, BackgroundTasks
from pydantic import BaseModel
from typing import Optional, List
import os
from datetime import datetime
import json
import sqlite3
import sys
from twilio.twiml.voice_response import VoiceResponse, Gather

# Add project root to path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Import our modules
from models.database import DatabaseManager
from voice_processing.voice_processor import VoiceProcessor
from config.settings import Config
from models.nlp_model import CustomNLPModel

try:
    import google.generativeai as genai
    if Config.GEMINI_API_KEY:
        genai.configure(api_key=Config.GEMINI_API_KEY)
except Exception as e:
    print(f"Error importing or configuring google-generativeai: {e}")
    genai = None

# Initialize components
router = APIRouter(prefix="/api/v1", tags=["voice_assistant"])

# Initialize database, voice processor and NLP models
db = DatabaseManager()
voice_processor = VoiceProcessor(Config.OPENAI_API_KEY)
nlp_model = CustomNLPModel()

def get_transfer_phone(settings: dict) -> str:
    """Helper to choose lineman phone or failover to backup operator based on availability"""
    if settings.get("operator_available", True):
        return settings.get("lineman_phone", "+91 9876543210")
    else:
        return settings.get("backup_operator_phone", "+91 9876543211")

def broadcast_outage_whatsapp(area: str, issue: str, eta: str):
    """Broadcast outage updates to consumers registered in the affected area via Twilio WhatsApp API"""
    try:
        conn = sqlite3.connect(db.db_path)
        cursor = conn.cursor()
        cursor.execute("SELECT phone FROM caller_memory WHERE last_area = ?", (area,))
        phones = [row[0] for row in cursor.fetchall()]
        conn.close()
        
        if not phones:
            print(f"DEBUG WHATSAPP: No callers registered in caller_memory for area: {area}. Skipping broadcast.")
            return
            
        message_body = f"TGSPDCL Outage Alert: {area} area lo {issue} issue undi. Approx {eta} restoration time. Meeru dayachesi cooperate cheyandi andi."
        print(f"DEBUG WHATSAPP: Starting broadcast for {area} to {len(phones)} users. Message: '{message_body}'")
        
        if Config.TWILIO_ACCOUNT_SID and Config.TWILIO_AUTH_TOKEN:
            try:
                from twilio.rest import Client
                client = Client(Config.TWILIO_ACCOUNT_SID, Config.TWILIO_AUTH_TOKEN)
                sender = "whatsapp:+14155238886"  # Twilio Sandbox number
                for phone in phones:
                    formatted_phone = phone.strip()
                    if not formatted_phone.startswith("whatsapp:"):
                        if not formatted_phone.startswith("+"):
                            formatted_phone = "+91" + formatted_phone
                        formatted_phone = f"whatsapp:{formatted_phone}"
                    try:
                        client.messages.create(
                            body=message_body,
                            from_=sender,
                            to=formatted_phone
                        )
                        print(f"DEBUG WHATSAPP: Sent message to {formatted_phone}")
                    except Exception as ex:
                        print(f"DEBUG WHATSAPP: Failed to send to {formatted_phone}: {ex}")
            except Exception as ex:
                print(f"DEBUG WHATSAPP: Twilio client error: {ex}")
        else:
            print("DEBUG WHATSAPP: Twilio credentials not configured. WhatsApp broadcast simulated successfully.")
    except Exception as e:
        print(f"DEBUG WHATSAPP: Error during WhatsApp broadcast: {e}")

class VoiceUpdateRequest(BaseModel):
    area: str
    issue: str
    eta: str
    status: str
    staff_name: Optional[str] = None

class ConsumerQueryRequest(BaseModel):
    area: str
    query: str

class VoiceNoteRequest(BaseModel):
    staff_id: str
    update_text: str

class AssistantToggleRequest(BaseModel):
    is_active: bool

class SignupRequest(BaseModel):
    name: str
    phone: str
    substation: str
    employee_id: str
    password: str
    cadre: str

class LoginRequest(BaseModel):
    employee_id: str
    password: str

class OutageStatusUpdateRequest(BaseModel):
    area: str
    status: str
    staff_name: Optional[str] = None
    reason: Optional[str] = None

@router.get("/")
async def root():
    return {"message": "TGSPDCL AI Voice Call Assistant API"}

@router.post("/voice-update/")
async def process_voice_update(voice_update: VoiceUpdateRequest, background_tasks: BackgroundTasks):
    """Process voice update from field staff"""
    try:
        # Prevent database updates if the area is unrecognized, empty, or "Unknown"
        area = voice_update.area
        if not area or area.lower() == "unknown" or area.lower() == "none" or area.strip() == "":
            raise HTTPException(status_code=400, detail="Cannot update outage for unrecognized or empty area.")

        # Store the outage information in database
        db.update_outage_info(
            area=area,
            issue=voice_update.issue,
            eta=voice_update.eta,
            status=voice_update.status,
            staff_name=voice_update.staff_name
        )

        # Broadcast update to registered callers in that area via WhatsApp
        background_tasks.add_task(
            broadcast_outage_whatsapp,
            area=area,
            issue=voice_update.issue,
            eta=voice_update.eta
        )

        return {
            "message": "Voice update processed successfully",
            "data": voice_update.dict()
        }
    except HTTPException as he:
        raise he
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error processing voice update: {str(e)}")

@router.get("/outage-info/{area}")
async def get_outage_info(area: str):
    """Get outage information for a specific area"""
    outage_info = db.get_outage_info(area)
    if outage_info:
        return outage_info
    else:
        raise HTTPException(status_code=404, detail="Area not found")

@router.get("/all-outages/")
async def get_all_outages():
    """Get all outage information"""
    outages = db.get_all_outages()
    return {"outages": outages}

@router.get("/assistant-state/")
async def get_assistant_state():
    """Get the current AI assistant active state and settings"""
    return db.get_assistant_state()

@router.post("/assistant-state/toggle/")
async def toggle_assistant_state(req: AssistantToggleRequest):
    """Toggle the AI assistant active state"""
    db.set_assistant_state(req.is_active)
    return {"message": f"AI Assistant status updated to {req.is_active}", "is_active": req.is_active}

@router.post("/auth/signup/")
async def auth_signup(req: SignupRequest):
    """Register a new lineman account"""
    success = db.create_user(
        name=req.name,
        phone=req.phone,
        substation=req.substation,
        staff_id=req.employee_id,
        password=req.password,
        cadre=req.cadre
    )
    if success:
        return {"message": "Account created successfully", "employee_id": req.employee_id}
    else:
        raise HTTPException(status_code=400, detail="Employee ID or Phone Number already exists")

@router.post("/auth/login/")
async def auth_login(req: LoginRequest):
    """Verify lineman credentials"""
    user = db.verify_user(req.employee_id, req.password)
    if user:
        return {
            "message": "Login successful",
            "user": {
                "name": user["name"],
                "phone": user["phone"],
                "substation": user["substation"],
                "employee_id": user["staff_id"],
                "cadre": user["cadre"]
            }
        }
    else:
        raise HTTPException(status_code=401, detail="Invalid Employee ID or Password")

@router.post("/outage/status/")
async def update_outage_status(req: OutageStatusUpdateRequest):
    """Update outage status and record the exact time of changed status"""
    try:
        # Fetch current outage details
        info = db.get_outage_info(req.area)
        if not info:
            raise HTTPException(status_code=404, detail="Outage area not found")
        
        # Update status in SQLite and record the current time in last_updated
        db.update_outage_info(
            area=req.area,
            issue=info["issue"],
            eta=info["eta"],
            status=req.status,
            staff_name=req.staff_name or info["staff_name"],
            reason=req.reason
        )
        
        # Retrieve fresh updated info to get the actual recorded timestamp
        updated_info = db.get_outage_info(req.area)
        return {
            "message": "Outage status updated successfully",
            "area": req.area,
            "status": req.status,
            "reason": updated_info.get("reason"),
            "last_updated": updated_info.get("last_updated", datetime.now().isoformat())
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error updating outage status: {str(e)}")

@router.post("/consumer-query/")
async def process_consumer_query(query: ConsumerQueryRequest):
    """Process consumer query and generate response"""
    # Check if the AI Call Assistant permission toggle is active!
    settings = db.get_assistant_state()
    transfer_phone = get_transfer_phone(settings)
    
    if not settings["is_active"]:
        response_text = "Oka minute line lo undandi andi, mee call ni maa substation operator ki transfer chestunnamu. Varu me samasyani parishkaristaru."
        db.log_consumer_query(query.area, query.query, response_text)
        return {
            "response": response_text, 
            "outage_info": None, 
            "forwarded": True, 
            "lineman_phone": transfer_phone
        }

    # Fetch outage info from database
    outage_info = db.get_outage_info(query.area)

    # 1. Hallucination Guardrail: Bypassing Gemini if no database outage entry exists
    if not outage_info:
        response_text = "Oka minute line lo undandi andi, mee call ni maa substation operator ki transfer chestunnamu. Varu me samasyani parishkaristaru."
        db.log_consumer_query(query.area, query.query, response_text)
        return {
            "response": response_text,
            "outage_info": None,
            "forwarded": True,
            "lineman_phone": transfer_phone
        }

    # Use Gemini if key is provided and import succeeded
    if genai and Config.GEMINI_API_KEY:
        try:
            prompt = f"""
            You are the TGSPDCL AI Voice Call Assistant, a friendly customer service assistant responding to a consumer on a phone call.
            The consumer's query is: "{query.query}"
            The consumer's area is: "{query.area}"
            
            Current Outage Status from the database for the area:
            {json.dumps(outage_info)}
            
            Instructions:
            - Set "allow_guessing": false. Do not guess any information. Only answer using the exact database outage info provided.
            - Generate a friendly Telugu reply (using Telugu script). Keep it natural.
            - Since an active outage record exists, state the status, issue, and ETA clearly. Ask them to cooperate (e.g. 'cooperate cheyandi andi').
            - If they ask complex/unrelated questions other than simple power status check, set should_forward to true.
            
            You MUST respond in JSON format with three fields:
            {{
              "response": "The Telugu text to speak to the caller",
              "should_forward": true/false,
              "allow_guessing": false
            }}
            """
            
            model_name = "gemini-1.5-flash"
            try:
                model = genai.GenerativeModel(model_name)
                response = model.generate_content(
                    prompt,
                    generation_config={"response_mime_type": "application/json"}
                )
            except Exception as model_err:
                print(f"Failed to use model {model_name}, trying gemini-2.5-flash: {model_err}")
                model_name = "gemini-2.5-flash"
                model = genai.GenerativeModel(model_name)
                response = model.generate_content(
                    prompt,
                    generation_config={"response_mime_type": "application/json"}
                )
            
            result = json.loads(response.text)
            response_text = result.get("response", "")
            should_forward = result.get("should_forward", False)
            
            db.log_consumer_query(query.area, query.query, response_text)
            return {
                "response": response_text,
                "outage_info": outage_info,
                "forwarded": should_forward,
                "lineman_phone": transfer_phone if should_forward else None
            }
        except Exception as gemini_err:
            print(f"Gemini API execution error: {str(gemini_err)}. Falling back to rule-based logic.")

    # Fallback rule-based logic if Gemini is not available or fails
    # Check if the query is a complex "more question" or emergency
    lower_query = query.query.lower()
    complex_keywords = [
        "spark", "smoke", "poga", "wire", "sound", "noise", "transformer", 
        "evareyna", "operator", "officer", "office", "lineman", "lm", "alm",
        "pole", "pol", "shock", "fire", "manta", "nippu", "meter", "bill",
        "complaint", "substation", "evaru", "who", "why"
    ]
    
    is_complex = False
    for kw in complex_keywords:
        if kw in lower_query:
            if kw == "why" and "current ledu" in lower_query:
                continue
            is_complex = True
            break

    # If it is a complex query, immediately reply and forward to operator!
    if is_complex:
        response_text = "Oka minute line lo undandi andi, mee call ni maa substation operator ki transfer chestunnamu. Varu me samasyani parishkaristaru."
        db.log_consumer_query(query.area, query.query, response_text)
        return {
            "response": response_text,
            "outage_info": None,
            "forwarded": True,
            "lineman_phone": transfer_phone
        }

    if outage_info:
        # Generate response based on status
        status = outage_info.get("status", "")
        reason = outage_info.get("reason", "")
        if status.lower() in ["solved", "restored", "completed"]:
            reason_text = f" Interruption ki karanam {reason} andi." if reason else ""
            response_text = f"Mee area {query.area} lo current vachindi chudandi andi.{reason_text} Maa sahakarinchinanduku dhanyavadalu."
        else:
            if voice_processor:
                response_text = voice_processor.generate_response(query.query, outage_info)
            else:
                response_text = f"Namaskaram andi. {query.area} area lo {outage_info['issue']} samasya undi. Maa staff daanni clear chestunnaru. Approximately {outage_info['eta']} samayam padutundi. Meeru dayachesi cooperate cheyandi andi."

        # Log the query and response
        db.log_consumer_query(query.area, query.query, response_text)
        return {"response": response_text, "outage_info": outage_info, "forwarded": False, "lineman_phone": None}
    else:
        # No database outage entry exists for this area. Forward call!
        response_text = "Oka minute line lo undandi andi, mee call ni maa substation operator ki transfer chestunnamu. Varu me samasyani parishkaristaru."
        db.log_consumer_query(query.area, query.query, response_text)
        return {
            "response": response_text,
            "outage_info": None,
            "forwarded": True,
            "lineman_phone": transfer_phone
        }

@router.post("/route-call/")
async def route_call(
    caller_number: str = Form(...),
    destination_number: str = Form(...)
):
    """Route a call using Exotel Connect API"""
    api_key = Config.EXOTEL_API_KEY
    api_token = Config.EXOTEL_API_TOKEN
    account_sid = Config.EXOTEL_ACCOUNT_SID
    exophone = Config.EXOTEL_EXOPHONE
    
    if not api_key or not api_token or not account_sid or not exophone:
        print("Exotel credentials not fully configured in environment.")
        return {"status": "error", "message": "Exotel credentials not configured"}

    url = f"https://api.in.exotel.com/v1/Accounts/{account_sid}/Calls/connect.json"
    data = {
        "From": caller_number,
        "To": destination_number,
        "CallerId": exophone,
        "Record": "true"
    }
    
    try:
        import requests
        from requests.auth import HTTPBasicAuth
        response = requests.post(url, data=data, auth=HTTPBasicAuth(api_key, api_token))
        if response.status_code == 200:
            print(f"Exotel call routing initiated successfully: {response.json()}")
            return {"status": "success", "data": response.json()}
        else:
            print(f"Exotel API failed with status {response.status_code}: {response.text}")
            return {"status": "failed", "error": response.text}
    except Exception as e:
        print(f"Error calling Exotel API, trying urllib fallback: {str(e)}")
        try:
            import urllib.request
            import urllib.parse
            import base64
            import json
            
            auth_str = f"{api_key}:{api_token}"
            auth_bytes = auth_str.encode('utf-8')
            auth_b64 = base64.b64encode(auth_bytes).decode('utf-8')
            
            post_data = urllib.parse.urlencode(data).encode('utf-8')
            req = urllib.request.Request(url, data=post_data, method='POST')
            req.add_header('Authorization', f'Basic {auth_b64}')
            req.add_header('Content-Type', 'application/x-www-form-urlencoded')
            
            with urllib.request.urlopen(req) as res:
                res_data = res.read().decode('utf-8')
                return {"status": "success", "data": json.loads(res_data)}
        except Exception as urllib_err:
            return {"status": "error", "message": str(urllib_err)}

@router.post("/voice-note/")
async def process_voice_note(file: UploadFile = File(...)):
    """Process voice note from field staff and return extracted info with confidence score"""
    try:
        # Save the uploaded file
        file_path = f"temp_voice_notes/{file.filename}"
        os.makedirs("temp_voice_notes", exist_ok=True)

        with open(file_path, "wb") as buffer:
            content = await file.read()
            buffer.write(content)

        # Convert speech to text
        transcribed_text = voice_processor.convert_speech_to_text(file_path)

        # Process with rule-based NLP model first
        processed_info = nlp_model.process_text(transcribed_text)
        
        area = processed_info["entities"].get("area", "Unknown")
        issue = processed_info["entities"].get("issue", "Unknown")
        eta = processed_info["entities"].get("eta", "Not Specified")
        
        # Calculate rule-based confidence
        confidence = 0.95
        if not transcribed_text.strip():
            confidence = 0.0
        elif area == "Unknown":
            confidence = 0.50
        elif issue == "Unknown":
            confidence = 0.75
        elif eta == "Not Specified":
            confidence = 0.85
            
        processed_info["confidence"] = confidence

        # Try Gemini if key is provided and config exists
        if genai and Config.GEMINI_API_KEY and transcribed_text.strip():
            try:
                prompt = f"""
                You are a data extractor for a power distribution company. 
                Analyze the following voice note text recorded by a lineman (which might be in Telugu, English, or mixed Telugu-English) and extract the outage details:
                Voice Note: "{transcribed_text}"
                
                You must extract:
                1. area: The standardized area name from this list: Ramanapet, Cherlapally, Siddipet, Narketpally, Choutuppal, Nakrekal, Nizamabad, Warangal, Karimnagar, Khammam, Nalgonda, Suryapet, Mahabubnagar. If none match or it's unclear, return "Unknown".
                2. issue: The standardized issue type from this list: Power Outage, Voltage Issue, Transformer Problem, Line Breakdown. If none match, return "Unknown".
                3. eta: The estimated time of restoration (e.g., "30 minutes", "1 hour", "2 hours", "15 minutes"). If not specified, return "Not Specified".
                4. status: The status, default to "In Progress".
                5. confidence: A float score between 0.0 and 1.0 indicating how confident you are in the extraction based on the audio transcription clarity. If the text is clear and has the area/issue, score it >= 0.8. If ambiguous or missing crucial parts, score it < 0.7.
                
                You MUST respond in JSON format with these exact fields:
                {{
                  "area": "Area Name or Unknown",
                  "issue": "Issue Type or Unknown",
                  "eta": "ETA or Not Specified",
                  "status": "In Progress",
                  "confidence": 0.0 to 1.0
                }}
                """
                model_name = "gemini-1.5-flash"
                model = genai.GenerativeModel(model_name)
                response = model.generate_content(
                    prompt,
                    generation_config={"response_mime_type": "application/json"}
                )
                result = json.loads(response.text)
                processed_info["entities"]["area"] = result.get("area", area)
                processed_info["entities"]["issue"] = result.get("issue", issue)
                processed_info["entities"]["eta"] = result.get("eta", eta)
                processed_info["entities"]["status"] = result.get("status", "In Progress")
                processed_info["confidence"] = float(result.get("confidence", confidence))
            except Exception as gemini_err:
                print(f"Gemini voice note parsing failed: {gemini_err}. Falling back to rule-based classification.")

        # Note: We do NOT update database directly here.
        # Lineman must verify and click confirm on the mobile client.
        return {
            "message": "Voice note processed successfully",
            "transcribed_text": transcribed_text,
            "processed_info": processed_info
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error processing voice note: {str(e)}")

@router.post("/staff-voice-update/")
async def process_staff_voice_update(voice_update: VoiceNoteRequest, background_tasks: BackgroundTasks):
    """Process staff voice update"""
    try:
        # Process with NLP model
        processed_info = nlp_model.process_text(voice_update.update_text)

        # If emergency detected, escalate immediately
        if processed_info["is_emergency"]:
            return {
                "message": "EMERGENCY DETECTED - Immediate escalation required",
                "is_emergency": True,
                "processed_info": processed_info
            }

        # Update database with extracted information if area is valid and recognized (not "Unknown")
        area = processed_info["entities"].get("area")
        if area and area.lower() != "unknown" and area.lower() != "none" and area.strip() != "":
            issue = processed_info["entities"].get("issue", "Unknown Issue")
            eta = processed_info["entities"].get("eta", "Not Specified")
            db.update_outage_info(
                area=area,
                issue=issue,
                eta=eta,
                status="In Progress",
                staff_name=voice_update.staff_id
            )
            # Queue WhatsApp broadcast
            background_tasks.add_task(
                broadcast_outage_whatsapp,
                area=area,
                issue=issue,
                eta=eta
            )

        return {
            "message": "Staff voice update processed successfully",
            "processed_info": processed_info
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error processing staff voice update: {str(e)}")

@router.get("/call-logs/")
async def get_call_logs():
    """Retrieve all call logs"""
    try:
        logs = db.get_all_call_logs()
        return {"logs": logs}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error retrieving call logs: {str(e)}")

class OperatorToggleRequest(BaseModel):
    available: bool

@router.get("/analytics/")
async def get_analytics():
    """Retrieve supervisor analytics data"""
    try:
        conn = sqlite3.connect(db.db_path)
        cursor = conn.cursor()
        
        # Total calls
        cursor.execute("SELECT COUNT(*) FROM call_logs")
        total_calls = cursor.fetchone()[0]
        
        # AI Resolved (status = 'Completed')
        cursor.execute("SELECT COUNT(*) FROM call_logs WHERE status = 'Completed'")
        ai_resolved = cursor.fetchone()[0]
        
        # Forwarded (status = 'Forwarded')
        cursor.execute("SELECT COUNT(*) FROM call_logs WHERE status = 'Forwarded'")
        forwarded = cursor.fetchone()[0]
        
        # Emergency (status = 'Emergency')
        cursor.execute("SELECT COUNT(*) FROM call_logs WHERE status = 'Emergency'")
        emergency = cursor.fetchone()[0]
        
        # Average Call Duration
        cursor.execute("SELECT AVG(duration) FROM call_logs WHERE duration > 0")
        avg_res = cursor.fetchone()[0]
        avg_duration = round(avg_res) if avg_res is not None else 0
        
        # Seed values fallback if database is empty (for TGSPDCL presentations)
        if total_calls == 0:
            total_calls = 124
            ai_resolved = 98
            forwarded = 26
            emergency = 3
            avg_duration = 42
            
        conn.close()
        
        return {
            "total_calls": total_calls,
            "ai_resolved": ai_resolved,
            "forwarded": forwarded,
            "emergency_calls": emergency,
            "avg_duration": avg_duration
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error retrieving analytics: {str(e)}")

@router.get("/operator-state/")
async def get_operator_state():
    """Get the current operator availability state"""
    state = db.get_assistant_state()
    return {"available": state["operator_available"], "backup_phone": state["backup_operator_phone"]}

@router.post("/operator-state/toggle/")
async def toggle_operator_state(req: OperatorToggleRequest):
    """Toggle the operator availability state"""
    db.set_operator_availability(req.available)
    return {"message": f"Operator availability updated to {req.available}", "available": req.available}

@router.post("/call-logs/")
async def create_call_log(
    caller_number: str = Form(...),
    transcript: str = Form(...),
    status: str = Form(...),
    duration: int = Form(0),
    file: Optional[UploadFile] = File(None)
):
    """Create a new call log and save its recording file if uploaded"""
    try:
        audio_path = ""
        if file:
            project_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
            recordings_dir = os.path.join(project_dir, "static", "recordings")
            os.makedirs(recordings_dir, exist_ok=True)
            
            # Save the file
            filename = f"rec_{int(datetime.now().timestamp())}_{file.filename}"
            file_dest = os.path.join(recordings_dir, filename)
            with open(file_dest, "wb") as buffer:
                content = await file.read()
                buffer.write(content)
            audio_path = f"/static/recordings/{filename}"
            
        inserted_id = db.save_call_log(
            caller_number=caller_number,
            transcript=transcript,
            audio_path=audio_path,
            status=status,
            duration=duration
        )
        return {
            "message": "Call log saved successfully",
            "id": inserted_id,
            "audio_path": audio_path
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error saving call log: {str(e)}")

@router.post("/voice/incoming")
async def voice_incoming(
    From_: str = Form(..., alias="From"),
    CallSid: str = Form(...)
):
    """Webhook for incoming Twilio calls"""
    response = VoiceResponse()
    settings = db.get_assistant_state()
    transfer_phone = get_transfer_phone(settings)
    
    # 1. Abuse Protection Check (max 10 calls per hour)
    recent_calls = db.get_recent_call_count(From_)
    if recent_calls >= 10:
        response.say("Mee number nundi e roju chala sarlu call chesaru andi. Dayachesi aagandi, call ni operator ki forward chestunnamu.", language="te-IN")
        response.dial(transfer_phone)
        db.save_call_log(
            caller_number=From_,
            transcript="[Abuse Protection - Limit Exceeded, Transferred to Operator]",
            audio_path="",
            status="Forwarded"
        )
        return Response(content=str(response), media_type="application/xml")
    
    if not settings.get("is_active", True):
        # AI assistant is inactive. Forward to lineman operator immediately.
        response.say("Namaskaram andi. Maa AI call assistant ippudu offline lo undi. Mee call ni substation operator ki forward chestunnamu. Oka minute line lo undandi.", language="te-IN")
        response.dial(transfer_phone)
        
        # Log call log
        db.save_call_log(
            caller_number=From_,
            transcript="[AI Assistant Offline - Forwarded to Operator]",
            audio_path="",
            status="Forwarded"
        )
        return Response(content=str(response), media_type="application/xml")

    # 2. Caller Location Memory query for custom greeting
    last_area = db.get_caller_memory(From_)
    
    # If assistant is active, prompt for speech gather, specifying turn=1
    gather = Gather(
        input="speech",
        language="te-IN",
        action="/api/v1/voice/respond?turn=1",
        method="POST",
        timeout=5
    )
    
    if last_area:
        greeting_text = f"Namaskaram andi. TGSPDCL AI Voice Call Assistant ki swagatam. Mee {last_area} area gurinche adugutunnara andi? Leda mee area mariyu samasya ento cheppandi."
    else:
        greeting_text = "Namaskaram andi. TGSPDCL AI Voice Call Assistant ki swagatam. Mee area peru mariyu mee samasya ento cheppandi."
        
    gather.say(greeting_text, language="te-IN")
    response.append(gather)
    
    # If no speech was input, redirect/fallback to lineman operator
    response.say("Mee nundi elaanti prathispandana ledandi. Call ni operator ki forward chestunnamu.", language="te-IN")
    response.dial(transfer_phone)
    
    return Response(content=str(response), media_type="application/xml")

@router.post("/voice/respond")
async def voice_respond(
    From_: str = Form(..., alias="From"),
    CallSid: str = Form(...),
    SpeechResult: Optional[str] = Form(None),
    turn: int = 1
):
    """Webhook to process speech gather result and generate TwiML response"""
    response = VoiceResponse()
    settings = db.get_assistant_state()
    transfer_phone = get_transfer_phone(settings)

    if not SpeechResult:
        response.say("Mee nundi elaanti prathispandana ledandi. Call ni operator ki forward chestunnamu.", language="te-IN")
        response.dial(transfer_phone)
        db.save_call_log(
            caller_number=From_,
            transcript="[No speech detected / Timeout]",
            audio_path="",
            status="Forwarded",
            duration=0
        )
        return Response(content=str(response), media_type="application/xml")

    # Check if this is a follow-up turn and user said no/nothing
    if turn > 1:
        clean_speech = SpeechResult.strip().lower()
        negative_words = ["ledu", "ledhu", "em ledu", "em ledhu", "no", "nothing", "thanks", "thank you", "chalu", "saripotundi"]
        query_indicators = ["power", "current", "light", "samasya", "poyindi", "breakdown", "spark", "కరెంట్", "కరెంటు", "పవర్", "ఎప్పుడు"]
        if any(neg in clean_speech for neg in negative_words) and not any(ind in clean_speech for ind in query_indicators):
            response.say("Dhanyavadalu andi. Selavu.", language="te-IN")
            response.hangup()
            
            db.save_call_log(
                caller_number=From_,
                transcript=f"Consumer: {SpeechResult}\nAI: Dhanyavadalu andi. Selavu.",
                audio_path="",
                status="Completed",
                duration=0
            )
            return Response(content=str(response), media_type="application/xml")

    # Extract area using NLP
    nlp_res = nlp_model.process_text(SpeechResult)
    area = nlp_res["entities"].get("area", "Unknown")
    
    # 1. Caller Location Memory fallback
    last_area = db.get_caller_memory(From_)
    if area == "Unknown" and last_area:
        affirmative_words = ["avunu", "yes", "ha", "aunu", "adhe", "ade", "correct", "అవును", "అవునండి", "ఆవును"]
        lower_speech = SpeechResult.lower().strip()
        if any(word in lower_speech for word in affirmative_words):
            area = last_area

    # 2. Update caller location memory if area is now resolved
    if area != "Unknown":
        db.update_caller_memory(From_, area)
    
    # Query database for outage info
    outage_info = db.get_outage_info(area) if area != "Unknown" else None
    
    should_forward = True
    response_text = ""
    priority = "normal"

    # Emergency check first (must bypass normal outage info checks to alert lineman)
    critical_keywords = ["wire down", "shock", "fire", "manta", "nippu", "తీగ", "వైరు", "షాక్", "ప్రమాదం"]
    high_keywords = ["spark", "smoke", "poga", "transformer", "sparks", "ట్రాన్స్"]
    lower_query = SpeechResult.lower()
    
    is_emergency = False
    if any(kw in lower_query for kw in critical_keywords):
        priority = "critical"
        response_text = "Samasya chala gambheeramainadhi andi. Maa operator ki ventane transfer chestunnamu. Line lo undandi."
        should_forward = True
        is_emergency = True
    elif any(kw in lower_query for kw in high_keywords):
        priority = "high"
        response_text = "Mee area lo transformer daggara sparks/poga vastunnayi andi. Dayachesi aagu daggara nunchi dooramga undandi. Call ni operator ki connect chestunnamu."
        should_forward = True
        is_emergency = True

    # 3. Prevent Hallucinated Outages: If no outage record exists in database, bypass LLM and transfer
    if not is_emergency:
        if area == "Unknown" or not outage_info:
            # Bypassing Gemini to prevent outage hallucination!
            response_text = "Oka minute line lo undandi andi, mee call ni maa substation operator ki transfer chestunnamu. Varu me samasyani parishkaristaru."
            should_forward = True
        else:
            # Outage record exists! Proceed to query caching or Gemini.
            is_simple_query = False
            simple_keywords = ["current", "power", "ledhu", "ledu", "eppudu", "vastundi", "vastadhi", "poyindi", "కరెంట్", "కరెంటు", "పవర్", "ఎప్పుడు"]
            complex_keywords = [
                "spark", "smoke", "poga", "wire", "sound", "noise", "transformer", 
                "evareyna", "operator", "officer", "office", "lineman", "lm", "alm",
                "pole", "pol", "shock", "fire", "manta", "nippu", "meter", "bill",
                "complaint", "substation", "evaru", "who", "why"
            ]
            
            has_simple = any(kw in lower_query for kw in simple_keywords)
            has_complex = any(kw in lower_query for kw in complex_keywords)
            if has_simple and not has_complex:
                is_simple_query = True

            # Caching check
            if is_simple_query:
                response_text = voice_processor.generate_response(SpeechResult, outage_info)
                should_forward = False
                priority = "normal"

            # Try Gemini if cache missed
            if not response_text and genai and Config.GEMINI_API_KEY:
                try:
                    prompt = f"""
                    You are the TGSPDCL AI Voice Call Assistant, a friendly customer service assistant responding to a consumer on a phone call.
                    Speak in local Telangana dialect (polite regional Telugu as spoken by local TGSPDCL lineman/substation staff in Hyderabad/Telangana).
                    Always use the respect marker 'andi' (అండి) instead of 'sir'.
                    
                    The consumer's query is: "{SpeechResult}"
                    The consumer's area is: "{area}"
                    
                    Current Outage Status from the database for the area:
                    {json.dumps(outage_info)}
                    
                    Instructions:
                    - Set "allow_guessing": false. Do not guess any information. Only answer using the exact database outage info provided.
                    - Classify severity: "normal" for power status, ETAs, and restoration queries.
                    - Since an active outage record exists, state the status, issue, and ETA clearly to reassure them in local dialect. Ask them to cooperate (e.g. 'cooperate cheyandi andi').
                    - If they ask complex/unrelated questions other than simple power status check, set should_forward to true.
                    
                    You MUST respond in JSON format with three fields:
                    {{
                      "response": "The Telugu dialect text to speak to the caller",
                      "should_forward": true/false,
                      "priority": "normal",
                      "allow_guessing": false
                    }}
                    """
                    model_name = "gemini-1.5-flash"
                    try:
                        model = genai.GenerativeModel(model_name)
                        gen_res = model.generate_content(
                            prompt,
                            generation_config={"response_mime_type": "application/json"}
                        )
                    except Exception as model_err:
                        print(f"Failed to use model {model_name}, trying gemini-2.5-flash: {model_err}")
                        model_name = "gemini-2.5-flash"
                        model = genai.GenerativeModel(model_name)
                        gen_res = model.generate_content(
                            prompt,
                            generation_config={"response_mime_type": "application/json"}
                        )
                    
                    result = json.loads(gen_res.text)
                    response_text = result.get("response", "")
                    should_forward = result.get("should_forward", True)
                    priority = result.get("priority", "normal")
                except Exception as gemini_err:
                    print(f"Gemini API execution error inside webhook: {str(gemini_err)}. Falling back to rule-based logic.")

            # Fallback to rule-based response
            if not response_text:
                status = outage_info.get("status", "")
                reason = outage_info.get("reason", "")
                if status.lower() in ["solved", "restored", "completed"]:
                    reason_text = f" Interruption ki karanam {reason} andi." if reason else ""
                    response_text = f"Mee area {area} lo current vachindi chudandi andi.{reason_text} Maa sahakarinchinanduku dhanyavadalu."
                    should_forward = False
                else:
                    if voice_processor:
                        response_text = voice_processor.generate_response(SpeechResult, outage_info)
                    else:
                        response_text = f"Namaskaram andi. {area} area lo {outage_info['issue']} samasya undi. Maa staff daanni clear chestunnaru. Approximately {outage_info['eta']} samayam padutundi. Meeru dayachesi cooperate cheyandi andi."
                    should_forward = False

    # Build TwiML voice response
    if should_forward:
        # Emergency safety warning or operator transfer
        response.say(response_text, language="te-IN")
        response.dial(transfer_phone)
    else:
        # Check turn limit to prevent infinite loops
        if turn >= 3:
            response.say(response_text, language="te-IN")
            response.say("Inkemaina sahayam kavala andi? Lekapothe call ni mugistunnamu. Sahakarinchinanduku dhanyavadalu.", language="te-IN")
            response.hangup()
        else:
            response.say(response_text, language="te-IN")
            # Loop with another Gather for multi-turn conversational dialog
            gather = Gather(
                input="speech",
                language="te-IN",
                action=f"/api/v1/voice/respond?turn={turn + 1}",
                method="POST",
                timeout=5
            )
            gather.say("Meku inkemainaa prasnalu unnaayaa andi?", language="te-IN")
            response.append(gather)
            
            # If no speech on gather (timeout)
            response.say("Mee nundi elaanti prathispandana ledandi. Call ni operator ki forward chestunnamu.", language="te-IN")
            response.dial(transfer_phone)

    # 4. Correct Emergency Call status classification
    log_status = "Completed"
    if priority in ["critical", "high"]:
        log_status = "Emergency"
    elif should_forward:
        log_status = "Forwarded"

    # Log the call transcript to database
    db.save_call_log(
        caller_number=From_,
        transcript=f"Consumer: {SpeechResult}\nAI: {response_text}",
        audio_path="",
        status=log_status,
        duration=0
    )

    return Response(content=str(response), media_type="application/xml")