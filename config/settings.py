# config/settings.py
import os
from dotenv import load_dotenv

# Automatically look for and load a .env file from the root directory
load_dotenv()

class Config:
    # ─── CORE GENAI CREDENTIALS ───
    GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")
    SARVAM_API_KEY = os.getenv("SARVAM_API_KEY", "")
    OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
    ELEVENLABS_API_KEY = os.getenv("ELEVENLABS_API_KEY", "")
    
    # ─── DATABASE CONFIGURATION ───
    DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:///./test.db")
    
    # ─── VOICE CONFIGURATION ───
    VOICE_PROVIDER = os.getenv("VOICE_PROVIDER", "elevenlabs")
    
    # ─── CLOUD TELEPHONY VARIABLES ───
    TWILIO_ACCOUNT_SID = os.getenv("TWILIO_ACCOUNT_SID", "")
    TWILIO_AUTH_TOKEN = os.getenv("TWILIO_AUTH_TOKEN", "")
    TWILIO_PHONE_NUMBER = os.getenv("TWILIO_PHONE_NUMBER", "")
    
    EXOTEL_ACCOUNT_SID = os.getenv("EXOTEL_ACCOUNT_SID", "")
    EXOTEL_API_KEY = os.getenv("EXOTEL_API_KEY", "")
    EXOTEL_API_TOKEN = os.getenv("EXOTEL_API_TOKEN", "")
    EXOTEL_EXOPHONE = os.getenv("EXOTEL_EXOPHONE", "")
    
    # ─── APPLICATION SETTINGS ───
    MAX_CONCURRENT_CALLS = int(os.getenv("MAX_CONCURRENT_CALLS", "100"))
    LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO")
    
    @classmethod
    def validate(cls):
        """
        Runs a startup diagnostic to catch missing ecosystem credentials 
        before the server starts accepting active voice loops.
        """
        missing_keys = []
        if not cls.GEMINI_API_KEY:
            missing_keys.append("GEMINI_API_KEY")
        if not cls.SARVAM_API_KEY:
            missing_keys.append("SARVAM_API_KEY")
            
        if missing_keys:
            print(f"⚠️ STARTUP WARNING: Missing keys in environment: {', '.join(missing_keys)}")
            print("Ensure these variables are fully defined inside your deployment platform dashboard or local .env file.")
        else:
            print("✅ Environmental Configuration Validated: AI Media Stream Drivers Initialized.")