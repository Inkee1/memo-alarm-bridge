package io.github.inkee1.memoalarmbridge.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AudioFilesTest {
 private val ref="11111111-1111-4111-8111-111111111111"
 private fun wav():ByteArray=ByteBuffer.allocate(76).order(ByteOrder.LITTLE_ENDIAN).apply{put("RIFF".toByteArray());putInt(68);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(16000);putInt(32000);putShort(2);putShort(16);put("data".toByteArray());putInt(32);put(ByteArray(32))}.array()
 @Test fun partialSynthesisIsNotPromoted(){val dir=Files.createTempDirectory("memoalarm-audio-test").toFile();try{val files=AudioFiles(dir);val temp=File(dir,"unfinished.part");temp.writeBytes(wav().copyOf(50));try{files.commit(temp,ref);fail("truncated WAV accepted")}catch(_:Exception){};assertNull(files.inspect(ref));assertFalse(File(dir,ref+".wav").exists())}finally{dir.deleteRecursively()}}
 @Test fun committedAudioIsImmutableAndTamperDetected(){val dir=Files.createTempDirectory("memoalarm-audio-test").toFile();try{val files=AudioFiles(dir);val temp=File(dir,"ready.part");temp.writeBytes(wav());val ready=files.commit(temp,ref);assertEquals(76L,ready.bytes);assertEquals(ready,files.inspect(ref));val altered=wav();altered[75]=7;val another=File(dir,"other.part");another.writeBytes(altered);try{files.commit(another,ref);fail("immutable reference replaced")}catch(_:Exception){};File(dir,ref+".wav").writeBytes(altered);assertNotEquals(ready,files.inspect(ref))}finally{dir.deleteRecursively()}}
 @Test fun referencesCannotEscapePrivateDirectory(){val dir=Files.createTempDirectory("memoalarm-audio-test").toFile();try{val files=AudioFiles(dir);assertNull(files.inspect("../../elsewhere"))}finally{dir.deleteRecursively()}}
}
