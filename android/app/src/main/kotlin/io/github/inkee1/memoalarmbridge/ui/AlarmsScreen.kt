package io.github.inkee1.memoalarmbridge.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.*
import com.google.gson.Gson
import io.github.inkee1.memoalarmbridge.core.*
import java.time.*
import java.util.UUID

class AlarmsScreen(private val host:MainActivity,state:Bundle?):LinearLayout(host) {
    private val body=host.input(host.tr("What should the phone say?","폰이 읽어줄 내용"),true)
    private val kind=Spinner(host)
    private val zone=host.input(host.tr("Time zone, e.g. Asia/Seoul","시간대, 예: Asia/Seoul"))
    private val silent=CheckBox(host).apply{text=host.tr("Also respect silent / vibrate mode","무음 / 진동 모드도 존중")}
    private val days=(1..7).map{day->CheckBox(host).apply{text=listOf(host.tr("Mon","월"),host.tr("Tue","화"),host.tr("Wed","수"),host.tr("Thu","목"),host.tr("Fri","금"),host.tr("Sat","토"),host.tr("Sun","일"))[day-1]}}
    private var date=state?.getString("alarm.date")?.let(LocalDate::parse)?:LocalDate.now()
    private var time=state?.getString("alarm.time")?.let(LocalTime::parse)?:LocalTime.now().plusHours(1).withSecond(0).withNano(0)
    private var scheduleId=state?.getString("alarm.id")
    private var baseVersion:Version?=state?.getString("alarm.base")?.let{Gson().fromJson(it,Version::class.java)}
    private var draftSequence=0L
    private var saving=false
    private var epochId=state?.getString("alarm.epoch")
    private var wasEnabled=state?.getBoolean("alarm.enabled")?:true
    private val dateButton=host.button(""){DatePickerDialog(host,{_,y,m,d->date=LocalDate.of(y,m+1,d);updateWhen()},date.year,date.monthValue-1,date.dayOfMonth).show()}
    private val timeButton=host.button(""){TimePickerDialog(host,{_,h,m->time=LocalTime.of(h,m);updateWhen()},time.hour,time.minute,true).show()}
    private val preview=host.label("",13,MainActivity.MUTED)
    private val list=LinearLayout(host).apply{orientation=VERTICAL}
    @Volatile private var loading=false
    init {
        orientation=VERTICAL;val form=host.panel();form.addView(host.label(host.tr("Speak at the right moment","필요한 순간에 읽어주기"),20,bold=true));form.addView(body);body.setText(state?.getString("alarm.body")?:"")
        kind.adapter=ArrayAdapter(host,android.R.layout.simple_spinner_dropdown_item,listOf(host.tr("Once","한 번"),host.tr("Every day","매일"),host.tr("Selected weekdays","선택한 요일")));kind.setSelection(state?.getInt("alarm.kind")?:0);form.addView(kind)
        val whenRow=LinearLayout(host);whenRow.addView(dateButton,LayoutParams(0,-2,1f));whenRow.addView(timeButton,LayoutParams(0,-2,1f));form.addView(whenRow)
        zone.setText(state?.getString("alarm.zone")?:ZoneId.systemDefault().id);form.addView(zone)
        val daysRow=LinearLayout(host);val daysRow2=LinearLayout(host);days.forEachIndexed{i,view->view.isChecked=state?.getBoolean("alarm.day.$i")?:false;(if(i<4)daysRow else daysRow2).addView(view,LayoutParams(0,-2,1f))};form.addView(daysRow);form.addView(daysRow2)
        silent.isChecked=state?.getBoolean("alarm.silent")?:false;form.addView(silent)
        form.addView(host.label(host.tr("Date applies to Once. Weekdays apply to Selected weekdays. Recurring alarms stay in the chosen time zone. The next two are prepared offline.","날짜는 ‘한 번’, 요일은 ‘선택한 요일’에 적용됩니다. 반복 알람은 지정 시간대를 따릅니다. 다음 2회를 오프라인으로 준비합니다."),12,MainActivity.MUTED))
        form.addView(host.button(host.tr("Preview next times","다음 시각 미리 보기")){val r=buildRule();preview.text=nextOccurrences(r,Instant.now(),3).joinToString("\n"){it.atUtc}.ifEmpty{host.tr("No future occurrence. Choose a later time.","다음 예약이 없습니다. 미래 시각을 선택하세요.")}});form.addView(preview)
        form.addView(host.button(host.tr("Save and prepare offline","저장하고 오프라인 준비")){save(false)})
        form.addView(host.button(host.tr("Test in one minute","1분 뒤 테스트")){save(true)})
        form.addView(host.button(host.tr("New alarm","새 알람")){draftSequence++;scheduleId=null;baseVersion=null;epochId=null;wasEnabled=true;body.setText("");preview.text=""})
        addView(form);addView(host.label(host.tr("Phone schedule","폰 예약 상태"),19,bold=true));addView(list);updateWhen()
    }
    private fun updateWhen(){dateButton.text=date.toString();timeButton.text=time.toString()}
    private fun buildRule(test:Boolean=false):ScheduleRule {
        val z=ZoneId.of(zone.text.toString().trim());val type=if(test)"one_shot" else listOf("one_shot","daily","weekly")[kind.selectedItemPosition]
        return ScheduleRule(scheduleId=scheduleId?:UUID.randomUUID().toString(),epochId=if(wasEnabled)epochId?:UUID.randomUUID().toString()else UUID.randomUUID().toString(),kind=type,atUtc=if(type=="one_shot")(if(test)Instant.now().plusSeconds(60)else LocalDateTime.of(date,time).atZone(z).toInstant()).toString()else "",localTime=if(type=="one_shot")""else time.toString(),zoneId=if(type=="one_shot")""else z.id,weekDays=if(type=="weekly")days.mapIndexedNotNull{i,v->if(v.isChecked)i+1 else null}else emptyList(),body=body.text.toString().ifBlank{if(test)host.tr("This is your offline test reminder.","오프라인 알람 테스트입니다.")else ""},enabled=true,respectSilent=silent.isChecked).also{validateRule(it).requireValid()}
    }
    private fun save(test:Boolean){
        if(saving)return
        val draft=buildRule(test);val rule=if(test)draft.copy(scheduleId=UUID.randomUUID().toString(),epochId=UUID.randomUUID().toString())else draft
        require(nextOccurrences(rule,Instant.now(),1).isNotEmpty()){host.tr("Choose a future time.","미래 시각을 선택하세요.")}
        val expected=if(test)null else baseVersion;val sequence=draftSequence;saving=true
        if(!test){scheduleId=rule.scheduleId;epochId=rule.epochId;wasEnabled=true}
        host.work({runCatching{host.graph.saveRule(rule,expected)}}){result->
            saving=false
            result.onSuccess{version->
                if(!test&&draftSequence==sequence)baseVersion=version
                host.tell(host.tr("Saved. Ready appears only after offline audio and phone registration succeed.","저장했습니다. 오프라인 음성과 폰 예약이 모두 성공해야 ‘예약됨’이 표시됩니다."));refresh()
            }.onFailure{error->if(error is StaleDraftException&&!test&&draftSequence==sequence){
                reviewChangedDraft(host,"schedule",Gson().toJsonTree(rule).asJsonObject,error.current,
                    {current->edit(Gson().fromJson(current.payload,ScheduleRule::class.java),current.version)},
                    {draftSequence++;scheduleId=null;baseVersion=null;epochId=null;wasEnabled=true;save(false)})
            }else host.error(error)}
        }
    }
    private fun edit(rule:ScheduleRule,version:Version){
        draftSequence++;scheduleId=rule.scheduleId;baseVersion=version;epochId=rule.epochId;wasEnabled=rule.enabled;body.setText(rule.body);silent.isChecked=rule.respectSilent
        kind.setSelection(listOf("one_shot","daily","weekly").indexOf(rule.kind))
        if(rule.kind=="one_shot"){val local=Instant.parse(rule.atUtc).atZone(ZoneId.systemDefault());date=local.toLocalDate();time=local.toLocalTime().withSecond(0).withNano(0);zone.setText(local.zone.id)}else{time=LocalTime.parse(rule.localTime);zone.setText(rule.zoneId)}
        days.forEachIndexed{i,v->v.isChecked=i+1 in rule.weekDays};updateWhen()
    }
    fun refresh(){if(loading)return;loading=true;host.work({try{host.graph.content().rules().map{(r,v)->Triple(r,v,host.graph.engine.report(r.scheduleId))} to host.graph.content().read()}finally{loading=false}}){(items,sync)->
        list.removeAllViews();if(items.isEmpty())list.addView(host.label(host.tr("No alarms yet. Use a one-minute test after setup.","아직 알람이 없습니다. 설정 후 1분 테스트를 해보세요."),14,MainActivity.MUTED))
        items.forEach{(rule,version,report)->val card=host.panel();card.addView(host.label(rule.body,17,MainActivity.INK,true))
            card.addView(host.label(host.syncStatus(sync,"schedule:${rule.scheduleId}"),12,MainActivity.MUTED))
            val applied=report.active.filter{it.key.version==version&&it.key.epochId==rule.epochId}
            val state=if(!rule.enabled)report.state else if(applied.isEmpty()&&report.state=="armed")"pending"else report.state
            card.addView(host.label(status(state),15,MainActivity.ACCENT,true));card.addView(host.label((if(version.serverRevision==null)host.tr("Local only · ","폰 전용 · ")else "")+rule.kind+" · "+(if(rule.kind=="one_shot")rule.atUtc else rule.localTime+" "+rule.zoneId),12,MainActivity.MUTED))
            report.active.forEach{e->card.addView(host.label((if(e.key.version==version)""else host.tr("Previous version remains: ","이전 버전 유지: "))+e.atUtc+" · "+e.phase,12,MainActivity.MUTED));if(e.phase=="firing"){val controls=LinearLayout(host);controls.addView(host.button(host.tr("Stop","정지")){host.work({host.graph.engine.finish(e.key.token,"dismissed")}){host.graph.requestSync();refresh()}},LayoutParams(0,-2,1f));controls.addView(host.button(host.tr("Snooze 5 min","5분 미루기")){host.work({host.graph.engine.snooze(e.key.token)}){host.graph.requestSync();refresh()}},LayoutParams(0,-2,1f));card.addView(controls)}}
            val actions=LinearLayout(host);actions.addView(host.button(host.tr("Edit / enable","편집 / 활성화")){edit(rule,version)},LayoutParams(0,-2,1f));actions.addView(host.button(host.tr("Cancel","취소")){host.work({host.graph.cancelRule(rule.scheduleId,version)}){host.graph.requestSync();refresh()}},LayoutParams(0,-2,1f));card.addView(actions);list.addView(card)
        }
    }}
    private fun status(value:String)=when(value){"armed"->host.tr("Ready on this phone","이 폰에 예약됨");"preparing"->host.tr("Preparing offline speech…","오프라인 음성 준비 중…");"blocked_permission"->host.tr("Needs exact alarm permission · open Setup","정확한 알람 권한 필요 · 설정 확인");"blocked_audio"->host.tr("Audio unavailable · choose an offline voice in Setup","음성 준비 안 됨 · 설정에서 오프라인 음성 선택");"cancelled"->host.tr("Cancelled on this phone","이 폰에서 취소됨");"fired"->host.tr("Triggered · completion is not an audibility guarantee","실행됨 · 실제 청취 여부는 확인되지 않음");"missed"->host.tr("Missed; not replayed","시각 지남 · 다시 재생하지 않음");"interrupted"->host.tr("Interrupted; not retried","중단됨 · 재시도하지 않음");"snoozed"->host.tr("Snoozed for five minutes","5분 미룸");"dismissed"->host.tr("Stopped","정지됨");else->host.tr("Pending on this phone","폰 예약 대기 중")}
    fun saveDraft(out:Bundle){out.putString("alarm.id",scheduleId);out.putString("alarm.base",Gson().toJson(baseVersion));out.putString("alarm.epoch",epochId);out.putBoolean("alarm.enabled",wasEnabled);out.putString("alarm.body",body.text.toString());out.putString("alarm.zone",zone.text.toString());out.putString("alarm.date",date.toString());out.putString("alarm.time",time.toString());out.putInt("alarm.kind",kind.selectedItemPosition);out.putBoolean("alarm.silent",silent.isChecked);days.forEachIndexed{i,v->out.putBoolean("alarm.day.$i",v.isChecked)}}
}
