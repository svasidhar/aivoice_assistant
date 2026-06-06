package com.tgspdcl.assistant

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.Locale

class CallScreeningService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var audioFile: File? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var callerNumber = "Unknown Number"
    private val callTranscript = mutableListOf<Pair<String, String>>()
    private var conversationStep = 0
    private var detectedArea = ""
    private var isCallActive = false
    private var isForwarded = false
    private var isTtsSpeaking = false

    private val CHANNEL_ID = "CallScreeningChannel"
    private val NOTIFICATION_ID = 2026
    private var isTtsInitialized = false

    override fun onCreate() {
        super.onCreate()
        Log.d("CallScreeningService", "Service Created")
        createNotificationChannel()

        // Initialize TTS with applicationContext to avoid Service context binding restrictions
        tts = TextToSpeech(applicationContext, this)

        // Initialize SpeechRecognizer on main thread
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
    }

    private fun setSpeakerphone(on: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (on) {
                    val speakerDevice = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speakerDevice != null) {
                        val success = audioManager.setCommunicationDevice(speakerDevice)
                        Log.d("CallScreeningService", "Modern setCommunicationDevice speaker: $success")
                    } else {
                        Log.e("CallScreeningService", "Modern builtin speaker device not found")
                    }
                } else {
                    audioManager.clearCommunicationDevice()
                    Log.d("CallScreeningService", "Modern clearCommunicationDevice")
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = on
                Log.d("CallScreeningService", "Legacy isSpeakerphoneOn set to $on")
            }
        } catch (e: Exception) {
            Log.e("CallScreeningService", "Failed to set speakerphone: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d("CallScreeningService", "onStartCommand action: $action")

        if (action == "START_SCREENING") {
            callerNumber = intent.getStringExtra("EXTRA_CALLER_NUMBER") ?: "Unknown Number"
            startForegroundService(callerNumber)
        } else if (action == "STOP_SCREENING") {
            stopForegroundService()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundService(number: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Screening Active")
            .setContentText("Intercepted and screening call from $number")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        isCallActive = true
        isForwarded = false
        callTranscript.clear()
        conversationStep = 0
        detectedArea = ""

        // Set SharedPreferences state so UI can dynamically display screening alert
        val sharedPrefs = getSharedPreferences("TGSPDCL_PREFS", Context.MODE_PRIVATE)
        sharedPrefs.edit().putBoolean("is_ai_screening_active", true).apply()

        callTranscript.add("System" to "Incoming call from unknown number $number intercepted.")

        // Configure audio router: Enable Speakerphone
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) {}
        setSpeakerphone(true)

        // Start call audio recording
        startRecording()

        // Wait brief moment for audio routing to stabilize, then greet if ready
        serviceScope.launch {
            delay(1000)
            if (isTtsInitialized) {
                triggerGreeting()
            } else {
                Log.d("CallScreeningService", "TTS not initialized yet. Greeting will trigger in onInit.")
            }
        }

        // Test Run delayed Speak call after 3 seconds as requested by the user
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                Log.e("AI_CALL", "FORCING TTS")
                val result = tts?.speak(
                    "Namaskaram andi, testing",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "forced_test"
                )
                Log.d("CallScreeningService", "TEST RUNNER: speak result code: $result")
                android.widget.Toast.makeText(applicationContext, "Forced Test Speak Result: $result", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("CallScreeningService", "TEST RUNNER: speak failed: ${e.message}", e)
            }
        }, 3000)
    }

    private fun triggerGreeting() {
        val greeting = "Hello sir, TGSPDCL assistant. Cheppandi sir."
        if (callTranscript.none { it.second == greeting }) {
            callTranscript.add("AI Assistant" to greeting)
        }
        speakText(greeting) {
            listenToCaller()
        }
    }

    private fun startRecording() {
        try {
            val cacheDir = externalCacheDir ?: cacheDir
            audioFile = File(cacheDir, "call_rec_${System.currentTimeMillis()}.mp4")
            
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(applicationContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            Log.d("CallScreeningService", "Call recording started at: ${audioFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e("CallScreeningService", "Error initializing MediaRecorder: ${e.message}", e)
            isRecording = false
        }
    }

    private fun stopRecording() {
        if (isRecording && mediaRecorder != null) {
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
                Log.d("CallScreeningService", "Call recording stopped.")
            } catch (e: Exception) {
                Log.e("CallScreeningService", "Error stopping MediaRecorder: ${e.message}")
            } finally {
                mediaRecorder = null
                isRecording = false
            }
        }
    }

    private fun speakText(text: String, onDone: () -> Unit = {}) {
        isTtsSpeaking = true
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "utterance_id")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                isTtsSpeaking = false
                if (isCallActive && !isForwarded) {
                    serviceScope.launch(Dispatchers.Main) {
                        onDone()
                    }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                isTtsSpeaking = false
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utterance_id")
    }

    private fun listenToCaller() {
        if (isForwarded || !isCallActive) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "te-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("CallScreeningService", "Speech Recognizer: Ready")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                Log.e("CallScreeningService", "Speech Recognizer Error: $error")
                // Retry listening if call remains active and we are not speaking or forwarded
                if (isCallActive && !isForwarded && !isTtsSpeaking) {
                    listenToCaller()
                }
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val query = matches[0]
                    callTranscript.add("Consumer" to query)
                    processQueryWithBackend(query)
                } else {
                    if (isCallActive && !isForwarded) {
                        listenToCaller()
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    private fun processQueryWithBackend(query: String) {
        val sharedPrefs = getSharedPreferences("TGSPDCL_PREFS", Context.MODE_PRIVATE)
        val host = sharedPrefs.getString("server_host", "10.0.2.2:8000") ?: "10.0.2.2:8000"
        val apiService = getApiService(host)
        val forwardNumber = sharedPrefs.getString("substation_forward_number", "+919876543210") ?: "+919876543210"

        if (apiService == null) {
            handleForwardingFallback(forwardNumber)
            return
        }

        serviceScope.launch {
            try {
                val cleanQuery = query.lowercase(Locale.ROOT)
                var responseText = ""
                var shouldForward = false

                if (conversationStep == 0) {
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
                    if (cleanQuery.contains("current") || cleanQuery.contains("power") || cleanQuery.contains("ledhu") || cleanQuery.contains("ledu")) {
                        responseText = "aa area sir?"
                        conversationStep = 2
                    } else {
                        responseText = "oka 2 minutues adagandee me call maa oka substation ke kaluputhamuu valle kee chanpandee ne oka samasyaa."
                        shouldForward = true
                    }
                }
                else if (conversationStep == 2) {
                    var area = ""
                    if (cleanQuery.contains("ramanapet")) area = "Ramanapet"
                    else if (cleanQuery.contains("cherlapally") || cleanQuery.contains("charla")) area = "Cherlapally"
                    else if (cleanQuery.contains("siddipet")) area = "Siddipet"
                    else if (cleanQuery.contains("narketpally")) area = "Narketpally"

                    if (area.isNotEmpty()) {
                        detectedArea = area
                        val res = withContext(Dispatchers.IO) {
                            apiService.postConsumerQuery(ConsumerQueryRequest(area = area, query = query))
                        }
                        responseText = res.response
                        shouldForward = res.forwarded
                        conversationStep = 3
                    } else {
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
                    if (cleanQuery.contains("tim") || cleanQuery.contains("time") || cleanQuery.contains("eta") || cleanQuery.contains("eppudu") || cleanQuery.contains("ganta") || cleanQuery.contains("hour") || cleanQuery.contains("minute")) {
                        if (detectedArea.isNotEmpty()) {
                            val res = withContext(Dispatchers.IO) {
                                apiService.postConsumerQuery(ConsumerQueryRequest(area = detectedArea, query = query))
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

                callTranscript.add("AI Assistant" to responseText)

                speakText(responseText) {
                    if (shouldForward) {
                        performCallTransfer(forwardNumber)
                    } else {
                        listenToCaller()
                    }
                }
            } catch (e: Exception) {
                Log.e("CallScreeningService", "Error query API: ${e.message}")
                handleForwardingFallback(forwardNumber)
            }
        }
    }

    private fun handleForwardingFallback(forwardNumber: String) {
        val errorMsg = "Substation network loading error. Operator connection standby."
        callTranscript.add("AI Assistant" to errorMsg)
        speakText(errorMsg) {
            performCallTransfer(forwardNumber)
        }
    }

    private fun performCallTransfer(forwardNumber: String) {
        isForwarded = true
        
        // Ring alert
        try {
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(applicationContext, notificationUri)
            ringtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Restore default earpiece audio routing so phone behaves normally during lineman talk
        setSpeakerphone(false)

        serviceScope.launch {
            delay(1500)
            try {
                if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$forwardNumber")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(callIntent)
                    Log.d("CallScreeningService", "Programmatic call forwarding to $forwardNumber initiated.")
                } else {
                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$forwardNumber")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(dialIntent)
                    Log.d("CallScreeningService", "Fallback call dial initiated.")
                }
            } catch (e: Exception) {
                Log.e("CallScreeningService", "Failed to forward call: ${e.message}")
            }
            
            // End background screening and upload logs, call status as forwarded
            stopForegroundService("Forwarded to Substation")
        }
    }

    private fun stopForegroundService(callStatus: String = "Answered by AI") {
        isCallActive = false
        stopRecording()

        // Disable SharedPreferences active screening state
        val sharedPrefs = getSharedPreferences("TGSPDCL_PREFS", Context.MODE_PRIVATE)
        sharedPrefs.edit().putBoolean("is_ai_screening_active", false).apply()

        // Turn off speakerphone & restore audio mode
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {}
        setSpeakerphone(false)

        // Disable speech components
        speechRecognizer?.stopListening()

        // Upload call log with audio file
        val host = sharedPrefs.getString("server_host", "10.0.2.2:8000") ?: "10.0.2.2:8000"
        val apiService = getApiService(host)
        val transcriptText = callTranscript.joinToString("\n") { "${it.first}: ${it.second}" }

        if (apiService != null) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val callerNumPart = callerNumber.toRequestBody("text/plain".toMediaTypeOrNull())
                    val transcriptPart = transcriptText.toRequestBody("text/plain".toMediaTypeOrNull())
                    val statusPart = callStatus.toRequestBody("text/plain".toMediaTypeOrNull())

                    var filePart: MultipartBody.Part? = null
                    val recFile = audioFile
                    if (recFile != null && recFile.exists() && recFile.length() > 0) {
                        val fileReq = recFile.asRequestBody("audio/mp4".toMediaTypeOrNull())
                        filePart = MultipartBody.Part.createFormData("file", recFile.name, fileReq)
                    }

                    val response = apiService.uploadCallLog(
                        callerNumber = callerNumPart,
                        transcript = transcriptPart,
                        status = statusPart,
                        file = filePart
                    )
                    Log.d("CallScreeningService", "Call log successfully uploaded: $response")
                } catch (e: Exception) {
                    Log.e("CallScreeningService", "Call log upload failed: ${e.message}", e)
                } finally {
                    withContext(Dispatchers.Main) {
                        stopSelf()
                    }
                }
            }
        } else {
            stopSelf()
        }
    }

    private fun getApiService(host: String): TgspdclApiService? {
        var cleanHost = host.trim()
        if (cleanHost.startsWith("http://")) cleanHost = cleanHost.substring(7)
        else if (cleanHost.startsWith("https://")) cleanHost = cleanHost.substring(8)
        if (cleanHost.endsWith("/")) cleanHost = cleanHost.substring(0, cleanHost.length - 1)
        val protocol = if (cleanHost.contains("localhost") || cleanHost.contains("10.0.2.2") || cleanHost.contains("192.168.")) "http" else "https"

        val logger = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(logger)
            .build()

        return try {
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Call Screening Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress notifications for automated call screening."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("te", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
            
            // Set audio attributes so TTS plays into the active voice communication stream!
            try {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)
                Log.d("CallScreeningService", "TTS AudioAttributes configured for VOICE_COMMUNICATION")
            } catch (e: Exception) {
                Log.e("CallScreeningService", "Failed to set TTS AudioAttributes: ${e.message}")
            }

            isTtsInitialized = true
            // If call started and we have only logged the intercepted event, play greeting now!
            if (isCallActive && callTranscript.size == 1) {
                triggerGreeting()
            }
        } else {
            Log.e("CallScreeningService", "TTS Initialization Failed")
        }
    }

    override fun onDestroy() {
        Log.d("CallScreeningService", "Service Destroyed")
        serviceScope.cancel()
        
        try {
            stopRecording()
        } catch (e: Exception) {}

        try {
            tts?.shutdown()
        } catch (e: Exception) {}

        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {}

        super.onDestroy()
    }
}
