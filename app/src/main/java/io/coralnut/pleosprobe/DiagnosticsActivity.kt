package io.coralnut.pleosprobe

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Emulator-only diagnostic app. Logs API responses, never simulates success. */
class DiagnosticsActivity : Activity() {
    private lateinit var helper: NaviHelper
    private lateinit var output: TextView
    private val controls = mutableListOf<Button>()
    private var connected = false
    private var registered = false
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    companion object { @Volatile var visible = false }
    private fun arm(usePolling: Boolean, voice: Boolean = false, sample: String? = null) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 40)
            event("NOTIFICATION_PERMISSION_REQUIRED — 허용 후 감지 시작을 다시 선택")
            return
        }
        startForegroundService(Intent(this, DestinationMonitorService::class.java).putExtra("polling", usePolling).putExtra("voice", voice).putExtra("sample", sample))
        event("MONITOR_SERVICE_REQUEST polling=$usePolling; 상세 기록 files/monitor.log")
    }
    private fun event(message: String) {
        val entry = "${clock.format(Date())} $message"
        Log.i("CoralNutProbe", entry)
        runOnUiThread {
            File(filesDir, "probe.log").appendText("$entry\n")
            output.text = ("$entry\n\n${output.text}").take(18000)
        }
    }
    private fun request(name: String, action: () -> Unit) {
        if (!connected) { event("BLOCKED $name: SDK 미연결"); return }
        try { event("REQUEST $name (결과 대기)"); action() }
        catch (e: Exception) { event("ERROR $name: ${e.javaClass.simpleName}: ${e.message}") }
    }
    private val listener = object : NaviHelperEventListener {
        override fun onDestinationChanged() {
            event("EVENT onDestinationChanged visible=$visible")
            request("getDestinationInfo after change") { helper.getDestinationInfo() }
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            event("RESPONSE onDestinationInfo: $destinationInfo")
        }
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            event("EVENT onWaypointChanged: $waypointChangedInfo")
            request("getWaypointInfo after change") { helper.getWaypointInfo() }
        }
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            event("RESPONSE onWayPointInfo: $waypointInfoList")
        }
        override fun onRoutingCompleted() {
            event("EVENT onRoutingCompleted — 내비 경로 탐색 완료")
            request("getDestinationInfo after routing") { helper.getDestinationInfo() }
            request("getWaypointInfo after routing") { helper.getWaypointInfo() }
        }
        override fun onRouteCancelled() { event("EVENT onRouteCancelled") }
        override fun onError(errorCode: NaviErrorCode) { event("NAVI_ERROR $errorCode") }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            finish()
            return
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
        }
        val title = TextView(this).apply {
            text = "CoralNut · 내비 연동 확인\nPleos 에뮬레이터 전용 / 실제 SDK 응답 기록"
            textSize = 24f
        }
        column.addView(title)
        fun control(label: String, action: () -> Unit) {
            val button = Button(this).apply {
                text = label; textSize = 19f; isEnabled = false
                setOnClickListener { action() }
            }
            controls += button
            column.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 76))
        }
        control("음성 추천 시작 · 마이크로 응답") { arm(true, true) }
        control("음성 추천 검증 · 응 음성 샘플") { arm(true, true, "yes.pcm") }
        control("음성 추천 검증 · 거절 음성 샘플") { arm(true, true, "no.pcm") }
        control("백그라운드 감지 시작 · 이벤트만") { arm(false) }
        control("백그라운드 감지 시작 · 이벤트 + 3초 조회") { arm(true) }
        control("감지 중지") { stopService(Intent(this, DestinationMonitorService::class.java)); event("MONITOR_STOP_REQUEST") }
        control("감지 결과 보기") {
            val log = File(filesDir, "monitor.log")
            output.text = if (log.exists()) log.readText().takeLast(16000) else "아직 감지 기록이 없어요. 감지 시작을 눌러주세요."
        }
        control("1 · 현재 목적지 읽기") { request("getDestinationInfo") { helper.getDestinationInfo() } }
        control("2 · 현재 경유지 읽기") { request("getWaypointInfo") { helper.getWaypointInfo() } }
        control("테스트 목적지 설정 · 강릉역 주변 좌표") {
            confirm("에뮬레이터 내비 목적지를 강릉역 주변 테스트 좌표로 바꿀까요? 실제 POI 검색 결과가 아닌 좌표 기반 테스트예요.") {
                request("requestRoute: Gangneung test coordinates") {
                    helper.requestRoute(RouteInfo(latitude = 37.7644, longitude = 128.8996,
                        displayLongitude = null, displayLatitude = null, poiName = "강릉역 테스트 좌표",
                        poiId = "", poiSubId = "", address = "좌표 기반 테스트"))
                }
            }
        }
        control("3 · 테스트 경유지 추가 · 서울 좌표") {
            confirm("첫 경유지를 서울 테스트 좌표로 설정할까요? 기존 첫 경유지가 있으면 변경될 수 있어요.") {
                request("addWaypoint FIRST: Seoul test coordinates") {
                    helper.addWaypoint(RequestWaypointInfo(latitude = 37.48544722, longitude = 127.03636666,
                        waypointIndex = WaypointIndex.FIRST, poiId = "",
                        poiName = "서울 경유 테스트 좌표", poiSubId = "", address = "좌표 기반 경유 테스트"))
                }
            }
        }
        control("테스트 첫 경유지 제거") {
            confirm("에뮬레이터의 첫 경유지를 제거할까요?") {
                request("removeWaypoint FIRST") { helper.removeWaypoint(WaypointIndex.FIRST) }
            }
        }
        output = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(output) }
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(column)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("coralnut_recommendation", "Muninn 여행 추천 테스트", NotificationManager.IMPORTANCE_HIGH))
        helper = NaviHelper(applicationContext)
        event("LIFECYCLE onCreate; connecting NaviHelper 3.0.5")
        try {
            helper.initialize(onConnected = {
                connected = true
                helper.addListener(listener)
                registered = true
                runOnUiThread { controls.forEach { it.isEnabled = true } }
                event("CONNECTED — 목적지/경유지 응답으로 별도 확인 필요")
            }, onFailed = { error ->
                connected = false
                event("CONNECTION_FAILED ${error.javaClass.simpleName}: ${error.message}")
            })
        } catch (error: Exception) { event("INITIALIZE_ERROR ${error.message}") }
    }
    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle("테스트 내비 변경").setMessage(message)
            .setNegativeButton("취소", null).setPositiveButton("실행") { _, _ -> action() }.show()
    }
    override fun onStart() { super.onStart(); visible = true; MainActivity.visible = true; event("LIFECYCLE onStart visible=true") }
    override fun onStop() { visible = false; MainActivity.visible = false; super.onStop(); event("LIFECYCLE onStop — 화면 뒤에서도 listener 유지, 프로세스 생존 보장 아님") }
    override fun onDestroy() {
        if (registered) helper.removeListener(listener)
        helper.release()
        connected = false
        super.onDestroy()
    }
}
