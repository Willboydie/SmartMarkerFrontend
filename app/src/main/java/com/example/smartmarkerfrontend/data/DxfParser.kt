package com.example.smartmarkerfrontend.data

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight ASCII DXF reader for set-out coordinates.
 * Extracts POINT / INSERT / compact CIRCLE centres as vertices, plus LINE /
 * LWPOLYLINE / CIRCLE geometry for the plan canvas.
 */
object DxfParser {

    data class Extracted(
        val nodes: List<MarkerNode>,
        val geometry: PlanGeometry
    )

    fun extract(dxf: String): Extracted {
        val entities = parseEntities(dxf)
        val polylines = mutableListOf<Polyline>()
        val circles = mutableListOf<CircleGeom>()
        val rawNodes = mutableListOf<Pair<Offset, String?>>()

        for (e in entities) {
            when (e.type) {
                "LINE" -> {
                    val a = e.pt(10, 20) ?: continue
                    val b = e.pt(11, 21) ?: continue
                    polylines += Polyline(listOf(a, b))
                }
                "LWPOLYLINE" -> {
                    if (e.vertices.size >= 2) {
                        polylines += Polyline(e.vertices, closed = e.closed)
                    }
                }
                "POLYLINE" -> {
                    if (e.vertices.size >= 2) {
                        polylines += Polyline(e.vertices, closed = e.closed)
                    }
                }
                "CIRCLE" -> {
                    val c = e.pt(10, 20) ?: continue
                    val r = e.float(40) ?: continue
                    circles += CircleGeom(c, r)
                    if (r <= 200f) rawNodes += c to null
                }
                "POINT", "INSERT" -> {
                    val p = e.pt(10, 20) ?: continue
                    rawNodes += p to e.text
                }
                "TEXT", "MTEXT", "ATTRIB", "ATTDEF" -> {
                    val p = e.pt(10, 20) ?: e.pt(11, 21) ?: continue
                    val label = e.text?.trim().orEmpty()
                    if (label.isNotEmpty()) rawNodes += p to label
                }
            }
        }

        val nodes = buildNodes(rawNodes, polylines)
        val allPts = buildList {
            addAll(nodes.map { Offset(it.x, it.y) })
            polylines.forEach { addAll(it.points) }
            circles.forEach { add(it.center) }
        }
        val bounds = boundsOf(allPts) ?: Rect(0f, 0f, 1000f, 1000f)
        val marker = Offset(bounds.center.x, bounds.bottom + bounds.height * 0.08f)
        val geometry = PlanGeometry(
            bounds = bounds.inflate(max(bounds.width, bounds.height) * 0.04f),
            outlines = polylines,
            circles = circles,
            marker = marker
        )
        return Extracted(nodes, geometry)
    }

    private fun buildNodes(
        raw: List<Pair<Offset, String?>>,
        polylines: List<Polyline>
    ): List<MarkerNode> {
        val merged = mergePoints(raw)
        val labeled = merged.filter { !it.second.isNullOrBlank() }
        val unlabeled = merged.filter { it.second.isNullOrBlank() }

        val chosen = when {
            unlabeled.isNotEmpty() -> {
                unlabeled.map { p ->
                    val near = labeled.minByOrNull { hypot((it.first.x - p.first.x).toDouble(), (it.first.y - p.first.y).toDouble()) }
                    val label = if (near != null && hypot((near.first.x - p.first.x).toDouble(), (near.first.y - p.first.y).toDouble()) < 800.0) {
                        near.second
                    } else null
                    p.first to label
                }
            }
            labeled.isNotEmpty() -> labeled
            else -> {
                val verts = polylines.flatMap { it.points }.let { mergePoints(it.map { p -> p to null }) }
                verts.take(80)
            }
        }

        val used = mutableSetOf<String>()
        return chosen.mapIndexed { i, (pt, label) ->
            val id = uniqueId(label, i, used)
            MarkerNode(id, pt.x, pt.y, NodeStatus.Open)
        }
    }

