package io.coralnut.pleosprobe

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Calls the actual SDK service in the dedicated emulator; no UI or mock server. */
class ProbeInstrumentation : Instrumentation() {
    private val main = Handler(Looper.getMainLooper())
    private val lines = mutableListOf<String>()
    private lateinit var helper: NaviHelper
    @Volatile private var destination: DestinationInfo? = null
    @Volatile private var waypoints: List<WaypointInfo> = emptyList()
    @Volatile private var destinationLatch = CountDownLatch(1)
    @Volatile private var waypointsLatch = CountDownLatch(1)
    @Volatile private var routeLatch = CountDownLatch(1)
    private fun record(s: String) { synchronized(lines) { lines += s }; Log.i("CoralNutProbeTest", s) }
    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            destination = destinationInfo; record("DESTINATION $destinationInfo"); destinationLatch.countDown()
        }
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            waypoints = waypointInfoList; record("WAYPOINTS $waypointInfoList"); waypointsLatch.countDown()
        }
        override fun onRoutingCompleted() { record("ROUTING_COMPLETED"); routeLatch.countDown() }
        override fun onDestinationChanged() { record("DESTINATION_CHANGED") }
        override fun onError(errorCode: NaviErrorCode) { record("NAVI_ERROR $errorCode") }
    }
    private var mode=""
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments);mode=arguments?.getString("mode") ?: ""; start() }
    override fun onStart() {
        if(mode=="vehicle-link-ui"){VehicleLinkUiProbe.run(this);return}
        if(mode=="subscriptions"){SubscriptionRecommendationProbe.run(this);return}
        if(mode=="real-api"||mode=="api-only"){RealApiProbe.run(this,mode);return}
        var passed = false
        try {
            val connection = CountDownLatch(1)
            var connected = false
            main.post {
                try {
                    helper = NaviHelper(targetContext)
                    helper.initialize(onConnected = {
                        connected = true; helper.addListener(listener); record("CONNECTED"); connection.countDown()
                    }, onFailed = { record("CONNECTION_FAILED $it"); connection.countDown() })
                } catch (e: Exception) { record("INITIALIZE_ERROR $e"); connection.countDown() }
            }
            check(connection.await(25, TimeUnit.SECONDS) && connected) { "NaviHelper connection did not succeed" }
            // An existing user-selected destination is read before changing the emulator route.
            main.post { helper.getDestinationInfo() }
            record("INITIAL_READ_RESPONSE=${destinationLatch.await(8, TimeUnit.SECONDS)}")
            destinationLatch = CountDownLatch(1)
            main.post {
                try { helper.requestRoute(RouteInfo(latitude = 37.7644, longitude = 128.8996,
                    displayLongitude = null, displayLatitude = null, poiName = "강릉역 테스트 좌표",
                    poiId = "", poiSubId = "", address = "좌표 기반 테스트")) }
                catch (e: Exception) { record("ROUTE_EXCEPTION $e") }
            }
            var destinationMatches = false
            for (attempt in 1..25) {
                destinationLatch = CountDownLatch(1)
                main.post { helper.getDestinationInfo() }
                destinationLatch.await(3, TimeUnit.SECONDS)
                destinationMatches = destination?.let { abs(it.latitude - 37.7644) < 0.005 && abs(it.longitude - 128.8996) < 0.005 } == true
                if (destinationMatches) break
                Thread.sleep(2000)
            }
            record("ROUTE_COMPLETED_EVENT=${routeLatch.count == 0L}")
            check(destinationMatches) { "Destination did not match requested coordinates: $destination" }
            record("PASS destination read matches requested coordinates")
            val waypointName = "서울 경유 테스트-${System.currentTimeMillis()}"
            waypointsLatch = CountDownLatch(1)
            main.post { helper.getWaypointInfo() }
            check(waypointsLatch.await(5, TimeUnit.SECONDS)) { "No waypoint baseline response" }
            check(waypoints.none { it.name == waypointName }) { "Unique test waypoint already existed" }
            main.post {
                try { helper.addWaypoint(RequestWaypointInfo(latitude = 37.48544722, longitude = 127.03636666,
                    waypointIndex = WaypointIndex.FIRST, poiId = "",
                    poiName = waypointName, poiSubId = "", address = "좌표 기반 경유 테스트")) }
                catch (e: Exception) { record("WAYPOINT_EXCEPTION $e") }
            }
            // Bounded retries read actual state; they never repeat the mutation.
            var found = false
            for (attempt in 1..25) {
                waypointsLatch = CountDownLatch(1)
                main.post { helper.getWaypointInfo() }
                waypointsLatch.await(4, TimeUnit.SECONDS)
                found = waypoints.any { it.name == waypointName && it.address.isNotBlank() }
                if (found) break
                Thread.sleep(2000)
            }
            check(found) { "Requested waypoint missing from actual waypoint response" }
            val confirmedWaypoint = waypoints.first { it.name == waypointName }
            if (confirmedWaypoint.latitude == 0.0 && confirmedWaypoint.longitude == 0.0) {
                record("LIMITATION waypoint coordinates returned as 0,0; insertion verified by fresh unique name and resolved address, not coordinates")
            }
            destinationLatch = CountDownLatch(1)
            main.post { helper.getDestinationInfo() }
            check(destinationLatch.await(8, TimeUnit.SECONDS) && destination?.let { abs(it.latitude - 37.7644) < 0.005 && abs(it.longitude - 128.8996) < 0.005 } == true) {
                "Final destination not preserved"
            }
            record("PASS waypoint is present and final destination preserved")
            passed = true
        } catch (e: Exception) { record("FAIL ${e.message}") }
        finally {
            val cleanup = CountDownLatch(1)
            main.post {
                if (::helper.isInitialized) { helper.removeListener(listener); helper.release() }
                cleanup.countDown()
            }
            cleanup.await(3, TimeUnit.SECONDS)
            val result = Bundle().apply {
                putString("result", if (passed) "PASS" else "FAIL")
                putString("stream", "\n" + synchronized(lines) { lines.joinToString("\n") } + "\n")
            }
            finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        }
    }
}
