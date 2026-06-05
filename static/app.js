// State Variables
let mediaRecorder = null;
let audioChunks = [];
let audioContext = null;
let analyser = null;
let dataArray = null;
let animationFrameId = null;
let isRecording = false;
let recordStartTime = 0;
let timerInterval = null;

let activeDialerQuery = "";
let activeDialerArea = "Cherlapally";
let callTimerInterval = null;
let callDuration = 0;
let currentTeluguSpeech = null;

let recognition = null;
let speechTranscribedText = "";

// Initial Setup when DOM loads
document.addEventListener("DOMContentLoaded", () => {
    // Check if lineman is logged in
    const isLoggedIn = localStorage.getItem("tgspdcl_logged_in") === "true";
    if (isLoggedIn) {
        showLinemanWorkspace();
    } else {
        logoutLineman();
    }

    // Load assistant toggle state from SQLite
    loadAssistantState();

    // Load operator settings from SQLite
    loadOperatorState();

    // Load initial outages and start polling
    fetchOutages();
    setInterval(fetchOutages, 5000);

    // Register event listeners
    setupEventListeners();
    setupCanvasPlaceholder();

    // Start dynamic clock widget
    updateClock();
    setInterval(updateClock, 1000);
});

// Update smartphone digital clock
function updateClock() {
    const clockEl = document.getElementById("mobile-clock");
    if (clockEl) {
        const now = new Date();
        const hrs = String(now.getHours()).padStart(2, "0");
        const mins = String(now.getMinutes()).padStart(2, "0");
        clockEl.innerText = `${hrs}:${mins}`;
    }
}

// Event Listeners Registration
function setupEventListeners() {
    // Lineman mic record button
    const recordBtn = document.getElementById("record-btn");
    if (recordBtn) {
        recordBtn.addEventListener("click", toggleRecording);
    }

    // Confirm voice update button
    const confirmBtn = document.getElementById("confirm-update-btn");
    if (confirmBtn) {
        confirmBtn.addEventListener("click", syncLinemanUpdateToDb);
    }

    // Call Simulator dial button
    const dialBtn = document.getElementById("dial-btn");
    if (dialBtn) {
        dialBtn.addEventListener("click", startConsumerCall);
    }

    // End call button
    const endCallBtn = document.getElementById("end-call-btn");
    if (endCallBtn) {
        endCallBtn.addEventListener("click", endConsumerCall);
    }

    // AI Permission Switch change listener
    const switchEl = document.getElementById("ai-permission-switch");
    if (switchEl) {
        switchEl.addEventListener("change", handleAssistantSwitchToggle);
    }

    // Operator Available Switch change listener
    const opSwitchEl = document.getElementById("operator-available-switch");
    if (opSwitchEl) {
        opSwitchEl.addEventListener("change", handleOperatorSwitchToggle);
    }

    // Extract manual details button listener
    const extractManualBtn = document.getElementById("extract-manual-btn");
    if (extractManualBtn) {
        extractManualBtn.addEventListener("click", extractManualOutageDetails);
    }

    // Login Submit button
    const loginSubmitBtn = document.getElementById("login-submit-btn");
    if (loginSubmitBtn) {
        loginSubmitBtn.addEventListener("click", loginLineman);
    }

    // Signup Submit button
    const signupSubmitBtn = document.getElementById("signup-submit-btn");
    if (signupSubmitBtn) {
        signupSubmitBtn.addEventListener("click", signupLineman);
    }
}

// ==========================================================================
/* AI Answering Permission Switch API Handlers */
// ==========================================================================
async function loadAssistantState() {
    try {
        const res = await fetch("/api/v1/assistant-state/");
        if (res.ok) {
            const data = await res.json();
            updateAssistantSwitchUI(data.is_active, data.lineman_phone);
        }
    } catch (err) {
        console.warn("Failed to load assistant settings:", err);
    }
}

async function handleAssistantSwitchToggle(e) {
    const isChecked = e.target.checked;
    try {
        const res = await fetch("/api/v1/assistant-state/toggle/", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ is_active: isChecked })
        });
        if (res.ok) {
            const data = await res.json();
            updateAssistantSwitchUI(data.is_active);
        }
    } catch (err) {
        console.error("Failed to toggle assistant settings:", err);
        e.target.checked = !isChecked; // Revert checkbox on error
    }
}

function updateAssistantSwitchUI(isActive, phone = "") {
    const switchEl = document.getElementById("ai-permission-switch");
    const dot = document.getElementById("switch-state-dot");
    const stateText = document.getElementById("toggle-state-text");
    const descText = document.getElementById("toggle-desc-text");
    const alertBox = document.getElementById("call-forwarding-alert");
    const phoneEl = document.getElementById("lineman-number");
    const reporterWrapper = document.getElementById("ai-reporter-wrapper");
    const disabledMessage = document.getElementById("ai-disabled-message");
    const aiStatusPill = document.getElementById("ai-status-pill");

    if (switchEl) {
        switchEl.checked = isActive;
    }

    if (isActive) {
        if (dot) {
            dot.classList.add("online");
        }
        if (stateText) stateText.innerText = "Active (Auto-Reply)";
        if (descText) descText.innerText = "AI answers incoming electricity queries in Telugu.";
        if (alertBox) alertBox.style.display = "none";
        if (reporterWrapper) reporterWrapper.style.display = "flex";
        if (disabledMessage) disabledMessage.style.display = "none";
        if (aiStatusPill) {
            aiStatusPill.innerText = "AI Active";
            aiStatusPill.style.background = "rgba(0, 230, 118, 0.1)";
            aiStatusPill.style.color = "var(--color-green)";
            aiStatusPill.style.borderColor = "rgba(0, 230, 118, 0.2)";
        }
    } else {
        if (dot) {
            dot.classList.remove("online");
        }
        if (stateText) stateText.innerText = "Suspended (Bypass)";
        if (descText) descText.innerText = "Direct calls will bypass AI and route to lineman.";
        if (alertBox) alertBox.style.display = "flex";
        if (phone && phoneEl) {
            phoneEl.innerText = phone;
        }
        if (reporterWrapper) reporterWrapper.style.display = "none";
        if (disabledMessage) disabledMessage.style.display = "block";
        if (aiStatusPill) {
            aiStatusPill.innerText = "AI Suspended";
            aiStatusPill.style.background = "rgba(255, 62, 85, 0.1)";
            aiStatusPill.style.color = "var(--color-red)";
            aiStatusPill.style.borderColor = "rgba(255, 62, 85, 0.2)";
        }
    }
}

// ==========================================================================
/* Operator Availability Toggle Handlers */
// ==========================================================================
async function loadOperatorState() {
    try {
        const res = await fetch("/api/v1/operator-state/");
        if (res.ok) {
            const data = await res.json();
            updateOperatorSwitchUI(data.available);
        }
    } catch (err) {
        console.warn("Failed to load operator state:", err);
    }
}

async function handleOperatorSwitchToggle(e) {
    const isChecked = e.target.checked;
    try {
        const res = await fetch("/api/v1/operator-state/toggle/", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ available: isChecked })
        });
        if (res.ok) {
            const data = await res.json();
            updateOperatorSwitchUI(data.available);
        }
    } catch (err) {
        console.error("Failed to toggle operator availability:", err);
        e.target.checked = !isChecked; // Revert checkbox on error
    }
}

