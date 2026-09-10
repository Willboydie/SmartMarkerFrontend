package com.example.smartmarkerfrontend.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartmarkerfrontend.data.AppScreen
import com.example.smartmarkerfrontend.data.DemoPlans
import com.example.smartmarkerfrontend.data.DxfParser
import com.example.smartmarkerfrontend.data.MoveStep
import com.example.smartmarkerfrontend.data.NodePacket
import com.example.smartmarkerfrontend.data.NodeStatus
import com.example.smartmarkerfrontend.data.PlanFile
import com.example.smartmarkerfrontend.data.Pose
import com.example.smartmarkerfrontend.data.RunKind
import com.example.smartmarkerfrontend.data.RunProgress
import com.example.smartmarkerfrontend.data.SmartMarkerState
import com.example.smartmarkerfrontend.data.SurveyTag
import com.example.smartmarkerfrontend.data.TabletCommand
import com.example.smartmarkerfrontend.data.Units
import com.example.smartmarkerfrontend.data.degrees
import com.example.smartmarkerfrontend.data.fitScale
import com.example.smartmarkerfrontend.device.UsbDongleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class SmartMarkerViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("smart_marker", Context.MODE_PRIVATE)
    private val dongle = UsbDongleManager(app)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<SmartMarkerState> = _state.asStateFlow()

    private var runJob: Job? = null
    private var lockJob: Job? = null
    private var poseJob: Job? = null
    private var moved = false

    init {
        if (dongle.attached.value) {
            _state.update { it.copy(link = true) }
        }
    }

    private fun initialState(): SmartMarkerState {
        val files = DemoPlans.seedFiles()
        val open = files.first()
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
            nodes = open.nodes,
            geometry = open.geometry,
            files = files,
            step = step,
            curTag = tag,
            units = units,
            beam = prefs.getInt("beam", 70),
            autoReconnect = prefs.getBoolean("autoRe", true)
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
        val wx = (cx - st.pan.x) / st.scale
        val wy = (cy - st.pan.y) / st.scale
        _state.update { it.copy(scale = k, pan = Offset(cx - wx * k, cy - wy * k)) }
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
        _state.update { st ->
            val next = !st.link
            st.copy(link = next, connOpen = true)
        }
    }

    fun toggleAutoReconnect() {
        _state.update { st ->
            prefs.edit().putBoolean("autoRe", !st.autoReconnect).apply()
            st.copy(autoReconnect = !st.autoReconnect)
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
        runJob = viewModelScope.launch {
            var pct = 0
            while (pct < 100) {
                val i = ((pct / 100f) * ids.size).toInt().coerceAtMost(ids.lastIndex)
                _state.update { it.copy(run = RunProgress(RunKind.Survey, ids, pct, i)) }
                delay(70)
                pct += 4
            }
            _state.update { cur ->
                val nodes = cur.nodes.map { n ->
                    if (n.id in ids) {
                        n.copy(status = if (n.id == "V07") NodeStatus.Failed else NodeStatus.Surveyed)
                    } else n
                }
                cur.copy(nodes = nodes, run = null)
            }
        }
    }

    fun toggleProject() {
        val st = _state.value
        if (st.projecting != null) {
            _state.update { it.copy(projecting = null) }
            return
        }
        val ids = st.selectedIds
        if (ids.isEmpty()) return
        dongle.send(TabletCommand.BeginProjection(ids))
        _state.update { cur ->
            cur.copy(
                projecting = ids,
                nodes = cur.nodes.map { n -> if (n.id in ids) n.copy(status = NodeStatus.Projected) else n }
            )
        }
    }

    fun cancelRun() {
        runJob?.cancel()
        _state.update { it.copy(run = null) }
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
            val n = (st.tags.maxOfOrNull { it.n } ?: 0) + 1
            prefs.edit().putInt("curTag", n).apply()
            st.copy(
                tags = st.tags + SurveyTag(n, false),
                curTag = n,
                posed = false,
                computing = false
            )
        }
    }

    fun autoLock() {
        val st = _state.value
        if (st.locking || st.computing) return
        val tag = st.tags.firstOrNull { it.n == st.curTag } ?: return
        if (tag.locked) return
        dongle.send(TabletCommand.BeginTagSearch)
        lockJob?.cancel()
        _state.update { it.copy(locking = true) }
        lockJob = viewModelScope.launch {
            delay(1400)
            var allLocked = false
            _state.update { cur ->
                val tags = cur.tags.map { if (it.n == cur.curTag) it.copy(locked = true) else it }
                allLocked = tags.count { it.locked } >= 2
                dongle.send(TabletCommand.TagFound(cur.curTag))
                cur.copy(locking = false, tags = tags, computing = allLocked)
            }
            if (allLocked) {
                poseJob?.cancel()
                poseJob = viewModelScope.launch {
                    delay(1800)
                    _state.update { it.copy(computing = false, posed = true, hadPose = true) }
                }
            }
        }
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
        dongle.send(TabletCommand.SetOrigin(Pose(0f, 0f, st.az)))
        _state.update { it.copy(hadPose = true) }
    }

    fun resetPose() {
        _state.update { st ->
            st.copy(
                posed = false,
                computing = false,
                tags = st.tags.map { it.copy(locked = false) },
                selectedIds = emptyList()
            )
        }
    }

    fun clearOrigin() {
        _state.update { st ->
            st.copy(
                posed = false,
                computing = false,
                hadPose = false,
                tags = st.tags.map { it.copy(locked = false) },
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
                TabletCommand.RegisterNodes(
                    chosen.nodes.map { NodePacket(it.id, it.x, it.y) }
                )
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
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryDisplayName(app, uri) ?: "plan.dxf"
                    val text = app.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: error("Could not read file")
                    name to DxfParser.extract(text)
                }
            }
            result.onSuccess { (name, extracted) ->
                if (extracted.nodes.isEmpty()) {
                    _state.update { it.copy(importError = "No coordinates found in $name") }
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
                        TabletCommand.RegisterNodes(
                            extracted.nodes.map { NodePacket(it.id, it.x, it.y) }
                        )
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
                        importError = null
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(importError = e.message ?: "Could not open DXF") }
            }
        }
    }

    fun dismissImportError() = _state.update { it.copy(importError = null) }

    private fun queryDisplayName(app: Application, uri: Uri): String? {
        val cursor = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment
    }

    override fun onCleared() {
        runJob?.cancel()
        lockJob?.cancel()
        poseJob?.cancel()
        super.onCleared()
    }
}
