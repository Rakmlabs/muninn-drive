package io.coralnut.pleosprobe
import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.DestinationInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Emulator probe only. No auto-start or restart after user/process termination. */
class DestinationMonitorService : Service() {
    data class Recommendation(val id:String,val destination:String,val destinationKey:String,val places:List<CoralnutPlace>,val missed:Boolean)
    companion object {
        const val UPDATE="io.coralnut.pleosprobe.RECOMMENDATIONS_READY"
        const val RETRY="io.coralnut.pleosprobe.RETRY_VOICE"
        var isMonitoring=false
            private set
        var recommendation:Recommendation?=null
            private set
    }
    private var deferredId:String?=null
    private fun publishUpdate()=sendBroadcast(Intent(UPDATE).setPackage(packageName))
    private fun clearRecommendation(){
        recommendation=null
        deferredId=null
        getSharedPreferences("drive",MODE_PRIVATE).edit().remove("ready").apply()
        getSystemService(NotificationManager::class.java).cancel(102)
        publishUpdate()
    }
    private var candidates=emptyList<CoralnutPlace>()
    private lateinit var parking:ParkedVideoGuard
    private lateinit var helper: NaviHelper
    private var registered = false
    private var voice: VoiceRecommendation? = null
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private fun event(message: String) {
        if (!BuildConfig.DEBUG) return
        val line = "${clock.format(Date())} $message"
        android.util.Log.i("CoralNutMonitor", line)
        File(filesDir, "monitor.log").appendText("$line\n")
    }
    private fun request(name: String, action: () -> Unit) {
        try { event("REQUEST $name"); action() } catch (e: Exception) { event("ERROR $name ${e.message}") }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val sessionTimeout=Runnable { event("MONITOR_TIMEOUT 10 minutes");stopSelf() }
    private var monitoring = false
    private var polling = false
    private var baselinePending = false
    private var lastDestination = ""
    private var activeDestination: DestinationInfo? = null
    private var voiceDestinationKey = ""
    private var source = "manual"
    private val poll = object : Runnable {
        override fun run() {
            if (!monitoring || !polling) return
            source = "POLL"
            request("background poll visible=${MainActivity.visible}") { helper.getDestinationInfo() }
            handler.postDelayed(this, 3000)
        }
    }
    private fun inspectDestination(info: DestinationInfo) {
        if (!monitoring) return
        val key = "${info.name}|${info.latitude}|${info.longitude}"
        if (baselinePending) {
            baselinePending = false
            lastDestination = key
            event("BASELINE_RECORDED $key")
            return
        }
        // Empty state during route calculation is not a new recommendation target.
        if (info.name.isBlank() || (info.latitude == 0.0 && info.longitude == 0.0) || key == lastDestination) return
        val prefs=getSharedPreferences("drive",MODE_PRIVATE)
        if(prefs.getString("routeEditDestination","")==key && System.currentTimeMillis()<prefs.getLong("routeEditUntil",0)) {
            lastDestination=key;activeDestination=info
            event("OWN_ROUTE_RECALCULATION ignored recommendation destination=$key");return
        }
        voice?.cancel()
        clearRecommendation()
        candidates=emptyList()
        lastDestination = key
        activeDestination = info
        event("DESTINATION_DETECTED source=$source visible=${MainActivity.visible} destination=$key")
        val destinationKey=key
        CoralnutClient.get(this).recommendations(info.latitude,info.longitude){ result ->
            if(!monitoring||lastDestination!=destinationKey)return@recommendations
            result.fold(onSuccess={
                if(it.places.isEmpty()){event("NO_RECOMMENDATION no public CoralNut places within 20km");return@fold}
                event("REAL_RECOMMENDATION_CANDIDATES count=${it.places.size}")
                candidates=it.places
                voiceDestinationKey=destinationKey
                voice?.destination(info.name)
            },onFailure={event("NO_RECOMMENDATION CoralNut request failed ${it.javaClass.simpleName}")})
        }

    }


    private fun showRecommendations(missed:Boolean) {
        val destination = activeDestination
        if(destination == null || voiceDestinationKey != lastDestination) {
            event("UI_OPEN_SKIPPED stale destination"); return
        }
        val id=java.util.UUID.randomUUID().toString()
        recommendation=Recommendation(id,destination.name,lastDestination,candidates,missed)
        getSharedPreferences("drive", MODE_PRIVATE).edit()
            .putString("ready", id).putLong("readyAt",System.currentTimeMillis())
            .putString("destination",destination.name).apply()
        publishUpdate()
        if(parking.refresh())openRecommendation(id)
        else{deferredId=id;event("UI_OPEN_DEFERRED driving or restriction state unavailable")}
    }
    private fun openRecommendation(id:String){
        val current=recommendation?.takeIf{it.id==id}?:return
        val prefs=getSharedPreferences("drive",MODE_PRIVATE)
        if(System.currentTimeMillis()-prefs.getLong("readyAt",0)>300000||prefs.getString("shown","")==id)return
        val open=(packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this,MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            startActivity(open)
            event("UI_OPEN_REQUEST id=$id; standard launcher intent")
        }
        catch(e:Exception){event("UI_OPEN_ERROR ${e.message}")}
        handler.postDelayed({
            if(recommendation?.id==id&&parking.allowed&&prefs.getString("shown","")!=id){
                if(getSharedPreferences("drive",MODE_PRIVATE).getString("prepared","")==id){
                    event("UI_HOME_PREPARED_WITHOUT_FOCUS id=$id; visual visibility requires external verification")
                    return@postDelayed
                }
                event("UI_OPEN_NOT_CONFIRMED id=$id; user can open CoralNut to view")
                val click=PendingIntent.getActivity(this,2,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                getSystemService(NotificationManager::class.java).notify(102,
                    Notification.Builder(this,"coralnut_recommendation").setSmallIcon(android.R.drawable.ic_dialog_map)
                        .setContentTitle(if(current.missed)"Muninn 음성 응답을 듣지 못했어요" else "Muninn 추천이 준비됐어요").setContentText("Muninn을 열어 장소를 확인하세요.")
                        .setContentIntent(click).setAutoCancel(true).build())
            }
        },4000)
    }

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationChanged() { handler.post {
            source = "EVENT onDestinationChanged"
            event("EVENT onDestinationChanged visible=${MainActivity.visible}")
            request("destination after event") { helper.getDestinationInfo() }
        } }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) { handler.post {
            event("RESPONSE onDestinationInfo: $destinationInfo")
            inspectDestination(destinationInfo)
        } }
        override fun onRouteCancelled() { handler.post {
            lastDestination = ""
            activeDestination = null
            clearRecommendation()
            voice?.cancel()
            getSystemService(NotificationManager::class.java).cancel(101)
            event("EVENT onRouteCancelled; recommendation reset")
        } }
        override fun onRoutingCompleted() { handler.post {
            source = "EVENT onRoutingCompleted"
            event(source)
            request("destination after routing") { helper.getDestinationInfo() }
        } }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("monitor", "목적지 감지 실행 상태", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("coralnut_recommendation", "Muninn 여행 추천 테스트", NotificationManager.IMPORTANCE_HIGH))
        startForeground(100, Notification.Builder(this, "monitor").setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Muninn 목적지 감지 테스트 중")
            .setContentText("테스트 앱에서 감지 중지로 종료할 수 있어요.").setOngoing(true).build())
        parking=ParkedVideoGuard(this,changed={allowed->
            if(allowed)deferredId?.let{id->deferredId=null;openRecommendation(id)}
        },log={event(it)})
        helper = NaviHelper(applicationContext)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action==RETRY){
            val current=recommendation
            if(monitoring&&current?.missed==true&&current.destinationKey==lastDestination&&current.id==intent.getStringExtra("id")){
                clearRecommendation()
                voice?.destination(current.destination)
            }
            if(!monitoring)stopSelf()
            return START_NOT_STICKY
        }
        clearRecommendation()
        voice?.release(); voice = null
        if (intent?.getBooleanExtra("voice", false) == true) {
            voice = VoiceRecommendation(this, if (BuildConfig.DEBUG) intent.getStringExtra("sample") else null, onAccepted = { showRecommendations(false) }, onMissed = { showRecommendations(true) }) { event(it) }
        }
        polling = intent?.getBooleanExtra("polling", false) == true
        monitoring = true
        isMonitoring = true
        publishUpdate()
        baselinePending = true
        handler.removeCallbacks(poll)
        event("MONITOR_START mode=${if (polling) "EVENT_AND_POLL" else "EVENT_ONLY"} FOREGROUND_SERVICE")
        if (registered) {
            source = "BASELINE"; helper.getDestinationInfo()
            if (polling) handler.postDelayed(poll, 3000)
        } else helper.initialize(onConnected = { handler.post {
            helper.addListener(listener); registered = true
            event("CONNECTED")
            source = "BASELINE"; helper.getDestinationInfo()
            if (polling) handler.postDelayed(poll, 3000)
        } }, onFailed = { event("CONNECTION_FAILED $it"); stopSelf() })
        // Bound this diagnostic session; do not leave indefinite queries running.
        handler.removeCallbacks(sessionTimeout)
        handler.postDelayed(sessionTimeout,600000)
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        monitoring = false
        isMonitoring = false
        deferredId=null
        publishUpdate()
        voice?.release(); voice = null
        handler.removeCallbacksAndMessages(null)
        if (registered) helper.removeListener(listener)
        helper.release()
        parking.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        event("MONITOR_STOPPED")
        super.onDestroy()
    }
}