function updateOperatorSwitchUI(available) {
    const switchEl = document.getElementById("operator-available-switch");
    const stateText = document.getElementById("operator-state-text");
    const descText = document.getElementById("operator-desc-text");

    if (switchEl) {
        switchEl.checked = available;
    }

    if (available) {
        if (stateText) stateText.innerText = "Operator Available";
        if (descText) descText.innerText = "If busy, calls fail over to backup lineman.";
    } else {
        if (stateText) stateText.innerText = "Operator Offline";
        if (descText) descText.innerText = "Calls route directly to backup operator.";
    }
}

// ==========================================================================
/* Supervisor Dashboard Analytics */
// ==========================================================================
async function fetchAnalytics() {
    try {
        const res = await fetch("/api/v1/analytics/");
        if (res.ok) {
            const data = await res.json();
            const totalCallsEl = document.getElementById("analytics-total-calls");
            const aiResolvedEl = document.getElementById("analytics-ai-resolved");
            const forwardedEl = document.getElementById("analytics-forwarded");
            const emergencyEl = document.getElementById("analytics-emergency");
            const avgDurationEl = document.getElementById("analytics-avg-duration");
            
            if (totalCallsEl) totalCallsEl.innerText = data.total_calls;
            if (aiResolvedEl) aiResolvedEl.innerText = data.ai_resolved;
            if (forwardedEl) forwardedEl.innerText = data.forwarded;
            if (emergencyEl) emergencyEl.innerText = data.emergency_calls;
            if (avgDurationEl) avgDurationEl.innerText = `${data.avg_duration} sec`;
        }
    } catch (err) {
        console.warn("Failed to load analytics data:", err);
    }
}

// ==========================================================================
/* Mobile Tab Switchers */
// ==========================================================================
function switchNavTab(tabName, element) {
    // Remove active class from all nav items
    document.querySelectorAll(".nav-item").forEach(el => el.classList.remove("active"));
    // Add active class to clicked element
    if (element) {
        element.classList.add("active");
    }

    // Hide all tab views
    document.querySelectorAll(".mobile-tab-view").forEach(el => el.classList.remove("active"));
    // Show target tab view
    const targetTab = document.getElementById(`tab-${tabName}-content`);
    if (targetTab) {
        targetTab.classList.add("active");
    }

    if (tabName === "profile") {
        loadLinemanProfile();
    }
}

function loadLinemanProfile() {
    const name = localStorage.getItem("tgspdcl_staff_name") || "LM Raju";
    const cadre = localStorage.getItem("tgspdcl_staff_cadre") || "LM";
    const employeeId = localStorage.getItem("tgspdcl_employee_id") || "LM_Raju";
    const phone = localStorage.getItem("tgspdcl_staff_phone") || "+91 9876543210";
    const substation = localStorage.getItem("tgspdcl_staff_substation") || "Ramanapet Substation";

    const nameEl = document.getElementById("profile-name");
    const cadreLabelEl = document.getElementById("profile-cadre-label");
    const idEl = document.getElementById("profile-id");
    const phoneEl = document.getElementById("profile-phone");
    const substationEl = document.getElementById("profile-substation");

    if (nameEl) nameEl.innerText = name;
    if (cadreLabelEl) {
        let cadreLabel = "Line Man (LM)";
        if (cadre === "ALM") cadreLabel = "Assistant Line Man (ALM)";
        else if (cadre === "JLM") cadreLabel = "Junior Line Man (JLM)";
        cadreLabelEl.innerText = cadreLabel;
    }
    if (idEl) idEl.innerText = employeeId;
    if (phoneEl) phoneEl.innerText = phone;
    if (substationEl) substationEl.innerText = substation;
}

function switchVoiceTab(tabName) {
    const micTabBtn = document.getElementById("btn-mic-tab");
    const presetTabBtn = document.getElementById("btn-preset-tab");
    const typeTabBtn = document.getElementById("btn-type-tab");
    const micView = document.getElementById("m-mic-view");
    const presetView = document.getElementById("m-preset-view");
    const typeView = document.getElementById("m-type-view");

    // Remove active classes
    if (micTabBtn) micTabBtn.classList.remove("active");
    if (presetTabBtn) presetTabBtn.classList.remove("active");
    if (typeTabBtn) typeTabBtn.classList.remove("active");
    if (micView) micView.classList.remove("active");
    if (presetView) presetView.classList.remove("active");
    if (typeView) typeView.classList.remove("active");

    // Add active class to target
    if (tabName === "mic") {
        if (micTabBtn) micTabBtn.classList.add("active");
        if (micView) micView.classList.add("active");
    } else if (tabName === "preset") {
        if (presetTabBtn) presetTabBtn.classList.add("active");
        if (presetView) presetView.classList.add("active");
    } else if (tabName === "type") {
        if (typeTabBtn) typeTabBtn.classList.add("active");
        if (typeView) typeView.classList.add("active");
    }
}

// Draw static placeholder wave
function setupCanvasPlaceholder() {
    const canvas = document.getElementById("audio-wave-canvas");
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.strokeStyle = "rgba(255, 179, 0, 0.2)";
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(0, canvas.height / 2);
    
    for (let i = 0; i < canvas.width; i++) {
        const y = canvas.height / 2 + Math.sin(i * 0.05) * 5;
        ctx.lineTo(i, y);
    }
    ctx.stroke();
}

// ==========================================================================
/* Lineman Recording Functionality */
// ==========================================================================
async function toggleRecording() {
    if (isRecording) {
        stopRecording();
    } else {
        await startRecording();
    }
}

async function startRecording() {
    audioChunks = [];
    speechTranscribedText = "";
    
    // Start Browser Speech Recognition (zero API keys, clean client-side Telugu!)
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (SpeechRecognition) {
        try {
            recognition = new SpeechRecognition();
            recognition.lang = 'te-IN'; // Set language to Telugu
            recognition.interimResults = false;
            recognition.maxAlternatives = 1;

            recognition.onresult = (event) => {
                const resultText = event.results[0][0].transcript;
                console.log("Browser Speech recognition output:", resultText);
                speechTranscribedText = resultText;
            };

            recognition.onerror = (err) => {
                console.warn("Speech recognition error:", err.error);
            };

            recognition.start();
        } catch (e) {
            console.warn("Could not start Speech Recognition:", e);
        }
    }

    try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        mediaRecorder = new MediaRecorder(stream);
        
        mediaRecorder.ondataavailable = (event) => {
            if (event.data.size > 0) {
                audioChunks.push(event.data);
            }
        };

        mediaRecorder.onstop = uploadRecording;

        // Start HTML5 Web Audio Analyser for drawing wave
        audioContext = new (window.AudioContext || window.webkitAudioContext)();
        const source = audioContext.createMediaStreamSource(stream);
        analyser = audioContext.createAnalyser();
        analyser.fftSize = 256;
        source.connect(analyser);
        
        const bufferLength = analyser.frequencyBinCount;
        dataArray = new Uint8Array(bufferLength);

        // UI Updates
        isRecording = true;
        const recordBtn = document.getElementById("record-btn");
        if (recordBtn) recordBtn.classList.add("recording");
        
        document.getElementById("recording-state-label").innerText = "Recording Live Audio...";
        
        recordStartTime = Date.now();
        timerInterval = setInterval(updateRecordTimer, 1000);
        
        mediaRecorder.start();
        drawVoiceWave();
    } catch (err) {
        console.error("Mic access denied or error:", err);
        alert("Microphone access is required to record voice notes. Please try the 'Demo Presets' tab if you are using a simulator or if microphone permission is blocked!");
    }
}

