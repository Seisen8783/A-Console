package com.noreal.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.noreal.core.CampusRouter
import com.noreal.core.Point
import com.noreal.core.Route
import com.noreal.core.Snap
import com.noreal.core.distanceMeters
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

class MainActivity : Activity() {
    private enum class Page { PLAYBACK, RECORD, PLANNER }

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var store: TrackStore
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var navigation: List<Button>
    private lateinit var map: TrackMapView
    private lateinit var title: TextView
    private lateinit var summary: TextView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var controls: LinearLayout
    private lateinit var recenter: Button
    private lateinit var timeInput: EditText
    private lateinit var resultText: TextView
    private lateinit var planControls: LinearLayout
    private var page = Page.PLAYBACK
    private var selected: Route? = null
    private var selectedTitle = "校园跑"
    private var chosenDefault: Route? = null
    private var campus: CampusRouter? = null
    private var roads: List<Route> = emptyList()
    private val pins = mutableListOf<Snap>()
    private var planned: Route? = null
    private var paneVisible = false
    private var playbackElapsed = 0.0
    private var duration = 0.0
    private var laps = 1
    private var lastProgress = -1
    private var lastReadoutSecond = -1L
    private var lastFrame = 0L
    private var framePending = false
    private var serviceState: SessionState = SessionState.Idle()
    private var requestingPermission: String? = null
    private var sessionStarting = false
    private var startupLoading = true
    private var importBusy = false
    private var editingPace = true
    private var pace = 360.0
    private var recordedCount = 0
    private var draftShowing: Long? = null
    private val observer: (SessionState) -> Unit = ::onSession

