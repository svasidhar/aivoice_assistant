import os
from dotenv import load_dotenv

load_dotenv()

class Config:
    # API Keys
    OPENAI_API_KEY = os.getenv('OPENAI_API_KEY', '')
    ELEVENLABS_API_KEY = os.getenv('ELEVENLABS_API_KEY', '')
    GEMINI_API_KEY = os.getenv('GEMINI_API_KEY', '')

    # Database Configuration
    DATABASE_URL = os.getenv('DATABASE_URL', 'sqlite:///./test.db')

    # Voice Configuration
    VOICE_PROVIDER = os.getenv('VOICE_PROVIDER', 'elevenlabs')  # or 'google'

    # Twilio Configuration
    TWILIO_ACCOUNT_SID = os.getenv('TWILIO_ACCOUNT_SID', '')
    TWILIO_AUTH_TOKEN = os.getenv('TWILIO_AUTH_TOKEN', '')
    TWILIO_PHONE_NUMBER = os.getenv('TWILIO_PHONE_NUMBER', '')

    # Application Settings
    MAX_CONCURRENT_CALLS = int(os.getenv('MAX_CONCURRENT_CALLS', '100'))
    LOG_LEVEL = os.getenv('LOG_LEVEL', 'INFO')

    # Exotel Configuration
    EXOTEL_API_KEY = os.getenv('EXOTEL_API_KEY', '')
    EXOTEL_API_TOKEN = os.getenv('EXOTEL_API_TOKEN', '')
    EXOTEL_ACCOUNT_SID = os.getenv('EXOTEL_ACCOUNT_SID', '')
    EXOTEL_EXOPHONE = os.getenv('EXOTEL_EXOPHONE', '')