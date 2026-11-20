package com.noreal.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.noreal.core.Point
import com.noreal.core.Route
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

internal class TrackMapView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val roadPath = Path()
    private val routePath = Path()
    private val visibleProgress = Path()
    private val directionMarker = Path()
    private val recordingPath = Path()
    private val measure = PathMeasure()
    private val bounds = RectF()
    private var origin: Point? = null
    private var lonMeters = 111_195.08
    private var zoom = 1f
    private var baseScale = 1f
    private var panX = 0f
    private var panY = 0f
    private var following = true
    private var routeVertices = emptyList<PointF>()
    private var routeMeters = DoubleArray(0)
    private var geometryMeters = FloatArray(0)
    private var recordedSegment = -1
    private var marker: Point? = null
    private var traveledMeters = 0.0
    private var bearing: Double? = null
    var pins: List<Point> = emptyList()
        set(value) { field = value; invalidate() }
    var onPick: ((Point) -> Unit)? = null
    var onFollowChanged: ((Boolean) -> Unit)? = null

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onScroll(first: MotionEvent?, latest: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDetector.isInProgress) return true
            follow(false)
            panX -= dx
            panY -= dy
            invalidate()
            return true
        }

        override fun onSingleTapUp(event: MotionEvent): Boolean {
            val callback = onPick ?: return false
            val point = pointAtScreen(event.x, event.y) ?: return false
            callback(point)
            performClick()
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            follow(false)
            val previous = zoom
            zoom = (zoom * detector.scaleFactor).coerceIn(0.5f, 32f)
            val factor = zoom / previous
            val focusX = detector.focusX - width / 2f
            val focusY = detector.focusY - height / 2f
            panX = focusX - (focusX - panX) * factor
            panY = focusY - (focusY - panY) * factor
            invalidate()
            return true
        }
    })

    init {
        isClickable = true
        contentDescription = "可拖动缩放的离线轨迹图"
    }

    fun scene(roads: List<Route>, active: Route?) {
        origin = roads.firstOrNull()?.points?.firstOrNull() ?: active?.points?.firstOrNull()
        origin?.let { lonMeters = 111_195.08 * max(0.00001, cos(it.latitude * PI / 180.0)) }
        roadPath.rewind()
        routePath.rewind()
        recordingPath.rewind()
        recordedSegment = -1
        marker = null
        traveledMeters = 0.0
        pins = emptyList()
        bounds.setEmpty()
        var hasPoint = false
        fun projectPath(points: List<Point>, target: Path): List<Pair<Int, PointF>> {
            val step = max(1, (points.size - 1) / 10_000 + 1)
            val indices = (0 until points.lastIndex step step).toList() + points.lastIndex
            return indices.mapIndexed { index, source ->
                val mapped = project(points[source])
                if (hasPoint) {
                    bounds.set(min(bounds.left, mapped.x), min(bounds.top, mapped.y),
                        max(bounds.right, mapped.x), max(bounds.bottom, mapped.y))
                } else {
                    bounds.set(mapped.x, mapped.y, mapped.x, mapped.y)
                    hasPoint = true
                }
                if (index == 0) target.moveTo(mapped.x, mapped.y) else target.lineTo(mapped.x, mapped.y)
                source to mapped
            }
        }
        roads.forEach { projectPath(it.points, roadPath) }
        val routeGeometry = active?.let { projectPath(it.points, routePath) }.orEmpty()
        routeVertices = routeGeometry.map { it.second }
        routeMeters = routeGeometry.map { active!!.cumulativeMeters[it.first] }.toDoubleArray()
        geometryMeters = FloatArray(routeVertices.size)
        for (index in 1 until routeVertices.size) {
            val a = routeVertices[index - 1]
            val b = routeVertices[index]
            geometryMeters[index] = geometryMeters[index - 1] + kotlin.math.hypot(b.x - a.x, b.y - a.y)
        }
        measure.setPath(routePath, false)
        zoom = 1f
        panX = 0f
        panY = 0f
        follow(true)
        fit()
        invalidate()
    }

    fun showPlannedRoute(roads: List<Route>, active: Route?) {
        val lastZoom = zoom
        val lastPanX = panX
        val lastPanY = panY
        scene(roads, active)
        zoom = lastZoom
        panX = lastPanX
        panY = lastPanY
        invalidate()
    }

    fun setRecorded(segments: List<List<Point>>) {
        scene(emptyList(), null)
        for ((index, segment) in segments.withIndex()) {
            for (point in segment) appendRecorded(index, point)
        }
    }

    fun appendRecorded(segment: Int, point: Point) {
        val firstPoint = origin == null
        if (firstPoint) {
            origin = point
            lonMeters = 111_195.08 * max(0.00001, cos(point.latitude * PI / 180.0))
        }
        val mapped = project(point)
        if (recordedSegment != segment) {
            recordedSegment = segment
            recordingPath.moveTo(mapped.x, mapped.y)
        } else {
            recordingPath.lineTo(mapped.x, mapped.y)
        }
        if (firstPoint) {
            bounds.set(mapped.x - 30, mapped.y - 30, mapped.x + 30, mapped.y + 30)
            fit()
        }
        invalidate()
    }

    fun showPosition(point: Point?, traveled: Double = 0.0, direction: Double? = null) {
        marker = point
        traveledMeters = traveled
        bearing = direction
        if (following && point != null && width > 0 && height > 0) {
            val mapped = toScreen(project(point))
            val edge = dp(28f)
            if (mapped.x < edge || mapped.y < edge || mapped.x > width - edge || mapped.y > height - edge) {
                center(point)
            }
        }
        invalidate()
    }

    fun recenter() {
        follow(true)
        val point = marker
        if (point != null) center(point) else {
            zoom = 1f
            panX = 0f
            panY = 0f
        }
        invalidate()
    }

    private fun center(point: Point) {
        val mapped = project(point)
        val scale = baseScale * zoom
        panX = (bounds.centerX() - mapped.x) * scale
        panY = (bounds.centerY() - mapped.y) * scale
    }

    private fun follow(value: Boolean) {
        if (following != value) {
            following = value
            onFollowChanged?.invoke(value)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gesture.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fit()
    }

    private fun fit() {
        val usableWidth = max(1f, width - dp(50f))
        val usableHeight = max(1f, height - dp(50f))
        baseScale = min(usableWidth / max(1f, bounds.width()), usableHeight / max(1f, bounds.height())).coerceAtMost(16f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF131F2D.toInt())
        drawGrid(canvas)
        if (origin == null) return
        val scale = baseScale * zoom
        canvas.save()
        canvas.translate(width / 2f + panX, height / 2f + panY)
        canvas.scale(scale, scale)
        canvas.translate(-bounds.centerX(), -bounds.centerY())
        stroke(0xFF344B5F.toInt(), dp(7f) / scale)
        canvas.drawPath(roadPath, paint)
        stroke(0xFF527788.toInt(), dp(4.5f) / scale)
        canvas.drawPath(routePath, paint)
        stroke(0xFF6DE6C2.toInt(), dp(4.5f) / scale)
        canvas.drawPath(recordingPath, paint)
        if (traveledMeters > 0.0 && routeMeters.isNotEmpty()) {
            visibleProgress.rewind()
            val search = routeMeters.binarySearch(traveledMeters)
            val progress = if (search >= 0) {
                geometryMeters[search]
            } else {
                val end = (-search - 1).coerceAtMost(routeMeters.lastIndex)
                val start = max(0, end - 1)
                val span = routeMeters[end] - routeMeters[start]
                val fraction = if (span > 0.0) ((traveledMeters - routeMeters[start]) / span).coerceIn(0.0, 1.0) else 0.0
                geometryMeters[start] + (geometryMeters[end] - geometryMeters[start]) * fraction.toFloat()
            }
            if (measure.getSegment(0f, progress, visibleProgress, true)) canvas.drawPath(visibleProgress, paint)
        }
        canvas.restore()

        if (routeVertices.isNotEmpty() && pins.isEmpty()) {
            val first = toScreen(routeVertices.first())
            val last = toScreen(routeVertices.last())
            dot(canvas, first, 0xFF66DFC0.toInt())
            dot(canvas, last, 0xFFF4AB61.toInt())
        }
        pins.forEachIndexed { index, point ->
            val center = toScreen(project(point))
            fill(0xFF67E5C2.toInt())
            canvas.drawCircle(center.x, center.y, dp(10f), paint)
            fill(0xFF102420.toInt())
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = dp(11f)
            canvas.drawText((index + 1).toString(), center.x, center.y - (paint.ascent() + paint.descent()) / 2f, paint)
        }
        marker?.let {
            val center = toScreen(project(it))
            fill(0x4467E5C2)
            canvas.drawCircle(center.x, center.y, dp(15f), paint)
            fill(0xFFCBFFF0.toInt())
            canvas.drawCircle(center.x, center.y, dp(6f), paint)
            bearing?.let { angle ->
                directionMarker.apply {
                    rewind()
                    moveTo(center.x, center.y - dp(13f))
                    lineTo(center.x - dp(3.5f), center.y - dp(6f))
                    lineTo(center.x + dp(3.5f), center.y - dp(6f))
                    close()
                }
                canvas.save()
                canvas.rotate(angle.toFloat(), center.x, center.y)
                canvas.drawPath(directionMarker, paint)
                canvas.restore()
            }
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val cell = dp(28f)
        stroke(0xFF223143.toInt(), dp(0.75f))
        var x = panX.mod(cell)
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            x += cell
        }
        var y = panY.mod(cell)
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
            y += cell
        }
    }

    private fun stroke(color: Int, width: Float) {
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
    }

    private fun fill(color: Int) {
        paint.color = color
        paint.style = Paint.Style.FILL
    }

    private fun dot(canvas: Canvas, point: PointF, color: Int) {
        fill(0xFF131F2D.toInt())
        canvas.drawCircle(point.x, point.y, dp(7f), paint)
        fill(color)
        canvas.drawCircle(point.x, point.y, dp(4.5f), paint)
    }

    private fun project(point: Point): PointF {
        val anchor = origin ?: point
        val longitudeDelta = (point.longitude - anchor.longitude + 180.0).mod(360.0) - 180.0
        return PointF((longitudeDelta * lonMeters).toFloat(), ((anchor.latitude - point.latitude) * 111_195.08).toFloat())
    }

    private fun pointAtScreen(x: Float, y: Float): Point? {
        val anchor = origin ?: return null
        val scale = baseScale * zoom
        val dx = (x - width / 2f - panX) / scale + bounds.centerX()
        val dy = (y - height / 2f - panY) / scale + bounds.centerY()
        val lat = (anchor.latitude - dy / 111_195.08).coerceIn(-90.0, 90.0)
        val lon = (anchor.longitude + dx / lonMeters + 180.0).mod(360.0) - 180.0
        return Point(lat, lon)
    }

    private fun toScreen(point: PointF): PointF {
        val scale = baseScale * zoom
        return PointF(width / 2f + panX + (point.x - bounds.centerX()) * scale,
            height / 2f + panY + (point.y - bounds.centerY()) * scale)
    }

    private fun dp(value: Float) = resources.displayMetrics.density * value
}
