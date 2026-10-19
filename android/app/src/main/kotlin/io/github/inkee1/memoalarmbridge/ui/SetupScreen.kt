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
import android.os.Build.MODEL
import io.github.inkee1.memoalarmbridge.core.RecoveryReason
import io.github.inkee1.memoalarmbridge.core.LocalRecord
import io.github.inkee1.memoalarmbridge.sync.ConnectionInvite

class SetupScreen(private val host:MainActivity):LinearLayout(host) {
    private val status=host.label("",14,MainActivity.MUTED)
    private val connection=host.label("",13,MainActivity.MUTED)
    private val invite=host.input(host.tr("Paste the full PC invitation","PC의 전체 초대 문자열 붙여넣기"),true)
    private val deviceName=host.input(host.tr("Phone name","폰 이름")).apply{setText(MODEL.take(70))}
    private val conflicts=LinearLayout(host).apply{orientation=VERTICAL}
    @Volatile private var loading=false
    init {
        orientation=VERTICAL;val card=host.panel();card.addView(host.label(host.tr("Make alarms dependable","알람 준비 확인"),20,bold=true));card.addView(status)
        card.addView(host.button(host.tr("Allow exact alarms","정확한 알람 허용")){host.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+host.packageName)))})
        card.addView(host.button(host.tr("Allow notifications","알림 허용")){if(Build.VERSION.SDK_INT>=33&&host.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)host.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),MainActivity.NOTIFICATIONS)else host.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,host.packageName))})
        card.addView(host.button(host.tr("Choose installed offline voice","설치된 오프라인 음성 선택")){chooseVoice()})
        card.addView(host.button(host.tr("Open system TTS settings","시스템 TTS 설정 열기")){host.startActivity(Intent("com.android.settings.TTS_SETTINGS"))})
        card.addView(host.button(host.tr("Check and prepare again","상태 확인하고 다시 준비")){host.work({host.graph.engine.reconcile(RecoveryReason.PERMISSION_CHANGED)}){host.tell(host.tr("Rechecked. See each alarm for its result.","다시 확인했습니다. 각 알람의 결과를 확인하세요."));refresh()}})
        card.addView(host.label(host.tr("No volume or Do Not Disturb settings are changed. Zero alarm volume can silence playback. Before first unlock after reboot, only the next two saved occurrences are known and use a generic tone. Unlock to restore private speech and replenish recurrence.","볼륨이나 방해금지 설정을 변경하지 않습니다. 알람 볼륨이 0이면 소리가 나지 않을 수 있습니다. 재부팅 후 첫 잠금 해제 전에는 저장된 다음 2회만 일반 알림음으로 실행합니다. 잠금을 해제하면 개인 음성과 이후 반복 예약을 복구합니다."),13,MainActivity.MUTED));addView(card)
        val local=host.panel();local.addView(host.label(host.tr("Works on its own","폰만으로 사용 가능"),20,bold=true));local.addView(host.label(host.tr("Save notes and alarms here without a PC, account, dot, or paid AI API. PC pairing is optional. An Android force-stop prevents alarms until you open the app again.","PC, 계정, dot, 유료 AI API 없이 메모와 알람을 저장할 수 있습니다. PC 연결은 선택 사항입니다. Android에서 앱을 강제 종료하면 다시 열 때까지 알람이 실행되지 않습니다."),14,MainActivity.MUTED));addView(local)
        val pairing=host.panel();pairing.addView(host.label(host.tr("Your PC connection","내 PC 연결"),20,bold=true));pairing.addView(connection);pairing.addView(deviceName);pairing.addView(invite)
        pairing.addView(host.button(host.tr("Review invitation and pair","초대 확인 후 연결")){reviewInvitation()})
        pairing.addView(host.button(host.tr("Sync now","지금 동기화")){host.tell(host.tr("Syncing with your PC…","PC와 동기화 중…"));host.network({host.graph.sync().run()}){r->host.tell(host.tr("Sync finished. Pending: ","동기화했습니다. 대기: ")+r.pending+host.tr(" · conflicts: "," · 충돌: ")+r.conflicts+host.tr(" · preparing alarms: "," · 준비 중 알람: ")+r.preparing);refresh()}})
        pairing.addView(host.button(host.tr("Remove phone connection","폰 연결 해제")){AlertDialog.Builder(host).setMessage(host.tr("Remove this phone’s connection credentials? Local notes and existing alarms remain. Revoke this device on the PC separately to remove its server access.","폰의 연결 인증 정보를 지울까요? 로컬 메모와 기존 알람은 유지됩니다. 서버 접근 권한도 없애려면 PC에서 해당 기기를 별도로 철회하세요.")).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok){_,_->host.work({host.graph.sync().unpair()}){refresh()}}.show()})
        pairing.addView(host.label(host.tr("Background sync is best effort, roughly every 15 minutes when connected. Use Sync now for an immediate attempt. Phone alarms use their saved offline schedule. Pair only with your own trusted PC; paired devices can read and change this collection.","백그라운드 동기화는 네트워크 연결 시 약 15분 간격으로 시도하며 지연될 수 있습니다. 즉시 시도하려면 ‘지금 동기화’를 누르세요. 알람은 폰의 오프라인 예약으로 실행합니다. 연결한 기기는 데이터 전체를 읽고 바꿀 수 있으므로 신뢰하는 내 PC에만 연결하세요."),12,MainActivity.MUTED));addView(pairing)
        addView(host.label(host.tr("Conflicts to review","검토할 충돌"),19,bold=true));addView(conflicts)
    }
    fun refresh(){
        val notification=host.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val volume=host.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM)
        status.text=host.tr("Exact alarms: ","정확한 알람: ")+(if(host.graph.scheduler.canScheduleExact())host.tr("allowed","허용")else host.tr("not allowed","미허용"))+"\n"+host.tr("Notifications: ","알림: ")+(if(notification)host.tr("allowed","허용")else host.tr("not allowed · use in-app Stop controls","미허용 · 앱 내부 정지 버튼 사용"))+"\n"+host.tr("Alarm volume: ","알람 볼륨: ")+volume+"\n"+host.tr("Voice: ","음성: ")+(host.getSharedPreferences("settings",0).getString("voice",null)?:host.tr("installed system-language voice","설치된 시스템 언어 음성"))
        if(!loading){loading=true;host.work({try{Triple(host.graph.sync().info(),host.graph.content().read(),host.graph.sync().lastError())}finally{loading=false}}){(info,state,error)->
            connection.text=(info?.endpoint?:host.tr("No PC paired","연결한 PC 없음"))+"\n"+host.tr("Last contact: ","마지막 접속: ")+state.lastContact.ifEmpty{host.tr("never","없음")}+"\n"+host.tr("Pending edits: ","전송 대기 수정: ")+state.records.values.count{it.dirty}+(if(error.isEmpty())""else "\n"+error)
            conflicts.removeAllViews();state.records.values.filter{it.conflict!=null}.forEach{record->val card=host.panel();card.addView(host.label(readable(record.payload,record.deleted),15));card.addView(host.button(host.tr("Review both versions","두 버전 검토")){reviewConflict(record)});conflicts.addView(card)}
        }}
    }
    private fun reviewInvitation(){
        val parsed=ConnectionInvite.decode(invite.text.toString());val name=deviceName.text.toString().trim()
        AlertDialog.Builder(host).setTitle(host.tr("Connect to this PC?","이 PC에 연결할까요?")).setMessage(parsed.endpoint+"\n\n"+host.tr("Certificate fingerprint\n","인증서 지문\n")+parsed.certSha256+"\n\n"+host.tr("Compare this with your PC. Pairing creates a protected device credential and enables background sync. Data on both sides will be shared; conflicts stay available for review.","PC 표시와 비교하세요. 연결하면 보호된 기기 인증 정보를 만들고 백그라운드 동기화를 켭니다. 양쪽 데이터가 공유되며 충돌한 버전은 검토할 수 있도록 남습니다.")).setNegativeButton(android.R.string.cancel,null).setPositiveButton(host.tr("Pair","연결")){_,_->host.tell(host.tr("Pairing…","연결 중…"));host.network({host.graph.sync().pair(parsed,name)}){invite.setText("");host.tell(host.tr("Paired. Use Sync now, then check each alarm’s phone status.","연결했습니다. 지금 동기화한 뒤 각 알람의 폰 상태를 확인하세요."));refresh()}}.show()
    }
    private fun readable(payload:com.google.gson.JsonObject,deleted:Boolean):String=if(deleted)host.tr("Deleted","삭제됨")else (payload["text"]?:payload["body"])?.asString.orEmpty().ifBlank{host.tr("Voice note","음성 메모")}
    private fun reviewConflict(record:LocalRecord){
        val conflict=requireNotNull(record.conflict);val remote=conflict.remote
        val text=host.tr("On this phone:\n","이 폰의 버전:\n")+readable(conflict.cancelledDraft?:record.payload,record.deleted)+"\n\n"+host.tr("On the PC:\n","PC 버전:\n")+(remote?.let{readable(it.payload,it.deleted)}?:host.tr("No current record","현재 항목 없음"))+(if(conflict.reason=="cancelled")host.tr("\n\nThe previous alarm was cancelled. Keeping this phone’s version creates a new alarm epoch.","\n\n이전 알람은 취소됐습니다. 폰 버전을 유지하면 새 알람으로 활성화합니다.")else "")
        fun resolve(local:Boolean){host.work({host.graph.sync().engine.resolve(record.key,local,conflict.revision,record.version.localGeneration);host.graph.engine.reconcile(RecoveryReason.APP_OPEN);host.graph.requestSync()}){refresh()}}
        AlertDialog.Builder(host).setTitle(host.tr("Choose a version","버전 선택")).setMessage(text).setPositiveButton(host.tr("Use phone version","폰 버전 사용")){_,_->resolve(true)}.setNegativeButton(host.tr("Use PC version","PC 버전 사용")){_,_->resolve(false)}.setNeutralButton(android.R.string.cancel,null).show()
    }
    private fun chooseVoice(){host.tell(host.tr("Checking installed voices…","설치된 음성 확인 중…"));host.work({host.graph.audio().offlineVoices()}){voices->
        if(voices.isEmpty()){host.tell(host.tr("No installed offline voice. Install your language in system TTS settings, then return.","설치된 오프라인 음성이 없습니다. 시스템 TTS 설정에서 언어를 설치한 뒤 돌아오세요."));return@work}
        AlertDialog.Builder(host).setTitle(host.tr("Offline voice","오프라인 음성")).setItems(voices.map{it.locale.displayName+" · "+it.name}.toTypedArray()){_,which->host.getSharedPreferences("settings",0).edit().putString("voice",voices[which].name).apply();host.tell(host.tr("Voice selected. Use Check and prepare again for blocked alarms. Existing prepared speech stays unchanged until the alarm is edited.","음성을 선택했습니다. 준비가 막힌 알람은 ‘다시 준비’를 누르세요. 기존에 준비된 음성은 알람 편집 전까지 유지됩니다."));refresh()}.show()
    }}
}