function stopRecording() {
    if (recognition) {
        try {
            recognition.stop();
        } catch (e) {}
    }

    if (!mediaRecorder || mediaRecorder.state === "inactive") return;
    
    mediaRecorder.stop();
    mediaRecorder.stream.getTracks().forEach(track => track.stop());
    
    isRecording = false;
    const recordBtn = document.getElementById("record-btn");
    if (recordBtn) recordBtn.classList.remove("recording");
    
    document.getElementById("recording-state-label").innerText = "Processing Voice Note...";
    
    clearInterval(timerInterval);
    document.getElementById("recording-timer").innerText = "00:00";
    
    if (animationFrameId) {
        cancelAnimationFrame(animationFrameId);
    }
    
    if (audioContext) {
        audioContext.close();
    }
}

function updateRecordTimer() {
    const elapsed = Math.floor((Date.now() - recordStartTime) / 1000);
    const m = String(Math.floor(elapsed / 60)).padStart(2, "0");
    const s = String(elapsed % 60).padStart(2, "0");
    document.getElementById("recording-timer").innerText = `${m}:${s}`;
}

// Canvas Visualizer Loop
function drawVoiceWave() {
    const canvas = document.getElementById("audio-wave-canvas");
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    
    animationFrameId = requestAnimationFrame(drawVoiceWave);
    analyser.getByteFrequencyData(dataArray);
    
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.strokeStyle = "rgb(255, 179, 0)";
    ctx.lineWidth = 3;
    ctx.beginPath();
    
    const sliceWidth = canvas.width / dataArray.length;
    let x = 0;
    
    for (let i = 0; i < dataArray.length; i++) {
        const v = dataArray[i] / 128.0;
        const y = (v * canvas.height) / 2;
        
        if (i === 0) {
            ctx.moveTo(x, y);
        } else {
            ctx.lineTo(x, y);
        }
        x += sliceWidth;
    }
    
    ctx.lineTo(canvas.width, canvas.height / 2);
    ctx.stroke();
}

// Process voice update using native speech transcription
async function uploadRecording() {
    // Increased wait time to 600ms to guarantee browser Speech recognition completes
    setTimeout(async () => {
        if (speechTranscribedText && speechTranscribedText.trim() !== "") {
            console.log("Utilizing real-time Telugu transcription:", speechTranscribedText);
            document.getElementById("recording-state-label").innerText = "AI Parsing Bilingual Entities...";

            const payload = {
                staff_id: localStorage.getItem("tgspdcl_employee_id") || "LM_Raju",
                update_text: speechTranscribedText
            };

            try {
                const res = await fetch("/api/v1/staff-voice-update/", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(payload)
                });

                if (res.ok) {
                    const data = await res.json();
                    showExtractedCard(speechTranscribedText, data.processed_info);
                } else {
                    console.warn("Bilingual text update route failed.");
                    showVoiceErrorDialog();
                }
            } catch (err) {
                console.error("Text sync error:", err);
                showVoiceErrorDialog();
            }
        } else {
            console.warn("No client-side speech recognized.");
            showVoiceErrorDialog();
        }

        document.getElementById("recording-state-label").innerText = "Ready to record";
        setupCanvasPlaceholder();
    }, 600);
}

// Fallback to mock voice-note file uploader if Web Speech is empty
async function uploadAudioFileFallback(audioBlob) {
    const formData = new FormData();
    formData.append("file", audioBlob, "lineman_cherlapally_update.wav");
    
    try {
        const res = await fetch("/api/v1/voice-note/", {
            method: "POST",
            body: formData
        });
        
        if (res.ok) {
            const data = await res.json();
            showExtractedCard(data.transcribed_text, data.processed_info);
        } else {
            alert("Error in mock voice fallback: " + res.statusText);
        }
    } catch (err) {
        console.error("Upload error:", err);
    }
}

// Lineman Preset voice updater
async function simulatePresetVoice(presetKey) {
    document.getElementById("recording-state-label").innerText = "Processing preset...";
    
    const formData = new FormData();
    const mockBlob = new Blob(["mock"], { type: "audio/wav" });
    formData.append("file", mockBlob, `lineman_${presetKey}_update.wav`);

    try {
        const res = await fetch("/api/v1/voice-note/", {
            method: "POST",
            body: formData
        });
        
        if (res.ok) {
            const data = await res.json();
            showExtractedCard(data.transcribed_text, data.processed_info);
        } else {
            alert("Error parsing preset update: " + res.statusText);
        }
    } catch (err) {
        console.error("Preset error:", err);
    }
    
    document.getElementById("recording-state-label").innerText = "Ready to record";
}

// Show Structured Parsing result card
function showExtractedCard(transcribedText, processedInfo) {
    const parsedArea = processedInfo.entities.area;
    
    // Check if the voice note was not parsed/detected properly.
    // If area is missing, invalid, or unrecognized, trigger the retry/keyboard recovery dialog box!
    if (!parsedArea || parsedArea.toLowerCase() === "unknown" || parsedArea.trim() === "") {
        console.warn("Details not detected in voice transcript.");
        showVoiceErrorDialog();
        return;
    }

    const card = document.getElementById("extraction-card");
    if (!card) return;
    card.style.display = "flex";
    
    document.getElementById("transcription-text").innerText = transcribedText;
    
    // Dynamically append unrecognized spoken areas to the dropdown options so they render correctly
    const areaSelect = document.getElementById("entity-area");
    // parsedArea is already defined and validated above
    
    if (areaSelect) {
        let areaExists = false;
        for (let i = 0; i < areaSelect.options.length; i++) {
            if (areaSelect.options[i].value.toLowerCase() === parsedArea.toLowerCase()) {
                areaExists = true;
                areaSelect.value = areaSelect.options[i].value;
                break;
            }
        }
        
        if (!areaExists && parsedArea) {
            const opt = document.createElement("option");
            opt.value = parsedArea;
            opt.innerText = parsedArea;
            areaSelect.appendChild(opt);
            areaSelect.value = parsedArea;
        }
    }
    
    const issueSelect = document.getElementById("entity-issue");
    if (issueSelect) {
        issueSelect.value = processedInfo.entities.issue || "Power Outage";
    }

    const etaInput = document.getElementById("entity-eta");
    if (etaInput) {
        etaInput.value = processedInfo.entities.eta || "Not Specified";
    }

    const statusSelect = document.getElementById("entity-status");
    if (statusSelect) {
        statusSelect.value = "In Progress";
    }

    // If emergency detected in lineman update, flash immediate alarm!
    if (processedInfo.is_emergency) {
        triggerEmergencyEscalation(parsedArea, "Transformer smoke / Spark hazard");
    }
}

// Sync the voice update details to DB
async function syncLinemanUpdateToDb() {
    const area = document.getElementById("entity-area").value;
    const issue = document.getElementById("entity-issue").value;
    const eta = document.getElementById("entity-eta").value;
    const status = document.getElementById("entity-status").value;
    
    const payload = {
        area: area,
        issue: issue,
        eta: eta,
        status: status,
        staff_name: localStorage.getItem("tgspdcl_staff_name") || "LM Raju"
    };

    try {
        const res = await fetch("/api/v1/voice-update/", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });
        
        if (res.ok) {
            document.getElementById("extraction-card").style.display = "none";
            fetchOutages(); // Refresh outages grid
            
            // Play success audio ding
            const ding = new Audio("https://assets.mixkit.co/active_storage/sfx/2019/2019-84.wav");
            ding.volume = 0.5;
            ding.play().catch(e => {});
        } else {
            alert("Failed to sync update: " + res.statusText);
        }
    } catch (err) {
        console.error("Sync error:", err);
    }
}

