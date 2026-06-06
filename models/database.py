from sqlalchemy import create_engine, Column, Integer, String, DateTime, Text
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker
from datetime import datetime
import sqlite3
from typing import Optional, Dict, List

Base = declarative_base()

class DatabaseManager:
    def __init__(self, db_path: str = "outage_data.db"):
        self.db_path = db_path
        self.init_database()

    def get_connection(self):
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        return conn

    def init_database(self):
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()

        cursor.execute('''
            CREATE TABLE IF NOT EXISTS outages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                area TEXT NOT NULL,
                issue TEXT,
                eta TEXT,
                status TEXT,
                last_updated TIMESTAMP,
                staff_name TEXT,
                reason TEXT
            )
        ''')

        cursor.execute('''
            CREATE TABLE IF NOT EXISTS consumer_queries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                area TEXT NOT NULL,
                query TEXT,
                response TEXT,
                timestamp TIMESTAMP
            )
        ''')

        cursor.execute('''
            CREATE TABLE IF NOT EXISTS staff_updates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                staff_id TEXT,
                area TEXT NOT NULL,
                update_text TEXT,
                timestamp TIMESTAMP
            )
        ''')

        cursor.execute('''
            CREATE TABLE IF NOT EXISTS assistant_settings (
                key TEXT PRIMARY KEY,
                value TEXT
            )
        ''')

        cursor.execute('''
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                phone TEXT UNIQUE NOT NULL,
                substation TEXT,
                staff_id TEXT UNIQUE NOT NULL,
                password TEXT NOT NULL,
                cadre TEXT DEFAULT 'LM'
            )
        ''')

        cursor.execute('''
            CREATE TABLE IF NOT EXISTS call_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                caller_number TEXT NOT NULL,
                timestamp TIMESTAMP,
                transcript TEXT,
                audio_path TEXT,
                status TEXT
            )
        ''')

        cursor.execute('''
            CREATE TABLE IF NOT EXISTS outage_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                area TEXT NOT NULL,
                issue TEXT,
                eta TEXT,
                status TEXT,
                updated_by TEXT,
                timestamp TIMESTAMP
            )
        ''')

        cursor.execute('''
            CREATE TABLE IF NOT EXISTS caller_memory (
                phone TEXT PRIMARY KEY,
                last_area TEXT NOT NULL,
                last_called TIMESTAMP
            )
        ''')

        # Safely add cadre column if the table was created under the older schema
        try:
            cursor.execute("ALTER TABLE users ADD COLUMN cadre TEXT DEFAULT 'LM'")
        except sqlite3.OperationalError:
            pass # Column already exists

        # Safely add reason column to outages if table already existed
        try:
            cursor.execute("ALTER TABLE outages ADD COLUMN reason TEXT")
        except sqlite3.OperationalError:
            pass # Column already exists

        # Safely add duration column to call_logs if table already existed
        try:
            cursor.execute("ALTER TABLE call_logs ADD COLUMN duration INTEGER DEFAULT 0")
        except sqlite3.OperationalError:
            pass # Column already exists

        # No pre-seeded outages to start with a clean and empty Live Grid
        pass

        # Add seed data for consumer queries if empty
        cursor.execute("SELECT COUNT(*) FROM consumer_queries")
        if cursor.fetchone()[0] == 0:
            cursor.executemany('''
                INSERT INTO consumer_queries (area, query, response, timestamp)
                VALUES (?, ?, ?, ?)
            ''', [
                ('Cherlapally', 'Current eppudu vastundi?', 'Cherlapally area lo Line Breakdown undi sir. Staff work chestunnaru. Approximately 1 hour padutundi.', datetime.now().isoformat()),
                ('Ramanapet', 'Power cut enduku ayindi?', 'Ramanapet lo Transformer Problem undi sir. Staff work chestunnaru. Approximately 30 minutes padutundi.', datetime.now().isoformat())
            ])

        # Seed default assistant status if empty
        cursor.execute("SELECT COUNT(*) FROM assistant_settings WHERE key = 'ai_assistant_active'")
        if cursor.fetchone()[0] == 0:
            cursor.execute("INSERT INTO assistant_settings (key, value) VALUES ('ai_assistant_active', 'true')")
            cursor.execute("INSERT INTO assistant_settings (key, value) VALUES ('lineman_phone', '+91 9876543210')")

        # Seed operator availability status if empty
        cursor.execute("SELECT COUNT(*) FROM assistant_settings WHERE key = 'operator_available'")
        if cursor.fetchone()[0] == 0:
            cursor.execute("INSERT INTO assistant_settings (key, value) VALUES ('operator_available', 'true')")
        cursor.execute("SELECT COUNT(*) FROM assistant_settings WHERE key = 'backup_operator_phone'")
        if cursor.fetchone()[0] == 0:
            cursor.execute("INSERT INTO assistant_settings (key, value) VALUES ('backup_operator_phone', '+91 9876543211')")

        # Seed default user if empty
        cursor.execute("SELECT COUNT(*) FROM users")
        if cursor.fetchone()[0] == 0:
            cursor.execute('''
                INSERT INTO users (name, phone, substation, staff_id, password)
                VALUES (?, ?, ?, ?, ?)
            ''', ('Raju', '+91 9876543210', 'Ramanapet Substation', 'LM_Raju', 'password123'))

        conn.commit()
        conn.close()

    def update_outage_info(self, area: str, issue: str, eta: str, status: str, staff_name: Optional[str] = None, reason: Optional[str] = None):
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()

        cursor.execute("SELECT * FROM outages WHERE area = ?", (area,))
        existing = cursor.fetchone()

        if existing:
            query = "UPDATE outages SET issue = ?, eta = ?, status = ?, last_updated = ?"
            params = [issue, eta, status, datetime.now().isoformat()]
            if staff_name:
                query += ", staff_name = ?"
                params.append(staff_name)
            if reason is not None:
                query += ", reason = ?"
                params.append(reason)
            query += " WHERE area = ?"
            params.append(area)
            cursor.execute(query, tuple(params))
        else:
            cursor.execute('''
                INSERT INTO outages (area, issue, eta, status, last_updated, staff_name, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            ''', (area, issue, eta, status, datetime.now().isoformat(), staff_name, reason))

        # Log to outage history
        cursor.execute('''
            INSERT INTO outage_history (area, issue, eta, status, updated_by, timestamp)
            VALUES (?, ?, ?, ?, ?, ?)
        ''', (area, issue, eta, status, staff_name or "Unknown Staff", datetime.now().isoformat()))

        conn.commit()
        conn.close()

    def get_outage_info(self, area: str) -> Optional[Dict]:
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()

        cursor.execute("SELECT * FROM outages WHERE area = ?", (area,))
        result = cursor.fetchone()
        conn.close()

        if result:
            return {
                "area": result[1],
                "issue": result[2],
                "eta": result[3],
                "status": result[4],
                "last_updated": result[5],
                "staff_name": result[6],
                "reason": result[7] if len(result) > 7 else None
            }
        return None

    def get_all_outages(self) -> List[Dict]:
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()

        cursor.execute("SELECT * FROM outages")
        results = cursor.fetchall()
        conn.close()

        outages = []
        for result in results:
            outages.append({
                "area": result[1],
                "issue": result[2],
                "eta": result[3],
                "status": result[4],
                "last_updated": result[5],
                "staff_name": result[6],
                "reason": result[7] if len(result) > 7 else None
            })

        return outages

    def log_consumer_query(self, area: str, query: str, response: str):
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()

        cursor.execute('''
            INSERT INTO consumer_queries (area, query, response, timestamp)
            VALUES (?, ?, ?, ?)
        ''', (area, query, response, datetime.now().isoformat()))

        conn.commit()
        conn.close()

    def log_staff_update(self, staff_id: str, area: str, update_text: str):
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()

        cursor.execute('''
            INSERT INTO staff_updates (staff_id, area, update_text, timestamp)
            VALUES (?, ?, ?, ?)
        ''', (staff_id, area, update_text, datetime.now().isoformat()))

        conn.commit()
        conn.close()

    def get_assistant_state(self) -> Dict:
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        
        cursor.execute("SELECT value FROM assistant_settings WHERE key = 'ai_assistant_active'")
        active_res = cursor.fetchone()
        is_active = (active_res[0].lower() == 'true') if active_res else True
        
        cursor.execute("SELECT value FROM assistant_settings WHERE key = 'lineman_phone'")
        phone_res = cursor.fetchone()
        phone = phone_res[0] if phone_res else "+91 9876543210"

        cursor.execute("SELECT value FROM assistant_settings WHERE key = 'operator_available'")
        avail_res = cursor.fetchone()
        operator_available = (avail_res[0].lower() == 'true') if avail_res else True

        cursor.execute("SELECT value FROM assistant_settings WHERE key = 'backup_operator_phone'")
        backup_res = cursor.fetchone()
        backup_phone = backup_res[0] if backup_res else "+91 9876543211"
        
        conn.close()
        return {
            "is_active": is_active, 
            "lineman_phone": phone,
            "operator_available": operator_available,
            "backup_operator_phone": backup_phone
        }

    def set_assistant_state(self, is_active: bool) -> None:
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        
        val_str = 'true' if is_active else 'false'
        cursor.execute("UPDATE assistant_settings SET value = ? WHERE key = 'ai_assistant_active'", (val_str,))
        
        conn.commit()
        conn.close()

    def set_operator_availability(self, available: bool) -> None:
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        
        val_str = 'true' if available else 'false'
        cursor.execute("UPDATE assistant_settings SET value = ? WHERE key = 'operator_available'", (val_str,))
        
        conn.commit()
        conn.close()

    def set_backup_operator_phone(self, phone: str) -> None:
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        
        cursor.execute("UPDATE assistant_settings SET value = ? WHERE key = 'backup_operator_phone'", (phone,))
        
        conn.commit()
        conn.close()

    def get_caller_memory(self, phone: str) -> Optional[str]:
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        
        cursor.execute("SELECT last_area FROM caller_memory WHERE phone = ?", (phone,))
        res = cursor.fetchone()
        conn.close()
        return res[0] if res else None

    def update_caller_memory(self, phone: str, last_area: str):
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        
        cursor.execute("SELECT 1 FROM caller_memory WHERE phone = ?", (phone,))
        exists = cursor.fetchone()
        
        from datetime import datetime
        if exists:
            cursor.execute("UPDATE caller_memory SET last_area = ?, last_called = ? WHERE phone = ?", (last_area, datetime.now().isoformat(), phone))
        else:
            cursor.execute("INSERT INTO caller_memory (phone, last_area, last_called) VALUES (?, ?, ?)", (phone, last_area, datetime.now().isoformat()))
        
        conn.commit()
        conn.close()

    def get_recent_call_count(self, caller_number: str) -> int:
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        
        from datetime import datetime, timedelta
        one_hour_ago = (datetime.now() - timedelta(hours=1)).isoformat()
        
        cursor.execute('''
            SELECT COUNT(*) FROM call_logs 
            WHERE caller_number = ? AND timestamp > ?
        ''', (caller_number, one_hour_ago))
        
        count = cursor.fetchone()[0]
        conn.close()
        return count

    def create_user(self, name: str, phone: str, substation: str, staff_id: str, password: str, cadre: str = "LM") -> bool:
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        try:
            cursor.execute('''
                INSERT INTO users (name, phone, substation, staff_id, password, cadre)
                VALUES (?, ?, ?, ?, ?, ?)
            ''', (name, phone, substation, staff_id, password, cadre))
            conn.commit()
            return True
        except sqlite3.IntegrityError:
            return False
        finally:
            conn.close()

    def verify_user(self, staff_id: str, password: str) -> Optional[Dict]:
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        cursor.execute("SELECT name, phone, substation, staff_id, cadre FROM users WHERE staff_id = ? AND password = ?", (staff_id, password))
        res = cursor.fetchone()
        conn.close()
        if res:
            return {
                "name": res[0],
                "phone": res[1],
                "substation": res[2],
                "staff_id": res[3],
                "cadre": res[4]
            }
        return None

    def save_call_log(self, caller_number: str, transcript: str, audio_path: str, status: str, duration: int = 0) -> int:
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        
        cursor.execute('''
            INSERT INTO call_logs (caller_number, timestamp, transcript, audio_path, status, duration)
            VALUES (?, ?, ?, ?, ?, ?)
        ''', (caller_number, datetime.now().isoformat(), transcript, audio_path, status, duration))
        
        inserted_id = cursor.lastrowid
        conn.commit()
        conn.close()
        return inserted_id

    def get_all_call_logs(self) -> List[Dict]:
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        
        cursor.execute("SELECT id, caller_number, timestamp, transcript, audio_path, status, duration FROM call_logs ORDER BY id DESC")
        results = cursor.fetchall()
        conn.close()
        
        logs = []
        for res in results:
            logs.append({
                "id": res[0],
                "caller_number": res[1],
                "timestamp": res[2],
                "transcript": res[3],
                "audio_path": res[4],
                "status": res[5],
                "duration": res[6]
            })
        return logs

