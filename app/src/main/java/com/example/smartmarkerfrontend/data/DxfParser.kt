package com.example.smartmarkerfrontend.data

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * DXF import via Chaquopy. Implement `parse_dxf` in
 * `app/src/main/python/dxf_parser.py`.
 *
 * Kotlin copies the picked DXF onto disk and calls:
 *
 * ```
 * parse_dxf(path: str) -> (plan_line_points, nodes)
 * ```
 *
 * `plan_line_points` is a list of polylines. Each polyline is a list of
 * `[x, y]` world millimetres, e.g.
 * `[[[0, 0], [5000, 0]], [[0, 0], [0, 3000]]]`.
 *
 * `nodes` is a list of `{"id": "V01", "x": 0.0, "y": 0.0}`.
 * `(id, x, y)` or `(x, y)` tuples are also accepted.
 *
 * A dict `{"plan_line_points": ..., "nodes": ...}` is accepted instead of a
 * 2-tuple.
 */
object DxfParser {

    data class Extracted(
        val planLinePoints: List<List<Offset>>,
        val nodes: List<MarkerNode>,
        val geometry: PlanGeometry
    )

    fun extract(context: Context, dxfFile: File): Extracted {
        require(dxfFile.isFile) { "DXF file not found: ${dxfFile.absolutePath}" }
        ensureStarted(context)
        val result = Python.getInstance()
            .getModule("dxf_parser")
            .callAttr("parse_dxf", dxfFile.absolutePath)
            ?: error("parse_dxf returned None")
        return decode(result)
    }

    internal fun fromParsed(
        planLinePoints: List<List<Offset>>,
        nodes: List<MarkerNode>
    ): Extracted {
        val geometry = geometryFrom(planLinePoints, nodes)
        return Extracted(
            planLinePoints = planLinePoints,
            nodes = nodes.map { it.withPlanCorners(geometry) },
            geometry = geometry
        )
    }

    @Synchronized
    private fun ensureStarted(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
    }

    private fun decode(result: PyObject): Extracted {
        val (planRaw, nodesRaw) = splitResult(result)
        val planLinePoints = planRaw?.let { decodePolylines(it) } ?: emptyList()
        val nodes = nodesRaw?.let { decodeNodes(it) } ?: emptyList()
        return fromParsed(planLinePoints, nodes)
    }

    private fun splitResult(result: PyObject): Pair<PyObject?, PyObject?> {
        if (pyTypeName(result) == "dict") {
            val map = result.asMap()
            val plan = map.pyGet("plan_line_points")
                ?: map.pyGet("planLinePoints")
                ?: error("parse_dxf dict is missing plan_line_points")
            val nodes = map.pyGet("nodes")
                ?: error("parse_dxf dict is missing nodes")
            return plan to nodes
        }
        val list = result.asList()
        require(list.size >= 2) { "parse_dxf must return (plan_line_points, nodes)" }
        return list[0] to list[1]
    }

    private fun decodePolylines(raw: PyObject): List<List<Offset>> {
        if (pyTypeName(raw) == "NoneType") return emptyList()
        val outer = raw.asList()
        if (outer.isEmpty()) return emptyList()
        val first = outer[0] ?: return emptyList()
        if (isNumber(first) || isPoint(first)) {
            val line = decodePoints(raw)
            return if (line.size >= 2) listOf(line) else emptyList()
        }
        return outer.map { decodePoints(it) }.filter { it.size >= 2 }
    }

    private fun decodePoints(raw: PyObject): List<Offset> {
        if (isNumber(raw)) return emptyList()
        val items = raw.asList()
        if (items.isEmpty()) return emptyList()
        if (isNumber(items[0])) {
            require(items.size >= 2) { "Point needs x and y" }
            return listOf(Offset(items[0].asFloat(), items[1].asFloat()))
        }
        return items.map { decodePoint(it) }
    }

    private fun decodePoint(raw: PyObject): Offset {
        if (pyTypeName(raw) == "dict") {
            val map = raw.asMap()
            val x = map.pyGet("x") ?: map.pyGet("X") ?: error("point missing x")
            val y = map.pyGet("y") ?: map.pyGet("Y") ?: error("point missing y")
            return Offset(x.asFloat(), y.asFloat())
        }
        val xy = raw.asList()
        require(xy.size >= 2) { "Point needs x and y" }
        return Offset(xy[0].asFloat(), xy[1].asFloat())
    }