// ==========================================================================
/* Consumer Call Simulator (Equal AI Console) */
// ==========================================================================
let consumerRecognition = null;
let isConsumerListening = false;

function toggleConsumerSpeech() {
    const micBtn = document.getElementById("consumer-mic-btn");
    const inputField = document.getElementById("dialer-input");
    if (!micBtn || !inputField) return;

    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRecognition) {
        alert("Web Speech API is not supported in this browser. Please use presets or type manual query.");
        return;
    }

    if (isConsumerListening) {
        if (consumerRecognition) {
            consumerRecognition.stop();
        }
        isConsumerListening = false;
        micBtn.style.background = "rgba(255, 255, 255, 0.06)";
        micBtn.style.color = "#fff";
        return;
    }

    try {
        consumerRecognition = new SpeechRecognition();
        consumerRecognition.lang = 'te-IN';
        consumerRecognition.interimResults = false;
        consumerRecognition.maxAlternatives = 1;

        consumerRecognition.onstart = () => {
            isConsumerListening = true;
            micBtn.style.background = "rgba(255, 62, 85, 0.25)";
            micBtn.style.color = "var(--color-red)";
            inputField.placeholder = "Listening Telugu speech...";
            inputField.value = "";
        };

        consumerRecognition.onresult = (event) => {
            const transcript = event.results[0][0].transcript;
            inputField.value = transcript;
            activeDialerQuery = transcript;
        };

        consumerRecognition.onend = () => {
            isConsumerListening = false;
            micBtn.style.background = "rgba(255, 255, 255, 0.06)";
            micBtn.style.color = "#fff";
            inputField.placeholder = "Type query in Telugu or select chip...";
        };

        consumerRecognition.onerror = (err) => {
            console.error("Consumer recognition error:", err);
            isConsumerListening = false;
            micBtn.style.background = "rgba(255, 255, 255, 0.06)";
            micBtn.style.color = "#fff";
        };

        consumerRecognition.start();
    } catch (e) {
        console.error("Could not start consumer recognition:", e);
    }
}

function selectQuery(queryText, areaName) {
    const dialerInput = document.getElementById("dialer-input");
    if (dialerInput) {
        dialerInput.value = queryText;
    }
    activeDialerQuery = queryText;
    activeDialerArea = areaName;
}

function startConsumerCall() {
    const dialerInput = document.getElementById("dialer-input");
    const inputVal = dialerInput ? dialerInput.value : "";
    if (!inputVal) {
        alert("Please type or select a query in Telugu first!");
        return;
    }

    activeDialerQuery = inputVal;
    
    // Intelligent area extraction from manually typed query!
    const lowerQuery = inputVal.toLowerCase();
    if (lowerQuery.includes("ramanapet")) {
        activeDialerArea = "Ramanapet";
    } else if (lowerQuery.includes("siddipet")) {
        activeDialerArea = "Siddipet";
    } else if (lowerQuery.includes("warangal")) {
        activeDialerArea = "Warangal";
    } else if (lowerQuery.includes("narketpally")) {
        activeDialerArea = "Narketpally";
    } else if (lowerQuery.includes("cherlapally")) {
        activeDialerArea = "Cherlapally";
    } else {
        if (!activeDialerArea) {
            activeDialerArea = "Cherlapally";
        }
    }

    // Toggle Panels
    document.getElementById("phone-dialer-panel").style.display = "none";
    const callPanel = document.getElementById("phone-call-panel");
    if (callPanel) {
        callPanel.classList.remove("call-inactive");
    }
    
    document.getElementById("call-status").innerText = "Dialing Assistant...";
    document.getElementById("call-status").style.color = "var(--color-electric)";
    document.getElementById("call-timer").innerText = "00:00";
    
    const endCallBtn = document.getElementById("end-call-btn");
    if (endCallBtn) endCallBtn.style.display = "block";
    
    // Play dial back ringtone
    const ringer = document.getElementById("ringback-audio");
    if (ringer) {
        ringer.volume = 0.3;
        ringer.play().catch(e => console.log("Audio play blocked by browser:", e));
    }

    callDuration = 0;
    
    // Simulate Connect after 1.8 seconds
    setTimeout(connectConsumerCall, 1800);
}

async function connectConsumerCall() {
    const ringer = document.getElementById("ringback-audio");
    if (ringer) ringer.pause();

    document.getElementById("call-status").innerText = "CONNECTED";
    
    // Start Call Timer
    callTimerInterval = setInterval(() => {
        callDuration++;
        const m = String(Math.floor(callDuration / 60)).padStart(2, "0");
        const s = String(callDuration % 60).padStart(2, "0");
        document.getElementById("call-timer").innerText = `${m}:${s}`;
    }, 1000);

    const chatContainer = document.getElementById("transcription-chat");
    if (chatContainer) chatContainer.innerHTML = ""; // Clear old chats

    // Add User Query Bubble
    appendChatBubble("user", activeDialerQuery);

    const intentBadge = document.getElementById("call-intent-badge");
    if (intentBadge) {
        intentBadge.style.display = "none";
    }

    try {
        const payload = {
            area: activeDialerArea,
            query: activeDialerQuery
        };
        
        const res = await fetch("/api/v1/consumer-query/", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });
        
        if (res.ok) {
            const data = await res.json();
            
            setTimeout(() => {
                // Set intent badge
                if (intentBadge) {
                    let intentText = "Query";
                    let badgeBg = "rgba(255, 255, 255, 0.08)";
                    let badgeColor = "var(--text-secondary)";
                    
                    const lowerQuery = activeDialerQuery.toLowerCase();
                    if (lowerQuery.includes("spark") || lowerQuery.includes("smoke") || lowerQuery.includes("పొగ") || lowerQuery.includes("వైర్")) {
                        intentText = "🚨 Danger";
                        badgeBg = "rgba(255, 62, 85, 0.15)";
                        badgeColor = "var(--color-red)";
                    } else if (data.outage_info && (data.outage_info.status.toLowerCase() === "solved" || data.outage_info.status.toLowerCase() === "restored")) {
                        intentText = "✅ Resolved";
                        badgeBg = "rgba(0, 230, 118, 0.15)";
                        badgeColor = "var(--color-green)";
                    } else if (lowerQuery.includes("eppudu") || lowerQuery.includes("time") || lowerQuery.includes("hour") || lowerQuery.includes("ganta") || lowerQuery.includes("minutes") || lowerQuery.includes("when")) {
                        intentText = "⏳ ETA Query";
                        badgeBg = "rgba(0, 198, 255, 0.15)";
                        badgeColor = "var(--color-electric)";
                    }
                    
                    intentBadge.innerText = intentText;
                    intentBadge.style.display = "inline-block";
                    intentBadge.style.background = badgeBg;
                    intentBadge.style.color = badgeColor;
                    intentBadge.style.borderColor = badgeColor;
                }

                // If call is forwarded because assistant state is OFF
                if (data.forwarded) {
                    const avatar = document.getElementById("call-avatar");
                    if (avatar) {
                        avatar.classList.add("forwarding");
                        const inner = avatar.querySelector(".speaker-avatar-inner");
                        if (inner) inner.innerText = "📞";
                    }
                    
                    appendChatBubble("system", `⚠️ Assistant Suspended. Forwarding to Lineman Raju at ${data.lineman_phone || "+91 9876543210"}`);
                    appendChatBubble("assistant", data.response);
                } else {
                    appendChatBubble("assistant", data.response);
                }
                
                // Speak out response in spoken Telugu TTS!
                speakInTelugu(data.response);
                
                // If emergency is detected in consumer query, escalate
                if (activeDialerQuery.toLowerCase().includes("spark") || 
                    activeDialerQuery.toLowerCase().includes("smoke") || 
                    activeDialerQuery.includes("పొగ") || 
                    activeDialerQuery.includes("వైర్")) {
                    
                    appendChatBubble("system", "⚠️ CALL ESCALATED: Emergency hazard routed to Substation Operator.");
                    triggerEmergencyEscalation(activeDialerArea, activeDialerQuery);
                }
            }, 800);

        } else {
            appendChatBubble("assistant", "Mee area ki information ledhu sir. Staff check chestunnaru.");
            speakInTelugu("Mee area ki information ledhu sir. Staff check chestunnaru.");
        }
    } catch (err) {
        console.error(err);
        appendChatBubble("assistant", "Connection failure. Assistant offline.");
    }
}

