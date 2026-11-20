package com.noreal.android

import android.app.Activity
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 启动页：声明用途、指向开源仓库，1.8 秒后进入主界面，点击任何位置可提前跳过。
 */
class SplashActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var leaving = false
    private val advance = Runnable { go() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Palette.background)
            setPadding(dp(28), dp(28), dp(28), dp(28))
            isClickable = true
            setOnClickListener { go() }
        }

        root.addView(label("NoReal", 36, Palette.text, bold = true), full())
        root.addView(label("系统级定位模拟", 13, Palette.muted).apply { setPadding(0, dp(8), 0, 0) }, full())

        val notice = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            background = GradientDrawable().apply {
                setColor(Palette.panel)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Palette.outline)
            }
        }
        notice.addView(label("本工具仅供测试使用", 15, Palette.text, bold = true), full())
        notice.addView(label("已开源，可获得最新版本", 13, Palette.muted).apply {
            setPadding(0, dp(9), 0, 0)
        }, full())
        notice.addView(label(REPO_HOST, 13, Palette.accent).apply {
            setPadding(0, dp(7), 0, 0)
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            isClickable = true
            setOnClickListener { openRepo() }
        }, full())

        // 两侧等宽留白，确保卡片在屏幕水平居中。
        val cardRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cardRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        cardRow.addView(notice)
        cardRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        root.addView(cardRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(44) })

        root.addView(label("轻触任意位置继续", 12, Palette.muted).apply {
            setPadding(0, dp(44), 0, 0)
        }, full())

        setContentView(root)
        handler.postDelayed(advance, 1800)
    }

    override fun onDestroy() {
        handler.removeCallbacks(advance)
        super.onDestroy()
    }

    private fun go() {
        if (leaving || isFinishing) return
        leaving = true
        handler.removeCallbacks(advance)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun openRepo() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }
            .onFailure { Toast.makeText(this, REPO_URL, Toast.LENGTH_LONG).show() }
    }

    private fun label(value: String, size: Int, tone: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(tone)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun full() = LinearLayout.LayoutParams(-1, -2)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REPO_HOST = "github.com/Seisen8783/NoReal"
        private const val REPO_URL = "https://github.com/Seisen8783/NoReal"
    }
}
