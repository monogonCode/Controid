package com.controid.service

import com.controid.clipboard.ClipboardEntry
import com.controid.protocol.LinkStatus
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ShizukuState {
    UNAVAILABLE,
    PERMISSION_REQUIRED,
    READY,
    DAEMON_UNAVAILABLE
}

data class EcosystemSnapshot(
    val shizukuState: ShizukuState = ShizukuState.UNAVAILABLE,
    val bleActive: Boolean = false,
    val tetheringStatus: LinkStatus = LinkStatus.IDLE,
    val tcpConnected: Boolean = false,
    val hidConnected: Boolean = false,
    val hidFallbackActive: Boolean = false,
    val isStreaming: Boolean = false,
    val pairedKeyFingerprint: String? = null,
    val lastError: String? = null,
    val serviceRunning: Boolean = false,
    val shizukuInstalled: Boolean = false,
    val shizukuUid: Int? = null,
    val connectedClients: Int = 0,
    val hotspotSsid: String? = null,
    val hotspotBand5Ghz: Boolean = true,
    val tcpAuthenticated: Boolean = false,
    val peerAddress: String? = null,
    val clipboardBackgroundGranted: Boolean = false,
    val streamWidth: Int = 0,
    val streamHeight: Int = 0,
    val streamFps: Int = 0,
    val streamBitrateMbps: Int = 0,
    val streamCodec: String = "H.264"
)

interface EcosystemCommands {
    fun requestShizukuPermission()
    fun startHotspot(prefer5Ghz: Boolean = true)
    fun stopHotspot()
    fun clearPairing()
    fun sendClipboardNow()
    fun copyClipboardEntry(entry: ClipboardEntry)
    fun copyTextToClipboard(text: String)
    fun reconnectHid()
    fun sendMouseMove(dx: Int, dy: Int, wheel: Int = 0)
    fun sendMouseButtons(mask: Int)
    fun sendKey(modifiers: Int, hidUsage: Int, pressed: Boolean, linuxKeycode: Int? = null)
    fun sendConsumer(usage: Int)
}

object EcosystemStateHub {
    private val _snapshot = MutableStateFlow(EcosystemSnapshot())
    val snapshot: StateFlow<EcosystemSnapshot> = _snapshot.asStateFlow()

    private val _clipboardHistory = MutableStateFlow<List<ClipboardEntry>>(emptyList())
    val clipboardHistory: StateFlow<List<ClipboardEntry>> = _clipboardHistory.asStateFlow()

    @Volatile
    private var commands: EcosystemCommands? = null

    fun registerCommands(handler: EcosystemCommands) {
        commands = handler
    }

    fun unregisterCommands(handler: EcosystemCommands) {
        if (commands === handler) {
            commands = null
        }
    }

    internal fun update(transform: (EcosystemSnapshot) -> EcosystemSnapshot) {
        _snapshot.update(transform)
    }

    internal fun updateClipboardHistory(entries: List<ClipboardEntry>) {
        _clipboardHistory.value = entries
    }

    fun setError(message: String?) {
        _snapshot.update { it.copy(lastError = message) }
    }

    fun requestShizukuPermission() {
        commands?.requestShizukuPermission()
            ?: setError("Service not running yet")
    }

    fun startHotspot(prefer5Ghz: Boolean = true) {
        commands?.startHotspot(prefer5Ghz)
            ?: setError("Service not running yet")
    }

    fun stopHotspot() {
        commands?.stopHotspot()
    }

    fun clearPairing() {
        commands?.clearPairing()
    }

    fun sendClipboardNow() {
        commands?.sendClipboardNow()
            ?: setError("Service not running yet")
    }

    fun copyClipboardEntry(entry: ClipboardEntry) {
        commands?.copyClipboardEntry(entry)
    }

    fun copyTextToClipboard(text: String) {
        commands?.copyTextToClipboard(text)
    }

    fun reconnectHid() {
        commands?.reconnectHid()
    }

    fun sendMouseMove(dx: Int, dy: Int, wheel: Int = 0) {
        commands?.sendMouseMove(dx, dy, wheel)
    }

    fun sendMouseButtons(mask: Int) {
        commands?.sendMouseButtons(mask)
    }

    fun sendKey(modifiers: Int, hidUsage: Int, pressed: Boolean, linuxKeycode: Int? = null) {
        commands?.sendKey(modifiers, hidUsage, pressed, linuxKeycode)
    }

    fun sendConsumer(usage: Int) {
        commands?.sendConsumer(usage)
    }

    fun formatPublicKeyFingerprint(pubkey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(pubkey)
        return digest.take(16).joinToString(":") { b -> "%02X".format(b.toInt() and 0xFF) }
    }
}
