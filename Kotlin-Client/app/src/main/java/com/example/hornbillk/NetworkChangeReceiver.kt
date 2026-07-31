// In file: app/src/main/java/com/hornbill.k/NetworkChangeReceiver.kt
// Change this at line 2:
package com.example.hornbillk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log

class NetworkChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            return
        }

        if (intent.action == ConnectivityManager.CONNECTIVITY_ACTION) {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            var isConnected = false

            // --- API Level Check for Network Status ---
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // API Level 23 (Marshmallow) and above
                val activeNetwork = connectivityManager.activeNetwork // This requires API 23
                if (activeNetwork != null) {
                    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                    isConnected = capabilities != null &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
            } else { // API Level 22 and below (compatible with minSdk 21)
                @Suppress("DEPRECATION") // Suppress deprecation warning for activeNetworkInfo
                val activeNetworkInfo = connectivityManager.activeNetworkInfo
                isConnected = activeNetworkInfo != null && activeNetworkInfo.isConnectedOrConnecting
            }
            // --- END API Level Check ---

            Log.d("NetworkReceiver", "Network state changed. Connected: $isConnected")

            if (isConnected) {
                val ipCheckIntent = Intent(Constants.IP_CHECK_BROADCAST_ACTION)
                context.sendBroadcast(ipCheckIntent)
                Log.d("NetworkReceiver", "Sent ACTION_CHECK_IP broadcast.")
            }
        }
    }
}