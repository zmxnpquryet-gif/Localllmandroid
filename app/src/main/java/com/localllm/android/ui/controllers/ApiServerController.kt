package com.localllm.android.ui.controllers

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.localllm.android.R
import com.localllm.android.data.preferences.AppSettingsStore
import com.localllm.android.i18n.LocalizedStringProvider
import com.localllm.android.model.GenerationSettings
import com.localllm.android.server.ApiServerService
import com.localllm.android.server.OllamaApiServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.util.Collections

/**
 * Manages the foreground [ApiServerService] binding, bearer key lifecycle,
 * LAN/loopback bind address toggle, and local IPv4 address discovery.
 */
internal class ApiServerController(
    private val application: Application,
    private val scope: CoroutineScope,
    private val settingsStore: AppSettingsStore,
    private val strings: LocalizedStringProvider,
    private val currentSettings: () -> GenerationSettings,
    private val updateSettingsState: (GenerationSettings) -> Unit,
    private val postStatus: (String) -> Unit
) {
    private val _isApiModeEnabled = MutableStateFlow(false)
    val isApiModeEnabled: StateFlow<Boolean> = _isApiModeEnabled.asStateFlow()

    private val _apiServerPort = MutableStateFlow(OllamaApiServer.DEFAULT_PORT)
    val apiServerPort: StateFlow<Int> = _apiServerPort.asStateFlow()

    private val _apiServerStatusMessage = MutableStateFlow<String?>(strings.get(R.string.vm_api_status_idle))
    val apiServerStatusMessage: StateFlow<String?> = _apiServerStatusMessage.asStateFlow()

    private val _apiRequestCount = MutableStateFlow(0)
    val apiRequestCount: StateFlow<Int> = _apiRequestCount.asStateFlow()

    private val _localIpAddress = MutableStateFlow("127.0.0.1")
    val localIpAddress: StateFlow<String> = _localIpAddress.asStateFlow()

    private val _apiServerApiKey = MutableStateFlow(settingsStore.loadOrCreateApiKey())
    val apiServerApiKey: StateFlow<String> = _apiServerApiKey.asStateFlow()

    private var apiBinder: ApiServerService.ServerBinder? = null
    private var apiConnection: ServiceConnection? = null

    fun reattachIfRunning() {
        if (ApiServerService.isRunning) {
            _isApiModeEnabled.value = true
            bindApiServer()
        }
    }

    fun setApiModeEnabled(enabled: Boolean) {
        _isApiModeEnabled.value = enabled
        if (enabled) startApiServer() else stopApiServer()
    }

    fun startApiServer() {
        ApiServerService.start(application)
        refreshLocalIp()
        bindApiServer()
    }

    fun stopApiServer() {
        ApiServerService.stop(application)
        unbindApiServer()
        _isApiModeEnabled.value = false
        val stoppedMsg = strings.get(R.string.vm_status_api_stopped)
        _apiServerStatusMessage.value = stoppedMsg
        postStatus(stoppedMsg)
    }

    fun restartApiServer() {
        stopApiServer()
        startApiServer()
    }

    fun setExternalAccess(enabled: Boolean) {
        val bindAddr = if (enabled) "0.0.0.0" else "127.0.0.1"
        updateSettingsState(currentSettings().copy(apiServerBindAddress = bindAddr))
        settingsStore.saveApiBindAddress(bindAddr)
        if (_isApiModeEnabled.value) {
            restartApiServer()
        }
    }

    fun regenerateApiKey() {
        val newKey = OllamaApiServer.generateSecureApiKey()
        settingsStore.saveApiKey(newKey)
        _apiServerApiKey.value = newKey
        try {
            apiBinder?.reloadApiKey()
        } catch (t: Throwable) {
            Log.w("ApiServerController", "API key reload failed: ${t.message}")
        }
    }

    fun refreshLocalIp() {
        scope.launch(Dispatchers.IO) {
            try {
                for (intf in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    for (addr in Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: ""
                            if (host.indexOf(':') < 0) {
                                _localIpAddress.value = host
                                return@launch
                            }
                        }
                    }
                }
                _localIpAddress.value = "127.0.0.1"
            } catch (_: Exception) {
                _localIpAddress.value = "127.0.0.1"
            }
        }
    }

    fun detach() {
        unbindApiServer()
    }

    private fun bindApiServer() {
        if (apiConnection != null) return
        val intent = Intent(application, ApiServerService::class.java)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? ApiServerService.ServerBinder ?: return
                apiBinder = binder
                binder.setListener { snap -> applyServerSnapshot(snap) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                apiBinder = null
                apiConnection = null
                _isApiModeEnabled.value = false
                _apiServerStatusMessage.value = strings.get(R.string.vm_status_api_stopped)
            }
        }
        apiConnection = conn
        try {
            application.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            Log.w("ApiServerController", "API service bind failed: ${t.message}")
            apiConnection = null
        }
    }

    private fun unbindApiServer() {
        runCatching { apiBinder?.setListener(null) }
        runCatching { apiConnection?.let { application.unbindService(it) } }
        apiBinder = null
        apiConnection = null
    }

    private fun applyServerSnapshot(snap: ApiServerService.Snapshot) {
        _isApiModeEnabled.value = snap.running
        _apiServerStatusMessage.value = snap.message
        postStatus(
            strings.get(
                R.string.vm_status_api_server,
                if (snap.running) strings.get(R.string.vm_state_running) else strings.get(R.string.vm_state_stopped)
            )
        )
        _apiRequestCount.value = snap.requestCount
        if (snap.apiKey.isNotBlank()) _apiServerApiKey.value = snap.apiKey
    }
}
