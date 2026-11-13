package io.coralnut.pleosprobe

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 실제 로컬 grant를 쓰되 만료 시각만 테스트 메모리에서 앞당긴다. 계정 승인은 하지 않는다. */
object VehicleLinkUiProbe {
    fun run(test:Instrumentation) {
        val lines=mutableListOf<String>()
        try {
            val generation=VehicleLinkGeneration()
            val cancelled=generation.current()
            generation.renew()
            var saved=false
            check(generation.commit(cancelled){saved=true}==null&&!saved)
            check(generation.commit(generation.current()){true}==true)
            lines+="PASS cancelled generation cannot commit a late approval; current generation can"
            val activity=test.startActivitySync(Intent(test.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val type=MainActivity::class.java
            val link=type.getDeclaredField("link").apply{isAccessible=true}
            val deadline=type.getDeclaredField("linkDeadline").apply{isAccessible=true}
            val loading=type.getDeclaredField("linkLoading").apply{isAccessible=true}
            val begin=type.getDeclaredMethod("beginLink").apply{isAccessible=true}
            fun awaitGrant():JSONObject {
                val until=System.currentTimeMillis()+20000
                while(System.currentTimeMillis()<until) {
                    var found:JSONObject?=null
                    test.runOnMainSync{if(!(loading.get(activity) as Boolean))found=link.get(activity) as? JSONObject}
                    if(found!=null)return found!!
                    Thread.sleep(50)
                }
                error("Local grant was not displayed")
            }
            test.runOnMainSync{begin.invoke(activity)}
            val first=awaitGrant().getString("userCode")
            test.runOnMainSync {
                deadline.setLong(activity,0)
                (type.getDeclaredField("linkPoll").apply{isAccessible=true}.get(activity) as Runnable).run()
                fun texts(view:View):List<String> = (if(view is TextView)listOf(view.text.toString())else emptyList())+
                    (if(view is ViewGroup)(0 until view.childCount).flatMap{texts(view.getChildAt(it))}else emptyList())
                check(texts(activity.window.decorView).any{it.contains("연결 코드가 만료")})
                check(link.get(activity)==null)
            }
            lines+="PASS local grant QR displayed; simulated UI expiry shows expired state"
            test.runOnMainSync{begin.invoke(activity)}
            check(awaitGrant().getString("userCode")!=first)
            lines+="PASS retry creates a different actual local grant"
            test.runOnMainSync{activity.onBackPressed()}
            test.runOnMainSync {
                val handler=type.getDeclaredField("main").apply{isAccessible=true}.get(activity) as android.os.Handler
                val poll=type.getDeclaredField("linkPoll").apply{isAccessible=true}.get(activity) as Runnable
                check(!handler.hasCallbacks(poll))
            }
            val checked=CountDownLatch(1);var pendingCancelled=false
            CoralnutClient.get(test.targetContext).pendingLink{pendingCancelled=it.getOrThrow()==null;checked.countDown()}
            check(checked.await(10,TimeUnit.SECONDS)&&pendingCancelled)
            lines+="PASS back navigation cancels persisted pending link"
            test.finish(Activity.RESULT_OK,Bundle().apply{putString("stream",lines.joinToString("\n")+"\nVEHICLE_LINK_UI_PASS (expiry clock simulated; no account approval)\n")})
        } catch(error:Throwable) {
            test.finish(Activity.RESULT_CANCELED,Bundle().apply{putString("stream",lines.joinToString("\n")+"\nVEHICLE_LINK_UI_FAIL ${error.message}\n")})
        }
    }
}
