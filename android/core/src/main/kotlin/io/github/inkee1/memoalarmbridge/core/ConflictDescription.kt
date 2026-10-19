package io.github.inkee1.memoalarmbridge.core

import com.google.gson.Gson
import com.google.gson.JsonObject

/** Human-readable complete choice fields; no credential or transport metadata. */
fun describeConflictVersion(entityType:String,payload:JsonObject,deleted:Boolean,korean:Boolean=false):String {
    fun tr(en:String,ko:String)=if(korean)ko else en
    fun yes(value:Boolean)=if(value)tr("Yes","예")else tr("No","아니요")
    val gson=Gson()
    val lines=mutableListOf<String>()
    if(deleted)lines+=tr("State: Deleted","상태: 삭제됨")
    when(entityType){
        "note"->{
            val note=gson.fromJson(payload,Note::class.java)
            if(!deleted)lines+=tr("State: Saved note","상태: 저장된 메모")
            lines+=tr("Text: ","내용: ")+note.text
            lines+=tr("Recording SHA-256: ","녹음 SHA-256: ")+note.audioBlobSha256.ifEmpty{tr("None","없음")}
        }
        "schedule"->{
            val rule=gson.fromJson(payload,ScheduleRule::class.java)
            lines+=tr("Speech: ","읽을 내용: ")+rule.body
            lines+=tr("State: ","상태: ")+(if(rule.enabled&&!deleted)tr("Enabled","활성")else tr("Cancelled / disabled","취소 / 비활성"))
            lines+=tr("Repeat: ","반복: ")+when(rule.kind){"one_shot"->tr("Once","한 번");"daily"->tr("Every day","매일");"weekly"->tr("Selected weekdays","선택한 요일");else->rule.kind}
            if(rule.kind=="one_shot")lines+=tr("At: ","예약 시각: ")+rule.atUtc else {
                lines+=tr("Local time: ","지정 시각: ")+rule.localTime
                lines+=tr("Time zone: ","시간대: ")+rule.zoneId
                if(rule.kind=="weekly"){
                    val names=if(korean)listOf("월","화","수","목","금","토","일")else listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun")
                    lines+=tr("Weekdays: ","요일: ")+rule.weekDays.joinToString(", "){names[it-1]}
                }
            }
            lines+=tr("Respect silent mode: ","무음 모드도 존중: ")+yes(rule.respectSilent)
        }
        else->throw IllegalArgumentException("Unknown entity type")
    }
    return lines.joinToString("\n")
}
