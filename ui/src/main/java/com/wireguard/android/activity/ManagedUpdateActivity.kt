package com.wireguard.android.activity

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import com.wireguard.android.R
import com.wireguard.android.databinding.VcsUpdateProgressBinding
import com.wireguard.android.util.VcsDialogs
import com.wireguard.android.vcs.ManagedUpdateFlow
import com.wireguard.android.vcs.UpdateFlowState
import com.wireguard.android.vcs.UpdatePhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/** One foreground window for the full flow; Android keeps its own required installation confirmation. */
class ManagedUpdateActivity : AppCompatActivity() {
    private lateinit var binding: VcsUpdateProgressBinding
    private var dialog: AlertDialog? = null
    private var permissionPrompted = false
    private var systemUiOpen = false
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        systemUiOpen = false // Continue only if Android reports the grant; denial stays actionable in this window.
    }
    private val installer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        systemUiOpen = false
        ManagedUpdateFlow.installerReturned(this)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionPrompted = savedInstanceState?.getBoolean("permission_prompted") ?: false
        systemUiOpen = savedInstanceState?.getBoolean("system_ui_open") ?: false
        binding = VcsUpdateProgressBinding.inflate(layoutInflater)
        binding.updateClose.setOnClickListener { finish() }
        binding.updateAction.setOnClickListener {
            when (ManagedUpdateFlow.state.value.phase) {
                UpdatePhase.FAILED -> ManagedUpdateFlow.start(this, retry = true)
                UpdatePhase.READY -> if (ManagedUpdateFlow.canInstall(this)) ManagedUpdateFlow.install(this) else openPermission()
                UpdatePhase.CONFIRMING -> ManagedUpdateFlow.continueInstallation(this)
                UpdatePhase.COMPLETED, UpdatePhase.UP_TO_DATE -> finish()
                else -> Unit
            }
        }
        dialog = VcsDialogs.show(this, title = getString(R.string.vcs_update_flow_title),
            message = getString(R.string.vcs_update_flow_subtitle), customView = binding.root, onDismiss = { finish() })
        ManagedUpdateFlow.start(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    ManagedUpdateFlow.refreshInstallation(this@ManagedUpdateActivity)
                    render(ManagedUpdateFlow.state.value)
                    if (!systemUiOpen) when (ManagedUpdateFlow.state.value.phase) {
                        UpdatePhase.READY -> if (ManagedUpdateFlow.canInstall(this@ManagedUpdateActivity)) ManagedUpdateFlow.install(this@ManagedUpdateActivity)
                            else if (!permissionPrompted) openPermission()
                        UpdatePhase.CONFIRMING -> ManagedUpdateFlow.takeConfirmation()?.let { intent ->
                            try { systemUiOpen = true; installer.launch(intent) }
                            catch (_: Exception) { systemUiOpen = false; ManagedUpdateFlow.restoreConfirmation(this@ManagedUpdateActivity, intent) }
                        }
                        else -> Unit
                    }
                    delay(500)
                }
            }
        }
    }
    private fun openPermission() {
        if (Build.VERSION.SDK_INT < 26) return
        permissionPrompted = true
        try {
            systemUiOpen = true
            permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
        } catch (_: Exception) { systemUiOpen = false }
    }
    private fun render(state: UpdateFlowState) {
        val titles = listOf(R.string.vcs_update_step_check, R.string.stat_download, R.string.vcs_update_step_verify, R.string.vcs_update_step_install)
        val rows = listOf(binding.updateCheckStep, binding.updateDownloadStep, binding.updateVerifyStep, binding.updateInstallStep)
        rows.forEachIndexed { index, row ->
            val complete = state.phase != UpdatePhase.FAILED && state.phase != UpdatePhase.UP_TO_DATE && state.phase.step > index
            val active = state.phase.step == index
            row.text = getString(R.string.vcs_update_step_label, if (complete) "✓" else if (active) "●" else "○", getString(titles[index]))
            row.setTextColor((if (complete || active) "#5EEAD4" else "#8899AA").toColorInt())
        }
        binding.updateVersion.text = state.versionName?.let { getString(R.string.vcs_update_target_version, it) } ?: getString(R.string.vcs_update_current_version, com.wireguard.android.BuildConfig.VERSION_NAME)
        binding.updateStatus.text = state.message ?: getString(when (state.phase) {
            UpdatePhase.CHECKING -> R.string.vcs_update_phase_checking
            UpdatePhase.DOWNLOADING -> R.string.vcs_update_phase_downloading
            UpdatePhase.PAUSED -> R.string.vcs_update_phase_paused
            UpdatePhase.VERIFYING -> R.string.vcs_update_phase_verifying
            UpdatePhase.READY -> R.string.vcs_update_phase_permission
            UpdatePhase.STAGING -> R.string.vcs_update_phase_staging
            UpdatePhase.CONFIRMING -> R.string.vcs_update_phase_confirming
            UpdatePhase.INSTALLING -> R.string.vcs_update_phase_installing
            UpdatePhase.COMPLETED -> R.string.vcs_update_phase_completed
            UpdatePhase.UP_TO_DATE -> R.string.vcs_update_none
            UpdatePhase.FAILED -> R.string.vcs_update_phase_failed
        })
        val terminal = state.phase in setOf(UpdatePhase.FAILED, UpdatePhase.UP_TO_DATE, UpdatePhase.COMPLETED)
        binding.updateProgress.visibility = if (terminal) View.GONE else View.VISIBLE
        binding.updateProgress.isIndeterminate = state.percent == null
        state.percent?.let { binding.updateProgress.progress = it }
        binding.updateBytes.text = if (state.total > 0) String.format(Locale.US, "%.1f / %.1f MiB%s", state.bytes / 1_048_576.0, state.total / 1_048_576.0,
            state.percent?.let { " · $it%" } ?: "") else state.percent?.let { "$it%" } ?: ""
        val action = when (state.phase) {
            UpdatePhase.FAILED -> R.string.vcs_update_retry
            UpdatePhase.READY -> if (ManagedUpdateFlow.canInstall(this)) R.string.vcs_update_step_install else R.string.vcs_update_allow_install
            UpdatePhase.CONFIRMING -> R.string.vcs_update_continue_install
            UpdatePhase.COMPLETED, UpdatePhase.UP_TO_DATE -> R.string.vcs_update_done
            else -> null
        }
        binding.updateAction.visibility = if (action == null) View.GONE else View.VISIBLE
        action?.let { binding.updateAction.setText(it) }
        binding.updateClose.setText(if (terminal) android.R.string.cancel else R.string.vcs_update_hide)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permission_prompted", permissionPrompted)
        outState.putBoolean("system_ui_open", systemUiOpen)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { dialog?.setOnDismissListener(null); dialog?.dismiss(); dialog = null; super.onDestroy() }
}
