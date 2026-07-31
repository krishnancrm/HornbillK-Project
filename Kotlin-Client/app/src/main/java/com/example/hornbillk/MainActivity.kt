package com.example.hornbillk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.hornbillk.databinding.ActivityMainBinding
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import android.view.View
import android.telephony.SmsManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dataRepository: DataRepository
    private lateinit var networkService: NetworkService
    private lateinit var appMonitor: AppMonitor

    private var currentServerConfig: ConnectionConfig? = null
    private var areSlidersUnlocked = false
    private val SMS_PERMISSION_REQUEST_CODE = 101

    // --- RESOURCES ---
    private val ANIMAL_PRIORITY_MAP = mapOf(
        "tiger" to R.drawable.tiger,
        "leopard" to R.drawable.leopard,
        "bear" to R.drawable.bear,
        "elephant" to R.drawable.elephant,
        "gaur" to R.drawable.bison,
        "wildboar" to R.drawable.wildboar,
        "monkey" to R.drawable.monkeys,
        "peacock" to R.drawable.peacock,
        "bison" to R.drawable.bison,
        "buffalo" to R.drawable.bison,
        "cow" to R.drawable.bison,
        "cattle" to R.drawable.bison,
        "bull" to R.drawable.bison,
        "ox" to R.drawable.bison
    )
    private val ANIMAL_SOUND_MAP = mapOf(
        "tiger" to R.raw.tiger, "leopard" to R.raw.leopard, "bear" to R.raw.bear,
        "elephant" to R.raw.elephant, "bison" to R.raw.bison, "wildboar" to R.raw.wildboar,
        "monkey" to R.raw.monkey, "peacock" to R.raw.peacock,
        "buffalo" to R.raw.bison, "gaur" to R.raw.bison, "cow" to R.raw.bison, "cattle" to R.raw.bison
    )

    private val ANIMAL_PRIORITY_ORDER = mapOf(
        "tiger" to 1, "leopard" to 2, "bear" to 3,
        "elephant" to 4, "bison" to 5, "wildboar" to 6, "monkey" to 7, "peacock" to 8,
        "buffalo" to 5, "gaur" to 5, "cow" to 5, "cattle" to 5
    )
    private val NO_ANIMAL_PLACEHOLDER = R.drawable.blank_i

    private var activeAlarmPlayer: MediaPlayer? = null

    private val cameraCardViews by lazy {
        mapOf(0 to binding.camera1Card, 1 to binding.camera2Card, 2 to binding.camera3Card, 3 to binding.camera4Card)
    }

    private var alertDismissHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val dismissAlertRunnable = Runnable {
        collapseAlertWindow()
    }

    private val statusTickerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusTickerRunnable = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("HornbillkPrefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("PREF_ALERT_ACTIVE", false)) {
                val animalName = prefs.getString("PREF_ANIMAL_NAME", "Unknown") ?: "Unknown"
                val cameraIndex = prefs.getInt("PREF_CAM_INDEX", 0)

                // Variable is named piPhoneNumber
                val piPhoneNumber = prefs.getString("PREF_PI_PHONE", "") ?: ""

                prefs.edit().clear().apply()

                // ⚡ Passed correctly as piPhoneNumber
                triggerAlertPopupAndScream(animalName, cameraIndex, piPhoneNumber)
            }
            statusTickerHandler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ⚡ 1. HARDWARE SCREEN WAKE FLAGS (Must be before setContentView)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 2. INFLATE LAYOUT
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 3. INITIALIZE CAMERA WINDOW PLACEHOLDERS
        binding.cam1Window.setImageResource(NO_ANIMAL_PLACEHOLDER)
        binding.cam2Window.setImageResource(NO_ANIMAL_PLACEHOLDER)
        binding.cam3Window.setImageResource(NO_ANIMAL_PLACEHOLDER)
        binding.cam4Window.setImageResource(NO_ANIMAL_PLACEHOLDER)

        // 4. SETUP CORE SERVICES
        dataRepository = DataRepository(this)
        networkService = NetworkService()

        val appClass = applicationContext as HornbillkApp
        appClass.appMonitor = AppMonitor(
            context = this,
            dataRepository = dataRepository,
            networkService = networkService,
            coroutineScope = lifecycleScope,
            onUiUpdate = { message, isError -> showToast(message, isError) },
            onImageUpdate = { cameraIndex, imageData -> updateImageDisplay(cameraIndex, imageData) },
            onAnimalUpdate = { animals -> updateCameraDisplays(animals) },
            onSliderAlertUpdate = { isAlert -> updateSliderColors(isAlert) }
        )
        appClass.appMonitor?.init()
        appMonitor = appClass.appMonitor!!

        setupLoginScreen()
        setupMainScreen()

        statusTickerHandler.post(statusTickerRunnable)

        // 5. PERMISSION CHECKS
        checkAndRequestSmsPermission()
        requestUnrestrictedBatteryPermission()

        // Request "Display Over Other Apps" permission if not granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            val overlayIntent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(overlayIntent)
        }

        // ⚡ 6. CLEAN ROUTER WITH INTENT CONSUMPTION TO PREVENT LOGIN LOCKOUT
        val isIntentTriggered = intent?.getBooleanExtra("ALERT_TRIGGER", false) == true

        if (isIntentTriggered) {
            val animalName = intent?.getStringExtra("ANIMAL_NAME") ?: "Unknown"
            val cameraIndex = intent?.getIntExtra("CAMERA_INDEX", 0) ?: 0
            val piPhone = intent?.getStringExtra(Constants.EXTRA_PI_PHONE_NUMBER) ?: ""

            // Consume the intent immediately so rotations/restarts don't trap the UI
            intent?.removeExtra("ALERT_TRIGGER")

            // Trigger the popup, bypass login, and start audio sequence
            triggerAlertPopupAndScream(animalName, cameraIndex, piPhone)
        } else {
            // Normal startup: Force Login layout visible, hide dashboard & alert layers safely
            binding.loginLayout.bringToFront()
            binding.loginLayout.isVisible = true
            binding.mainScrollLayout.isVisible = false
            binding.statusSlidersSection.isVisible = false
            binding.alertLayout.isVisible = false
        }
    }

    private fun requestUnrestrictedBatteryPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(fallbackIntent)
                }
            }
        }
    }
    private fun checkAndRequestSmsPermission() {
        val perms = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), SMS_PERMISSION_REQUEST_CODE)
        }
    }
    private fun promptUserToOpenSettings() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Critical Permission Needed")
        builder.setMessage("Your phone's security is blocking HornbillK from receiving SMS alerts automatically.\n\nPlease tap 'Settings', go to Permissions, and allow SMS access so the app can wake up during an emergency.")
        builder.setCancelable(false)

        builder.setPositiveButton("Settings") { dialog, _ ->
            dialog.dismiss()
            // This intent opens the exact settings page for your app
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            val uri = android.net.Uri.fromParts("package", packageName, null)
            intent.data = uri
            startActivity(intent)
        }

        builder.setNegativeButton("Exit App") { dialog, _ ->
            dialog.dismiss()
            finish() // App cannot function without SMS, close it.
        }

        builder.show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == SMS_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // Permission was granted!
                showToast("SMS Monitoring Active", false)
            } else {
                // System denied it or blocked the popup entirely.
                // Force the user to the settings screen.
                promptUserToOpenSettings()
            }
        }
    }
    // You can remove the 'videoUrl' parameter completely!
    private fun triggerAlertPopupAndScream(animalName: String, cameraIndex: Int, piPhoneNumber: String) {
        try {
            HornbillkApp.activeAnimalName = animalName
            HornbillkApp.activeCameraIndex = cameraIndex
            HornbillkApp.activePiPhone = piPhoneNumber
            HornbillkApp.isAlertActive = true

            // ⚡ OPTION 1: Keep login screen visible, hide dashboard behind it
            binding.loginLayout.visibility = View.VISIBLE
            binding.mainScrollLayout.visibility = View.GONE

            var cleanGifKey = animalName.lowercase().replace(" ", "").replace("-", "").trim()
            if (cleanGifKey == "monkey") cleanGifKey = "monkeys"

            // ⚡ HERE IS YOUR REQUESTED TOAST TO VERIFY THE FILE NAME
            runOnUiThread {
                android.widget.Toast.makeText(this@MainActivity, "Trying to load: $cleanGifKey.gif", android.widget.Toast.LENGTH_LONG).show()
            }

            val drawableResId = resources.getIdentifier(cleanGifKey, "drawable", packageName)

            android.util.Log.d("MainActivity", "Attempting to load GIF. Animal Name: '$animalName' | Clean Key: '$cleanGifKey' | Found ID: $drawableResId")

            // ⚡ ----------------- NEW LINES END ----------------- ⚡

            // 20-minute expiration timer to clear background data safely
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val clearedAnimals = mutableListOf("", "", "", "")
                updateCameraDisplays(clearedAnimals)
                HornbillkApp.isAlertActive = false
                HornbillkApp.activeAnimalName = ""
                HornbillkApp.activePiPhone = ""
            }, 1_200_000L)

            // Bring the floating Alert Popup Overlay to the front over the login screen
            binding.alertLayout.bringToFront()
            binding.alertLayout.visibility = View.VISIBLE

            val safeUrl = "https://pi.hornbillk.com/alert_image/$cameraIndex"
            binding.alertDetailsText.text = "🚨 ALERT! ${animalName.uppercase()} on Cam ${cameraIndex + 1}"

            binding.alertImage.visibility = View.GONE
            binding.alertWebview.visibility = View.VISIBLE

            binding.alertWebview.webViewClient = object : android.webkit.WebViewClient() {
                override fun onReceivedError(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    showConnectionLostError(view)
                }
                override fun onReceivedHttpError(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request?.isForMainFrame == true) {
                        showConnectionLostError(view)
                    }
                }
                private fun showConnectionLostError(view: android.webkit.WebView?) {
                    val htmlData = "<html><body style='background:black;color:red;display:flex;justify-content:center;align-items:center;height:100%;font-family:sans-serif;'><h2>CONNECTION LOST</h2></body></html>"
                    view?.loadData(htmlData, "text/html", "UTF-8")
                }
            }

            binding.alertWebview.webChromeClient = android.webkit.WebChromeClient()
            binding.alertWebview.settings.apply {
                javaScriptEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
            }
            binding.alertWebview.loadUrl(safeUrl)

            // ⚡ RESTORED YOUR AUDIO ALARM BLOCK HERE
            var cleanAudioKey: String? = animalName.lowercase().replace(" ", "").replace("-", "")
            if (cleanAudioKey == "monkey") cleanAudioKey = "monkeys"

            if (!cleanAudioKey.isNullOrEmpty()) {
                val rawResId = resources.getIdentifier(cleanAudioKey, "raw", packageName)
                if (rawResId != 0) {
                    activeAlarmPlayer?.release()
                    activeAlarmPlayer = android.media.MediaPlayer.create(applicationContext, rawResId).apply {
                        isLooping = true
                        start()
                    }
                } else {
                    appMonitor?.onAlertReceived(cleanAudioKey)
                }
            }

            // ⚡ THIS IS YOUR ONLY ACK TRIGGER NOW (Foreground)
            if (piPhoneNumber.isNotBlank()) {
                Log.d("MainActivity", "App is now in foreground. Firing ACK to $piPhoneNumber")
                acknowledgeAlertToPi(piPhoneNumber)
            }

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.setStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC),
                0
            )

            binding.btnDismissAlert.setOnClickListener {
                collapseAlertWindow()
            }

            // 20-second auto-dismiss for popup and scream
            alertDismissHandler.removeCallbacks(dismissAlertRunnable)
            alertDismissHandler.postDelayed(dismissAlertRunnable, 20_000L)

        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error triggering alert popup: ${e.message}", e)
        }
    }

    private fun loadActiveAlertGifOnDashboard() {
        val animalName = HornbillkApp.activeAnimalName
        val cameraIndex = HornbillkApp.activeCameraIndex

        if (animalName.isNullOrBlank() || cameraIndex == -1) {
            android.util.Log.e("MainActivity", "Loader skipped: No active animal name found.")
            return
        }

        val targetCamWindow = when (cameraIndex) {
            0 -> binding.cam1Window
            1 -> binding.cam2Window
            2 -> binding.cam3Window
            3 -> binding.cam4Window
            else -> binding.cam1Window
        }

        // 1. Clean the string to match your map keys
        var cleanName = animalName.lowercase().replace(" ", "").replace("-", "").trim()
        if (cleanName == "monkey") cleanName = "monkeys"

        // ⚡ 2. THE FIX: Grab the exact file directly from your Map!
        val resourceId = ANIMAL_PRIORITY_MAP[cleanName]

        if (resourceId != null) {
            runOnUiThread {
                android.widget.Toast.makeText(this@MainActivity, "Loading $cleanName from Drawable Map!", android.widget.Toast.LENGTH_LONG).show()
            }

            // 3. Load the guaranteed resource ID
            com.bumptech.glide.Glide.with(this@MainActivity)
                .asGif()
                .load(resourceId)
                .override(com.bumptech.glide.request.target.Target.SIZE_ORIGINAL)
                .into(targetCamWindow)

            android.util.Log.d("MainActivity", "Successfully loaded $cleanName GIF from Map.")
        } else {
            // Only falls back to blank if the Pi sends an animal not in your map (like "alien")
            targetCamWindow.setImageResource(NO_ANIMAL_PLACEHOLDER)
            android.util.Log.e("MainActivity", "Map Load Failed: '$cleanName' is not inside ANIMAL_PRIORITY_MAP")
        }
    }
    private fun acknowledgeAlertToPi(phoneNumber: String) {
        // ⚡ 1. THE GATEKEEPER: Prevent the UID 10749 crash
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {

            Log.e("MainActivity", "Gatekeeper stopped the crash! Requesting permission from user now.")

            // Pop up the system box asking for permission
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.SEND_SMS),
                101
            )

            runOnUiThread {
                //Toast.makeText(this, "⚠️ Please grant SMS permission and try again!", Toast.LENGTH_LONG).show()
            }

            return // ⚡ 2. KILL THE FUNCTION HERE. Do not proceed to the SmsManager.
        }

        // 3. If the code makes it down here, the permission is 100% granted. Fire the text.
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java) // ⚡ Removed android.telephony.
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault() // ⚡ Removed android.telephony.
            }

            smsManager.sendTextMessage(phoneNumber, null, "ACK", null, null)
            Log.d("MainActivity", "ACK SMS successfully fired to $phoneNumber")

        } catch (e: SecurityException) {
            // This catches a true, hard OS block
            Log.e("MainActivity", "CRITICAL: OS Hard-Blocked the SMS. Reason: ${e.message}")
            runOnUiThread {
                //Toast.makeText(this, "⚠️ ${e.message}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to send ACK: ${e.message}")
        }
    }

    fun collapseAlertWindow() {
        // 1. Hide the popup overlay and webview
        binding.alertLayout.visibility = android.view.View.GONE
        binding.alertWebview.loadUrl("about:blank") // Stop streaming network calls in background

        // 2. STOP AND RELEASE THE SCREAM AUDIO
        try {
            activeAlarmPlayer?.stop()
            activeAlarmPlayer?.release()
            activeAlarmPlayer = null
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error stopping alarm player: ${e.message}", e)
        }

        HornbillkApp.isAlertActive = false

        // ⚡ 3. CONSUME THE INTENT SO IT NEVER RE-TRIGGERS ON ROTATION/RESTART
        intent?.removeExtra("ALERT_TRIGGER")
        intent?.action = null
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the activity's current intent reference

        val isIntentTriggered = intent.getBooleanExtra("ALERT_TRIGGER", false)

        if (isIntentTriggered) {
            val animalName = intent.getStringExtra("ANIMAL_NAME") ?: "Unknown"
            val cameraIndex = intent.getIntExtra("CAMERA_INDEX", 0)
            val piPhone = intent.getStringExtra(Constants.EXTRA_PI_PHONE_NUMBER) ?: ""

            // Consume the intent so it doesn't re-trigger on configuration changes
            intent.removeExtra("ALERT_TRIGGER")

            // Instantly fire the popup and audio sequence while already logged in
            triggerAlertPopupAndScream(animalName, cameraIndex, piPhone)
        }
    }

    // 1. Keep your setup things here (No alert checks!)
    private fun setupMainScreen() {
        setupSliders()
        setupCameraCards()

        binding.btnSaveCooldown.setOnClickListener {
            val cooldownInput = binding.etCooldownValue.text?.toString()?.toIntOrNull()
            val adminPassword = binding.etAdminPassword.text?.toString() ?: ""
            val activeConfig = networkService.currentServerConfig

            if (cooldownInput == null || cooldownInput < 5 || cooldownInput > 3600) {
                showToast("Please enter a valid cooldown period (5 to 3600 seconds)", true)
                return@setOnClickListener
            }
            if (adminPassword.isEmpty()) {
                showToast("Admin password is required to change server configurations", true)
                return@setOnClickListener
            }
            if (activeConfig == null || activeConfig.cloudflareTunnelUrl.isEmpty()) {
                showToast("No active server configuration loaded", true)
                return@setOnClickListener
            }
            updateServerCooldown(cooldownInput, adminPassword, activeConfig)
        }

        binding.logoutButton.setOnClickListener { logout() }
    }
    private fun setupCameraCards() {
        cameraCardViews.forEach { (index, card) ->
            card.setOnClickListener {
                if (currentServerConfig != null) {
                    showFullscreenCamera(index, currentServerConfig!!)
                } else {
                    showToast("Connect to server first", true)
                    binding.statusSlidersSection.isVisible = !binding.statusSlidersSection.isVisible
                }
            }
        }
    }

    private fun setupSliders() {
        val touchListener = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                if (!areSlidersUnlocked) {
                    showAdminPasswordDialog()
                }
            }
            override fun onStopTrackingTouch(slider: Slider) {
                if (!areSlidersUnlocked) {
                    currentServerConfig?.let {
                        val original = if (slider.id == R.id.sensitivity_slider) it.sensitivity else it.interval
                        slider.value = original.toFloat()
                    }
                }
            }
        }
        binding.sensitivitySlider.addOnSliderTouchListener(touchListener)
        binding.intervalSlider.addOnSliderTouchListener(touchListener)

        val changeListener = { slider: Slider, value: Float, fromUser: Boolean ->
            if (fromUser && areSlidersUnlocked) {
                showToast("Setting updated locally: ${value.toInt()}", false)
            }
        }
        binding.sensitivitySlider.addOnChangeListener { slider, value, fromUser -> changeListener(slider, value, fromUser) }
        binding.intervalSlider.addOnChangeListener { slider, value, fromUser -> changeListener(slider, value, fromUser) }
    }

    private fun showAdminPasswordDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Admin Password")
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val container = LinearLayout(this)
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.setMargins(50, 0, 50, 0)
        input.layoutParams = params
        container.addView(input)
        builder.setView(container)

        builder.setPositiveButton("OK") { dialog, _ ->
            val password = input.text.toString().trim()
            val url = currentServerConfig?.cloudflareTunnelUrl

            if (url != null && password.isNotEmpty()) {
                checkAdminPassword(password, url)
            } else {
                showToast("Not connected", true)
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancel") { d, _ -> d.cancel() }
        builder.show()
    }

    private fun checkAdminPassword(password: String, url: String) {
        showToast("Verifying...", false)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val success = networkService.checkAdminPassword(password, url)
                withContext(Dispatchers.Main) {
                    if (success) {
                        areSlidersUnlocked = true
                        updateSliderColors(true)
                        showToast("Unlocked!", false)
                    } else {
                        showToast("Incorrect Password", true)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showToast("Verification Error: ${e.message}", true) }
            }
        }
    }

    private fun showLocalGifDialog(resourceId: Int, title: String) {
        val dialogView = android.widget.ImageView(this)
        dialogView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        dialogView.adjustViewBounds = true
        Glide.with(this).asGif().load(resourceId).into(dialogView)

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dialogView.setOnClickListener { dialog.dismiss() }
    }

    private fun showFullscreenCamera(index: Int, config: ConnectionConfig) {
        lifecycleScope.launch(Dispatchers.IO) {
            val bytes = networkService.loadImageFromPi(index, config)
            withContext(Dispatchers.Main) {
                if (bytes != null && bytes.isNotEmpty()) {
                    FullscreenImageDialogFragment.newInstance(bytes).show(supportFragmentManager, "full")
                } else {
                    showToast("No image available", true)
                }
            }
        }
    }

    fun stopMonitoringFromDoubleTap() {
        try {
            activeAlarmPlayer?.stop()
            activeAlarmPlayer?.release()
            activeAlarmPlayer = null
        } catch (e: Exception) {
            Log.e("MainActivity", "Error stopping player", e)
        }
        binding.statusSlidersSection.isVisible = true
        showToast("Viewing closed. Monitoring continues.", false)
    }

    private fun updateImageDisplay(idx: Int, data: ByteArray) {
        val view = when(idx) { 0->binding.cam1Window; 1->binding.cam2Window; 2->binding.cam3Window; 3->binding.cam4Window; else->null }
        view?.let { Glide.with(this).load(data).into(it) }
    }

    private fun updateCameraDisplays(animals: List<String>) {
        val views = listOf(binding.cam1Window, binding.cam2Window, binding.cam3Window, binding.cam4Window)
        for (i in views.indices) {
            val animal = animals.getOrNull(i) ?: ""
            if (animal.isNotBlank()) {
                var cleanKey = animal.lowercase().replace(" ", "").replace("-", "")
                if (cleanKey == "monkey") cleanKey = "monkeys"
                val resId = ANIMAL_PRIORITY_MAP[cleanKey] ?: NO_ANIMAL_PLACEHOLDER
                if (resId == NO_ANIMAL_PLACEHOLDER) {
                    Glide.with(this).load(resId).into(views[i])
                } else {
                    Glide.with(this).asGif().load(resId).into(views[i])
                }
            } else {
                Glide.with(this).load(NO_ANIMAL_PLACEHOLDER).into(views[i])
            }
        }
    }

    private fun showToast(msg: String, isError: Boolean) = Toast.makeText(this, msg, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    private fun checkAndRestoreRecentAlert() {
        val prefs = getSharedPreferences("HornbillPrefs", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("LAST_TIME", 0) < 1800000L) {
            val animal = prefs.getString("LAST_ANIMAL", null)
            val idx = prefs.getInt("LAST_CAM", -1)
            if (animal != null && idx != -1) restoreAlertImageOnly(animal, idx)
        }
    }

    private fun restoreAlertImageOnly(animal: String, idx: Int) {
        val cleanKey = animal.lowercase().replace(" ", "").replace("-", "")
        val resId = ANIMAL_PRIORITY_MAP[cleanKey] ?: 0
        if (resId != 0 && idx in 0..3) {
            val view = when(idx) { 0->binding.cam1Window; 1->binding.cam2Window; 2->binding.cam3Window; 3->binding.cam4Window; else->null }
            if (view != null) {
                Glide.with(this).asGif().load(resId).into(view)
                view.setOnClickListener {
                    if (currentServerConfig != null) showFullscreenCamera(idx, currentServerConfig!!)
                    else showLocalGifDialog(resId, animal)
                }
            }
        }
    }

    private fun updateSliderColors(isUnlocked: Boolean) {
        val colorRes = if (isUnlocked) R.color.slider_alert_red else R.color.slider_track_default
        val color = ContextCompat.getColor(this, colorRes)
        binding.sensitivitySlider.setThumbTintList(ColorStateList.valueOf(color))
    }

    private fun logout() {
        currentServerConfig = null
        areSlidersUnlocked = false
        appMonitor.stopAllJobs()
        resetAllCameraImages()
        binding.statusSlidersSection.isVisible = false
        showLoginScreen()
        showToast("Logged out", false)
    }

    private fun resetAllCameraImages() {
        val views = listOf(binding.cam1Window, binding.cam2Window, binding.cam3Window, binding.cam4Window)
        views.forEach { Glide.with(this).clear(it); it.setImageResource(NO_ANIMAL_PLACEHOLDER) }
    }

    private fun setupLoginScreen() {
        dataRepository.loadSavedConnections()
        val aliasAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, dataRepository.displayAliases.toMutableList())
        binding.aliasDropdown.setAdapter(aliasAdapter)
        binding.aliasDropdown.setOnClickListener { binding.aliasDropdown.showDropDown() }
        binding.aliasDropdown.setOnItemClickListener { parent, _, position, _ ->
            val config = dataRepository.getConnection(parent.getItemAtPosition(position).toString())
            config?.let {
                binding.newAliasEntry.setText(it.alias)
                binding.cloudflareTunnelUrlEntry.setText(it.cloudflareTunnelUrl)
                binding.newApiKeyEntry.setText(it.apiKey)
                binding.piM2mSimNumberEntry.setText(it.piM2mSimNumber)
            }
        }
        binding.connectButton.setOnClickListener {
            val alias = binding.newAliasEntry.text.toString()
            val url = binding.cloudflareTunnelUrlEntry.text.toString()
            val key = binding.newApiKeyEntry.text.toString()
            val sim = binding.piM2mSimNumberEntry.text.toString()
            if (alias.isNotEmpty() && url.isNotEmpty() && key.isNotEmpty()) {
                loginToServer(ConnectionConfig(alias, url, key, sim))
            } else {
                showToast("Please fill all fields.", true)
            }
        }
        binding.saveConnectionButton.setOnClickListener {
            val alias = binding.newAliasEntry.text.toString()
            val url = binding.cloudflareTunnelUrlEntry.text.toString()
            val key = binding.newApiKeyEntry.text.toString()
            val sim = binding.piM2mSimNumberEntry.text.toString()

            if (alias.isNotEmpty()) {
                val config = ConnectionConfig(alias, url, key, sim)
                dataRepository.saveConnection(config)
                dataRepository.loadSavedConnections()
                val newAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, dataRepository.displayAliases.toMutableList())
                binding.aliasDropdown.setAdapter(newAdapter)
                showToast("Saved $alias!", false)
            } else {
                showToast("Alias name required to save", true)
            }
        }
        binding.resetLoginButton.setOnClickListener {
            binding.newAliasEntry.text?.clear(); binding.cloudflareTunnelUrlEntry.text?.clear(); binding.newApiKeyEntry.text?.clear(); binding.piM2mSimNumberEntry.text?.clear()
        }
    }

    private fun loginToServer(config: ConnectionConfig) {
        binding.loadingOverlayFrame.isVisible = true
        Glide.with(this).asGif().load(R.drawable.hornbill).into(binding.hornbillLoadingGif)

        val phoneInput = findViewById<EditText>(R.id.client_phone_number_entry)
        val clientPhone = phoneInput?.text?.toString()?.trim() ?: ""

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val success = networkService.login(config, clientPhone)
                withContext(Dispatchers.Main) {
                    binding.loadingOverlayFrame.isVisible = false
                    if (success) {
                        currentServerConfig = config
                        handleSuccessfulLogin(config)
                        showMainScreen()

                        // ⚡ Trigger the GIF only AFTER showMainScreen() makes the dashboard visible
                        if (HornbillkApp.isAlertActive) {
                            loadActiveAlertGifOnDashboard()
                        }

                        if (clientPhone.isNotEmpty()) {
                            showToast("Login & SMS Registration Successful!", false)
                        } else {
                            showToast("Login Successful (View Only mode)", false)
                        }
                    } else {
                        showToast("Login Failed. Registry full or invalid alias.", true)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.loadingOverlayFrame.isVisible = false
                    showToast("Error: ${e.message}", true)
                }
            }
        }
    }

    private fun handleSuccessfulLogin(config: ConnectionConfig) {
        showToast("Connected to ${config.alias}", false)
        binding.sensitivitySlider.value = config.sensitivity
        binding.intervalSlider.value = config.interval.toFloat()
        binding.statusSlidersSection.isVisible = true
        startMonitoringOnPi(config)
    }

    private fun startMonitoringOnPi(config: ConnectionConfig) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = networkService.startMonitor(config.cloudflareTunnelUrl, config.apiKey)
            withContext(Dispatchers.Main) { binding.statusText.text = if (success) "Status: Monitoring" else "Status: Error" }
        }
    }

    private fun showMainScreen() {
        binding.loginLayout.isVisible = false
        binding.mainScrollLayout.isVisible = true
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)

        // ⚡ Only clear the cameras if there is NO active alert
        if (!HornbillkApp.isAlertActive) {
            updateCameraDisplays(emptyList())
        }

        checkAndRestoreRecentAlert()
    }

    private fun showLoginScreen() {
        binding.loginLayout.isVisible = true
        binding.mainScrollLayout.isVisible = false
        resetLoginUi()
        binding.aliasDropdown.text.clear()
        dataRepository.loadSavedConnections()
    }

    private fun resetLoginUi() {
        binding.connectButton.isEnabled = true
        binding.saveConnectionButton.isEnabled = true
        binding.aliasDropdownLayoutParent.isEnabled = true
        binding.newAliasEntry.isEnabled = true
        binding.cloudflareTunnelUrlEntry.isEnabled = true
        binding.newApiKeyEntry.isEnabled = true
        binding.piM2mSimNumberEntry.isEnabled = true
    }

    private fun updateServerCooldown(newValueInSeconds: Int, adminPasswordString: String, config: ConnectionConfig) {
        val url = "https://${config.cloudflareTunnelUrl}/settings/cooldown"
        val jsonPayload = JSONObject().apply {
            put("password", adminPasswordString)
            put("value", newValueInSeconds)
        }
        val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(requestBody).addHeader("X-API-Key", config.apiKey).build()
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { showToast("Network communication error", true) }
            }
            override fun onResponse(call: Call, response: Response) {
                val responseBodyString = response.body?.string()
                val isSuccess = response.isSuccessful && responseBodyString?.contains("\"success\":true") == true
                runOnUiThread {
                    if (isSuccess) {
                        showToast("Server Cooldown updated to $newValueInSeconds seconds successfully!", false)
                        binding.etCooldownValue.text?.clear()
                        binding.etAdminPassword.text?.clear()
                    } else {
                        showToast("Failed to modify server settings: Check password", true)
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()

        // ⚡ This checks your HornbillkApp companion object EVERY TIME this screen becomes visible
        if (HornbillkApp.isAlertActive && !HornbillkApp.activeAnimalName.isNullOrEmpty()) {
            // Since you added activeVideoUrl to your companion object,
            // you will eventually update this function to handle both GIFs and Videos!
            loadActiveAlertGifOnDashboard()
        } else {
            // Optional but recommended: Clear the dashboard UI if there is NO active alert
            // clearDashboardAlerts()
        }
    }
}