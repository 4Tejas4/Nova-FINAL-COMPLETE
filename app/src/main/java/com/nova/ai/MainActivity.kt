package com.nova.ai

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var startButton: TextView
    private var pulse:ObjectAnimator?=null
    private val micPermissionCode=101

    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);AppContext.init(this);setContentView(R.layout.activity_main)
        chat=findViewById(R.id.chat);scroll=findViewById(R.id.chatScroll);input=findViewById(R.id.messageInput);status=findViewById(R.id.statusText);startButton=findViewById(R.id.startButton)
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener{startActivity(Intent(this,SettingsActivity::class.java))}
        findViewById<ImageButton>(R.id.sendButton).setOnClickListener{sendText()}
        findViewById<ImageButton>(R.id.micButton).setOnClickListener{toggleNova()}
        startButton.setOnClickListener{toggleNova()}
        input.setOnEditorActionListener{_,_,_->sendText();true}
        addBubble("I’m ready. Load a GGUF model in Settings, then say “Hey Nova” or type a command. Everything in this build is designed to reason locally on the phone.",false)
        updateState()
    }
    private fun sendText(){val text=input.text.toString().trim();if(text.isBlank())return;input.setText("");addBubble(text,true);runCommand(text)}
    private fun runCommand(text:String){if(!LocalModelManager.isInstalled(this)){addBubble("The local AI model is not installed. Open Settings and download/load the GGUF model.",false);return};status.text="Local AI • thinking…";NovaAgentCore(this).submitVoiceOrText(text,object:NovaAgentCore.Callback{override fun onStatus(message:String){runOnUiThread{status.text="Local AI • $message"}};override fun onFinished(success:Boolean,message:String){runOnUiThread{addBubble(message,false);status.text=if(success)"Local AI • ready" else "Local AI • stopped"}}})}
    private fun toggleNova(){if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.RECORD_AUDIO),micPermissionCode);return};val running=startButton.text.toString().contains("STOP",true);if(running){stopNova()}else{startNova()}}
    private fun startNova(){try{val i=Intent(this,NovaWakeService::class.java).setAction(NovaWakeService.ACTION_START);if(Build.VERSION.SDK_INT>=26)startForegroundService(i) else startService(i);startButton.text="STOP";status.text="Listening locally • Hey Nova";startPulse()}catch(e:Exception){addBubble("Could not start Nova: ${e.message}",false)}}
    private fun stopNova(){stopService(Intent(this,NovaWakeService::class.java).setAction(NovaWakeService.ACTION_STOP));startButton.text="START";status.text="Local AI • ready";stopPulse()}
    private fun updateState(){if(LocalModelManager.isInstalled(this)){status.text="Local AI • model ready"}else{status.text="Local AI • model not loaded"}}
    private fun startPulse(){pulse?.cancel();pulse=ObjectAnimator.ofFloat(startButton,"alpha",1f,.55f,1f).apply{duration=1200;repeatCount=ObjectAnimator.INFINITE;interpolator=LinearInterpolator();start()}}
    private fun stopPulse(){pulse?.cancel();startButton.alpha=1f}
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==micPermissionCode&&grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED){toggleNova()}else if(requestCode==micPermissionCode){addBubble("Microphone permission is required for Nova to listen. Please allow it in Settings → Apps → Nova → Permissions.",false)}}
    private fun addBubble(text:String,isUser:Boolean){val tv=TextView(this);tv.text=text;tv.textSize=16f;tv.setTextColor(getColor(if(isUser)R.color.nova_user_text else R.color.nova_text));tv.setPadding(18,14,18,14);tv.background=getDrawable(if(isUser)R.drawable.bg_user_bubble else R.drawable.bg_ai_bubble);val lp=LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,8,0,8);tv.layoutParams=lp;chat.addView(tv);scroll.post{scroll.fullScroll(ScrollView.FOCUS_DOWN)}}
    override fun onResume(){super.onResume();updateState()}
    override fun onDestroy(){stopPulse();super.onDestroy()}
}
