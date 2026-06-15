package com.bytedance.zgx.pocketmind.debug

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class DeviceControlEvalActivity : Activity() {
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "PocketMind Device Control Eval"

        statusView = TextView(this).apply {
            text = "Eval status idle"
            textSize = 18f
            contentDescription = "Eval status"
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
            text = "PocketMind Device Control Eval"
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
        root.addView(Button(this).apply {
            text = "Open Eval Panel"
            contentDescription = "OpenEvalPanel"
            setOnClickListener { showEvalPanel() }
        })
        root.addView(evalScrollContainer())

        setContentView(root)
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
}
