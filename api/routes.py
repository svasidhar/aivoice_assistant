from fastapi import APIRouter, UploadFile, File, HTTPException, Header, Depends
from pydantic import BaseModel, Field, field_validator
from typing import Optional, List
import os
from datetime import datetime, timedelta
import json
import re
import sys
import structlog
import jwt
from functools import wraps

# Setup structured logging
structlog.configure(
    processors=[
        structlog.stdlib.filter_by_level,
        structlog.stdlib.add_logger_name,
        structlog.stdlib.add_log_level,
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.JSONRenderer()
    ],
    wrapper_class=structlog.stdlib.BoundLogger,
    context_class=dict,
    logger_factory=structlog.stdlib.LoggerFactory(),
)
logger = structlog.get_logger()

# Add project root to path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Import our modules
from models.database import DatabaseManager
from voice_processing.voice_processor import VoiceProcessor
from config.settings import Config
from models.nlp_model import CustomNLPModel
from api.session_manager import session_manager

# JWT secret for auth middleware
JWT_SECRET = os.getenv("JWT_SECRET", "tgspdcl-secret-key-change-in-production")
JWT_ALGORITHM = "HS256"


# ============= JWT Authentication Dependency =============

async def get_current_user(authorization: Optional[str] = Header(None)) -> Optional[dict]:
    """
    Extract and verify JWT token from Authorization header.
    Returns None if no token or invalid (allows public access).
    For protected routes, use require_auth wrapper.
    """
    if not authorization:
        return None

    # Extract token from "Bearer <token>" format
    parts = authorization.split()
    if len(parts) != 2 or parts[0].lower() != "bearer":
        return None

    token = parts[1]
    payload = verify_token(token)
    return payload


def require_auth(user: Optional[dict] = Depends(get_current_user)):
    """Dependency that requires valid authentication."""
    if user is None:
        raise HTTPException(status_code=401, detail="Authentication required")
    return user

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

class VoiceUpdateRequest(BaseModel):
    area: str = Field(..., min_length=1, max_length=100, description="Area name must be 1-100 characters")
    issue: str = Field(..., min_length=1, max_length=500, description="Issue description must be 1-500 characters")
    eta: str = Field(..., min_length=1, max_length=100, description="ETA must be 1-100 characters")
    status: str = Field(..., min_length=1, max_length=50, description="Status must be 1-50 characters")
    staff_name: Optional[str] = Field(None, max_length=100, description="Staff name must be under 100 characters")

    @field_validator('area')
    @classmethod
    def validate_area(cls, v):
        if not v or v.strip() == "" or v.lower() in ["unknown", "none", "null"]:
            raise ValueError("Area cannot be empty, unknown, none, or null")
        # Sanitize: allow only alphanumeric, spaces, and common Indian place name chars
        if not re.match(r'^[a-zA-Z0-9\s_\-\'\.]+$', v):
            raise ValueError("Area contains invalid characters")
        return v.strip()

    @field_validator('status')
    @classmethod
    def validate_status(cls, v):
        allowed_statuses = ["In Progress", "Pending", "Solved", "Restored", "Completed", "Investigating"]
        if v not in allowed_statuses:
            raise ValueError(f"Status must be one of: {', '.join(allowed_statuses)}")
        return v


class ConsumerQueryRequest(BaseModel):
    area: str = Field(..., min_length=1, max_length=100, description="Area name must be 1-100 characters")
    query: str = Field(..., min_length=1, max_length=1000, description="Query must be 1-1000 characters")
    session_id: Optional[str] = Field(None, description="Session ID for conversation state tracking")

    @field_validator('area')
    @classmethod
    def validate_area(cls, v):
        if not v or v.strip() == "":
            raise ValueError("Area cannot be empty")
        return v.strip()


