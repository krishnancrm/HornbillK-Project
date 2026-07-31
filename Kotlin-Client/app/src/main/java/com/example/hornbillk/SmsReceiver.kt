package com.example.hornbillk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.app.NotificationCompat

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            val bundle = intent.extras
            if (bundle != null) {
                val pdus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bundle.getSerializable("pdus") as? Array<*>
                } else {
                    @Suppress("DEPRECATION")
                    bundle.get("pdus") as? Array<*>
                }

                if (pdus != null) {
                    val format = bundle.getString("format")

                    // ⚡ FIX 1: STITCH MULTIPART MESSAGES TOGETHER
                    var fullMessageBody = ""
                    var senderPhoneNumber = ""

                    for (pdu in pdus) {
                        val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            SmsMessage.createFromPdu(pdu as ByteArray, format)
                        } else {
                            @Suppress("DEPRECATION")
                            SmsMessage.createFromPdu(pdu as ByteArray)
                        }

                        fullMessageBody += message?.messageBody ?: ""
                        if (senderPhoneNumber.isBlank()) {
                            senderPhoneNumber = message?.originatingAddress ?: ""
                        }
                    }

                    Log.d("SmsReceiver", "Full SMS Assembled from $senderPhoneNumber: $fullMessageBody")

                    if (fullMessageBody.contains("ALERT!", ignoreCase = true)) {
                        val detectedAnimal = extractAnimalName(fullMessageBody)
                        var cameraIndex = extractCameraIndex(fullMessageBody)

                        if (detectedAnimal != null) {
                            if (cameraIndex == -1) {
                                cameraIndex = 3
                            }

                            // ⚡ NEW 1: GRAB WAKELOCK TO FORCE CPU AWAKE
                            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                            val wakeLock = powerManager.newWakeLock(
                                PowerManager.PARTIAL_WAKE_LOCK,
                                "HornbillK::SmsWakeLock"
                            )
                            wakeLock.acquire(5000L) // Hold CPU awake for 5 seconds

                            // ⚡ UPDATE GLOBAL STATE BUS
                            HornbillkApp.isAlertActive = true
                            HornbillkApp.activeAnimalName = detectedAnimal
                            HornbillkApp.activeCameraIndex = cameraIndex
                            HornbillkApp.activePiPhone = senderPhoneNumber

                            // ⚡ PERSISTENT STORAGE WITH TIMESTAMP INCLUDED
                            val prefs = context.getSharedPreferences("HornbillkPrefs", Context.MODE_PRIVATE)
                            prefs.edit().apply {
                                putBoolean("PREF_ALERT_ACTIVE", true)
                                putString("PREF_ANIMAL_NAME", detectedAnimal)
                                putInt("PREF_CAM_INDEX", cameraIndex)
                                putString("PREF_PI_PHONE", senderPhoneNumber)
                                putLong("PREF_ALERT_TIMESTAMP", System.currentTimeMillis())
                                apply()
                            }

                            // ⚡ PACK LAUNCH INTENT
                            val launchIntent = Intent(context, MainActivity::class.java).apply {
                                action = "com.example.hornbillk.ACTION_WILDLIFE_ALERT"
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                putExtra("ALERT_TRIGGER", true)
                                putExtra("ANIMAL_NAME", detectedAnimal)
                                putExtra("CAMERA_INDEX", cameraIndex)
                                putExtra(Constants.EXTRA_PI_PHONE_NUMBER, senderPhoneNumber) // Ensure you have a Constants.kt or replace with "PI_PHONE_NUMBER"
                            }

                            val isAlertTriggered = launchIntent.getBooleanExtra("ALERT_TRIGGER", false)
                            Log.d("SmsReceiver", "Alert parsed: $detectedAnimal on Cam $cameraIndex | TRIGGER: $isAlertTriggered")

                            val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            } else {
                                PendingIntent.FLAG_UPDATE_CURRENT
                            }

                            val fullScreenPendingIntent = PendingIntent.getActivity(
                                context,
                                System.currentTimeMillis().toInt(),
                                launchIntent,
                                flag
                            )

                            // ⚡ FIRE NOTIFICATION TO WAKE DEVICE & FORCE POPUP
                            triggerFullScreenNotification(context, detectedAnimal, cameraIndex, fullScreenPendingIntent)

                            // ⚡ NEW 3: DIRECT LAUNCH OVERRIDE
                            try {
                                context.startActivity(launchIntent)
                                Log.d("SmsReceiver", "Direct launch fired successfully.")
                            } catch (e: Exception) {
                                Log.e("SmsReceiver", "Direct launch blocked by OS. Relying on FullScreenIntent.", e)
                            }

                            logBatteryOptimizationWarning(context)
                        }
                    }
                }
            }
        }
    }

    private fun triggerFullScreenNotification(
        context: Context,
        animal: String,
        cameraIndex: Int,
        pendingIntent: PendingIntent
    ) {
        val channelId = "hornbillk_critical_alerts"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Critical Wildlife Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Forces the screen on when a dangerous target is discovered"
                enableVibration(true)
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ CRITICAL WILDLIFE ALERT")
            .setContentText("${animal.uppercase()} detected on Cam ${cameraIndex + 1}")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)

        notificationManager.notify(1001, builder.build())
    }

    private fun logBatteryOptimizationWarning(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                Log.w("SmsReceiver", "Battery optimizations are active; app may sleep.")
            }
        }
    }

    private fun extractAnimalName(messageBody: String): String? {
        val knownAnimals = listOf(
            "tiger", "bear", "elephant", "leopard", "wild boar",
            "monkey", "peacock", "pig", "dog", "cow",
            "buffalo", "bison", "gaur", "cattle", "bull", "ox"
        )

        for (animal in knownAnimals) {
            if (messageBody.contains(animal, ignoreCase = true)) {
                return if (animal == "wild boar") "wildboar" else animal
            }
        }
        return null
    }

    private fun extractCameraIndex(messageBody: String): Int {
        val regex = Regex("Cam\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val matchResult = regex.find(messageBody)

        return if (matchResult != null) {
            val rawDigits = matchResult.groupValues[1].toInt()
            rawDigits - 1
        } else {
            -1
        }
    }
}