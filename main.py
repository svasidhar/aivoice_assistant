# main.py
import os
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from fastapi.middleware.cors import CORSMiddleware

# Import mounted execution routes
from api.routes import router as main_router, exotel_router
from api.staff_routes import router as staff_router
from api.websocket_handler import ws_router
from config.settings import Config

# Execute runtime environmental validations
Config.validate()

app = FastAPI(
    title="TGSPDCL AI Voice Assistant",
    description="Asynchronous Media Stream Engine and Tool server for rural utility breakdown automations.",
    version="1.1.0"
)

# Enable Cross-Origin Resource Sharing (CORS) for your local web dashboard testing layout
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ─── ROUTER MOUNT COUPLING ───

# 1. Consumer Data Lookups & Core Handshakes
app.include_router(main_router)
app.include_router(exotel_router)

# 2. Milestone 1: Staff Voice Note Processing Node
app.include_router(staff_router)

# 3. Live Streaming Node: Asynchronous Full-Duplex Voice Engine
app.include_router(ws_router)

# ─── DASHBOARD PORTAL ASSETS MOUNTING ───
static_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")
try:
    app.mount("/static", StaticFiles(directory=static_dir), name="static")
    print("📁 Static directory mounted successfully under /static URL layout.")
except Exception:
    print("ℹ️ Note: /static folder asset reference could not be initialized. Clean directory if debugging local web dashboards.")

@app.get("/")
async def serve_index():
    # Return index.html from the static directory
    index_path = os.path.join(static_dir, "index.html")
    if os.path.exists(index_path):
        return FileResponse(index_path)
    return {"message": "TGSPDCL AI Voice Call Assistant API is active. Please add static/index.html to view the dashboard."}

@app.get("/health")
async def engine_health_check():
    """Provides instant node diagnostics for Render uptime status loops."""
    return {
        "engine_status": "operational",
        "websocket_pipeline": "active",
        "database_concurrency_mode": "WAL",
        "target_exophone": "09513886363"
    }