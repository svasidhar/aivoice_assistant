package com.tgspdcl.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.view.WindowManager
import android.app.role.RoleManager
import android.telecom.TelecomManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Multipart
import retrofit2.http.Part
import android.media.MediaPlayer
import java.util.Locale

// ==========================================================================
// RETROFIT API LAYER DEFINITION
// ==========================================================================
data class AssistantState(val is_active: Boolean, val lineman_phone: String)
data class ToggleRequest(val is_active: Boolean)
data class ToggleResponse(val message: String, val is_active: Boolean)
data class Outage(
    val area: String,
    val issue: String?,
    val eta: String?,
    val status: String,
    val staff_name: String?,
    val reason: String? = null
)
data class StatusUpdateResponse(
    val message: String,
    val area: String,
    val status: String,
    val reason: String? = null,
    val last_updated: String? = null
)
data class OutagesResponse(val outages: List<Outage>)
data class VoiceUpdateRequest(
    val area: String,
    val issue: String,
    val eta: String,
    val status: String,
    val staff_name: String? = null
)
data class ConsumerQueryRequest(val area: String, val query: String)
data class ConsumerQueryResponse(
    val response: String,
    val outage_info: Outage?,
    val forwarded: Boolean,
    val lineman_phone: String?
)
data class OutageStatusUpdateRequest(
    val area: String,
    val status: String,
    val staff_name: String? = null,
    val reason: String? = null
)
data class LoginRequest(val employee_id: String, val password: String)
data class UserProfile(
    val name: String,
    val phone: String,
    val substation: String,
    val employee_id: String,
    val cadre: String
)
data class LoginResponse(val message: String, val user: UserProfile)

data class CallLog(
    val id: Int,
    val caller_number: String,
    val timestamp: String,
    val transcript: String,
    val audio_path: String,
    val status: String,
    val duration: Int
)

data class CallLogsResponse(
    val logs: List<CallLog>
)

interface TgspdclApiService {
    @GET("api/v1/assistant-state/")
    suspend fun getAssistantState(): AssistantState

    @POST("api/v1/assistant-state/toggle/")
    suspend fun toggleAssistantState(@Body req: ToggleRequest): ToggleResponse

    @GET("api/v1/all-outages/")
    suspend fun getAllOutages(): OutagesResponse

    @POST("api/v1/voice-update/")
    suspend fun syncOutage(@Body req: VoiceUpdateRequest): Any

    @POST("api/v1/consumer-query/")
    suspend fun postConsumerQuery(@Body req: ConsumerQueryRequest): ConsumerQueryResponse

    @POST("api/v1/outage/status/")
    suspend fun updateOutageStatus(@Body req: OutageStatusUpdateRequest): StatusUpdateResponse

    @POST("api/v1/auth/login/")
    suspend fun login(@Body req: LoginRequest): LoginResponse

    @GET("api/v1/call-logs/")
    suspend fun getCallLogs(): CallLogsResponse

