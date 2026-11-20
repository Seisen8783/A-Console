package com.noreal.locbridge

import android.app.Activity
import android.content.ContentValues
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * 模块的配置与状态页。
 *
 * 这里能做的事：改目标包名和端口、看目标进程是否已经收到坐标、确认 hook 命中过几次。
 * 真正的注入动作在 LSPosed 里完成：启用 LocBridge，作用域勾上目标应用，重启目标应用。
 */
class MainActivity : Activity() {

    private lateinit var targetsInput: EditText
    private lateinit var portInput: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(BACKGROUND) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("LocBridge", 26, TEXT, bold = true))
        root.addView(
            text(
                "把 NoReal 的轨迹坐标直接交给指定应用，绕过系统给模拟定位打的标记。\n" +
                    "坐标由 NoReal 在本机广播，模块在目标进程里订阅，全程不经过定位服务。",
                13, MUTED
            ),
            margins(top = 8, bottom = 18)
        )

        root.addView(text("目标包名", 13, TEXT, bold = true))
        targetsInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            setTextColor(TEXT)
            setTextSize(15f)
            setPadding(dp(10), 0, dp(10), 0)
            background = surface(PANEL, OUTLINE, 10)
            hint = "com.tencent.mm"
            contentDescription = "目标包名，逗号分隔"
        }
        root.addView(targetsInput, margins(top = 6, bottom = 14).apply {
            height = dp(46)
            width = -1
        })

        root.addView(text("坐标流端口", 13, TEXT, bold = true))
        portInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            setTextColor(TEXT)
            setTextSize(15f)
            setPadding(dp(10), 0, dp(10), 0)
            background = surface(PANEL, OUTLINE, 10)
            hint = "38765"
            contentDescription = "坐标流端口"
        }
        root.addView(portInput, margins(top = 6, bottom = 14).apply {
            height = dp(46)
            width = -1
        })

        root.addView(button("保存配置") { save() }, margins(top = 2, bottom = 10).apply {
            height = dp(48)
            width = -1
        })

        root.addView(button("刷新状态") { loadStatus() }, margins(bottom = 16).apply {
            height = dp(44)
            width = -1
        })

        status = text("", 13, MUTED)
        root.addView(status)

        root.addView(
            text(
                "使用步骤\n" +
                    "1. 在 LSPosed（或 LSPatch）里启用本模块，作用域勾选目标应用与「系统框架」。\n" +
                    "2. 打开 NoReal，切换到「接管模式」并开始轨迹回放。\n" +
                    "3. 回到这里看状态：出现坐标与命中次数说明注入生效。\n" +
                    "4. 改完配置要重启目标应用才会重新加载模块。",
                13, MUTED
            ),
            margins(top = 20)
        )

        loadConfig()
        loadStatus()
    }

    private fun loadConfig() {
        val config = BridgeConfig.read(applicationContext)
        targetsInput.setText(config.targets.joinToString(","))
        portInput.setText(config.port.toString())
    }

    private fun save() {
        val targets = targetsInput.text.toString()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val port = portInput.text.toString().toIntOrNull()
        if (targets.isEmpty()) {
            status.text = "至少填一个目标包名。"
            return
        }
        if (port == null || port !in 1024..65535) {
            status.text = "端口不合法。"
            return
        }
        runCatching {
            contentResolver.insert(
                Uri.parse("content://${BridgeProvider.AUTHORITY}"),
                ContentValues().apply {
                    put(BridgeProvider.KEY_TARGETS, targets.joinToString(","))
                    put(BridgeProvider.KEY_PORT, port)
                }
            )
        }.onFailure {
            status.text = "保存失败：${it.message}"
            return
        }
        status.text = "已保存。重启目标应用后生效。"
    }

    private fun loadStatus() {
        val cursor = runCatching {
            contentResolver.query(
                Uri.parse("content://${BridgeProvider.AUTHORITY}"),
                null,
                null,
                null,
                null
            )
        }.getOrNull()
        cursor?.use {
            if (!it.moveToFirst()) {
                status.text = "还没有收到目标进程的回执。"
                return
            }
            val raw = it.getString(2).orEmpty()
            val updatedAt = it.getLong(3)
            if (raw.isEmpty()) {
                status.text = "还没有收到目标进程的回执。\n请确认模块已启用、作用域包含目标应用，并已重启目标应用。"
                return
            }
            val json = JSONObject(raw)
            val hit = json.optLong("hits")
            val connected = json.optBoolean("connected")
            val error = json.optString("error")
            val builder = StringBuilder()
            builder.append("目标进程：").append(json.optString("pkg")).append(" · ")
                .append(json.optString("proc")).append('\n')
            builder.append("已收到坐标：").append(if (connected) "是" else "否")
                .append(" · 命中 ").append(hit).append(" 次\n")
            if (json.has("lat")) {
                builder.append(String.format("坐标：%.6f, %.6f\n", json.optDouble("lat"), json.optDouble("lon")))
                builder.append(String.format("速度：%.2f m/s · 圈数 %d\n", json.optDouble("spd"), json.optInt("lap")))
            }
            if (error.isNotEmpty()) builder.append("订阅错误：").append(error).append('\n')
            if (updatedAt > 0) {
                builder.append("最近回执：").append(
                    android.text.format.DateFormat.format("HH:mm:ss", updatedAt)
                )
            }
            status.text = builder.toString()
        }
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(size.toFloat())
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(TEXT)
        setOnClickListener { onClick() }
    }

    private fun surface(fill: Int, stroke: Int, radius: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun margins(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        this.topMargin = dp(top)
        this.bottomMargin = dp(bottom)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val BACKGROUND = 0xFFFAFAF7.toInt()
        private const val PANEL = 0xFFFFFFFF.toInt()
        private const val TEXT = 0xFF16181D.toInt()
        private const val MUTED = 0xFF6B6E76.toInt()
        private const val OUTLINE = 0x1A16181D
    }
}
