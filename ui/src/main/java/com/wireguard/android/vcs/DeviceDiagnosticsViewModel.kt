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
        val ports: PhonePortReport? = null, val portProgress: Pair<Int, Int>? = null, val portError: Boolean = false,
        val apps: AppStorageReport? = null, val appProgress: Pair<Int, Int>? = null, val appError: Boolean = false
    ) {
        val busy: Boolean get() = refreshing || portProgress != null || appProgress != null
    }
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var refreshJob: Job? = null
    private var portsJob: Job? = null
    private var appsJob: Job? = null
    private var appGeneration = 0
    init { refresh() }

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
    fun scanPorts() {
        if (portsJob?.isActive == true) return
        portsJob = viewModelScope.launch {
            mutable.value = mutable.value.copy(ports = null, portProgress = 0 to PhonePortScan.PORTS.size * 2, portError = false)
            try {
                val result = PhonePortScan.scan { done, total -> withContext(Dispatchers.Main.immediate) {
                    val latest = mutable.value.portProgress?.first ?: 0
                    mutable.value = mutable.value.copy(portProgress = maxOf(done, latest) to total)
                } }
                mutable.value = mutable.value.copy(ports = result)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(portError = true) }
            finally { mutable.value = mutable.value.copy(portProgress = null) }
        }
    }
    fun scanApps() {
        if (appsJob?.isActive == true) return
        val generation = ++appGeneration
        appsJob = viewModelScope.launch {
            mutable.value = mutable.value.copy(apps = null, appProgress = 0 to 0, appError = false)
            try {
                val result = ManagedDiagnostics.appStorage(getApplication()) { done, total -> withContext(Dispatchers.Main.immediate) {
                    if (generation == appGeneration) mutable.value = mutable.value.copy(appProgress = done to total)
                } }
                if (generation == appGeneration && ManagedDiagnostics.hasUsageAccess(getApplication()))
                    mutable.value = mutable.value.copy(apps = result)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (generation == appGeneration) mutable.value = mutable.value.copy(appError = true) }
            finally { if (generation == appGeneration) mutable.value = mutable.value.copy(appProgress = null) }
        }
    }
    fun revokeUsageObservation() {
        ++appGeneration
        appsJob?.cancel()
        mutable.value = mutable.value.copy(apps = null, appProgress = null)
    }
}
