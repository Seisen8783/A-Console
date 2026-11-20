package com.noreal.android

import android.content.Context
import android.util.Xml
import com.noreal.core.Point
import com.noreal.core.Route
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipFile

internal object TrackFiles {
    private const val MAX_FILE = 10 * 1024 * 1024
    private const val MAX_XML = 20 * 1024 * 1024
    private const val MAX_POINTS = 100_000

    fun read(context: Context, input: InputStream): List<Route> {
        val file = File.createTempFile("track-", ".tmp", context.cacheDir)
        try {
            input.use { stream -> file.outputStream().use { copyBounded(stream, it, MAX_FILE) } }
            val signature = file.inputStream().use { it.read() == 0x50 && it.read() == 0x4b }
            if (!signature) return file.inputStream().use { parse(it.readBytes()) }
            return ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".kml", ignoreCase = true) }
                    .toList()
                val root = entries.filter { it.name.equals("doc.kml", ignoreCase = true) }
                val entry = root.singleOrNull() ?: entries.singleOrNull()
                    ?: throw IllegalArgumentException("KMZ 应含一个 doc.kml 或唯一 KML。")
                require(entry.size <= MAX_XML) { "轨迹 XML 超过 20 MiB。" }
                zip.getInputStream(entry).use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    copyBounded(stream, output, MAX_XML)
                    parse(output.toByteArray())
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Int) {
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "轨迹文件超过大小上限。" }
            output.write(buffer, 0, count)
        }
    }

    private fun parse(bytes: ByteArray): List<Route> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(bytes.inputStream(), null)
        val routes = mutableListOf<Route>()
        var rootName = ""
        var segmentDepth = -1
        var segment = mutableListOf<Point>()
        var total = 0
        fun append(latitude: String?, longitude: String?) {
            val lat = latitude?.toDoubleOrNull()
            val lon = longitude?.toDoubleOrNull()
            require(lat != null && lon != null && lat.isFinite() && lon.isFinite()
                && lat in -90.0..90.0 && lon in -180.0..180.0) { "发现无效的经纬度。" }
            require(++total <= MAX_POINTS) { "轨迹点超过 10 万个。" }
            val point = Point(lat, lon)
            if (segment.lastOrNull() != point) segment.add(point)
        }
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.DOCDECL -> throw IllegalArgumentException("轨迹 XML 含不支持的文档类型声明。")
                XmlPullParser.START_TAG -> {
                    if (parser.depth == 1) {
                        rootName = parser.name
                        require(rootName == "kml" || rootName == "gpx") { "请选择 KMZ、KML 或 GPX 轨迹。" }
                    }
                    if (segmentDepth < 0 && ((rootName == "kml" && parser.name == "LineString")
                            || (rootName == "gpx" && (parser.name == "trkseg" || parser.name == "rte")))) {
                        segmentDepth = parser.depth
                        segment = mutableListOf()
                    } else if (parser.depth == segmentDepth + 1 && segmentDepth > 0) {
                        if (rootName == "kml" && parser.name == "coordinates") {
                            parser.nextText().splitToSequence(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { tuple ->
                                val values = tuple.split(',')
                                require(values.size in 2..3) { "KML 坐标格式无效。" }
                                append(values[1], values[0])
                            }
                        } else if (rootName == "gpx" && (parser.name == "trkpt" || parser.name == "rtept")) {
                            append(parser.getAttributeValue(null, "lat"), parser.getAttributeValue(null, "lon"))
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.depth == segmentDepth) {
                    if (segment.size >= 2) routes.add(Route(segment.toList()))
                    segmentDepth = -1
                }
            }
        }
        require(routes.isNotEmpty()) { "文件没有可回放的有效线段。" }
        return routes
    }
}
