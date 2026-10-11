package com.wireguard.android.activity

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.wireguard.android.BuildConfig
import com.wireguard.android.R
import com.wireguard.android.databinding.DeviceDiagnosticsActivityBinding
import com.wireguard.android.vcs.DeviceDiagnosticMath
import com.wireguard.android.vcs.DeviceDiagnosticsViewModel
import com.wireguard.android.vcs.ManagedDiagnostics
import com.wireguard.android.vcs.StartupAppStatus
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Branded, read-only device overview. JSON is available only through explicit sharing. */
class DeviceDiagnosticsActivity : AppCompatActivity() {
    private lateinit var binding: DeviceDiagnosticsActivityBinding
    private lateinit var model: DeviceDiagnosticsViewModel
    private var awaitingUsageAccess = false
    private val expandedSections = mutableSetOf<Int>()
    private val sectionViews = mutableMapOf<Int, DiagnosticSection>()
    private var sectionPosition = 0

    private class DiagnosticSection(
        val container: LinearLayout,
        val heading: LinearLayout,
        val content: LinearLayout,
        val chevron: ImageView,
        val busy: ProgressBar
    )
    private val settings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (awaitingUsageAccess) {
            awaitingUsageAccess = false
            if (ManagedDiagnostics.hasUsageAccess(this)) model.scanApps()
            else Toast.makeText(this, R.string.vcs_diag_settings_retry, Toast.LENGTH_LONG).show()
        }
        render(model.state.value)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark("#071018".toColorInt()),
            navigationBarStyle = SystemBarStyle.dark("#071018".toColorInt()))
        super.onCreate(savedInstanceState)
        awaitingUsageAccess = savedInstanceState?.getBoolean("awaiting_usage_access") ?: false
        savedInstanceState?.getIntArray(EXPANDED_SECTIONS_KEY)?.let { expandedSections.addAll(it.toList()) }
        binding = DeviceDiagnosticsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.diagnosticsToolbar.setNavigationOnClickListener { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        model = ViewModelProvider(this)[DeviceDiagnosticsViewModel::class.java]
        binding.diagnosticsRefresh.setOnClickListener { model.refresh() }
        binding.diagnosticsShare.setOnClickListener {
            val state = model.state.value
            state.report?.let { report ->
                val export = ManagedDiagnostics.export(report, state.apps?.storage.takeIf { ManagedDiagnostics.hasUsageAccess(this) }, state.startup).toString(2)
                open(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, export), getString(R.string.vcs_diag_share_report)))
            }
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { render(it) } } }
    }
    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) {
            if (!ManagedDiagnostics.hasUsageAccess(this)) model.revokeUsageObservation()
            render(model.state.value)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("awaiting_usage_access", awaitingUsageAccess)
        outState.putIntArray(EXPANDED_SECTIONS_KEY, expandedSections.toIntArray())
        super.onSaveInstanceState(outState)
    }

    private fun render(state: DeviceDiagnosticsViewModel.State) {
        binding.diagnosticsRefresh.isEnabled = !state.refreshing
        binding.diagnosticsShare.isEnabled = state.report != null && !state.busy
        binding.diagnosticsLoading.visibility = if (state.refreshing) View.VISIBLE else View.GONE
        binding.diagnosticsStatus.text = when {
            state.error -> getString(R.string.vcs_diag_failed)
            state.refreshing -> getString(R.string.vcs_diag_collecting)
            state.report != null -> getString(R.string.vcs_diag_observed, time(state.report.collectedAt), BuildConfig.VERSION_NAME)
            else -> getString(R.string.vcs_diag_collecting)
        }
        // Keep the section headers stable while measurements and progress update.
        sectionPosition = 0
        state.report?.let { report ->
            val r = report.resources; val s = report.security
            card(R.string.vcs_diag_resources, R.drawable.ic_bolt) {
                row("Logical CPUs available to VirtuVPN", r.logicalCpus.toString())
                row("VirtuVPN CPU · one-core basis", r.appCpuOneCorePercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: unavailable())
                row("CPU sample", "${r.cpuSampleMillis} ms")
                note(R.string.vcs_diag_cpu_limit)
                row("Available RAM / total", "${bytes(r.memoryAvailable)} / ${bytes(r.memoryTotal)}")
                gauge("Occupied RAM", DeviceDiagnosticMath.usedPercent(r.memoryTotal, r.memoryAvailable), false)
                row("Android memory pressure", when (r.memoryLow) { true -> "Low memory reported"; false -> "Not reported"; null -> unavailable() }, if (r.memoryLow == true) AMBER else TEXT)
                row("VirtuVPN process RAM · PSS", bytes(r.appMemoryPss))
                note(R.string.vcs_diag_memory_limit)
            }
            card(R.string.vcs_diag_storage, R.drawable.ic_device) {
                row("Free internal storage / total", "${bytes(r.storageAvailable)} / ${bytes(r.storageTotal)}")
                val storagePercent = DeviceDiagnosticMath.usedPercent(r.storageTotal, r.storageAvailable)
                gauge("Used internal storage", storagePercent, (storagePercent ?: 0) >= 90 || (r.storageAvailable != null && r.storageAvailable < 1_073_741_824))
                row("VirtuVPN app + data", bytes(r.ownStorage?.totalBytes))
                row("VirtuVPN cache · included in data", bytes(r.ownStorage?.cacheBytes))
                note(R.string.vcs_diag_storage_limit)
                action(R.string.vcs_diag_storage_settings) { open(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
            }
            card(R.string.vcs_diag_battery, R.drawable.ic_power) {
                row("Battery level", r.batteryPercent?.let { "$it%" } ?: unavailable())
                row("Battery temperature", r.batteryCelsius?.let { String.format(Locale.US, "%.1f °C", it) } ?: unavailable(), if ((r.batteryCelsius ?: 0.0) >= 45) AMBER else TEXT)
                row("Android thermal state", thermal(r.thermalStatus), if ((r.thermalStatus ?: 0) >= 3) AMBER else TEXT)
            }
            card(R.string.vcs_diag_security, R.drawable.ic_shield) {
                row("Android version", s.androidVersion)
                row("Security patch", s.securityPatch.ifBlank { unavailable() }, if ((s.patchAgeDays ?: 0) >= 180) AMBER else TEXT)
                row("Patch age · device clock", s.patchAgeDays?.let { "$it days" } ?: unavailable())
                if ((s.patchAgeDays ?: 0) >= 180) note("The reported patch is over six months old. Check for an Android system update; patch age alone is not a vulnerability scan.")
                row("Secure screen lock", switch(s.screenLock), if (s.screenLock == false) AMBER else TEXT)
                row("USB debugging", switch(s.usbDebugging), if (s.usbDebugging == true) AMBER else TEXT)
                if (s.usbDebugging == true) note("USB debugging is enabled. Use trusted computers and review Android debugging authorizations.")
                row("Developer options", switch(s.developerOptions))
                note("These are observable settings, not a malware verdict. Root status, bootloader integrity and all installed apps are not verified.")
                action(R.string.vcs_diag_security_settings) { open(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            }
        }
        card(R.string.vcs_diag_apps, R.drawable.ic_device, busy = state.appProgress != null) {
            row("Other apps · live CPU / RAM", getString(R.string.vcs_diag_android_restricted))
            note(R.string.vcs_diag_app_resources_limit)
            action(R.string.vcs_diag_application_settings) { open(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)) }
            if (Build.VERSION.SDK_INT < 26) {
                note(R.string.vcs_diag_optional_older)
                action(R.string.vcs_diag_storage_settings) { open(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
            } else {
                note(R.string.vcs_diag_usage_intro)
                val access = ManagedDiagnostics.hasUsageAccess(this@DeviceDiagnosticsActivity)
                state.appProgress?.let { progress(it) }
                if (state.appError) note(R.string.vcs_diag_optional_failed)
                if (access) state.apps?.let { sample ->
                    val storage = sample.storage
                    note(getString(R.string.vcs_diag_app_scope, storage.measured, storage.visible,
                        getString(if (storage.complete) R.string.vcs_diag_sample_complete else R.string.vcs_diag_partial)))
                    note("Observed ${time(storage.collectedAt)}")
                    row("Measured app + data storage", bytes(storage.measuredTotalBytes))
                    note(R.string.vcs_diag_storage_share)
                    storage.apps.forEachIndexed { i, app ->
                        row("${i + 1}. ${app.label}", "${bytes(app.totalBytes)} · ${share(app.totalBytes, storage.measuredTotalBytes)}")
                        note("App ${bytes(app.appBytes)} · Data ${bytes(app.dataBytes)} · Cache ${bytes(app.cacheBytes)} included")
                    }
                    note(R.string.vcs_diag_apps_limit)
                    note(R.string.vcs_diag_usage_heading)
                    sample.usage?.let { usage ->
                        note("Observed ${time(usage.collectedAt)}")
                        note(getString(R.string.vcs_diag_usage_period, time(usage.recordBegin), time(usage.recordEnd)))
                        row("Apps with foreground records", usage.usedApps.toString())
                        note(R.string.vcs_diag_usage_share)
                        usage.apps.forEachIndexed { i, app ->
                            row("${i + 1}. ${app.label}", "${duration(app.foregroundMillis)} · ${share(app.foregroundMillis, usage.totalForegroundMillis)}")
                        }
                    } ?: note(R.string.vcs_diag_usage_missing)
                }
                action(if (access) R.string.vcs_diag_scan_apps else R.string.vcs_diag_usage_access, state.appProgress == null) {
                    if (ManagedDiagnostics.hasUsageAccess(this@DeviceDiagnosticsActivity)) model.scanApps()
                    else {
                        awaitingUsageAccess = true
                        try { settings.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:$packageName".toUri())) }
                        catch (_: Exception) { awaitingUsageAccess = false; unavailableAction() }
                    }
                }
                if (access) action(R.string.vcs_diag_usage_manage) { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:$packageName".toUri())) }
            }
        }
        card(R.string.vcs_diag_startup, R.drawable.ic_power, busy = state.startupProgress != null) {
            note(R.string.vcs_diag_startup_intro)
            note(R.string.vcs_diag_startup_limit)
            action(R.string.vcs_diag_scan_startup, state.startupProgress == null) { model.scanStartup() }
            state.startupProgress?.let { progress(it) }
            if (state.startupError) note(R.string.vcs_diag_optional_failed)
            state.startup?.let { sample ->
                note("Observed ${time(sample.collectedAt)}")
                row("Visible apps checked", "${sample.checked} / ${sample.visibleApps}")
                row("Startup entries shown / identified", "${sample.apps.size} / ${sample.identified}")
                row("Evidence", getString(if (sample.complete) R.string.vcs_diag_startup_complete else R.string.vcs_diag_partial))
                if (sample.apps.isEmpty()) note(R.string.vcs_diag_startup_empty)
                sample.apps.forEach { app ->
                    row(app.label, getString(when (app.status) {
                        StartupAppStatus.ENABLED_RECEIVER -> R.string.vcs_diag_startup_enabled
                        StartupAppStatus.DISABLED -> R.string.vcs_diag_startup_disabled
                        StartupAppStatus.STOPPED -> R.string.vcs_diag_startup_stopped
                        StartupAppStatus.PERMISSION_ONLY -> R.string.vcs_diag_startup_permission_only
                        StartupAppStatus.PERMISSION_NOT_GRANTED -> R.string.vcs_diag_startup_permission_missing
                        StartupAppStatus.UNVERIFIED -> R.string.vcs_diag_unavailable
                    }))
                    val kind = when (app.systemApp) { true -> "System app"; false -> "Installed app"; null -> unavailable() }
                    val phases = listOfNotNull("After unlock".takeIf { app.afterUnlock }, "Before unlock".takeIf { app.beforeUnlock })
                    note(if (phases.isEmpty()) kind else "$kind · ${phases.joinToString(" / ")}")
                    action(getString(R.string.vcs_diag_review_app, app.label)) {
                        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${app.packageName}".toUri()))
                    }
                }
            }
        }
    }
    private fun card(title: Int, icon: Int, busy: Boolean = false, content: LinearLayout.() -> Unit) {
        val section = sectionViews.getOrPut(title) { createSection(title, icon) }
        val position = sectionPosition++
        if (binding.diagnosticsSections.indexOfChild(section.container) != position) {
            binding.diagnosticsSections.removeView(section.container)
            binding.diagnosticsSections.addView(section.container, position)
        }
        section.content.removeAllViews()
        section.content.content()
        section.busy.visibility = if (busy) View.VISIBLE else View.GONE
        updateSection(title, section)
    }

    private fun createSection(title: Int, icon: Int): DiagnosticSection {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundResource(R.drawable.stat_card_background)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            contentDescription = getString(title)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            val ripple = TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true) && ripple.resourceId != 0) {
                foreground = ContextCompat.getDrawable(this@DeviceDiagnosticsActivity, ripple.resourceId)
            }
        }
        heading.addView(ImageView(this).apply {
            setImageResource(icon)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            imageTintList = ColorStateList.valueOf(ACCENT)
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
        heading.addView(text(getString(title), 18f, TEXT, true).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val busy = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
        heading.addView(busy, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(10) })
        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = ColorStateList.valueOf(ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        heading.addView(chevron, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(12) })
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(heading, LinearLayout.LayoutParams(-1, -2))
        container.addView(content, LinearLayout.LayoutParams(-1, -2))
        val section = DiagnosticSection(container, heading, content, chevron, busy)
        heading.setOnClickListener {
            if (!expandedSections.add(title)) expandedSections.remove(title)
            updateSection(title, section)
        }
        ViewCompat.setAccessibilityHeading(heading, true)
        ViewCompat.setAccessibilityDelegate(heading, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
                info.addAction(if (title in expandedSections) {
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_COLLAPSE
                } else {
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_EXPAND
                })
            }
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                val expanded = title in expandedSections
                if ((!expanded && action == AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_EXPAND.id) ||
                    (expanded && action == AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_COLLAPSE.id)) {
                    return host.performClick()
                }
                return super.performAccessibilityAction(host, action, args)
            }
        })
        return section
    }

    private fun updateSection(title: Int, section: DiagnosticSection) {
        val expanded = title in expandedSections
        section.content.visibility = if (expanded) View.VISIBLE else View.GONE
        section.chevron.rotation = when {
            expanded -> 90f
            resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL -> 180f
            else -> 0f
        }
        val state = getString(if (expanded) R.string.vcs_diag_section_expanded else R.string.vcs_diag_section_collapsed)
        ViewCompat.setStateDescription(section.heading, if (section.busy.isVisible) {
            getString(R.string.vcs_diag_section_busy, state)
        } else state)
    }
    private fun LinearLayout.row(label: String, value: String, color: Int = TEXT) {
        val row = LinearLayout(this@DeviceDiagnosticsActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; setPadding(0, dp(12), 0, dp(2)) }
        row.addView(text(label, 13f, MUTED), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        row.addView(text(value, 14f, color, true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(row)
    }
    private fun LinearLayout.note(resource: Int) = note(getString(resource))
    private fun LinearLayout.note(value: String) { addView(text(value, 13f, MUTED).apply { setPadding(0, dp(10), 0, 0); setLineSpacing(dp(3).toFloat(), 1f) }) }
    private fun LinearLayout.gauge(label: String, percent: Int?, warning: Boolean) {
        row(label, percent?.let { "$it%" } ?: unavailable(), if (warning) AMBER else TEXT)
        if (percent != null) addView(ProgressBar(this@DeviceDiagnosticsActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = percent; progressTintList = ColorStateList.valueOf(if (warning) AMBER else ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf("#243944".toColorInt())
        }, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(4); bottomMargin = dp(4) })
    }
    private fun LinearLayout.progress(value: Pair<Int, Int>) {
        note(getString(R.string.vcs_diag_progress, value.first, value.second))
        addView(ProgressBar(this@DeviceDiagnosticsActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = value.second <= 0; max = value.second.coerceAtLeast(1); progress = value.first
            progressTintList = ColorStateList.valueOf(ACCENT); indeterminateTintList = ColorStateList.valueOf(ACCENT)
        }, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(8) })
    }
    private fun LinearLayout.action(resource: Int, enabled: Boolean = true, callback: () -> Unit) = action(getString(resource), enabled, callback)
    private fun LinearLayout.action(label: String, enabled: Boolean = true, callback: () -> Unit) {
        addView(MaterialButton(this@DeviceDiagnosticsActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; isEnabled = enabled; setTextColor(ACCENT); strokeColor = ColorStateList.valueOf(ACCENT); strokeWidth = dp(1); cornerRadius = dp(12)
            backgroundTintList = ColorStateList.valueOf("#102330".toColorInt()); minHeight = dp(48)
            setOnClickListener { callback() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun open(intent: Intent) { try { startActivity(intent) } catch (_: Exception) { unavailableAction() } }
    private fun unavailableAction() { Toast.makeText(this, R.string.vcs_diag_action_unavailable, Toast.LENGTH_LONG).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun unavailable() = getString(R.string.vcs_diag_unavailable)
    private fun switch(value: Boolean?) = when (value) { true -> "Enabled"; false -> "Disabled"; null -> unavailable() }
    private fun time(value: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
    private fun share(amount: Long, total: Long) = DeviceDiagnosticMath.sharePercent(amount, total)?.let { String.format(Locale.US, "%.1f%%", it) } ?: unavailable()
    private fun duration(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${seconds / 60} min"
            else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
        }
    }
    private fun bytes(value: Long?): String {
        if (value == null || value < 0) return unavailable()
        val gib = 1_073_741_824.0
        return if (value >= gib) String.format(Locale.US, "%.1f GiB", value / gib) else String.format(Locale.US, "%.1f MiB", value / 1_048_576.0)
    }
    private fun thermal(value: Int?): String = when (value) {
        0 -> "No throttling reported"; 1 -> "Light"; 2 -> "Moderate"; 3 -> "Severe"; 4 -> "Critical"; 5 -> "Emergency"; 6 -> "Shutdown"; else -> unavailable()
    }
    companion object {
        private const val EXPANDED_SECTIONS_KEY = "expanded_diagnostic_sections"
        private val TEXT = "#FFFFFF".toColorInt(); private val MUTED = "#AFC0CC".toColorInt()
        private val ACCENT = "#5EEAD4".toColorInt(); private val AMBER = "#FFB74D".toColorInt()
    }
}
