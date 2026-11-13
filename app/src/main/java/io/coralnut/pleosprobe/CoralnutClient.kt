package io.coralnut.pleosprobe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.*

data class CoralnutVideo(val id:String,val title:String,val thumbnailUrl:String,val fallbackUrl:String,val startSec:Int?)

data class CoralnutPlace(val id:String,val name:String,val creator:String,val channelId:String,val slug:String,
    val category:String,val address:String,val latitude:Double,val longitude:Double,val distanceKm:Double,
    val quote:String,val subscribed:Boolean,val video:CoralnutVideo?=null,val region:String="지역 미분류",val avatarUrl:String?=null) {
    val hasCoordinates get()=latitude.isFinite()&&longitude.isFinite()
}
data class CoralnutRecommendations(val places:List<CoralnutPlace>,val subscriptionStatus:String,val matchedChannelCount:Int=0) {
    val subscriptionLabel:String get()=when(subscriptionStatus){
        "CONNECTED"->if(places.any{it.subscribed})"내 구독 크리에이터 추천" else "다른 크리에이터 추천"
        "DISABLED"->"일반 추천 · 구독 연결 꺼짐"
        "AUTHORIZATION_REQUIRED"->"일반 추천 · 구독 권한 연결 필요"
        "GUEST"->"로그인 없이 둘러보기"
        else->"일반 추천 · 구독 정보 확인 지연"
    }
    val subscriptionExplanation:String get()=when(subscriptionStatus){
        "CONNECTED"->when {
            places.any{it.subscribed}->"구독 중인 크리에이터의 장소부터 보여드려요. 같은 그룹에서는 목적지에 가까운 순서예요."
            matchedChannelCount==0->"구독 목록과 일치하는 Muninn 공개 크리에이터가 없어 다른 크리에이터의 장소를 보여드려요."
            else->"목적지 반경 20km에 구독 크리에이터의 장소가 없어 다른 크리에이터의 장소를 보여드려요."
        }
        "DISABLED","AUTHORIZATION_REQUIRED"->"휴대폰 Muninn에서 YouTube 구독 연결을 완료한 뒤 추천을 새로고침해주세요."
        "UNAVAILABLE"->"구독 정보를 확인하지 못해 가까운 공개 장소를 보여드려요. 잠시 후 새로고침해주세요."
        else->"Muninn 계정을 연결하면 구독 크리에이터의 장소를 먼저 추천받을 수 있어요."
    }
}

/** 구독 우선 정렬을 먼저 적용해 같은 장소의 여러 소개 중 구독 채널을 남긴다. */
internal fun rankRecommendations(places:List<CoralnutPlace>,limit:Int=5)=places
    .sortedWith(compareByDescending<CoralnutPlace>{it.subscribed}.thenBy{it.distanceKm}.thenBy{it.id}.thenBy{it.channelId})
    .distinctBy{it.id}.take(limit)

