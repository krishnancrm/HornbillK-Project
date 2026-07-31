package com.example.hornbillk

import android.content.Context
import android.content.IntentFilter
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AppMonitor(
    private val context: Context,
    private val dataRepository: DataRepository,
    private val networkService: NetworkService,
    private val coroutineScope: LifecycleCoroutineScope,
    private val onUiUpdate: (message: String, isError: Boolean) -> Unit,
    private val onImageUpdate: (cameraIndex: Int, imageData: ByteArray) -> Unit,
    private val onAnimalUpdate: (animals: List<String>) -> Unit,
    private val onSliderAlertUpdate: (isAlert: Boolean) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    private var pollingJob: Job? = null
    private var connectionJobs = ConcurrentHashMap<String, Job>()
    private val smsReceiver = SmsReceiver()

    // ⚡ Track the currently active media player so we can stop it on demand
    private var activeMediaPlayer: MediaPlayer? = null

    fun init() {
        Log.d("AppMonitor", "Initializing app monitor")
    }

    fun startAllJobs() {
        if (isMonitoring) {
            Log.d("AppMonitor", "Jobs already started.")
            return
        }
        isMonitoring = true
        startImagePolling()
        Log.d("AppMonitor", "All jobs started.")
    }

    fun stopAllJobs() {
        if (!isMonitoring) {
            Log.d("AppMonitor", "Jobs already stopped.")
            return
        }
        isMonitoring = false
        pollingJob?.cancel()
        Log.d("AppMonitor", "All jobs stopped.")
    }

    fun onAlertReceived(animalName: String) {
        handler.post {
            playAnimalScream(animalName)
        }
    }

    // ⚡ METHOD TO STOP SOUND EARLY WHEN DISMISSED
    fun stopAlert() {
        handler.post {
            try {
                activeMediaPlayer?.stop()
                activeMediaPlayer?.release()
                activeMediaPlayer = null
                Log.d("AppMonitor", "Active alert sound stopped and released.")
            } catch (e: Exception) {
                Log.e("AppMonitor", "Error stopping media player: ${e.message}")
            }
        }
    }

    private fun playAnimalScream(animalName: String) {
        val resourceId = context.resources.getIdentifier(
            animalName,
            "raw",
            context.packageName
        )

        if (resourceId != 0) {
            try {
                // ⚡ Stop any currently playing sound before starting a new one
                activeMediaPlayer?.stop()
                activeMediaPlayer?.release()

                activeMediaPlayer = MediaPlayer.create(context, resourceId).apply {
                    start()
                    setOnCompletionListener { mp ->
                        mp.release()
                        if (activeMediaPlayer == mp) {
                            activeMediaPlayer = null
                        }
                    }
                }

                Log.d("AppMonitor", "Playing sound for $animalName from resource ID: $resourceId")
                onUiUpdate("Alert! $animalName detected!", false)
            } catch (e: Exception) {
                Log.e("AppMonitor", "Failed to play sound for $animalName: ${e.message}", e)
            }
        } else {
            Log.e("AppMonitor", "No raw resource found for animal: $animalName")
            onUiUpdate("Alert! $animalName detected, but no sound file found.", false)
        }
    }

    fun registerReceiver() {
        val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        context.registerReceiver(smsReceiver, filter)
    }

    fun unregisterReceiver() {
        context.unregisterReceiver(smsReceiver)
    }

    fun checkAndSendCurrentIp(alias: String, simNumber: String) {
        coroutineScope.launch(Dispatchers.IO) {
            val ipv4 = networkService.getPublicIpV4()
            val ipv6 = networkService.getPublicIpV6()

            if (ipv4 != null && ipv6 != null) {
                val message = "My IP is v4:$ipv4, v6:$ipv6"
                sendSms(simNumber, message)
            } else {
                onUiUpdate("Could not get public IP addresses.", true)
            }
        }
    }

    private fun startImagePolling() {
        dataRepository.loadSavedConnections()
        val firstAlias = dataRepository.displayAliases.firstOrNull()
        val currentConfig = if (firstAlias != null) dataRepository.getConnection(firstAlias) else null

        if (currentConfig != null) {
            pollingJob?.cancel()
            pollingJob = coroutineScope.launch(Dispatchers.IO) {
                Log.d("AppMonitor", "Polling started for ${currentConfig.alias}")

                while (isMonitoring) {
                    try {
                        for (i in 0..3) {
                            val bytes = networkService.loadImageFromPi(i, currentConfig)
                            if (bytes != null && bytes.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    onImageUpdate(i, bytes)
                                }
                            }
                        }

                        val waitTime = (currentConfig.interval * 1000).toLong()
                        delay(if (waitTime < 500) 5000 else waitTime)

                    } catch (e: Exception) {
                        Log.e("AppMonitor", "Polling error: ${e.message}")
                        delay(5000)
                    }
                }
            }
        } else {
            Log.d("AppMonitor", "No connection config found. Polling skipped.")
        }
    }

    fun cleanup() {
        Log.d("AppMonitor", "Cleaning up monitor")
        pollingJob?.cancel()
        connectionJobs.values.forEach { it.cancel() }
        stopAlert()
    }

    private fun sendSms(phoneNumber: String, message: String) {
        try {
            val smsManager = android.telephony.SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            Log.d("AppMonitor", "SMS sent to $phoneNumber")
        } catch (e: Exception) {
            Log.e("AppMonitor", "Failed to send SMS: ${e.message}", e)
        }
    }

    data class DetectedAnimal(val name: String, val timestamp: Long)
}