class VoiceNoteRequest(BaseModel):
    staff_id: str = Field(..., min_length=1, max_length=50, description="Staff ID must be 1-50 characters")
    update_text: str = Field(..., min_length=1, max_length=2000, description="Update text must be 1-2000 characters")

    @field_validator('staff_id')
    @classmethod
    def validate_staff_id(cls, v):
        if not re.match(r'^[a-zA-Z0-9_\-]+$', v):
            raise ValueError("Staff ID contains invalid characters")
        return v


class AssistantToggleRequest(BaseModel):
    is_active: bool


class SignupRequest(BaseModel):
    name: str = Field(..., min_length=1, max_length=100, description="Name must be 1-100 characters")
    phone: str = Field(..., min_length=10, max_length=15, description="Phone must be 10-15 characters")
    substation: str = Field(..., min_length=1, max_length=100, description="Substation must be 1-100 characters")
    employee_id: str = Field(..., min_length=1, max_length=50, description="Employee ID must be 1-50 characters")
    password: str = Field(..., min_length=6, max_length=128, description="Password must be 6-128 characters")
    cadre: str = Field(default="LM", max_length=50, description="Cadre must be under 50 characters")

    @field_validator('phone')
    @classmethod
    def validate_phone(cls, v):
        # Allow common phone formats: +91 9876543210, 9876543210, etc.
        if not re.match(r'^[\+]?[0-9\s\-]{10,15}$', v):
            raise ValueError("Invalid phone number format")
        return v


class LoginRequest(BaseModel):
    employee_id: str = Field(..., min_length=1, max_length=50, description="Employee ID must be 1-50 characters")
    password: str = Field(..., min_length=1, max_length=128, description="Password must be 1-128 characters")


class OutageStatusUpdateRequest(BaseModel):
<<<<<<< HEAD
    area: str
    status: str
    staff_name: Optional[str] = None
    reason: Optional[str] = None
=======
    area: str = Field(..., min_length=1, max_length=100, description="Area name must be 1-100 characters")
    status: str = Field(..., min_length=1, max_length=50, description="Status must be 1-50 characters")
    staff_name: Optional[str] = Field(None, max_length=100, description="Staff name must be under 100 characters")

    @field_validator('status')
    @classmethod
    def validate_status(cls, v):
        allowed_statuses = ["In Progress", "Pending", "Solved", "Restored", "Completed", "Investigating"]
        if v not in allowed_statuses:
            raise ValueError(f"Status must be one of: {', '.join(allowed_statuses)}")
        return v
>>>>>>> 819d7d4 (feat: Security and reliability improvements)

@router.get("/")
async def root():
    logger.info("Root endpoint accessed")
    return {"message": "TGSPDCL AI Voice Call Assistant API"}

@router.get("/health")
async def health_check():
    """Health check endpoint for monitoring"""
    try:
        db.get_assistant_state()
        db_status = "healthy"
    except Exception as e:
        logger.error("Database health check failed", error=str(e))
        db_status = "unhealthy"

    return {
        "status": db_status,
        "timestamp": datetime.now().isoformat()
    }

# ============= JWT Auth Helper Functions =============

def create_access_token(data: dict, expires_delta: Optional[timedelta] = None) -> str:
    """Create JWT access token for authenticated users."""
    to_encode = data.copy()
    expire = datetime.utcnow() + (expires_delta or timedelta(hours=24))
    to_encode.update({"exp": expire})
    return jwt.encode(to_encode, JWT_SECRET, algorithm=JWT_ALGORITHM)

def verify_token(token: str) -> Optional[dict]:
    """Verify JWT token and return payload."""
    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        return payload
    except jwt.ExpiredSignatureError:
        return None
    except jwt.InvalidTokenError:
        return None

# ============= Session Management Endpoints =============

@router.post("/session/start/")
async def start_conversation_session(caller_number: str):
    """Start a new conversation session for an incoming call."""
    session = session_manager.get_or_create_session(caller_number)
    logger.info("Conversation session started", session_id=session.session_id, caller=caller_number)
    return {
        "session_id": session.session_id,
        "caller_number": caller_number,
        "step": session.step
    }

