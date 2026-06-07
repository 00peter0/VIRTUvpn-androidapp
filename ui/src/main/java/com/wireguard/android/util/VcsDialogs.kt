/*
 * Copyright © 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.wireguard.android.R
import com.wireguard.android.databinding.VcsMessageDialogBinding

object VcsDialogs {
    data class Action(
        val text: CharSequence,
        val primary: Boolean = false,
        val dismissAfterClick: Boolean = true,
        val onClick: (() -> Unit)? = null
    )

    fun action(context: Context, @StringRes textRes: Int, primary: Boolean = false, onClick: (() -> Unit)? = null) =
        Action(context.getString(textRes), primary, onClick = onClick)

    fun action(text: CharSequence, primary: Boolean = false, onClick: (() -> Unit)? = null) =
        Action(text, primary, onClick = onClick)

    fun show(
        context: Context,
        title: CharSequence? = null,
        message: CharSequence? = null,
        customView: View? = null,
        positive: Action? = null,
        negative: Action? = null,
        neutral: Action? = null,
        cancelable: Boolean = true,
        softInputMode: Int? = null,
        onDismiss: (() -> Unit)? = null
    ): AlertDialog {
        val binding = VcsMessageDialogBinding.inflate(android.view.LayoutInflater.from(context))
        binding.dialogTitle.text = title ?: ""
        binding.dialogTitle.isVisible = !title.isNullOrBlank()
        binding.dialogMessage.text = message ?: ""
        binding.dialogMessage.isVisible = !message.isNullOrBlank()
        binding.dialogCustomContent.isVisible = customView != null
        customView?.let { binding.dialogCustomContent.addView(it) }

        lateinit var dialog: AlertDialog
        val actions = listOfNotNull(negative, neutral, positive)
        binding.dialogActions.isVisible = actions.isNotEmpty()
        binding.dialogActions.orientation = if (actions.size > 2) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        actions.forEachIndexed { index, action ->
            binding.dialogActions.addView(createActionButton(context, action, actions.size, index) {
                action.onClick?.invoke()
                if (action.dismissAfterClick) dialog.dismiss()
            })
        }

        dialog = AlertDialog.Builder(context)
            .setView(binding.root)
            .create()
        dialog.setCancelable(cancelable)
        if (onDismiss != null) dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        applyWindowStyle(dialog, softInputMode)
        return dialog
    }

    fun applyDefaultStyle(dialog: AlertDialog) {
        applyWindowStyle(dialog)
        stylePlatformButtons(dialog)
    }

    private fun createActionButton(
        context: Context,
        action: Action,
        actionCount: Int,
        index: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            minHeight = dp(context, 46)
            gravity = android.view.Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            maxLines = 2
            text = action.text
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (action.primary) Color.parseColor("#02110F") else Color.parseColor("#E5F2F7"))
            background = ContextCompat.getDrawable(
                context,
                if (action.primary) R.drawable.vcs_dialog_primary_button else R.drawable.vcs_dialog_secondary_button
            )
            foreground = android.util.TypedValue().let { outValue ->
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                ContextCompat.getDrawable(context, outValue.resourceId)
            }
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
            setOnClickListener { onClick() }
            layoutParams = if (actionCount > 2) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 46)).apply {
                    if (index > 0) topMargin = dp(context, 10)
                }
            } else {
                LinearLayout.LayoutParams(0, dp(context, 46), 1f).apply {
                    if (index > 0) marginStart = dp(context, 12)
                }
            }
        }
    }

    private fun applyWindowStyle(dialog: AlertDialog, softInputMode: Int? = null) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            softInputMode?.let { setSoftInputMode(it) }
            setLayout((dialog.context.resources.displayMetrics.widthPixels * 0.9f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun stylePlatformButtons(dialog: AlertDialog) {
        listOf(
            AlertDialog.BUTTON_POSITIVE to true,
            AlertDialog.BUTTON_NEGATIVE to false,
            AlertDialog.BUTTON_NEUTRAL to false
        ).forEach { (which, primary) ->
            dialog.getButton(which)?.apply {
                setTextColor(if (primary) Color.parseColor("#5EEAD4") else Color.parseColor("#E5F2F7"))
                isAllCaps = false
            }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
