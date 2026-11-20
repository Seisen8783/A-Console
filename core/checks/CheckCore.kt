package com.unreal.core

import java.io.File
import java.util.Locale
import kotlin.math.abs

fun main(args: Array<String>) {
    val route = Route(File(args[0]).readLines().map {
        val (latitude, longitude) = it.split(',').map(String::toDouble)
        Point(latitude, longitude)
    })
    check(route.points.size == 644)
    check(abs(route.lengthMeters - 905.0450243886174) < 0.00001)
    check(abs(route.durationSeconds(360.0) - 325.816208779902) < 0.00001)
    val router = CampusRouter(listOf(route))
    val start = checkNotNull(router.snap(route.points.first()))
    val end = checkNotNull(router.snap(route.points.last()))
    val reversed = checkNotNull(router.plan(listOf(end, start)))
    check(reversed.points == route.points.reversed())
    check(router.snap(Point(0.0, 0.0)) == null)

    val a = Point(0.0, 0.0)
    val b = Point(0.0, 0.001)
    val c = Point(0.001, 0.001)
    val d = Point(0.0, 0.003)
    val e = Point(0.001, 0.003)
    val graph = CampusRouter(listOf(Route(listOf(a, b)), Route(listOf(b, c)), Route(listOf(d, e))))
    val from = checkNotNull(graph.snap(Point(0.0, 0.0005)))
    val via = checkNotNull(graph.snap(b))
    val to = checkNotNull(graph.snap(c))
    check(graph.plan(listOf(from, via, to))?.points?.contains(b) == true)
    check(graph.plan(listOf(from, checkNotNull(graph.snap(e)))) == null)
    check(abs(Route(listOf(Point(0.0, 179.9), Point(0.0, -179.9))).sample(1.0, 2.0).point.longitude) > 179.99)

    for (i in 0..326) {
        val sample = route.sample(i.toDouble(), route.durationSeconds(360.0))
        println(String.format(Locale.ROOT, "%.12f,%.12f", sample.point.latitude, sample.point.longitude))
    }
}
