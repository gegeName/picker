package com.chat.picker.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.Window
import android.widget.TextView
import com.chat.picker.R
import androidx.core.graphics.drawable.toDrawable

internal class LoadingDialog(context: Context) : Dialog(context) {
    private var cancelOnBack: Boolean = false
    private var onBackCancel: (() -> Unit)? = null

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.picker_dialog_loading)
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (cancelOnBack) onBackCancel?.invoke()
                true
            } else {
                false
            }
        }
    }

    fun setText(text: String) {
        findViewById<TextView>(R.id.loading_text)?.text = text
    }

    fun setBackCancelEnabled(enable: Boolean, onCancel: (() -> Unit)? = null) {
        cancelOnBack = enable
        onBackCancel = onCancel
    }
}
