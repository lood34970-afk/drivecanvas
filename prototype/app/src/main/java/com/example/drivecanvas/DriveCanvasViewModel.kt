package com.example.drivecanvas

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.drivecanvas.core.CandidateKind
import com.example.drivecanvas.core.CanvasPlanner
import com.example.drivecanvas.core.DeviationScenario
import com.example.drivecanvas.core.DriveSummary
import com.example.drivecanvas.core.GuidanceCommand
import com.example.drivecanvas.core.GuidanceConfig
import com.example.drivecanvas.core.GuidanceEngine
import com.example.drivecanvas.core.GuidanceEvent
import com.example.drivecanvas.core.GuidancePlan
import com.example.drivecanvas.core.GuidanceState
import com.example.drivecanvas.core.LatLon
import com.example.drivecanvas.core.PlanResult
import com.example.drivecanvas.core.PlannedCandidate
import com.example.drivecanvas.core.RoadGraph
import com.example.drivecanvas.core.SimConfig
import com.example.drivecanvas.core.SimulationRunner
import com.example.drivecanvas.core.haversineMeters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class Screen { COURSE, DRIVING, RESULT }

enum class DriveMode { REAL_NAVI, SIMULATION }

data class CourseUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val graph: RoadGraph? = null,
    val candidates: List<PlannedCandidate> = emptyList(),
    val noRouteReasons: List<String> = emptyList(),
    val selectedKind: CandidateKind = CandidateKind.SHAPE,
    val mode: DriveMode = DriveMode.SIMULATION,
    val includeDeviation: Boolean = false,
    val naviConnected: Boolean = false,
    val naviError: String? = null,
    val lastLocation: LatLon? = null,
) {
    /** The chosen candidate; falls back to the first available one when the chosen kind is missing. */
    val selected: PlannedCandidate?
        get() = candidates.firstOrNull { it.kind == selectedKind } ?: candidates.firstOrNull()

    /** Distance from the last known location to the planned start, metres; null when unknown. */
    val distanceToStartM: Double?
        get() {
            val start = selected?.plan?.start ?: return null
            val here = lastLocation ?: return null
            return haversineMeters(here, start)
        }

    /** Why "드라이브 시작" is disabled, or null when the drive can start. */
    val startBlockReason: String?
        get() {
            val distance = distanceToStartM
            return when {
                loading -> "코스를 계산하는 중이에요"
                selected == null -> "사용할 수 있는 코스가 없어요"
                mode == DriveMode.SIMULATION -> null
                !naviConnected -> "내비게이션 연결 대기"
                lastLocation == null -> "현재 위치를 확인하는 중이에요"
                distance != null && distance > DriveCanvasViewModel.START_RADIUS_M ->
                    "출발점(약 ${String.format(Locale.ROOT, "%.1f", distance / 1000.0)} km)으로 이동하면 시작할 수 있어요"
                else -> null
            }
        }
}

data class DrivingUi(
    val sim: Boolean = false,
    val progress: Double = 0.0,
    val legIndex: Int = -1,
    val legCount: Int = 0,
    val guidance: GuidanceState = GuidanceState.IDLE,
    val banner: String = "",
)

data class DriveResult(
    val summary: DriveSummary,
    val plan: GuidancePlan,
    val label: String,
    val sim: Boolean,
)

/**
 * Holds course planning, the active drive session (simulation or real NaviHelper) and the result.
 * Engine calls happen on the main thread; simulation steps run on Dispatchers.Default.
 */
class DriveCanvasViewModel(application: Application) : AndroidViewModel(application) {
    private val vehicle = VehicleMotion()
    private val speaker = Speaker(application)
    private val naviBridge = NaviBridge(application, ::onNaviEvent, ::onNaviConnection)

    val speedKmh: StateFlow<Float?> = vehicle.speedKmh

    private val _screen = MutableStateFlow(Screen.COURSE)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _course = MutableStateFlow(CourseUiState())
    val course: StateFlow<CourseUiState> = _course.asStateFlow()

    private val _driving = MutableStateFlow(DrivingUi())
    val driving: StateFlow<DrivingUi> = _driving.asStateFlow()

