package io.github.inkee1.memoalarmbridge.sync

import android.content.Context
import android.os.UserManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DeviceSecrets(private val context:Context) {
    private fun file():AtomicFile {check(context.getSystemService(UserManager::class.java).isUserUnlocked){"Unlock before accessing the connection"};return AtomicFile(File(context.noBackupFilesDir,"device-connection.bin"))}
    private fun key(create:Boolean):SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        if(store.containsAlias(ALIAS))return store.getKey(ALIAS,null) as SecretKey
        check(create){"Connection key is missing; review PC devices and re-pair"}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())}.generateKey()
    }
    @Synchronized fun load():ConnectionCredential? {
        val file=file();if(!file.baseFile.exists())return null
        val bytes=file.openRead().use{input->val buffer=ByteArray(65537);var count=0;while(count<buffer.size){val n=input.read(buffer,count,buffer.size-count);if(n<0)break;count+=n};buffer.copyOf(count)};check(bytes.size in 30..65536&&bytes[0].toInt()==1){"Connection file is invalid; original retained"}
        val iv=bytes.copyOfRange(1,13);val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,iv));cipher.updateAAD(AAD)
        val result=Gson().fromJson(String(cipher.doFinal(bytes.copyOfRange(13,bytes.size)),Charsets.UTF_8),ConnectionCredential::class.java);BridgeClient(result);return result
    }
    @Synchronized fun save(value:ConnectionCredential){
        BridgeClient(value);val file=file();val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true));cipher.updateAAD(AAD)
        val encrypted=cipher.doFinal(Gson().toJson(value).toByteArray(Charsets.UTF_8));check(cipher.iv.size==12)
        val out=file.startWrite();try{out.write(byteArrayOf(1));out.write(cipher.iv);out.write(encrypted);file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
    }
    @Synchronized fun clear(){file().delete();KeyStore.getInstance("AndroidKeyStore").apply{load(null);if(containsAlias(ALIAS))deleteEntry(ALIAS)}}
    companion object {private const val ALIAS="memoalarm.device-connection.v1";private val AAD="MemoAlarmBridge device connection v1".toByteArray(Charsets.US_ASCII)}
}
