package io.github.inkee1.memoalarmbridge.sync

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import io.github.inkee1.memoalarmbridge.core.*
import java.io.*
import java.net.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.net.ssl.*

data class ConnectionInvite(@SerializedName("schema_version")val schemaVersion:Int=1,val endpoint:String="",@SerializedName("cert_sha256")val certSha256:String="",val nonce:String="",@SerializedName("expires_at")val expiresAt:String="") {
    companion object {
        fun decode(value:String,now:Instant=Instant.now()):ConnectionInvite {
            require(value.length in 1..8192){"Paste the complete invitation"}
            val json=JsonParser.parseString(String(Base64.getUrlDecoder().decode(value.trim()),Charsets.UTF_8)).asJsonObject
            require(json.keySet()==setOf("schema_version","endpoint","cert_sha256","nonce","expires_at")){"Invalid invitation fields"}
            return Gson().fromJson(json,ConnectionInvite::class.java).also{
                origin(it.endpoint);require(it.schemaVersion==1&&validHash(it.certSha256)&&Regex("^[A-Za-z0-9_-]{43}$").matches(it.nonce)){"Invalid invitation"}
                val until=Instant.parse(it.expiresAt);require(until>now&&Duration.between(now,until)<=Duration.ofSeconds(630)){"Invitation expired or clocks differ; create a fresh invitation"}
            }
        }
    }
}
data class ConnectionCredential(@SerializedName("schema_version")val schemaVersion:Int=1,val endpoint:String="",@SerializedName("cert_sha256")val certSha256:String="",@SerializedName("device_id")val deviceId:String="",val token:String="")
private fun origin(value:String):String {
    val uri=URI(value)
    require(uri.scheme=="https"&&!uri.host.isNullOrBlank()&&uri.userInfo==null&&uri.rawQuery==null&&uri.rawFragment==null&&(uri.rawPath.isNullOrEmpty()||uri.rawPath=="/")&&(uri.port==-1||uri.port in 1..65535)){"Bridge address must be an HTTPS origin without credentials, path, query or fragment"}
    return "https://"+uri.rawAuthority
}

