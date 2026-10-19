package io.github.inkee1.memoalarmbridge.ui

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import io.github.inkee1.memoalarmbridge.core.RecoveryReason

class SetupScreen(private val host:MainActivity):LinearLayout(host) {
    private val status=host.label("",14,MainActivity.MUTED)
    init {
        orientation=VERTICAL;val card=host.panel();card.addView(host.label(host.tr("Make alarms dependable","알람 준비 확인"),20,bold=true));card.addView(status)
        card.addView(host.button(host.tr("Allow exact alarms","정확한 알람 허용")){host.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+host.packageName)))})
        card.addView(host.button(host.tr("Allow notifications","알림 허용")){if(Build.VERSION.SDK_INT>=33&&host.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)host.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),MainActivity.NOTIFICATIONS)else host.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,host.packageName))})
        card.addView(host.button(host.tr("Choose installed offline voice","설치된 오프라인 음성 선택")){chooseVoice()})
        card.addView(host.button(host.tr("Open system TTS settings","시스템 TTS 설정 열기")){host.startActivity(Intent("com.android.settings.TTS_SETTINGS"))})
        card.addView(host.button(host.tr("Check and prepare again","상태 확인하고 다시 준비")){host.work({host.graph.engine.reconcile(RecoveryReason.PERMISSION_CHANGED)}){host.tell(host.tr("Rechecked. See each alarm for its result.","다시 확인했습니다. 각 알람의 결과를 확인하세요."));refresh()}})
        card.addView(host.label(host.tr("No volume or Do Not Disturb settings are changed. Zero alarm volume can silence playback. Before first unlock after reboot, only the next two saved occurrences are known and use a generic tone. Unlock to restore private speech and replenish recurrence.","볼륨이나 방해금지 설정을 변경하지 않습니다. 알람 볼륨이 0이면 소리가 나지 않을 수 있습니다. 재부팅 후 첫 잠금 해제 전에는 저장된 다음 2회만 일반 알림음으로 실행합니다. 잠금을 해제하면 개인 음성과 이후 반복 예약을 복구합니다."),13,MainActivity.MUTED));addView(card)
        val local=host.panel();local.addView(host.label(host.tr("Works on its own","폰만으로 사용 가능"),20,bold=true));local.addView(host.label(host.tr("Save notes and alarms here without a PC, account, dot, or paid AI API. PC pairing is optional. An Android force-stop prevents alarms until you open the app again.","PC, 계정, dot, 유료 AI API 없이 메모와 알람을 저장할 수 있습니다. PC 연결은 선택 사항입니다. Android에서 앱을 강제 종료하면 다시 열 때까지 알람이 실행되지 않습니다."),14,MainActivity.MUTED));addView(local)
    }
    fun refresh(){
        val notification=host.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val volume=host.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM)
        status.text=host.tr("Exact alarms: ","정확한 알람: ")+(if(host.graph.scheduler.canScheduleExact())host.tr("allowed","허용")else host.tr("not allowed","미허용"))+"\n"+host.tr("Notifications: ","알림: ")+(if(notification)host.tr("allowed","허용")else host.tr("not allowed · use in-app Stop controls","미허용 · 앱 내부 정지 버튼 사용"))+"\n"+host.tr("Alarm volume: ","알람 볼륨: ")+volume+"\n"+host.tr("Voice: ","음성: ")+(host.getSharedPreferences("settings",0).getString("voice",null)?:host.tr("installed system-language voice","설치된 시스템 언어 음성"))
    }
    private fun chooseVoice(){host.tell(host.tr("Checking installed voices…","설치된 음성 확인 중…"));host.work({host.graph.audio().offlineVoices()}){voices->
        if(voices.isEmpty()){host.tell(host.tr("No installed offline voice. Install your language in system TTS settings, then return.","설치된 오프라인 음성이 없습니다. 시스템 TTS 설정에서 언어를 설치한 뒤 돌아오세요."));return@work}
        AlertDialog.Builder(host).setTitle(host.tr("Offline voice","오프라인 음성")).setItems(voices.map{it.locale.displayName+" · "+it.name}.toTypedArray()){_,which->host.getSharedPreferences("settings",0).edit().putString("voice",voices[which].name).apply();host.tell(host.tr("Voice selected. Use Check and prepare again for blocked alarms. Existing prepared speech stays unchanged until the alarm is edited.","음성을 선택했습니다. 준비가 막힌 알람은 ‘다시 준비’를 누르세요. 기존에 준비된 음성은 알람 편집 전까지 유지됩니다."));refresh()}.show()
    }}
}