    private fun decodeNodes(raw: PyObject): List<MarkerNode> {
        if (pyTypeName(raw) == "NoneType") return emptyList()
        val used = mutableSetOf<String>()
        return raw.asList().mapIndexedNotNull { index, item ->
            item ?: return@mapIndexedNotNull null
            val (label, x, y) = decodeNode(item)
            MarkerNode(uniqueId(label, index, used), x, y, NodeStatus.Open)
        }
    }

    private fun decodeNode(item: PyObject): Triple<String?, Float, Float> {
        if (pyTypeName(item) == "dict") {
            val map = item.asMap()
            val id = map.pyGet("id")?.toString()?.takeIf { it.isNotBlank() && it != "None" }
            val x = map.pyGet("x") ?: map.pyGet("X") ?: error("node missing x")
            val y = map.pyGet("y") ?: map.pyGet("Y") ?: error("node missing y")
            return Triple(id, x.asFloat(), y.asFloat())
        }
        val list = item.asList()
        require(list.size >= 2) { "node needs at least x and y" }
        return if (list.size >= 3 && !isNumber(list[0])) {
            Triple(list[0].toString(), list[1].asFloat(), list[2].asFloat())
        } else {
            Triple(null, list[0].asFloat(), list[1].asFloat())
        }
    }

    internal fun geometryFrom(
        planLinePoints: List<List<Offset>>,
        nodes: List<MarkerNode>
    ): PlanGeometry {
        val outlines = planLinePoints.map { pts ->
            val closed = pts.size >= 3 &&
                abs(pts.first().x - pts.last().x) < 0.5f &&
                abs(pts.first().y - pts.last().y) < 0.5f
            val trimmed = if (closed) pts.dropLast(1) else pts
            Polyline(trimmed, closed)
        }
        val allPts = buildList {
            addAll(nodes.map { Offset(it.x, it.y) })
            outlines.forEach { addAll(it.points) }
        }
        val bounds = boundsOf(allPts) ?: Rect(0f, 0f, 1000f, 1000f)
        val pad = max(bounds.width, bounds.height) * 0.04f
        val marker = Offset(bounds.center.x, bounds.top - bounds.height * 0.08f)
        return PlanGeometry(
            bounds = Rect(bounds.left - pad, bounds.top - pad, bounds.right + pad, bounds.bottom + pad),
            outlines = outlines,
            marker = marker
        )
    }

    private fun uniqueId(label: String?, index: Int, used: MutableSet<String>): String {
        val base = label?.trim()?.takeIf { it.isNotEmpty() } ?: "V%02d".format(index + 1)
        var id = base
        var n = 2
        while (id in used) {
            id = "$base-$n"
            n++
        }
        used += id
        return id
    }

    private fun boundsOf(pts: List<Offset>): Rect? {
        if (pts.isEmpty()) return null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (p in pts) {
            minX = min(minX, p.x)
            minY = min(minY, p.y)
            maxX = max(maxX, p.x)
            maxY = max(maxY, p.y)
        }
        if (!minX.isFinite()) return null
        if (abs(maxX - minX) < 1f) {
            maxX += 500f
            minX -= 500f
        }
        if (abs(maxY - minY) < 1f) {
            maxY += 500f
            minY -= 500f
        }
        return Rect(minX, minY, maxX, maxY)
    }

    private fun Map<PyObject, PyObject>.pyGet(key: String): PyObject? =
        this[PyObject.fromJava(key)]

    private fun pyTypeName(obj: PyObject): String =
        obj.type().get("__name__")?.toString().orEmpty()

    private fun isNumber(obj: PyObject): Boolean =
        runCatching { obj.toDouble(); true }.getOrDefault(false)

    private fun isPoint(obj: PyObject): Boolean {
        if (pyTypeName(obj) == "dict") {
            val map = obj.asMap()
            return map.pyGet("x") != null || map.pyGet("X") != null
        }
        val list = runCatching { obj.asList() }.getOrNull() ?: return false
        return list.size >= 2 && isNumber(list[0])
    }

    private fun PyObject.asFloat(): Float =
        runCatching { toFloat() }
            .recoverCatching { toDouble().toFloat() }
            .getOrElse { toString().toFloat() }
}