    private fun uniqueId(label: String?, index: Int, used: MutableSet<String>): String {
        val base = label?.let { sanitizeId(it) }?.takeIf { it.isNotBlank() } ?: "V%02d".format(index + 1)
        var id = base
        var n = 2
        while (id in used) {
            id = "$base-$n"
            n++
        }
        used += id
        return id
    }

    private fun sanitizeId(raw: String): String {
        val t = raw.substringBefore("\n").trim()
        val m = Regex("""[A-Za-z]{0,4}\d{1,4}""").find(t)
        return (m?.value ?: t.take(8)).uppercase()
    }

    private fun mergePoints(pts: List<Pair<Offset, String?>>, tol: Float = 8f): List<Pair<Offset, String?>> {
        val out = mutableListOf<Pair<Offset, String?>>()
        for (p in pts) {
            val i = out.indexOfFirst { hypot((it.first.x - p.first.x).toDouble(), (it.first.y - p.first.y).toDouble()) <= tol }
            if (i < 0) {
                out += p
            } else if (out[i].second.isNullOrBlank() && !p.second.isNullOrBlank()) {
                out[i] = out[i].first to p.second
            }
        }
        return out
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

    private fun Rect.inflate(pad: Float) = Rect(left - pad, top - pad, right + pad, bottom + pad)

    private data class Ent(
        val type: String,
        val nums: MutableMap<Int, Float> = mutableMapOf(),
        val vertices: MutableList<Offset> = mutableListOf(),
        var text: String? = null,
        var closed: Boolean = false
    ) {
        fun pt(xc: Int, yc: Int): Offset? {
            val x = nums[xc] ?: return null
            val y = nums[yc] ?: return null
            return Offset(x, y)
        }

        fun float(code: Int): Float? = nums[code]
    }

    private fun parseEntities(dxf: String): List<Ent> {
        val lines = dxf.lines().map { it.trim() }
        val out = mutableListOf<Ent>()
        var inEntities = false
        var current: Ent? = null
        var polyline: Ent? = null
        var pendingX: Float? = null
        var i = 0
        while (i + 1 < lines.size) {
            val code = lines[i].toIntOrNull()
            val value = lines[i + 1]
            i += 2
            if (code == null) continue
            if (code == 0) {
                current?.let { if (it.type != "VERTEX" && it.type != "SEQEND") out += it }
                if (value.equals("SECTION", true) || value.equals("ENDSEC", true) ||
                    value.equals("EOF", true) || value.equals("TABLE", true)
                ) {
                    current = null
                    continue
                }
                if (value.equals("VERTEX", true) && polyline != null) {
                    current = Ent("VERTEX")
                    continue
                }
                if (value.equals("SEQEND", true)) {
                    polyline?.let { out += it }
                    polyline = null
                    current = null
                    continue
                }
                val ent = Ent(value.uppercase())
                current = ent
                if (ent.type == "POLYLINE") polyline = ent
                continue
            }
            if (code == 2 && value.equals("ENTITIES", true)) inEntities = true
            if (!inEntities && current == null) continue
            val ent = current ?: continue
            when (code) {
                1, 3 -> if (ent.text.isNullOrBlank()) ent.text = value
                70 -> if (value.toIntOrNull()?.and(1) == 1) ent.closed = true
                10 -> {
                    val x = value.toFloatOrNull()
                    if (x != null) {
                        if (ent.type == "LWPOLYLINE") pendingX = x else ent.nums[10] = x
                    }
                }
                20 -> {
                    val y = value.toFloatOrNull() ?: continue
                    if (ent.type == "LWPOLYLINE") {
                        val x = pendingX
                        if (x != null) {
                            ent.vertices += Offset(x, y)
                            pendingX = null
                        }
                    } else {
                        ent.nums[20] = y
                        if (ent.type == "VERTEX") {
                            val x = ent.nums[10]
                            if (x != null) polyline?.vertices?.add(Offset(x, y))
                        }
                    }
                }
                11, 21, 40 -> value.toFloatOrNull()?.let { ent.nums[code] = it }
            }
        }
        current?.let { if (it.type != "VERTEX") out += it }
        polyline?.let { if (it !in out) out += it }
        return out.filter { it.type != "SECTION" && it.type != "ENDSEC" }
    }
}