    private val _result = MutableStateFlow<DriveResult?>(null)
    val result: StateFlow<DriveResult?> = _result.asStateFlow()

    private var activeCandidate: PlannedCandidate? = null
    private var activeEngine: GuidanceEngine? = null
    private var activeRunner: SimulationRunner? = null
    private var driveJob: Job? = null
    /** Set by a simulated "그리기 끝내기" press; the sim loop consumes it before its next step. */
    private val simUserEndRequested = AtomicBoolean(false)

    init {
        naviBridge.connect()
        loadCourse()
        viewModelScope.launch {
            while (isActive) {
                delay(LOCATION_POLL_MS)
                if (_screen.value == Screen.COURSE && _course.value.naviConnected) naviBridge.requestLocation()
            }
        }
    }

    /** Starts AAOS speed subscription. Call after the CAR_SPEED permission request. */
    fun startVehicleMotion() {
        vehicle.start(getApplication())
    }

    fun selectCandidate(kind: CandidateKind) {
        _course.update { it.copy(selectedKind = kind) }
    }

    fun setMode(mode: DriveMode) {
        _course.update { it.copy(mode = mode) }
    }

    fun setIncludeDeviation(include: Boolean) {
        _course.update { it.copy(includeDeviation = include) }
    }

    fun startDrive() {
        val state = _course.value
        val candidate = state.selected ?: return
        val graph = state.graph ?: return
        if (state.startBlockReason != null) return

        activeCandidate = candidate
        _result.value = null
        _driving.value = DrivingUi(sim = state.mode == DriveMode.SIMULATION, banner = "출발합니다")
        _screen.value = Screen.DRIVING
        if (state.mode == DriveMode.SIMULATION) {
            startSimulation(graph, candidate.plan, state.includeDeviation)
        } else {
            startRealNavi(candidate.plan)
        }
    }

    /** Sends UserEndEarly to the guidance engine. In simulation the sim loop delivers it before its next step. */
    fun endEarly() {
        if (activeRunner != null) {
            val guidance = _driving.value.guidance
            if (guidance in END_EARLY_STATES) simUserEndRequested.set(true)
            return
        }
        val engine = activeEngine ?: return
        dispatch(engine.handle(GuidanceEvent.UserEndEarly(now())))
    }

    fun backToCourse() {
        _result.value = null
        _screen.value = Screen.COURSE
    }

