from fastapi import APIRouter, UploadFile, File, HTTPException
from pydantic import BaseModel
from typing import Optional
import os
from datetime import datetime
import sqlite3

# Import our modules
from models.database import DatabaseManager
from voice_processing.voice_processor import VoiceProcessor
from config.settings import Config

# Initialize components
router = APIRouter(prefix="/api/v1", tags=["staff_updates"])

# Initialize database and voice processor
db = DatabaseManager()
voice_processor = VoiceProcessor() if Config.OPENAI_API_KEY else None

class StaffVoiceUpdate(BaseModel):
    staff_id: str
    update_text: str
    area: Optional[str] = None
    issue: Optional[str] = None
    eta: Optional[str] = None

@router.post("/process-staff-update/")
async def process_staff_update(update: StaffVoiceUpdate):
    """Process staff update"""
    try:
        # If area, issue, and eta are provided directly, use them
        if update.area and update.issue and update.eta:
            db.update_outage_info(
                area=update.area,
                issue=update.issue,
                eta=update.eta,
                status="In Progress",
                staff_name=update.staff_id
            )

            return {
                "message": "Staff update processed successfully",
                "data": update.dict()
            }
        else:
            # Process with NLP if text is provided
            # In a real implementation, this would use our custom NLP model
            return {
                "message": "Staff update processed successfully",
                "data": update.dict()
            }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error processing staff update: {str(e)}")

@router.post("/process-voice-note/")
async def process_voice_note(file: UploadFile = File(...)):
    """Process voice note from field staff"""
    try:
        # Save the uploaded file
        file_path = f"temp_voice_notes/{file.filename}"
        os.makedirs("temp_voice_notes", exist_ok=True)

        with open(file_path, "wb") as buffer:
            content = await file.read()
            buffer.write(content)

        # In a real implementation, we would:
        # 1. Convert speech to text using Whisper
        # 2. Extract information using our custom NLP model
        # 3. Update outage database

        # For now, we'll just acknowledge receipt
        return {"message": "Voice note received", "filename": file.filename, "file_path": file_path}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error processing voice note: {str(e)}")