package com.controid.ui

import com.controid.clipboard.ClipboardEntry
import com.controid.protocol.LinkStatus
import com.controid.service.ShizukuState

enum class ControidTab(val title: String) {
    DASHBOARD("Dashboard"),
    TRACKPAD("Trackpad"),
    CLIPBOARD("Clipboard")
}

data class EcosystemUiState(
    val serviceRunning: Boolean = false,
    val shizukuState: ShizukuState = ShizukuState.UNAVAILABLE,
    val shizukuInstalled: Boolean = false,
    val shizukuRunning: Boolean = false,
    val shizukuGranted: Boolean = false,
    val shizukuUid: Int? = null,
    val linkStatus: LinkStatus = LinkStatus.IDLE,
    val hotspotActive: Boolean = false,
    val hotspotSsid: String? = null,
    val hotspotBand5Ghz: Boolean = true,
    val bleAdvertising: Boolean = false,
    val connectedClients: Int = 0,
    val tcpConnected: Boolean = false,
    val tcpAuthenticated: Boolean = false,
    val peerAddress: String? = null,
    val pairedFingerprint: String? = null,
    val isPaired: Boolean = false,
    val streamActive: Boolean = false,
    val streamWidth: Int = 0,
    val streamHeight: Int = 0,
    val streamFps: Int = 0,
    val streamBitrateMbps: Int = 0,
    val streamCodec: String = "H.264",
    val hidConnected: Boolean = false,
    val fallbackInputActive: Boolean = false,
    val clipboardBackgroundGranted: Boolean = false,
    val clipboardEntries: List<ClipboardEntry> = emptyList(),
    val statusDetail: String? = null
)

internal fun formatByteSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
