package io.coralnut.pleosprobe

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.io.File
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

/** 기존 Muninn 계정과 공개 장소를 사용하는 차량 앱. */
class MainActivity:Activity(){
    companion object { @Volatile var visible=false }
    private enum class Screen { HOME,DETAIL,CONFIRM,DONE,WAIT,LOGIN,VOICE_RESULTS,VOICE_MISSED }
    private var voiceResult:DestinationMonitorService.Recommendation?=null
    private var screen=Screen.HOME
    private var places=emptyList<CoralnutPlace>()
    private var catalog=emptyList<CoralnutPlace>()
    private var browseRegion:String?=null
    private var browseAllRegions=false
    private var browseCreator:String?=null
    private var position:android.location.Location?=null
    private var nearbyMode=false
    private val hasDestination get()=::nav.isInitialized&&nav.destination?.name?.isNotBlank()==true
    private var locating=false
    private var locationVersion=0
    private var locationSignal:CancellationSignal?=null
    private var showingDomesticFallback=false
    private val defaultsToAllRegions get()=!hasDestination&&!nearbyMode
    private val allRegionsSelected get()=browseRegion==null&&(browseAllRegions||defaultsToAllRegions)
    private val referenceLabel get()=if(browseRegion!=null)browseRegion!! else if(!nearbyMode&&hasDestination) "${nav.destination!!.name} 주변" else "내 주변"
    private fun visiblePlaces(){
        showingDomesticFallback=false
        voiceResult?.let{places=it.places;return}
        if(catalog.none{it.region==browseRegion})browseRegion=null
        if(catalog.none{it.channelId==browseCreator&&(browseRegion==null||it.region==browseRegion)})browseCreator=null
        val region=browseRegion;val creator=browseCreator
        places=rankRecommendations(catalog.filter{p->(if(region!=null)p.region==region else if(allRegionsSelected||creator!=null)true else p.distanceKm<=20)&&(creator==null||p.channelId==creator)},if(allRegionsSelected||region!=null||creator!=null)Int.MAX_VALUE else 5)
        if(region==null&&creator==null&&places.isEmpty()&&catalog.isNotEmpty()){
            showingDomesticFallback=true
            places=rankRecommendations(catalog.map{it.copy(distanceKm=Double.POSITIVE_INFINITY)})
        }
    }
    private fun placeMeta(p:CoralnutPlace)=p.category+if(p.distanceKm.isFinite())" · 직선 %.1fkm".format(p.distanceKm)else " · ${p.region}"
    private val timelineSelections=mutableMapOf<String,String>()
    private var selected=0
    private var completedPlace:CoralnutPlace?=null
    private var addingPlace:CoralnutPlace?=null
    private lateinit var nav:NavigationBridge
    private lateinit var coral:CoralnutClient
    private lateinit var parking:ParkedVideoGuard
    private val main=Handler(Looper.getMainLooper())
    private var notice=""
    private var subscriptionStatus="GUEST"
    private var matchedChannelCount=0
    private val thumbnails=VideoThumbnails{log(it)}
    private var loading=false
    private var loadError=false
    private var requestVersion=0
    private var targetKey=""
    private var observedDestination=""
    private var linkGeneration=0L
    private var linkLoading=false
    private var link:JSONObject?=null
    private var linkDeadline=0L
    private var pendingShowId:String?=null
    private val currentDestination get()=if(::nav.isInitialized)nav.destinationLabel else "내비 연결 중"
    private val events=object:BroadcastReceiver(){
        override fun onReceive(context:Context?,intent:Intent?){
            val result=voiceResult
            if(result!=null&&DestinationMonitorService.recommendation?.id!=result.id&&!nav.busy){
                voiceResult=null
                pendingShowId=null
                if(screen in listOf(Screen.VOICE_RESULTS,Screen.VOICE_MISSED,Screen.DETAIL,Screen.CONFIRM))screen=Screen.HOME
                visiblePlaces()
                if(visible)render()
            }
            if(visible){consumeRecommendations();if(screen==Screen.WAIT)render()}
        }
    }
    private fun log(message:String){if(!BuildConfig.DEBUG)return;File(filesDir,"drive-ui.log").appendText("${System.currentTimeMillis()} $message\n")}
    private fun consumeRecommendations(){
        val prefs=getSharedPreferences("drive",MODE_PRIVATE);val ready=prefs.getString("ready",null)?:return
        if(prefs.getString("shown","")==ready||pendingShowId==ready||System.currentTimeMillis()-prefs.getLong("readyAt",0)>300000)return
        if(::nav.isInitialized&&nav.busy)return
        val result=DestinationMonitorService.recommendation?.takeIf{it.id==ready}?:return
        if(!parking.allowed)return
        pendingShowId=ready;prefs.edit().putString("prepared",ready).apply()
        voiceResult=result
        visiblePlaces()
        notice="";screen=if(result.missed)Screen.VOICE_MISSED else Screen.VOICE_RESULTS
        render()
        log("VOICE_UI_PREPARED id=$ready missed=${result.missed}")
        recordVisibleHome()
    }
    private fun recordVisibleHome(){val id=pendingShowId?:return
        if(!hasWindowFocus()||!parking.allowed||screen !in listOf(Screen.VOICE_RESULTS,Screen.VOICE_MISSED))return
        getSharedPreferences("drive",MODE_PRIVATE).edit().putString("shown",id).apply()
        pendingShowId=null
        log("VOICE_UI_SHOWN id=$id screen=$screen")
    }
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);if(hasFocus){consumeRecommendations();recordVisibleHome()}}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);coral=CoralnutClient.get(this)
        parking=ParkedVideoGuard(this,changed={allowed->
            if(allowed&&visible)consumeRecommendations()
            if(voiceResult!=null)render()
        },log={log(it)});render()
        registerReceiver(events,IntentFilter(DestinationMonitorService.UPDATE),Context.RECEIVER_NOT_EXPORTED)
        nav=NavigationBridge(this,changed={
            if(::nav.isInitialized){
                val destination=nav.destination
                val destinationKey=destination?.takeIf{it.name.isNotBlank()}?.let{"${it.name}|${it.latitude}|${it.longitude}"}.orEmpty()
                if(destinationKey!=observedDestination){
                    observedDestination=destinationKey
                    if(!nav.busy&&voiceResult?.destinationKey?.let{it!=destinationKey}==true){
                        voiceResult=null;pendingShowId=null;screen=Screen.HOME;visiblePlaces()
                    }
                    nearbyMode=false;browseRegion=null;browseAllRegions=false;browseCreator=null
                    visiblePlaces()
                }
                if(!nav.busy&&!nav.settingDestination&&completedPlace!=null&&nav.waypoints.none{it.name.startsWith("${completedPlace!!.name} · Muninn-")})completedPlace=null
                loadPlaces(false)
            };if(!isFinishing&&!isDestroyed)render()
        },completed={
            completedPlace=addingPlace;screen=Screen.DONE;notice="";render();log("WAYPOINT_COMPLETION_UI place=${completedPlace?.name}")
            packageManager.getLaunchIntentForPackage(packageName)?.let{startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))}
        })
        val restoreGeneration=linkGeneration
        coral.pendingLink{result->if(!isDestroyed&&!linkLoading&&restoreGeneration==linkGeneration&&screen==Screen.HOME)result.getOrNull()?.let{
            link=it;linkDeadline=it.getLong("expiresAt");screen=Screen.LOGIN;notice="로그인 연결을 이어서 확인하고 있어요.";render();main.post(linkPoll)
        }}
        loadPlaces(false)
        if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)locate()
        coral.account{result->result.exceptionOrNull()?.let{notice="계정을 확인하지 못했어요. 다시 시도해주세요."};if(!isDestroyed)render()}
    }
    private fun loadPlaces(force:Boolean){
        if(!::nav.isInitialized||nav.busy)return
        val destination=nav.destination?.takeIf{!nearbyMode&&it.name.isNotBlank()&&(it.latitude!=0.0||it.longitude!=0.0)}
        val lat=destination?.latitude ?: position?.latitude
        val lon=destination?.longitude ?: position?.longitude
        val key="${destination?.name}|$lat|$lon"
        if(!force&&key==targetKey)return
        targetKey=key;val version=++requestVersion
        loading=true;loadError=false
        if(voiceResult==null&&screen in listOf(Screen.DETAIL,Screen.CONFIRM))screen=Screen.HOME
        coral.catalog(lat,lon){result->
            if(isDestroyed||version!=requestVersion)return@catalog
            loading=false
            result.fold(onSuccess={catalog=it.places;subscriptionStatus=it.subscriptionStatus;matchedChannelCount=it.matchedChannelCount;visiblePlaces();log("DISCOVERY catalog=${catalog.size} visible=${places.size} reference=$key")},onFailure={
                loadError=true
                catalog=catalog.map{place->
                    val distance=if(place.hasCoordinates&&lat!=null&&lon!=null)CoralnutClient.distance(lat,lon,place.latitude,place.longitude) else Double.POSITIVE_INFINITY
                    place.copy(distanceKm=distance)
                }
                visiblePlaces()
                log("DISCOVERY_ERROR ${it.javaClass.simpleName}")
            })
            render()
        }
    }
    private fun locate(){
        if(locating)return
        if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),42);return}
        val manager=getSystemService(android.location.LocationManager::class.java)
        val provider=when{manager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)->android.location.LocationManager.GPS_PROVIDER;manager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)->android.location.LocationManager.NETWORK_PROVIDER;else->null}
        if(provider==null){notice="차량 위치를 켜거나 지역을 선택해주세요.";render();return}
        if(Build.VERSION.SDK_INT<30){notice="지역을 선택해 둘러볼 수 있어요.";render();return}
        val version=++locationVersion
        locating=true;locationSignal=CancellationSignal()
        manager.getCurrentLocation(provider,locationSignal,mainExecutor){location->
            if(isDestroyed||version!=locationVersion)return@getCurrentLocation
            locating=false;position=location
            notice=if(location==null)"현재 위치를 확인하지 못했어요. 지역을 선택해주세요." else ""
            loadPlaces(true);render()
        }
        main.postDelayed({if(locating&&version==locationVersion){locationVersion++;locationSignal?.cancel();locating=false;notice="위치 확인이 늦어지고 있어요. 지역을 먼저 둘러보세요.";render()}},12000)
        render()
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==42){if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)locate()else{notice="위치 권한 없이 지역과 크리에이터로 둘러볼 수 있어요.";render()}}
    }
    override fun onResume(){super.onResume();consumeRecommendations();if(::nav.isInitialized)nav.refresh()}
    override fun onNewIntent(intent:Intent?){super.onNewIntent(intent);consumeRecommendations()}
    override fun onStart(){super.onStart();visible=true;consumeRecommendations()}
    override fun onStop(){visible=false;super.onStop()}
    override fun onDestroy(){locationSignal?.cancel();if(::parking.isInitialized)parking.close();thumbnails.close();main.removeCallbacksAndMessages(null);unregisterReceiver(events);if(::nav.isInitialized)nav.close();super.onDestroy()}
    @Deprecated("Android back compatibility") override fun onBackPressed(){if(::nav.isInitialized&&nav.busy)return;if(screen==Screen.HOME)super.onBackPressed()else{if(screen==Screen.LOGIN)cancelVehicleLink();go(if(screen==Screen.DETAIL&&voiceResult!=null)Screen.VOICE_RESULTS else Screen.HOME)}}
    private fun go(next:Screen){if(::nav.isInitialized&&nav.busy)return;if(next==Screen.HOME){voiceResult=null;pendingShowId=null;visiblePlaces()};screen=next;render();if(next==Screen.CONFIRM)nav.refresh()}
    private fun openNavigation(){packageManager.getLaunchIntentForPackage("ai.umos.maps.android.navigation.app")?.let{startActivity(it)}}
    private fun startMonitoring(sample:String?){
        if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),41);notice="알림 권한을 허용한 뒤 다시 시작해주세요.";render();return}
        getSharedPreferences("drive",MODE_PRIVATE).edit().remove("ready").remove("shown").apply()
        startForegroundService(Intent(this,DestinationMonitorService::class.java).putExtra("polling",true).putExtra("voice",true).putExtra("sample",sample))
        voiceResult=null;pendingShowId=null;visiblePlaces()
        screen=Screen.WAIT;notice="";render();log("MONITOR_STARTED sample=${sample?:"MICROPHONE"}")
    }
    private fun chooseVoice(){
        if (!BuildConfig.DEBUG) {
            startMonitoring(null)
            return
        }
        AlertDialog.Builder(this).setTitle("음성 추천 입력 방식").setItems(arrayOf("응 음성 샘플로 시작","거절 음성 샘플로 시작","무음 샘플로 시작","실제 마이크로 시작")){_,which->startMonitoring(when(which){0->"yes.pcm";1->"no.pcm";2->"silence";else->null})}.setNegativeButton("취소",null).show()}
    private val homeGround=DrivePalette.ground
    private val homeSurface=DrivePalette.surface
    private val homeInk=DrivePalette.ink
    private val homeMuted=DrivePalette.muted
    private val homeLine=DrivePalette.line
    private lateinit var shell:LinearLayout
    private lateinit var panel:LinearLayout
    private fun d(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun bg(color:Int,radius:Int=0,border:Int?=null)=GradientDrawable().apply{
        setColor(color);cornerRadius=d(radius).toFloat();if(border!=null)setStroke(d(1),border)
    }
    private fun text(value:String,size:Float=22f,bold:Boolean=false,color:Int=homeInk)=TextView(this).apply{
        text=value;textSize=size;setTextColor(color);if(bold)setTypeface(typeface,Typeface.BOLD);includeFontPadding=false
    }
    private fun column()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun row()=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun space(parent:LinearLayout,height:Int){parent.addView(View(this),LinearLayout.LayoutParams(1,d(height)))}
    private fun divider(parent:LinearLayout){parent.addView(View(this).apply{setBackgroundColor(homeLine)},LinearLayout.LayoutParams(-1,d(1)))}
    private fun homeButton(label:String,primary:Boolean=false,action:()->Unit)=Button(this).apply{
        text=label;textSize=20f;isAllCaps=false;setPadding(d(16),d(8),d(16),d(8));minHeight=d(64)
        isEnabled=!(::nav.isInitialized&&nav.busy)
        stateListAnimator=null
        elevation=0f
        translationZ=0f
        backgroundTintList=null
        val selectedState=intArrayOf(android.R.attr.state_selected)
        val focusedState=intArrayOf(android.R.attr.state_focused)
        val surface=if(primary)DrivePalette.selected else DrivePalette.button
        setTextColor(android.content.res.ColorStateList(
            arrayOf(selectedState,intArrayOf()),
            intArrayOf(DrivePalette.selectedInk,if(primary)DrivePalette.selectedInk else homeInk)
        ))
        val states=android.graphics.drawable.StateListDrawable().apply{
            addState(selectedState+focusedState,bg(DrivePalette.selected,14,DrivePalette.selectedInk))
            addState(focusedState,bg(surface,14,DrivePalette.selectedInk))
            addState(selectedState,bg(DrivePalette.selected,14))
            addState(intArrayOf(),bg(surface,14,if(primary)null else homeLine))
        }
        background=android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x2219394b),states,bg(Color.WHITE,14)
        )
        alpha=if(isEnabled)1f else 0.45f;setOnClickListener{action()}
    }
    private fun homeText(value:String,size:Float=22f,bold:Boolean=false,color:Int=homeInk)=text(value,size,bold,color)
    private fun heading(value:String){panel.addView(homeText(value,34f,true));space(panel,16)}
    private fun body(value:String,size:Float=22f){panel.addView(homeText(value,size,false,homeMuted));space(panel,14)}
    private fun appHeader(back:String?=null,backAction:(()->Unit)?=null):LinearLayout{
        val compact=resources.configuration.screenWidthDp<700
        val header=row().apply{setPadding(d(if(compact)16 else 32),d(18),d(if(compact)16 else 32),d(18))}
        if(back!=null)header.addView(homeButton(back){backAction?.invoke()},LinearLayout.LayoutParams(d(if(compact)112 else 160),d(64)).apply{rightMargin=d(12)})
        if(!compact)header.addView(ImageView(this).apply{setImageResource(R.mipmap.ic_launcher);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(d(52),d(52)).apply{rightMargin=d(12)})
        header.addView(homeText("Muninn Drive",26f,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f))
        if(back==null)header.addView(homeButton(if(coral.userName==null)"로그인" else "내 계정"){if(coral.userName==null)beginLink()else showAccount()},LinearLayout.LayoutParams(d(if(compact)100 else 136),d(64)))
        header.addView(homeButton("더 보기"){ }.apply{setOnClickListener{homeMenu(it)}},LinearLayout.LayoutParams(d(if(compact)96 else 136),d(64)).apply{leftMargin=d(12)})
        return header
    }
    private fun homeMenu(anchor:View){
        PopupMenu(this,anchor).apply{
            menu.add("추천 기준").setOnMenuItemClickListener{
                val info=CoralnutRecommendations(places,subscriptionStatus,matchedChannelCount)
                AlertDialog.Builder(this@MainActivity).setTitle(info.subscriptionLabel).setMessage(info.subscriptionExplanation.replace("목적지", "추천 기준 위치")+"\n\n주변 추천은 반경 20km 이내예요. 지역·크리에이터 탐색은 해당 공개 장소를 영상별로 보여줘요. 실제 이동 시간은 기본 내비에서 확인해주세요.").setPositiveButton("확인",null).show();true
            }
            menu.add("음성 추천 설정").setOnMenuItemClickListener{chooseVoice();true}
            menu.add("기본 내비 열기").setOnMenuItemClickListener{openNavigation();true}
            if (BuildConfig.DEBUG) menu.add("연동 테스트").setOnMenuItemClickListener{startActivity(Intent(this@MainActivity,DiagnosticsActivity::class.java));true}
            show()
        }
    }
    private fun render(){
        if(isDestroyed)return
        if(screen in listOf(Screen.DETAIL,Screen.CONFIRM)&&selected !in places.indices)screen=Screen.HOME
        if(screen==Screen.HOME){home();return}
        shell=column().apply{setBackgroundColor(homeGround)}
        shell.addView(appHeader(when{screen==Screen.CONFIRM->"장소 상세";screen==Screen.DETAIL&&voiceResult!=null->"목적지 주변 추천";else->"추천 홈"}){
            if(screen==Screen.LOGIN)cancelVehicleLink()
            go(when{screen==Screen.CONFIRM->Screen.DETAIL;screen==Screen.DETAIL&&voiceResult!=null->Screen.VOICE_RESULTS;else->Screen.HOME})
        });divider(shell)
        panel=column().apply{setPadding(d(32),d(28),d(32),d(24))}
        shell.addView(ScrollView(this).apply{isFillViewport=true;addView(panel)},LinearLayout.LayoutParams(-1,0,1f))
        if(voiceResult!=null&&!parking.allowed&&screen!=Screen.DONE){
            heading("정차 후 추천을 볼 수 있어요")
            screenActions("기본 내비로 돌아가기"){openNavigation()}
            setContentView(shell)
            return
        }
        when(screen){Screen.VOICE_RESULTS->voiceRecommendations();Screen.VOICE_MISSED->voiceMissed();Screen.DETAIL->detail();Screen.CONFIRM->confirm();Screen.DONE->done();Screen.WAIT->waiting();Screen.LOGIN->login();else->Unit}
        setContentView(shell)
    }
    private fun screenActions(primary:String,secondary:String?=null,onSecondary:(()->Unit)?=null,onPrimary:()->Unit){
        val bar=row().apply{setPadding(d(32),d(16),d(32),d(20))}
        if(secondary!=null)bar.addView(homeButton(secondary){onSecondary?.invoke()},LinearLayout.LayoutParams(0,d(72),1f).apply{rightMargin=d(16)})
        bar.addView(homeButton(primary,true,onPrimary),LinearLayout.LayoutParams(0,d(72),if(secondary==null)1f else 2f))
        divider(shell);shell.addView(bar)
    }
    private fun waypointLabel(value:String)=value.replace(Regex(" · (?:Muninn|코랄넛)-[a-f0-9]{8}$"),"")
    private fun routeStop(label:String,value:String,selected:Boolean=false,parent:LinearLayout=panel){
        val item=row().apply{setPadding(0,d(18),0,d(18))}
        item.addView(View(this).apply{background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(selected)homeInk else homeMuted)}},LinearLayout.LayoutParams(d(10),d(10)).apply{rightMargin=d(22)})
        val words=column();words.addView(homeText(label,20f,false,homeMuted));space(words,8);words.addView(homeText(waypointLabel(value),28f,true))
        item.addView(words,LinearLayout.LayoutParams(0,-2,1f));parent.addView(item)
    }
    private fun home(){
        val width=resources.configuration.screenWidthDp
        shell=column().apply{setBackgroundColor(homeGround)}
        shell.addView(appHeader());divider(shell)
        panel=column().apply{setPadding(d(32),d(28),d(32),d(24))}
        val title=row()
        title.addView(homeText(if(browseCreator!=null)catalog.firstOrNull{it.channelId==browseCreator}?.creator ?: "크리에이터 장소" else if(allRegionsSelected||showingDomesticFallback)"국내 장소 둘러보기" else referenceLabel,34f,true),LinearLayout.LayoutParams(0,-2,1f))
        title.addView(homeButton("새로고침"){notice="";loadPlaces(true);render()},LinearLayout.LayoutParams(d(150),d(64)))
        panel.addView(title);space(panel,12)
        panel.addView(homeText(when{loading->"공개 장소를 확인하고 있어요.";loadError->"추천 연결 상태를 확인해주세요.";browseCreator!=null->"이 크리에이터가 소개한 장소를 골라보세요.";browseRegion!=null->"${browseRegion}에서 크리에이터가 소개한 장소";allRegionsSelected->"모든 국내 지역의 공개 장소를 영상별로 둘러보세요.";showingDomesticFallback->when{
            !hasDestination&&position==null->"목적지를 설정하거나 내 위치를 사용하면 주변 장소를 추천해요."
            else->"주변 20km에 추천 장소가 없어 국내 공개 장소를 보여드려요."
        };places.isNotEmpty()->if(places.any{it.subscribed})"구독 크리에이터의 장소부터 추천해요." else "크리에이터가 소개한 공개 장소를 골랐어요.";position==null&&(nearbyMode||!hasDestination)->"지역이나 크리에이터를 골라 여행을 시작해보세요.";else->"반경 20km에 장소가 없어요. 다른 지역을 둘러볼까요?"},22f,false,homeMuted));space(panel,20)
        val controls=row()
        controls.addView(homeButton(if(locating)"위치 확인 중…" else "내 위치 사용"){browseRegion=null;browseAllRegions=false;browseCreator=null;nearbyMode=true;locate()},LinearLayout.LayoutParams(d(190),d(64)))
        if((browseAllRegions&&!defaultsToAllRegions)||browseRegion!=null||browseCreator!=null)controls.addView(homeButton(if(defaultsToAllRegions)"전체로 돌아가기" else "주변으로 돌아가기"){browseRegion=null;browseAllRegions=false;browseCreator=null;visiblePlaces();render()},LinearLayout.LayoutParams(d(220),d(64)).apply{leftMargin=d(12)})
        panel.addView(controls);space(panel,24)
        if(notice.isNotBlank()){panel.addView(homeText(notice,20f));space(panel,18)}
        if(loading)panel.addView(statePanel("추천을 불러오는 중","주변과 국내 지역의 공개 장소를 확인하고 있어요."))
        if(loadError){
            panel.addView(statePanel("추천에 연결하지 못했어요",
                if(catalog.isEmpty())"네트워크를 확인한 뒤 다시 시도해주세요." else "이미 불러온 장소는 계속 둘러볼 수 있어요."){
                notice="";loadPlaces(true);render()
            })
            space(panel,20)
        }
        if(catalog.isNotEmpty()&&!loading) {
            val wide=width>=900
            val layout=LinearLayout(this).apply{orientation=if(wide)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL}
            val regions=column()
            regions.addView(homeText("국내 지역",24f,true))
            space(regions,12)
            val domesticCount=catalog.distinctBy{it.id}.size
            regions.addView(homeButton("전체 · ${domesticCount}곳",allRegionsSelected){
                browseAllRegions=true;browseRegion=null;browseCreator=null;visiblePlaces();render()
            }.apply{
                contentDescription="국내 지역 전체 · ${domesticCount}곳"
                isSelected=allRegionsSelected
            },LinearLayout.LayoutParams(-1,d(76)).apply{bottomMargin=d(10)})
            catalog.groupBy{it.region}.toSortedMap().forEach{(region,items)->
                regions.addView(homeButton("$region · ${items.distinctBy{it.id}.size}곳",browseRegion==region){
                    browseRegion=region;browseAllRegions=false;browseCreator=null;visiblePlaces();render()
                },LinearLayout.LayoutParams(-1,d(76)).apply{bottomMargin=d(10)})
            }
            layout.addView(regions,if(wide)LinearLayout.LayoutParams(d(240),-2).apply{rightMargin=d(24)} else LinearLayout.LayoutParams(-1,-2))
            val results=column()
            val regionPlaces=catalog.filter{browseRegion==null||it.region==browseRegion}
            val creators=regionPlaces.groupBy{it.channelId}.values.sortedWith(
                compareByDescending<List<CoralnutPlace>>{it.any{p->p.subscribed}}.thenBy{it.first().creator})
            results.addView(homeText("크리에이터",24f,true));space(results,12)
            val strip=row()
            strip.addView(homeButton("전체",browseCreator==null){browseCreator=null;visiblePlaces();render()},
                LinearLayout.LayoutParams(-2,d(72)).apply{rightMargin=d(10)})
            creators.forEach{items->strip.addView(creatorFilter(items.first(),items.distinctBy{it.id}.size),
                LinearLayout.LayoutParams(-2,d(72)).apply{rightMargin=d(10)})}
            results.addView(HorizontalScrollView(this).apply{addView(strip)})
            space(results,22)
            results.addView(homeText(when{browseRegion!=null->"${browseRegion}에서 발견";allRegionsSelected||showingDomesticFallback->"국내 공개 장소";else->"추천 장소"},26f,true))
            space(results,12)
            if(places.isEmpty())results.addView(statePanel("주변 추천 장소가 없어요","다른 국내 지역을 선택해 소개 영상을 둘러보세요."))
            val indexed=places.withIndex().toList()
            val videos=indexed.filter{it.value.video!=null}.groupBy{it.value.video!!.id}
            videos.values.forEach{group->
                results.addView(videoGroup(group,wide));space(results,12)
            }
            indexed.filter{it.value.video==null}.forEach{(index,place)->
                results.addView(discoveryCard(place,index,width-64));space(results,12)
            }
            layout.addView(results,if(wide)LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-1,-2))
            panel.addView(layout)
        }else if(!loading&&!loadError)panel.addView(statePanel("아직 공개된 장소가 없어요","새 장소가 공개되면 여기에서 볼 수 있어요."))
        shell.addView(ScrollView(this).apply{isFillViewport=true;addView(panel)},LinearLayout.LayoutParams(-1,0,1f))
        setContentView(shell)
    }

    private fun creatorFilter(place:CoralnutPlace,count:Int)=row().apply{
        val active=browseCreator==place.channelId
        background=bg(if(active)DrivePalette.selected else homeSurface,16,homeLine)
        setPadding(d(12),0,d(16),0)
        isFocusable=true
        isSelected=active
        val caption=(if(place.subscribed)"구독 중 · " else "")+"${count}곳"
        contentDescription="${place.creator} · $caption"
        addView(creatorAvatar(place),LinearLayout.LayoutParams(d(46),d(46)).apply{rightMargin=d(10)})
        val labels=column()
        val color=if(active)DrivePalette.selectedInk else homeInk
        labels.addView(homeText(place.creator,18f,true,color).apply{maxWidth=d(220);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
        labels.addView(homeText(caption,16f,false,color))
        addView(labels)
        setOnClickListener{browseCreator=if(active)null else place.channelId;visiblePlaces();render()}
    }

    private fun creatorAvatar(place:CoralnutPlace)=FrameLayout(this).apply{
        background=bg(homeLine,24);clipToOutline=true
        addView(homeText(place.creator.take(1),20f,true).apply{gravity=Gravity.CENTER},FrameLayout.LayoutParams(-1,-1))
        place.avatarUrl?.let{url->
            addView(ImageView(this@MainActivity).apply{
                scaleType=ImageView.ScaleType.CENTER_CROP
                thumbnails.bindAvatar(this,url)
            },FrameLayout.LayoutParams(-1,-1))
        }
    }

    private fun discoveryCard(place:CoralnutPlace,index:Int,width:Int,wide:Boolean=false)=LinearLayout(this).apply{
        orientation=if(wide)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        gravity=Gravity.CENTER_VERTICAL
        background=bg(homeSurface,16,homeLine)
        setPadding(d(12),d(12),d(12),d(12))
        place.video?.let{video->
            val height=(width-36).coerceAtLeast(120)*9/16
            addView(thumbnail(place,video).apply{
                background=bg(homeGround,10);clipToOutline=true
            },if(wide)LinearLayout.LayoutParams(0,d(height),1.1f).apply{rightMargin=d(20)}
                else LinearLayout.LayoutParams(-1,d(height)))
            if(!wide)space(this,10)
        }
        val info=column()
        info.addView(homeText(place.name,if(wide)32f else 25f,true))
        if(wide){
            space(info,10)
            val profile=row()
            profile.addView(creatorAvatar(place),LinearLayout.LayoutParams(d(36),d(36)).apply{rightMargin=d(10)})
            profile.addView(homeText(place.creator,20f,true))
            info.addView(profile);space(info,8)
            info.addView(homeText(placeMeta(place),20f,false,homeMuted))
            info.addView(homeText(place.address,18f,false,homeMuted))
        }else{
            place.video?.title?.takeIf{it.isNotBlank()}?.let{title->info.addView(homeText(title,18f,false,homeMuted).apply{
                maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
            })}
            info.addView(homeText("${place.creator} · ${placeMeta(place)}",18f,false,homeMuted))
        }
        space(info,10)
        info.addView(homeButton("장소 보기",wide){selected=index;go(Screen.DETAIL)},LinearLayout.LayoutParams(if(wide)d(180) else -1,d(64)))
        addView(info,if(wide)LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-1,-2))
    }
    private fun videoGroup(entries:List<IndexedValue<CoralnutPlace>>,wide:Boolean)=column().apply{
        val first=entries.first().value
        val video=checkNotNull(first.video)
        background=bg(homeSurface,16,homeLine)
        setPadding(d(16),d(16),d(16),d(16))
        val header=if(wide)row() else column()
        header.addView(thumbnail(first,video.copy(startSec=null),fullImage=true,cover=true).apply{
            background=bg(homeGround,10);clipToOutline=true
        },LinearLayout.LayoutParams(if(wide)d(360) else -1,-2).apply{
            if(wide)rightMargin=d(20) else bottomMargin=d(12)
        })
        val info=column()
        info.addView(homeText(video.title,24f,true))
        space(info,8)
        info.addView(homeText("${first.creator} · 소개 장소 ${entries.size}곳",18f,false,homeMuted))
        header.addView(info,if(wide)LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-1,-2))
        addView(header);space(this,18)
        addView(homeText("영상 타임라인",22f,true));space(this,8)
        val ordered=entries.sortedBy{it.value.video?.startSec ?: Int.MAX_VALUE}
        val strip=row()
        val track=HorizontalScrollView(this@MainActivity).apply{addView(strip)}
        addView(track,LinearLayout.LayoutParams(-1,-2))
        space(this,12)
        val selection=column().apply{
            background=bg(homeGround,12,homeLine)
            setPadding(d(16),d(14),d(16),d(14))
        }
        addView(selection)
        val chips=mutableListOf<Button>()
        fun select(entry:IndexedValue<CoralnutPlace>){
            val (index,place)=entry
            val clip=checkNotNull(place.video)
            timelineSelections[video.id]=place.id
            ordered.zip(chips).forEach{(candidate,button)->
                val active=candidate==entry
                button.isSelected=active
            }
            selection.removeAllViews()
            selection.addView(homeText(place.name,24f,true))
            space(selection,4)
            selection.addView(homeText("${place.category} · ${place.address}",18f,false,homeMuted))
            space(selection,12)
            val actions=row()
            clip.startSec?.let{seconds->
                val time="%d:%02d".format(seconds/60,seconds%60)
                actions.addView(homeButton("${time}부터 재생",true){openVideo(place,clip,keepScreen=true)},
                    LinearLayout.LayoutParams(0,d(64),1f).apply{rightMargin=d(10)})
            }
            actions.addView(homeButton("장소 보기"){selected=index;go(Screen.DETAIL)}.apply{
                contentDescription="${place.name} 장소 보기"
            },LinearLayout.LayoutParams(0,d(64),1f))
            selection.addView(actions)
        }
        ordered.forEach{entry->
            val place=entry.value
            val seconds=place.video?.startSec
            val label=seconds?.let{"%d:%02d".format(it/60,it%60)} ?: "시점 없음"
            val chip=homeButton("$label\n${place.name}"){select(entry)}.apply{
                maxLines=3
                ellipsize=android.text.TextUtils.TruncateAt.END
                gravity=Gravity.START or Gravity.CENTER_VERTICAL
            }
            chips.add(chip)
            strip.addView(chip,LinearLayout.LayoutParams(d(154),d(92)).apply{rightMargin=d(8)})
        }
        val initial=ordered.firstOrNull{it.value.id==timelineSelections[video.id]} ?: ordered.first()
        select(initial)
        track.post{track.scrollTo(chips[ordered.indexOf(initial)].left,0)}
    }
    private fun openVideo(place:CoralnutPlace,video:CoralnutVideo,keepScreen:Boolean=false){
        if(!parking.refresh()){Toast.makeText(this,"주차 상태를 확인한 뒤 영상을 재생할 수 있어요.",Toast.LENGTH_LONG).show();log("VIDEO_BLOCKED not parked or state unavailable");return}
        if(!keepScreen){selected=places.indexOfFirst{it.id==place.id}.coerceAtLeast(0);screen=Screen.DETAIL;render()}
        startActivity(Intent(this,PlaceVideoActivity::class.java).putExtra("videoId",video.id).putExtra("startSec",video.startSec ?: 0).putExtra("title",video.title))
    }
    private fun thumbnail(place:CoralnutPlace,video:CoralnutVideo,fullImage:Boolean=false,cover:Boolean=false)=
        object:FrameLayout(this){
            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
                val height=if(fullImage&&View.MeasureSpec.getMode(widthMeasureSpec)==View.MeasureSpec.EXACTLY){
                    View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(widthMeasureSpec)*9/16,View.MeasureSpec.EXACTLY)
                }else heightMeasureSpec
                super.onMeasure(widthMeasureSpec,height)
            }
        }.apply{
        contentDescription=if(cover)"${video.title} 처음부터 재생" else "${place.name} 소개 영상 재생";isClickable=true;isFocusable=true
        val image=ImageView(this@MainActivity).apply{
            contentDescription=if(cover)"${video.title} 썸네일" else "${place.name} 소개 영상 썸네일"
            scaleType=if(fullImage)ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(DrivePalette.button);setImageResource(android.R.drawable.ic_menu_gallery)
            thumbnails.bind(this,video)
        }
        addView(image,FrameLayout.LayoutParams(-1,-1))
        addView(text("▶  "+(video.startSec?.let{"%d:%02d".format(it/60,it%60)} ?: "처음부터"),18f,true,Color.WHITE).apply{
            background=bg(homeInk,6);setPadding(d(10),d(6),d(10),d(6))
        },FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.END).apply{setMargins(d(8),d(8),d(8),d(8))})
        setOnClickListener{openVideo(place,video,keepScreen=cover)}
    }
    private fun detail(){
        val p=places[selected]
        val width=resources.configuration.screenWidthDp
        val wide=width>=900
        val content=LinearLayout(this).apply{
            orientation=if(wide)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity=if(wide)Gravity.CENTER_VERTICAL else Gravity.TOP
        }
        val info=column()
        info.addView(homeText(p.name,46f,true));space(info,10)
        info.addView(homeText(placeMeta(p),22f));space(info,8)
        info.addView(homeText(p.address,20f,false,homeMuted));space(info,14)
        info.addView(homeText((if(p.subscribed)"내 구독 · " else "")+p.creator,22f,true))
        p.video?.let{video->
            space(info,18)
            info.addView(thumbnail(p,video,fullImage=true).apply{
                clipToOutline=true;outlineProvider=ViewOutlineProvider.BACKGROUND;background=bg(homeSurface,16)
            },LinearLayout.LayoutParams(-1,-2))
            space(info,8)
            info.addView(homeText(video.title,20f,false,homeMuted).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END})
            space(info,6)
            info.addView(homeText("주차 중 재생",18f,false,homeMuted))
        }
        if(p.quote.isNotBlank()){space(info,16);info.addView(homeText(p.quote,20f,false,homeMuted))}
        content.addView(info,if(wide)LinearLayout.LayoutParams(0,-2,1.15f).apply{rightMargin=d(32)} else LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=d(24)})
        val preview=column().apply{
            background=bg(homeSurface,16,homeLine)
            setPadding(d(24),d(20),d(24),d(20))
        }
        preview.addView(homeText("경로 미리보기",26f,true))
        when{
            !p.hasCoordinates->{
                space(preview,12)
                preview.addView(homeText("위치 좌표 등록 후 경로를 볼 수 있어요.",20f,false,homeMuted))
            }
            !nav.connected->{
                space(preview,12)
                preview.addView(homeText("기본 내비 상태를 확인하고 있어요.",20f,false,homeMuted))
            }
            hasDestination->{
                nav.waypoints.forEachIndexed{i,waypoint->routeStop("기존 경유지 ${i+1}",waypoint.name,parent=preview)}
                routeStop("추가할 경유지",p.name,true,preview)
                divider(preview)
                routeStop("최종 목적지",currentDestination,parent=preview)
            }
            else->routeStop("목적지",p.name,true,preview)
        }
        content.addView(preview,if(wide)LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-1,-2))
        panel.addView(content)
        if(nav.unconfirmed)screenActions("반영 상태 다시 조회"){nav.recheck()}
        else if(!p.hasCoordinates){
            space(panel,16)
            body("아직 위치 좌표가 없어 내비로 보낼 수 없어요.",20f)
        }
        else if(::nav.isInitialized&&nav.destination?.name?.isNotBlank()==true)screenActions("경유지로 추가"){go(Screen.CONFIRM)}
        else screenActions(if(nav.unconfirmed)"반영 상태 다시 조회" else if(nav.busy)"내비 반영 확인 중…" else "여기로 출발"){
            if(nav.unconfirmed)nav.recheck()else{addingPlace=p;nav.startDestination(p.name,p.latitude,p.longitude,p.address)}
        }
        if(nav.message!="내비 연결됨"){space(panel,16);body(nav.message,20f)}
    }
    private fun waypointScreen(place:CoralnutPlace,completed:Boolean){
        val wide=resources.configuration.screenWidthDp>=900
        val destination=currentDestination
        val content=LinearLayout(this).apply{
            orientation=if(wide)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(0,d(24),0,d(24))
        }
        val info=column()
        val title=if(completed)"${place.name}을 경유지로 추가했어요" else "${place.name}에 들렀다 갈까요?"
        info.addView(homeText(title,40f,true));space(info,14)
        val summary=if(completed)"기본 내비에서 반영을 확인했어요.\n${place.name}을 들른 뒤 ${destination}로 안내해요."
            else "${place.name}을 경유지로 추가해요.\n최종 목적지는 ${destination}로 유지돼요."
        info.addView(homeText(summary,22f,false,homeMuted));space(info,20)
        val profile=row()
        profile.addView(creatorAvatar(place),LinearLayout.LayoutParams(d(46),d(46)).apply{rightMargin=d(12)})
        val caption=column()
        caption.addView(homeText("${place.creator} · ${place.name}",20f,true))
        caption.addView(homeText(place.address,18f,false,homeMuted))
        profile.addView(caption,LinearLayout.LayoutParams(0,-2,1f))
        info.addView(profile)
        content.addView(info,if(wide)LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=d(32)} else LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=d(24)})

        val route=column().apply{
            setPadding(d(24),d(20),d(24),d(20))
            background=bg(homeSurface,16,homeLine)
        }
        route.addView(homeText(if(completed)"반영된 경로" else "추가 후 경로",26f,true))
        nav.waypoints.forEachIndexed{i,waypoint->
            val added=completed&&waypointLabel(waypoint.name)==place.name&&i==nav.waypoints.lastIndex
            routeStop(if(added)"경유지" else "기존 경유지 ${i+1}",waypoint.name,added,route)
        }
        if(!completed)routeStop("추가할 경유지",place.name,true,route)
        divider(route)
        routeStop("최종 목적지",destination,parent=route)
        content.addView(route,if(wide)LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-1,-2))
        panel.addView(content)
    }
    private fun confirm(){
        val p=places[selected]
        waypointScreen(p,false)
        if(nav.message!="내비 연결됨")body(nav.message,20f)
        body("이동 시간은 기본 내비에서 다시 계산해요.",20f)
        val checking=nav.unconfirmed&&!nav.busy
        screenActions(if(checking)"반영 상태 다시 조회" else if(nav.busy)"내비 반영 확인 중…" else "확인하고 내비에 추가","취소",{go(Screen.DETAIL)}){
            if(checking)nav.recheck()else{addingPlace=p;nav.add(p.name,p.latitude,p.longitude,p.address)}
        }
    }
    private fun done(){
        val place=completedPlace
        if(!nav.settingDestination&&place!=null){
            waypointScreen(place,true)
            screenActions("기본 내비로 돌아가기","추천 홈으로",{go(Screen.HOME)}){openNavigation()}
            return
        }
        heading(if(nav.settingDestination)"목적지로 설정했어요" else "현재 경로")
        routeStop("목적지",place?.name ?: currentDestination,true)
        if(nav.settingDestination)body("기본 내비에서 선택한 장소의 이름과 좌표를 확인했어요.")
        screenActions("기본 내비에서 확인","추천 홈으로 돌아가기",{go(Screen.HOME)}){openNavigation()}
    }
    private fun voiceRecommendations(){
        val result=voiceResult?:return
        heading("${result.destination} 주변")
        body("목적지 주변에서 크리에이터가 소개한 장소 ${places.size}곳")
        val width=resources.configuration.screenWidthDp
        val columns=if(width>=900&&places.size>1)2 else 1
        places.withIndex().chunked(columns).forEach{entries->
            val cards=row().apply{gravity=Gravity.TOP}
            entries.forEach{(index,place)->
                cards.addView(discoveryCard(place,index,((width-64)/columns).coerceAtMost(650),wide=width>=900&&columns==1),LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=d(12)})
            }
            if(columns==2&&entries.size==1)cards.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
            panel.addView(cards);space(panel,12)
        }
    }
    private fun voiceMissed(){
        val result=voiceResult?:return
        heading("음성 응답을 듣지 못했어요")
        body("${result.destination} 안내는 계속돼요.")
        val recovery=column().apply{
            background=bg(homeSurface,16,homeLine);setPadding(d(24),d(20),d(24),d(20))
        }
        recovery.addView(homeText("목적지 주변 추천을 다시 열까요?",26f,true))
        space(recovery,12)
        recovery.addView(homeText("질문을 다시 듣거나 화면에서 바로 추천을 볼 수 있어요.",20f,false,homeMuted))
        space(recovery,16)
        if(DestinationMonitorService.isMonitoring)recovery.addView(homeButton("질문 다시 듣기"){
            startService(Intent(this,DestinationMonitorService::class.java).setAction(DestinationMonitorService.RETRY).putExtra("id",result.id))
            voiceResult=null;pendingShowId=null;visiblePlaces()
            go(Screen.WAIT);openNavigation()
        })
        panel.addView(recovery)
        screenActions("추천 보기","내비로 돌아가기",{go(Screen.HOME);openNavigation()}){go(Screen.VOICE_RESULTS)}
    }
    private fun statePanel(title:String,description:String,retry:(()->Unit)?=null)=column().apply{
        background=bg(homeSurface,16,homeLine)
        setPadding(d(24),d(20),d(24),d(20))
        addView(homeText(title,24f,true))
        space(this,8)
        addView(homeText(description,20f,false,homeMuted))
        retry?.let{
            space(this,14)
            addView(homeButton("연결 다시 시도",true,it),LinearLayout.LayoutParams(-2,d(64)))
        }
    }
    private fun waiting(){
        val active=DestinationMonitorService.isMonitoring
        heading(if(active)"새 목적지를 기다리고 있어요" else "음성 추천을 시작할 수 있어요")
        body(if(active)"새 목적지 주변에 크리에이터 장소가 있으면 먼저 물어볼게요." else "기본 내비에서 목적지를 설정하기 전에 음성 추천을 시작해주세요.")
        panel.addView(statePanel(if(active)"목적지 감지 중" else "목적지 감지 꺼짐",
            if(hasDestination)"현재 목적지 · $currentDestination" else "기본 내비에서 목적지를 설정해주세요."))
        screenActions(if(active)"기본 내비 열기" else "음성 추천 설정",if(active)"감지 중지" else "추천 홈으로",{
            if(active)stopService(Intent(this,DestinationMonitorService::class.java))
            go(Screen.HOME)
        }){if(active)openNavigation()else chooseVoice()}
    }
    private fun cancelVehicleLink(){++linkGeneration;linkLoading=false;link=null;main.removeCallbacks(linkPoll);coral.cancelLink()}
    private fun beginLink(){if(linkLoading)return;val generation=++linkGeneration;main.removeCallbacks(linkPoll);linkLoading=true;screen=Screen.LOGIN;link=null;notice="연결 코드를 만들고 있어요.";render();coral.createLink{result->if(isDestroyed||screen!=Screen.LOGIN||generation!=linkGeneration)return@createLink;linkLoading=false;result.fold(onSuccess={link=it;linkDeadline=it.getLong("expiresAt");notice="";render();main.postDelayed(linkPoll,5000)},onFailure={notice="차량 연결 서버에 연결하지 못했어요. 다시 시도해주세요.";render()})}}
    private val linkPoll=object:Runnable{override fun run(){val grant=link?:return;if(screen!=Screen.LOGIN||isDestroyed)return
        if(System.currentTimeMillis()>linkDeadline){cancelVehicleLink();notice="연결 코드가 만료됐어요. 새 코드를 만들어주세요.";render();return}
        coral.pollLink(grant.getString("deviceCode")){result->if(isDestroyed||screen!=Screen.LOGIN||link!==grant)return@pollLink
            result.fold(onSuccess={when(it){"AUTHORIZED"->{link=null;screen=Screen.HOME;notice="${coral.userName} 계정으로 연결했어요.";log("VEHICLE_LOGIN_VERIFIED");loadPlaces(true);render()};"EXPIRED"->{cancelVehicleLink();notice="코드가 만료되었거나 사용됐어요.";render()};else->main.postDelayed(this,5000)}},onFailure={notice="연결 상태 확인에 실패했어요. 재시도 중이에요.";render();main.postDelayed(this,5000)})
        }
    }}
    private fun login(){
        val title="휴대폰으로 차량을 연결하세요"
        val grant=link
        val widthDp=resources.configuration.screenWidthDp
        val wide=widthDp>=700
        if(grant==null||!wide)heading(title)
        if(notice.isNotBlank())body(notice)
        if(grant!=null){
            val url=grant.getString("verificationUri");val local=android.net.Uri.parse(url).host in listOf("localhost","127.0.0.1","10.0.2.2")
            val matrix=MultiFormatWriter().encode(url,BarcodeFormat.QR_CODE,384,384)
            val bitmap=Bitmap.createBitmap(384,384,Bitmap.Config.RGB_565)
            for(y in 0 until 384)for(x in 0 until 384)bitmap.setPixel(x,y,if(matrix[x,y])Color.BLACK else Color.WHITE)
            val content=LinearLayout(this).apply{orientation=if(wide)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL}
            val qrSize=d(if(wide)((widthDp-64)*0.45f).toInt().coerceAtMost(360) else (widthDp-64).coerceIn(160,300))
            content.addView(ImageView(this).apply{
                setImageBitmap(bitmap)
                contentDescription="Muninn 차량 연결 QR"
                setPadding(d(16),d(16),d(16),d(16))
                background=bg(Color.WHITE,20,homeLine)
            },LinearLayout.LayoutParams(qrSize,qrSize).apply{
                if(wide)rightMargin=d(32) else bottomMargin=d(24)
            })
            val info=column()
            if(wide){info.addView(homeText(title,34f,true));space(info,16)}
            info.addView(homeText(if(local)"이 Mac의 브라우저에서 연결 주소를 열어주세요." else "카메라로 QR을 찍고 Muninn 계정으로 로그인하세요.",24f,true));space(info,24)
            info.addView(homeText("차량 연결 코드",20f,false,homeMuted));space(info,12)
            info.addView(homeText(grant.getString("userCode"),34f,true));space(info,20)
            info.addView(homeText("두 화면의 코드가 같은지 확인하세요.",22f,false,homeMuted));space(info,16)
            info.addView(homeText("발급 후 10분 동안 유효해요.",20f,false,homeMuted));space(info,16)
            info.addView(homeText("휴대폰에서 연결을 기다리는 중",20f,true))
            if(local){space(info,16);info.addView(homeText(url,18f,false,homeMuted).apply{setTextIsSelectable(true)})}
            content.addView(info,if(wide)LinearLayout.LayoutParams(0,-2,1f)else LinearLayout.LayoutParams(-1,-2));panel.addView(content)
        }else if(!linkLoading)body("새 연결 코드를 만들거나 로그인 없이 먼저 둘러볼 수 있어요.")
        screenActions("로그인 없이 둘러보기",if(linkLoading)"코드 생성 중…" else "새 연결 코드 만들기",{if(!linkLoading)beginLink()}){cancelVehicleLink();go(Screen.HOME)}
    }
    private fun showAccount(){AlertDialog.Builder(this).setTitle(coral.userName?:"Muninn 계정").setMessage("이 차량의 로그인만 해제할 수 있어요. 휴대폰 로그인은 유지돼요.").setPositiveButton("차량 로그아웃"){_,_->coral.logout{result->notice=if(result.isSuccess)"차량 로그인을 해제했어요." else "로그아웃에 실패했어요. 다시 시도해주세요.";loadPlaces(true);render()}}.setNegativeButton("닫기",null).show()}
}