@router.get("/session/{session_id}/state/")
async def get_conversation_state(session_id: str):
    """Get current conversation state for a session."""
    session = session_manager.get_session(session_id)
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")
    return {
        "session_id": session.session_id,
        "step": session.step,
        "detected_area": session.detected_area,
        "forwarded": session.forwarded
    }

@router.post("/session/{session_id}/advance/")
async def advance_conversation_step(session_id: str, area: Optional[str] = None):
    """Advance conversation to next step."""
    session = session_manager.get_session(session_id)
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")
    if area:
        session.detected_area = area
    session.advance_step()
    return {
        "session_id": session.session_id,
        "step": session.step,
        "detected_area": session.detected_area
    }

@router.post("/session/{session_id}/end/")
async def end_conversation_session(session_id: str):
    """End a conversation session (call completed)."""
    session_manager.end_session(session_id)
    return {"message": "Session ended", "session_id": session_id}

# ============= Consumer Query with Session Support =============

@router.post("/voice-update/")
async def process_voice_update(voice_update: VoiceUpdateRequest):
    """Process voice update from field staff"""
    try:
        logger.info("Processing voice update", area=voice_update.area, status=voice_update.status)

        # Prevent database updates if the area is unrecognized, empty, or "Unknown"
        area = voice_update.area
        if not area or area.lower() == "unknown" or area.lower() == "none" or area.strip() == "":
            logger.warn("Invalid area in voice update", area=voice_update.area)
            raise HTTPException(status_code=400, detail="Cannot update outage for unrecognized or empty area.")

        # Store the outage information in database
        db.update_outage_info(
            area=area,
            issue=voice_update.issue,
            eta=voice_update.eta,
            status=voice_update.status,
            staff_name=voice_update.staff_name
        )

        logger.info("Voice update processed successfully", area=area)
        return {
            "message": "Voice update processed successfully",
            "data": voice_update.dict()
        }
    except HTTPException as he:
        raise he
    except Exception as e:
        logger.error("Error processing voice update", error=str(e), exc_info=True)
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
async def toggle_assistant_state(req: AssistantToggleRequest, user: Optional[dict] = Depends(get_current_user)):
    """Toggle the AI assistant active state (requires authentication)"""
    if not user:
        raise HTTPException(status_code=401, detail="Authentication required")
    logger.info("Toggling assistant state", is_active=req.is_active, user=user.get("sub", "unknown"))
    db.set_assistant_state(req.is_active)
    return {"message": f"AI Assistant status updated to {req.is_active}", "is_active": req.is_active}

@router.post("/auth/signup/")
async def auth_signup(req: SignupRequest):
    """Register a new lineman account"""
    logger.info("User signup attempt", employee_id=req.employee_id, substation=req.substation)
    success = db.create_user(
        name=req.name,
        phone=req.phone,
        substation=req.substation,
        staff_id=req.employee_id,
        password=req.password,
        cadre=req.cadre
    )
    if success:
        logger.info("User account created successfully", employee_id=req.employee_id)
        # Generate JWT token for new user
        token = create_access_token({"sub": req.employee_id, "name": req.name})
        return {"message": "Account created successfully", "employee_id": req.employee_id, "access_token": token}
    else:
        logger.warn("User signup failed - duplicate", employee_id=req.employee_id, phone=req.phone)
        raise HTTPException(status_code=400, detail="Employee ID or Phone Number already exists")

@router.post("/auth/login/")
async def auth_login(req: LoginRequest):
    """Verify lineman credentials and return JWT token"""
    logger.info("User login attempt", employee_id=req.employee_id)
    user = db.verify_user(req.employee_id, req.password)
    if user:
        logger.info("User login successful", employee_id=req.employee_id)
        # Generate JWT token
        token = create_access_token({
            "sub": user["staff_id"],
            "name": user["name"],
            "substation": user["substation"],
            "cadre": user["cadre"]
        })
        return {
            "message": "Login successful",
            "access_token": token,
            "token_type": "bearer",
            "user": {
                "name": user["name"],
                "phone": user["phone"],
                "substation": user["substation"],
                "employee_id": user["staff_id"],
                "cadre": user["cadre"]
            }
        }
    else:
        logger.warn("User login failed - invalid credentials", employee_id=req.employee_id)
        raise HTTPException(status_code=401, detail="Invalid Employee ID or Password")