    private val animate = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePending = false
            if (!paneVisible) return
            if (frameTimeNanos - lastFrame >= 32_000_000L) {
                lastFrame = frameTimeNanos
                drawState()
            }
            requestFrames()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TrackStore(applicationContext)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BACKGROUND)
            clipToPadding = false
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(5), dp(16), dp(22))
            isFocusableInTouchMode = true
        }
        scroll.addView(root)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        setContentView(scroll)
        root.addView(text("NoReal", 34, TEXT, bold = true))
        root.addView(text("系统级定位模拟", 14, MUTED), margins(top = 2, bottom = 12))
        val tabs = row()
        navigation = listOf(
            button("系统模拟") { changePage(Page.PLAYBACK) },
            button("真实录制") { changePage(Page.RECORD) },
            button("校园规划") { changePage(Page.PLANNER) }
        )
        navigation.forEach { tabs.addView(it, weighted()) }
        root.addView(tabs)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body, margins(top = 15))
        renderPage()
        SessionEvents.observe(observer)
        io.execute {
            val initial = runCatching {
                val default = assets.open("default.kmz").use { TrackFiles.read(this, it).single() }
                val publishedRoads = runCatching { readCampusRoads() }.getOrElse { listOf(default) }
                Triple(default, publishedRoads, CampusRouter(publishedRoads))
            }
            if (SessionEvents.current is SessionState.Idle && !SessionEvents.starting && MockLocationOutput.hasPending(this)) {
                runCatching { MockLocationOutput(this).restorePending() }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                startupLoading = false
                initial.onSuccess { result ->
                    chosenDefault = result.first
                    roads = result.second
                    campus = result.third
                    if (selected == null) setRoute(result.first, "校园跑")
                    else if (page == Page.PLANNER) renderPage()
                }.onFailure { toast("默认轨迹加载失败，请选择文件导入。") }
                if (MockLocationOutput.hasPending(this) && SessionEvents.current is SessionState.Idle) {
                    toast("上一会话的系统定位清理待处理，请检查开发者选项。")
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        paneVisible = true
        drawState(force = true)
        requestFrames()
        offerDraft()
    }

    override fun onStop() {
        paneVisible = false
        Choreographer.getInstance().removeFrameCallback(animate)
        framePending = false
        super.onStop()
    }

    override fun onDestroy() {
        SessionEvents.remove(observer)
        io.execute { store.close() }
        io.shutdown()
        super.onDestroy()
    }

    private fun changePage(next: Page) {
        if (next == page) return
        if (busy()) {
            toast("请先结束当前的录制或模拟。")
            return
        }
        page = next
        renderPage()
    }

    private fun renderPage() {
        navigation.forEachIndexed { index, view -> decorate(view, Page.entries[index] == page) }
        body.removeAllViews()
        val top = row()
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = text("", 21, TEXT, bold = true)
        summary = text("", 13, MUTED)
        labels.addView(title)
        labels.addView(summary, margins(top = 3))
        top.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        recenter = button("回到位置") { map.recenter() }
        top.addView(recenter)
        body.addView(top)
        map = TrackMapView(this)
        val mapHeight = if (page == Page.PLAYBACK) dp(232) else dp(300)
        body.addView(map, LinearLayout.LayoutParams(-1, mapHeight).apply { topMargin = dp(10) })
        status = text("", 15, TEXT, bold = true)
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        detail = text("", 13, MUTED)
        body.addView(status, margins(top = 12))
        body.addView(detail, margins(top = 5))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(OUTLINE)
        }
        body.addView(progress, margins(top = 4))
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(controls, margins(top = 10))
        lastReadoutSecond = -1
        lastProgress = -1
        when (page) {
            Page.PLAYBACK -> {
                title.text = selectedTitle
                summary.text = selected?.let { "${formatDistance(it.lengthMeters)} · ${it.points.size} 个轨迹点" }
                    ?: "载入默认轨迹或选择文件"
                map.scene(emptyList(), selected)
                map.showPosition(selected?.points?.firstOrNull())
            }
            Page.RECORD -> {
                title.text = "录制真实轨迹"
                summary.text = "使用手机定位 · 支持锁屏录制"
                val active = serviceState as? SessionState.Recording
                val draft = active?.draftId
                val segments = draft?.let { runCatching { store.readSegments(it) }.getOrDefault(emptyList()) }.orEmpty()
                recordedCount = segments.sumOf { it.size }
                map.setRecorded(segments)
                map.showPosition(active?.point)
            }
            Page.PLANNER -> {
                title.text = "校园离线规划"
                summary.text = "在已覆盖的道路上选择路径点"
                map.scene(roads, planned)
                map.pins = pins.map { it.point }
                map.onPick = ::pick
            }
        }
        renderControls()
        drawState(force = true)
    }

    private fun renderControls() {
        if (!::controls.isInitialized) return
        controls.removeAllViews()
        when (page) {
            Page.PLAYBACK -> renderPlaybackControls()
            Page.RECORD -> renderRecordingControls()
            Page.PLANNER -> renderPlannerControls()
        }
    }

    private fun renderPlaybackControls() {
        val active = serviceState as? SessionState.Mock
        val interaction = row()
        if (active != null) {
            val moving = active.clock.running
            if (!active.hold) {
                interaction.addView(button(if (moving) "暂停" else "继续", accent = true) {
                    sessionCommand(if (moving) LocationSessionService.PAUSE else LocationSessionService.RESUME)
                }, weighted())
            }
            interaction.addView(button("停止") { stopPlayback() }, weighted())
            controls.addView(interaction)
            val sources = active.sources.joinToString("、").ifEmpty { "GPS" }
            controls.addView(text("正在写入系统定位源 · $sources", 12, MUTED), margins(top = 6))
            return
        }

        val assetsRow = row().apply {
            addView(button("导入文件") { openPicker() }, weighted())
            addView(button("轨迹库") { showLibrary() }, weighted())
            addView(button("默认轨迹") { chosenDefault?.let { setRoute(it, "校园跑") } }, weighted())
        }
        controls.addView(assetsRow)
        val modeRow = row().apply {
            addView(button("按配速设置", editingPace) {
                currentPace()?.let { pace = it }
                editingPace = true
                renderControls()
            }, weighted())
            addView(button("按用时设置", !editingPace) {
                currentPace()?.let { pace = it }
                editingPace = false
                renderControls()
            }, weighted())
        }
        controls.addView(modeRow, margins(top = 6))
        val config = row().apply { gravity = Gravity.CENTER_VERTICAL }
        timeInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_TIME
            isSingleLine = true
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            setTextSize(19f)
            setPadding(dp(9), 0, dp(9), 0)
            background = surface(PANEL, OUTLINE, 10)
            hint = if (editingPace) "分:秒" else "总用时"
            contentDescription = if (editingPace) "配速，分每公里" else "总完成用时"
            val lapSeconds = selected?.durationSeconds(pace) ?: 0.0
            setText(if (editingPace) timeText(pace) else timeText(lapSeconds * laps))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(editable: Editable?) = describeConfig()
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            })
        }
        config.addView(timeInput, LinearLayout.LayoutParams(dp(106), dp(45)).apply { marginStart = dp(3); marginEnd = dp(10) })
        resultText = text("", 13, MUTED)
        config.addView(resultText, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(config, margins(top = 8, bottom = 6))

        val lapRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        lapRow.addView(text("圈数", 13, MUTED), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(3) })
        lapRow.addView(button("−") { changeLaps(-1) }, LinearLayout.LayoutParams(dp(46), dp(38)).apply { marginStart = dp(8) })
        lapRow.addView(text(laps.toString(), 18, TEXT, bold = true), LinearLayout.LayoutParams(dp(48), -2).apply {
            gravity = Gravity.CENTER
        })
        lapRow.addView(button("+") { changeLaps(1) }, LinearLayout.LayoutParams(dp(46), dp(38)))
        val route = selected
        val summary = if (route == null || laps <= 1) "" else {
            "总计 ${formatDistance(route.lengthMeters * laps)}"
        }
        lapRow.addView(text(summary, 12, MUTED), LinearLayout.LayoutParams(0, -2, 1f).apply {
            gravity = Gravity.END
            marginEnd = dp(3)
        })
        controls.addView(lapRow, margins(top = 2))
        if (route != null && laps > 1) {
            val gap = closingGap(route)
            if (gap > 30.0) {
                controls.addView(
                    text("轨迹首尾相差 ${formatDistance(gap)}，循环时会从终点跳回起点", 12, Palette.warning),
                    margins(top = 4)
                )
            }
        }
        describeConfig()
        interaction.addView(button("定位到起点") { startHold() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        interaction.addView(button("开始系统模拟", accent = true) { startPlayback() },
            LinearLayout.LayoutParams(0, dp(48), 1.6f).apply { marginStart = dp(3); marginEnd = dp(3) })
        controls.addView(interaction)
        val allowed = MockLocationOutput.allowed(this)
        controls.addView(
            text(
                if (allowed) "已授权 · 模拟坐标会写入系统定位源，其他应用可直接读取"
                else "未授权 · 需在开发者选项把 NoReal 设为模拟位置信息应用",
                12, if (allowed) MUTED else Palette.warning
            ),
            margins(top = 6)
        )
    }

    private fun renderRecordingControls() {
        val active = serviceState as? SessionState.Recording
        val buttons = row()
        if (active == null) {
            buttons.addView(button("开始录制", accent = true) {
                if (!busy()) askLocation(LocationSessionService.START_RECORD)
            }, weighted())
            buttons.addView(button("查看录制") { showLibrary() }, weighted())
        } else {
            buttons.addView(button(if (active.clock.running) "暂停录制" else "继续录制", accent = true) {
                sessionCommand(if (active.clock.running) LocationSessionService.PAUSE else LocationSessionService.RESUME)
            }, weighted())
            buttons.addView(button("停止并保存") { sessionCommand(LocationSessionService.STOP) }, weighted())
        }
        controls.addView(buttons)
    }

    private fun renderPlannerControls() {
        planControls = row()
        planControls.addView(button("撤销标点") {
            if (pins.isNotEmpty()) {
                pins.removeAt(pins.lastIndex)
                updatePlan()
            }
        }, weighted())
        planControls.addView(button("清除标点") {
            pins.clear()
            updatePlan()
        }, weighted())
        planControls.addView(button("保存并模拟", accent = true) {
            val route = planned
            if (route == null) toast("请先标记两个可以连通的位置。")
            else persistRoute("校园规划", route)
        }, weighted())
        controls.addView(planControls)
    }

    private fun describeConfig() {
        if (!::resultText.isInitialized) return
        val route = selected ?: run { resultText.text = "请先选择轨迹"; return }
        val count = laps.coerceAtLeast(1)
        val number = readTime(timeInput.text.toString())
        val resultingPace = currentPace()
        if (resultingPace == null || number == null) {
            val minimum = ceil(route.durationSeconds(180.0) * count)
            val maximum = floor(route.durationSeconds(540.0) * count)
            resultText.text = if (editingPace) "配速范围 3:00–9:00 分/公里"
                else "有效总用时 ${timeText(minimum)}–${timeText(maximum)}"
        } else {
            resultText.text = if (editingPace) {
                "分/公里 · 总用时 ${timeText(route.durationSeconds(resultingPace) * count)}"
            } else {
                "总用时 · 配速约 ${timeText(resultingPace)} 分/公里"
            }
        }
    }

    private fun currentPace(): Double? {
        if (!::timeInput.isInitialized) return null
        val route = selected ?: return null
        val entered = readTime(timeInput.text.toString()) ?: return null
        if (entered <= 0.0) return null
        val count = laps.coerceAtLeast(1)
        val result = if (editingPace) entered
            else entered * 1000.0 / (route.lengthMeters * count)
        return result.takeIf { it in 180.0..540.0 }
    }

    private fun changeLaps(delta: Int) {
        if (busy()) return
        val next = (laps + delta).coerceIn(1, 99)
        if (next == laps) return
        currentPace()?.let { pace = it }
        laps = next
        renderControls()
        drawState(force = true)
    }

    /** 轨迹首尾距离，用来判断循环时会不会出现跳变。 */
    private fun closingGap(route: Route): Double =
        if (route.points.size < 2) 0.0 else distanceMeters(route.points.first(), route.points.last())

    private fun startPlayback() {
        if (busy()) return
        val route = selected ?: run { toast("请先选择轨迹。"); return }
        val value = currentPace() ?: run { toast("请检查配速或完成用时。"); return }
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(timeInput.windowToken, 0)
        pace = value
        duration = route.durationSeconds(value)
        playbackElapsed = 0.0
        if (!MockLocationOutput.allowed(this)) {
            showMockPermissionDialog { beginMock(route, duration) }
            return
        }
        beginMock(route, duration)
    }

    private fun showMockPermissionDialog(onProceed: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("需要「模拟位置信息应用」授权")
            .setMessage(
                "这一步只需做一次。\n\n" +
                    "原生 Android：设置 → 开发者选项 → 选择模拟位置信息应用 → 选中 NoReal；" +
                    "也可以在「应用详情 → 权限」里找同名开关。\n\n" +
                    "鸿蒙、卓易通这类兼容层：鸿蒙设置里没有这一项，要到容器的 Android 设置里找。\n\n" +
                    "adb 授予：\n" +
                    "adb shell appops set com.noreal.android android:mock_location allow"
            )
            .setNegativeButton("取消", null)
            .setNeutralButton("开发者选项") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    .onFailure { openAppDetails() }
            }
            .setPositiveButton("仍然尝试") { _, _ -> onProceed() }
            .show()
    }

    private fun beginMock(route: Route, lapSeconds: Double) {
        SessionEvents.mockRequest = MockRequest(route, lapSeconds, laps.coerceAtLeast(1))
        askLocation(LocationSessionService.START_MOCK)
    }

    /** 把定位直接钉在当前轨迹的起点，不跑轨迹。 */
    private fun startHold() {
        if (busy()) return
        val route = selected ?: run { toast("请先选择轨迹。"); return }
        playbackElapsed = 0.0
        if (!MockLocationOutput.allowed(this)) {
            showMockPermissionDialog { beginHold(route) }
            return
        }
        beginHold(route)
    }

    private fun beginHold(route: Route) {
        SessionEvents.holdRequest = route
        askLocation(LocationSessionService.START_HOLD)
    }

    private fun stopPlayback() {
        if (serviceState is SessionState.Mock) sessionCommand(LocationSessionService.STOP)
    }

    private fun askLocation(action: String) {
        if (!hasFineLocation()) {
            requestingPermission = action
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), PERMISSION_LOCATION)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notification_asked", false)) {
            requestingPermission = action
            getPreferences(MODE_PRIVATE).edit().putBoolean("notification_asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_NOTIFICATION)
            return
        }
        sessionCommand(action)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        val action = requestingPermission ?: return
        requestingPermission = null
        if (requestCode == PERMISSION_LOCATION && !hasFineLocation()) {
            SessionEvents.mockRequest = null
            toast("需要精确定位权限才能使用此功能。")
        } else if (requestCode == PERMISSION_LOCATION) askLocation(action)
        else if (requestCode == PERMISSION_NOTIFICATION) sessionCommand(action)
    }

    private fun hasFineLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun sessionCommand(action: String) {
        val intent = Intent(this, LocationSessionService::class.java).setAction(action)
        val start = action == LocationSessionService.START_MOCK ||
            action == LocationSessionService.START_RECORD ||
            action == LocationSessionService.START_HOLD
        if (start && sessionStarting) return
        if (start) {
            sessionStarting = true
            SessionEvents.starting = true
        }
        runCatching {
            if (start && Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        }.onFailure {
            sessionStarting = false
            if (start) SessionEvents.starting = false
            SessionEvents.mockRequest = null
            toast("无法启动位置会话，请检查定位授权。")
        }
    }

    private fun onSession(next: SessionState) {
        sessionStarting = false
        val previous = serviceState
        serviceState = next
        when (next) {
            is SessionState.Mock -> {
                if (previous !is SessionState.Mock || page != Page.PLAYBACK) {
                    selected = next.route
                    duration = next.duration
                    page = Page.PLAYBACK
                    renderPage()
                } else if (previous.clock.running != next.clock.running) renderControls()
            }
            is SessionState.Recording -> {
                if (previous !is SessionState.Recording || page != Page.RECORD) {
                    page = Page.RECORD
                    renderPage()
                } else if (previous.clock.running != next.clock.running) renderControls()
                if (next.pointCount > recordedCount && next.point != null) {
                    map.appendRecorded(next.segment, next.point)
                    recordedCount = next.pointCount
                }
            }
            is SessionState.Idle -> {
                if (previous is SessionState.Mock) {
                    val total = previous.totalDuration
                    val elapsed = if (next.message == "模拟完成。") total else previous.clock.read()
                    playbackElapsed = elapsed.coerceIn(0.0, total)
                }
                if (previous !is SessionState.Idle) renderControls()
                if (next.message.isNotBlank()) toast(next.message)
                offerDraft()
            }
        }
        drawState(force = true)
        requestFrames()
    }

    private fun requestFrames() {
        val running = when (val current = serviceState) {
            is SessionState.Mock -> current.clock.running && !current.hold
            is SessionState.Recording -> current.clock.running
            is SessionState.Idle -> false
        }
        if (paneVisible && running && !framePending) {
            framePending = true
            Choreographer.getInstance().postFrameCallback(animate)
        }
    }

    private fun offerDraft() {
        if (!paneVisible) return
        val id = (serviceState as? SessionState.Idle)?.draftId ?: return
        if (id != draftShowing) {
            draftShowing = id
            saveDraft(id)
        }
    }

    private fun drawState(force: Boolean = false) {
        if (!::status.isInitialized) return
        val active = serviceState
        when (page) {
            Page.PLAYBACK -> {
                val route = selected
                if (route == null) {
                    status.text = "请选择轨迹"
                    detail.text = ""
                    return
                }
                val system = active as? SessionState.Mock
                if (system?.hold == true) {
                    val point = system.holdPoint
                    val holding = system.clock.running
                    if (force || holding) map.showPosition(point, 0.0, null)
                    status.text = if (holding) "已定位在起点 · 保持中" else "定位保持已暂停"
                    detail.text = String.format(Locale.ROOT, "%.6f, %.6f  ·  静态\n系统定位源  %s",
                        point.latitude, point.longitude,
                        system.sources.joinToString("、").ifEmpty { "GPS" })
                    if (progress.progress != 0) progress.progress = 0
                    return
                }
                val lapSeconds = system?.duration ?: duration
                val lapTotal = system?.lapCount ?: laps.coerceAtLeast(1)
                val total = if (system != null) system.totalDuration else lapSeconds * lapTotal
                val elapsed = when {
                    total <= 0.0 -> 0.0
                    system != null -> system.clock.read().coerceIn(0.0, total)
                    else -> playbackElapsed.coerceIn(0.0, total)
                }
                val currentLap = if (lapSeconds > 0.0) ((elapsed / lapSeconds).toInt() + 1).coerceIn(1, lapTotal) else 1
                val within = if (lapSeconds > 0.0) (elapsed - (currentLap - 1) * lapSeconds).coerceIn(0.0, lapSeconds) else 0.0
                val result = if (lapSeconds > 0.0) route.sample(within, lapSeconds) else null
                val point = result?.point ?: route.points.first()
                val travelled = ((currentLap - 1) * route.lengthMeters + (result?.distanceMeters ?: 0.0))
                    .coerceAtMost(route.lengthMeters * lapTotal)
                val moving = system?.clock?.running == true
                if (force || moving) map.showPosition(point, result?.distanceMeters ?: 0.0, result?.bearingDegrees)
                val phase = when {
                    total > 0.0 && elapsed >= total -> "已到终点"
                    system != null -> if (moving) "系统模拟中" else "系统模拟已暂停"
                    playbackElapsed > 0.0 -> "模拟已结束"
                    else -> "准备系统模拟"
                }
                val value = if (total > 0.0) ((elapsed / total) * 1000).toInt() else 0
                if (progress.progress != value) progress.progress = value
                val seconds = elapsed.toLong()
                if (force || seconds != lastReadoutSecond || (value == 1000 && lastProgress != value)) {
                    lastReadoutSecond = seconds
                    lastProgress = value
                    val speed = if (moving && value < 1000) result?.speedMps ?: 0.0 else 0.0
                    val lapText = if (lapTotal > 1) "  ·  第 $currentLap/$lapTotal 圈" else ""
                    status.text = "$phase$lapText  ·  ${formatDistance(travelled)}"
                    summary.text = if (lapTotal > 1) {
                        "${formatDistance(route.lengthMeters)} × $lapTotal 圈 · 总计 ${formatDistance(route.lengthMeters * lapTotal)}"
                    } else {
                        "${formatDistance(route.lengthMeters)} · ${route.points.size} 个轨迹点"
                    }
                    val sources = system?.sources?.joinToString("、")?.let { "\n系统定位源  $it" }.orEmpty()
                    detail.text = String.format(Locale.ROOT, "%.6f, %.6f  ·  %.2f m/s\n剩余 %s%s",
                        point.latitude, point.longitude, speed, timeText(ceil(total - elapsed)), sources)
                }
            }
            Page.RECORD -> {
                val recording = active as? SessionState.Recording
                if (recording == null) {
                    status.text = "准备录制"
                    detail.text = "开始后会在这里显示手机的位置和已记录轨迹。"
                    progress.visibility = View.INVISIBLE
                } else {
                    val seconds = recording.clock.read()
                    if (force) map.showPosition(recording.point)
                    if (force || seconds.toLong() != lastReadoutSecond) {
                        lastReadoutSecond = seconds.toLong()
                        status.text = when {
                            !recording.clock.running -> "录制已暂停"
                            !recording.signalAvailable -> "等待有效定位"
                            else -> "录制中  ·  ${formatDistance(recording.distanceMeters)}"
                        }
                        detail.text = "${timeText(seconds)}  ·  ${recording.pointCount} 个轨迹点"
                    }
                }
            }
            Page.PLANNER -> if (force) {
                val route = planned
                status.text = when {
                    pins.isEmpty() -> "点按已知道路设置起点"
                    pins.size == 1 -> "继续点按设置终点"
                    route == null -> "标点之间没有可通行路线"
                    else -> "规划结果  ·  ${formatDistance(route.lengthMeters)}"
                }
                detail.text = "标点 ${pins.size}/7 · 按选点顺序模拟"
                progress.visibility = View.INVISIBLE
            }
        }
    }

    private fun pick(point: Point) {
        if (pins.size == 7) {
            toast("最多设置 7 个路径点。")
            return
        }
        val snap = campus?.snap(point)
        if (snap == null) {
            toast("请在已覆盖道路附近标点。")
            return
        }
        if (pins.lastOrNull()?.point == snap.point) return
        pins.add(snap)
        updatePlan()
    }

    private fun updatePlan() {
        planned = if (pins.size >= 2) campus?.plan(pins) else null
        map.showPlannedRoute(roads, planned)
        map.pins = pins.map { it.point }
        drawState(force = true)
    }

    private fun setRoute(route: Route, name: String) {
        selected = route
        selectedTitle = name
        playbackElapsed = 0.0
        duration = 0.0
        page = Page.PLAYBACK
        renderPage()
    }

    private fun openPicker() {
        if (busy() || importBusy) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, PICK_FILE)
    }

    @Deprecated("Used for a platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        importBusy = true
        io.execute {
            val result = runCatching {
                val filename = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "导入轨迹"
                val input = contentResolver.openInputStream(uri) ?: error("无法读取所选文件。")
                filename.substringBeforeLast('.') to TrackFiles.read(this, input)
            }
            ui {
                importBusy = false
                result.onSuccess { (name, routes) ->
                    chooseRoute(name, routes) { route, label -> persistRoute(label, route) }
                }.onFailure { toast(it.message ?: "导入轨迹失败。") }
            }
        }
    }

    private fun persistRoute(name: String, route: Route) {
        if (importBusy || busy()) return
        importBusy = true
        io.execute {
            val saved = runCatching { store.addRoute(name, route) }
            ui {
                importBusy = false
                saved.onSuccess { setRoute(route, name) }.onFailure { toast("保存轨迹失败。") }
            }
        }
    }

    private fun showLibrary() {
        if (busy()) return
        io.execute {
            val entries = runCatching { store.entries() }.getOrDefault(emptyList())
            ui {
                if (entries.isEmpty()) {
                    toast("还没有保存的轨迹。")
                } else {
                    val labels = entries.map { "${it.title}${if (it.draft) " · 草稿" else ""}" }.toTypedArray()
                    AlertDialog.Builder(this).setTitle("本机轨迹").setItems(labels) { _, index ->
                        val entry = entries[index]
                        if (entry.draft) saveDraft(entry.id)
                        else AlertDialog.Builder(this).setTitle(entry.title)
                            .setItems(arrayOf("开始模拟", "删除轨迹")) { _, action ->
                                if (action == 0) openSaved(entry.id, entry.title) else confirmDelete(entry.id)
                            }.show()
                    }.setNegativeButton("关闭", null).show()
                }
            }
        }
    }

    private fun confirmDelete(id: Long) {
        AlertDialog.Builder(this).setTitle("删除这条轨迹？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> io.execute { store.delete(id) } }.show()
    }

    private fun saveDraft(id: Long) {
        io.execute {
            val tracks = runCatching { store.routes(id) }.getOrDefault(emptyList())
            val currentName = runCatching { store.entries().firstOrNull { it.id == id }?.title }.getOrNull()
            ui {
                if (currentName == null) {
                    SessionEvents.consumeDraft(id)
                    return@ui
                }
                if (tracks.isEmpty()) {
                    AlertDialog.Builder(this).setTitle("录制草稿")
                        .setMessage("暂时没有可模拟的连续轨迹。")
                        .setNegativeButton("保留", null)
                        .setPositiveButton("删除") { _, _ -> io.execute { store.delete(id) } }.show()
                } else {
                    val input = EditText(this).apply {
                        setText(currentName)
                        setSingleLine()
                        selectAll()
                    }
                    val length = tracks.sumOf { it.lengthMeters }
                    AlertDialog.Builder(this).setTitle("保存录制 · ${formatDistance(length)}")
                        .setMessage("${tracks.size} 个有效连续轨迹段")
                        .setView(input)
                        .setNegativeButton("保留草稿", null)
                        .setPositiveButton("保存") { _, _ ->
                            val name = input.text.toString().trim().ifEmpty { currentName }.take(120)
                            io.execute {
                                val saved = runCatching { store.saveDraft(id, name) }
                                ui {
                                    saved.onSuccess { chooseRoute(name, tracks) { route, label -> setRoute(route, label) } }
                                        .onFailure { toast("保存录制失败，草稿已保留。") }
                                }
                            }
                        }.show()
                }
                SessionEvents.consumeDraft(id)
            }
        }
    }

    private fun openSaved(id: Long, name: String) {
        io.execute {
            val routes = runCatching { store.routes(id) }.getOrDefault(emptyList())
            ui { chooseRoute(name, routes) { route, label -> setRoute(route, label) } }
        }
    }

    private fun chooseRoute(name: String, routes: List<Route>, onSelected: (Route, String) -> Unit) {
        when (routes.size) {
            0 -> toast("没有可以模拟的连续轨迹。")
            1 -> onSelected(routes.first(), name)
            else -> {
                val items = routes.mapIndexed { i, route -> "第 ${i + 1} 段 · ${formatDistance(route.lengthMeters)}" }.toTypedArray()
                AlertDialog.Builder(this).setTitle("选择连续轨迹")
                    .setItems(items) { _, index -> onSelected(routes[index], "$name · 第 ${index + 1} 段") }
                    .setNegativeButton("取消", null).show()
            }
        }
    }

    private fun readCampusRoads(): List<Route> {
        val data = assets.open("campus.geojson").bufferedReader().use { it.readText() }
        val features = org.json.JSONObject(data).getJSONArray("features")
        val routes = buildList {
            for (index in 0 until features.length()) {
                val geometry = features.getJSONObject(index).getJSONObject("geometry")
                if (geometry.getString("type") != "LineString") continue
                val coordinates = geometry.getJSONArray("coordinates")
                add(Route(List(coordinates.length()) { i ->
                    val position = coordinates.getJSONArray(i)
                    Point(position.getDouble(1), position.getDouble(0))
                }))
            }
        }
        require(routes.isNotEmpty())
        return routes
    }

    private fun ui(action: () -> Unit) = runOnUiThread { if (!isDestroyed && !isFinishing) action() }
    private fun busy() = startupLoading || serviceState !is SessionState.Idle ||
        sessionStarting || SessionEvents.starting || requestingPermission != null
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun openAppDetails() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast("请手动进入本应用的权限页，授予模拟位置。") }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun weighted() = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) }
    private fun margins(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun text(value: String, size: Int, tone: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(tone)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun surface(fill: Int, outline: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(1), outline)
        cornerRadius = dp(radius).toFloat()
    }

    private fun decorate(view: Button, accent: Boolean) {
        val background = surface(if (accent) ACCENT else PANEL, if (accent) ACCENT else OUTLINE, 11)
        view.background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x22000000), background, null)
        view.setTextColor(if (accent) 0xFF0B2520.toInt() else TEXT)
    }

    private fun button(label: String, accent: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        minimumHeight = dp(44)
        minHeight = dp(44)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(7), 0, dp(7), 0)
        decorate(this, accent)
        setOnClickListener { action() }
    }

    private fun readTime(value: String): Double? {
        val parts = value.trim().split(':')
        if (parts.size !in 2..3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        val amount = parts.map { it.toLongOrNull() ?: return null }
        if (amount.last() >= 60 || (amount.size == 3 && amount[1] >= 60)) return null
        return amount.fold(0.0) { sum, item -> sum * 60.0 + item }
    }

    private fun timeText(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).roundToLong()
        return if (total >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
        else String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
    }

    private fun formatDistance(meters: Double): String =
        if (meters < 1000.0) String.format(Locale.ROOT, "%.1f 米", meters)
        else String.format(Locale.ROOT, "%.2f 公里", meters / 1000.0)

    companion object {
        private const val PICK_FILE = 100
        private const val PERMISSION_LOCATION = 101
        private const val PERMISSION_NOTIFICATION = 102
        private val BACKGROUND = Palette.background
        private val PANEL = Palette.panel
        private val OUTLINE = Palette.outline
        private val TEXT = Palette.text
        private val MUTED = Palette.muted
        private val ACCENT = Palette.accent
    }
}
