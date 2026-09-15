package com.example.smartmarkerfrontend.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartmarkerfrontend.data.AppScreen
import com.example.smartmarkerfrontend.data.DeviceTelemetry
import com.example.smartmarkerfrontend.data.DxfParser
import com.example.smartmarkerfrontend.data.EmptyPlanGeometry
import com.example.smartmarkerfrontend.data.MoveStep
import com.example.smartmarkerfrontend.data.NodeStatus
import com.example.smartmarkerfrontend.data.PlanFile
import com.example.smartmarkerfrontend.data.Pose
import com.example.smartmarkerfrontend.data.RunKind
import com.example.smartmarkerfrontend.data.RunProgress
import com.example.smartmarkerfrontend.data.SmartMarkerState
import com.example.smartmarkerfrontend.data.Tag
import com.example.smartmarkerfrontend.data.TabletCommand
import com.example.smartmarkerfrontend.data.Units
import com.example.smartmarkerfrontend.data.degrees
import com.example.smartmarkerfrontend.data.fitScale
import com.example.smartmarkerfrontend.data.panForWorldAtScreen
import com.example.smartmarkerfrontend.data.wrapTwoPi
import com.example.smartmarkerfrontend.device.NodeIds
import com.example.smartmarkerfrontend.device.UsbDongleManager
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class SmartMarkerViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("smart_marker", Context.MODE_PRIVATE)
    private val dongle = UsbDongleManager(app, viewModelScope)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<SmartMarkerState> = _state.asStateFlow()

    private var runJob: Job? = null
    private var moved = false
    private var deviceMovedLatched = false

    init {
        dongle.autoReconnect = prefs.getBoolean("autoRe", true)
        viewModelScope.launch {
            dongle.telemetry.collect { applyDeviceTelemetry(it) }
        }
        viewModelScope.launch {
            dongle.connected.collect { up ->
                _state.update { it.copy(link = up) }
            }
        }
        if (dongle.autoReconnect) dongle.start()
    }

    fun onHostResume() {
        dongle.refresh()
    }

    private fun initialState(): SmartMarkerState {
        val step = when (prefs.getString("step", "coarse")) {
            "fine" -> MoveStep.Fine
            else -> MoveStep.Coarse
        }
        val tag = prefs.getInt("curTag", 1)
        val units = when (prefs.getString("units", "mm")) {
            "m" -> Units.Metres
            "ft" -> Units.Feet
            else -> Units.Millimetres
        }
        return SmartMarkerState(
            screen = AppScreen.Plans,
            nodes = emptyList(),
            geometry = EmptyPlanGeometry,
            files = emptyList(),
            posed = false,
            hadPose = false,
            tags = listOf(Tag(1, false), Tag(2, false)),
            step = step,
            curTag = tag,
            units = units,
            beam = prefs.getInt("beam", 70),
            autoReconnect = prefs.getBoolean("autoRe", true),
            link = false
        )
    }

    fun go(screen: AppScreen) {
        _state.update { it.copy(screen = screen, connOpen = false, userOpen = false) }
    }

    fun onCanvasSize(width: Float, height: Float) {
        if (width < 8f || height < 8f) return
        _state.update { st ->
            if (st.canvasSize.x == width && st.canvasSize.y == height) return@update st
            if (st.canvasSize == Offset.Zero) {
                val (s, p) = fitScale(width, height, st.geometry)
                st.copy(canvasSize = Offset(width, height), scale = s, pan = p)
            } else {
                st.copy(canvasSize = Offset(width, height))
            }
        }
    }

    fun panBy(delta: Offset) {
        if (_state.value.run != null) return
        _state.update { it.copy(pan = it.pan + delta) }
        moved = true
    }

    fun setView(scale: Float, pan: Offset) {
        if (_state.value.run != null) return
        _state.update { it.copy(scale = clampScale(scale), pan = pan) }
        moved = true
    }

    fun markGestureStart() {
        moved = false
    }

    fun zoomBy(factor: Float) {
        val st = _state.value
        val k = clampScale(st.scale * factor)
        val cx = st.canvasSize.x / 2f
        val cy = st.canvasSize.y / 2f
        val world = st.screenToWorld(Offset(cx, cy))
        _state.update { it.copy(scale = k, pan = panForWorldAtScreen(world.x, world.y, k, Offset(cx, cy))) }
    }

    fun fitPlan() {
        val st = _state.value
        val (s, p) = fitScale(st.canvasSize.x.coerceAtLeast(1f), st.canvasSize.y.coerceAtLeast(1f), st.geometry)
        _state.update { it.copy(scale = s, pan = p) }
    }

    private fun clampScale(scale: Float): Float {
        val fit = fitFloor()
        val min = (fit * 0.2f).coerceAtLeast(0.004f)
        val max = (fit * 30f).coerceAtMost(12f)
        return scale.coerceIn(min, max)
    }

    private fun fitFloor(): Float {
        val st = _state.value
        val (s, _) = fitScale(
            st.canvasSize.x.coerceAtLeast(400f),
            st.canvasSize.y.coerceAtLeast(300f),
            st.geometry
        )
        return s
    }

    fun tapNode(id: String) {
        if (moved) return
        _state.update { st ->
            val sel = st.selectedIds
            val next = if (st.multiSelect) {
                if (id in sel) sel - id else sel + id
            } else {
                if (sel.size == 1 && sel[0] == id) emptyList() else listOf(id)
            }
            st.copy(selectedIds = next)
        }
    }

    fun tapCanvas(pos: Offset) {
        if (moved) return
        val st = _state.value
        val hit = st.nodes.minByOrNull { n ->
            val p = st.worldToScreen(n.x, n.y)
            (p - pos).getDistance()
        }
        if (hit != null && (st.worldToScreen(hit.x, hit.y) - pos).getDistance() <= 42f) {
            tapNode(hit.id)
        }
    }

    fun clearSelection() = _state.update { it.copy(selectedIds = emptyList()) }

    fun toggleMulti() = _state.update { it.copy(multiSelect = !it.multiSelect) }
    fun toggleLabels() = _state.update { it.copy(labels = !it.labels) }
    fun toggleGrid() = _state.update { it.copy(grid = !it.grid) }
    fun toggleFov() = _state.update { it.copy(fovOn = !it.fovOn) }

    fun toggleUser() = _state.update { it.copy(userOpen = !it.userOpen, connOpen = false) }
    fun toggleConn() = _state.update { it.copy(connOpen = !it.connOpen, userOpen = false) }
    fun closeConn() = _state.update { it.copy(connOpen = false) }
    fun closeMenus() = _state.update { it.copy(connOpen = false, userOpen = false) }

    fun toggleLink() {
        if (dongle.connected.value) {
            dongle.stop()
        } else {
            dongle.autoReconnect = _state.value.autoReconnect
            dongle.start()
        }
        _state.update { it.copy(connOpen = true) }
    }

    fun toggleAutoReconnect() {
        _state.update { st ->
            val next = !st.autoReconnect
            prefs.edit().putBoolean("autoRe", next).apply()
            dongle.autoReconnect = next
            st.copy(autoReconnect = next)
        }
    }

    fun setUnits(units: Units) {
        prefs.edit().putString(
            "units",
            when (units) {
                Units.Millimetres -> "mm"
                Units.Metres -> "m"
                Units.Feet -> "ft"
            }
        ).apply()
        _state.update { it.copy(units = units) }
    }

    fun setBeam(value: Int) {
        val duty = value.coerceIn(10, 100)
        prefs.edit().putInt("beam", duty).apply()
        _state.update { it.copy(beam = duty) }
        dongle.send(TabletCommand.SetLaserPower(duty))
    }

    fun toggleLaser() {
        _state.update { it.copy(laser = !it.laserOn, projecting = null) }
    }

    fun startSurvey() {
        val st = _state.value
        val ids = st.selectedIds
        if (ids.isEmpty() || st.posed.not() || st.run != null || st.projecting != null) return
        if (ids.any { id -> st.nodes.firstOrNull { it.id == id }?.let { !st.inView(it) } == true }) return
        dongle.send(TabletCommand.BeginSurvey(ids))
        runJob?.cancel()
        _state.update {
            it.copy(
                isSurveying = true,
                run = RunProgress(RunKind.Survey, ids)
            )
        }
        if (dongle.connected.value) return
        runJob = viewModelScope.launch {
            for (id in ids) {
                delay(SURVEY_POINT_PERIOD_MS)
                if (!isActive) return@launch
                val node = _state.value.nodes.firstOrNull { it.id == id }
                val success = node != null && id != "V07"
                val pose = Pose(
                    eastMm = node?.x ?: 0f,
                    northMm = node?.y ?: 0f,
                    upMm = 0f,
                    rollRad = 0f,
                    pitchRad = 0f,
                    yawRad = wrapTwoPi(Math.toRadians(_state.value.az.toDouble()).toFloat())
                )
                applySurveyPoint(DeviceTelemetry.SurveyPoint(id, success, pose))
            }
            _state.update { cur ->
                val run = cur.run
                val done = run != null && run.kind == RunKind.Survey && run.reported.size >= run.ids.size
                cur.copy(isSurveying = false, run = if (done) null else run)
            }
        }
    }

    fun toggleProject() {
        val st = _state.value
        if (st.projecting != null) {
            dongle.send(TabletCommand.SetProjectionMode(0))
            _state.update { it.copy(projecting = null) }
            return
        }
        val ids = st.selectedIds
        if (ids.isEmpty()) return
        dongle.send(TabletCommand.BeginProjection(ids))
        if (dongle.connected.value) return
        _state.update { cur ->
            cur.copy(
                projecting = ids,
                nodes = cur.nodes.map { n -> if (n.id in ids) n.copy(status = NodeStatus.Projected) else n }
            )
        }
    }

    fun cancelRun() {
        runJob?.cancel()
        dongle.send(TabletCommand.SetProjectionMode(0))
        _state.update { it.copy(run = null, isSurveying = false) }
    }

    fun setStep(step: MoveStep) {
        prefs.edit().putString("step", if (step == MoveStep.Fine) "fine" else "coarse").apply()
        _state.update { it.copy(step = step) }
    }

    fun pickTag(n: Int) {
        prefs.edit().putInt("curTag", n).apply()
        _state.update { it.copy(curTag = n) }
    }

    fun addTag() {
        _state.update { st ->
            val n = (st.tags.maxOfOrNull { it.id } ?: 0) + 1
            prefs.edit().putInt("curTag", n).apply()
            st.copy(
                tags = st.tags + Tag(n, false),
                curTag = n,
                posed = false,
                computing = false
            )
        }
    }

    fun beginTagSearch() {
        val st = _state.value
        if (st.posed || st.locking) return
        dongle.send(TabletCommand.BeginTagSearch)
        _state.update { it.copy(locking = true, computing = false) }
    }

    fun confirmTag() {
        val st = _state.value
        if (!st.locking || st.posed) return
        val tag = st.tags.firstOrNull { it.id == st.curTag } ?: return
        if (tag.aligned) return
        dongle.send(TabletCommand.TagFound(st.curTag))
        var needNextSearch = false
        _state.update { cur ->
            val tags = cur.tags.map { if (it.id == cur.curTag) it.copy(aligned = true) else it }
            val allLocked = tags.count { it.aligned } >= 2
            needNextSearch = !allLocked
            cur.copy(
                tags = tags,
                posed = allLocked,
                hadPose = cur.hadPose || allLocked,
                locking = !allLocked,
                computing = false
            )
        }
        if (needNextSearch) dongle.send(TabletCommand.BeginTagSearch)
    }

    fun nudge(dxAz: Float, dyEl: Float) {
        val step = _state.value.step.degrees()
        _state.update { st ->
            val az = ((st.az + dxAz * step) * 100f).roundToInt() / 100f
            val el = ((st.el + dyEl * step) * 100f).roundToInt() / 100f
            if (dxAz != 0f) dongle.send(TabletCommand.NudgeBeamX(dxAz * step))
            if (dyEl != 0f) dongle.send(TabletCommand.NudgeBeamY(dyEl * step))
            st.copy(az = az, el = el)
        }
    }

    fun setOrigin() {
        val st = _state.value
        if (st.posed || st.locking) return
        val yaw = wrapTwoPi(Math.toRadians(st.az.toDouble()).toFloat())
        _state.update { it.copy(hadPose = true, locking = true, computing = false) }
        dongle.send(
            TabletCommand.SetOrigin(Pose(0f, 0f, 0f, 0f, 0f, yaw)),
            TabletCommand.BeginTagSearch
        )
    }

    fun resetPose() {
        _state.update { st ->
            st.copy(
                posed = false,
                computing = false,
                locking = false,
                tags = st.tags.map { it.copy(aligned = false) },
                selectedIds = emptyList()
            )
        }
    }

    fun clearOrigin() {
        _state.update { st ->
            st.copy(
                posed = false,
                computing = false,
                locking = false,
                hadPose = false,
                tags = st.tags.map { it.copy(aligned = false) },
                selectedIds = emptyList()
            )
        }
    }

    fun openPlan(name: String) {
        _state.update { st ->
            val saved = st.files.map { f ->
                if (f.open) f.copy(nodes = st.nodes, geometry = st.geometry) else f
            }.map { it.copy(open = it.name == name) }
            val chosen = saved.first { it.name == name }
            val sized = st.canvasSize
            val (s, p) = if (sized.x > 8f) fitScale(sized.x, sized.y, chosen.geometry) else st.scale to st.pan
            dongle.send(
                TabletCommand.RegisterNodes(chosen.nodes.map { it.toPacket() })
            )
            st.copy(
                files = saved,
                nodes = chosen.nodes,
                geometry = chosen.geometry,
                screen = AppScreen.Plan,
                selectedIds = emptyList(),
                projecting = null,
                scale = s,
                pan = p,
                importError = null
            )
        }
    }

    fun importDxf(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(importing = true, importError = null) }
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryDisplayName(app, uri) ?: "plan.dxf"
                    val tmp = File.createTempFile("import-", ".dxf", app.cacheDir)
                    try {
                        app.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Could not read file")
                        name to DxfParser.extract(app, tmp)
                    } finally {
                        tmp.delete()
                    }
                }
            }
            result.onSuccess { (name, extracted) ->
                if (extracted.nodes.isEmpty() && extracted.planLinePoints.isEmpty()) {
                    _state.update {
                        it.copy(
                            importing = false,
                            importError = "No coordinates found in $name"
                        )
                    }
                    return@onSuccess
                }
                val today = SimpleDateFormat("d MMM yyyy", Locale.UK).format(Date())
                val file = PlanFile(name, today, true, extracted.nodes, extracted.geometry)
                _state.update { st ->
                    val rest = st.files.map { it.copy(open = false) }
                    val sized = st.canvasSize
                    val (s, p) = if (sized.x > 8f) {
                        fitScale(sized.x, sized.y, extracted.geometry)
                    } else st.scale to st.pan
                    dongle.send(
                        TabletCommand.RegisterNodes(extracted.nodes.map { it.toPacket() })
                    )
                    st.copy(
                        files = listOf(file) + rest,
                        nodes = extracted.nodes,
                        geometry = extracted.geometry,
                        screen = AppScreen.Plan,
                        selectedIds = emptyList(),
                        projecting = null,
                        scale = s,
                        pan = p,
                        importError = null,
                        importing = false
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(importing = false, importError = e.message ?: "Could not open DXF")
                }
            }
        }
    }

    fun dismissImportError() = _state.update { it.copy(importError = null) }

    private fun applyDeviceTelemetry(msg: DeviceTelemetry) {
        when (msg) {
            is DeviceTelemetry.Handshake -> {
                _state.update { it.copy(firmwareVersion = msg.firmwareVersion) }
                val st = _state.value
                dongle.send(TabletCommand.RegisterNodes(st.nodes.map { it.toPacket() }))
                dongle.send(TabletCommand.SetLaserPower(st.beam))
            }
            is DeviceTelemetry.Regular -> applyRegularTelemetry(msg)
            is DeviceTelemetry.SurveyPoint -> applySurveyPoint(msg)
            is DeviceTelemetry.ProjectionStart -> {
                val ids = msg.projectedNodes.map { it.id }
                _state.update { st ->
                    st.copy(
                        projecting = ids,
                        nodes = st.nodes.map { n ->
                            if (ids.any { id -> sameNode(n.id, id) }) {
                                n.copy(status = NodeStatus.Projected)
                            } else n
                        }
                    )
                }
            }
        }
    }

    private fun applySurveyPoint(msg: DeviceTelemetry.SurveyPoint) {
        _state.update { st ->
            val run = st.run
            if (run == null || run.kind != RunKind.Survey) return@update st
            val planId = st.nodes.firstOrNull { sameNode(it.id, msg.nodeId) }?.id
                ?: run.ids.firstOrNull { sameNode(it, msg.nodeId) }
                ?: msg.nodeId
            val nodes = st.nodes.map { n ->
                if (sameNode(n.id, planId)) {
                    n.copy(status = if (msg.success) NodeStatus.Surveyed else NodeStatus.NotVisible)
                } else n
            }
            val reported = if (run.reported.any { sameNode(it, planId) }) {
                run.reported
            } else {
                run.reported + planId
            }
            val updated = run.copy(reported = reported)
            val finished = updated.reported.size >= updated.ids.size && !st.isSurveying
            st.copy(nodes = nodes, run = if (finished) null else updated)
        }
    }

    private fun applyRegularTelemetry(msg: DeviceTelemetry.Regular) {
        val azDeg = Math.toDegrees(msg.effectiveYaw.toDouble()).toFloat()
        val becameMoved = msg.moved && !deviceMovedLatched
        deviceMovedLatched = msg.moved
        val poseValid = msg.lastKnownPose != null
        _state.update { st ->
            val projecting = if (!msg.isProjecting) null else st.projecting
            val tags = if (becameMoved) st.tags.map { it.copy(aligned = false) } else st.tags
            val run = st.run
            val surveyFinished = run != null &&
                run.kind == RunKind.Survey &&
                run.reported.size >= run.ids.size &&
                !msg.isSurveying
            st.copy(
                laser = msg.laserOn,
                hadPose = msg.originIsSet || (st.hadPose && !becameMoved),
                posed = when {
                    msg.moved -> false
                    st.locking -> st.posed
                    poseValid -> true
                    else -> st.posed
                },
                computing = if (becameMoved) false else st.computing,
                locking = when {
                    msg.moved -> false
                    else -> st.locking
                },
                tags = tags,
                deviceYawRad = msg.effectiveYaw,
                az = azDeg,
                projecting = projecting,
                isSurveying = msg.isSurveying,
                run = if (surveyFinished) null else run
            )
        }
    }

    override fun onCleared() {
        runJob?.cancel()
        dongle.release()
        super.onCleared()
    }

    private fun queryDisplayName(app: Application, uri: Uri): String? {
        val cursor = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment
    }

    private fun sameNode(planId: String, wireId: String): Boolean =
        planId == wireId || NodeIds.toWire(planId) == NodeIds.toWire(wireId)

    companion object {
        const val SURVEY_POINT_PERIOD_MS = 5_000L
    }
}