function takeOverCall() {
    if ('speechSynthesis' in window) {
        window.speechSynthesis.cancel();
    }
    
    // Set active call state in Equal AI panel
    document.getElementById("call-status").innerText = "ACTIVE (SPEAKING WITH LINEMAN)";
    document.getElementById("call-status").style.color = "var(--color-green)";
    
    const avatar = document.getElementById("call-avatar");
    if (avatar) {
        avatar.classList.remove("speaking");
        avatar.classList.add("forwarding");
        const inner = avatar.querySelector(".speaker-avatar-inner");
        if (inner) inner.innerText = "🤵";
    }

    appendChatBubble("system", "🙋‍♂️ Lineman Raju took over the call. AI screening deactivated.");
    
    // Play interception ding
    const ding = new Audio("https://assets.mixkit.co/active_storage/sfx/2019/2019-84.wav");
    ding.volume = 0.4;
    ding.play().catch(e => {});

    // Show active call overlay on the smartphone screen (left panel!)
    const activeCallOverlay = document.getElementById("active-call-overlay");
    const activeCallText = document.getElementById("active-call-overlay-text");
    if (activeCallOverlay) {
        if (activeCallText) {
            activeCallText.innerText = `Connected with consumer from ${activeDialerArea}...`;
        }
        activeCallOverlay.style.display = "flex";
    }

    // Hide control replies
    const equalAiControls = document.getElementById("equal-ai-controls");
    if (equalAiControls) {
        equalAiControls.style.opacity = "0.5";
        equalAiControls.style.pointerEvents = "none";
    }
}

function sendQuickReply(text) {
    appendChatBubble("assistant", text);
    speakInTelugu(text);
}

// Append Chat bubble inside phone chassis
function appendChatBubble(role, text) {
    const container = document.getElementById("transcription-chat");
    if (!container) return;
    const bubble = document.createElement("div");
    bubble.className = `chat-bubble ${role}`;
    bubble.innerText = text;
    container.appendChild(bubble);
    container.scrollTop = container.scrollHeight;
}

// Speak response aloud in Telugu or Indian English
function speakInTelugu(text) {
    if (!('speechSynthesis' in window)) return;
    
    // Stop any existing speak
    window.speechSynthesis.cancel();
    
    // Set active speaking visuals (pulse avatar)
    const avatar = document.getElementById("call-avatar");
    if (avatar) avatar.classList.add("speaking");

    currentTeluguSpeech = new SpeechSynthesisUtterance(text);
    
    const voices = window.speechSynthesis.getVoices();
    let teluguVoice = voices.find(v => v.lang.includes("te-IN") || v.lang.includes("te_IN"));
    
    if (!teluguVoice) {
        // Fallback to Indian English voice
        teluguVoice = voices.find(v => v.lang.includes("en-IN") || v.lang.includes("en_IN"));
    }
    
    if (teluguVoice) {
        currentTeluguSpeech.voice = teluguVoice;
    }
    
    currentTeluguSpeech.rate = 0.85; // Natural spoken rate
    currentTeluguSpeech.pitch = 1.0;
    
    currentTeluguSpeech.onend = () => {
        if (avatar) avatar.classList.remove("speaking");
    };

    window.speechSynthesis.speak(currentTeluguSpeech);
}

// Trigger voices load
if (typeof speechSynthesis !== 'undefined' && speechSynthesis.onvoiceschanged !== undefined) {
    speechSynthesis.onvoiceschanged = () => {
        console.log("SpeechSynthesis voices loaded.");
    };
}

function endConsumerCall() {
    if ('speechSynthesis' in window) {
        window.speechSynthesis.cancel();
    }
    
    const avatar = document.getElementById("call-avatar");
    if (avatar) {
        avatar.classList.remove("speaking");
        avatar.classList.remove("forwarding");
        const inner = avatar.querySelector(".speaker-avatar-inner");
        if (inner) inner.innerText = "⚡";
    }

    clearInterval(callTimerInterval);
    
    // Play hangup beep
    const hangup = document.getElementById("hangup-audio");
    if (hangup) hangup.play().catch(e => {});

    // Toggle Panels back
    const callPanel = document.getElementById("phone-call-panel");
    if (callPanel) {
        callPanel.classList.add("call-inactive");
    }
    
    const dialerPanel = document.getElementById("phone-dialer-panel");
    if (dialerPanel) {
        dialerPanel.style.display = "flex";
    }

    // Hide active call overlay on smartphone
    const activeCallOverlay = document.getElementById("active-call-overlay");
    if (activeCallOverlay) {
        activeCallOverlay.style.display = "none";
    }

    // Reset control replies opacity/pointer-events
    const equalAiControls = document.getElementById("equal-ai-controls");
    if (equalAiControls) {
        equalAiControls.style.opacity = "1";
        equalAiControls.style.pointerEvents = "auto";
    }

    document.getElementById("call-status").style.color = "var(--color-electric)";

    // Save call log to database
    try {
        const chatContainer = document.getElementById("transcription-chat");
        let transcriptText = "";
        if (chatContainer) {
            const bubbles = chatContainer.querySelectorAll(".chat-bubble");
            bubbles.forEach(bubble => {
                if (bubble.classList.contains("user")) {
                    transcriptText += `Consumer: ${bubble.innerText}\n`;
                } else if (bubble.classList.contains("assistant")) {
                    transcriptText += `AI: ${bubble.innerText}\n`;
                } else {
                    transcriptText += `${bubble.innerText}\n`;
                }
            });
        }
        
        let status = "Completed";
        const lowerTranscript = transcriptText.toLowerCase();
        if (lowerTranscript.includes("emergency") || lowerTranscript.includes("spark") || lowerTranscript.includes("smoke") || lowerTranscript.includes("పొగ") || lowerTranscript.includes("వైర్") || lowerTranscript.includes("danger")) {
            status = "Emergency";
        } else if (document.getElementById("call-status").innerText.includes("SPEAKING") || lowerTranscript.includes("forwarding") || lowerTranscript.includes("transfer") || (avatar && avatar.classList.contains("forwarding"))) {
            status = "Forwarded";
        }

        const formData = new FormData();
        formData.append("caller_number", "+91 9999999999"); // Mock number for simulator calls
        formData.append("transcript", transcriptText || `Consumer: ${activeDialerQuery}`);
        formData.append("status", status);
        formData.append("duration", callDuration);

        fetch("/api/v1/call-logs/", {
            method: "POST",
            body: formData
        }).then(res => {
            if (res.ok) {
                fetchAnalytics();
            }
        }).catch(err => console.warn("Failed to upload call log:", err));
    } catch (e) {
        console.warn("Error processing call log upload:", e);
    }
}

