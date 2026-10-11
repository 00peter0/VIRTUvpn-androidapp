package com.wireguard.android.activity

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Branded, read-only device overview. JSON is available only through explicit sharing. */
class DeviceDiagnosticsActivity : AppCompatActivity() {
    private lateinit var binding: DeviceDiagnosticsActivityBinding
    private lateinit var model: DeviceDiagnosticsViewModel
    private var awaitingUsageAccess = false
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
                val export = ManagedDiagnostics.export(report, state.ports, state.apps.takeIf { ManagedDiagnostics.hasUsageAccess(this) }).toString(2)
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
        binding.diagnosticsSections.removeAllViews()
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
            card(R.string.vcs_diag_connection, R.drawable.ic_wifi) {
                row("VCS account session", if (report.accountSessionPresent) "Present" else "Optional · not signed in")
                row("Managed device session", if (report.deviceSessionPresent) "Present" else "Not enrolled")
                row("Last synchronization state", report.syncPhase)
                row("Update state", report.updatePhase)
                row("Battery optimization for VirtuVPN", switch(s.batteryOptimized))
                row("Background restricted by Android", switch(s.backgroundRestricted))
                row("Active network has VPN transport", switch(s.vpnTransport))
                note("Background synchronization is periodic and subject to Android battery and network scheduling. Session presence does not prove current server connectivity.")
                if (report.tunnels.isEmpty()) note(if (report.tunnelEvidenceComplete) R.string.vcs_diag_no_tunnels else R.string.vcs_diag_partial)
                report.tunnels.forEach { t ->
                    row("VPN ${t.index}", t.state)
                    row("Configured DNS servers", t.dnsCount?.toString() ?: unavailable())
                    row("Default route · IPv4 / IPv6", "${switch(t.defaultIpv4)} / ${switch(t.defaultIpv6)}")
                    row("Last handshake age", t.handshakeAgeSeconds?.let { "$it seconds" } ?: "No handshake evidence")
                }
                if (!report.tunnelEvidenceComplete && report.tunnels.isNotEmpty()) note(R.string.vcs_diag_partial)
                note(R.string.vcs_diag_tunnel_limit)
            }
        }
        card(R.string.vcs_diag_apps, R.drawable.ic_device) {
            if (Build.VERSION.SDK_INT < 26) {
                note(R.string.vcs_diag_optional_older)
                action(R.string.vcs_diag_storage_settings) { open(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
            } else {
                note(R.string.vcs_diag_usage_intro)
                val access = ManagedDiagnostics.hasUsageAccess(this@DeviceDiagnosticsActivity)
                state.appProgress?.let { progress(it) }
                if (state.appError) note(R.string.vcs_diag_failed)
                if (access) state.apps?.let { sample ->
                    note(getString(R.string.vcs_diag_app_scope, sample.measured, sample.visible,
                        getString(if (sample.complete) R.string.vcs_diag_sample_complete else R.string.vcs_diag_partial)))
                    note("Observed ${time(sample.collectedAt)}")
                    sample.apps.forEachIndexed { i, app ->
                        row("${i + 1}. ${app.label}", bytes(app.totalBytes))
                        note("App ${bytes(app.appBytes)} · Data ${bytes(app.dataBytes)} · Cache ${bytes(app.cacheBytes)} included")
                    }
                    note(R.string.vcs_diag_apps_limit)
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
        card(R.string.vcs_diag_ports, R.drawable.ic_server) {
            note(R.string.vcs_diag_port_intro)
            state.portProgress?.let { progress(it) }
            if (state.portError) note(R.string.vcs_diag_failed)
            state.ports?.let { result ->
                note("Observed ${time(result.collectedAt)}")
                row("Evidence", getString(if (result.complete) R.string.vcs_diag_ports_complete else R.string.vcs_diag_partial))
                note(getString(R.string.vcs_diag_port_scope, result.completed, result.total, result.unknown))
                if (result.accepting.isEmpty()) note(R.string.vcs_diag_no_ports)
                result.accepting.forEach { row("${it.family} loopback · TCP ${it.port}", "Accepting", AMBER) }
            }
            note(R.string.vcs_diag_port_limit)
            action(R.string.vcs_diag_scan_ports, state.portProgress == null) { model.scanPorts() }
        }
    }
    private fun card(title: Int, icon: Int, content: LinearLayout.() -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundResource(R.drawable.stat_card_background)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(ImageView(this).apply { setImageResource(icon); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; imageTintList = ColorStateList.valueOf(ACCENT) }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
        heading.addView(text(getString(title), 18f, TEXT, true), LinearLayout.LayoutParams(-1, -2))
        card.addView(heading)
        card.content(); binding.diagnosticsSections.addView(card)
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
    private fun LinearLayout.action(resource: Int, enabled: Boolean = true, callback: () -> Unit) {
        addView(MaterialButton(this@DeviceDiagnosticsActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(resource); isEnabled = enabled; setTextColor(ACCENT); strokeColor = ColorStateList.valueOf(ACCENT); strokeWidth = dp(1); cornerRadius = dp(12)
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
    private fun bytes(value: Long?): String {
        if (value == null || value < 0) return unavailable()
        val gib = 1_073_741_824.0
        return if (value >= gib) String.format(Locale.US, "%.1f GiB", value / gib) else String.format(Locale.US, "%.1f MiB", value / 1_048_576.0)
    }
    private fun thermal(value: Int?): String = when (value) {
        0 -> "No throttling reported"; 1 -> "Light"; 2 -> "Moderate"; 3 -> "Severe"; 4 -> "Critical"; 5 -> "Emergency"; 6 -> "Shutdown"; else -> unavailable()
    }
    companion object {
        private val TEXT = "#FFFFFF".toColorInt(); private val MUTED = "#AFC0CC".toColorInt()
        private val ACCENT = "#5EEAD4".toColorInt(); private val AMBER = "#FFB74D".toColorInt()
    }
}