/** 네트워크·토큰 회전을 한 큐에서 처리해 웹 세션과 독립된 차량 세션을 유지한다. */
class CoralnutClient private constructor(context:Context) {
    companion object {
        @Volatile private var instance:CoralnutClient?=null
        fun get(context:Context):CoralnutClient=instance ?: synchronized(this){instance ?: CoralnutClient(context.applicationContext).also{instance=it}}
        private fun category(value:String)=when(value){
            "RESTAURANT"->"식당";"CAFE"->"카페";"BAR"->"술집";"LANDMARK"->"명소";"NATURE"->"자연";"LODGING"->"숙소";"SHOPPING"->"쇼핑";"ACTIVITY"->"체험";else->"장소"
        }
        fun distance(a:Double,b:Double,c:Double,d:Double):Double {
            val lat=Math.toRadians(c-a);val lon=Math.toRadians(d-b)
            val h=sin(lat/2).pow(2)+cos(Math.toRadians(a))*cos(Math.toRadians(c))*sin(lon/2).pow(2)
            return 6371*2*atan2(sqrt(h.coerceIn(0.0,1.0)),sqrt((1-h).coerceIn(0.0,1.0)))
        }
    }
    private val main=Handler(Looper.getMainLooper())
    private val queue=Executors.newSingleThreadExecutor()
    private val linkGeneration=VehicleLinkGeneration()
    private val file=AtomicFile(java.io.File(context.filesDir,"vehicle-session.enc"))
    private val pendingFile=AtomicFile(java.io.File(context.filesDir,"vehicle-link.enc"))
    private var session:JSONObject?=null
    @Volatile var userName:String?=null;private set
    private class HttpError(val status:Int):Exception("Muninn 요청 실패 ($status)")
    init { queue.execute{try{session=readSession();userName=session?.optJSONObject("user")?.optString("name")?.ifBlank{"Muninn 사용자"}}catch(_:Exception){clearSession()}} }
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        return (store.getKey("coralnut-vehicle",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("coralnut-vehicle",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun readSession():JSONObject?=readEncrypted(file)
    private fun readEncrypted(storage:AtomicFile):JSONObject? {
        if(!storage.baseFile.exists())return null
        val data=JSONObject(storage.openRead().bufferedReader().use{it.readText()})
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(data.getString("iv"),Base64.NO_WRAP)))}
        return JSONObject(String(cipher.doFinal(Base64.decode(data.getString("data"),Base64.NO_WRAP)),Charsets.UTF_8))
    }
    private fun saveSession(tokens:JSONObject){
        writeEncrypted(file,tokens)
        session=tokens;userName=tokens.optJSONObject("user")?.optString("name")?.ifBlank{"Muninn 사용자"}
    }
    private fun writeEncrypted(storage:AtomicFile,tokens:JSONObject){
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key())}
        val data=JSONObject().put("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).put("data",Base64.encodeToString(cipher.doFinal(tokens.toString().toByteArray()),Base64.NO_WRAP)).toString()
        val output=storage.startWrite();try{output.write(data.toByteArray());storage.finishWrite(output)}catch(e:Exception){storage.failWrite(output);throw e}
    }
    private fun clearSession(){session=null;userName=null;file.delete()}
    private fun request(path:String,method:String="GET",body:JSONObject?=null,token:String?=null):Any {
        val base=BuildConfig.CORALNUT_API_BASE
        val connection=URL(base+path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod=method;connection.connectTimeout=12000;connection.readTimeout=20000
            connection.setRequestProperty("Accept","application/json")
            if(token!=null)connection.setRequestProperty("Authorization","Bearer $token")
            if(body!=null){connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use{it.write(body.toString().toByteArray())}}
            val status=connection.responseCode
            if(status !in 200..299)throw HttpError(status)
            val json=JSONObject(connection.inputStream.bufferedReader().use{it.readText()})
            return if(json.has("data"))json.get("data") else json
        } finally {connection.disconnect()}
    }
    private fun authenticated(path:String):JSONObject {
        val tokens=session ?: throw Exception("로그인이 필요해요.")
        try{return request(path,token=tokens.getString("accessToken")) as JSONObject}
        catch(e:HttpError){if(e.status!=401)throw e}
        val renewed=try{request("/auth/refresh","POST",JSONObject().put("refreshToken",tokens.getString("refreshToken"))) as JSONObject}
        catch(e:HttpError){if(e.status==401)clearSession();throw e}
        saveSession(renewed)
        return request(path,token=renewed.getString("accessToken")) as JSONObject
    }
    private fun <T> async(action:()->T,callback:(Result<T>)->Unit){queue.execute{val result=runCatching(action);main.post{callback(result)}}}
    fun account(callback:(Result<String?>)->Unit)=async({if(session==null)null else authenticated("/user").optString("name").also{userName=it}},callback)
    fun createLink(callback:(Result<JSONObject>)->Unit){
        val generation=linkGeneration.renew()
        async({
            val grant=request("/auth/vehicle","POST",JSONObject()) as JSONObject
            grant.put("expiresAt",System.currentTimeMillis()+grant.getLong("expiresIn")*1000)
            linkGeneration.commit(generation){writeEncrypted(pendingFile,grant);grant}
                ?: throw java.util.concurrent.CancellationException("취소된 차량 연결")
        },callback)
    }
    fun pendingLink(callback:(Result<JSONObject?>)->Unit)=async({
        val grant=readEncrypted(pendingFile)
        if(grant!=null&&grant.optLong("expiresAt")>System.currentTimeMillis())grant else {pendingFile.delete();null}
    },callback)
    fun cancelLink() {linkGeneration.renew();queue.execute{pendingFile.delete()}}
    fun pollLink(deviceCode:String,callback:(Result<String>)->Unit){
        val generation=linkGeneration.current()
        async({
            if(linkGeneration.current()!=generation||readEncrypted(pendingFile)?.optString("deviceCode")!=deviceCode)"EXPIRED"
            else {
                val response=request("/auth/vehicle/token","POST",JSONObject().put("deviceCode",deviceCode)) as JSONObject
                val status=response.getString("status")
                if(status=="AUTHORIZED"){
                    val tokens=response.getJSONObject("tokens")
                    var committed=false
                    try {
                        val user=request("/user",token=tokens.getString("accessToken")) as JSONObject
                        tokens.put("user",user)
                        committed=linkGeneration.commit(generation){
                            if(readEncrypted(pendingFile)?.optString("deviceCode")==deviceCode){saveSession(tokens);pendingFile.delete();true}else false
                        } ?: false
                        if(committed)"AUTHORIZED" else "EXPIRED"
                    } finally {
                        if(!committed)runCatching{request("/auth/logout","POST",JSONObject().put("refreshToken",tokens.getString("refreshToken")))}
                            .onFailure{android.util.Log.w("MuninnLink","Discarded session revocation was unavailable")}
                    }
                }else{
                    if(status=="EXPIRED")linkGeneration.commit(generation){pendingFile.delete()}
                    status
                }
            }
        },callback)
    }
    fun logout(callback:(Result<Unit>)->Unit)=async({
        val tokens=session
        if(tokens!=null)request("/auth/logout","POST",JSONObject().put("refreshToken",tokens.getString("refreshToken")))
        clearSession();Unit
    },callback)
    fun recommendations(lat:Double,lon:Double,callback:(Result<CoralnutRecommendations>)->Unit)=catalog(lat,lon){result->callback(result.map{it.copy(places=rankRecommendations(it.places.filter{p->p.distanceKm<=20}))})}
    fun catalog(lat:Double?,lon:Double?,callback:(Result<CoralnutRecommendations>)->Unit)=async({
        var status=if(session==null)"GUEST" else "UNAVAILABLE"
        val subscribed=mutableSetOf<String>()
        if(session!=null){try{
            val match=authenticated("/user/subscriptions/match");status=match.getString("status")
            val channels=match.optJSONArray("channels") ?: JSONArray()
            if(status=="CONNECTED")for(i in 0 until channels.length())subscribed.add(channels.getJSONObject(i).getString("id"))
        }catch(_:Exception){status=if(session==null)"AUTHORIZATION_REQUIRED" else "UNAVAILABLE"}}
        val channels=request("/public/channels/directory/details") as JSONArray
        val places=mutableListOf<CoralnutPlace>()
        for(i in 0 until channels.length()){
            val channel=channels.getJSONObject(i);val id=channel.getString("channelId")
            val contentRows=channel.optJSONArray("contents") ?: JSONArray()
            val contents=(0 until contentRows.length()).map{contentRows.getJSONObject(it)}.associateBy{it.getString("id")}
            val rows=channel.optJSONArray("places") ?: JSONArray()
            for(j in 0 until rows.length()){
                val p=rows.getJSONObject(j)
                if(p.optBoolean("closed"))continue
                val address=p.optString("address")
                val noCoordinates=p.isNull("lat")&&p.isNull("lng")
                val a=p.optDouble("lat",Double.NaN);val b=p.optDouble("lng",Double.NaN)
                if(noCoordinates){
                    if(!hasDomesticAddress(address))continue
                }else if(!isDomesticPlace(address,a,b))continue
                val km=if(!noCoordinates&&lat!=null&&lon!=null)distance(lat,lon,a,b)else Double.POSITIVE_INFINITY
                val bullets=p.optJSONArray("bullets");val notes=if(bullets!=null)(0 until bullets.length()).map{bullets.optString(it)}.filter{it.isNotBlank()}.joinToString("\n")else ""
                val mentions=p.optJSONArray("mentions") ?: JSONArray()
                val linked=(0 until mentions.length()).map{mentions.getJSONObject(it)}.mapNotNull{mention->
                    val content=contents[mention.optString("contentId")] ?: return@mapNotNull null
                    val videoId=content.optString("youtubeVideoId")
                    if(!Regex("[A-Za-z0-9_-]{11}").matches(videoId))return@mapNotNull null
                    val fallback="https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
                    val supplied=content.optString("thumbnailUrl").takeIf{url->runCatching{val parsed=URL(url);parsed.protocol=="https"&&parsed.host=="i.ytimg.com"}.getOrDefault(false)}
                    CoralnutVideo(videoId,content.optString("title"),supplied ?: fallback,fallback,
                        if(mention.isNull("timecodeSec"))null else mention.optInt("timecodeSec").takeIf{it>=0})
                }.firstOrNull()
                places.add(CoralnutPlace(p.getString("id"),p.getString("name"),channel.getString("name"),id,channel.getString("slug"),category(p.optString("category")),p.optString("address").takeUnless{it=="null"} ?: "주소 정보 없음",a,b,km,notes,id in subscribed,linked,p.optString("area").takeUnless{it.isBlank()||it=="null"} ?: "지역 미분류",channel.optString("avatarUrl").takeIf{it.isNotBlank()}))
            }
        }
        CoralnutRecommendations(places,status,subscribed.size)
    },callback)
}