class BridgeClient(private val credential:ConnectionCredential):SyncTransport {
    private val gson=Gson()
    private val endpoint=origin(credential.endpoint)
    private val socketFactory:SSLSocketFactory
    init {
        require(credential.schemaVersion==1&&validHash(credential.certSha256)){"Invalid saved connection"}
        require((credential.deviceId.isEmpty()&&credential.token.isEmpty())||(validId(credential.deviceId)&&Regex("^[A-Za-z0-9_-]{40,128}$").matches(credential.token))){"Invalid device credential"}
        val trust=object:X509TrustManager {
            override fun getAcceptedIssuers():Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain:Array<X509Certificate>,authType:String){throw CertificateException("Client trust is not provided")}
            override fun checkServerTrusted(chain:Array<X509Certificate>,authType:String){
                val leaf=chain.firstOrNull()?:throw CertificateException("Server certificate missing")
                val digest=MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString(""){"%02x".format(it)}
                if(!MessageDigest.isEqual(digest.toByteArray(Charsets.US_ASCII),credential.certSha256.toByteArray(Charsets.US_ASCII)))throw CertificateException("Server fingerprint changed; re-pair deliberately")
                leaf.checkValidity()
            }
        }
        socketFactory=ModernTlsFactory(SSLContext.getInstance("TLS").apply{init(null,arrayOf(trust),null)}.socketFactory)
    }
    private fun connection(path:String,method:String,authenticated:Boolean=true):HttpsURLConnection {
        require(path.startsWith("/v1/")&&!path.contains('?')&&!path.contains('#')&&!path.contains(".."))
        return (URL(endpoint+path).openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply{
            sslSocketFactory=socketFactory
            // Keep the platform hostname verifier. A pin does not waive host validity.
            instanceFollowRedirects=false;connectTimeout=10000;readTimeout=45000;requestMethod=method;useCaches=false
            if(authenticated){check(credential.deviceId.isNotEmpty());setRequestProperty("Authorization","Bearer "+credential.deviceId+"."+credential.token)}
        }
    }
    private fun readBounded(input:InputStream,limit:Int):ByteArray {
        val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val n=input.read(buffer);if(n<0)break;if(out.size()+n>limit)throw IOException("Bridge response exceeds limit");out.write(buffer,0,n)}
        return out.toByteArray()
    }
    private fun post(path:String,body:Any,authenticated:Boolean=true):ByteArray {
        val bytes=gson.toJson(body).toByteArray(Charsets.UTF_8);require(bytes.size<=4*1024*1024){"Sync request exceeds limit"}
        val connection=connection(path,"POST",authenticated)
        try{connection.setRequestProperty("Content-Type","application/json");connection.doOutput=true;connection.setFixedLengthStreamingMode(bytes.size);connection.outputStream.use{it.write(bytes)};checkStatus(connection);return connection.inputStream.use{readBounded(it,8*1024*1024)}}finally{connection.disconnect()}
    }
    private fun checkStatus(connection:HttpsURLConnection){val code=connection.responseCode;if(code !in 200..299)throw IOException(when(code){401->"Device access was revoked or is invalid; review PC devices and re-pair";409->"Sync state was rejected; review server restore/acknowledgement status and retry";else->"Bridge request failed (HTTP $code); no redirect was followed"})}
    override fun exchange(request:SyncRequest):SyncResponse=gson.fromJson(String(post("/v1/sync",request),Charsets.UTF_8),SyncResponse::class.java)
    fun uploadBlob(hash:String,file:File){
        require(validHash(hash)&&file.isFile&&file.length() in 1..MAX_BLOB){"Recording missing or exceeds 25 MiB"}
        val connection=connection("/v1/blob/"+hash,"PUT")
        try{connection.setRequestProperty("Content-Type","application/octet-stream");connection.doOutput=true;connection.setFixedLengthStreamingMode(file.length());connection.outputStream.use{out->file.inputStream().use{it.copyTo(out)}};checkStatus(connection);connection.inputStream.use{readBounded(it,4096)}}finally{connection.disconnect()}
    }
    fun downloadBlob(hash:String,directory:File):File {
        require(validHash(hash));check(directory.mkdirs()||directory.isDirectory)
        val target=File(directory,hash+".m4a")
        if(target.isFile&&target.length() in 1..MAX_BLOB&&sha256(target)==hash)return target
        val temporary=File.createTempFile("download-",".part",directory);val connection=connection("/v1/blob/"+hash,"GET")
        try{
            checkStatus(connection);if(connection.contentLengthLong>MAX_BLOB)throw IOException("Audio exceeds 25 MiB")
            val digest=MessageDigest.getInstance("SHA-256");var total=0L
            FileOutputStream(temporary).use{out->connection.inputStream.use{input->val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;total+=n;if(total>MAX_BLOB)throw IOException("Audio exceeds 25 MiB");digest.update(buffer,0,n);out.write(buffer,0,n)}};out.fd.sync()}
            check(total>0&&digest.digest().joinToString(""){"%02x".format(it)}==hash){"Incomplete or changed audio"}
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);return target
        }finally{connection.disconnect();temporary.delete()}
    }
    companion object {
        const val MAX_BLOB=25L*1024*1024
        fun sha256(file:File):String=file.inputStream().use{input->val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)};hash.digest().joinToString(""){"%02x".format(it)}}
        fun pair(invite:ConnectionInvite,deviceName:String):ConnectionCredential {
            require(deviceName.isNotBlank()&&deviceName.codePointCount(0,deviceName.length)<=80)
            val client=BridgeClient(ConnectionCredential(endpoint=invite.endpoint,certSha256=invite.certSha256))
            val result=Gson().fromJson(String(client.post("/v1/pair",mapOf("nonce" to invite.nonce,"device_name" to deviceName),false),Charsets.UTF_8),ConnectionCredential::class.java)
            val saved=result.copy(endpoint=invite.endpoint,certSha256=invite.certSha256)
            BridgeClient(saved) // Validate all received credential fields before storage.
            return saved
        }
    }
}
private class ModernTlsFactory(private val delegate:SSLSocketFactory):SSLSocketFactory() {
    private fun configured(socket:Socket):Socket=(socket as SSLSocket).apply{enabledProtocols=supportedProtocols.filter{it=="TLSv1.2"||it=="TLSv1.3"}.toTypedArray()}
    override fun getDefaultCipherSuites():Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites():Array<String> = delegate.supportedCipherSuites
    override fun createSocket(socket:Socket,host:String,port:Int,close:Boolean)=configured(delegate.createSocket(socket,host,port,close))
    override fun createSocket(host:String,port:Int)=configured(delegate.createSocket(host,port))
    override fun createSocket(host:String,port:Int,local:InetAddress,localPort:Int)=configured(delegate.createSocket(host,port,local,localPort))
    override fun createSocket(host:InetAddress,port:Int)=configured(delegate.createSocket(host,port))
    override fun createSocket(host:InetAddress,port:Int,local:InetAddress,localPort:Int)=configured(delegate.createSocket(host,port,local,localPort))
    override fun createSocket()=configured(delegate.createSocket())
}