@router.post("/outage/status/")
async def update_outage_status(req: OutageStatusUpdateRequest, user: Optional[dict] = Depends(get_current_user)):
    """Update outage status (requires authentication)"""
    if not user:
        raise HTTPException(status_code=401, detail="Authentication required")
    try:
        logger.info("Updating outage status", area=req.area, status=req.status, user=user.get("sub", "unknown"))
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
    if not settings["is_active"]:
        response_text = "Oka minute aagandee, maa substation operator line loki vastaadu. Athanine adigandee."
        db.log_consumer_query(query.area, query.query, response_text)
        return {
            "response": response_text, 
            "outage_info": None, 
            "forwarded": True, 
            "lineman_phone": settings['lineman_phone']
        }

    # Fetch outage info from database
    outage_info = db.get_outage_info(query.area)

    # Use Gemini if key is provided and import succeeded
    if genai and Config.GEMINI_API_KEY:
        try:
            prompt = f"""
            You are the TGSPDCL AI Voice Call Assistant, a friendly and polite customer service assistant responding to a consumer on a phone call.
            The consumer's query is: "{query.query}"
            The consumer's area is: "{query.area}"

            Current Outage Status from the database for the area:
            {json.dumps(outage_info) if outage_info else "No active outage record found for this area."}

            Instructions:
            1. Generate a friendly, polite, conversational reply in Telugu (using Telugu script). Keep it natural for voice communication.
            2. If there is an active outage in the database (status is In Progress, Pending, etc.):
               - Politely inform the consumer about the outage status, the cause (issue), and the ETA (Estimated Time of Restoration) in Telugu. Keep it simple, clear, and reassuring. Ask them to cooperate (e.g., 'meeru dayachesi cooperate cheyandi sir').
            3. If the outage is marked as Solved, Completed, or Restored:
               - Inform them that power has been restored: say "current vachindi chudandi sir" (current came, check it) in Telugu.
               - Also, mention the reason why the power was interrupted if there is a reason for interruption recorded in the database (e.g., 'interruption ki karanam <reason>'). Thank them for their cooperation.
            4. If the consumer asks complex or unrelated questions (e.g. regarding billing, contact details or phone numbers, complaints, reporting sparks/fire emergency, asking to speak to a supervisor/officer/operator/lineman, or any query other than simple power outage checks), OR if no outage record exists for their area in the database:
               - Set "should_forward" to true in your JSON output.
               - In the "response" text, politely inform them that you are forwarding their call to the substation operator/lineman. For example: "Oka minute aagandi sir, mee call ni substation operator ki forward chestunnamu. Valle ki cheppandi mee samasya."
            5. If they just ask simple power status check questions (like 'current eppudu vastundi', 'current enduku poyindi') and we have the active database outage info, answer it and set "should_forward" to false.

            You MUST respond in JSON format with two fields:
            {{
              "response": "The Telugu text to speak to the caller",
              "should_forward": true/false
            }}
            """

            result = None
            model_name = "gemini-1.5-flash"
            try:
                model = genai.GenerativeModel(model_name)
                response = model.generate_content(
                    prompt,
                    generation_config={"response_mime_type": "application/json"}
                )
                result = json.loads(response.text)
            except Exception as model_err:
                print(f"Gemini API error with {model_name}: {model_err}. Trying fallback model gemini-2.5-flash...")
                try:
                    model_name = "gemini-2.5-flash"
                    model = genai.GenerativeModel(model_name)
                    response = model.generate_content(
                        prompt,
                        generation_config={"response_mime_type": "application/json"}
                    )
                    result = json.loads(response.text)
                except Exception as fallback_err:
                    print(f"Fallback model {model_name} also failed: {fallback_err}")
                    result = None

            if result:
                response_text = result.get("response", "")
                should_forward = result.get("should_forward", False)

                db.log_consumer_query(query.area, query.query, response_text)
                return {
                    "response": response_text,
                    "outage_info": outage_info,
                    "forwarded": should_forward,
                    "lineman_phone": settings['lineman_phone'] if should_forward else None
                }
            else:
                print("Gemini API returned no result, falling back to rule-based logic.")
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
        response_text = "Oka minute aagandee, maa substation operator line loki vastaadu. Athanine adigandee."
        db.log_consumer_query(query.area, query.query, response_text)
        return {
            "response": response_text,
            "outage_info": None,
            "forwarded": True,
            "lineman_phone": settings['lineman_phone']
        }

    if outage_info:
        # Generate response based on status
        status = outage_info.get("status", "")
        reason = outage_info.get("reason", "")
        if status.lower() in ["solved", "restored", "completed"]:
            reason_text = f" Interruption ki karanam {reason} sir." if reason else ""
            response_text = f"Mee area {query.area} lo current vachindi chudandi sir.{reason_text} Cooperation ki dhanyavadalu."
        else:
            if voice_processor:
                response_text = voice_processor.generate_response(query.query, outage_info)
            else:
                response_text = f"{query.area} area lo {outage_info['issue']} undi sir. Staff clear chestunnaru. Approximately {outage_info['eta']} padutundi. Meeru cooperate cheyandi sir."

        # Log the query and response
        db.log_consumer_query(query.area, query.query, response_text)
        return {"response": response_text, "outage_info": outage_info, "forwarded": False, "lineman_phone": None}
    else:
        # No database outage entry exists for this area. Forward call!
        response_text = "Oka minute aagandee, maa substation operator line loki vastaadu. Athanine adigandee."
        db.log_consumer_query(query.area, query.query, response_text)
        return {
            "response": response_text,
            "outage_info": None,
            "forwarded": True,
            "lineman_phone": settings['lineman_phone']
        }

