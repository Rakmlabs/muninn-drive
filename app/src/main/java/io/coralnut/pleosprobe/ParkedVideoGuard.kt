package io.coralnut.pleosprobe

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/** 플랫폼의 정차/주행 제한을 따른다. 신호 연결 해제·조회 실패 시 허용 상태를 보존하지 않는다. */
class ParkedVideoGuard(context:Context,private val changed:(Boolean)->Unit,private val log:(String)->Unit){
    private val main=Handler(Looper.getMainLooper())
    private var car:Car?=null
    private var manager:CarUxRestrictionsManager?=null
    private var closed=false
    var allowed=false;private set
    private fun update(value:Boolean,reason:String){
        val previous=allowed;allowed=value
        if(previous!=value){log("VIDEO_GUARD allowed=$value $reason");changed(value)}
    }
    private val listener=CarUxRestrictionsManager.OnUxRestrictionsChangedListener{restrictions->main.post{if(!closed)apply(restrictions)}}
    private fun apply(restrictions:CarUxRestrictions){
        val permitted=!restrictions.isRequiresDistractionOptimization &&
            restrictions.activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO==0
        update(permitted,"requiresDO=${restrictions.isRequiresDistractionOptimization} restrictions=${restrictions.activeRestrictions}")
    }
    private val poll=object:Runnable{override fun run(){if(closed)return;refresh();main.postDelayed(this,1000)}}
    init{
        if(Build.VERSION.SDK_INT>=29){
            try{
                car=Car.createCar(context.applicationContext,main,Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT){connected,ready->
                    main.post{
                        if(closed)return@post
                        if(!ready){manager=null;update(false,"service disconnected");return@post}
                        try{
                            manager=connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                            manager!!.registerListener(listener);refresh()
                        }catch(e:Exception){manager=null;update(false,"registration unavailable");log("VIDEO_GUARD_ERROR ${e.javaClass.simpleName}")}
                    }
                }
            }catch(e:Exception){log("VIDEO_GUARD_ERROR ${e.javaClass.simpleName}")}
        }
        main.post(poll)
    }
    fun refresh():Boolean{
        if(closed)return false
        try{
            if(car?.isConnected!=true||manager==null)update(false,"service unavailable")
            else apply(manager!!.currentCarUxRestrictions)
        }catch(e:Exception){update(false,"state unavailable");log("VIDEO_GUARD_ERROR ${e.javaClass.simpleName}")}
        return allowed
    }
    fun close(){closed=true;main.removeCallbacksAndMessages(null);runCatching{manager?.unregisterListener()};manager=null;runCatching{car?.disconnect()};car=null;allowed=false}
}