// ==========================================================================
/* Outages & Logs Data loading */
// ==========================================================================
async function fetchOutages() {
    try {
        const res = await fetch("/api/v1/all-outages/");
        if (!res.ok) return;
        
        const data = await res.json();
        renderAdminGrid(data.outages);
        fetchRecentCallLogs();
        fetchAnalytics(); // Refresh supervisor dashboard analytics
    } catch (err) {
        console.warn("Failed loading outages data:", err);
    }
}

// Render dynamic outages list inside the mobile card list
function renderAdminGrid(outages) {
    const feed = document.getElementById("mobile-outages-feed");
    if (!feed) return;
    
    feed.innerHTML = "";
    
    let activeOutages = 0;
    const isLoggedIn = localStorage.getItem("tgspdcl_logged_in") === "true";

    outages.forEach(outage => {
        if (outage.status === "In Progress" || outage.status === "Outage") {
            activeOutages++;
        }

        const card = document.createElement("div");
        const statusClass = outage.status.toLowerCase().replace(/\s+/g, '-');
        card.className = `mobile-outage-card ${statusClass}`;
        
        const timeStr = formatTimestamp(outage.last_updated);
        
        const loggedInStaffName = (localStorage.getItem("tgspdcl_staff_name") || "").trim().toLowerCase();
        const loggedInStaffId = (localStorage.getItem("tgspdcl_employee_id") || "").trim().toLowerCase();
        const staffCreator = (outage.staff_name || "").trim().toLowerCase();

        const isRestored = outage.status.toLowerCase() === "restored" || outage.status.toLowerCase() === "solved";

        // Show edit select ONLY if:
        // 1. Outage is NOT restored (status is active)
        // 2. AND the current logged-in user is the creator (or creator is empty/ALM/Staff/default)
        const canEdit = !isRestored && (
                        !staffCreator || staffCreator === "" || 
                        staffCreator === "alm" || staffCreator === "staff" ||
                        staffCreator === loggedInStaffName || 
                        staffCreator === loggedInStaffId);

        let actionHtml = "";
        if (canEdit) {
            actionHtml = `
                <select class="m-outage-select ${statusClass}" onchange="changeOutageStatus('${outage.area}', this.value)" style="background: rgba(0,0,0,0.45); border: 1px solid rgba(255,255,255,0.1); border-radius: 6px; padding: 3px 6px; color: inherit; font-size: 8.5px; font-weight: 700; outline: none; cursor: pointer; text-transform: uppercase; font-family: var(--font-body); transition: border-color 0.2s;">
                    <option value="Outage" style="background-color: var(--bg-main); color: var(--color-red);" ${outage.status === 'Outage' ? 'selected' : ''}>Outage</option>
                    <option value="In Progress" style="background-color: var(--bg-main); color: var(--color-amber);" ${outage.status === 'In Progress' ? 'selected' : ''}>In Progress</option>
                    <option value="Restored" style="background-color: var(--bg-main); color: var(--color-green);" ${outage.status === 'Restored' ? 'selected' : ''}>Restored</option>
                </select>
            `;
        } else {
            actionHtml = `
                <span class="m-outage-badge ${statusClass}" style="font-size: 8.5px; font-weight: 700; padding: 3px 6px; border-radius: 4px; text-transform: uppercase; border: 1px solid currentColor; color: inherit;">
                    ${outage.status}
                </span>
            `;
        }

        card.innerHTML = `
            <div class="m-outage-info">
                <strong>${outage.area}</strong>
                <span>${outage.issue || "Fuse Failure"}</span>
                <em>ETA: ${outage.eta || "Pending"} • Staff: ${outage.staff_name || "ALM"} • Upd: ${timeStr}</em>
            </div>
            ${actionHtml}
        `;
        feed.appendChild(card);
    });

    // Update Mobile Outage counter
    const countBadge = document.getElementById("stats-outages-count");
    if (countBadge) {
        countBadge.innerText = `${activeOutages} Active Outage${activeOutages !== 1 ? 's' : ''}`;
    }
}

// Load call history and update stats call counts
async function fetchRecentCallLogs() {
    try {
        const list = document.getElementById("call-log-list");
        const listDesktop = document.getElementById("call-log-list-desktop");
        if (!list && !listDesktop) return;

        let localQueries = JSON.parse(localStorage.getItem("tgspdcl_local_queries") || "[]");
        if (localQueries.length === 0) {
            // Seed default logs if empty
            localQueries = [
                { area: "Cherlapally", query: "Current eppudu vastundi?", response: "Cherlapally area lo Line Breakdown undi sir. Staff work chestunnaru. Approximately 1 hour padutundi.", time: "19:28" },
                { area: "Ramanapet", query: "Power cut enduku ayindi?", response: "Ramanapet lo Transformer Problem undi sir. Staff work chestunnaru. Approximately 30 minutes padutundi.", time: "19:15" }
            ];
            localStorage.setItem("tgspdcl_local_queries", JSON.stringify(localQueries));
        }

        // Count queries
        const countBadge = document.getElementById("stats-calls-count");
        if (countBadge) {
            countBadge.innerText = `${localQueries.length} Call${localQueries.length !== 1 ? 's' : ''}`;
        }
        const countBadgeDesktop = document.getElementById("stats-calls-count-desktop");
        if (countBadgeDesktop) {
            countBadgeDesktop.innerText = `${localQueries.length} Call${localQueries.length !== 1 ? 's' : ''}`;
        }

        const renderLogs = (container) => {
            if (!container) return;
            container.innerHTML = "";
            localQueries.slice().reverse().forEach(log => {
                const item = document.createElement("div");
                item.className = "mobile-log-card";
                item.innerHTML = `
                    <div class="m-log-meta">
                        <em>⚡ ${log.area}</em>
                        <span>🕒 ${log.time || "Just now"}</span>
                    </div>
                    <div class="m-log-query">Q: ${log.query}</div>
                    <div class="m-log-response">${log.response}</div>
                `;
                container.appendChild(item);
            });
        };

        renderLogs(list);
        renderLogs(listDesktop);

    } catch (err) {
        console.warn(err);
    }
}

// Save consumer query locally to list
function logQueryLocally(area, queryText, responseText) {
    const localQueries = JSON.parse(localStorage.getItem("tgspdcl_local_queries") || "[]");
    
    const now = new Date();
    const timeStr = `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`;
    
    localQueries.push({
        area: area,
        query: queryText,
        response: responseText,
        time: timeStr
    });
    
    localStorage.setItem("tgspdcl_local_queries", JSON.stringify(localQueries));
    fetchRecentCallLogs();
}

// Intercept Consumer query API response to save log
const originalFetch = window.fetch;
window.fetch = async function(...args) {
    const res = await originalFetch.apply(this, args);
    if (args[0] === "/api/v1/consumer-query/") {
        try {
            const clone = res.clone();
            const data = await clone.json();
            const payload = JSON.parse(args[1].body);
            logQueryLocally(payload.area, payload.query, data.response);
        } catch (e) {
            console.error("Local log error:", e);
        }
    }
    return res;
};