@router.post("/voice-note/")
async def process_voice_note(file: UploadFile = File(...)):
    """Process voice note from field staff"""
    try:
        # Save the uploaded file
        file_path = f"temp_voice_notes/{file.filename}"
        os.makedirs("temp_voice_notes", exist_ok=True)

        with open(file_path, "wb") as buffer:
            content = await file.read()
            buffer.write(content)

        # Convert speech to text
        transcribed_text = voice_processor.convert_speech_to_text(file_path)

        # Process with NLP model
        processed_info = nlp_model.process_text(transcribed_text)

        # Update database with extracted information if area is valid and recognized (not "Unknown")
        area = processed_info["entities"].get("area")
        if area and area.lower() != "unknown" and area.lower() != "none" and area.strip() != "":
            db.update_outage_info(
                area=area,
                issue=processed_info["entities"].get("issue", "Unknown Issue"),
                eta=processed_info["entities"].get("eta", "Not Specified"),
                status="In Progress"
            )

        return {
            "message": "Voice note processed successfully",
            "transcribed_text": transcribed_text,
            "processed_info": processed_info
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error processing voice note: {str(e)}")

@router.post("/staff-voice-update/")
async def process_staff_voice_update(voice_update: VoiceNoteRequest):
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
            db.update_outage_info(
                area=area,
                issue=processed_info["entities"].get("issue", "Unknown Issue"),
                eta=processed_info["entities"].get("eta", "Not Specified"),
                status="In Progress",
                staff_name=voice_update.staff_id
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

@router.post("/call-logs/")
async def create_call_log(
    caller_number: str = Form(...),
    transcript: str = Form(...),
    status: str = Form(...),
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
            status=status
        )
        return {
            "message": "Call log saved successfully",
            "id": inserted_id,
            "audio_path": audio_path
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error saving call log: {str(e)}")