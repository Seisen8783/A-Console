package com.noreal.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Process

/**
 * 给自动化测试用的启动入口。
 *
 * 只接受 shell（adb）与 root 发起的广播：调用方 uid 必须是 SHELL_UID 或 ROOT_UID，
 * 否则任何第三方应用都能通过一条广播把用户的定位接管起来。
 */
internal class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val caller = Binder.getCallingUid()
        val allowed = caller == Process.SHELL_UID || caller == Process.ROOT_UID ||
            caller == Process.myUid()
        if (!allowed) {
            android.util.Log.w(TAG, "拒绝非 shell 来源的启动请求：uid=$caller")
            return
        }
        when (intent.action) {
            ACTION_START_INTERCEPT -> {
                val pace = intent.getStringExtra(EXTRA_PACE)?.toDoubleOrNull() ?: DEFAULT_PACE
                val laps = intent.getIntExtra(EXTRA_LAPS, 1).coerceIn(1, 99)
                val track = intent.getStringExtra(EXTRA_TRACK)
                android.util.Log.i(TAG, "启动接管模式回放 pace=$pace laps=$laps track=$track")
                SessionEvents.interceptRequest = InterceptRequest(pace, laps, track)
                SessionEvents.mockRequest = null
                context.startForegroundService(
                    Intent(context, LocationSessionService::class.java)
                        .setAction(LocationSessionService.START_MOCK)
                )
            }
            ACTION_STOP -> {
                android.util.Log.i(TAG, "停止会话")
                context.startService(
                    Intent(context, LocationSessionService::class.java)
                        .setAction(LocationSessionService.STOP)
                )
            }
        }
    }

    companion object {
        private const val TAG = "NoReal"
        const val ACTION_START_INTERCEPT = "com.noreal.android.action.START_INTERCEPT"
        const val ACTION_STOP = "com.noreal.android.action.STOP"
        const val EXTRA_PACE = "pace"
        const val EXTRA_LAPS = "laps"
        const val EXTRA_TRACK = "track"
        const val DEFAULT_PACE = 360.0
    }
}
