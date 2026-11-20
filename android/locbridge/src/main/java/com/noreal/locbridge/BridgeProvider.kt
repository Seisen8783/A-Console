package com.noreal.locbridge

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * 用一个 provider 跨进程传递配置与状态。
 *
 * - UI 进程写配置（目标包名、端口），注入到目标进程的模块读配置。
 * - 注入到目标进程的模块回写状态（最近一次坐标、命中次数），UI 进程读状态。
 *
 * 这里只在本机传递配置和调试信息，没有敏感数据，因此 provider 设为 exported。
 */
internal class BridgeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.$AUTHORITY"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val preferences = context?.getSharedPreferences(STORE, Context.MODE_PRIVATE) ?: return null
        val cursor = MatrixCursor(COLUMNS)
        cursor.addRow(
            arrayOf<Any?>(
                preferences.getString(KEY_TARGETS, BridgeConfig.DEFAULT.targets.joinToString(",")),
                preferences.getInt(KEY_PORT, BridgeConfig.DEFAULT.port),
                preferences.getString(KEY_STATUS, ""),
                preferences.getLong(KEY_STATUS_AT, 0L)
            )
        )
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val preferences = context?.getSharedPreferences(STORE, Context.MODE_PRIVATE) ?: return null
        val editor = preferences.edit()
        values?.getAsString(KEY_TARGETS)?.let { incoming ->
            // 取并集：探针 App 可以把自己加进来，不会覆盖用户在模块界面里配好的目标。
            val merged = (preferences.getString(KEY_TARGETS, "") ?: "")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableSet()
            incoming.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { merged.add(it) }
            editor.putString(KEY_TARGETS, merged.joinToString(","))
        }
        values?.getAsInteger(KEY_PORT)?.let { editor.putInt(KEY_PORT, it) }
        values?.getAsString(KEY_STATUS)?.let {
            editor.putString(KEY_STATUS, it)
            editor.putLong(KEY_STATUS_AT, System.currentTimeMillis())
        }
        editor.apply()
        return ContentUris.withAppendedId(uri, System.currentTimeMillis())
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        insert(uri, values)
        return 1
    }

    companion object {
        const val AUTHORITY = "com.noreal.locbridge.provider"
        const val STORE = "bridge"
        const val KEY_TARGETS = "targets"
        const val KEY_PORT = "port"
        const val KEY_STATUS = "status"
        const val KEY_STATUS_AT = "statusAt"
        val COLUMNS = arrayOf(KEY_TARGETS, KEY_PORT, KEY_STATUS, KEY_STATUS_AT)
    }
}