// Trigger Red Flashing alert on screen
function triggerEmergencyEscalation(area, details) {
    const alertPanel = document.getElementById("escalation-alert-panel");
    const alertText = document.getElementById("escalation-alert-text");
    
    if (alertPanel && alertText) {
        alertText.innerText = `Emergency call received from ${area}: "${details}". Automatically routed to Substation Operator. ALM/LM notified.`;
        alertPanel.style.display = "block";
        
        // Play emergency alarm
        const beep = new Audio("https://assets.mixkit.co/active_storage/sfx/951/951-84.wav");
        beep.volume = 0.4;
        beep.play().catch(e => {});
    }
}

function acknowledgeEscalation() {
    const alertPanel = document.getElementById("escalation-alert-panel");
    if (alertPanel) {
        alertPanel.style.display = "none";
    }
}

// Parse manually typed outage reports
async function extractManualOutageDetails() {
    const textarea = document.getElementById("manual-update-textarea");
    if (!textarea) return;
    const text = textarea.value.trim();
    if (!text) {
        alert("Please type a Telugu or bilingual update message first!");
        return;
    }

    document.getElementById("recording-state-label").innerText = "AI Parsing Manual Input...";
    
    const payload = {
        staff_id: localStorage.getItem("tgspdcl_employee_id") || "LM_Raju",
        update_text: text
    };

    try {
        const res = await fetch("/api/v1/staff-voice-update/", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });

        if (res.ok) {
            const data = await res.json();
            showExtractedCard(text, data.processed_info);
            textarea.value = ""; // Clear text area
        } else {
            alert("Failed to parse manual input: " + res.statusText);
        }
    } catch (err) {
        console.error("Manual parse error:", err);
        alert("Failed to connect to parser server.");
    }
    document.getElementById("recording-state-label").innerText = "Ready to record";
}

// ==========================================================================
/* Lineman Portal Login & Signup Handlers */
// ==========================================================================
function switchAuthTab(tabName) {
    const loginTabBtn = document.getElementById("btn-login-tab");
    const signupTabBtn = document.getElementById("btn-signup-tab");
    const loginForm = document.getElementById("auth-login-form");
    const signupForm = document.getElementById("auth-signup-form");
    const feedbackBox = document.getElementById("auth-feedback-box");

    if (feedbackBox) feedbackBox.style.display = "none";

    if (tabName === "login") {
        if (loginTabBtn) loginTabBtn.classList.add("active");
        if (signupTabBtn) signupTabBtn.classList.remove("active");
        if (loginForm) loginForm.style.display = "flex";
        if (signupForm) signupForm.style.display = "none";
    } else {
        if (loginTabBtn) loginTabBtn.classList.remove("active");
        if (signupTabBtn) signupTabBtn.classList.add("active");
        if (loginForm) loginForm.style.display = "none";
        if (signupForm) signupForm.style.display = "flex";
    }
}

async function loginLineman() {
    const employeeId = document.getElementById("login-employee-id").value.trim();
    const password = document.getElementById("login-password").value.trim();

    if (!employeeId || !password) {
        showAuthFeedback("Please fill in all credentials!", "error");
        return;
    }

    showAuthFeedback("Verifying credentials...", "info");

    try {
        const res = await fetch("/api/v1/auth/login/", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ employee_id: employeeId, password: password })
        });

        if (res.ok) {
            const data = await res.json();
            showAuthFeedback("Unlock success! Opening control panel...", "success");
            
            // Save state
            localStorage.setItem("tgspdcl_logged_in", "true");
            localStorage.setItem("tgspdcl_staff_name", data.user.name);
            localStorage.setItem("tgspdcl_employee_id", data.user.employee_id);
            localStorage.setItem("tgspdcl_staff_cadre", data.user.cadre);
            localStorage.setItem("tgspdcl_staff_phone", data.user.phone);
            localStorage.setItem("tgspdcl_staff_substation", data.user.substation);

            // Audio success cue
            const ding = new Audio("https://assets.mixkit.co/active_storage/sfx/2019/2019-84.wav");
            ding.volume = 0.4;
            ding.play().catch(e => {});

            setTimeout(() => {
                showLinemanWorkspace();
            }, 800);
        } else {
            const err = await res.json();
            showAuthFeedback(err.detail || "Authentication failed!", "error");
        }
    } catch (e) {
        console.error("Login request error:", e);
        showAuthFeedback("Failed to connect to authentication server.", "error");
    }
}

async function signupLineman() {
    const name = document.getElementById("signup-name").value.trim();
    const phone = document.getElementById("signup-phone").value.trim();
    const substation = document.getElementById("signup-substation").value.trim();
    const employeeId = document.getElementById("signup-employee-id").value.trim();
    const password = document.getElementById("signup-password").value.trim();
    const cadre = document.getElementById("signup-cadre").value;

    if (!name || !phone || !substation || !employeeId || !password || !cadre) {
        showAuthFeedback("Please fill in all registration fields!", "error");
        return;
    }

    showAuthFeedback("Creating account...", "info");

    try {
        const res = await fetch("/api/v1/auth/signup/", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                name: name,
                phone: phone,
                substation: substation,
                employee_id: employeeId,
                password: password,
                cadre: cadre
            })
        });

        if (res.ok) {
            showAuthFeedback("Account registered! Please login now.", "success");
            setTimeout(() => {
                switchAuthTab("login");
                document.getElementById("login-employee-id").value = employeeId;
                document.getElementById("login-password").value = "";
            }, 1200);
        } else {
            const err = await res.json();
            showAuthFeedback(err.detail || "Registration failed!", "error");
        }
    } catch (e) {
        console.error("Signup error:", e);
        showAuthFeedback("Failed to connect to authentication server.", "error");
    }
}

function showAuthFeedback(msg, type) {
    const box = document.getElementById("auth-feedback-box");
    const text = document.getElementById("auth-feedback-text");
    if (!box || !text) return;

    text.innerText = msg;
    box.style.display = "flex";

    // Style adjustments based on feedback type
    if (type === "error") {
        box.style.background = "rgba(255, 62, 85, 0.08)";
        box.style.borderColor = "rgba(255, 62, 85, 0.2)";
        text.style.color = "#ff9da8";
    } else if (type === "success") {
        box.style.background = "rgba(0, 230, 118, 0.08)";
        box.style.borderColor = "rgba(0, 230, 118, 0.2)";
        text.style.color = "#85ffc7";
    } else {
        box.style.background = "rgba(0, 210, 255, 0.08)";
        box.style.borderColor = "rgba(0, 210, 255, 0.15)";
        text.style.color = "#9deeff";
    }
}

function showLinemanWorkspace() {
    const authBox = document.getElementById("mobile-auth-container");
    const workBox = document.getElementById("mobile-workspace-container");
    
    if (authBox) authBox.style.display = "none";
    if (workBox) workBox.style.display = "flex";

    // Dynamically customize application subtitle according to actual designation cadre!
    const subtitleEl = document.querySelector(".app-titles span");
    const cadre = localStorage.getItem("tgspdcl_staff_cadre") || "LM";
    let cadreLabel = "Lineman Control Center";
    if (cadre === "ALM") {
        cadreLabel = "ALM Control Center";
    } else if (cadre === "JLM") {
        cadreLabel = "JLM Control Center";
    } else {
        cadreLabel = "Lineman Control Center";
    }
    if (subtitleEl) {
        subtitleEl.innerText = cadreLabel;
    }

    // Ensure we start at Tab 1
    const firstNavBtn = document.querySelector(".nav-item");
    if (firstNavBtn) {
        switchNavTab("home", firstNavBtn);
    }
}

