package com.nova.ai

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    companion object {
        const val LOCAL_PROVIDER = "Local LLM"
        const val OPENROUTER_PROVIDER = LOCAL_PROVIDER
        const val GEMINI_PROVIDER = LOCAL_PROVIDER
        const val OPENAI_PROVIDER = LOCAL_PROVIDER
        const val CLAUDE_PROVIDER = LOCAL_PROVIDER
        const val CUSTOM_PROVIDER = LOCAL_PROVIDER
        fun isLocalProvider(context: android.content.Context)=true
        fun getApiKey(context: android.content.Context)=""
        fun getProvider(context: android.content.Context)=LOCAL_PROVIDER
        fun getEndpoint(context: android.content.Context)=""
        fun getModelName(context: android.content.Context)=LocalModelManager.MODEL_NAME
        fun getMusicPackage(context: android.content.Context):String=context.getSharedPreferences("NovaPrefs",0).getString("music_app_package","").orEmpty()
        fun saveMusicPackage(context: android.content.Context,pkg:String){context.getSharedPreferences("NovaPrefs",0).edit().putString("music_app_package",pkg).apply()}
        fun saveSettings(context:android.content.Context,apiKey:String,provider:String,endpoint:String,modelName:String){}
    }
    private lateinit var status:TextView
    private lateinit var musicSpinner:Spinner
    private lateinit var musicAccess:Button
    private val PICK_MODEL=700

    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContentView(R.layout.activity_settings)
        status=findViewById(R.id.modelStatusText); musicSpinner=findViewById(R.id.musicAppSpinner); musicAccess=findViewById(R.id.musicAccessButton)
        findViewById<Spinner>(R.id.providerSpinner).apply{adapter=ArrayAdapter(this@SettingsActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("Local AI (on-device)"));isEnabled=false}
        findViewById<EditText>(R.id.apiKeyInput).apply{setText("");isEnabled=false;hint="No API key required"}
        findViewById<EditText>(R.id.endpointInput).apply{setText("");isEnabled=false;hint="No cloud endpoint required"}
        findViewById<EditText>(R.id.modelInput).apply{setText(LocalModelManager.MODEL_NAME);isEnabled=false}
        findViewById<Button>(R.id.saveButton).apply{text="DONE";setOnClickListener{finish()}}
        findViewById<Button>(R.id.loadModelButton).setOnClickListener{
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="application/octet-stream";addCategory(Intent.CATEGORY_OPENABLE)}, PICK_MODEL)
        }
        findViewById<Button>(R.id.testButton).apply{text="DOWNLOAD / LOAD LOCAL AI MODEL";setOnClickListener{downloadModel()}}
        findViewById<Button>(R.id.clearConversationButton).setOnClickListener{ConversationManager(this).clearConversation();Toast.makeText(this,"Conversation cleared",Toast.LENGTH_SHORT).show()}
        populateMusicApps(); refreshModelStatus()
    }
    private fun refreshModelStatus(){status.text=if(LocalModelManager.isInstalled(this))"LOCAL MODEL: INSTALLED • ${LocalModelManager.modelFile(this).length()/1024/1024} MB" else "LOCAL MODEL: NOT INSTALLED • Nova will download it on first setup."}
    private fun downloadModel(){findViewById<Button>(R.id.testButton).isEnabled=false;status.text="DOWNLOADING LOCAL AI MODEL…";lifecycleScope.launch{try{withContext(Dispatchers.IO){LocalModelManager.download(this@SettingsActivity){p->runOnUiThread{status.text="DOWNLOADING LOCAL AI MODEL… $p%"}}};LocalAIEngine.shutdown();refreshModelStatus();Toast.makeText(this@SettingsActivity,"Local AI model installed",Toast.LENGTH_LONG).show()}catch(e:Exception){status.text="MODEL ERROR: ${e.message}";Toast.makeText(this@SettingsActivity,e.message ?:"Model download failed",Toast.LENGTH_LONG).show()}finally{findViewById<Button>(R.id.testButton).isEnabled=true}}}
    private fun populateMusicApps(){val pm=packageManager;val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);val apps=pm.queryIntentActivities(intent,0).map{it.loadLabel(pm).toString() to it.activityInfo.packageName}.distinctBy{it.second}.sortedBy{it.first.lowercase()};musicSpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,apps.map{it.first});val selected=getMusicPackage(this);val idx=apps.indexOfFirst{it.second==selected};if(idx>=0)musicSpinner.setSelection(idx);musicSpinner.setOnItemSelectedListener(object:android.widget.AdapterView.OnItemSelectedListener{override fun onItemSelected(p:android.widget.AdapterView<*>?,v:android.view.View?,pos:Int,id:Long){if(pos in apps.indices)saveMusicPackage(this@SettingsActivity,apps[pos].second)};override fun onNothingSelected(p:android.widget.AdapterView<*>?) {}});musicAccess.text=if(MusicController.isNotificationAccessEnabled(this))"MUSIC CONTROL ACCESS: ON" else "ENABLE MUSIC CONTROL ACCESS";musicAccess.setOnClickListener{MusicController.openNotificationAccessSettings(this)}}
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode==PICK_MODEL && resultCode==Activity.RESULT_OK){val uri=data?.data?:return;lifecycleScope.launch{try{withContext(Dispatchers.IO){LocalModelManager.copyFromUri(this@SettingsActivity,uri)};LocalAIEngine.shutdown();refreshModelStatus();Toast.makeText(this@SettingsActivity,"GGUF model loaded",Toast.LENGTH_LONG).show()}catch(e:Exception){Toast.makeText(this@SettingsActivity,e.message?:"Could not load model",Toast.LENGTH_LONG).show()}}}}

}
