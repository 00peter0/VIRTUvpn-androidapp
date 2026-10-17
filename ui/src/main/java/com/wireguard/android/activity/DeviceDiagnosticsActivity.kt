package com.wireguard.android.activity

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
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
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.wireguard.android.BuildConfig
import com.wireguard.android.R
import com.wireguard.android.databinding.DeviceDiagnosticsActivityBinding
import com.wireguard.android.vcs.DeviceDiagnosticMath
import com.wireguard.android.vcs.DeviceDiagnosticsViewModel
import com.wireguard.android.vcs.ManagedDiagnostics
import com.wireguard.android.vcs.StartupAppStatus
import com.wireguard.android.vcs.StartupAppFilter
import com.wireguard.android.vcs.StartupAppList
import com.wireguard.android.vcs.StartupAppPage
import com.wireguard.android.vcs.StartupAppReport
import com.wireguard.android.widget.VcsInfoButton
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
    private var startupPanel: StartupPanel? = null

    private class StartupPanel(
        val check: MaterialButton, val progress: LinearLayout, val observations: LinearLayout,
        val controls: LinearLayout, val search: TextInputEditText,
        val navigation: LinearLayout, val entries: LinearLayout
    )

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
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            view.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, keyboard))
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
                row("Available RAM / total", "${bytes(r.memoryAvailable)} / ${bytes(r.memoryTotal)}")
                gauge("Occupied RAM", DeviceDiagnosticMath.usedPercent(r.memoryTotal, r.memoryAvailable), false)
                row("Android memory pressure", when (r.memoryLow) { true -> "Low memory reported"; false -> "Not reported"; null -> unavailable() }, if (r.memoryLow == true) AMBER else TEXT)
                row("VirtuVPN process RAM · PSS", bytes(r.appMemoryPss))
            }
            card(R.string.vcs_diag_storage, R.drawable.ic_device) {
                row("Free internal storage / total", "${bytes(r.storageAvailable)} / ${bytes(r.storageTotal)}")
                val storagePercent = DeviceDiagnosticMath.usedPercent(r.storageTotal, r.storageAvailable)
                gauge("Used internal storage", storagePercent, (storagePercent ?: 0) >= 90 || (r.storageAvailable != null && r.storageAvailable < 1_073_741_824))
                row("VirtuVPN app + data", bytes(r.ownStorage?.totalBytes))
                row("VirtuVPN cache · included in data", bytes(r.ownStorage?.cacheBytes))
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
            action(R.string.vcs_diag_application_settings) { open(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)) }
            if (Build.VERSION.SDK_INT < 26) {
                note(R.string.vcs_diag_optional_older)
                action(R.string.vcs_diag_storage_settings) { open(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
            } else {
                val access = ManagedDiagnostics.hasUsageAccess(this@DeviceDiagnosticsActivity)
                if (!access) note(R.string.vcs_diag_usage_intro)
                state.appProgress?.let { progress(it) }
                if (state.appError) note(R.string.vcs_diag_optional_failed)
                if (access) state.apps?.let { sample ->
                    val storage = sample.storage
                    note(getString(R.string.vcs_diag_app_scope, storage.measured, storage.visible,
                        getString(if (storage.complete) R.string.vcs_diag_sample_complete else R.string.vcs_diag_partial)))
                    note("Observed ${time(storage.collectedAt)}")
                    row("Measured app + data storage", bytes(storage.measuredTotalBytes))
                    storage.apps.forEachIndexed { i, app ->
                        row("${i + 1}. ${app.label}", "${bytes(app.totalBytes)} · ${share(app.totalBytes, storage.measuredTotalBytes)}")
                        note("App ${bytes(app.appBytes)} · Data ${bytes(app.dataBytes)} · Cache ${bytes(app.cacheBytes)} included")
                    }
                    note(R.string.vcs_diag_usage_heading)
                    sample.usage?.let { usage ->
                        note("Observed ${time(usage.collectedAt)}")
                        note(getString(R.string.vcs_diag_usage_period, time(usage.recordBegin), time(usage.recordEnd)))
                        row("Apps with foreground records", usage.usedApps.toString())
                        usage.apps.forEachIndexed { i, app ->
                            row("${i + 1}. ${app.label}", "${duration(app.foregroundMillis)} · ${share(app.foregroundMillis, usage.totalForegroundMillis)}")
                        }
                    } ?: note(R.string.vcs_diag_usage_missing)
                }
                action(if (access) R.string.vcs_diag_scan_apps else R.string.vcs_diag_usage_access, state.appProgress == null) {
                    if (ManagedDiagnostics.hasUsageAccess(this@DeviceDiagnosticsActivity)) model.scanApps()
                    else {
                        awaitingUsageAccess = true
                        try { settings.launch(PermissionsActivity.intent(this@DeviceDiagnosticsActivity, PermissionsActivity.USAGE)) }
                        catch (_: Exception) { awaitingUsageAccess = false; unavailableAction() }
                    }
                }
                if (access) action(R.string.vcs_diag_usage_manage) { open(PermissionsActivity.intent(this@DeviceDiagnosticsActivity, PermissionsActivity.USAGE, resumeAction = false)) }
            }
        }
        card(R.string.vcs_diag_startup, R.drawable.ic_power, busy = state.startupProgress != null, rebuild = false) {
            val panel = startupPanel ?: createStartupPanel().also { startupPanel = it }
            panel.check.isEnabled = state.startupProgress == null
            panel.progress.removeAllViews()
            state.startupProgress?.let { panel.progress.progress(it) }
            if (state.startupError) panel.progress.note(R.string.vcs_diag_optional_failed)
            panel.observations.removeAllViews()
            state.startup?.let { sample ->
                panel.observations.note("Observed ${time(sample.collectedAt)}")
                panel.observations.row("Visible apps checked", "${sample.checked} / ${sample.visibleApps}")
                panel.observations.row("Startup entries collected / identified", "${sample.apps.size} / ${sample.identified}")
                panel.observations.row("Evidence", getString(if (sample.complete) R.string.vcs_diag_startup_complete else R.string.vcs_diag_partial))
            }
            renderStartupResults(state.startup, panel)
        }
    }

    private fun LinearLayout.createStartupPanel(): StartupPanel {
        note(R.string.vcs_diag_startup_summary)
        val check = action(R.string.vcs_diag_scan_startup) { model.scanStartup() }
        val progress = column()
        val observations = column()
        val controls = column()
        val input = TextInputLayout(this@DeviceDiagnosticsActivity).apply {
            hint = getString(R.string.vcs_diag_startup_search)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = "#102330".toColorInt()
            boxStrokeColor = ACCENT
            defaultHintTextColor = ColorStateList.valueOf(MUTED)
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
            setEndIconTintList(ColorStateList.valueOf(ACCENT))
            isSaveEnabled = false
        }
        val search = TextInputEditText(input.context).apply {
            setTextColor(TEXT); setHintTextColor(MUTED); textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(200))
            isSaveEnabled = false
            if (Build.VERSION.SDK_INT >= 26) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
            setText(model.startupSelection.query)
        }
        input.addView(search, LinearLayout.LayoutParams(-1, -2))
        controls.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val filters = ChipGroup(this@DeviceDiagnosticsActivity).apply {
            isSingleSelection = true; isSelectionRequired = true; setChipSpacing(dp(6))
        }
        val choices = mapOf(StartupAppFilter.ALL to R.string.vcs_diag_startup_all,
            StartupAppFilter.INSTALLED to R.string.vcs_diag_startup_installed,
            StartupAppFilter.SYSTEM to R.string.vcs_diag_startup_system)
        val ids = choices.mapValues { (_, label) ->
            Chip(this@DeviceDiagnosticsActivity).apply {
                id = View.generateViewId(); text = getString(label); isCheckable = true
                setEnsureMinTouchTargetSize(true)
                chipBackgroundColor = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(ACCENT, "#102330".toColorInt()))
                setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf("#071018".toColorInt(), ACCENT)))
                chipStrokeColor = ColorStateList.valueOf(ACCENT); chipStrokeWidth = dp(1).toFloat()
                isSaveEnabled = false
                filters.addView(this)
            }.id
        }
        filters.check(ids.getValue(model.startupSelection.filter))
        controls.addView(filters, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val panel = StartupPanel(check, progress, observations, controls, search, controls.column(), column())
        search.doAfterTextChanged {
            model.selectStartupApps(model.startupSelection.copy(query = it?.toString().orEmpty(), pageIndex = 0))
            renderStartupResults(model.state.value.startup, panel)
        }
        filters.setOnCheckedStateChangeListener { _, checked ->
            ids.entries.firstOrNull { it.value in checked }?.key?.let { kind ->
                model.selectStartupApps(model.startupSelection.copy(filter = kind, pageIndex = 0))
                renderStartupResults(model.state.value.startup, panel)
            }
        }
        return panel
    }

    private fun renderStartupResults(sample: StartupAppReport?, panel: StartupPanel) {
        panel.controls.isVisible = sample != null
        panel.navigation.removeAllViews(); panel.entries.removeAllViews()
        if (sample == null) return
        val page = StartupAppList.page(sample.apps, model.startupSelection)
        model.selectStartupApps(model.startupSelection.copy(pageIndex = page.index))
        panel.navigation.note(getString(R.string.vcs_diag_startup_scope, page.matching, page.collected))
        if (page.apps.isEmpty()) {
            panel.entries.note(if (sample.apps.isEmpty()) R.string.vcs_diag_startup_empty else R.string.vcs_diag_startup_no_matches)
            return
        }
        panel.navigation.note(getString(R.string.vcs_diag_startup_page, page.index + 1, page.pages))
        panel.navigation.startupPager(page, panel)
        with(panel.entries) {
            page.apps.forEach { app ->
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
        panel.entries.startupPager(page, panel)
    }

    private fun LinearLayout.startupPager(page: StartupAppPage, panel: StartupPanel) {
        val buttons = LinearLayout(this@DeviceDiagnosticsActivity).apply { orientation = LinearLayout.HORIZONTAL }
        fun navigate(index: Int) {
            model.selectStartupApps(model.startupSelection.copy(pageIndex = index))
            renderStartupResults(model.state.value.startup, panel)
            panel.search.clearFocus()
            WindowCompat.getInsetsController(window, panel.search).hide(WindowInsetsCompat.Type.ime())
            panel.navigation.post { panel.navigation.requestRectangleOnScreen(Rect(0, 0, panel.navigation.width, panel.navigation.height), false) }
        }
        val previous = buttons.action(R.string.vcs_diag_startup_previous, page.index > 0) { navigate(page.index - 1) }
        previous.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) }
        val next = buttons.action(R.string.vcs_diag_startup_next, page.index + 1 < page.pages) { navigate(page.index + 1) }
        next.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun LinearLayout.column() = LinearLayout(this@DeviceDiagnosticsActivity).apply {
        orientation = LinearLayout.VERTICAL
    }.also { addView(it, LinearLayout.LayoutParams(-1, -2)) }

    private fun card(title: Int, icon: Int, busy: Boolean = false, rebuild: Boolean = true, content: LinearLayout.() -> Unit) {
        val section = sectionViews.getOrPut(title) { createSection(title, icon) }
        val position = sectionPosition++
        if (binding.diagnosticsSections.indexOfChild(section.container) != position) {
            binding.diagnosticsSections.removeView(section.container)
            binding.diagnosticsSections.addView(section.container, position)
        }
        if (rebuild) section.content.removeAllViews()
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
        val explanations = when (title) {
            R.string.vcs_diag_resources -> listOf(R.string.vcs_diag_cpu_limit, R.string.vcs_diag_memory_limit)
            R.string.vcs_diag_storage -> listOf(R.string.vcs_diag_storage_limit)
            R.string.vcs_diag_apps -> listOf(R.string.vcs_diag_app_resources_limit, R.string.vcs_diag_storage_share,
                R.string.vcs_diag_apps_limit, R.string.vcs_diag_usage_share)
            R.string.vcs_diag_startup -> listOf(R.string.vcs_diag_startup_intro, R.string.vcs_diag_startup_limit)
            else -> emptyList()
        }
        if (explanations.isNotEmpty()) {
            heading.addView(VcsInfoButton(this).apply {
                setInfo(getString(title), explanations.joinToString("\n\n") { getString(it) })
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
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
    private fun LinearLayout.action(label: String, enabled: Boolean = true, callback: () -> Unit): MaterialButton {
        val colors = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(ACCENT, "#665EEAD4".toColorInt()))
        val button = MaterialButton(this@DeviceDiagnosticsActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; isEnabled = enabled; setTextColor(colors); strokeColor = colors; strokeWidth = dp(1); cornerRadius = dp(12)
            backgroundTintList = ColorStateList.valueOf("#102330".toColorInt()); minHeight = dp(48)
            setOnClickListener { callback() }
        }
        addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return button
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
