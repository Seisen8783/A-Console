package com.noreal.android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.noreal.core.Point
import com.noreal.core.Route

internal sealed interface SessionState {
    data class Idle(val message: String = "", val draftId: Long? = null) : SessionState
    data class Recording(
        val draftId: Long,
        val clock: PlaybackClock,
        val point: Point? = null,
        val pointCount: Int = 0,
        val segment: Int = 0,
        val distanceMeters: Double = 0.0,
        val signalAvailable: Boolean = false
    ) : SessionState

    data class Mock(
        val route: Route,
        val duration: Double,
        val clock: PlaybackClock,
        val sources: List<String>,
        val laps: Int = 1
    ) : SessionState {
        val lapCount: Int get() = laps.coerceAtLeast(1)
        val totalDuration: Double get() = duration * lapCount
        val totalMeters: Double get() = route.lengthMeters * lapCount

        /** 当前所在圈（从 1 开始），已跑完或超过总时长时夹到最后一圈。 */
        fun lapAt(elapsed: Double): Int {
            if (duration <= 0.0) return 1
            return ((elapsed / duration).toInt() + 1).coerceIn(1, lapCount)
        }

        /** 当前圈内的进度秒数。 */
        fun withinLap(elapsed: Double): Double {
            if (duration <= 0.0) return 0.0
            val index = (elapsed / duration).toInt().coerceIn(0, lapCount - 1)
            return (elapsed - index * duration).coerceIn(0.0, duration)
        }
    }
}

/** 一次模拟请求：单圈时长与圈数分开存，总时长由两者相乘得到。 */
internal data class MockRequest(val route: Route, val duration: Double, val laps: Int)

internal data class PlaybackClock(val elapsed: Double = 0.0, val startedNanos: Long = 0L) {
    val running: Boolean get() = startedNanos != 0L
    fun read(now: Long = SystemClock.elapsedRealtimeNanos()): Double =
        elapsed + if (running) (now - startedNanos) / 1_000_000_000.0 else 0.0
    fun resume(): PlaybackClock = copy(startedNanos = SystemClock.elapsedRealtimeNanos())
    fun pause(): PlaybackClock = PlaybackClock(read())
}

internal object SessionEvents {
    private val main = Handler(Looper.getMainLooper())
    private val observers = mutableSetOf<(SessionState) -> Unit>()
    @Volatile var current: SessionState = SessionState.Idle()
        private set
    @Volatile var starting = false
    var mockRequest: MockRequest? = null

    fun observe(observer: (SessionState) -> Unit) {
        observers.add(observer)
        observer(current)
    }

    fun remove(observer: (SessionState) -> Unit) {
        observers.remove(observer)
    }

    fun consumeDraft(id: Long) {
        val state = current as? SessionState.Idle ?: return
        if (state.draftId == id) current = state.copy(draftId = null)
    }

    fun publish(state: SessionState) {
        current = state
        starting = false
        main.post { observers.toList().forEach { it(state) } }
    }
}