    @Multipart
    @POST("api/v1/call-logs/")
    suspend fun uploadCallLog(
        @Part("caller_number") callerNumber: RequestBody,
        @Part("transcript") transcript: RequestBody,
        @Part("status") status: RequestBody,
        @Part file: MultipartBody.Part?
    ): Any
}

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())

    private var serverHost = "10.0.2.2:8000"
    private var apiService: TgspdclApiService? = null

    // Call state variables
    private val isCallActiveState = mutableStateOf(false)
    private val callerNumberState = mutableStateOf("")
    private val callTranscriptState = mutableStateListOf<Pair<String, String>>() // Pair of (Sender, Message)
    private val isForwardedToOperatorState = mutableStateOf(false)
    private val isTtsSpeakingState = mutableStateOf(false)
    private var conversationStep = 0
    private var detectedArea = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show on lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Initialize SharedPreferences and host IP settings
        val sharedPrefs = getSharedPreferences("TGSPDCL_PREFS", Context.MODE_PRIVATE)
        val savedHost = sharedPrefs.getString("server_host", "10.0.2.2:8000") ?: "10.0.2.2:8000"
        updateApiService(savedHost)

        // Initialize TTS
        tts = TextToSpeech(this, this)

        // Initialize SpeechRecognizer
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        checkAndRequestPermissions()
        handleIntent(intent)

        setContent {
            TgspdclMobileTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val isCallActive by remember { isCallActiveState }
                    if (isCallActive) {
                        CallScreeningOverlay(
                            callerNumber = callerNumberState.value,
                            transcript = callTranscriptState,
                            isForwarded = isForwardedToOperatorState.value,
                            onHangUp = { endCall() }
                        )
                    } else {
                        MainLinemanScreen(
                            onSpeak = { text ->
                                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "test")
                            }
                        )
                    }
                }
            }
        }
    }

    fun updateApiService(host: String) {
        var cleanHost = host.trim()
        if (cleanHost.startsWith("http://")) {
            cleanHost = cleanHost.substring(7)
        } else if (cleanHost.startsWith("https://")) {
            cleanHost = cleanHost.substring(8)
        }
        if (cleanHost.endsWith("/")) {
            cleanHost = cleanHost.substring(0, cleanHost.length - 1)
        }
        serverHost = cleanHost
        
        val logger = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(logger)
            .build()
        val protocol = if (cleanHost.contains("localhost") || cleanHost.contains("10.0.2.2") || cleanHost.contains("192.168.")) "http" else "https"
        apiService = try {
            Retrofit.Builder()
                .baseUrl("$protocol://$cleanHost/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(TgspdclApiService::class.java)
        } catch (e: Exception) {
            null
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("te", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent != null) {
            if (intent.getBooleanExtra("EXTRA_INCOMING_CALL", false)) {
                val number = intent.getStringExtra("EXTRA_CALLER_NUMBER") ?: "Unknown Number"
                startCallScreening(number)
            } else if (intent.getBooleanExtra("EXTRA_END_CALL", false)) {
                endCall()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    fun checkAndRequestPermissions() {
        val missingPermissions = mutableListOf<String>()
        val permissions = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.CALL_PHONE
        )
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission)
            }
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), 1001)
        }

        // Check overlay (draw over other apps) permission for Android M (6.0)+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Overlay permission is required to display screening overlay. Opening settings...", Toast.LENGTH_LONG).show()
                val overlayIntent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(overlayIntent)
            }
        }

        // Request Default Dialer Role (Required in Android 10+ to answer calls programmatically)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(Context.ROLE_SERVICE) as RoleManager
            if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                    Toast.makeText(this, "Setting as default dialer is required to intercept and auto-answer calls.", Toast.LENGTH_LONG).show()
                    val roleIntent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    startActivityForResult(roleIntent, 1002)
                }
            }
        } else {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val currentDialer = telecomManager.defaultDialerPackage
            if (currentDialer != packageName) {
                Toast.makeText(this, "Setting as default dialer is required to intercept and auto-answer calls.", Toast.LENGTH_LONG).show()
                val dialerIntent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                    putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                }
                startActivity(dialerIntent)
            }
        }
    }

    fun startCallScreening(callerNumber: String) {
        isCallActiveState.value = true
        isForwardedToOperatorState.value = false
        callerNumberState.value = callerNumber
        callTranscriptState.clear()

        conversationStep = 0
        detectedArea = ""

        callTranscriptState.add("System" to "Incoming call from unknown number $callerNumber intercepted.")

        val greeting = "Hello sir, TGSPDCL assistant. Cheppandi sir."
        callTranscriptState.add("AI Assistant" to greeting)

        speakText(greeting) {
            listenToCaller()
        }
    }

    private fun performCallTransfer(forwardNumber: String) {
        triggerOperatorForward()
        // Wait 1.5 seconds, then initiate ACTION_CALL
        mainScope.launch {
            delay(1500)
            try {
                if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$forwardNumber")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(callIntent)
                    Log.d("MainActivity", "Programmatic call forwarding to $forwardNumber initiated.")
                } else {
                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$forwardNumber")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(dialIntent)
                    Log.d("MainActivity", "Fallback call dial to $forwardNumber initiated.")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to forward call: ${e.message}")
            }
        }
    }

    private fun speakText(text: String, onDone: () -> Unit = {}) {
        isTtsSpeakingState.value = true
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "utterance_id")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    isTtsSpeakingState.value = false
                    onDone()
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    isTtsSpeakingState.value = false
                }
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utterance_id")
    }

    private fun listenToCaller() {
        if (isForwardedToOperatorState.value || !isCallActiveState.value) return

        runOnUiThread {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "te-IN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d("STT", "Ready for speech")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    Log.e("STT", "Error code: $error")
                    if (isCallActiveState.value && !isForwardedToOperatorState.value && !isTtsSpeakingState.value) {
                        listenToCaller()
                    }
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val query = matches[0]
                        callTranscriptState.add("Consumer" to query)
                        processQueryWithBackend(query)
                    } else {
                        listenToCaller()
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speechRecognizer?.startListening(intent)
        }
    }

    private fun processQueryWithBackend(query: String) {
        val service = apiService ?: return
        val sharedPrefs = getSharedPreferences("TGSPDCL_PREFS", Context.MODE_PRIVATE)
        val forwardNumber = sharedPrefs.getString("substation_forward_number", "+919876543210") ?: "+919876543210"

        mainScope.launch {
            try {
                val cleanQuery = query.lowercase(Locale.ROOT)
                var responseText = ""
                var shouldForward = false

                if (conversationStep == 0) {
                    // Turn 1: Hello -> Hello
                    if (cleanQuery.contains("hello") || cleanQuery.contains("hi") || cleanQuery.contains("namaskaram")) {
                        responseText = "Hello sir. Cheppandi, em samasya undi?"
                        conversationStep = 1
                    } else if (cleanQuery.contains("current") || cleanQuery.contains("power") || cleanQuery.contains("ledhu") || cleanQuery.contains("ledu")) {
                        responseText = "aa area sir?"
                        conversationStep = 2
                    } else {
                        responseText = "Hello sir. Cheppandi, em samasya undi?"
                        conversationStep = 1
                    }
                } 
                else if (conversationStep == 1) {
                    // Turn 2: Consumer says "current ledhu sir"
                    if (cleanQuery.contains("current") || cleanQuery.contains("power") || cleanQuery.contains("ledhu") || cleanQuery.contains("ledu")) {
                        responseText = "aa area sir?"
                        conversationStep = 2
                    } else {
                        responseText = "oka 2 minutues adagandee me call maa oka substation ke kaluputhamuu valle kee chanpandee ne oka samasyaa."
                        shouldForward = true
                    }
                }
                else if (conversationStep == 2) {
                    // Turn 3: Consumer says area name (e.g. Ramanapet or Cherlapally)
                    var area = ""
                    if (cleanQuery.contains("ramanapet")) area = "Ramanapet"
                    else if (cleanQuery.contains("cherlapally") || cleanQuery.contains("charla")) area = "Cherlapally"
                    else if (cleanQuery.contains("siddipet")) area = "Siddipet"
                    else if (cleanQuery.contains("narketpally")) area = "Narketpally"

                    if (area.isNotEmpty()) {
                        detectedArea = area
                        val res = withContext(Dispatchers.IO) {
                            service.postConsumerQuery(ConsumerQueryRequest(area = area, query = query))
                        }
                        responseText = res.response
                        shouldForward = res.forwarded
                        conversationStep = 3
                    } else {
                        // Check if it is a complex/unrelated query to avoid getting stuck
                        val isComplex = cleanQuery.contains("spark") || cleanQuery.contains("smoke") || cleanQuery.contains("poga") || 
                                        cleanQuery.contains("wire") || cleanQuery.contains("sound") || cleanQuery.contains("noise") || 
                                        cleanQuery.contains("transformer") || cleanQuery.contains("operator") || cleanQuery.contains("officer") || 
                                        cleanQuery.contains("lineman") || cleanQuery.contains("lm") || cleanQuery.contains("substation") || 
                                        cleanQuery.contains("meter") || cleanQuery.contains("bill") || cleanQuery.contains("complaint") ||
                                        cleanQuery.contains("evaru") || cleanQuery.contains("who") || cleanQuery.contains("why")
                        if (isComplex) {
                            responseText = "oka 2 minutues adagandee me call maa oka substation ke kaluputhamuu valle kee chanpandee ne oka samasyaa."
                            shouldForward = true
                        } else {
                            responseText = "Dayachesi area peru cheppandi sir. Ramanapet aa leka Cherlapally aa?"
                        }
                    }
                }
                else if (conversationStep == 3) {
                    // Turn 4: Consumer asks "antha tim paduthundee" (ETA query)
                    if (cleanQuery.contains("tim") || cleanQuery.contains("time") || cleanQuery.contains("eta") || cleanQuery.contains("eppudu") || cleanQuery.contains("ganta") || cleanQuery.contains("hour") || cleanQuery.contains("minute")) {
                        if (detectedArea.isNotEmpty()) {
                            val res = withContext(Dispatchers.IO) {
                                service.postConsumerQuery(ConsumerQueryRequest(area = detectedArea, query = query))
                            }
                            responseText = res.response
                            shouldForward = res.forwarded
                            conversationStep = 4
                        } else {
                            responseText = "oka 30 minutes padutundi sir. Staff work chestunnaru."
                            conversationStep = 4
                        }
                    } else {
                        responseText = "oka 2 minutues adagandee me call maa oka substation ke kaluputhamuu valle kee chanpandee ne oka samasyaa."
                        shouldForward = true
                    }
                }
                else {
                    responseText = "oka 2 minutues adagandee me call maa oka substation ke kaluputhamuu valle kee chanpandee ne oka samasyaa."
                    shouldForward = true
                }

                callTranscriptState.add("AI Assistant" to responseText)

                speakText(responseText) {
                    if (shouldForward) {
                        performCallTransfer(forwardNumber)
                    } else {
                        listenToCaller()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error sending query to server: ${e.message}", e)
                val errorMsg = "Substation network loading error. Operator connection standby."
                callTranscriptState.add("AI Assistant" to errorMsg)
                speakText(errorMsg) {
                    performCallTransfer(forwardNumber)
                }
            }
        }
    }

    private fun triggerOperatorForward() {
        isForwardedToOperatorState.value = true
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = false
        Log.d("MainActivity", "Speakerphone disabled. Switch to earpiece.")
        playForwardAlert()
    }

    private fun playForwardAlert() {
        try {
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(applicationContext, notificationUri)
            ringtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun endCall() {
        isCallActiveState.value = false
        isForwardedToOperatorState.value = false
        callTranscriptState.clear()

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false

        speechRecognizer?.stopListening()
        Toast.makeText(this, "Call Ended", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
        speechRecognizer?.destroy()
    }
}

// ==========================================================================
// THEME & COLOR PALETTE
// ==========================================================================
val BgMain = Color(0xFF060B16)
val BgCard = Color(0xAA0D162B)
val BorderGlass = Color(0xFF1E2640)
val ColorElectric = Color(0xFF00D2FF)
val ColorAmber = Color(0xFFFFB300)
val ColorGreen = Color(0xFF00E676)
val ColorRed = Color(0xFFFF3E55)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFFCBD5E1)
val TextMuted = Color(0xFF64748B)

@Composable
fun TgspdclMobileTheme(content: @Composable () -> Unit) {
    val darkColorScheme = darkColorScheme(
        background = BgMain,
        surface = BgCard,
        primary = ColorElectric,
        secondary = ColorAmber,
        error = ColorRed
    )
    MaterialTheme(
        colorScheme = darkColorScheme,
        content = content
    )
}

// ==========================================================================
// MAIN LINEMAN SCREEN CONTROL CENTER
// ==========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainLinemanScreen(onSpeak: (String) -> Unit = {}) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sharedPrefs = context.getSharedPreferences("TGSPDCL_PREFS", Context.MODE_PRIVATE)
    
    // Tabs state
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Home", "Status", "Call Logs", "Profile")

    var isServiceScreeningActive by remember { mutableStateOf(sharedPrefs.getBoolean("is_ai_screening_active", false)) }
    val listener = remember {
        SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "is_ai_screening_active") {
                isServiceScreeningActive = prefs.getBoolean("is_ai_screening_active", false)
            }
        }
    }
    DisposableEffect(sharedPrefs) {
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    // Server IP Config
    var serverHost by remember { mutableStateOf(sharedPrefs.getString("server_host", "10.0.2.2:8000") ?: "10.0.2.2:8000") }
    var isEditingHost by remember { mutableStateOf(false) }

    // Forwarding destination setting
    var forwardingNumber by remember { mutableStateOf(sharedPrefs.getString("substation_forward_number", "+919876543210") ?: "+919876543210") }

    // Backend States
    var isAssistantActive by remember { mutableStateOf(sharedPrefs.getBoolean("ai_assistant_active", true)) }
    var linemanName by remember { mutableStateOf(sharedPrefs.getString("lineman_name", "LM Raju") ?: "LM Raju") }
    var linemanSubstation by remember { mutableStateOf(sharedPrefs.getString("lineman_substation", "Cherlapally Substation") ?: "Cherlapally Substation") }
    var linemanCadre by remember { mutableStateOf(sharedPrefs.getString("lineman_cadre", "Line Inspector") ?: "Line Inspector") }
    var linemanPhone by remember { mutableStateOf(sharedPrefs.getString("lineman_phone", "+91 9876543210") ?: "+91 9876543210") }
    var isEditingProfile by remember { mutableStateOf(false) }
    var isLoggedIn by remember { mutableStateOf(sharedPrefs.getBoolean("is_logged_in", true)) }
    var outagesList by remember { mutableStateOf(listOf<Outage>()) }
    var isLoading by remember { mutableStateOf(false) }

    // Speech & manual simulation states
    var isRecording by remember { mutableStateOf(false) }
    var parsedText by remember { mutableStateOf("") }
    var hasParsedResult by remember { mutableStateOf(false) }
    var typedText by remember { mutableStateOf("") }

    // Extracted Fields for sync confirm dialog
    var editArea by remember { mutableStateOf("Cherlapally") }
    var editIssue by remember { mutableStateOf("Line Breakdown") }
    var editEta by remember { mutableStateOf("1 hour") }
    var editStatus by remember { mutableStateOf("In Progress") }

    // Interruption Reason Dialog states
    var showReasonDialog by remember { mutableStateOf(false) }
    var selectedOutageForReason by remember { mutableStateOf<Outage?>(null) }
    var targetStatusForReason by remember { mutableStateOf("") }
    var customReasonText by remember { mutableStateOf("") }
    var selectedReasonOption by remember { mutableStateOf("") }

    // Edit ETA Dialog states
    var showEtaDialog by remember { mutableStateOf(false) }
    var selectedOutageForEta by remember { mutableStateOf<Outage?>(null) }
    var newEtaText by remember { mutableStateOf("") }

    // Build Retrofit Client dynamically based on serverHost
    val apiService = remember(serverHost) {
        val logger = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(logger)
            .build()
        val protocol = if (serverHost.contains("localhost") || serverHost.contains("10.0.2.2") || serverHost.contains("192.168.")) "http" else "https"

        try {
            Retrofit.Builder()
                .baseUrl("$protocol://$serverHost/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(TgspdclApiService::class.java)
        } catch (e: Exception) {
            null
        }
    }

    // Refresh Data helper
    val refreshData: () -> Unit = {
        if (apiService != null) {
            coroutineScope.launch {
                isLoading = true
                try {
                    val state = apiService.getAssistantState()
                    isAssistantActive = state.is_active
                    linemanPhone = state.lineman_phone
                    sharedPrefs.edit().putBoolean("ai_assistant_active", state.is_active).apply()

                    val outagesRes = apiService.getAllOutages()
                    outagesList = outagesRes.outages
                } catch (e: Exception) {
                    Toast.makeText(context, "Network Error: Couldn't sync with server.", Toast.LENGTH_SHORT).show()
                } finally {
                    isLoading = false
                }
            }
        }
    }

    // Load initial data
    LaunchedEffect(apiService) {
        refreshData()
    }

    // Mic permissions launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isRecording = true
        } else {
            Toast.makeText(context, "Permission needed to record", Toast.LENGTH_SHORT).show()
        }
    }

    if (!isLoggedIn) {
        LoginScreen(
            onLoginSuccess = { user ->
                sharedPrefs.edit().putBoolean("is_logged_in", true).apply()
                sharedPrefs.edit().putString("lineman_name", user.name).apply()
                sharedPrefs.edit().putString("lineman_substation", user.substation).apply()
                sharedPrefs.edit().putString("lineman_cadre", user.cadre).apply()
                sharedPrefs.edit().putString("lineman_phone", user.phone).apply()
                
                linemanName = user.name
                linemanSubstation = user.substation
                linemanCadre = user.cadre
                linemanPhone = user.phone
                
                isLoggedIn = true
                Toast.makeText(context, "Login successful!", Toast.LENGTH_SHORT).show()
                refreshData()
            },
            apiService = apiService
        )
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = BgCard) {
                    tabTitles.forEachIndexed { index, title ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            label = { Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            icon = {
                                Text(
                                    text = when (index) {
                                        0 -> "🏠"
                                        1 -> "📊"
                                        2 -> "📞"
                                        else -> "👤"
                                    },
                                    fontSize = 20.sp
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = ColorElectric,
                                selectedTextColor = ColorElectric,
                                indicatorColor = ColorElectric.copy(alpha = 0.15f),
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted
                            )
                        )
                    }
                }
            },
            containerColor = BgMain
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(ColorElectric, ColorAmber)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "⚡",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "TGSPDCL Assistant",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = when (selectedTab) {
                                    0 -> "Lineman Control Center"
                                    1 -> "AI & Network Status"
                                    2 -> "AI Call Log History"
                                    else -> "Lineman Profile"
                                },
                                fontSize = 11.sp,
                                color = ColorElectric,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    IconButton(onClick = refreshData) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Sync",
                            tint = ColorElectric
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (selectedTab) {
                    0 -> {
                        // HOME TAB CONTENT
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item {
                                // Cloud Host Config
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, BorderGlass, RoundedCornerShape(16.dp)),
                                    colors = CardDefaults.cardColors(containerColor = BgCard),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = "📡 CLOUD API HOST CONFIG",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextMuted,
                                            letterSpacing = 1.sp
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(
                                                    text = "Host Endpoint:",
                                                    fontSize = 12.sp,
                                                    color = TextSecondary
                                                )
                                                if (isEditingHost) {
                                                    var tempHost by remember { mutableStateOf(serverHost) }
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        OutlinedTextField(
                                                            value = tempHost,
                                                            onValueChange = { tempHost = it },
                                                            singleLine = true,
                                                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TextPrimary),
                                                            modifier = Modifier.width(180.dp).height(50.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Button(
                                                            onClick = {
                                                                var clean = tempHost.trim()
                                                                if (clean.startsWith("http://")) {
                                                                    clean = clean.substring(7)
                                                                } else if (clean.startsWith("https://")) {
                                                                    clean = clean.substring(8)
                                                                }
                                                                if (clean.endsWith("/")) {
                                                                    clean = clean.substring(0, clean.length - 1)
                                                                }
                                                                serverHost = clean
                                                                sharedPrefs.edit().putString("server_host", clean).apply()
                                                                (context as? MainActivity)?.updateApiService(clean)
                                                                isEditingHost = false
                                                            },
                                                            contentPadding = PaddingValues(horizontal = 8.dp)
                                                        ) {
                                                            Text("Save", fontSize = 10.sp)
                                                        }
                                                    }
                                                } else {
                                                    Text(
                                                        text = serverHost,
                                                        fontSize = 16.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextPrimary
                                                    )
                                                }
                                            }
                                            if (!isEditingHost) {
                                                Text(
                                                    text = "Edit IP",
                                                    fontSize = 13.sp,
                                                    color = ColorAmber,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.clickable { isEditingHost = true }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            item {
                                // AI Call Answering Switch Card
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, BorderGlass, RoundedCornerShape(16.dp)),
                                    colors = CardDefaults.cardColors(containerColor = BgCard),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "🤖 AI CALL ANSWERING PERMISSION",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextMuted,
                                                letterSpacing = 1.sp
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isAssistantActive) ColorGreen else ColorRed)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = if (isAssistantActive) "Answering Mode: Active" else "Answering Mode: Bypass",
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = if (isAssistantActive) "AI answers unknown calls programmatically." else "Calls bypass AI and route to your phone.",
                                                    fontSize = 11.sp,
                                                    color = TextSecondary
                                                )
                                            }
                                            Switch(
                                                checked = isAssistantActive,
                                                onCheckedChange = { isChecked ->
                                                    if (apiService != null) {
                                                        coroutineScope.launch {
                                                            try {
                                                                val res = apiService.toggleAssistantState(ToggleRequest(isChecked))
                                                                isAssistantActive = res.is_active
                                                                sharedPrefs.edit().putBoolean("ai_assistant_active", res.is_active).apply()
                                                                Toast.makeText(
                                                                    context,
                                                                    if (res.is_active) "AI Answering Activated!" else "Bypass Active: Call Forwarding On!",
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            } catch (e: Exception) {
                                                                Toast.makeText(context, "Failed to toggle status", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    }
                                                },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = ColorElectric,
                                                    checkedTrackColor = ColorElectric.copy(alpha = 0.3f),
                                                    uncheckedThumbColor = TextMuted,
                                                    uncheckedTrackColor = Color.Black
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            item {
                                // Substation Name Field
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, BorderGlass, RoundedCornerShape(16.dp)),
                                    colors = CardDefaults.cardColors(containerColor = BgCard),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = "🏢 SUBSTATION NAME",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextMuted,
                                            letterSpacing = 1.sp
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        OutlinedTextField(
                                            value = linemanSubstation,
                                            onValueChange = { newValue ->
                                                linemanSubstation = newValue
                                                sharedPrefs.edit().putString("lineman_substation", newValue).apply()
                                            },
                                            singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = TextPrimary),
                                            modifier = Modifier.fillMaxWidth(),
                                            placeholder = { Text("e.g. Cherlapally Substation") }
                                        )
                                    }
                                }
                            }

                            if (isAssistantActive) {
                                item {
                                    Text(
                                        text = "🎙️ Voice / ✍️ Manual Outage Reporter",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, BorderGlass, RoundedCornerShape(16.dp)),
                                        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            val infiniteTransition = rememberInfiniteTransition()
                                            val scale by infiniteTransition.animateFloat(
                                                initialValue = 1f,
                                                targetValue = if (isRecording) 1.25f else 1f,
                                                animationSpec = infiniteRepeatable(
                                                    animation = tween(800, easing = LinearEasing),
                                                    repeatMode = RepeatMode.Reverse
                                                )
                                            )
                                            val scaleGlow by infiniteTransition.animateFloat(
                                                initialValue = 1f,
                                                targetValue = if (isRecording) 1.8f else 1f,
                                                animationSpec = infiniteRepeatable(
                                                    animation = tween(800, easing = LinearEasing),
                                                    repeatMode = RepeatMode.Reverse
                                                )
                                            )

                                            Box(
                                                modifier = Modifier
                                                    .padding(vertical = 12.dp)
                                                    .size(80.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isRecording) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(50.dp * scaleGlow)
                                                            .clip(CircleShape)
                                                            .background(ColorAmber.copy(alpha = 0.15f))
                                                    )
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .size(60.dp * scale)
                                                        .clip(CircleShape)
                                                        .background(
                                                            Brush.linearGradient(
                                                                listOf(ColorAmber, Color(0xFFFF8C00))
                                                            )
                                                        )
                                                        .clickable {
                                                            if (isRecording) {
                                                                isRecording = false
                                                                parsedText = "Cherlapally area lo line breakdown ayyindi. Oka ganta (1 hour) padutundi. Staff work chestunnaru."
                                                                editArea = "Cherlapally"
                                                                editIssue = "Line Breakdown"
                                                                editEta = "1 hour"
                                                                editStatus = "In Progress"
                                                                hasParsedResult = true
                                                            } else {
                                                                val status = ContextCompat.checkSelfPermission(
                                                                    context,
                                                                    Manifest.permission.RECORD_AUDIO
                                                                )
                                                                if (status == PackageManager.PERMISSION_GRANTED) {
                                                                    isRecording = true
                                                                } else {
                                                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                                }
                                                            }
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = if (isRecording) "⏹️" else "🎤",
                                                        fontSize = 26.sp
                                                    )
                                                }
                                            }

                                            Text(
                                                text = if (isRecording) "Recording... Tap to stop" else "Hold or Tap to Record Update in Telugu",
                                                fontSize = 11.sp,
                                                color = TextMuted,
                                                textAlign = TextAlign.Center
                                            )

                                            Spacer(modifier = Modifier.height(16.dp))
                                            Divider(color = BorderGlass)
                                            Spacer(modifier = Modifier.height(16.dp))

                                            OutlinedTextField(
                                                value = typedText,
                                                onValueChange = { typedText = it },
                                                label = { Text("Or Type Outage Details Manually", color = TextMuted) },
                                                textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary),
                                                modifier = Modifier.fillMaxWidth(),
                                                placeholder = { Text("e.g. Cherlapally lo 1 hour breakdown") }
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Button(
                                                onClick = {
                                                    if (typedText.isNotBlank()) {
                                                        parsedText = typedText
                                                        val lower = typedText.lowercase(Locale.ROOT)
                                                        
                                                        // Quick local parsing heuristic
                                                        if (lower.contains("ramanapet")) editArea = "Ramanapet"
                                                        else if (lower.contains("cherlapally")) editArea = "Cherlapally"
                                                        else if (lower.contains("siddipet")) editArea = "Siddipet"
                                                        else if (lower.contains("narketpally")) editArea = "Narketpally"
                                                        else editArea = "Cherlapally"

                                                        if (lower.contains("breakdown") || lower.contains("line")) editIssue = "Line Breakdown"
                                                        else if (lower.contains("transformer")) editIssue = "Transformer Problem"
                                                        else if (lower.contains("fuse")) editIssue = "Fuse Failure"
                                                        else editIssue = "Line Breakdown"

                                                        if (lower.contains("1 hour") || lower.contains("ganta") || lower.contains("one")) editEta = "1 hour"
                                                        else if (lower.contains("2 hour") || lower.contains("gantalu") || lower.contains("two")) editEta = "2 hours"
                                                        else if (lower.contains("30 min")) editEta = "30 minutes"
                                                        else editEta = "1 hour"

                                                        editStatus = "In Progress"
                                                        hasParsedResult = true
                                                        typedText = ""
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = ButtonDefaults.buttonColors(containerColor = ColorElectric)
                                            ) {
                                                Text("Parse Typed Details", color = Color.Black, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }

                            item {
                                // Confirm entity cards
                                AnimatedVisibility(visible = hasParsedResult) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, ColorAmber.copy(alpha = 0.25f), RoundedCornerShape(16.dp)),
                                        colors = CardDefaults.cardColors(containerColor = ColorAmber.copy(alpha = 0.04f)),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "🤖 AI PARSED DETAILS",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ColorAmber
                                                )
                                                Text(
                                                    text = "Outage details to submit",
                                                    fontSize = 9.sp,
                                                    color = TextMuted
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = "\"$parsedText\"",
                                                fontSize = 11.sp,
                                                color = TextPrimary,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color.Black.copy(alpha = 0.2f))
                                                    .padding(8.dp)
                                            )

                                            Spacer(modifier = Modifier.height(8.dp))
                                            
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                OutlinedTextField(
                                                    value = editArea,
                                                    onValueChange = { editArea = it },
                                                    label = { Text("Area", fontSize = 8.sp) },
                                                    singleLine = true,
                                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = TextPrimary),
                                                    modifier = Modifier.weight(1f)
                                                )
                                                OutlinedTextField(
                                                    value = editIssue,
                                                    onValueChange = { editIssue = it },
                                                    label = { Text("Issue", fontSize = 8.sp) },
                                                    singleLine = true,
                                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = TextPrimary),
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(6.dp))

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                OutlinedTextField(
                                                    value = editEta,
                                                    onValueChange = { editEta = it },
                                                    label = { Text("ETA", fontSize = 8.sp) },
                                                    singleLine = true,
                                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = TextPrimary),
                                                    modifier = Modifier.weight(1f)
                                                )
                                                OutlinedTextField(
                                                    value = editStatus,
                                                    onValueChange = { editStatus = it },
                                                    label = { Text("Status", fontSize = 8.sp) },
                                                    singleLine = true,
                                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = TextPrimary),
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(12.dp))

                                            Button(
                                                 onClick = {
                                                     if (apiService != null) {
                                                         coroutineScope.launch {
                                                             try {
                                                                 apiService.syncOutage(
                                                                     VoiceUpdateRequest(
                                                                         area = editArea,
                                                                         issue = editIssue,
                                                                         eta = editEta,
                                                                         status = editStatus,
                                                                         staff_name = linemanName
                                                                     )
                                                                 )
                                                                 hasParsedResult = false
                                                                 Toast.makeText(context, "Outage Database Updated!", Toast.LENGTH_SHORT).show()
                                                                 refreshData()
                                                             } catch (e: Exception) {
                                                                 Toast.makeText(context, "Failed to update db", Toast.LENGTH_SHORT).show()
                                                             }
                                                         }
                                                     }
                                                 },
                                                 colors = ButtonDefaults.buttonColors(containerColor = ColorAmber),
                                                 modifier = Modifier.fillMaxWidth()
                                             ) {
                                                 Text(
                                                     text = "Save Outage to database",
                                                     fontSize = 11.sp,
                                                     fontWeight = FontWeight.Bold,
                                                     color = Color.Black
                                                 )
                                             }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        // STATUS & CONFIG TAB CONTENT
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item {
                                // Substation Forwarding Number
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, BorderGlass, RoundedCornerShape(16.dp)),
                                    colors = CardDefaults.cardColors(containerColor = BgCard),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = "📞 SUBSTATION FORWARDING PHONE NUMBER",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextMuted,
                                            letterSpacing = 1.sp
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "This number is dialed programmatically when complex or unrelated consumer queries require operator transfer.",
                                            fontSize = 11.sp,
                                            color = TextSecondary
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = forwardingNumber,
                                                onValueChange = { forwardingNumber = it },
                                                singleLine = true,
                                                placeholder = { Text("+91 98765 43210") },
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = TextPrimary),
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Button(
                                                onClick = {
                                                    sharedPrefs.edit().putString("substation_forward_number", forwardingNumber).apply()
                                                    Toast.makeText(context, "Forwarding number saved!", Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Text("Save")
                                            }
                                        }
                                    }
                                }
                            }

                            item {
                                // Permissions Status Card
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, BorderGlass, RoundedCornerShape(16.dp)),
                                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.2f)),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = "🛠️ REQUIRED PERMISSIONS STATUS",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextMuted,
                                            letterSpacing = 1.sp
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))

                                        val permissions = listOf(
                                            Manifest.permission.READ_PHONE_STATE to "Read Phone State",
                                            Manifest.permission.READ_CALL_LOG to "Read Call Log",
                                            Manifest.permission.READ_CONTACTS to "Read Contacts",
                                            Manifest.permission.ANSWER_PHONE_CALLS to "Answer Calls",
                                            Manifest.permission.RECORD_AUDIO to "Record Audio (Mic)",
                                            Manifest.permission.CALL_PHONE to "Direct Calling (Forward)"
                                        )

                                        permissions.forEach { (perm, name) ->
                                            val granted = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(name, fontSize = 12.sp, color = TextPrimary)
                                                Text(
                                                    text = if (granted) "🟢 GRANTED" else "🔴 MISSING",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (granted) ColorGreen else ColorRed
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))
                                        Button(
                                            onClick = {
                                                (context as? MainActivity)?.checkAndRequestPermissions()
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = ColorElectric)
                                        ) {
                                            Text("Re-Request Missing Permissions", fontSize = 11.sp, color = TextPrimary)
                                        }
                                    }
                                }
                            }

                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "⚡ Created Outage Reports",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(ColorElectric.copy(alpha = 0.08f))
                                            .border(1.dp, ColorElectric.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        val activeCount = outagesList.filter { 
                                            val statusLower = it.status.lowercase(Locale.ROOT)
                                            statusLower != "restored" && statusLower != "completed" && statusLower != "solved"
                                        }.size
                                        Text(
                                            text = "$activeCount Active",
                                            fontSize = 9.sp,
                                            color = ColorElectric,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            if (outagesList.isEmpty()) {
                                item {
                                    Text(
                                        text = "No active outages. Grid is healthy.",
                                        fontSize = 12.sp,
                                        color = TextMuted,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                items(outagesList) { outage ->
                                    OutageItemCard(
                                        outage = outage,
                                        linemanName = linemanName,
                                        onStatusChange = { newStatus ->
                                            selectedOutageForReason = outage
                                            targetStatusForReason = newStatus
                                            showReasonDialog = true
                                        },
                                        onUpdateEta = { selectedOutage ->
                                            selectedOutageForEta = selectedOutage
                                            newEtaText = selectedOutage.eta ?: ""
                                            showEtaDialog = true
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    }

                    2 -> {
                        // CALL LOGS TAB CONTENT
                        CallLogsScreen(apiService = apiService, serverHost = serverHost)
                    }

                    3 -> {
                        // PROFILE TAB CONTENT
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(modifier = Modifier.height(16.dp))

                            // Avatar Circle
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(ColorElectric, ColorAmber)
                                        )
                                    )
                                    .border(2.dp, BorderGlass, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("👨‍🔧", fontSize = 50.sp)
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            if (isEditingProfile) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, BorderGlass, RoundedCornerShape(16.dp)),
                                    colors = CardDefaults.cardColors(containerColor = BgCard),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text("EDIT PROFILE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ColorElectric, letterSpacing = 1.sp)
                                        Spacer(modifier = Modifier.height(16.dp))

                                        var nameField by remember { mutableStateOf(linemanName) }
                                        var substationField by remember { mutableStateOf(linemanSubstation) }
                                        var cadreField by remember { mutableStateOf(linemanCadre) }
                                        var phoneField by remember { mutableStateOf(linemanPhone) }

                                        OutlinedTextField(
                                            value = nameField,
                                            onValueChange = { nameField = it },
                                            label = { Text("Name", color = TextMuted) },
                                            singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))

                                        OutlinedTextField(
                                            value = substationField,
                                            onValueChange = { substationField = it },
                                            label = { Text("Substation", color = TextMuted) },
                                            singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))

                                        OutlinedTextField(
                                            value = cadreField,
                                            onValueChange = { cadreField = it },
                                            label = { Text("Cadre / Title", color = TextMuted) },
                                            singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))

                                        OutlinedTextField(
                                            value = phoneField,
                                            onValueChange = { phoneField = it },
                                            label = { Text("Phone Number", color = TextMuted) },
                                            singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary),
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        Spacer(modifier = Modifier.height(16.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    sharedPrefs.edit().putString("lineman_name", nameField).apply()
                                                    sharedPrefs.edit().putString("lineman_substation", substationField).apply()
                                                    sharedPrefs.edit().putString("lineman_cadre", cadreField).apply()
                                                    sharedPrefs.edit().putString("lineman_phone", phoneField).apply()
                                                    
                                                    linemanName = nameField
                                                    linemanSubstation = substationField
                                                    linemanCadre = cadreField
                                                    linemanPhone = phoneField
                                                    isEditingProfile = false
                                                    Toast.makeText(context, "Profile updated successfully!", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.buttonColors(containerColor = ColorGreen)
                                            ) {
                                                Text("Save Changes", color = Color.Black, fontWeight = FontWeight.Bold)
                                            }

                                            Button(
                                                onClick = {
                                                    isEditingProfile = false
                                                },
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                                            ) {
                                                Text("Cancel", color = TextPrimary)
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = linemanName,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "$linemanSubstation • $linemanCadre",
                                    fontSize = 12.sp,
                                    color = ColorElectric,
                                    fontWeight = FontWeight.Medium
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                // Lineman details card
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, BorderGlass, RoundedCornerShape(16.dp)),
                                    colors = CardDefaults.cardColors(containerColor = BgCard),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Department", fontSize = 12.sp, color = TextMuted)
                                            Text("TGSPDCL (Telangana)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        }
                                        Divider(color = BorderGlass)
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Employee ID", fontSize = 12.sp, color = TextMuted)
                                            Text("TS-LINEMAN-55412", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        }
                                        Divider(color = BorderGlass)
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Phone", fontSize = 12.sp, color = TextMuted)
                                            Text(linemanPhone, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        }
                                        Divider(color = BorderGlass)
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Status", fontSize = 12.sp, color = TextMuted)
                                            Text("🟢 ACTIVE & READY", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ColorGreen)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(24.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = { isEditingProfile = true },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = ColorElectric)
                                    ) {
                                        Text("Edit Profile", color = Color.Black, fontWeight = FontWeight.Bold)
                                    }

                                    Button(
                                        onClick = {
                                            sharedPrefs.edit().putBoolean("is_logged_in", false).apply()
                                            isLoggedIn = false
                                            Toast.makeText(context, "Logged out successfully", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = ColorRed)
                                    ) {
                                        Text("Logout", color = TextPrimary, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(modifier = Modifier.height(24.dp))

                                Button(
                                    onClick = {
                                        try {
                                            onSpeak("Namaskaram andi testing")
                                            Toast.makeText(context, "Requesting TTS Speak...", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "TTS Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = ColorElectric)
                                ) {
                                    Text("🔊 Test TTS Output (Offline)", color = Color.Black, fontWeight = FontWeight.Bold)
                                }

                                Spacer(modifier = Modifier.height(24.dp))

                                // Test Service Simulator Tools Card
                                val isServiceActive = isServiceScreeningActive
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, if (isServiceActive) ColorGreen.copy(alpha = 0.4f) else ColorAmber.copy(alpha = 0.25f), RoundedCornerShape(16.dp)),
                                    colors = CardDefaults.cardColors(containerColor = if (isServiceActive) ColorGreen.copy(alpha = 0.05f) else ColorAmber.copy(alpha = 0.05f)),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = if (isServiceActive) "🤖 ACTIVE CALL SCREENING SIMULATION" else "🧪 TEST RUNNER SIMULATION",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isServiceActive) ColorGreen else ColorAmber,
                                            letterSpacing = 1.sp
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = if (isServiceActive) "AI is currently screening a call in the background. The lineman's screen remains clean and undisturbed. You can listen afterwards in the 'Call Logs' tab." else "Manually trigger the background Call Screening Service to test the Telugu speech screening flow, the automated call forwarding, and Call Logs registration without placing a real cellular call.",
                                            fontSize = 11.sp,
                                            color = TextSecondary
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        
                                        if (isServiceActive) {
                                            Button(
                                                onClick = {
                                                    val stopIntent = Intent(context, CallScreeningService::class.java).apply {
                                                        action = "STOP_SCREENING"
                                                    }
                                                    context.startService(stopIntent)
                                                    Toast.makeText(context, "Hang up signal sent to service", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = ButtonDefaults.buttonColors(containerColor = ColorRed)
                                            ) {
                                                Text("Simulate Hang Up (End Screening)", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                                            }
                                        } else {
                                            Button(
                                                onClick = {
                                                    val serviceIntent = Intent(context, CallScreeningService::class.java).apply {
                                                        action = "START_SCREENING"
                                                        putExtra("EXTRA_CALLER_NUMBER", "+91 99999 88888")
                                                    }
                                                    ContextCompat.startForegroundService(context, serviceIntent)
                                                    Toast.makeText(context, "Call Screening Service Simulated", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = ButtonDefaults.buttonColors(containerColor = ColorAmber)
                                            ) {
                                                Text("Simulate Incoming Call (Start Screening)", fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showReasonDialog) {
        AlertDialog(
            onDismissRequest = {
                showReasonDialog = false
                selectedReasonOption = ""
                customReasonText = ""
                selectedOutageForReason = null
            },
            title = {
                Text(
                    text = "Reason for Interruption",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Why was the power supply interrupted in ${selectedOutageForReason?.area ?: ""}? This reason will be spoken to consumers when they call.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    
                    val options = listOf(
                        "Line Breakdown",
                        "Tree Fall",
                        "Heavy Rain / Storm",
                        "Fuse Blown",
                        "Load Shedding",
                        "Transformer Replacement"
                    )
                    
                    options.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedReasonOption = option }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedReasonOption == option,
                                onClick = { selectedReasonOption = option },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = ColorElectric,
                                    unselectedColor = TextMuted
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = option,
                                color = if (selectedReasonOption == option) ColorElectric else TextPrimary,
                                fontSize = 14.sp
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = customReasonText,
                        onValueChange = { customReasonText = it },
                        label = { Text("Other Reason (Custom)", color = TextMuted) },
                        textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalReason = if (customReasonText.isNotBlank()) customReasonText.trim() else selectedReasonOption.ifEmpty { "unspecified maintenance work" }
                        if (apiService != null && selectedOutageForReason != null) {
                            coroutineScope.launch {
                                try {
                                    apiService.updateOutageStatus(
                                        OutageStatusUpdateRequest(
                                            area = selectedOutageForReason!!.area,
                                            status = targetStatusForReason,
                                            staff_name = linemanName,
                                            reason = finalReason
                                        )
                                    )
                                    Toast.makeText(context, "Status updated to $targetStatusForReason!", Toast.LENGTH_SHORT).show()
                                    refreshData()
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Failed to update status on server", e)
                                    val errorMsg = getRetrofitErrorMessage(e)
                                    Toast.makeText(context, "Failed to update status: $errorMsg", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        showReasonDialog = false
                        selectedReasonOption = ""
                        customReasonText = ""
                        selectedOutageForReason = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ColorElectric)
                ) {
                    Text("Submit", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        showReasonDialog = false
                        selectedReasonOption = ""
                        customReasonText = ""
                        selectedOutageForReason = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.3f))
                ) {
                    Text("Cancel", color = TextPrimary)
                }
            },
            containerColor = BgCard,
            modifier = Modifier.border(1.dp, BorderGlass, RoundedCornerShape(28.dp))
        )
    }

    if (showEtaDialog) {
        AlertDialog(
            onDismissRequest = {
                showEtaDialog = false
                newEtaText = ""
                selectedOutageForEta = null
            },
            title = {
                Text(
                    text = "Update Restoration ETA",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Enter new Estimated Time of Restoration for ${selectedOutageForEta?.area}:",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    val etaPresets = listOf("15 minutes", "30 minutes", "45 minutes", "1 hour", "1.5 hours", "2 hours")
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        etaPresets.take(3).forEach { preset ->
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { 
                                        newEtaText = preset
                                    }
                                    .border(1.dp, if (newEtaText == preset) ColorElectric else BorderGlass, RoundedCornerShape(8.dp)),
                                colors = CardDefaults.cardColors(containerColor = if (newEtaText == preset) ColorElectric.copy(alpha = 0.1f) else BgCard)
                            ) {
                                Box(modifier = Modifier.padding(8.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text(preset, fontSize = 10.sp, color = if (newEtaText == preset) ColorElectric else TextPrimary, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        etaPresets.drop(3).forEach { preset ->
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { 
                                        newEtaText = preset
                                    }
                                    .border(1.dp, if (newEtaText == preset) ColorElectric else BorderGlass, RoundedCornerShape(8.dp)),
                                colors = CardDefaults.cardColors(containerColor = if (newEtaText == preset) ColorElectric.copy(alpha = 0.1f) else BgCard)
                            ) {
                                Box(modifier = Modifier.padding(8.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text(preset, fontSize = 10.sp, color = if (newEtaText == preset) ColorElectric else TextPrimary, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedTextField(
                        value = newEtaText,
                        onValueChange = { newEtaText = it },
                        label = { Text("Custom ETA / Time", color = TextMuted) },
                        textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalEta = newEtaText.trim()
                        if (finalEta.isBlank()) {
                            Toast.makeText(context, "Please enter or select a restoration time!", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (apiService != null && selectedOutageForEta != null) {
                            coroutineScope.launch {
                                try {
                                    apiService.syncOutage(
                                        VoiceUpdateRequest(
                                            area = selectedOutageForEta!!.area,
                                            issue = selectedOutageForEta!!.issue ?: "Power Outage",
                                            eta = finalEta,
                                            status = selectedOutageForEta!!.status,
                                            staff_name = linemanName
                                        )
                                    )
                                    Toast.makeText(context, "Restoration ETA updated to $finalEta!", Toast.LENGTH_SHORT).show()
                                    refreshData()
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Failed to update ETA on server", e)
                                    val errorMsg = getRetrofitErrorMessage(e)
                                    Toast.makeText(context, "Failed to update ETA: $errorMsg", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        showEtaDialog = false
                        newEtaText = ""
                        selectedOutageForEta = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ColorElectric)
                ) {
                    Text("Update", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        showEtaDialog = false
                        newEtaText = ""
                        selectedOutageForEta = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.3f))
                ) {
                    Text("Cancel", color = TextPrimary)
                }
            },
            containerColor = BgCard,
            modifier = Modifier.border(1.dp, BorderGlass, RoundedCornerShape(28.dp))
        )
    }
}

@Composable
fun OutageItemCard(
    outage: Outage,
    linemanName: String,
    onStatusChange: (String) -> Unit = {},
    onUpdateEta: (Outage) -> Unit = {}
) {
    val accentColor = when (outage.status.lowercase(Locale.ROOT)) {
        "restored", "solved" -> ColorGreen
        "completed" -> ColorElectric
        "in progress" -> ColorAmber
        else -> ColorRed
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                BorderGlass,
                RoundedCornerShape(8.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawLeftBorder(accentColor, 4.dp)
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = outage.area,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = outage.issue ?: "Fuse Failure",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Text(
                        text = "ETA: ${outage.eta ?: "Pending"} • Staff: ${outage.staff_name ?: "ALM"}",
                        fontSize = 9.sp,
                        color = TextMuted
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(accentColor.copy(alpha = 0.08f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = outage.status,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                }
            }

            val statusLower = outage.status.lowercase(Locale.ROOT)
            val isDone = statusLower == "restored" || statusLower == "completed" || statusLower == "solved"

            val loggedInName = linemanName.trim().lowercase(Locale.ROOT)
            val creatorName = (outage.staff_name ?: "").trim().lowercase(Locale.ROOT)
            val canEdit = !isDone && (creatorName.isEmpty() || creatorName == "alm" || creatorName == "staff" || creatorName == "default" || creatorName == "unknown" || creatorName == "unknown staff" || creatorName == loggedInName)

            if (canEdit) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = BorderGlass.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { onUpdateEta(outage) },
                        colors = ButtonDefaults.buttonColors(containerColor = ColorElectric.copy(alpha = 0.1f), contentColor = ColorElectric),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(26.dp)
                            .border(1.dp, ColorElectric.copy(alpha = 0.3f), RoundedCornerShape(4.dp)),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("Update ETA", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Text(
                        text = "Resolve:",
                        fontSize = 10.sp,
                        color = TextMuted,
                        fontWeight = FontWeight.Medium
                    )
                    Button(
                        onClick = { onStatusChange("Restored") },
                        colors = ButtonDefaults.buttonColors(containerColor = ColorGreen.copy(alpha = 0.15f), contentColor = ColorGreen),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("Restored", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onStatusChange("Completed") },
                        colors = ButtonDefaults.buttonColors(containerColor = ColorElectric.copy(alpha = 0.15f), contentColor = ColorElectric),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("Completed", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// Custom helper modifier to draw left border
fun Modifier.drawLeftBorder(color: Color, width: androidx.compose.ui.unit.Dp): Modifier {
    return this.drawBehind {
        val strokeWidth = width.toPx()
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(strokeWidth / 2, 0f),
            end = androidx.compose.ui.geometry.Offset(strokeWidth / 2, size.height),
            strokeWidth = strokeWidth
        )
    }
}

// Helper to get descriptive errors from Retrofit exceptions
fun getRetrofitErrorMessage(e: Exception): String {
    return when (e) {
        is retrofit2.HttpException -> {
            val code = e.code()
            val errorBody = try {
                e.response()?.errorBody()?.string()
            } catch (ex: Exception) {
                null
            }
            val detail = if (!errorBody.isNullOrBlank()) {
                try {
                    val gson = com.google.gson.Gson()
                    val map = gson.fromJson(errorBody, Map::class.java)
                    map["detail"]?.toString()
                } catch (ex: Exception) {
                    null
                }
            } else {
                null
            }
            detail ?: "HTTP $code: ${e.message()}"
        }
        is java.io.IOException -> "Network error: ${e.localizedMessage ?: "Connection timed out"}"
        else -> e.localizedMessage ?: e.javaClass.simpleName
    }
}

// ==========================================================================
// CALL SCREENING FULL-SCREEN OVERLAY
// ==========================================================================
@Composable
fun CallScreeningOverlay(
    callerNumber: String,
    transcript: List<Pair<String, String>>,
    isForwarded: Boolean,
    onHangUp: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgMain)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(28.dp))

        // Call State Header
        Card(
            colors = CardDefaults.cardColors(containerColor = if (isForwarded) ColorGreen.copy(alpha = 0.08f) else ColorElectric.copy(alpha = 0.08f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.border(1.dp, if (isForwarded) ColorGreen.copy(alpha = 0.2f) else ColorElectric.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isForwarded) ColorGreen else ColorElectric)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isForwarded) "DIRECT HANDSET LINE ACTIVE" else "EQUAL AI CALL SCREENING ACTIVE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isForwarded) ColorGreen else ColorElectric,
                    letterSpacing = 0.5.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Glowing Caller Avatar
        Box(
            modifier = Modifier.size(100.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp * pulseScale)
                    .clip(CircleShape)
                    .background(if (isForwarded) ColorGreen.copy(alpha = 0.15f) else ColorElectric.copy(alpha = 0.15f))
            )
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            if (isForwarded) listOf(ColorGreen, Color(0xFF00C853)) else listOf(ColorElectric, Color(0xFF0077FF))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isForwarded) "📞" else "🤖",
                    fontSize = 28.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = callerNumber,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Text(
            text = if (isForwarded) "Speaking with Operator (LM Raju)" else "Interception screening in progress...",
            fontSize = 11.sp,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Scrolling Transcript Pane
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, BorderGlass, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = BgCard),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "LIVE SCREEN TRANSCRIPT",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(transcript) { item ->
                        val isUser = item.first == "Consumer"
                        val isSystem = item.first == "System"
                        
                        if (isSystem) {
                            Text(
                                text = item.second,
                                fontSize = 10.sp,
                                color = ColorAmber,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isUser) Color(0xFF1E293B) else ColorElectric.copy(alpha = 0.08f)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.widthIn(max = 240.dp)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = item.first,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isUser) ColorAmber else ColorElectric
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = item.second,
                                            fontSize = 12.sp,
                                            color = TextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Red Hang Up Button
        Button(
            onClick = onHangUp,
            colors = ButtonDefaults.buttonColors(containerColor = ColorRed),
            shape = CircleShape,
            modifier = Modifier
                .size(64.dp)
                .border(2.dp, Color.White.copy(alpha = 0.15f), CircleShape),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("❌", fontSize = 24.sp)
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "HANG UP CALL",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onLoginSuccess: (UserProfile) -> Unit, apiService: TgspdclApiService?) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sharedPrefs = context.getSharedPreferences("TGSPDCL_PREFS", Context.MODE_PRIVATE)

    var employeeId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgMain)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(70.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(ColorElectric, ColorAmber))),
            contentAlignment = Alignment.Center
        ) {
            Text("⚡", fontSize = 36.sp, color = Color.Black)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("TGSPDCL Assistant", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text("Lineman Control Hub", fontSize = 14.sp, color = ColorElectric, fontWeight = FontWeight.Medium)

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderGlass, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = BgCard),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("LOG IN", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextMuted, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = employeeId,
                    onValueChange = { employeeId = it },
                    label = { Text("Employee ID", color = TextMuted) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password", color = TextMuted) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        if (employeeId.isBlank() || password.isBlank()) {
                            Toast.makeText(context, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        isLoading = true
                        coroutineScope.launch {
                            try {
                                if (apiService != null) {
                                    val res = withContext(Dispatchers.IO) {
                                        apiService.login(LoginRequest(employeeId, password))
                                    }
                                    onLoginSuccess(res.user)
                                } else {
                                    // Local fallback check for demo
                                    if (employeeId == "LM_Raju" && password == "password123") {
                                        onLoginSuccess(UserProfile("LM Raju", "+91 9876543210", "Cherlapally Substation", "LM_Raju", "Line Inspector"))
                                    } else {
                                        Toast.makeText(context, "Invalid Credentials (Local Fallback)", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (e: Exception) {
                                // Local fallback check on network error
                                if (employeeId == "LM_Raju" && password == "password123") {
                                    onLoginSuccess(UserProfile("LM Raju", "+91 9876543210", "Cherlapally Substation", "LM_Raju", "Line Inspector"))
                                } else {
                                    Toast.makeText(context, "Login Failed. Check credentials or network.", Toast.LENGTH_SHORT).show()
                                }
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ColorElectric),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                    } else {
                        Text("Log In", fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Demo Credentials: LM_Raju / password123",
            fontSize = 11.sp,
            color = TextMuted,
            textAlign = TextAlign.Center
        )
    }
}

// ==========================================================================
// CALL LOGS SCREEN
// ==========================================================================
@Composable
fun CallLogsScreen(apiService: TgspdclApiService?, serverHost: String) {
    val context = LocalContext.current
    var logsList by remember { mutableStateOf(listOf<CallLog>()) }
    var isLoadingLogs by remember { mutableStateOf(false) }
    var expandedLogId by remember { mutableStateOf<Int?>(null) }

    // Audio Player States
    var playingLogId by remember { mutableStateOf<Int?>(null) }
    var isAudioPlaying by remember { mutableStateOf(false) }
    var isPreparingAudio by remember { mutableStateOf(false) }
    
    val mediaPlayer = remember { MediaPlayer() }

    val fetchLogs = {
        if (apiService != null) {
            isLoadingLogs = true
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val res = apiService.getCallLogs()
                    logsList = res.logs
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to load call logs: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    isLoadingLogs = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        fetchLogs()
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                mediaPlayer.release()
            } catch (e: Exception) {}
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🤖 AI CONSUMER CALL SCREENING LOGS",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                letterSpacing = 1.sp
            )
            
            IconButton(
                onClick = { fetchLogs() },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh Logs",
                    tint = ColorElectric,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoadingLogs) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ColorElectric)
            }
        } else if (logsList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📞", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No screened call logs found on this server.",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(logsList) { log ->
                    val isExpanded = expandedLogId == log.id
                    val isCurrentPlaying = playingLogId == log.id

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderGlass, RoundedCornerShape(16.dp))
                            .clickable {
                                expandedLogId = if (isExpanded) null else log.id
                            },
                        colors = CardDefaults.cardColors(containerColor = BgCard),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Top row: Number and Badge
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("📞", fontSize = 16.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = log.caller_number,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }

                                val isForwardedStatus = log.status.contains("Forwarded")
                                val badgeColor = if (isForwardedStatus) ColorAmber else ColorGreen
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(badgeColor.copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = log.status,
                                        fontSize = 10.sp,
                                        color = badgeColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Middle Row: Timestamp
                            val displayTime = try {
                                log.timestamp.replace("T", " ").substring(0, 16)
                            } catch (e: Exception) {
                                log.timestamp
                            }

                            Text(
                                text = "Screened on: $displayTime",
                                fontSize = 11.sp,
                                color = TextMuted
                            )

                            // Audio player section (if audio_path is present)
                            if (log.audio_path.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.03f))
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Play/Pause button
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(ColorElectric.copy(alpha = 0.15f))
                                            .clickable {
                                                if (isCurrentPlaying) {
                                                    if (isAudioPlaying) {
                                                        mediaPlayer.pause()
                                                        isAudioPlaying = false
                                                    } else {
                                                        mediaPlayer.start()
                                                        isAudioPlaying = true
                                                    }
                                                } else {
                                                    // Start new playback
                                                    try {
                                                        mediaPlayer.reset()
                                                        playingLogId = log.id
                                                        isPreparingAudio = true
                                                        isAudioPlaying = false

                                                        val protocol = if (serverHost.contains("localhost") || serverHost.contains("10.0.2.2") || serverHost.contains("192.168.")) "http" else "https"
                                                        val audioUrl = "$protocol://$serverHost${log.audio_path}"
                                                        
                                                        mediaPlayer.setDataSource(audioUrl)
                                                        mediaPlayer.prepareAsync()
                                                        mediaPlayer.setOnPreparedListener {
                                                            isPreparingAudio = false
                                                            mediaPlayer.start()
                                                            isAudioPlaying = true
                                                        }
                                                        mediaPlayer.setOnCompletionListener {
                                                            isAudioPlaying = false
                                                            playingLogId = null
                                                        }
                                                        mediaPlayer.setOnErrorListener { _, _, _ ->
                                                            isPreparingAudio = false
                                                            isAudioPlaying = false
                                                            playingLogId = null
                                                            Toast.makeText(context, "Failed to stream audio file", Toast.LENGTH_SHORT).show()
                                                            true
                                                        }
                                                    } catch (e: Exception) {
                                                        isPreparingAudio = false
                                                        playingLogId = null
                                                        Toast.makeText(context, "Error playing audio: ${e.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isCurrentPlaying && isPreparingAudio) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                color = ColorElectric,
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Text(
                                                text = if (isCurrentPlaying && isAudioPlaying) "⏸" else "▶",
                                                color = ColorElectric,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    // Audio track details
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (isCurrentPlaying) {
                                                if (isAudioPlaying) "⚡ Stream Playing..." else "⏸ Stream Paused"
                                            } else "🔊 Listen to Call Recording",
                                            fontSize = 12.sp,
                                            color = if (isCurrentPlaying) ColorElectric else TextSecondary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    // Playing animation
                                    if (isCurrentPlaying && isAudioPlaying) {
                                        Text("🔊🎶", fontSize = 14.sp, color = ColorElectric)
                                    }
                                }
                            }

                            // Transcript expansion trigger
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isExpanded) "Hide Transcript ▲" else "Show Dialogue Transcript ▼",
                                    fontSize = 12.sp,
                                    color = ColorElectric,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (isExpanded) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Divider(color = BorderGlass)
                                Spacer(modifier = Modifier.height(12.dp))

                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val lines = log.transcript.split("\n")
                                    for (line in lines) {
                                        if (line.trim().isEmpty()) continue
                                        
                                        val parts = line.split(":", limit = 2)
                                        val sender = if (parts.size > 1) parts[0].trim() else ""
                                        val message = if (parts.size > 1) parts[1].trim() else line.trim()

                                        when {
                                            sender.contains("AI Assistant") -> {
                                                Column(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalAlignment = Alignment.End
                                                ) {
                                                    Text(
                                                        text = sender,
                                                        fontSize = 9.sp,
                                                        color = TextMuted,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(12.dp, 12.dp, 0.dp, 12.dp))
                                                            .background(ColorElectric.copy(alpha = 0.08f))
                                                            .border(0.5.dp, ColorElectric.copy(alpha = 0.15f), RoundedCornerShape(12.dp, 12.dp, 0.dp, 12.dp))
                                                            .padding(10.dp)
                                                    ) {
                                                        Text(
                                                            text = message,
                                                            fontSize = 12.sp,
                                                            color = TextPrimary
                                                        )
                                                    }
                                                }
                                            }
                                            sender.contains("Consumer") -> {
                                                Column(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalAlignment = Alignment.Start
                                                ) {
                                                    Text(
                                                        text = sender,
                                                        fontSize = 9.sp,
                                                        color = TextMuted,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(12.dp, 12.dp, 12.dp, 0.dp))
                                                            .background(ColorGreen.copy(alpha = 0.08f))
                                                            .border(0.5.dp, ColorGreen.copy(alpha = 0.15f), RoundedCornerShape(12.dp, 12.dp, 12.dp, 0.dp))
                                                            .padding(10.dp)
                                                    ) {
                                                        Text(
                                                            text = message,
                                                            fontSize = 12.sp,
                                                            color = TextPrimary
                                                        )
                                                    }
                                                }
                                            }
                                            else -> {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 4.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = line,
                                                        fontSize = 10.sp,
                                                        color = TextMuted,
                                                        textAlign = TextAlign.Center
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
