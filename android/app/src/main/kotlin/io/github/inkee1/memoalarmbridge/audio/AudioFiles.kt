package io.github.inkee1.memoalarmbridge.audio

import io.github.inkee1.memoalarmbridge.core.ReadyAudio
import io.github.inkee1.memoalarmbridge.core.validId
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class AudioFiles(val directory:File) {
 init {check(directory.isDirectory||directory.mkdirs()){ "Audio directory unavailable" }}
 fun path(ref:String):File {require(validId(ref));return File(directory,ref+".wav")}
 fun inspect(ref:String):ReadyAudio?=try {val f=path(ref);if(!f.isFile)null else {validateWave(f);ReadyAudio(ref,sha(f),f.length())}}catch(_:Exception){null}
 @Synchronized fun commit(temp:File,ref:String):ReadyAudio {
  require(temp.canonicalFile.parentFile==directory.canonicalFile&&temp.name.endsWith(".part")){"Invalid synthesis temporary file"}
  validateWave(temp);val ready=ReadyAudio(ref,sha(temp),temp.length());val target=path(ref)
  RandomAccessFile(temp,"rw").use{it.fd.sync()}
  if(target.exists()){check(inspect(ref)==ready){"Audio references are immutable"};check(temp.delete());return ready}
  Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE)
  return ready
 }
 private fun sha(f:File):String {val digest=MessageDigest.getInstance("SHA-256");f.inputStream().buffered().use{input->val b=ByteArray(8192);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}};return digest.digest().joinToString(""){"%02x".format(it)}}
 private fun validateWave(f:File){
  require(f.length() in 45..25L*1024*1024){"Audio must be complete and at most 25 MiB"}
  RandomAccessFile(f,"r").use{r->
   fun tag():String {val b=ByteArray(4);r.readFully(b);return b.toString(Charsets.US_ASCII)}
   fun u32():Long=Integer.toUnsignedLong(Integer.reverseBytes(r.readInt()))
   fun u16():Int=java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(r.readShort()))
   require(tag()=="RIFF");require(u32()+8==f.length()){ "Incomplete WAV header" };require(tag()=="WAVE")
   var byteRate=0L;var alignment=0;var dataBytes=0L
   while(r.filePointer+8<=r.length()){
    val type=tag();val size=u32();val start=r.filePointer;require(size<=r.length()-start){"Truncated WAV chunk"}
    if(type=="fmt ") {require(size>=16);val encoding=u16();val channels=u16();val rate=u32();byteRate=u32();alignment=u16();val bits=u16();require(encoding in listOf(1,3)&&channels in 1..2&&rate in 8000..192000&&bits in listOf(8,16,24,32));require(encoding!=3||bits==32);require(alignment==channels*bits/8&&byteRate==rate*alignment)}
    if(type=="data")dataBytes+=size
    val next=start+size+(size%2);require(next<=r.length());r.seek(next)
   }
   require(byteRate>0&&alignment>0&&dataBytes>0&&dataBytes%alignment==0L){"WAV has no valid PCM frames"}
   require(dataBytes.toDouble()/byteRate<=120.0){"Speech exceeds two minutes; shorten the text"}
  }
 }
}