    private fun loadCourse() {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching { planCourse(getApplication()) }
            }
            outcome.onSuccess { (graph, plan) ->
                _course.update { state ->
                    when (plan) {
                        is PlanResult.Ok -> state.copy(
                            loading = false,
                            graph = graph,
                            candidates = listOfNotNull(plan.shapeFirst, plan.timeFirst),
                        )
                        is PlanResult.NoRoute -> state.copy(
                            loading = false,
                            graph = graph,
                            noRouteReasons = plan.reasonsKo,
                        )
                    }
                }
            }.onFailure { error ->
                _course.update { it.copy(loading = false, loadError = error.message ?: error.javaClass.simpleName) }
            }
        }
    }

    private fun startSimulation(graph: RoadGraph, plan: GuidancePlan, deviation: Boolean) {
        val runner = SimulationRunner(
            graph,
            plan,
            SimConfig(deviation = if (deviation) DeviationScenario() else null),
            if (deviation) GuidanceConfig.demo() else GuidanceConfig(),
        )
        activeRunner = runner
        simUserEndRequested.set(false)
        driveJob = viewModelScope.launch {
            dispatch(withContext(Dispatchers.Default) { runner.start() })
            while (isActive && !runner.finished) {
                val commands = withContext(Dispatchers.Default) {
                    if (simUserEndRequested.getAndSet(false)) runner.userEndEarly() else runner.step()
                }
                dispatch(commands)
                delay(SIM_STEP_MS)
            }
        }
    }

    private fun startRealNavi(plan: GuidancePlan) {
        val engine = GuidanceEngine(plan, GuidanceConfig())
        activeEngine = engine
        driveJob = viewModelScope.launch {
            dispatch(engine.handle(GuidanceEvent.Start(now())))
            while (isActive && activeEngine === engine) {
                delay(TICK_MS)
                naviBridge.requestLocation()
                dispatch(engine.handle(GuidanceEvent.Tick(now())))
            }
        }
    }

    private fun onNaviEvent(event: GuidanceEvent) {
        if (event is GuidanceEvent.Location) _course.update { it.copy(lastLocation = event.latLon) }
        val engine = activeEngine ?: return
        dispatch(engine.handle(event))
    }

    private fun onNaviConnection(connected: Boolean, error: String?) {
        _course.update { it.copy(naviConnected = connected, naviError = error) }
    }

    /** Executes guidance commands. Route requests only reach NaviHelper in real mode (the simulator handles its own). */
    private fun dispatch(commands: List<GuidanceCommand>) {
        refreshDriving()
        for (command in commands) {
            when (command) {
                is GuidanceCommand.RequestRoute -> if (activeEngine != null) {
                    runCatching { naviBridge.requestRoute(command) }.onFailure {
                        onNaviEvent(GuidanceEvent.NaviError(now(), it.message ?: it.javaClass.simpleName))
                    }
                }
                is GuidanceCommand.Announce -> announce(command.textKo)
                is GuidanceCommand.Finish -> finishDrive(command.summary)
            }
        }
        refreshDriving()
    }

    private fun announce(text: String) {
        _driving.update { it.copy(banner = text) }
        speaker.speak(text)
    }

    private fun finishDrive(summary: DriveSummary) {
        val candidate = activeCandidate ?: return
        val sim = activeRunner != null
        activeEngine = null
        activeRunner = null
        _result.value = DriveResult(summary, candidate.plan, candidate.labelKo, sim)
        _screen.value = Screen.RESULT
    }

    private fun refreshDriving() {
        val engine = activeRunner?.engine ?: activeEngine ?: return
        _driving.update {
            it.copy(
                progress = engine.progressFraction,
                legIndex = engine.currentLegIndex,
                legCount = engine.legCount,
                guidance = engine.state,
            )
        }
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    override fun onCleared() {
        driveJob?.cancel()
        vehicle.stop()
        speaker.shutdown()
        naviBridge.release()
        super.onCleared()
    }

    companion object {
        const val GRAPH_ASSET = "canvas/ilsan.dcg"
        const val PLACEMENTS_ASSET = "canvas/ilsan.placements"
        const val TARGET_MIN = 35.0
        /** Start is allowed only within this distance of the planned start (metres). */
        const val START_RADIUS_M = 300.0
        const val SIM_STEP_MS = 50L
        const val TICK_MS = 1_000L
        const val LOCATION_POLL_MS = 2_000L
        /** States in which "그리기 끝내기" is accepted. */
        val END_EARLY_STATES = setOf(
            GuidanceState.LEG_REQUESTED,
            GuidanceState.GUIDING,
            GuidanceState.OFF_ROUTE,
        )

        private fun planCourse(app: Application): Pair<RoadGraph, PlanResult> {
            val graph = app.assets.open(GRAPH_ASSET).use { CanvasPlanner.loadGraph(it) }
            val placements = app.assets.open(PLACEMENTS_ASSET).use { CanvasPlanner.loadPlacements(it) }
            return graph to CanvasPlanner(graph).planFromPlacements(placements.records, TARGET_MIN)
        }
    }
}

/** Korean text-to-speech. Fails silently when the engine or the Korean voice is unavailable. */
private class Speaker(context: Context) {
    @Volatile
    private var ready = false
    private var tts: TextToSpeech? = null

    init {
        runCatching {
            tts = TextToSpeech(context) { status ->
                val languageResult = runCatching { tts?.setLanguage(Locale.KOREAN) }.getOrNull()
                    ?: TextToSpeech.LANG_NOT_SUPPORTED
                ready = status == TextToSpeech.SUCCESS && languageResult >= TextToSpeech.LANG_AVAILABLE
            }
        }
    }

    fun speak(text: String) {
        if (!ready) return
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "guide") }
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
    }
}