class OutageInfo(Base):
    __tablename__ = 'outage_info'
    id = Column(Integer, primary_key=True)
    area = Column(String(100))
    issue = Column(String(200))
    eta = Column(String(100))
    status = Column(String(50))
    last_updated = Column(DateTime)
    staff_name = Column(String(100))

    def __init__(self, area, issue, eta, status, last_updated=None, staff_name=None):
        self.area = area
        self.issue = issue
        self.eta = eta
        self.status = status
        self.last_updated = last_updated or datetime.now()
        self.staff_name = staff_name

class ConsumerQuery(Base):
    __tablename__ = 'consumer_query'
    id = Column(Integer, primary_key=True)
    area = Column(String(100))
    query = Column(Text)
    response = Column(Text)
    timestamp = Column(DateTime)

    def __init__(self, area, query, response, timestamp=None):
        self.area = area
        self.query = query
        self.response = response
        self.timestamp = timestamp or datetime.now()

class StaffUpdate(Base):
    __tablename__ = 'staff_update'
    id = Column(Integer, primary_key=True)
    staff_id = Column(String(50))
    update_text = Column(Text)
    timestamp = Column(DateTime)

    def __init__(self, staff_id, update_text, timestamp=None):
        self.staff_id = staff_id
        self.update_text = update_text
        self.timestamp = timestamp or datetime.now()