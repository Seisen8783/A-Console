package com.noreal.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.noreal.core.Point
import com.noreal.core.Route

internal data class TrackEntry(val id: Long, val title: String, val draft: Boolean)

internal class TrackStore(context: Context) : SQLiteOpenHelper(context, "tracks.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE tracks (
                id INTEGER PRIMARY KEY, title TEXT NOT NULL, draft INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE points (
                id INTEGER PRIMARY KEY, track_id INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
                segment INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL,
                time_ms INTEGER, accuracy_m REAL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX points_by_track ON points(track_id, segment, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun entries(): List<TrackEntry> = readableDatabase.query(
        "tracks", arrayOf("id", "title", "draft"), null, null, null, null, "id DESC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(TrackEntry(cursor.getLong(0), cursor.getString(1), cursor.getInt(2) == 1))
        }
    }

    fun routes(id: Long): List<Route> = readSegments(id).filter { it.size >= 2 }.map { Route(it) }

    fun readSegments(id: Long): List<List<Point>> = readableDatabase.query(
        "points", arrayOf("segment", "lat", "lon"), "track_id = ?", arrayOf(id.toString()),
        null, null, "segment, id"
    ).use { cursor ->
        val segments = linkedMapOf<Int, MutableList<Point>>()
        while (cursor.moveToNext()) {
            val points = segments.getOrPut(cursor.getInt(0)) { mutableListOf() }
            val point = Point(cursor.getDouble(1), cursor.getDouble(2))
            if (points.lastOrNull() != point) points.add(point)
        }
        segments.values.toList()
    }

    fun addRoute(title: String, route: Route): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = createTrack(db, title, false)
            db.compileStatement(
                "INSERT INTO points(track_id, segment, lat, lon) VALUES (?, 0, ?, ?)"
            ).use { statement ->
                statement.bindLong(1, id)
                for (point in route.points) {
                    statement.bindDouble(2, point.latitude)
                    statement.bindDouble(3, point.longitude)
                    statement.executeInsert()
                }
            }
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    fun createDraft(title: String): Long = createTrack(writableDatabase, title, true)

    fun append(id: Long, segment: Int, point: Point, wallTime: Long, accuracy: Float) {
        writableDatabase.insertOrThrow("points", null, ContentValues().apply {
            put("track_id", id)
            put("segment", segment)
            put("lat", point.latitude)
            put("lon", point.longitude)
            put("time_ms", wallTime)
            put("accuracy_m", accuracy)
        })
    }

    fun saveDraft(id: Long, title: String) {
        writableDatabase.update("tracks", ContentValues().apply {
            put("title", title)
            put("draft", 0)
        }, "id = ?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("tracks", "id = ?", arrayOf(id.toString()))
    }

    private fun createTrack(db: SQLiteDatabase, title: String, draft: Boolean): Long =
        db.insertOrThrow("tracks", null, ContentValues().apply {
            put("title", title)
            put("draft", if (draft) 1 else 0)
        })
}
