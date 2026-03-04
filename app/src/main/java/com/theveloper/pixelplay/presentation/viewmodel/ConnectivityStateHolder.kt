package com.theveloper.pixelplay.presentation.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages WiFi and Bluetooth connectivity state.
 * Extracted from PlayerViewModel to improve modularity.
 *
 * Responsibilities:
 * - WiFi state tracking (enabled, radio state, network name)
 * - Bluetooth state tracking (enabled, active device name, paired/connected audio devices)
 * - System callback registration and lifecycle management
 */
@Singleton
class ConnectivityStateHolder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // WiFi State
    private val _isWifiEnabled = MutableStateFlow(false)
    val isWifiEnabled: StateFlow<Boolean> = _isWifiEnabled.asStateFlow()

    private val _isWifiRadioOn = MutableStateFlow(false)
    val isWifiRadioOn: StateFlow<Boolean> = _isWifiRadioOn.asStateFlow()

    private val _wifiName = MutableStateFlow<String?>(null)
    val wifiName: StateFlow<String?> = _wifiName.asStateFlow()

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    // Bluetooth State
    private val _isBluetoothEnabled = MutableStateFlow(false)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _bluetoothName = MutableStateFlow<String?>(null)
    val bluetoothName: StateFlow<String?> = _bluetoothName.asStateFlow()

    private val _bluetoothAudioDevices = MutableStateFlow<List<String>>(emptyList())
    val bluetoothAudioDevices: StateFlow<List<String>> = _bluetoothAudioDevices.asStateFlow()
    
    // Offline Barrier Event
    // Event to signal that playback was blocked due to offline status
    // Using extraBufferCapacity to ensure the event isn't lost if no collectors are immediately suspended
    private val _offlinePlaybackBlocked = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val offlinePlaybackBlocked: SharedFlow<Unit> = _offlinePlaybackBlocked.asSharedFlow()
    
    fun triggerOfflineBlockedEvent() {
        _offlinePlaybackBlocked.tryEmit(Unit)
    }

    /**
     * Manually refresh local connection info (e.g. WiFi SSID).
     */
    fun refreshLocalConnectionInfo(refreshBluetoothDevices: Boolean = false) {
        val activeNetwork = connectivityManager.activeNetwork
        updateWifiInfo(activeNetwork)
        if (refreshBluetoothDevices) {
            refreshBluetoothAudioDevices()
        } else {
            updateAudioDevices()
        }
    }

    // System services
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val audioManager: android.media.AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

    // Callbacks and receivers
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiStateReceiver: BroadcastReceiver? = null
    private var bluetoothStateReceiver: BroadcastReceiver? = null
    private var audioDeviceCallback: android.media.AudioDeviceCallback? = null

    private var isInitialized = false
    private val discoveredBluetoothAudioDevices = linkedSetOf<String>()

    /**
     * Initialize connectivity monitoring. Should be called once from ViewModel.
     */
    fun initialize() {
        if (isInitialized) return
        isInitialized = true

        // Initial state check
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        updateWifiRadioState()
        _isWifiEnabled.value = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (_isWifiEnabled.value) {
            updateWifiInfo(activeNetwork)
        }
        
        _isOnline.value = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        _isBluetoothEnabled.value = bluetoothAdapter?.isEnabled ?: false

        // Register WiFi network callback
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            // Track all valid networks to handle rapid switching
            private val availableNetworks = mutableSetOf<Network>()

            override fun onAvailable(network: Network) {
                // Network is available, but waiting for capability check
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                
                if (hasInternet && isValidated) {
                    availableNetworks.add(network)
                } else {
                    availableNetworks.remove(network)
                }
                
                checkConnectivity()
                
                if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    _isWifiEnabled.value = true
                    updateWifiInfo(network)
                }
            }

            override fun onLost(network: Network) {
                availableNetworks.remove(network)
                checkConnectivity()
                
                val currentNetwork = connectivityManager.activeNetwork
                val caps = connectivityManager.getNetworkCapabilities(currentNetwork)
                _isWifiEnabled.value = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                if (!_isWifiEnabled.value) _wifiName.value = null
            }
            
            private fun checkConnectivity() {
                _isOnline.value = availableNetworks.isNotEmpty()
            }
        }
        
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback!!)

        // Register receivers
        wifiStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                     updateWifiRadioState()
                }
            }
        }
        context.registerReceiver(wifiStateReceiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))

        bluetoothStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        _isBluetoothEnabled.value = bluetoothAdapter?.isEnabled ?: false
                        if (_isBluetoothEnabled.value) {
                            updateAudioDevices()
                        } else {
                            discoveredBluetoothAudioDevices.clear()
                            _bluetoothAudioDevices.value = emptyList()
                            updateBluetoothName(emptyList())
                        }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> updateAudioDevices()
                    BluetoothDevice.ACTION_FOUND -> {
                        extractBluetoothDevice(intent)
                            ?.takeIf { it.isAudioOutputCandidate() }
                            ?.name
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() && !isOwnBluetoothDeviceName(it) }
                            ?.let { deviceName ->
                                discoveredBluetoothAudioDevices += deviceName
                                updateAudioDevices()
                            }
                    }
                }
            }
        }
        context.registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_FOUND)
            }
        )

        // Audio Device Callback
        audioDeviceCallback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
                updateAudioDevices()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
                updateAudioDevices()
            }
        }
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        updateAudioDevices()
    }

    private fun updateWifiRadioState() {
        _isWifiRadioOn.value = wifiManager?.isWifiEnabled == true
    }

    @SuppressLint("MissingPermission")
    private fun updateWifiInfo(network: Network?) {
         if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
             val info = wifiManager?.connectionInfo
             if (info != null && info.supplicantState == android.net.wifi.SupplicantState.COMPLETED) {
                 var ssid = info.ssid
                 if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                     ssid = ssid.substring(1, ssid.length - 1)
                 }
                 _wifiName.value = ssid
             } else {
                 _wifiName.value = null
             }
         } else {
             // Basic fallback purely on network capabilities if we don't have permission (unlikely for system app but good practice)
             _wifiName.value = "WiFi Connected" 
         }
    }

    private fun updateBluetoothName(connectedAudioDevices: List<String>) {
        if (!_isBluetoothEnabled.value) {
            _bluetoothName.value = null
            return
        }

        _bluetoothName.value = _bluetoothName.value
            ?.takeIf { it in connectedAudioDevices }
            ?: connectedAudioDevices.firstOrNull()
    }

    private fun updateAudioDevices() {
        if (!_isBluetoothEnabled.value) {
            discoveredBluetoothAudioDevices.clear()
            _bluetoothAudioDevices.value = emptyList()
            updateBluetoothName(emptyList())
            return
        }

        val connectedDevices = sanitizeBluetoothDeviceNames(collectConnectedBluetoothNames())
        val availableDevices = sanitizeBluetoothDeviceNames(discoveredBluetoothAudioDevices.toList())
        val connectedDeviceSet = connectedDevices.toSet()

        _bluetoothAudioDevices.value = (connectedDevices + availableDevices)
            .distinct()
            .sortedWith(
                compareByDescending<String> { it in connectedDeviceSet }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it }
            )
        updateBluetoothName(connectedDevices)
    }

    @SuppressLint("MissingPermission")
    private fun safeGetConnectedDevices(profile: Int): List<BluetoothDevice> {
        return runCatching { bluetoothManager.getConnectedDevices(profile) }.getOrElse { emptyList() }
    }

    private fun collectConnectedBluetoothNames(): List<String> {
        val connectedDevices = mutableListOf<String>()
        val audioDevices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)

        for (device in audioDevices) {
            if (
                device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            ) {
                device.productName?.toString()?.let(connectedDevices::add)
            }
        }

        if (hasBluetoothConnectPermission()) {
            safeGetConnectedDevices(BluetoothProfile.A2DP)
                .mapNotNull { it.name }
                .forEach { if (!connectedDevices.contains(it)) connectedDevices.add(it) }

            safeGetConnectedDevices(BluetoothProfile.HEADSET)
                .mapNotNull { it.name }
                .forEach { if (!connectedDevices.contains(it)) connectedDevices.add(it) }
        }

        return connectedDevices
    }

    private fun refreshBluetoothAudioDevices() {
        discoveredBluetoothAudioDevices.clear()
        updateAudioDevices()

        val adapter = bluetoothAdapter ?: return
        if (!canStartBluetoothDiscovery()) return

        if (adapter.isDiscovering) {
            runCatching { adapter.cancelDiscovery() }
        }
        runCatching { adapter.startDiscovery() }
    }

    private fun sanitizeBluetoothDeviceNames(names: List<String>): List<String> {
        return names
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isOwnBluetoothDeviceName(it) }
    }

    private fun isOwnBluetoothDeviceName(name: String): Boolean {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) return true

        val localDeviceNames = buildSet {
            bluetoothAdapter?.name?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            Build.MODEL.trim().takeIf { it.isNotEmpty() }?.let(::add)
        }

        return localDeviceNames.any { it.equals(normalizedName, ignoreCase = true) }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothDiscoveryLocationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun canStartBluetoothDiscovery(): Boolean {
        return _isBluetoothEnabled.value &&
            hasBluetoothScanPermission() &&
            hasBluetoothDiscoveryLocationPermission()
    }

    private fun BluetoothDevice.isAudioOutputCandidate(): Boolean {
        val deviceClass = bluetoothClass ?: return false
        return deviceClass.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
            deviceClass.hasService(BluetoothClass.Service.RENDER)
    }

    @Suppress("DEPRECATION")
    private fun extractBluetoothDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    /**
     * Cleanup resources. Should be called from ViewModel's onCleared.
     */
    fun onCleared() {
        networkCallback?.let { 
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        wifiStateReceiver?.let { 
            runCatching { context.unregisterReceiver(it) }
        }
        bluetoothStateReceiver?.let { 
            runCatching { context.unregisterReceiver(it) }
        }
        audioDeviceCallback?.let { 
            audioManager.unregisterAudioDeviceCallback(it) 
        }
        bluetoothAdapter?.takeIf { it.isDiscovering }?.let {
            runCatching { it.cancelDiscovery() }
        }
        discoveredBluetoothAudioDevices.clear()
        isInitialized = false
    }
}
