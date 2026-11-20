package com.noreal.core

import kotlin.math.*

private const val EARTH_RADIUS = 6_371_008.8
private const val RAD = PI / 180.0
private const val MIN_LEG_METERS = 0.000001

data class Point(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "经纬度超出范围" }
    }
}

data class Sample(
    val point: Point,
    val distanceMeters: Double,
    val speedMps: Double,
    val bearingDegrees: Double,
)

fun distanceMeters(a: Point, b: Point): Double {
    val sineLat = sin((b.latitude - a.latitude) * RAD / 2)
    val sineLon = sin((b.longitude - a.longitude) * RAD / 2)
    val h = sineLat * sineLat + cos(a.latitude * RAD) * cos(b.latitude * RAD) * sineLon * sineLon
    return 2 * EARTH_RADIUS * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

private fun bearingRadians(a: Point, b: Point): Double {
    val lat = a.latitude * RAD
    val nextLat = b.latitude * RAD
    val deltaLon = (b.longitude - a.longitude) * RAD
    return atan2(sin(deltaLon) * cos(nextLat), cos(lat) * sin(nextLat) - sin(lat) * cos(nextLat) * cos(deltaLon))
}

private fun project(a: Point, bearing: Double, meters: Double): Point {
    val angle = meters / EARTH_RADIUS
    val lat = a.latitude * RAD
    val newLat = asin((sin(lat) * cos(angle) + cos(lat) * sin(angle) * cos(bearing)).coerceIn(-1.0, 1.0))
    val deltaLon = atan2(sin(bearing) * sin(angle) * cos(lat), cos(angle) - sin(lat) * sin(newLat))
    return Point(newLat / RAD, ((a.longitude + deltaLon / RAD + 540) % 360) - 180)
}

class Route(input: List<Point>) {
    val points: List<Point> = buildList {
        for (point in input) {
            if (isEmpty() || distanceMeters(last(), point) > MIN_LEG_METERS) add(point)
        }
    }
    val cumulativeMeters: DoubleArray
    val lengthMeters: Double get() = cumulativeMeters.last()

    init {
        require(points.size >= 2) { "轨迹至少需要两个不同的位置" }
        cumulativeMeters = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val leg = distanceMeters(points[i - 1], points[i])
            require(leg < PI * EARTH_RADIUS - 0.001) { "轨迹包含无法确定短弧的对跖点" }
            cumulativeMeters[i] = cumulativeMeters[i - 1] + leg
        }
    }

    private fun segment(meters: Double): Int {
        val i = cumulativeMeters.binarySearch(meters)
        return if (i >= 0) i.coerceAtMost(points.lastIndex - 1) else (-i - 2).coerceAtLeast(0)
    }

    fun pointAt(meters: Double): Point {
        val m = meters.coerceIn(0.0, lengthMeters)
        if (m == lengthMeters) return points.last()
        val i = segment(m)
        if (m == cumulativeMeters[i]) return points[i]
        return project(points[i], bearingRadians(points[i], points[i + 1]), m - cumulativeMeters[i])
    }

    fun bearingAt(meters: Double): Double {
        val m = meters.coerceIn(0.0, lengthMeters)
        val i = segment(m)
        val bearing = if (m == lengthMeters) bearingRadians(points[i + 1], points[i]) + PI
                      else bearingRadians(pointAt(m), points[i + 1])
        return (bearing / RAD + 360) % 360
    }

    fun durationSeconds(paceSecondsPerKm: Double): Double = lengthMeters * paceSecondsPerKm / 1000
    fun paceSecondsPerKm(durationSeconds: Double): Double = durationSeconds * 1000 / lengthMeters

    fun sample(elapsedSeconds: Double, durationSeconds: Double): Sample {
        require(durationSeconds > 0 && durationSeconds.isFinite()) { "完成用时必须大于零" }
        val m = lengthMeters * (elapsedSeconds / durationSeconds).coerceIn(0.0, 1.0)
        return Sample(pointAt(m), m, if (m == lengthMeters) 0.0 else lengthMeters / durationSeconds, bearingAt(m))
    }

    internal fun portion(from: Double, to: Double): List<Point> {
        if (from > to) return portion(to, from).asReversed()
        val result = mutableListOf(pointAt(from))
        var i = segment(from) + 1
        while (i < points.size && cumulativeMeters[i] < to) {
            result.add(points[i])
            i++
        }
        result.add(pointAt(to))
        return result
    }
}

