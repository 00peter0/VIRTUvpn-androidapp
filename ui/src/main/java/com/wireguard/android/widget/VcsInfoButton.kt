package com.wireguard.android.widget

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import com.wireguard.android.R
import com.wireguard.android.util.VcsDialogs

/** Shared, read-only contextual help; never executes the action it describes. */
class VcsInfoButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageButton(context, attrs, defStyleAttr) {
    private var infoTitle: CharSequence = ""
    private var infoMessage: CharSequence = ""

    init {
        val size = (48 * resources.displayMetrics.density).toInt()
        val padding = (12 * resources.displayMetrics.density).toInt()
        minimumWidth = size
        minimumHeight = size
        setPadding(padding, padding, padding, padding)
        setImageResource(R.drawable.ic_info_outline)
        val background = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)) {
            setBackgroundResource(background.resourceId)
        }
        val values = context.obtainStyledAttributes(attrs, R.styleable.VcsInfoButton, defStyleAttr, 0)
        try {
            setInfo(
                values.getText(R.styleable.VcsInfoButton_infoTitle) ?: "",
                values.getText(R.styleable.VcsInfoButton_infoMessage) ?: ""
            )
        } finally {
            values.recycle()
        }
        setOnClickListener {
            if (infoMessage.isNotBlank()) {
                VcsDialogs.show(
                    context = context,
                    title = infoTitle,
                    message = infoMessage,
                    positive = VcsDialogs.action(context, android.R.string.ok, primary = true)
                )
            }
        }
    }

    fun setInfo(title: CharSequence, message: CharSequence) {
        infoTitle = title
        infoMessage = message
        contentDescription = context.getString(R.string.vcs_information_about, title)
        TooltipCompat.setTooltipText(this, title)
        isEnabled = message.isNotBlank()
    }
}
