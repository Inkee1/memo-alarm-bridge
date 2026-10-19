package io.github.inkee1.memoalarmbridge.audio

import android.content.Context
import android.media.MediaRecorder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Activity-owned capture. onPause always stops it; no background microphone. */
class VoiceRecorder(private val context:Context) {
    private var recorder:MediaRecorder?=null
    private var part:File?=null
    val active get()=recorder!=null
    fun start(onLimit:()->Unit){
        check(!active)
        val directory=File(context.filesDir,"recordings").apply{check(mkdirs()||isDirectory)}
        val output=File(directory,UUID.randomUUID().toString()+".part")
        val r=MediaRecorder(context)
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1);r.setAudioSamplingRate(16000);r.setAudioEncodingBitRate(32000)
            r.setOutputFile(output);r.setMaxDuration(300000);r.setMaxFileSize(25L*1024*1024)
            r.setOnInfoListener{_,what,_->if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED||what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)onLimit()}
            r.prepare();r.start();part=output;recorder=r
        } catch(e:Exception){r.release();output.delete();throw e}
    }
    fun stop():String? {
        val r=recorder?:return null;val file=requireNotNull(part);recorder=null;part=null
        try {
            r.stop();r.release()
            check(file.length() in 1..25L*1024*1024){"Recording is empty or too large"}
            FileOutputStream(file,true).use{it.fd.sync()}
            val hash=FileInputStream(file).use{input->val digest=MessageDigest.getInstance("SHA-256");val buf=ByteArray(8192);while(true){val n=input.read(buf);if(n<0)break;digest.update(buf,0,n)};digest.digest().joinToString(""){"%02x".format(it)}}
            val target=File(file.parentFile,hash+".m4a")
            if(target.exists())file.delete()else Files.move(file.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE)
            return hash
        } catch(e:Exception){runCatching{r.release()};file.delete();throw e}
    }
}
