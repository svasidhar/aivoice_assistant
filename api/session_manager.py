"""
Session-based conversation state management for backend dialog tracking.
Moves conversation state from Android client to backend.
"""
import uuid
from datetime import datetime, timedelta
from typing import Dict, Optional, Any
from dataclasses import dataclass, field
import structlog

logger = structlog.get_logger()


@dataclass
class ConversationState:
    """Tracks conversation progress for a single call session."""
    session_id: str
    caller_number: str
    step: int = 0
    detected_area: str = ""
    context: Dict[str, Any] = field(default_factory=dict)
    created_at: datetime = field(default_factory=datetime.now)
    last_activity: datetime = field(default_factory=datetime.now)
    forwarded: bool = False

    def advance_step(self):
        """Move to next conversation step."""
        self.step += 1
        self.last_activity = datetime.now()

    def reset(self):
        """Reset conversation to initial state."""
        self.step = 0
        self.detected_area = ""
        self.context = {}
        self.last_activity = datetime.now()
        self.forwarded = False


class SessionManager:
    """
    In-memory session store for active call conversations.
    Sessions expire after 30 minutes of inactivity.
    """

    def __init__(self, session_timeout_minutes: int = 30):
        self.sessions: Dict[str, ConversationState] = {}
        self.session_timeout = timedelta(minutes=session_timeout_minutes)
        logger.info("SessionManager initialized", timeout_minutes=session_timeout_minutes)

    def create_session(self, caller_number: str) -> ConversationState:
        """Create a new session for an incoming call."""
        session_id = str(uuid.uuid4())[:8]
        state = ConversationState(
            session_id=session_id,
            caller_number=caller_number
        )
        self.sessions[session_id] = state
        logger.info("New call session created", session_id=session_id, caller=caller_number)
        return state

    def get_session(self, session_id: str) -> Optional[ConversationState]:
        """Get existing session by ID."""
        return self.sessions.get(session_id)

    def get_or_create_session(self, caller_number: str) -> ConversationState:
        """Get existing session for caller or create new one."""
        # Find existing active session for this caller
        for session in self.sessions.values():
            if session.caller_number == caller_number and not session.forwarded:
                # Check if session is still valid (not expired)
                if datetime.now() - session.last_activity < self.session_timeout:
                    return session
                else:
                    # Expired session - clean it up
                    logger.info("Expired session cleaned up", session_id=session.session_id)
                    self.sessions.pop(session.session_id, None)

        # Create new session
        return self.create_session(caller_number)

    def end_session(self, session_id: str) -> bool:
        """End a call session (hang up)."""
        if session_id in self.sessions:
            session = self.sessions.pop(session_id)
            logger.info("Call session ended", session_id=session_id, duration=str(datetime.now() - session.created_at))
            return True
        return False

    def cleanup_expired(self) -> int:
        """Remove expired sessions. Returns count of cleaned sessions."""
        now = datetime.now()
        expired = [
            sid for sid, s in self.sessions.items()
            if now - s.last_activity > self.session_timeout
        ]
        for sid in expired:
            self.sessions.pop(sid, None)
        if expired:
            logger.info("Cleaned up expired sessions", count=len(expired))
        return len(expired)

    def get_active_count(self) -> int:
        """Get count of active sessions."""
        return len(self.sessions)


# Global singleton instance
session_manager = SessionManager()