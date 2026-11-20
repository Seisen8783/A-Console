package com.noreal.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.noreal.core.Point
import com.noreal.core.distanceMeters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

internal class LocationSessionService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private lateinit var store: TrackStore
    private lateinit var output: MockLocationOutput
    private lateinit var location: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var state: SessionState = SessionState.Idle()
    private var firstActiveStart = 0L
    private var lastFix = 0L
    private var lastPoint: Point? = null
    private var lastPointTime = 0L
    private var recordListening = false
    private var mockEpoch = 0L
    private var mockStart = 0L
    private var terminalFrame = false

    private val recordListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            runCatching { record(location) }.onFailure { finish(errorMessage(it), preserveDraft = true) }
        }

        override fun onProviderDisabled(provider: String) {
            finish("定位已关闭，录制草稿已保留。", preserveDraft = true)
        }

        override fun onProviderEnabled(provider: String) = Unit
        @Deprecated("Legacy location callback")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    }

    private val pulse = object : Runnable {
        override fun run() {
            runCatching {
                when (val current = state) {
                    is SessionState.Recording -> {
                        check(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            "精确定位权限已失效。"
                        }
                        if (current.signalAvailable && lastFix > 0L &&
                            SystemClock.elapsedRealtimeNanos() - lastFix > GAP_NANOS) update(current.copy(signalAvailable = false))
                        handler.postDelayed(this, 1000L)
                    }
                    is SessionState.Mock -> publishMock(current)
                    is SessionState.Idle -> Unit
                }
            }.onFailure { finish(errorMessage(it), preserveDraft = true) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        location = getSystemService(LocationManager::class.java)
        worker = HandlerThread("noreal-location").apply { start() }
        handler = Handler(worker.looper)
        store = TrackStore(this)
        output = MockLocationOutput(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START_RECORD, START_MOCK, START_HOLD -> {
                val mockRequest = if (intent.action == START_MOCK) SessionEvents.mockRequest else null
                val holdRequest = if (intent.action == START_HOLD) SessionEvents.holdRequest else null
                SessionEvents.mockRequest = null
                SessionEvents.holdRequest = null
                try {
                    promote(intent.action.orEmpty())
                } catch (_: SecurityException) {
                    SessionEvents.publish(SessionState.Idle("请先授予精确定位权限。"))
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                handler.post {
                    if (state !is SessionState.Idle) return@post
                    runCatching {
                        when (intent.action) {
                            START_RECORD -> beginRecord()
                            START_HOLD -> {
                                checkNotNull(holdRequest) { "请选择要定位的轨迹。" }
                                beginHold(holdRequest)
                            }
                            else -> {
                                checkNotNull(mockRequest) { "请选择要回放的轨迹。" }
                                beginMock(mockRequest.route, mockRequest.duration, mockRequest.laps)
                            }
                        }
                    }.onFailure { finish(errorMessage(it), preserveDraft = true) }
                }
            }
            PAUSE -> handler.post { runCatching { pause() }.onFailure { finish(errorMessage(it), true) } }
            RESUME -> handler.post { runCatching { resume() }.onFailure { finish(errorMessage(it), true) } }
            STOP -> handler.post { finish("", preserveDraft = false) }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun promote(action: String) {
        val notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, "轨迹定位会话", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 100, Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }, pendingFlags)
        val stop = PendingIntent.getService(this, 101, Intent(this, javaClass).setAction(STOP), pendingFlags)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notification = builder.setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(
                when (action) {
                    START_RECORD -> "NoReal · 真实轨迹录制"
                    START_HOLD -> "NoReal · 定位保持"
                    else -> "NoReal · 系统测试定位"
                }
            )
            .setContentText("点此返回轨迹界面")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "停止", stop
            ).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginRecord() {
        check(location.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "请开启手机的精确定位服务。" }
        check(output.restorePending()) { "请重新授权并清理上一会话的模拟定位后再录制。" }
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        val id = store.createDraft("录制 $stamp")
        update(SessionState.Recording(id, PlaybackClock().resume()))
        subscribeToFixes()
        handler.post(pulse)
    }

    @Suppress("MissingPermission")
    private fun subscribeToFixes() {
        check(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            "请先授予精确定位权限。"
        }
        firstActiveStart = SystemClock.elapsedRealtimeNanos()
        lastFix = 0L
        lastPoint = null
        lastPointTime = 0L
        location.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, recordListener, worker.looper)
        recordListening = true
    }

    private fun stopFixes() {
        if (recordListening) {
            recordListening = false
            runCatching { location.removeUpdates(recordListener) }
        }
    }

    @Suppress("DEPRECATION")
    private fun record(fix: Location) {
        val current = state as? SessionState.Recording ?: return
        if (!current.clock.running) return
        val mocked = if (Build.VERSION.SDK_INT >= 31) fix.isMock else fix.isFromMockProvider
        if (mocked || !fix.hasAccuracy() || fix.accuracy !in 0.0f..25.0f ||
            !fix.latitude.isFinite() || !fix.longitude.isFinite() ||
            fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0) return
        val time = fix.elapsedRealtimeNanos
        if (time < firstActiveStart || time <= lastFix) return
        var segment = current.segment
        if (lastFix > 0 && time - lastFix > GAP_NANOS) {
            segment += 1
            lastPoint = null
        }
        lastFix = time
        val point = Point(fix.latitude, fix.longitude)
        var distance = lastPoint?.let { distanceMeters(it, point) } ?: 0.0
        val secondsSinceStoredPoint = (time - lastPointTime) / 1_000_000_000.0
        if (lastPoint != null && distance > maxOf(70.0, 15.0 * secondsSinceStoredPoint)) {
            segment += 1
            lastPoint = null
            distance = 0.0
        }
        val append = lastPoint == null || distance >= 3.0
        if (append) {
            check(current.pointCount < 100_000) { "已达到单条录制的 10 万点上限。" }
            store.append(current.draftId, segment, point, fix.time, fix.accuracy)
            lastPoint = point
            lastPointTime = time
        }
        update(current.copy(
            point = point,
            pointCount = current.pointCount + if (append) 1 else 0,
            segment = segment,
            distanceMeters = current.distanceMeters + if (append) distance else 0.0,
            signalAvailable = true
        ))
    }

    private fun beginHold(route: com.noreal.core.Route) {
        val enabled = if (Build.VERSION.SDK_INT >= 28) location.isLocationEnabled
            else location.isProviderEnabled(LocationManager.GPS_PROVIDER)
        check(enabled) { "请开启手机定位服务。" }
        output.start()
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:hold").apply {
            setReferenceCounted(false)
            acquire(3_600_000L)
        }
        mockEpoch = System.currentTimeMillis()
        mockStart = SystemClock.elapsedRealtimeNanos()
        terminalFrame = false
        val holding = SessionState.Mock(route, 0.0, PlaybackClock(startedNanos = mockStart),
            output.activeSources, 1, hold = true)
        update(holding)
        publishMock(holding, first = true)
    }

    private fun beginMock(route: com.noreal.core.Route, duration: Double, laps: Int) {
        val enabled = if (Build.VERSION.SDK_INT >= 28) location.isLocationEnabled
            else location.isProviderEnabled(LocationManager.GPS_PROVIDER)
        check(enabled) { "请开启手机定位服务。" }
        output.start()
        val total = duration * laps.coerceAtLeast(1)
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:replay").apply {
            setReferenceCounted(false)
            acquire(((total + 120.0) * 1000.0).roundToLong().coerceIn(120_000L, 86_400_000L))
        }
        mockEpoch = System.currentTimeMillis()
        mockStart = SystemClock.elapsedRealtimeNanos()
        terminalFrame = false
        val playing = SessionState.Mock(route, duration, PlaybackClock(startedNanos = mockStart),
            output.activeSources, laps)
        update(playing)
        publishMock(playing, first = true)
    }

    private fun publishMock(current: SessionState.Mock, first: Boolean = false) {
        if (current.hold) {
            if (!current.clock.running) return
            val now = SystemClock.elapsedRealtimeNanos()
            output.publish(current.holdPoint, 0.0, 0.0, mockEpoch + (now - mockStart) / 1_000_000L, now)
            if (current.sources != output.activeSources) update(current.copy(sources = output.activeSources))
            handler.postDelayed(pulse, 1000L)
            return
        }
        if (terminalFrame) {
            finish("模拟完成", preserveDraft = false, keepLocation = true)
            return
        }
        val now = SystemClock.elapsedRealtimeNanos()
        val total = current.totalDuration
        val elapsed = if (first) 0.0 else current.clock.read(now).coerceIn(0.0, total)
        val result = current.route.sample(current.withinLap(elapsed), current.duration)
        terminalFrame = elapsed >= total
        output.publish(
            result.point,
            if (current.clock.running && !terminalFrame) result.speedMps else 0.0,
            result.bearingDegrees,
            mockEpoch + (now - mockStart) / 1_000_000L,
            now
        )
        if (current.sources != output.activeSources) update(current.copy(sources = output.activeSources))
        val remaining = if (current.clock.running) ((total - elapsed) * 1000.0).roundToLong() else 1000L
        val delay = if (terminalFrame) 1000L else remaining.coerceIn(1L, 1000L)
        handler.postDelayed(pulse, delay)
    }

    private fun pause() {
        when (val current = state) {
            is SessionState.Recording -> if (current.clock.running) {
                stopFixes()
                handler.removeCallbacks(pulse)
                update(current.copy(clock = current.clock.pause(), signalAvailable = false))
            }
            is SessionState.Mock -> if (current.clock.running && !terminalFrame) {
                handler.removeCallbacks(pulse)
                val paused = current.copy(clock = current.clock.pause())
                update(paused)
                publishMock(paused)
            }
            is SessionState.Idle -> Unit
        }
    }

    private fun resume() {
        when (val current = state) {
            is SessionState.Recording -> if (!current.clock.running) {
                check(location.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "请开启手机定位服务。" }
                subscribeToFixes()
                update(current.copy(clock = current.clock.resume(), segment = current.segment + 1))
                handler.post(pulse)
            }
            is SessionState.Mock -> if (!current.clock.running) {
                handler.removeCallbacks(pulse)
                val resumed = current.copy(clock = current.clock.resume())
                update(resumed)
                publishMock(resumed)
            }
            is SessionState.Idle -> Unit
        }
    }

    private fun update(next: SessionState) {
        state = next
        SessionEvents.publish(next)
    }

    private fun finish(message: String, preserveDraft: Boolean, keepLocation: Boolean = false) {
        handler.removeCallbacks(pulse)
        val recording = state as? SessionState.Recording
        stopFixes()
        val clean = runCatching {
            if (keepLocation) output.markHeld() else output.restorePending()
        }.getOrDefault(false)
        releaseWakeLock()
        val status = when {
            !clean -> "模拟定位清理待处理。请检查开发者选项中的授权。"
            keepLocation -> "$message，定位已保留在终点。"
            else -> message
        }
        val draftToOffer = if (!preserveDraft) recording?.draftId else null
        update(SessionState.Idle(status, draftToOffer))
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (keepLocation) notifyFinished()
        stopSelf()
    }

    /** 跑完轨迹后弹一条普通通知，位置保持不动。 */
    private fun notifyFinished() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "模拟完成提醒", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 102, Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }, pendingFlags)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_DONE)
            else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("NoReal · 模拟完成")
            .setContentText("定位停在终点，未恢复真实位置")
            .setStyle(Notification.BigTextStyle().bigText("轨迹已跑完，系统定位仍停在终点。打开应用可恢复真实定位。"))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(NOTIFICATION_DONE_ID, notification) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun errorMessage(failure: Throwable): String = when {
        failure is SecurityException -> "系统拒绝写入模拟定位：没有 mock location 权限。"
        failure is IllegalStateException -> failure.message ?: "位置会话中断。"
        else -> "位置会话中断，已保留录制草稿。"
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        handler.post {
            stopFixes()
            if (state !is SessionState.Idle) {
                runCatching { output.restorePending() }
                releaseWakeLock()
                update(SessionState.Idle("位置会话已结束，已保留录制草稿。"))
            }
            store.close()
        }
        worker.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val START_RECORD = "record"
        const val START_MOCK = "mock"
        const val START_HOLD = "hold"
        const val PAUSE = "pause"
        const val RESUME = "resume"
        const val STOP = "stop"
        private const val CHANNEL = "location-session"
        private const val CHANNEL_DONE = "location-done"
        private const val NOTIFICATION_ID = 10
        private const val NOTIFICATION_DONE_ID = 11
        private const val GAP_NANOS = 15_000_000_000L
    }
}
