package com.chat.picker.ui

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.chat.picker.R

internal class CropTextInputDialog(
    private val activity: AppCompatActivity,
) {
    private var dialog: AlertDialog? = null

    fun show(
        initialText: String = "",
        sourceRect: RectF? = null,
        color: Int,
        textIndex: Int = -1,
        onCommit: (text: String, sourceRect: RectF?, textIndex: Int) -> Unit,
    ) {
        dismiss()
        val content = activity.layoutInflater.inflate(R.layout.picker_dialog_text_input, null)
        val input = content.findViewById<EditText>(R.id.picker_text_dialog_input).apply {
            setText(initialText)
            setTextColor(color)
            setSelection(text?.length ?: 0)
        }
        var committed = false
        val nextDialog = AlertDialog.Builder(activity, R.style.PickerTextDialogTheme)
            .setView(content)
            .create()
        nextDialog.setCanceledOnTouchOutside(false)
        content.findViewById<TextView>(R.id.picker_text_dialog_close).setOnClickListener {
            nextDialog.dismiss()
        }
        content.findViewById<TextView>(R.id.picker_text_dialog_done).setOnClickListener {
            nextDialog.dismiss()
        }
        nextDialog.setOnShowListener {
            nextDialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                attributes = attributes.apply {
                    windowAnimations = R.style.PickerTextDialogAnimation
                }
                setDimAmount(0.55f)
                setLayout(
                    (activity.resources.displayMetrics.widthPixels * 0.86f).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            input.requestFocus()
            showKeyboard(input)
        }
        nextDialog.setOnDismissListener {
            if (!committed) {
                committed = true
                onCommit(input.text?.toString().orEmpty(), sourceRect, textIndex)
            }
            if (dialog === nextDialog) dialog = null
        }
        dialog = nextDialog
        nextDialog.show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun showKeyboard(view: View) {
        view.post {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
