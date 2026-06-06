package com.tgspdcl.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.os.Build

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        Log.d("CallReceiver", "Phone State Changed: $state")

        val sharedPrefs = context.getSharedPreferences("TGSPDCL_PREFS", Context.MODE_PRIVATE)

        if (state == TelephonyManager.EXTRA_STATE_RINGING) {
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            Log.d("CallReceiver", "Ringing: $incomingNumber")

            if (incomingNumber.isNullOrEmpty()) return

            // 1. Check contacts and AI switch
            val inContacts = isNumberInContacts(context, incomingNumber)
            val isAiActive = sharedPrefs.getBoolean("ai_assistant_active", true)

            if (!inContacts && isAiActive) {
                // Save the number
                sharedPrefs.edit()
                    .putString("pending_incoming_number", incomingNumber)
                    .apply()
                Log.d("CallReceiver", "Saved unknown caller: $incomingNumber to pending list.")

                // Silence ringer immediately so phone doesn't ring
                try {
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
                    Log.d("CallReceiver", "Ringer muted for unknown caller.")
                } catch (e: Exception) {
                    Log.e("CallReceiver", "Failed to mute ringer: ${e.message}")
                }

                // Automatically answer the call programmatically
                try {
                    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                    if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                        telecomManager.acceptRingingCall()
                        Log.d("CallReceiver", "Auto-answered ringing call programmatically.")
                    } else {
                        Log.w("CallReceiver", "ANSWER_PHONE_CALLS permission not granted.")
                    }
                } catch (e: Exception) {
                    Log.e("CallReceiver", "Error answering call programmatically: ${e.message}", e)
                }
            } else {
                sharedPrefs.edit().remove("pending_incoming_number").apply()
            }
        } 
        else if (state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
            // Call has been answered!
            val pendingNumber = sharedPrefs.getString("pending_incoming_number", null)
            Log.d("CallReceiver", "Call Answered (OFFHOOK). Pending number: $pendingNumber")

            // Unmute ringer
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
            } catch (e: Exception) {
                Log.e("CallReceiver", "Failed to unmute ringer: ${e.message}")
            }

            if (!pendingNumber.isNullOrEmpty()) {
                // Start CallScreeningService instead of MainActivity
                val serviceIntent = Intent(context, CallScreeningService::class.java).apply {
                    action = "START_SCREENING"
                    putExtra("EXTRA_CALLER_NUMBER", pendingNumber)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d("CallReceiver", "CallScreeningService started for caller: $pendingNumber")
                
                // Clear pending number so we don't trigger twice
                sharedPrefs.edit().remove("pending_incoming_number").apply()
            }
        }
        else if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            // Call hung up
            Log.d("CallReceiver", "Call Hung Up (IDLE). Stopping background service.")
            sharedPrefs.edit().remove("pending_incoming_number").apply()

            // Restore ringer settings
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
            } catch (e: Exception) {
                Log.e("CallReceiver", "Failed to unmute ringer: ${e.message}")
            }

            // Trigger end call in CallScreeningService
            val stopIntent = Intent(context, CallScreeningService::class.java).apply {
                action = "STOP_SCREENING"
            }
            context.startService(stopIntent)
            Log.d("CallReceiver", "CallScreeningService stop signal sent.")
        }
    }

    private fun isNumberInContacts(context: Context, phoneNumber: String): Boolean {
        try {
            if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                Log.w("CallReceiver", "READ_CONTACTS permission not granted, assuming unknown.")
                return false
            }
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val contactName = it.getString(0)
                    Log.d("CallReceiver", "Found contact: $contactName")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e("CallReceiver", "Error querying contacts: ${e.message}", e)
        }
        return false
    }
}