private data class Edge(val route: Route, val start: Int, val end: Int)
private data class Leg(val edge: Int, val from: Double, val to: Double)
private data class Path(val length: Double, val legs: List<Leg>)
private data class Connection(val node: Int, val meters: Double)

class Snap internal constructor(val point: Point, internal val edge: Int, internal val meters: Double)

class CampusRouter(routes: List<Route>) {
    private val nodes = mutableListOf<Point>()
    private val edges = routes.map { Edge(it, node(it.points.first()), node(it.points.last())) }

    private fun node(point: Point): Int {
        val index = nodes.indexOfFirst { it.latitude == point.latitude && it.longitude == point.longitude }
        if (index >= 0) return index
        nodes.add(point)
        return nodes.lastIndex
    }

    fun snap(point: Point, maxMeters: Double = 20.0): Snap? {
        var nearest: Snap? = null
        var best = maxMeters
        for ((edgeIndex, edge) in edges.withIndex()) {
            val route = edge.route
            for (i in 0 until route.points.lastIndex) {
                val a = route.points[i]
                val b = route.points[i + 1]
                val delta = distanceMeters(a, point) / EARTH_RADIUS
                val bearing = bearingRadians(a, b)
                val along = atan2(sin(delta) * cos(bearingRadians(a, point) - bearing), cos(delta)) * EARTH_RADIUS
                val start = route.cumulativeMeters[i]
                val end = route.cumulativeMeters[i + 1]
                val meters = (start + along).coerceIn(start, end)
                val candidate = route.pointAt(meters)
                val offset = distanceMeters(point, candidate)
                if (offset <= best && (nearest == null || offset < best)) {
                    nearest = Snap(candidate, edgeIndex, meters)
                    best = offset
                }
            }
        }
        return nearest
    }

    private fun shortest(start: Int, end: Int): Path? {
        val costs = DoubleArray(nodes.size) { Double.POSITIVE_INFINITY }
        val visited = BooleanArray(nodes.size)
        val paths = arrayOfNulls<List<Leg>>(nodes.size)
        costs[start] = 0.0
        paths[start] = emptyList()
        for (step in nodes.indices) {
            var current = -1
            for (i in nodes.indices) {
                if (!visited[i] && (current == -1 || costs[i] < costs[current])) current = i
            }
            if (current == -1 || !costs[current].isFinite()) break
            if (current == end) return Path(costs[current], paths[current].orEmpty())
            visited[current] = true
            for ((i, edge) in edges.withIndex()) {
                val length = edge.route.lengthMeters
                val next: Int
                val leg: Leg
                if (current == edge.start) {
                    next = edge.end
                    leg = Leg(i, 0.0, length)
                } else if (current == edge.end) {
                    next = edge.start
                    leg = Leg(i, length, 0.0)
                } else continue
                val cost = costs[current] + length
                if (cost < costs[next]) {
                    costs[next] = cost
                    paths[next] = paths[current].orEmpty() + leg
                }
            }
        }
        return null
    }

    private fun path(from: Snap, to: Snap): Path? {
        val first = edges[from.edge]
        val last = edges[to.edge]
        var best = if (from.edge == to.edge) {
            Path(abs(to.meters - from.meters), listOf(Leg(from.edge, from.meters, to.meters)))
        } else null
        for (a in listOf(Connection(first.start, 0.0), Connection(first.end, first.route.lengthMeters))) {
            for (b in listOf(Connection(last.start, 0.0), Connection(last.end, last.route.lengthMeters))) {
                val middle = shortest(a.node, b.node) ?: continue
                val cost = abs(a.meters - from.meters) + middle.length + abs(to.meters - b.meters)
                if (best == null || cost < best.length) {
                    best = Path(cost, listOf(Leg(from.edge, from.meters, a.meters)) + middle.legs + Leg(to.edge, b.meters, to.meters))
                }
            }
        }
        return best
    }

    fun plan(points: List<Snap>): Route? {
        if (points.size < 2) return null
        val result = mutableListOf<Point>()
        for ((from, to) in points.zipWithNext()) {
            val path = path(from, to) ?: return null
            for (leg in path.legs) {
                for (point in edges[leg.edge].route.portion(leg.from, leg.to)) {
                    if (result.isEmpty() || distanceMeters(result.last(), point) > MIN_LEG_METERS) result.add(point)
                }
            }
        }
        return if (result.size < 2) null else Route(result)
    }
}
