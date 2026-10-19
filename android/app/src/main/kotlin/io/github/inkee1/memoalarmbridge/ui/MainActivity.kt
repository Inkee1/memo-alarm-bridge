package io.github.inkee1.memoalarmbridge.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*
import io.github.inkee1.memoalarmbridge.AppGraph
import io.github.inkee1.memoalarmbridge.core.RecoveryReason
import java.util.Locale

class MainActivity:Activity() {
    lateinit var graph:AppGraph;private set
    private lateinit var notes:NotesScreen
    private lateinit var alarms:AlarmsScreen
    private lateinit var setup:SetupScreen
    private lateinit var holder:FrameLayout
    private lateinit var message:TextView
    private var selected=0
    private val handler=Handler(Looper.getMainLooper())
    private val refresh=object:Runnable {override fun run(){when(selected){0->notes.refresh();1->alarms.refresh();2->setup.refresh()};handler.postDelayed(this,2000)}}
    override fun onCreate(state:Bundle?){
        super.onCreate(state);graph=AppGraph.get(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(BG);setPadding(dp(20),0,dp(20),0)}
        root.setOnApplyWindowInsetsListener{view,insets->val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime());view.setPadding(dp(20),bars.top+dp(14),dp(20),bars.bottom+dp(10));insets}
        root.addView(label("MEMOALARM",13,ACCENT).apply{letterSpacing=.18f})
        root.addView(label(tr("Your words. On time.","내 메모, 제시간에."),28,INK,true))
        root.addView(label(tr("Private notes and offline phone alarms","나만의 메모와 오프라인 폰 알람"),14,MUTED))
        val tabs=LinearLayout(this)
        listOf(tr("Notes","메모"),tr("Alarms","알람"),tr("Setup","설정")).forEachIndexed{i,title->tabs.addView(button(title){show(i)},LinearLayout.LayoutParams(0,dp(54),1f))}
        root.addView(tabs)
        holder=FrameLayout(this);root.addView(holder,LinearLayout.LayoutParams(-1,0,1f))
        message=label("",13,MUTED);message.maxLines=5;root.addView(message)
        notes=NotesScreen(this,state);alarms=AlarmsScreen(this,state);setup=SetupScreen(this)
        setContentView(root);show(state?.getInt("tab")?:0)
    }
    private fun show(index:Int){
        if(::notes.isInitialized&&selected==0&&index!=0)notes.pause()
        selected=index;holder.removeAllViews();val screen=when(index){0->notes;1->alarms;else->setup}
        (screen.parent as? ViewGroup)?.removeView(screen)
        holder.addView(ScrollView(this).apply{isFillViewport=true;addView(screen)})
        when(index){0->notes.refresh();1->alarms.refresh();else->setup.refresh()}
    }
    override fun onResume(){super.onResume();work({graph.engine.reconcile(RecoveryReason.APP_OPEN)}){};handler.post(refresh)}
    override fun onPause(){handler.removeCallbacks(refresh);notes.pause();super.onPause()}
    override fun onDestroy(){notes.close();super.onDestroy()}
    override fun onSaveInstanceState(out:Bundle){out.putInt("tab",selected);notes.saveDraft(out);alarms.saveDraft(out);super.onSaveInstanceState(out)}
    fun <T> work(job:()->T,done:(T)->Unit){graph.work.execute{val result=runCatching(job);runOnUiThread{if(!isDestroyed)result.fold(done){error(it)}}}}
    fun <T> network(job:()->T,done:(T)->Unit){graph.syncWork.execute{val result=runCatching(job);runOnUiThread{if(!isDestroyed)result.fold(done){error(it)}}}}
    fun tell(text:String){message.text=text;message.setTextColor(MUTED)}
    fun error(error:Throwable){message.text=tr("Could not complete: ","완료하지 못했습니다: ")+(error.message?:error.javaClass.simpleName);message.setTextColor(Color.rgb(154,48,48))}
    fun requestMic(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)notes.startRecording()else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),MIC)}
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==MIC){if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)notes.startRecording()else tell(tr("Microphone permission was not granted. Text notes still work.","마이크 권한이 없습니다. 텍스트 메모는 사용할 수 있습니다."))};setup.refresh()}
    fun tr(en:String,ko:String)=if(Locale.getDefault().language=="ko")ko else en
    fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    fun label(text:String,size:Int=16,color:Int=INK,bold:Boolean=false)=TextView(this).apply{this.text=text;textSize=size.toFloat();setTextColor(color);setPadding(0,dp(5),0,dp(5));if(bold)setTypeface(typeface,Typeface.BOLD)}
    fun button(text:String,action:()->Unit)=Button(this).apply{this.text=text;isAllCaps=false;setTextColor(INK);setOnClickListener{runCatching(action).onFailure(::error)}}
    fun input(hint:String,multiline:Boolean=false)=EditText(this).apply{this.hint=hint;setTextColor(INK);setHintTextColor(MUTED);setSingleLine(!multiline);if(multiline){minLines=3;maxLines=8;gravity=Gravity.TOP or Gravity.START;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES}}
    fun panel()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(10),dp(14),dp(10));background=GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(16).toFloat()};layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10);bottomMargin=dp(8)}}
    companion object {const val MIC=11;const val NOTIFICATIONS=12;val BG=Color.rgb(241,246,245);val INK=Color.rgb(20,49,57);val MUTED=Color.rgb(72,102,107);val ACCENT=Color.rgb(0,116,104)}
}
