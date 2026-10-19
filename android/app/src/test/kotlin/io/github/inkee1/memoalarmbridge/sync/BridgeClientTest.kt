package io.github.inkee1.memoalarmbridge.sync

import io.github.inkee1.memoalarmbridge.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class BridgeClientTest {
    private class LocalServer(ssl:SSLContext,private val seen:AtomicInteger):AutoCloseable {
        private val socket=ssl.serverSocketFactory.createServerSocket(0,10,InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val port get()=socket.localPort
        @Volatile var running=true
        @Volatile var redirect=false
        private val thread=Thread{
            while(running)try{(socket.accept() as SSLSocket).use{client->
                client.soTimeout=3000;client.startHandshake();val input=client.inputStream.bufferedReader();val first=input.readLine()?:return@use
                if(!first.startsWith("POST "))return@use
                var length=0;while(true){val line=input.readLine()?:return@use;if(line.isEmpty())break;if(line.startsWith("Content-Length:",true))length=line.substringAfter(':').trim().toInt()}
                repeat(length){check(input.read()!=-1)};seen.incrementAndGet()
                val body="{\"schema_version\":1,\"cursor\":0,\"more\":false,\"results\":[],\"changes\":[]}".toByteArray()
                val response=if(redirect)"HTTP/1.1 302 Found\r\nLocation: https://localhost:1/steal\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" else "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                client.outputStream.write(response.toByteArray());if(!redirect)client.outputStream.write(body);client.outputStream.flush()
            }}catch(_:Exception){/* A rejected handshake intentionally never reaches HTTP. */}
        }.apply{isDaemon=true;start()}
        override fun close(){running=false;socket.close();thread.join(1000)}
    }
    private fun server(block:(LocalServer,String,AtomicInteger)->Unit){
        val dir=Files.createTempDirectory("memoalarm-tls-test").toFile();val store=File(dir,"test.p12")
        try {
            val tool=File(System.getProperty("java.home"),"bin/keytool"+if(System.getProperty("os.name").startsWith("Windows"))".exe"else "")
            val process=ProcessBuilder(tool.path,"-genkeypair","-alias","test","-keyalg","EC","-groupname","secp256r1","-storetype","PKCS12","-keystore",store.path,"-storepass","synthetic-only","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-validity","1","-noprompt").redirectErrorStream(true).start()
            process.inputStream.readBytes();assertEquals(0,process.waitFor())
            val ks=KeyStore.getInstance("PKCS12").apply{store.inputStream().use{load(it,"synthetic-only".toCharArray())}}
            val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply{init(ks,"synthetic-only".toCharArray())}
            val ssl=SSLContext.getInstance("TLS").apply{init(km.keyManagers,null,null)}
            val seen=AtomicInteger();val server=LocalServer(ssl,seen)
            val pin=MessageDigest.getInstance("SHA-256").digest(ks.getCertificate("test").encoded).joinToString(""){"%02x".format(it)}
            try{block(server,pin,seen)}finally{server.close()}
        }finally{store.delete();dir.delete()}
    }
    @Test fun wrongPinStopsBeforeAuthenticatedHttp(){server{s,pin,seen->val endpoint="https://127.0.0.1:"+s.port;val good=ConnectionCredential(endpoint=endpoint,certSha256=pin,deviceId="11111111-1111-4111-8111-111111111111",token="synthetic-token-that-is-at-least-forty-characters");assertEquals(0L,BridgeClient(good).exchange(SyncRequest()).cursor);assertEquals(1,seen.get());try{BridgeClient(good.copy(certSha256="0".repeat(64))).exchange(SyncRequest());fail("wrong pin accepted")}catch(_:java.io.IOException){};assertEquals(1,seen.get())}}
    @Test fun redirectsAreNotFollowed(){server{s,pin,seen->s.redirect=true;try{BridgeClient(ConnectionCredential(endpoint="https://127.0.0.1:"+s.port,certSha256=pin,deviceId="11111111-1111-4111-8111-111111111111",token="synthetic-token-that-is-at-least-forty-characters")).exchange(SyncRequest());fail("redirect accepted")}catch(e:java.io.IOException){assertTrue(e.message.orEmpty().contains("HTTP 302"))};assertEquals(1,seen.get())}}
    @Test fun rejectsCredentialedOrNonHttpsOrigin(){for(endpoint in listOf("http://localhost:1234","https://user:password@localhost:1234","https://localhost:1234/path","https://localhost:1234?x=y")){try{BridgeClient(ConnectionCredential(endpoint=endpoint,certSha256="a".repeat(64),deviceId="11111111-1111-4111-8111-111111111111",token="synthetic-token-that-is-at-least-forty-characters"));fail("bad origin accepted")}catch(_:IllegalArgumentException){}}}
}
