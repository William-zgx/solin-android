package com.bytedance.zgx.solin.debug

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bytedance.zgx.solin.device.AndroidCurrentScreenControlProvider
import com.bytedance.zgx.solin.device.ScreenStateReadResult
import com.bytedance.zgx.solin.device.UiActionReadResult
import com.bytedance.zgx.solin.device.background.BackgroundPhoneSession
import com.bytedance.zgx.solin.device.background.BackgroundPhoneFailure
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Manual debug console only. Never reads Intent data, extras, commands or automatic action lists. */
class BackgroundPhoneControlActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val destroyed = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    @Volatile private var session: BackgroundPhoneSession? = null
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        fun label(text: String) { layout.addView(TextView(this).apply { this.text = text }) }
        fun input(hint: String) = EditText(this).apply { this.hint = hint; layout.addView(this) }
        fun button(text: String, action: () -> Unit) {
            layout.addView(Button(this).apply { this.text = text; setOnClickListener { action() } })
        }
        label("实验后台屏幕（仅调试构建）")
        label("先通过已授权 ADB 启动 helper。每项操作都需点击按钮；默认不用于聊天；可另行点击按钮为随后任务选择此会话。截图和读屏留在本机。返回、主屏及最近任务按键不支持。未选给 Agent 的会话在关闭页面时停止；已选会话由停止按钮或到期结束。")
        val packageInput = input("要启动的 App 包名，例如 com.android.settings")
        val targetInput = input("要点击的控件文字（危险控件仍会拒绝）")
        button("显式启用并创建后台屏幕") {
            runOperation {
                check(session == null) { "已有会话，请先停止" }
                val connected = BackgroundPhoneSession.connect(this, userEnabled = true)
                try {
                    connected.create(720, 1280, 320)
                    check(!destroyed.get())
                    session = connected
                    "后台屏幕已创建；点击启动 App 后再观察。"
                } catch (error: Exception) { connected.close(); throw error }
            }
        }
        button("启动所填 App") {
            val name = packageInput.text.toString().trim()
            runOperation {
                val state = checkNotNull(session) { "请先启用" }.launch(name)
                "启动请求已提交；显示屏 ${state.displayId}。请点击观察核对结果。"
            }
        }
        button("观察后台屏幕") {
            runOperation {
                checkNotNull(session) { "请先启用" }.withControl {
                    when (val result = AndroidCurrentScreenControlProvider().observeCurrentScreen()) {
                        is ScreenStateReadResult.Available -> "${result.snapshot.packageName}\n${result.snapshot.textSummary}"
                        is ScreenStateReadResult.Failed -> "后台屏幕不可读取：${result.failureKind.name}"
                        else -> "后台屏幕不可读取：${result.javaClass.simpleName}"
                    }
                }
            }
        }
        button("点击所填控件（经过现有危险控件守卫）") {
            val target = targetInput.text.toString().trim()
            runOperation {
                require(target.isNotBlank()) { "请输入控件文字" }
                checkNotNull(session) { "请先启用" }.withControl {
                    when (val result = AndroidCurrentScreenControlProvider().tap(target)) {
                        is UiActionReadResult.Available -> result.result.summary
                        is UiActionReadResult.Failed -> "操作未完成：${result.failureKind.name}"
                        else -> "操作未完成：${result.javaClass.simpleName}"
                    }
                }
            }
        }
        button("为随后发起的 Agent 任务选用此后台屏幕") {
            try {
                BackgroundPhoneSession.selectForAgent(checkNotNull(session))
                output.text = "已显式选择：随后发起的 Agent 任务使用此后台会话。已有任务不切换；会话失效不会回退主屏。"
            } catch (_: Exception) { output.text = "请先创建有效后台会话" }
        }
        button("停止并销毁后台屏幕") {
            // Close on a separate thread so a queued read cannot prevent the stop request.
            val current = session ?: BackgroundPhoneSession.selectedForAgent()
            session = null
            BackgroundPhoneSession.selectForAgent(null) // New runs use main; existing runs retain closed binding.
            Thread({ current?.close() }, "SolinBackgroundStop").start()
            output.text = "已请求停止；会话不会自动恢复。"
        }
        button("验证后台截图（仅显示 PNG 字节数）") {
            runOperation {
                val bytes = checkNotNull(session) { "请先启用" }.capture().size
                "后台截图成功：PNG $bytes 字节；未保存或展示图像。"
            }
        }
        output = TextView(this).apply { text = "未启用"; setTextIsSelectable(true) }
        layout.addView(output)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun runOperation(block: () -> String) {
        if (!busy.compareAndSet(false, true)) { output.text = "上一操作尚未结束"; return }
        output.text = "执行中…"
        worker.execute {
            val text = try { block() } catch (error: Exception) {
                session?.close(); session = null
                val code = (error as? BackgroundPhoneFailure)?.code ?: "operation_refused"
                "操作被拒绝（$code）；不会改为操作主屏。请检查无障碍连接、屏幕锁定和 helper 状态。"
            } finally { busy.set(false) }
            runOnUiThread { if (!destroyed.get()) output.text = text }
        }
    }

    override fun onDestroy() {
        destroyed.set(true)
        val current = session
        session = null
        if (current !== BackgroundPhoneSession.selectedForAgent()) {
            Thread({ current?.close() }, "SolinBackgroundClose").start()
        }
        worker.shutdownNow()
        super.onDestroy()
    }
}
