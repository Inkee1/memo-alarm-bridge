package io.github.inkee1.memoalarmbridge.ui

import android.app.AlertDialog
import android.media.*
import android.os.Bundle
import android.widget.*
import io.github.inkee1.memoalarmbridge.R
import io.github.inkee1.memoalarmbridge.audio.VoiceRecorder
import io.github.inkee1.memoalarmbridge.core.Note
import java.io.File
import java.util.UUID

class NotesScreen(private val host:MainActivity,state:Bundle?):LinearLayout(host) {
    private val editor=host.input(host.tr("Write something to remember…","기억할 내용을 적으세요…"),true).apply{id=R.id.note_text}
    private val list=LinearLayout(host).apply{orientation=VERTICAL}
    private val recording=host.label("")
    private val recorder=VoiceRecorder(host)
    private var id=state?.getString("note.id")
    private var audioHash=state?.getString("note.audio")?:""
    private var player:MediaPlayer?=null
    @Volatile private var loading=false
    init {
        orientation=VERTICAL
        val card=host.panel();card.addView(host.label(host.tr("A thought, kept here","생각을 여기에"),20,bold=true));card.addView(editor)
        editor.setText(state?.getString("note.text")?:"")
        card.addView(recording)
        val row=LinearLayout(host)
        row.addView(host.button(host.tr("Record / stop","녹음 / 정지")){if(recorder.active)stopRecording()else host.requestMic()},LayoutParams(0,-2,1f))
        row.addView(host.button(host.tr("Save note","메모 저장")){save()}.apply{id=R.id.save_note},LayoutParams(0,-2,1f));card.addView(row)
        card.addView(host.button(host.tr("New blank note","새 메모")){id=null;audioHash="";editor.setText("");updateRecording()})
        card.addView(host.label(host.tr("Voice capture stays on this screen. Tap Save to keep the draft. No transcription service is required.","녹음은 이 화면에서만 진행됩니다. 저장을 눌러 초안을 보관하세요. 음성 인식 서비스는 필요하지 않습니다."),12,MainActivity.MUTED))
        addView(card);addView(host.label(host.tr("Saved on this phone","이 폰에 저장됨"),19,bold=true));addView(list);updateRecording()
    }
    private fun updateRecording(){recording.text=if(recorder.active)host.tr("● Recording · maximum 5 minutes","● 녹음 중 · 최대 5분")else if(audioHash.isNotEmpty())host.tr("Voice recording attached","음성 녹음 첨부됨")else ""}
    fun startRecording(){recorder.start{host.runOnUiThread{runCatching{stopRecording()}.onFailure(host::error)}};updateRecording()}
    private fun stopRecording(){recorder.stop()?.let{audioHash=it};updateRecording()}
    private fun save(){
        if(recorder.active)stopRecording()
        val text=editor.text.toString();require(text.isNotBlank()||audioHash.isNotEmpty()){host.tr("Write a note or record your voice first.","메모를 적거나 음성을 녹음하세요.")}
        val note=Note(id?:UUID.randomUUID().toString(),text,audioHash)
        id=note.noteId
        host.work({host.graph.content().saveNote(note)}){stored->id=stored.note.noteId;host.graph.requestSync();host.tell(host.tr("Saved locally.","폰에 저장했습니다."));refresh()}
    }
    fun refresh(){if(loading)return;loading=true;host.work({try{host.graph.content().notes() to host.graph.content().read()}finally{loading=false}}){(notes,sync)->
        list.removeAllViews()
        if(notes.isEmpty())list.addView(host.label(host.tr("Your first note starts above.","위에서 첫 메모를 작성하세요."),14,MainActivity.MUTED))
        notes.forEach{stored->val note=stored.note;val card=host.panel();card.addView(host.label(note.text.ifBlank{host.tr("Voice note","음성 메모")},17));card.addView(host.label(host.syncStatus(sync,"note:${note.noteId}"),12,MainActivity.MUTED))
            val actions=LinearLayout(host);actions.addView(host.button(host.tr("Edit","편집")){id=note.noteId;audioHash=note.audioBlobSha256;editor.setText(note.text);updateRecording()},LayoutParams(0,-2,1f))
            actions.addView(host.button(host.tr("Delete","삭제")){AlertDialog.Builder(host).setMessage(host.tr("Delete this note?","이 메모를 삭제할까요?")).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok){_,_->host.work({host.graph.content().deleteNote(note.noteId)}){host.graph.requestSync();refresh()}}.show()},LayoutParams(0,-2,1f));card.addView(actions)
            if(note.audioBlobSha256.isNotEmpty())card.addView(host.button(host.tr("Play voice note","음성 메모 재생")){play(note.audioBlobSha256)})
            list.addView(card)
        }
    }}
    private fun play(hash:String){
        player?.release();val file=File(host.filesDir,"recordings/$hash.m4a");check(file.isFile){host.tr("Voice file is not on this phone yet.","음성 파일이 아직 이 폰에 없습니다.")}
        player=MediaPlayer().apply{setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());setDataSource(file.path);setOnPreparedListener{it.start()};setOnCompletionListener{it.release();if(player===it)player=null};setOnErrorListener{media,_,_->media.release();if(player===media)player=null;host.tell(host.tr("Could not play this recording.","녹음을 재생하지 못했습니다."));true};prepareAsync()}
    }
    fun pause(){if(recorder.active)runCatching{stopRecording()}.onFailure(host::error);player?.release();player=null}
    fun close()=pause()
    fun saveDraft(out:Bundle){out.putString("note.id",id);out.putString("note.audio",audioHash);out.putString("note.text",editor.text.toString())}
}
