package com.bytedance.zgx.pocketmind.debug

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class DeviceControlEvalActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var searchInput: EditText
    private lateinit var searchResultView: TextView
    private lateinit var filterButton: Button
    private lateinit var profile: EvalSearchProfile

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profile = EvalSearchProfile.fromIntentValue(intent.getStringExtra(EXTRA_PROFILE))
        title = "PocketMind Device Control Eval"

        statusView = TextView(this).apply {
            text = "Eval status idle"
            textSize = 18f
            contentDescription = "Eval status"
        }
        searchResultView = TextView(this).apply {
            text = "Eval search result idle"
            textSize = 18f
            contentDescription = "Eval search result"
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            contentDescription = "Device control eval root"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        root.addView(TextView(this).apply {
            text = "PocketMind Device Control Eval - ${profile.title}"
            textSize = 22f
        })
        root.addView(statusView)
        root.addView(Button(this).apply {
            text = "Eval Tap Target"
            contentDescription = "EvalTapTarget"
            setOnClickListener { statusView.text = "Tap success" }
        })
        root.addView(EditText(this).apply {
            hint = "Eval input field"
            contentDescription = "EvalInputField"
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        })
        searchInput = EditText(this).apply {
            hint = profile.searchHint
            contentDescription = profile.inputDescription
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    submitEvalSearch()
                    true
                } else {
                    false
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        root.addView(searchInput)
        root.addView(Button(this).apply {
            text = profile.submitText
            contentDescription = profile.submitDescription
            setOnClickListener { submitEvalSearch() }
        })
        root.addView(searchResultView)
        filterButton = Button(this).apply {
            text = "筛选"
            contentDescription = "筛选 FilterEntry"
            isEnabled = profile.supportsFilter
            setOnClickListener { statusView.text = "Filter applied" }
        }
        root.addView(filterButton)
        root.addView(Button(this).apply {
            text = "Open Eval Panel"
            contentDescription = "OpenEvalPanel"
            setOnClickListener { showEvalPanel() }
        })
        root.addView(evalScrollContainer())

        setContentView(root)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    private fun submitEvalSearch() {
        val query = searchInput.text?.toString().orEmpty()
        searchResultView.text = profile.resultText(query)
    }

    private fun evalScrollContainer(): ScrollView =
        ScrollView(this).apply {
            contentDescription = "EvalScrollContainer"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                360,
            )
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                repeat(40) { index ->
                    val itemNumber = index + 1
                    addView(TextView(context).apply {
                        text = if (itemNumber == 35) {
                            "Scroll target item 35"
                        } else {
                            "Eval list item ${itemNumber.toString().padStart(2, '0')}"
                        }
                        textSize = 18f
                        setPadding(0, 18, 0, 18)
                    })
                }
            })
        }

    private fun showEvalPanel() {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(TextView(context).apply {
                text = "Eval panel open"
                textSize = 20f
            })
            addView(Button(context).apply {
                text = "Dismiss Eval Panel"
                contentDescription = "DismissEvalPanel"
                setOnClickListener { dialog.dismiss() }
            })
        }
        dialog.setContentView(panel)
        dialog.show()
    }

    private data class EvalSearchProfile(
        val key: String,
        val title: String,
        val searchHint: String,
        val inputDescription: String,
        val submitText: String,
        val submitDescription: String,
        val resultHints: List<String>,
        val supportsFilter: Boolean,
    ) {
        fun resultText(query: String): String =
            buildString {
                append("Eval search result: ")
                append(title)
                append(' ')
                append(query)
                if (resultHints.isNotEmpty()) {
                    append(' ')
                    append(resultHints.joinToString(" "))
                }
            }

        companion object {
            fun fromIntentValue(value: String?): EvalSearchProfile =
                when (value?.trim()?.lowercase()) {
                    "taobao", "淘宝" -> EvalSearchProfile(
                        key = "taobao",
                        title = "淘宝",
                        searchHint = "搜索商品",
                        inputDescription = "淘宝搜索商品 EvalSearchInput",
                        submitText = "搜索",
                        submitDescription = "淘宝搜索 EvalSearchSubmit",
                        resultHints = listOf("综合", "销量", "筛选", "商品列表"),
                        supportsFilter = true,
                    )

                    "pdd", "pinduoduo", "拼多多" -> EvalSearchProfile(
                        key = "pdd",
                        title = "拼多多",
                        searchHint = "多多搜索 搜索商品",
                        inputDescription = "拼多多搜索商品 EvalSearchInput",
                        submitText = "搜索",
                        submitDescription = "拼多多搜索 EvalSearchSubmit",
                        resultHints = listOf("综合", "销量", "筛选", "百亿补贴"),
                        supportsFilter = true,
                    )

                    "gaode", "amap", "高德", "高德地图" -> EvalSearchProfile(
                        key = "gaode",
                        title = "高德地图",
                        searchHint = "搜索地点 目的地",
                        inputDescription = "高德地图搜索地点 EvalSearchInput",
                        submitText = "搜索",
                        submitDescription = "高德地图搜索 EvalSearchSubmit",
                        resultHints = listOf("路线", "导航", "到这去", "地点列表"),
                        supportsFilter = false,
                    )

                    "browser", "chrome", "浏览器" -> EvalSearchProfile(
                        key = "browser",
                        title = "浏览器",
                        searchHint = "搜索或输入网址 地址栏",
                        inputDescription = "浏览器地址栏 EvalSearchInput",
                        submitText = "前往",
                        submitDescription = "浏览器前往 EvalSearchSubmit",
                        resultHints = listOf("搜索结果", "网页", "相关搜索"),
                        supportsFilter = false,
                    )

                    else -> EvalSearchProfile(
                        key = "generic",
                        title = "通用",
                        searchHint = "搜索商品",
                        inputDescription = "EvalSearchInput",
                        submitText = "搜索",
                        submitDescription = "EvalSearchSubmit",
                        resultHints = listOf("综合", "销量", "筛选"),
                        supportsFilter = true,
                    )
                }
        }
    }

    private companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
