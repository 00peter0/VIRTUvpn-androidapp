package com.wireguard.android.vcs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One snapshot per observation; survives rotation, stops on explicit navigation away. No history is persisted. */
class DeviceDiagnosticsViewModel(application: Application) : AndroidViewModel(application) {
    data class State(
        val report: DeviceDiagnosticReport? = null, val refreshing: Boolean = false, val error: Boolean = false,
        val apps: AppResourceReport? = null, val appProgress: Pair<Int, Int>? = null, val appError: Boolean = false,
        val startup: StartupAppReport? = null, val startupProgress: Pair<Int, Int>? = null, val startupError: Boolean = false
    ) {
        val busy: Boolean get() = refreshing || appProgress != null || startupProgress != null
    }
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var refreshJob: Job? = null
    private var appsJob: Job? = null
    private var startupJob: Job? = null
    private var appGeneration = 0
    var startupSelection = StartupAppSelection()
        private set
    init { refresh() }

    fun selectStartupApps(selection: StartupAppSelection) { startupSelection = selection }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            mutable.value = mutable.value.copy(refreshing = true, error = false)
            try { mutable.value = mutable.value.copy(report = ManagedDiagnostics.collect(getApplication())) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(error = true) }
            finally { mutable.value = mutable.value.copy(refreshing = false) }
        }
    }
    fun scanApps() {
        if (appsJob?.isActive == true) return
        val generation = ++appGeneration
        appsJob = viewModelScope.launch {
            mutable.value = mutable.value.copy(apps = null, appProgress = 0 to 0, appError = false)
            try {
                val result = ManagedDiagnostics.appResources(getApplication()) { done, total -> withContext(Dispatchers.Main.immediate) {
                    if (generation == appGeneration) mutable.value = mutable.value.copy(appProgress = done to total)
                } }
                if (generation == appGeneration && ManagedDiagnostics.hasUsageAccess(getApplication()))
                    mutable.value = mutable.value.copy(apps = result)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (generation == appGeneration) mutable.value = mutable.value.copy(appError = true) }
            finally { if (generation == appGeneration) mutable.value = mutable.value.copy(appProgress = null) }
        }
    }
    fun scanStartup() {
        if (startupJob?.isActive == true) return
        startupSelection = startupSelection.copy(pageIndex = 0)
        startupJob = viewModelScope.launch {
            mutable.value = mutable.value.copy(startup = null, startupProgress = 0 to 0, startupError = false)
            try {
                val result = StartupDiagnostics.collect(getApplication()) { done, total -> withContext(Dispatchers.Main.immediate) {
                    mutable.value = mutable.value.copy(startupProgress = done to total)
                } }
                mutable.value = mutable.value.copy(startup = result)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(startupError = true) }
            finally { mutable.value = mutable.value.copy(startupProgress = null) }
        }
    }
    fun revokeUsageObservation() {
        ++appGeneration
        appsJob?.cancel()
        mutable.value = mutable.value.copy(apps = null, appProgress = null)
    }
}
