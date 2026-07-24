package com.smstotelegram

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Clean, bright main activity.
 *
 * Features:
 * - Status card with icon and description
 * - Forwarding toggle, test message
 * - Settings dialog: credentials, blacklist, battery optimization, message template, SIM naming
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusIcon: TextView
    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView
    private lateinit var statusDetail: TextView
    private lateinit var credStatus: TextView
    private lateinit var toggleButton: Button
    private lateinit var testMessageButton: Button
    private lateinit var settingsButton: Button
    private lateinit var requestPermissionButton: Button
    private lateinit var statusCard: View

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                updateUI(true)
                startForegroundService()
            } else {
                updateUI(false)
                val denied = permissions.filter { !it.value }.keys
                if (denied.any { shouldShowRequestPermissionRationale(it) }) {
                    Snackbar.make(findViewById(android.R.id.content), "SMS permission required", Snackbar.LENGTH_INDEFINITE)
                        .setAction("Grant") { requestSmsPermissions() }.show()
                }
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startForegroundService()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        TelegramSender.init(this)
        ForwardingManager.init(this)

        statusIcon = findViewById(R.id.status_icon)
        statusText = findViewById(R.id.status_text)
        statusSub = findViewById(R.id.status_sub)
        statusDetail = findViewById(R.id.status_detail)
        credStatus = findViewById(R.id.cred_status)
        toggleButton = findViewById(R.id.toggle_button)
        testMessageButton = findViewById(R.id.test_message_button)
        settingsButton = findViewById(R.id.settings_button)
        requestPermissionButton = findViewById(R.id.request_permission_button)
        statusCard = findViewById(R.id.status_card)

        settingsButton.setOnClickListener { showSettingsDialog() }
        requestPermissionButton.setOnClickListener { requestSmsPermissions() }
        toggleButton.setOnClickListener { toggleForwarding() }
        testMessageButton.setOnClickListener { sendTestMessage() }

        checkAndRequestPermissions()
        updateCredStatus()
        refreshUI()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    private fun checkAndRequestPermissions() {
        if (hasSmsPermissions()) {
            updateUI(true)
            startForegroundService()
        } else {
            updateUI(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Snackbar.make(findViewById(android.R.id.content), "SMS permission required", Snackbar.LENGTH_INDEFINITE)
                    .setAction("Grant") { requestSmsPermissions() }.show()
            } else {
                requestSmsPermissions()
            }
        }
    }

    private fun hasSmsPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    private fun requestSmsPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.READ_SMS)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun updateUI(granted: Boolean) {
        toggleButton.visibility = if (granted) View.VISIBLE else View.GONE
        testMessageButton.visibility = if (granted) View.VISIBLE else View.GONE
        requestPermissionButton.visibility = if (granted) View.GONE else View.VISIBLE
        statusCard.visibility = View.VISIBLE

        if (granted) {
            val enabled = ForwardingManager.isEnabled()
            statusIcon.text = if (enabled) "\u2705" else "\u23F8"
            statusText.text = if (enabled) "Forwarding Active" else "Paused"
            statusDetail.text = if (enabled) "All SMS sent to Telegram" else "Tap Resume to continue"
            statusSub.text = if (enabled) "Forwarding is active" else "Forwarding is paused"
            toggleButton.text = if (enabled) "Pause" else "Resume"
        } else {
            statusIcon.text = "\u26D4"
            statusText.text = "No Permission"
            statusDetail.text = "Grant SMS permission to enable"
            statusSub.text = "Permission required"
        }
    }

    private fun updateCredStatus() {
        credStatus.text = if (TelegramSender.hasCredentials())
            "Bot configured" else "Not configured"
    }

    private fun refreshUI() {
        val hasPerm = hasSmsPermissions()
        updateUI(hasPerm)
        updateCredStatus()
    }

    private fun toggleForwarding() {
        val enabled = ForwardingManager.toggle()
        val msg = if (enabled) "Forwarding Resumed" else "Forwarding Paused"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        updateUI(hasSmsPermissions())
    }

    private fun sendTestMessage() {
        if (!TelegramSender.hasCredentials()) {
            Toast.makeText(this, "Configure credentials first", Toast.LENGTH_SHORT).show()
            return
        }

        // Show dialog to input simulated SMS sender, body, and SIM slot
        val inputLayout = LayoutInflater.from(this).inflate(R.layout.dialog_emulate_sms, null)
        val senderInput = inputLayout.findViewById<TextInputEditText>(R.id.emulate_sender_input)
        val bodyInput = inputLayout.findViewById<TextInputEditText>(R.id.emulate_body_input)
        val simGroup = inputLayout.findViewById<android.widget.RadioGroup>(R.id.emulate_sim_group)

        AlertDialog.Builder(this, R.style.Theme_SmsToTelegram)
            .setTitle("Test SMS")
            .setMessage("Simulate an incoming SMS to test the full forwarding pipeline")
            .setView(inputLayout, 24, 8, 24, 8)
            .setPositiveButton("Send") { _, _ ->
                val sender = senderInput.text?.toString()?.trim() ?: ""
                val body = bodyInput.text?.toString()?.trim() ?: ""
                if (sender.isEmpty() || body.isEmpty()) {
                    Toast.makeText(this, "Both sender and message are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Determine SIM slot from radio button selection
                val slotIndex: Int? = when (simGroup.checkedRadioButtonId) {
                    R.id.emulate_sim1 -> 0
                    R.id.emulate_sim2 -> 1
                    else -> null // Unknown
                }

                Toast.makeText(this, "Sending test SMS...", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        TelegramSender.sendEmulatedSms(sender, body, slotIndex)
                    }
                    val msg = when {
                        result.skipped -> "Skipped: ${result.reason}"
                        result.success -> "Test SMS sent! (daily count: ${ForwardingManager.getDailyCount()})"
                        else -> "Failed: ${result.reason}"
                    }
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        val serviceIntent = Intent(this, SmsForwarderService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun showSettingsDialog() {
        val builder = AlertDialog.Builder(this, R.style.Theme_SmsToTelegram)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        builder.setView(view)
        builder.setCancelable(true)

        val dialog = builder.create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // --- Telegram section ---
        val botTokenInput = view.findViewById<TextInputEditText>(R.id.bot_token_input)
        val chatIdInput = view.findViewById<TextInputEditText>(R.id.chat_id_input)
        val templateInput = view.findViewById<TextInputEditText>(R.id.message_template_input)
        val saveButton = view.findViewById<Button>(R.id.save_credentials_button)
        val closeButton = view.findViewById<TextView>(R.id.close_button)
        val resetTemplateButton = view.findViewById<Button>(R.id.reset_template_button)

        // SIM name inputs
        val sim1NameInput = view.findViewById<TextInputEditText>(R.id.sim1_name_input)
        val sim2NameInput = view.findViewById<TextInputEditText>(R.id.sim2_name_input)

        // Heartbeat switch
        val heartbeatSwitch = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.heartbeat_switch)
        heartbeatSwitch.isChecked = ForwardingManager.isHeartbeatEnabled()

        TelegramSender.getBotToken()?.let { botTokenInput.setText(it) }
        TelegramSender.getChatId()?.let { chatIdInput.setText(it) }
        templateInput.setText(TelegramSender.getMessageTemplate())

        // Load custom SIM names
        ForwardingManager.getSim1CustomName()?.let { sim1NameInput.setText(it) }
        ForwardingManager.getSim2CustomName()?.let { sim2NameInput.setText(it) }

        closeButton.setOnClickListener { dialog.dismiss() }

        // Reset template to default
        resetTemplateButton.setOnClickListener {
            templateInput.setText(TelegramSender.getDefaultTemplate())
        }

        // --- Battery optimization section ---
        val batteryOptButton = view.findViewById<Button>(R.id.battery_opt_button)
        updateBatteryButtonText(batteryOptButton)

        batteryOptButton.setOnClickListener { requestBatteryOptimization() }

        // --- Save button ---
        saveButton.setOnClickListener {
            val token = botTokenInput.text?.toString()?.trim() ?: ""
            val chatId = chatIdInput.text?.toString()?.trim() ?: ""
            val template = templateInput.text?.toString()?.trim() ?: ""

            var hasError = false
            if (token.isEmpty()) {
                botTokenInput.error = "Required"
                hasError = true
            } else {
                botTokenInput.error = null
            }
            if (chatId.isEmpty()) {
                chatIdInput.error = "Required"
                hasError = true
            } else {
                chatIdInput.error = null
            }
            if (hasError) return@setOnClickListener

            TelegramSender.saveCredentials(this, token, chatId)
            if (template.isNotEmpty()) {
                TelegramSender.saveMessageTemplate(this, template)
            }

            // Save custom SIM names
            val sim1Name = sim1NameInput.text?.toString()?.trim() ?: ""
            val sim2Name = sim2NameInput.text?.toString()?.trim() ?: ""
            ForwardingManager.saveSim1CustomName(sim1Name)
            ForwardingManager.saveSim2CustomName(sim2Name)

            // Save heartbeat setting
            ForwardingManager.setHeartbeatEnabled(heartbeatSwitch.isChecked)

            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            updateCredStatus()
            refreshUI()
            dialog.dismiss()
        }

        dialog.show()
    }

    // ---- Battery optimization ----

    private fun updateBatteryButtonText(button: Button) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                button.text = "Already Disabled \u2705"
                button.isEnabled = false
            } else {
                button.text = "Request"
                button.isEnabled = true
            }
        } else {
            button.text = "Not needed \u2705"
            button.isEnabled = false
        }
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback: open system battery settings
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                try {
                    startActivity(fallbackIntent)
                } catch (e2: Exception) {
                    Toast.makeText(this, "Manually disable battery optimization in system settings", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}