function logoutLineman() {
    // Clear credentials
    localStorage.removeItem("tgspdcl_logged_in");
    localStorage.removeItem("tgspdcl_staff_name");
    localStorage.removeItem("tgspdcl_employee_id");
    localStorage.removeItem("tgspdcl_staff_cadre");
    localStorage.removeItem("tgspdcl_staff_phone");
    localStorage.removeItem("tgspdcl_staff_substation");

    const authBox = document.getElementById("mobile-auth-container");
    const workBox = document.getElementById("mobile-workspace-container");
    
    if (authBox) authBox.style.display = "flex";
    if (workBox) workBox.style.display = "none";

    // Clear login inputs
    const userField = document.getElementById("login-employee-id");
    const passField = document.getElementById("login-password");
    if (userField) userField.value = "";
    if (passField) passField.value = "";
    
    // Switch to login subtab by default
    switchAuthTab("login");
}

// Format ISO timestamp into HH:MM natural format
function formatTimestamp(isoString) {
    if (!isoString) return "Pending";
    try {
        const date = new Date(isoString);
        if (isNaN(date.getTime())) return "Pending";
        const hrs = String(date.getHours()).padStart(2, "0");
        const mins = String(date.getMinutes()).padStart(2, "0");
        return `${hrs}:${mins}`;
    } catch (e) {
        return "Pending";
    }
}

// Change outage status and record the exact time of changed status
async function changeOutageStatus(area, newStatus) {
    const staffName = localStorage.getItem("tgspdcl_staff_name") || "Staff";
    try {
        const res = await fetch("/api/v1/outage/status/", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                area: area,
                status: newStatus,
                staff_name: staffName
            })
        });

        if (res.ok) {
            fetchOutages(); // Refresh grid and statistics
            
            // Audio ding success feedback
            const ding = new Audio("https://assets.mixkit.co/active_storage/sfx/2019/2019-84.wav");
            ding.volume = 0.3;
            ding.play().catch(e => {});
        } else {
            alert("Failed to update status: " + res.statusText);
        }
    } catch (err) {
        console.error("Status update error:", err);
        alert("Failed to connect to status server.");
    }
}

// Show voice unrecognized dialog modal
function showVoiceErrorDialog() {
    const dialog = document.getElementById("voice-error-dialog");
    if (dialog) {
        dialog.style.display = "flex";
        
        // Play error beep alert
        const beep = new Audio("https://assets.mixkit.co/active_storage/sfx/951/951-84.wav");
        beep.volume = 0.3;
        beep.play().catch(e => {});
    }
}

// Dismiss voice error only
function dismissVoiceErrorOnly() {
    const dialog = document.getElementById("voice-error-dialog");
    if (dialog) dialog.style.display = "none";
}

// Dismiss voice error and perform actionable recovery
function dismissVoiceErrorAndAction(action) {
    dismissVoiceErrorOnly();
    if (action === "voice") {
        switchVoiceTab("mic");
        // Trigger recording button click or toggle
        setTimeout(() => {
            toggleRecording();
        }, 150);
    } else {
        switchVoiceTab("type");
        const textarea = document.getElementById("manual-update-textarea");
        if (textarea) {
            setTimeout(() => {
                textarea.focus();
            }, 150);
        }
    }
}

// ==========================================================================
/* Mobile Phone Simulator Functions (For physical mobile device browsers) */
// ==========================================================================
function openMobileDialerModal() {
    const modal = document.getElementById("mobile-dialer-modal");
    if (modal) {
        modal.style.display = "flex";
        const input = document.getElementById("mobile-dialer-input");
        if (input) input.value = "";
    }
}

function closeMobileDialerModal() {
    const modal = document.getElementById("mobile-dialer-modal");
    if (modal) modal.style.display = "none";
}

function selectMobileQuery(queryText, areaName) {
    const input = document.getElementById("mobile-dialer-input");
    if (input) input.value = queryText;
    activeDialerQuery = queryText;
    activeDialerArea = areaName;
}

let mobileConsumerRecognition = null;
let isMobileConsumerListening = false;

function toggleMobileConsumerSpeech() {
    const micBtn = document.getElementById("mobile-consumer-mic");
    const inputField = document.getElementById("mobile-dialer-input");
    if (!micBtn || !inputField) return;

    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRecognition) {
        alert("Web Speech API is not supported in this browser.");
        return;
    }

    if (isMobileConsumerListening) {
        if (mobileConsumerRecognition) mobileConsumerRecognition.stop();
        isMobileConsumerListening = false;
        micBtn.style.background = "rgba(255, 255, 255, 0.06)";
        micBtn.style.color = "#fff";
        return;
    }

    try {
        mobileConsumerRecognition = new SpeechRecognition();
        mobileConsumerRecognition.lang = 'te-IN';
        mobileConsumerRecognition.interimResults = false;
        mobileConsumerRecognition.maxAlternatives = 1;

        mobileConsumerRecognition.onstart = () => {
            isMobileConsumerListening = true;
            micBtn.style.background = "rgba(255, 62, 85, 0.25)";
            micBtn.style.color = "var(--color-red)";
            inputField.placeholder = "Listening Telugu speech...";
            inputField.value = "";
        };

        mobileConsumerRecognition.onresult = (event) => {
            const transcript = event.results[0][0].transcript;
            inputField.value = transcript;
            activeDialerQuery = transcript;
        };

        mobileConsumerRecognition.onend = () => {
            isMobileConsumerListening = false;
            micBtn.style.background = "rgba(255, 255, 255, 0.06)";
            micBtn.style.color = "#fff";
            inputField.placeholder = "Type query in Telugu...";
        };

        mobileConsumerRecognition.onerror = (err) => {
            console.error(err);
            isMobileConsumerListening = false;
            micBtn.style.background = "rgba(255, 255, 255, 0.06)";
            micBtn.style.color = "#fff";
        };

        mobileConsumerRecognition.start();
    } catch (e) {
        console.error(e);
    }
}

function startMobileConsumerCall() {
    const inputField = document.getElementById("mobile-dialer-input");
    const inputVal = inputField ? inputField.value.trim() : "";
    if (!inputVal) {
        alert("Please select or type a query first!");
        return;
    }
    
    activeDialerQuery = inputVal;
    
    const lowerQuery = inputVal.toLowerCase();
    if (lowerQuery.includes("ramanapet")) activeDialerArea = "Ramanapet";
    else if (lowerQuery.includes("siddipet")) activeDialerArea = "Siddipet";
    else if (lowerQuery.includes("warangal")) activeDialerArea = "Warangal";
    else if (lowerQuery.includes("narketpally")) activeDialerArea = "Narketpally";
    else if (lowerQuery.includes("cherlapally")) activeDialerArea = "Cherlapally";
    else if (!activeDialerArea) activeDialerArea = "Cherlapally";

    closeMobileDialerModal();
    
    const callPanel = document.getElementById("phone-call-panel");
    if (callPanel) {
        callPanel.classList.remove("call-inactive");
    }
    
    document.getElementById("call-status").innerText = "Dialing Assistant...";
    document.getElementById("call-status").style.color = "var(--color-electric)";
    document.getElementById("call-timer").innerText = "00:00";
    
    const endCallBtn = document.getElementById("end-call-btn");
    if (endCallBtn) endCallBtn.style.display = "flex";
    
    const ringer = document.getElementById("ringback-audio");
    if (ringer) {
        ringer.volume = 0.3;
        ringer.play().catch(e => console.log("Audio play blocked:", e));
    }

    callDuration = 0;
    setTimeout(connectConsumerCall, 1800);
}

