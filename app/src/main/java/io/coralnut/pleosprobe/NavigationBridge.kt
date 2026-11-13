package io.coralnut.pleosprobe

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** 요청 성공 대신 실제 조회 상태로 경유지 반영과 최종 목적지 유지를 판정한다. */
class NavigationBridge(context: Context, private val changed: () -> Unit, private val completed: () -> Unit) {
    private val app=context.applicationContext
    private val helper=NaviHelper(app)
    private val main=Handler(Looper.getMainLooper())
    var destination: DestinationInfo?=null; private set
    var waypoints: List<WaypointInfo> = emptyList(); private set
    var busy=false; private set
    var message="내비 연결 중"; private set
    var connected=false; private set
    var unconfirmed=false; private set
    var settingDestination=false; private set
    private var closed=false
    private var phase="IDLE"
    private var expected: DestinationInfo?=null
    private var originalNames=emptyList<String>()
    private var requestedName=""
    private var placeName=""
    private var latitude=0.0
    private var longitude=0.0
    private var address=""
    private var gotDestination=false
    private var gotWaypoints=false
    private var deadline=0L
    val destinationLabel get()=destination?.name?.takeIf { it.isNotBlank() } ?: "내비 목적지 미설정"
    private fun log(s:String){if(!BuildConfig.DEBUG)return;File(app.filesDir,"navigation-ui.log").appendText("${System.currentTimeMillis()} $s\n")}
    private fun same(a:DestinationInfo?,b:DestinationInfo?)=a!=null&&b!=null&&a.name.isNotBlank()&&b.name.isNotBlank()&&abs(a.latitude-b.latitude)<0.0001&&abs(a.longitude-b.longitude)<0.0001
    private fun gangneung(d:DestinationInfo)=d.name.contains("강릉")|| (d.latitude in 37.6..37.9&&d.longitude in 128.7..129.1)
    private val listener=object:NaviHelperEventListener {
        override fun onDestinationChanged(){main.post{refresh()}}
        override fun onRouteCancelled(){main.post{refresh()}}
        override fun onDestinationInfo(destinationInfo:DestinationInfo){main.post {
            if(closed)return@post
            val previous=destination
            destination=destinationInfo;gotDestination=true
            log("DESTINATION $destinationInfo")
            evaluate();if(!busy&&(previous?.name!=destinationInfo.name||previous?.latitude!=destinationInfo.latitude||previous?.longitude!=destinationInfo.longitude))changed()
        }}
        override fun onWayPointInfo(waypointInfoList:List<WaypointInfo>){main.post {
            if(closed)return@post
            val previous=waypoints.map{it.name to it.address}
            waypoints=waypointInfoList;gotWaypoints=true
            log("WAYPOINTS $waypointInfoList")
            evaluate();if(!busy&&previous!=waypointInfoList.map{it.name to it.address})changed()
        }}
        override fun onError(errorCode:NaviErrorCode){main.post{if(!closed)log("NAVI_ERROR $errorCode phase=$phase")}}
    }
    init{helper.initialize(onConnected={main.post {
        if(!closed){connected=true;helper.addListener(listener);message="내비 연결됨";refresh();changed()}
    }},onFailed={main.post{if(!closed){message="내비 연결 실패: ${it.message}";changed()}}})}
    fun refresh(){if(connected&&!closed){helper.getDestinationInfo();helper.getWaypointInfo()}}
    fun add(name:String,lat:Double,lon:Double,placeAddress:String=""){
        if(busy||closed)return
        if(!connected){message="내비 연결 후 다시 시도해 주세요.";changed();return}
        if(unconfirmed){message="이전 추가의 반영 여부를 먼저 다시 조회해 주세요.";changed();return}
        val d=destination
        if(d==null||d.name.isBlank()){message="기본 내비에서 목적지를 먼저 설정해 주세요.";changed();return}
        if(!lat.isFinite()||!lon.isFinite()||lat !in -90.0..90.0||lon !in -180.0..180.0||(lat==0.0&&lon==0.0)){message="장소 좌표를 확인할 수 없어요.";changed();return}
        settingDestination=false
        expected=d;placeName=name;latitude=lat;longitude=lon;address=placeAddress
        gotDestination=false;gotWaypoints=false;phase="PREFLIGHT";busy=true;deadline=System.currentTimeMillis()+15000
        message="최신 목적지·기존 경유지 확인 중";log("PREFLIGHT expected=$d");changed();poll.run()
    }
    fun startDestination(name:String,lat:Double,lon:Double,placeAddress:String){
        if(busy||closed)return
        if(!connected||unconfirmed){message="내비 연결과 이전 요청 반영 상태를 먼저 확인해주세요.";changed();return}
        if(name.isBlank()||!lat.isFinite()||!lon.isFinite()||lat !in -90.0..90.0||lon !in -180.0..180.0||(lat==0.0&&lon==0.0)){message="장소 좌표를 확인할 수 없어요.";changed();return}
        settingDestination=true;placeName=name;latitude=lat;longitude=lon;address=placeAddress
        gotDestination=false;gotWaypoints=false;phase="PREFLIGHT_ROUTE";busy=true;deadline=System.currentTimeMillis()+15000
        message="최신 내비 목적지 확인 중";changed();poll.run()
    }
    fun recheck(){if(!unconfirmed||busy)return;phase=if(settingDestination)"VERIFY_ROUTE" else "VERIFY";busy=true;deadline=System.currentTimeMillis()+45000;message="내비 반영 상태 다시 확인 중";changed();poll.run()}
    private val poll=object:Runnable {override fun run(){
        if(closed||!busy)return
        if(System.currentTimeMillis()>deadline){
            unconfirmed=phase in listOf("VERIFY","VERIFY_ROUTE");phase="IDLE";busy=false
            message=if(unconfirmed) "반영을 확인하지 못했어요. 중복 요청 없이 현재 내비 상태를 다시 조회해 주세요." else "내비 응답을 받지 못했어요. 목적지를 확인하고 다시 시도해 주세요."
            log("TIMEOUT unconfirmed=$unconfirmed");changed();return
        }
        refresh();main.postDelayed(this,2000)
    }}
    private fun evaluate(){
        if(!busy)return
        if(phase=="PREFLIGHT_ROUTE"&&gotDestination&&gotWaypoints){
            if(!destination?.name.isNullOrBlank()||waypoints.isNotEmpty()){fail("내비에 목적지나 경유지가 설정됐어요. 기존 경로를 확인하고 경유지로 추가해주세요.");return}
            phase="VERIFY_ROUTE";unconfirmed=true;gotDestination=false;deadline=System.currentTimeMillis()+45000
            message="내비 목적지 반영 확인 중";changed()
            try{
                log("SET_DESTINATION_ONCE name=$placeName lat=$latitude lon=$longitude")
                helper.requestRoute(RouteInfo(latitude=latitude,longitude=longitude,displayLatitude=null,displayLongitude=null,poiName=placeName,poiId="",poiSubId="",address=address))
            }catch(e:Exception){busy=false;phase="IDLE";message="요청 결과가 불명확해요. 목적지 상태를 다시 조회해주세요.";changed()}
        }
        if(phase=="VERIFY_ROUTE"&&gotDestination){
            val actual=destination
            if(actual!=null&&actual.name==placeName&&abs(actual.latitude-latitude)<0.0001&&abs(actual.longitude-longitude)<0.0001){
                log("DESTINATION_VERIFIED $actual")
                phase="IDLE";busy=false;unconfirmed=false;main.removeCallbacks(poll)
                message="내비 목적지 반영을 확인했어요.";completed()
            }
        }
        if(phase=="PREFLIGHT"&&gotDestination&&gotWaypoints){
            if(!same(destination,expected)){fail("목적지가 변경됐어요. 확인 화면에서 다시 확인해 주세요.");return}
            if(waypoints.size>=2){fail("경유지가 이미 두 개예요. 기본 내비에서 정리한 뒤 추가해 주세요.");return}
            originalNames=waypoints.map{it.name}
            requestedName="$placeName · Muninn-${UUID.randomUUID().toString().take(8)}"
            phase="VERIFY";deadline=System.currentTimeMillis()+45000
            message="내비 경유지 반영 확인 중";unconfirmed=true
            changed()
            try{
                app.getSharedPreferences("drive",Context.MODE_PRIVATE).edit()
                    .putString("routeEditDestination","${expected?.name}|${expected?.latitude}|${expected?.longitude}")
                    .putLong("routeEditUntil",System.currentTimeMillis()+60000).apply()
                log("ADD_ONCE name=$requestedName slot=${waypoints.size} lat=$latitude lon=$longitude")
                helper.addWaypoint(RequestWaypointInfo(latitude=latitude,longitude=longitude,
                    waypointIndex=if(waypoints.isEmpty())WaypointIndex.FIRST else WaypointIndex.SECOND,
                    poiId="",poiSubId="",poiName=requestedName,address=address))
            }catch(e:Exception){busy=false;phase="IDLE";message="추가 요청 결과가 불명확해요. 경유지 상태를 다시 조회해 주세요.";log("ADD_EXCEPTION ${e.message}");changed()}
        }
        if(phase=="VERIFY"){
            val actual=waypoints.find{it.name==requestedName&&it.address.isNotBlank()}
            if(actual!=null&&same(destination,expected)&&originalNames.all{old->waypoints.any{it.name==old}}){
                log("VERIFIED name=$requestedName final=$destination waypoint=$actual")
                if(actual.latitude==0.0&&actual.longitude==0.0)log("LIMITATION waypoint coordinates 0,0; unique name and resolved address verified")
                phase="IDLE";busy=false;unconfirmed=false;main.removeCallbacks(poll)
                message="내비 경유지 반영과 최종 목적지 유지를 확인했어요.";completed()
            }
        }
    }
    private fun fail(reason:String){busy=false;phase="IDLE";main.removeCallbacks(poll);message=reason;log("BLOCKED $reason");changed()}
    fun close(){closed=true;main.removeCallbacksAndMessages(null);if(connected)helper.removeListener(listener);helper.release()}
}
