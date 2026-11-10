package com.example.drivecanvas

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.drivecanvas.core.GuidanceCommand
import com.example.drivecanvas.core.GuidanceEvent
import com.example.drivecanvas.core.LatLon

/**
 * Wraps NaviHelper. NaviHelper callbacks arrive on binder threads; they are mapped to [GuidanceEvent]s
 * and delivered to [onEvent] on the main thread. [GuidanceCommand.RequestRoute] is executed by [requestRoute].
 */
class NaviBridge(
    context: Context,
    private val onEvent: (GuidanceEvent) -> Unit,
    private val onConnection: (connected: Boolean, error: String?) -> Unit,
) {
    private val helper = NaviHelper(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var connected = false
    private var listenerAdded = false

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) = emit { GuidanceEvent.RouteStarted(it) }

        override fun onDestinationArrived(info: DestinationArrivedInfo) = emit { GuidanceEvent.DestinationArrived(it) }

        override fun onWayPointArrived(info: WaypointArrivedInfo) {
            val index = if (info.index == WaypointIndex.SECOND) 1 else 0
            emit { GuidanceEvent.WaypointArrived(it, index) }
        }

        override fun onReRouteEnded() = emit { GuidanceEvent.ReRouteEnded(it) }

        override fun onRouteCancelled() = emit { GuidanceEvent.RouteCancelled(it) }

        override fun onError(code: NaviErrorCode) = emit { GuidanceEvent.NaviError(it, code.name) }

        override fun onCurrentLocationInfo(info: CurrentLocationInfo) =
            emit { GuidanceEvent.Location(it, LatLon(info.latitude, info.longitude), info.heading) }
    }

    /** Initializes the NaviHelper service connection. Connection state is reported through [onConnection]. */
    fun connect() {
        runCatching {
            helper.initialize(
                {
                    main.post {
                        connected = true
                        if (!listenerAdded) {
                            helper.addListener(listener)
                            listenerAdded = true
                        }
                        onConnection(true, null)
                    }
                },
                { error ->
                    main.post {
                        connected = false
                        onConnection(false, error.message ?: error.javaClass.simpleName)
                    }
                },
            )
        }.onFailure {
            onConnection(false, it.message ?: it.javaClass.simpleName)
        }
    }

    /** Asks NaviHelper for the current location; the answer arrives as a Location event. */
    fun requestLocation() {
        if (!connected) return
        runCatching { helper.getCurrentLocationInfo() }
    }

    /**
     * Executes a route request. Legs (legIndex >= 0) go through requestRouteWithWaypoints with at most two
     * waypoints mapped to FIRST and SECOND; a home request (legIndex < 0) goes through requestRoute.
     */
    fun requestRoute(cmd: GuidanceCommand.RequestRoute) {
        val destination = RouteInfo(
            latitude = cmd.destination.lat,
            longitude = cmd.destination.lon,
            displayLatitude = null,
            displayLongitude = null,
            poiName = "Drive Canvas 코스",
            poiId = "",
            address = "",
            poiSubId = "",
            routeOption = RouteOption.RECOMMENDED,
            poiProvider = "",
            isMobileRequest = false,
        )
        if (cmd.legIndex < 0) {
            helper.requestRoute(destination)
            return
        }
        require(cmd.waypoints.size <= 2) { "NaviHelper accepts at most two waypoints" }
        val waypoints = cmd.waypoints.mapIndexed { i, w ->
            RequestWaypointInfo(
                latitude = w.lat,
                longitude = w.lon,
                poiName = "",
                waypointIndex = if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                poiId = "",
                address = "",
                poiSubId = "",
                poiProvider = "",
            )
        }
        helper.requestRouteWithWaypoints(RouteWithWaypointsRequest(destination, waypoints))
    }

    fun release() {
        connected = false
        if (listenerAdded) runCatching { helper.removeListener(listener) }
        listenerAdded = false
        runCatching { helper.release() }
    }

    private fun emit(make: (Long) -> GuidanceEvent) {
        val timeMs = SystemClock.elapsedRealtime()
        main.post { onEvent(make(timeMs)) }
    }
}
