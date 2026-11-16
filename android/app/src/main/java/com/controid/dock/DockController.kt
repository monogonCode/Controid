package com.controid.dock

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.controid.protocol.Frame
import com.controid.protocol.Multiplexer
import com.controid.protocol.ProtocolConstants
import com.controid.service.EcosystemStateHub
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class DockController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val multiplexerProvider: () -> Multiplexer?
) {
    private val inboundStreams = ConcurrentHashMap<Int, ByteArrayOutputStream>()
    private val nextStreamId = AtomicInteger(0x8001)
    private val iconsMap = ConcurrentHashMap<String, ImageBitmap>()
    private val requestedIconKeys = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var refreshTimeoutJob: Job? = null

    private val iconCacheDir: File by lazy {
        File(context.cacheDir, "dock_icons").apply { mkdirs() }
    }
    private val snapshotCacheFile: File by lazy {
        File(context.cacheDir, "dock_snapshot_cache.json")
    }

    init {
        scope.launch(Dispatchers.IO) {
            loadCachedSnapshot()
        }
    }

    suspend fun handleIncomingFrame(frame: Frame, multiplexer: Multiplexer) {
        try {
            if (frame.channelId != ProtocolConstants.CHANNEL_DOCK) return
            val sid = frame.streamId.toInt() and 0xFFFF
            val flags = frame.flags.toInt()
            val isStart = (flags and ProtocolConstants.FLAG_START.toInt()) != 0
            val isEnd = (flags and ProtocolConstants.FLAG_END.toInt()) != 0
            val isAbort = (flags and ProtocolConstants.FLAG_ABORT.toInt()) != 0

            if (isAbort) {
                inboundStreams.remove(sid)
                return
            }

            val payloadBytes: ByteArray? = when {
                isStart && isEnd -> {
                    inboundStreams.remove(sid)
                    frame.payload.copyOf(frame.payloadLength)
                }

                isStart -> {
                    if (frame.payloadLength > MAX_DOCK_MSG_BYTES) {
                        inboundStreams.remove(sid)
                        null
                    } else {
                        val baos = ByteArrayOutputStream(frame.payloadLength.coerceAtLeast(1024))
                        if (frame.payloadLength > 0) {
                            baos.write(frame.payload, 0, frame.payloadLength)
                        }
                        inboundStreams[sid] = baos
                        null
                    }
                }

                else -> {
                    val baos = inboundStreams[sid] ?: return
                    if (baos.size() + frame.payloadLength > MAX_DOCK_MSG_BYTES) {
                        inboundStreams.remove(sid)
                        null
                    } else {
                        if (frame.payloadLength > 0) {
                            baos.write(frame.payload, 0, frame.payloadLength)
                        }
                        if (isEnd) {
                            inboundStreams.remove(sid)
                            baos.toByteArray()
                        } else {
                            null
                        }
                    }
                }
            }

            if (payloadBytes != null && payloadBytes.isNotEmpty()) {
                processMessage(payloadBytes, multiplexer)
            }
        } finally {
            multiplexer.recycle(frame)
        }
    }

    fun abortStream(streamId: Int) {
        inboundStreams.remove(streamId and 0xFFFF)
    }

    fun reset() {
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = null
        inboundStreams.clear()
        requestedIconKeys.clear()
        EcosystemStateHub.updateDockSnapshot { it.copy(loading = false, syncError = null) }
        EcosystemStateHub.updateDockSpectrum(EMPTY_SPECTRUM)
    }

    fun requestRefresh(force: Boolean = false) {
        val mux = multiplexerProvider() ?: run {
            EcosystemStateHub.updateDockSnapshot { it.copy(loading = false) }
            return
        }
        refreshTimeoutJob?.cancel()
        EcosystemStateHub.updateDockSnapshot { it.copy(loading = true, syncError = null) }
        val json = JSONObject().put("force_refresh", force).toString()
        sendDockMessage(mux, ProtocolConstants.DOCK_LIST_REQ, json.toByteArray(StandardCharsets.UTF_8))
        refreshTimeoutJob = scope.launch(Dispatchers.IO) {
            delay(7_000L)
            EcosystemStateHub.updateDockSnapshot { current ->
                if (current.loading) {
                    current.copy(
                        loading = false,
                        syncError = "Sin respuesta del daemon Linux (verifica que controidd esté actualizado y conectado)."
                    )
                } else {
                    current
                }
            }
        }
    }

    fun launchApp(desktopId: String, action: String = "activate") {
        val mux = multiplexerProvider() ?: run {
            EcosystemStateHub.emitDockFeedback(
                DockLaunchFeedback(
                    desktopId = desktopId,
                    ok = false,
                    action = "error",
                    error = "Sin conexión con el PC"
                )
            )
            return
        }
        val json = JSONObject()
            .put("desktop_id", desktopId)
            .put("action", action)
            .toString()
        sendDockMessage(mux, ProtocolConstants.DOCK_LAUNCH_REQ, json.toByteArray(StandardCharsets.UTF_8))
    }

    fun setPinned(desktopId: String, pinned: Boolean) {
        val mux = multiplexerProvider() ?: return
        EcosystemStateHub.updateDockSnapshot { current ->
            val updated = if (pinned) {
                if (desktopId in current.pinnedIds) current.pinnedIds else current.pinnedIds + desktopId
            } else {
                current.pinnedIds.filterNot { it == desktopId }
            }
            current.copy(pinnedIds = updated)
        }
        val json = JSONObject()
            .put("desktop_id", desktopId)
            .put("pinned", pinned)
            .toString()
        sendDockMessage(mux, ProtocolConstants.DOCK_PIN_SET, json.toByteArray(StandardCharsets.UTF_8))
    }

    fun setPinnedOrder(pinnedIds: List<String>) {
        val mux = multiplexerProvider() ?: return
        val cleaned = pinnedIds.asSequence().filter { it.isNotBlank() }.distinct().toList()
        EcosystemStateHub.updateDockSnapshot { current ->
            current.copy(pinnedIds = cleaned)
        }
        val json = JSONObject()
            .put("pinned_ids", JSONArray(cleaned))
            .toString()
        sendDockMessage(mux, ProtocolConstants.DOCK_PIN_SET, json.toByteArray(StandardCharsets.UTF_8))
    }

    fun sendSystemAction(action: String, value: String? = null) {
        val mux = multiplexerProvider() ?: return
        val json = JSONObject().put("action", action)
        if (value != null) {
            json.put("value", value)
        }
        sendDockMessage(
            mux,
            ProtocolConstants.DOCK_SYSTEM_ACTION,
            json.toString().toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun processMessage(payload: ByteArray, multiplexer: Multiplexer) {
        val opcode = payload[0]
        if (opcode == ProtocolConstants.DOCK_MEDIA_SPECTRUM) {
            handleMediaSpectrum(payload)
            return
        }
        val body = payload.copyOfRange(1, payload.size)
        when (opcode) {
            ProtocolConstants.DOCK_SNAPSHOT -> handleSnapshot(body, multiplexer)
            ProtocolConstants.DOCK_RUNTIME_UPDATE -> handleRuntimeUpdate(body)
            ProtocolConstants.DOCK_ICON_RESP -> handleIconResponse(body)
            ProtocolConstants.DOCK_LAUNCH_ACK -> handleLaunchAck(body)
        }
    }

    private fun handleMediaSpectrum(payload: ByteArray) {
        if (payload.size <= 1) return
        val count = (payload.size - 1).coerceAtMost(SPECTRUM_BAR_COUNT)
        val bars = List(SPECTRUM_BAR_COUNT) { idx ->
            if (idx < count) {
                (payload[1 + idx].toInt() and 0xFF).coerceIn(0, 100)
            } else {
                0
            }
        }
        EcosystemStateHub.updateDockSpectrum(bars)
    }

    private fun handleSnapshot(body: ByteArray, multiplexer: Multiplexer) {
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = null
        val raw = String(body, StandardCharsets.UTF_8)
        runCatching { snapshotCacheFile.writeText(raw) }
        val parsed = parseSnapshotJson(raw) ?: return
        EcosystemStateHub.updateDockSnapshot {
            parsed.copy(loading = false, syncError = null, syncedAtMs = System.currentTimeMillis())
        }
        hydrateCachedIconsAndRequestMissing(parsed, multiplexer)
    }

    private fun handleRuntimeUpdate(body: ByteArray) {
        val obj = runCatching { JSONObject(String(body, StandardCharsets.UTF_8)) }.getOrNull() ?: return
        val desktopEnv = obj.optString("desktop_env", "")
        val sessionType = obj.optString("session_type", "")
        val supportsFocus = obj.optBoolean("supports_window_focus", false)
        val supportsWorkspaces = obj.optBoolean("supports_workspaces", false)
        val activeWs = obj.optString("active_workspace", "").ifBlank { null }
        val workspaces = parseWorkspaces(obj.optJSONArray("workspaces"))
        val pinnedIds = parseStringList(obj.optJSONArray("pinned_ids"))
        val frequentIds = parseStringList(obj.optJSONArray("frequent_ids"))
        val focusedId = obj.optString("focused_id", "").ifBlank { null }
        val runningObj = obj.optJSONObject("running_counts")
        val runningCounts = buildMap {
            if (runningObj != null) {
                val keys = runningObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, runningObj.optInt(key, 0))
                }
            }
        }
        val media = parseMedia(obj.optJSONObject("media"))

        EcosystemStateHub.updateDockSnapshot { current ->
            val updatedApps = current.apps.map { app ->
                app.copy(
                    runningCount = runningCounts[app.id] ?: 0,
                    focused = focusedId == app.id
                )
            }
            current.copy(
                desktopEnv = desktopEnv.ifBlank { current.desktopEnv },
                sessionType = sessionType.ifBlank { current.sessionType },
                supportsWindowFocus = supportsFocus,
                supportsWorkspaces = supportsWorkspaces,
                activeWorkspace = activeWs,
                workspaces = workspaces,
                pinnedIds = pinnedIds,
                frequentIds = frequentIds,
                media = media,
                apps = updatedApps,
                syncedAtMs = System.currentTimeMillis()
            )
        }
    }

    private fun handleIconResponse(body: ByteArray) {
        if (body.size < 4) return
        val bb = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
        val idLen = bb.short.toInt() and 0xFFFF
        if (idLen <= 0 || body.size < 2 + idLen + 2) return
        val idBytes = ByteArray(idLen)
        bb.get(idBytes)
        val appId = String(idBytes, StandardCharsets.UTF_8)

        val hashLen = bb.short.toInt() and 0xFFFF
        if (bb.remaining() < hashLen) return
        val hashBytes = ByteArray(hashLen)
        bb.get(hashBytes)
        val iconHash = String(hashBytes, StandardCharsets.UTF_8)

        val pngBytes = ByteArray(bb.remaining())
        bb.get(pngBytes)
        if (pngBytes.isEmpty()) return

        val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size) ?: return
        val img = bitmap.asImageBitmap()
        iconsMap[appId] = img
        EcosystemStateHub.updateDockIcons(iconsMap.toMap())

        scope.launch(Dispatchers.IO) {
            runCatching {
                cacheFileFor(appId, iconHash).writeBytes(pngBytes)
            }
        }
    }

    private fun handleLaunchAck(body: ByteArray) {
        val obj = runCatching { JSONObject(String(body, StandardCharsets.UTF_8)) }.getOrNull() ?: return
        val desktopId = obj.optString("desktop_id", "")
        val ok = obj.optBoolean("ok", false)
        val action = obj.optString("action", "")
        val error = obj.optString("error", "").ifBlank { null }
        EcosystemStateHub.emitDockFeedback(
            DockLaunchFeedback(
                desktopId = desktopId,
                ok = ok,
                action = action,
                error = error
            )
        )
    }

    private fun loadCachedSnapshot() {
        if (!snapshotCacheFile.isFile) return
        val raw = runCatching { snapshotCacheFile.readText() }.getOrNull() ?: return
        val parsed = parseSnapshotJson(raw) ?: return
        EcosystemStateHub.updateDockSnapshot {
            if (it.apps.isEmpty()) parsed.copy(loading = false) else it
        }
        var loadedAny = false
        for (app in parsed.apps) {
            val hash = app.iconHash ?: continue
            val f = cacheFileFor(app.id, hash)
            if (f.isFile) {
                val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
                if (bmp != null) {
                    iconsMap[app.id] = bmp.asImageBitmap()
                    loadedAny = true
                }
            }
        }
        if (loadedAny) {
            EcosystemStateHub.updateDockIcons(iconsMap.toMap())
        }
    }

    private fun hydrateCachedIconsAndRequestMissing(snapshot: DockSnapshotState, multiplexer: Multiplexer) {
        scope.launch(Dispatchers.IO) {
            val pinnedSet = snapshot.pinnedIds.toSet()
            val missingPrioritized = mutableListOf<String>()
            val missingRest = mutableListOf<String>()
            var loadedAny = false

            for (app in snapshot.apps) {
                if (!app.hasIcon) continue
                val hash = app.iconHash
                if (hash != null) {
                    val file = cacheFileFor(app.id, hash)
                    if (file.isFile) {
                        if (!iconsMap.containsKey(app.id)) {
                            val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                            if (bmp != null) {
                                iconsMap[app.id] = bmp.asImageBitmap()
                                loadedAny = true
                            }
                        }
                        continue
                    }
                }
                val requestKey = "${app.id}:${hash ?: "none"}"
                if (requestedIconKeys.add(requestKey)) {
                    if (app.id in pinnedSet || app.runningCount > 0) {
                        missingPrioritized.add(app.id)
                    } else {
                        missingRest.add(app.id)
                    }
                }
            }

            if (loadedAny) {
                EcosystemStateHub.updateDockIcons(iconsMap.toMap())
            }

            val allMissing = missingPrioritized + missingRest
            for (batch in allMissing.chunked(24)) {
                val req = JSONObject()
                    .put("ids", JSONArray(batch))
                    .put("size", 96)
                    .toString()
                sendDockMessage(
                    multiplexer,
                    ProtocolConstants.DOCK_ICON_REQ,
                    req.toByteArray(StandardCharsets.UTF_8)
                )
            }
        }
    }

    private fun cacheFileFor(appId: String, iconHash: String): File {
        val safeId = appId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeHash = iconHash.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(iconCacheDir, "${safeId}_${safeHash}.png")
    }

    private fun sendDockMessage(multiplexer: Multiplexer, opcode: Byte, body: ByteArray) {
        scope.launch(Dispatchers.IO) {
            val payload = ByteArray(1 + body.size)
            payload[0] = opcode
            System.arraycopy(body, 0, payload, 1, body.size)

            val sid = allocateStreamId().toShort()
            val maxChunk = ProtocolConstants.MAX_PAYLOAD_SIZE
            var offset = 0
            var seq = 0
            while (offset < payload.size) {
                val end = (offset + maxChunk).coerceAtMost(payload.size)
                var flags = ProtocolConstants.FLAG_URGENT.toInt()
                if (offset == 0) flags = flags or ProtocolConstants.FLAG_START.toInt()
                if (end >= payload.size) flags = flags or ProtocolConstants.FLAG_END.toInt()
                val chunk = payload.copyOfRange(offset, end)
                offset = end
                val frame = Frame(
                    channelId = ProtocolConstants.CHANNEL_DOCK,
                    streamId = sid,
                    flags = flags.toShort(),
                    sequenceNumber = seq++,
                    payload = chunk
                )
                runCatching { multiplexer.sendFrame(frame, highPriority = true) }
                    .onFailure { return@launch }
            }
        }
    }

    private fun allocateStreamId(): Int {
        while (true) {
            val cur = nextStreamId.get()
            val normalized = if (cur < 0x8001 || cur >= 0xFFFF) 0x8001 else cur
            val next = if (normalized >= 0xFFFE) 0x8001 else normalized + 1
            if (nextStreamId.compareAndSet(cur, next)) {
                return normalized
            }
        }
    }

    private fun parseSnapshotJson(raw: String): DockSnapshotState? {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val appsArr = obj.optJSONArray("apps") ?: JSONArray()
        val apps = buildList(appsArr.length()) {
            for (i in 0 until appsArr.length()) {
                val item = appsArr.optJSONObject(i) ?: continue
                add(
                    DockAppItem(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        genericName = item.optString("generic_name", "").ifBlank { null },
                        categories = parseStringList(item.optJSONArray("categories")),
                        iconHash = item.optString("icon_hash", "").ifBlank { null },
                        hasIcon = item.optBoolean("has_icon", false),
                        runningCount = item.optInt("running_count", 0),
                        focused = item.optBoolean("focused", false),
                        terminal = item.optBoolean("terminal", false),
                        launchCount = item.optInt("launch_count", 0),
                        lastLaunched = item.optLong("last_launched", 0L)
                    )
                )
            }
        }

        return DockSnapshotState(
            desktopEnv = obj.optString("desktop_env", ""),
            sessionType = obj.optString("session_type", ""),
            supportsWindowFocus = obj.optBoolean("supports_window_focus", false),
            supportsWorkspaces = obj.optBoolean("supports_workspaces", false),
            activeWorkspace = obj.optString("active_workspace", "").ifBlank { null },
            workspaces = parseWorkspaces(obj.optJSONArray("workspaces")),
            pinnedIds = parseStringList(obj.optJSONArray("pinned_ids")),
            frequentIds = parseStringList(obj.optJSONArray("frequent_ids")),
            apps = apps,
            media = parseMedia(obj.optJSONObject("media"))
        )
    }

    private fun parseWorkspaces(arr: JSONArray?): List<DockWorkspace> {
        if (arr == null) return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val w = arr.optJSONObject(i) ?: continue
                add(
                    DockWorkspace(
                        id = w.optString("id"),
                        name = w.optString("name"),
                        active = w.optBoolean("active", false),
                        windows = w.optInt("windows", 0)
                    )
                )
            }
        }
    }

    private fun parseMedia(obj: JSONObject?): DockMediaState? {
        if (obj == null) return null
        val title = obj.optString("title", "").trim()
        val artist = obj.optString("artist", "").trim()
        val rawVolume = if (obj.has("volume") && !obj.isNull("volume")) obj.opt("volume") else null
        val volume = (rawVolume as? Number)?.toInt()?.coerceIn(0, 150)
        val rawMuted = if (obj.has("muted") && !obj.isNull("muted")) obj.opt("muted") else null
        val muted = rawMuted as? Boolean
        if (title.isEmpty() && artist.isEmpty() && volume == null && muted == null) return null
        return DockMediaState(
            title = title,
            artist = artist,
            playing = obj.optBoolean("playing", false),
            volume = volume,
            muted = muted
        )
    }

    private fun parseStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotBlank()) add(s)
            }
        }
    }

    companion object {
        private const val MAX_DOCK_MSG_BYTES = 4 * 1024 * 1024
        private const val SPECTRUM_BAR_COUNT = 16
        private val EMPTY_SPECTRUM = List(SPECTRUM_BAR_COUNT) { 0 }
    }
}
