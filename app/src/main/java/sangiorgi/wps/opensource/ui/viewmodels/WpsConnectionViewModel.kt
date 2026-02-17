package sangiorgi.wps.opensource.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import sangiorgi.wps.lib.ConnectionUpdateCallback
import sangiorgi.wps.lib.ConnectionUpdateCallback.TYPE_LOCKED
import sangiorgi.wps.lib.ConnectionUpdateCallback.TYPE_PIXIE_DUST_NOT_COMPATIBLE
import sangiorgi.wps.lib.ConnectionUpdateCallback.TYPE_SELINUX
import sangiorgi.wps.lib.WpsConnectionManager
import sangiorgi.wps.lib.models.NetworkToTest
import sangiorgi.wps.opensource.domain.models.WifiNetwork
import sangiorgi.wps.opensource.ui.screens.*
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class WpsConnectionViewModel @Inject constructor(
    private val wpsManager: WpsConnectionManager,
) : ViewModel(), ConnectionUpdateCallback {

    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private var currentNetwork: WifiNetwork? = null
    private var currentMethod: ConnectionMethod? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun startConnection(network: WifiNetwork, method: ConnectionMethod) {
        currentNetwork = network
        currentMethod = method

        // Reset state
        _connectionState.value = ConnectionState(
            status = ConnectionStatus.CONNECTING,
            totalPins = when (method) {
                is ConnectionMethod.STANDARD_WITH_PINS -> method.pins.size
                is ConnectionMethod.BRUTE_FORCE -> 100000000 // All 8-digit PINs
                else -> 1
            },
        )

        val bssid = network.bssid
        val ssid = network.ssid
        val pins = when (method) {
            is ConnectionMethod.STANDARD_WITH_PINS -> method.pins.toTypedArray()
            is ConnectionMethod.CUSTOM_PIN_WITH_VALUE -> arrayOf(method.pin)
            is ConnectionMethod.BELKIN -> generateBelkinPins(network.bssid)
            is ConnectionMethod.PIXIE_DUST -> arrayOf()
            is ConnectionMethod.BRUTE_FORCE -> arrayOf()
            else -> getDefaultPins()
        }

        // Start connection based on method
        viewModelScope.launch {
            addLog("Starting WPS connection...", LogType.INFO)
            addLog("Network: ${network.ssid}", LogType.INFO)
            addLog("BSSID: ${network.bssid}", LogType.INFO)
            addLog("Method: ${getMethodName(method)}", LogType.INFO)

            when (method) {
                is ConnectionMethod.PIXIE_DUST -> {
                    addLog("Starting Pixie Dust attack...", LogType.WARNING)
                    wpsManager.pixieDust(bssid, ssid, this@WpsConnectionViewModel)
                }
                is ConnectionMethod.BELKIN -> {
                    addLog("Using Belkin-specific PIN generation...", LogType.INFO)
                    wpsManager.testBelkinPin(bssid, ssid, this@WpsConnectionViewModel)
                }
                is ConnectionMethod.BRUTE_FORCE -> {
                    addLog("Starting brute force attack (this may take a long time)...", LogType.WARNING)
                    wpsManager.bruteForce(bssid, ssid, 1000, this@WpsConnectionViewModel)
                }
                else -> {
                    addLog("Testing ${pins.size} PINs...", LogType.INFO)
                    wpsManager.testPins(bssid, ssid, pins, this@WpsConnectionViewModel)
                }
            }
        }
    }

    fun cancelConnection() {
        addLog("Cancelling connection...", LogType.WARNING)
        _connectionState.update { it.copy(status = ConnectionStatus.FAILED) }

        // Run cancel and cleanup on background thread to avoid ANR
        viewModelScope.launch(Dispatchers.IO) {
            try {
                wpsManager.cancel()
                wpsManager.cleanup()
            } catch (_: Exception) {
                // Ignore errors during cleanup
            }
        }
    }

    override fun create(title: String, message: String, progress: Int) {
        addLog(message, LogType.INFO)
        _connectionState.update { state ->
            state.copy(
                totalPins = progress,
                currentPinIndex = 0,
            )
        }
    }

    override fun updateMessage(message: String) {
        addLog(message, LogType.INFO)

        // Parse PIN from message if present
        val pinRegex = "\\b\\d{8}\\b".toRegex()
        val pin = pinRegex.find(message)?.value

        if (pin != null) {
            _connectionState.update { it.copy(currentPin = pin) }
        }
    }

    override fun updateCount(increment: Int) {
        _connectionState.update { state ->
            state.copy(currentPinIndex = state.currentPinIndex + increment)
        }
    }

    override fun error(message: String, type: Int) {
        val logType = when (type) {
            TYPE_LOCKED -> {
                addLog("WPS is locked on this router!", LogType.ERROR)
                LogType.ERROR
            }
            TYPE_SELINUX -> {
                addLog("SELinux error encountered", LogType.ERROR)
                LogType.ERROR
            }
            TYPE_PIXIE_DUST_NOT_COMPATIBLE -> {
                addLog("Router not vulnerable to Pixie Dust", LogType.WARNING)
                LogType.WARNING
            }
            else -> LogType.ERROR
        }

        addLog(message, logType)
        _connectionState.update { state ->
            state.copy(
                status = ConnectionStatus.FAILED,
                errorMessage = message,
            )
        }
    }

    override fun success(networkToTest: NetworkToTest, isRoot: Boolean) {
        val successPin = _connectionState.value.currentPin
        addLog("SUCCESS! PIN found: $successPin", LogType.SUCCESS)

        if (isRoot) {
            addLog("Connection established with root privileges", LogType.SUCCESS)
        }

        // Get password from NetworkToTest (extracted from wpa_supplicant output)
        val password = networkToTest.password
        if (!password.isNullOrEmpty()) {
            addLog("WiFi Password: $password", LogType.SUCCESS)
        }

        _connectionState.update { state ->
            state.copy(
                status = ConnectionStatus.SUCCESS,
                successPin = successPin,
                password = password,
            )
        }
    }

    // Additional callbacks for improved pattern
    override fun onPixieDustSuccess(pin: String, password: String?) {
        addLog("Pixie Dust attack successful!", LogType.SUCCESS)
        addLog("PIN discovered: $pin", LogType.SUCCESS)
        if (!password.isNullOrEmpty()) {
            addLog("WiFi Password: $password", LogType.SUCCESS)
        }

        _connectionState.update { state ->
            state.copy(
                status = ConnectionStatus.SUCCESS,
                successPin = pin,
                password = password,
            )
        }
    }

    override fun onPixieDustFailure(error: String) {
        addLog("Pixie Dust attack failed: $error", LogType.ERROR)
        _connectionState.update { state ->
            state.copy(
                status = ConnectionStatus.FAILED,
                errorMessage = error,
            )
        }
    }

    private fun addLog(message: String, type: LogType) {
        val timestamp = dateFormat.format(Date())
        val log = ConnectionLog(timestamp, message, type)

        _connectionState.update { state ->
            state.copy(logs = state.logs + log)
        }
    }

    private fun getMethodName(method: ConnectionMethod): String {
        return when (method) {
            is ConnectionMethod.STANDARD -> "Standard WPS"
            is ConnectionMethod.STANDARD_WITH_PINS -> "Standard WPS with ${method.pins.size} PINs"
            is ConnectionMethod.PIXIE_DUST -> "Pixie Dust"
            is ConnectionMethod.BELKIN -> "Belkin-Specific"
            is ConnectionMethod.BRUTE_FORCE -> "Brute Force"
            is ConnectionMethod.CUSTOM_PIN -> "Custom PIN"
            is ConnectionMethod.CUSTOM_PIN_WITH_VALUE -> "Custom PIN: ${method.pin}"
        }
    }

    private fun getDefaultPins(): Array<String> {
        return arrayOf(
            "12345670",
        )
    }

    private fun generateBelkinPins(bssid: String): Array<String> {
        // Simplified Belkin PIN generation
        // In reality, this would use the actual Belkin algorithm
        val mac = bssid.replace(":", "")
        val seed = mac.substring(6).toIntOrNull(16) ?: 0
        val pin = String.format(Locale.ROOT, "%08d", seed % 100000000)
        return arrayOf(pin, "12345670", "00000000")
    }

    override fun onCleared() {
        super.onCleared()
        // Only clean up the active operation — do NOT shutdown the shared singleton manager.
        // The manager's executor and thread pool are reused across ViewModel instances.
        Thread {
            try {
                wpsManager.cleanup()
            } catch (_: Exception) {
                // Ignore errors during cleanup
            }
        }.start()
    }
}
