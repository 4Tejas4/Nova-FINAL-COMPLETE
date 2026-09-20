package com.nova.ai

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Card-based settings screen matching the reference design. */
class SettingsActivity : AppCompatActivity() {
    companion object {
        const val LOCAL_PROVIDER = "Local LLM"
        const val OPENROUTER_PROVIDER = LOCAL_PROVIDER
        const val GEMINI_PROVIDER = LOCAL_PROVIDER
        const val OPENAI_PROVIDER = LOCAL_PROVIDER
        const val CLAUDE_PROVIDER = LOCAL_PROVIDER
        const val CUSTOM_PROVIDER = LOCAL_PROVIDER
        fun isLocalProvider(context: android.content.Context) = true
        fun getApiKey(context: android.content.Context) = ""
        fun getProvider(context: android.content.Context) = LOCAL_PROVIDER
        fun getEndpoint(context: android.content.Context) = ""
        fun getModelName(context: android.content.Context) = LocalModelManager.MODEL_NAME
        fun getMusicPackage(context: android.content.Context): String = context.getSharedPreferences("NovaPrefs", 0).getString("music_app_package", "").orEmpty()
        fun saveMusicPackage(context: android.content.Context, pkg: String) { context.getSharedPreferences("NovaPrefs", 0).edit().putString("music_app_package", pkg).apply() }
        fun saveSettings(context: android.content.Context, apiKey: String, provider: String, endpoint: String, modelName: String) {}
    }

    private lateinit var status: TextView
    private lateinit var musicSpinner: Spinner
    private lateinit var musicAccess: Button
    private val PICK_MODEL = 700

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        AppContext.init(this)

        status = findViewById(R.id.modelStatusText)
        musicSpinner = findViewById(R.id.musicAppSpinner)
        musicAccess = findViewById(R.id.musicAccessButton)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.searchButton).setOnClickListener {
            Toast.makeText(this, "Search inside settings is coming soon", Toast.LENGTH_SHORT).show()
        }

        val version = try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            "v${pi.versionName} · local-offline"
        } catch (_: Exception) { "local-offline" }
        findViewById<TextView>(R.id.versionText).text = version
        findViewById<TextView>(R.id.aboutText).text =
            "Nova $version\n\nAn offline voice assistant: Vosk wake word and speech recognition, " +
            "llama.cpp on-device inference (Qwen GGUF), and the Nova agent executor.\n\n" +
            "Credits: llama.cpp (ggml-org), Vosk (Alpha Cephei), Qwen (Alibaba Cloud)."

        // Expandable rows
        fun toggle(rowId: Int, panelId: Int) {
            findViewById<View>(rowId).setOnClickListener {
                val panel = findViewById<View>(panelId)
                panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        toggle(R.id.rowAi, R.id.panelAi)
        toggle(R.id.rowSpeech, R.id.panelSpeech)
        toggle(R.id.rowVoice, R.id.panelVoice)
        toggle(R.id.rowAppearance, R.id.panelAppearance)
        toggle(R.id.rowQuick, R.id.panelQuick)
        toggle(R.id.rowPrivacy, R.id.panelPrivacy)
        toggle(R.id.rowAbout, R.id.panelAbout)

        // AI & Model panel
        findViewById<Button>(R.id.btnDownloadModel).setOnClickListener { downloadModel() }
        findViewById<Button>(R.id.btnLoadGguf).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/octet-stream"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, PICK_MODEL)
        }

        // Appearance
        val reduceFx = findViewById<CheckBox>(R.id.reduceFx)
        val prefs = getSharedPreferences("NovaPrefs", 0)
        reduceFx.isChecked = prefs.getBoolean("reduce_fx", false)
        reduceFx.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("reduce_fx", checked).apply() }

        // Quick Tasks
        populateMusicApps()

        // Privacy
        findViewById<Button>(R.id.btnSecurityReport).setOnClickListener {
            val report = try { NovaSecurityAudit(this).report() } catch (e: Exception) { "Report unavailable: ${e.message}" }
            AlertDialog.Builder(this).setTitle("Security report").setMessage(report).setPositiveButton("OK", null).show()
        }
        findViewById<Button>(R.id.btnClearChat).setOnClickListener {
            ConversationManager(this).clearConversation()
            Toast.makeText(this, "Conversation cleared", Toast.LENGTH_SHORT).show()
        }

        refreshModelStatus()
    }

    private fun refreshModelStatus() {
        status.text = if (LocalModelManager.isInstalled(this))
            "LOCAL MODEL: INSTALLED • ${LocalModelManager.modelFile(this).length() / 1024 / 1024} MB"
        else
            "LOCAL MODEL: NOT INSTALLED • Tap Download below."
    }

    private fun downloadModel() {
        findViewById<Button>(R.id.btnDownloadModel).isEnabled = false
        status.text = "DOWNLOADING LOCAL AI MODEL…"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    LocalModelManager.download(this@SettingsActivity) { p ->
                        runOnUiThread { status.text = "DOWNLOADING LOCAL AI MODEL… $p%" }
                    }
                }
                LocalAIEngine.shutdown()
                refreshModelStatus()
                Toast.makeText(this@SettingsActivity, "Local AI model installed", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                status.text = "MODEL ERROR: ${e.message}"
                Toast.makeText(this@SettingsActivity, e.message ?: "Model download failed", Toast.LENGTH_LONG).show()
            } finally {
                findViewById<Button>(R.id.btnDownloadModel).isEnabled = true
            }
        }
    }

    private fun populateMusicApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }.sortedBy { it.first.lowercase() }
        musicSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, apps.map { it.first })
        val selected = getMusicPackage(this)
        val idx = apps.indexOfFirst { it.second == selected }
        if (idx >= 0) musicSpinner.setSelection(idx)
        musicSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (pos in apps.indices) saveMusicPackage(this@SettingsActivity, apps[pos].second)
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        musicAccess.text = if (MusicController.isNotificationAccessEnabled(this)) "MUSIC CONTROL ACCESS: ON" else "ENABLE MUSIC CONTROL ACCESS"
        musicAccess.setOnClickListener { MusicController.openNotificationAccessSettings(this) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_MODEL && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { LocalModelManager.copyFromUri(this@SettingsActivity, uri) }
                    LocalAIEngine.shutdown()
                    refreshModelStatus()
                    Toast.makeText(this@SettingsActivity, "GGUF model loaded", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@SettingsActivity, e.message ?: "Could not load model", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
