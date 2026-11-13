package io.coralnut.pleosprobe
import android.app.Activity
import android.app.Instrumentation
import android.os.*
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
/** 실제 공개 API와 부산 목적지 좌표를 확인한다. */
object RealApiProbe {
 fun run(test:Instrumentation, mode:String){
  val main=Handler(Looper.getMainLooper());var helper:NaviHelper?=null;val lines=mutableListOf<String>()
  try {
   var result:Result<CoralnutRecommendations>?=null;var latch=CountDownLatch(1)
   CoralnutClient.get(test.targetContext).recommendations(35.16,129.11){result=it;latch.countDown()}
   check(latch.await(40,TimeUnit.SECONDS));val places=result!!.getOrThrow().places
   check(places.isNotEmpty()&&places.all{it.id.isNotBlank()&&it.distanceKm<=20&&it.latitude!=0.0})
   lines+="REAL_PUBLIC_API ${places.map{it.name+":"+it.id}}"
   latch=CountDownLatch(1)
   CoralnutClient.get(test.targetContext).recommendations(37.76516388,128.90143333){result=it;latch.countDown()}
   check(latch.await(40,TimeUnit.SECONDS));check(result!!.getOrThrow().places.isEmpty())
   lines+="GANGNEUNG_EMPTY_CONFIRMED no sample fallback"
   if(mode=="api-only"){
    val account=CountDownLatch(1);var name:String?=null
    val client=CoralnutClient.get(test.targetContext)
    client.account{it.onSuccess{name=it};account.countDown()}
    check(account.await(30,TimeUnit.SECONDS)&&!name.isNullOrBlank()){ "Saved account was not restored" }
    lines+="REAL_ACCOUNT_RESTORED"
    val field=CoralnutClient::class.java.getDeclaredField("session").apply{isAccessible=true}
    val tokens=field.get(client) as org.json.JSONObject
    val oldRefresh=tokens.getString("refreshToken")
    // 인증 실패만 유도한다. 실제 refresh token으로 서버가 회전한 결과를 확인한다.
    tokens.put("accessToken","expired-access-probe")
    val refreshed=CountDownLatch(1);var refreshSuccess=false
    client.account{refreshSuccess=it.isSuccess;refreshed.countDown()}
    check(refreshed.await(30,TimeUnit.SECONDS)&&refreshSuccess)
    check((field.get(client) as org.json.JSONObject).getString("refreshToken")!=oldRefresh)
    lines+="REAL_REFRESH_ROTATED_AFTER_401 no tokens logged"
    test.finish(Activity.RESULT_OK,Bundle().apply{putString("stream",lines.joinToString("\n")+"\nREAL_API_AUTH_PASS\n")});return
   }
   val connected=CountDownLatch(1);val destination=CountDownLatch(1);var failed=false
   main.post {
    helper=NaviHelper(test.targetContext)
    helper!!.initialize(onConnected={
     helper!!.addListener(object:NaviHelperEventListener{override fun onDestinationInfo(destinationInfo:DestinationInfo){if(kotlin.math.abs(destinationInfo.latitude-35.16)<0.001&&kotlin.math.abs(destinationInfo.longitude-129.11)<0.001)destination.countDown()}});connected.countDown()
    },onFailed={failed=true;connected.countDown()})
   }
   check(connected.await(25,TimeUnit.SECONDS)&&!failed){"NaviHelper initialization failed"}
   main.post{helper!!.requestRoute(RouteInfo(latitude=35.16,longitude=129.11,displayLatitude=null,displayLongitude=null,poiName="부산 수영구",poiId="",poiSubId="",address="부산광역시 수영구"))}
   repeat(25){if(destination.count>0){main.post{helper!!.getDestinationInfo()};destination.await(2,TimeUnit.SECONDS)}}
   check(destination.count==0L){"Busan route readback failed"};lines+="REAL_NAV_DESTINATION Busan verified"
   test.finish(Activity.RESULT_OK,Bundle().apply{putString("stream",lines.joinToString("\n")+"\nREAL_API_PASS\n")})
  }catch(e:Throwable){test.finish(Activity.RESULT_CANCELED,Bundle().apply{putString("stream",lines.joinToString("\n")+"\nREAL_API_FAILED ${e.message}\n")})}
  finally{main.post{helper?.release()}}
 }
}
