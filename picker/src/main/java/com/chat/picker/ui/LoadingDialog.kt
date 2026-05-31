package com.chat.picker.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.widget.TextView
import com.chat.picker.R
import androidx.core.graphics.drawable.toDrawable

internal class LoadingDialog(context: Context) : Dialog(context) {
    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.picker_dialog_loading)
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
    }

    fun setText(text: String) {
        findViewById<TextView>(R.id.loading_text)?.text = text
    }
}
