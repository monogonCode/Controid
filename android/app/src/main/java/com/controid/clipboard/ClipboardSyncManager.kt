package com.controid.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.controid.protocol.EchoHash
import com.controid.protocol.EchoSuppressor
import com.controid.protocol.Frame
import com.controid.protocol.Multiplexer
import com.controid.protocol.ProtocolConstants
import com.controid.protocol.StreamStartMeta
import com.controid.service.ExternalClipboardStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ClipboardDirection {
    INBOUND,
    OUTBOUND
}

enum class ClipboardContentType {
    TEXT,
    IMAGE,
    BINARY
}

data class ClipboardEntry(
    val id: String,
    val direction: ClipboardDirection,
    val contentType: ClipboardContentType,
    val mime: String,
    val sizeBytes: Long,
    val textPreview: String? = null,
    val fileUri: Uri? = null,
    val localFile: File? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

class ClipboardSyncManager(
    private val context: Context,
    private val multiplexerProvider: () -> Multiplexer?,
    private val shizukuGrantProvider: suspend () -> Boolean,
    private val externalClipboardReader: (suspend () -> String?)? = null,
    private val externalClipboardStreamReader: (suspend () -> ExternalClipboardStream?)? = null
) {
    private val clipboardManager: ClipboardManager by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    private val spoolDir = File(context.cacheDir, "spool")
    private val receivedDir = File(context.cacheDir, "clipboard_received")
    private val sessionId = UUID.randomUUID().toString().replace("-", "").take(8)

    private val echoSuppressor = EchoSuppressor()
    private val streamIdCounter = AtomicInteger(1)
    private val started = AtomicBoolean(false)
    private val sendMutex = Mutex()
    private val activeInbound = ConcurrentHashMap<Int, InboundStream>()

    @Volatile
    private var scope: CoroutineScope? = null

    private val _history = MutableStateFlow<List<ClipboardEntry>>(emptyList())
    val history: StateFlow<List<ClipboardEntry>> = _history.asStateFlow()

    private val mainHandler: Handler? by lazy {
        Looper.getMainLooper()?.let { Handler(it) }
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        scope?.launch(Dispatchers.IO) {
            runCatching { sendCurrentClipboardNow() }
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope

        newScope.launch {
            purgeSpoolDirectory()
            runCatching { shizukuGrantProvider() }
        }

        runOnMainOrDirect {
            runCatching { clipboardManager.addPrimaryClipChangedListener(clipListener) }
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return

        runOnMainOrDirect {
            runCatching { clipboardManager.removePrimaryClipChangedListener(clipListener) }
        }

        val currentScope = scope
        scope = null
        currentScope?.cancel()

        val sids = activeInbound.keys.toList()
        for (sid in sids) {
            cleanupInbound(sid)
        }
        purgeSpoolDirectory()
    }

    fun abortStream(streamId: Int) {
        cleanupInbound(streamId and 0xFFFF)
    }

    suspend fun handleIncomingFrame(frame: Frame) {
        try {
            if (frame.channelId != ProtocolConstants.CHANNEL_CLIP_META &&
                frame.channelId != ProtocolConstants.CHANNEL_CLIP_DATA
            ) {
                return
            }

            val sid = frame.streamId.toInt() and 0xFFFF
            val flags = frame.flags.toInt()
            val isStart = (flags and ProtocolConstants.FLAG_START.toInt()) != 0
            val isEnd = (flags and ProtocolConstants.FLAG_END.toInt()) != 0
            val isAbort = (flags and ProtocolConstants.FLAG_ABORT.toInt()) != 0

            if (isAbort) {
                withContext(Dispatchers.IO) { cleanupInbound(sid) }
                return
            }

            if (isStart) {
                withContext(Dispatchers.IO) {
                    cleanupInbound(sid)
                    val meta = try {
                        StreamStartMeta.decode(frame.payload, 0, frame.payloadLength)
                    } catch (_: Exception) {
                        return@withContext
                    }

                    if (meta.totalLen < 0L || meta.totalLen > ProtocolConstants.MAX_SPOOL_BYTES) {
                        return@withContext
                    }

                    val stream = openInboundStream(sid, meta) ?: return@withContext
                    activeInbound[sid] = stream

                    if (!stream.append(meta.initialData, 0, meta.initialData.size)) {
                        cleanupInbound(sid)
                        return@withContext
                    }

                    if (isEnd) {
                        activeInbound.remove(sid)
                        finalizeInbound(stream)
                    }
                }
                return
            }

            val stream = activeInbound[sid] ?: return
            withContext(Dispatchers.IO) {
                if (!stream.append(frame.payload, 0, frame.payloadLength)) {
                    cleanupInbound(sid)
                    return@withContext
                }
                if (isEnd) {
                    activeInbound.remove(sid)
                    finalizeInbound(stream)
                }
            }
        } finally {
            multiplexerProvider()?.recycle(frame)
        }
    }

    suspend fun onExternalClipboardText(text: String) {
        if (text.isEmpty()) return
        withContext(Dispatchers.IO) {
            sendMutex.withLock {
                val mux = multiplexerProvider() ?: return@withLock
                streamTextClipboard(mux, text)
            }
        }
    }

    suspend fun onExternalClipboardStream(stream: ExternalClipboardStream) {
        withContext(Dispatchers.IO) {
            stream.pfd.use { pfd ->
                if (isControidReceivedUri(stream.uriString)) return@withContext
                sendMutex.withLock {
                    val mux = multiplexerProvider()
                    if (mux == null) {
                        Log.w(TAG, "Dropped external clipboard stream (${stream.mime}): activeMultiplexer is null")
                        return@withLock
                    }
                    streamExternalPipe(mux, stream.mime, stream.uriString, pfd)
                }
            }
        }
    }

    suspend fun sendCurrentClipboardNow(): Boolean = withContext(Dispatchers.IO) {
        sendMutex.withLock {
            val mux = multiplexerProvider() ?: return@withLock false
            val clip = runCatching { clipboardManager.primaryClip }.getOrNull()
            if (clip != null && clip.itemCount > 0) {
                var fallbackText: String? = null
                for (i in 0 until clip.itemCount) {
                    val item = clip.getItemAt(i) ?: continue
                    val uri = item.uri
                    val itemText = (item.text ?: runCatching { item.coerceToText(context) }.getOrNull())
                        ?.toString()
                    if (uri != null) {
                        val mime = resolveMimeType(uri, clip)
                        val scheme = uri.scheme?.lowercase(Locale.ROOT)
                        val isLocalStreamUri = scheme == "content" || scheme == "file"
                        val shouldSendUri = mime.startsWith("image/", ignoreCase = true) ||
                            itemText.isNullOrBlank() ||
                            itemText == uri.toString() ||
                            (isLocalStreamUri && !mime.startsWith("text/", ignoreCase = true))
                        if (shouldSendUri) {
                            if (streamUriClipboard(mux, uri, mime)) {
                                return@withLock true
                            }
                        }
                    }
                    if (fallbackText.isNullOrEmpty() && !itemText.isNullOrEmpty()) {
                        fallbackText = itemText
                    }
                }
                if (!fallbackText.isNullOrEmpty()) {
                    return@withLock streamTextClipboard(mux, fallbackText)
                }
            }

            val extStream = runCatching { externalClipboardStreamReader?.invoke() }.getOrNull()
            if (extStream != null) {
                val sent = extStream.pfd.use { pfd ->
                    streamExternalPipe(mux, extStream.mime, extStream.uriString, pfd)
                }
                if (sent) return@withLock true
            }

            val extText = runCatching { externalClipboardReader?.invoke() }.getOrNull()
            if (extText.isNullOrEmpty()) return@withLock false
            streamTextClipboard(mux, extText)
        }
    }

    suspend fun copyEntryToClipboard(entry: ClipboardEntry): Boolean = withContext(Dispatchers.IO) {
        when (entry.contentType) {
            ClipboardContentType.TEXT -> {
                val text = entry.textPreview ?: return@withContext false
                echoSuppressor.record(EchoHash.computeTextEchoHash(text))
                val clip = ClipData.newPlainText("Controid", text)
                runCatching { clipboardManager.setPrimaryClip(clip) }.isSuccess
            }

            ClipboardContentType.IMAGE, ClipboardContentType.BINARY -> {
                val uri = entry.fileUri ?: entry.localFile?.let { buildShareableUri(it, entry.mime) }
                    ?: return@withContext false
                scanUriForHash(uri)?.let { scan ->
                    val hash = EchoHash.computeClipboardEchoHash(
                        isText = false,
                        totalLen = scan.totalLen,
                        head = scan.head,
                        tail = scan.tail
                    )
                    echoSuppressor.record(hash)
                }
                val clip = ClipData.newUri(context.contentResolver, "Controid", uri)
                runCatching { clipboardManager.setPrimaryClip(clip) }.isSuccess
            }
        }
    }

    private fun openInboundStream(sid: Int, meta: StreamStartMeta): InboundStream? {
        val isText = meta.mime.startsWith("text/", ignoreCase = true)
        val useDiskSpool = meta.totalLen > ProtocolConstants.RAM_THRESHOLD_BYTES

        return if (useDiskSpool) {
            if (!spoolDir.exists()) spoolDir.mkdirs()
            val spoolFile = File(spoolDir, "${sessionId}_${sid}.tmp")
            val channel = try {
                FileChannel.open(
                    spoolFile.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
            } catch (_: IOException) {
                spoolFile.delete()
                return null
            }
            InboundStream(
                streamId = sid,
                expectedTotalLen = meta.totalLen,
                mime = meta.mime,
                isText = false,
                ramStream = null,
                spoolFile = spoolFile,
                fileChannel = channel
            )
        } else {
            val initialCap = meta.totalLen.toInt().coerceIn(256, 1024 * 1024)
            InboundStream(
                streamId = sid,
                expectedTotalLen = meta.totalLen,
                mime = meta.mime,
                isText = isText,
                ramStream = ByteArrayOutputStream(initialCap),
                spoolFile = null,
                fileChannel = null
            )
        }
    }

    private fun finalizeInbound(stream: InboundStream) {
        try {
            stream.closeResources()

            if (stream.receivedBytes != stream.expectedTotalLen) {
                stream.deleteSpoolFile()
                return
            }

            if (stream.isText && stream.ramStream != null) {
                val bytes = stream.ramStream.toByteArray()
                val text = String(bytes, Charsets.UTF_8)
                val hash = EchoHash.computeTextEchoHash(text)
                echoSuppressor.record(hash)

                val clip = ClipData.newPlainText("Controid", text)
                runCatching { clipboardManager.setPrimaryClip(clip) }

                recordHistory(
                    ClipboardEntry(
                        id = "${sessionId}_${stream.streamId}_${System.nanoTime()}",
                        direction = ClipboardDirection.INBOUND,
                        contentType = ClipboardContentType.TEXT,
                        mime = stream.mime,
                        sizeBytes = stream.receivedBytes,
                        textPreview = text
                    )
                )
                return
            }

            val headSlice = stream.headSnapshot()
            val tailSlice = stream.tailSnapshot()
            val hash = EchoHash.computeClipboardEchoHash(
                isText = false,
                totalLen = stream.receivedBytes,
                head = headSlice,
                tail = tailSlice
            )
            echoSuppressor.record(hash)

            val committedFile = persistCommittedFile(stream) ?: return
            val shareUri = buildShareableUri(committedFile, stream.mime)
            if (shareUri != null) {
                val clip = ClipData.newUri(context.contentResolver, "Controid", shareUri)
                runCatching { clipboardManager.setPrimaryClip(clip) }
            }

            val contentType = if (stream.mime.startsWith("image/", ignoreCase = true)) {
                ClipboardContentType.IMAGE
            } else {
                ClipboardContentType.BINARY
            }

            recordHistory(
                ClipboardEntry(
                    id = "${sessionId}_${stream.streamId}_${System.nanoTime()}",
                    direction = ClipboardDirection.INBOUND,
                    contentType = contentType,
                    mime = stream.mime,
                    sizeBytes = stream.receivedBytes,
                    fileUri = shareUri,
                    localFile = committedFile
                )
            )
        } catch (_: Exception) {
            stream.closeResources()
            stream.deleteSpoolFile()
        }
    }

    private fun persistCommittedFile(stream: InboundStream): File? {
        if (!receivedDir.exists()) receivedDir.mkdirs()
        pruneOldReceivedFiles()

        val ext = extensionFromMime(stream.mime)
        val target = File(receivedDir, "clip_${System.currentTimeMillis()}_${stream.streamId}.$ext")

        val spool = stream.spoolFile
        if (spool != null && spool.exists()) {
            if (spool.renameTo(target)) {
                return target
            }
            return try {
                copyFileChunked(spool, target)
                spool.delete()
                target
            } catch (_: IOException) {
                spool.delete()
                target.delete()
                null
            }
        }

        val ram = stream.ramStream ?: return null
        return try {
            FileOutputStream(target).use { out ->
                ram.writeTo(out)
                out.flush()
            }
            target
        } catch (_: IOException) {
            target.delete()
            null
        }
    }

    private fun buildShareableUri(file: File, mime: String): Uri? {
        val authority = "${context.packageName}.fileprovider"
        val hasProvider = runCatching {
            context.packageManager.resolveContentProvider(authority, 0) != null
        }.getOrDefault(false)

        if (hasProvider) {
            val uri = runCatching { FileProvider.getUriForFile(context, authority, file) }.getOrNull()
            if (uri != null) return uri
        }

        if (mime.startsWith("image/", ignoreCase = true) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            return insertImageIntoMediaStore(file, mime)
        }

        return null
    }

    private fun insertImageIntoMediaStore(file: File, mime: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input ->
                    val buf = ByteArray(ProtocolConstants.MAX_PAYLOAD_SIZE)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                    }
                    out.flush()
                }
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private suspend fun streamTextClipboard(mux: Multiplexer, text: String): Boolean {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val totalLen = utf8.size.toLong()
        if (totalLen <= 0L || totalLen > ProtocolConstants.MAX_SPOOL_BYTES) return false

        val hash = EchoHash.computeTextEchoHash(text)
        if (echoSuppressor.shouldSuppress(hash)) return false

        val channel = if (utf8.size <= TEXT_META_CHANNEL_LIMIT) {
            ProtocolConstants.CHANNEL_CLIP_META
        } else {
            ProtocolConstants.CHANNEL_CLIP_DATA
        }

        val mime = "text/plain"
        val mimeLen = mime.toByteArray(Charsets.UTF_8).size
        val maxInitial = (ProtocolConstants.MAX_PAYLOAD_SIZE - 10 - mimeLen).coerceAtLeast(0)
        val firstChunkLen = utf8.size.coerceAtMost(maxInitial)
        val initialData = utf8.copyOfRange(0, firstChunkLen)
        val metaPayload = StreamStartMeta(totalLen, mime, initialData).encode()

        val sid = nextStreamId()
        var offset = firstChunkLen
        var seq = 0

        val startFlags = if (offset >= utf8.size) {
            (ProtocolConstants.FLAG_START.toInt() or ProtocolConstants.FLAG_END.toInt()).toShort()
        } else {
            ProtocolConstants.FLAG_START
        }

        try {
            mux.sendFrame(
                Frame(
                    channelId = channel,
                    streamId = sid,
                    flags = startFlags,
                    sequenceNumber = seq++,
                    payload = metaPayload
                )
            )

            while (offset < utf8.size) {
                val end = (offset + ProtocolConstants.MAX_PAYLOAD_SIZE).coerceAtMost(utf8.size)
                val chunk = utf8.copyOfRange(offset, end)
                offset = end
                val flags = if (offset >= utf8.size) ProtocolConstants.FLAG_END else 0.toShort()
                mux.sendFrame(
                    Frame(
                        channelId = channel,
                        streamId = sid,
                        flags = flags,
                        sequenceNumber = seq++,
                        payload = chunk
                    )
                )
            }
        } catch (e: Exception) {
            sendAbortFrame(mux, channel, sid)
            return false
        }

        recordHistory(
            ClipboardEntry(
                id = "${sessionId}_${sid}_${System.nanoTime()}",
                direction = ClipboardDirection.OUTBOUND,
                contentType = ClipboardContentType.TEXT,
                mime = mime,
                sizeBytes = totalLen,
                textPreview = text
            )
        )
        return true
    }

    private suspend fun streamUriClipboard(mux: Multiplexer, uri: Uri, rawMime: String): Boolean {
        val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return false
        return input.use { src ->
            streamBinarySource(mux = mux, input = src, rawMime = rawMime, recordUri = uri)
        }
    }

    private suspend fun streamExternalPipe(
        mux: Multiplexer,
        rawMime: String,
        uriString: String,
        pfd: ParcelFileDescriptor
    ): Boolean {
        val uri = uriString.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { src ->
            streamBinarySource(mux = mux, input = src, rawMime = rawMime, recordUri = uri)
        }
    }

    private suspend fun streamBinarySource(
        mux: Multiplexer,
        input: InputStream,
        rawMime: String,
        recordUri: Uri?
    ): Boolean {
        val sourceTemp = spoolInputToTempFile(input) ?: return false
        var transcodedFile: File? = null
        try {
            if (sourceTemp.totalLen <= 0L || sourceTemp.totalLen > ProtocolConstants.MAX_SPOOL_BYTES) {
                return false
            }
            val rawHash = EchoHash.computeClipboardEchoHash(
                isText = false,
                totalLen = sourceTemp.totalLen,
                head = sourceTemp.head,
                tail = sourceTemp.tail
            )
            if (echoSuppressor.shouldSuppress(rawHash)) return false

            var effectiveMime = resolveEffectiveMime(rawMime, sourceTemp.head)
            var sendFile = sourceTemp.file
            var sendLen = sourceTemp.totalLen
            var sendHash = rawHash

            if (shouldTranscodeToPng(effectiveMime, sourceTemp.totalLen)) {
                val pngScan = transcodeFileToPng(sourceTemp.file)
                if (pngScan != null) {
                    transcodedFile = pngScan.file
                    sendFile = pngScan.file
                    sendLen = pngScan.totalLen
                    sendHash = EchoHash.computeClipboardEchoHash(
                        isText = false,
                        totalLen = pngScan.totalLen,
                        head = pngScan.head,
                        tail = pngScan.tail
                    )
                    effectiveMime = "image/png"
                }
            }

            echoSuppressor.record(rawHash)
            if (!sendHash.contentEquals(rawHash)) {
                echoSuppressor.record(sendHash)
            }

            val mime = truncateMime(effectiveMime)
            val ok = streamFileToMultiplexer(
                mux = mux,
                file = sendFile,
                totalLen = sendLen,
                mime = mime,
                recordUri = recordUri
            )
            Log.i(TAG, "Streamed clipboard source mime=$mime bytes=$sendLen ok=$ok")
            return ok
        } finally {
            runCatching { transcodedFile?.delete() }
            runCatching { sourceTemp.file.delete() }
        }
    }

    private suspend fun streamFileToMultiplexer(
        mux: Multiplexer,
        file: File,
        totalLen: Long,
        mime: String,
        recordUri: Uri?
    ): Boolean {
        val mimeBytes = mime.toByteArray(Charsets.UTF_8)
        val maxInitial = (ProtocolConstants.MAX_PAYLOAD_SIZE - 10 - mimeBytes.size).coerceAtLeast(0)
        val sid = nextStreamId()
        val channel = ProtocolConstants.CHANNEL_CLIP_DATA

        val input = runCatching { FileInputStream(file) }.getOrNull() ?: return false
        try {
            input.use { stream ->
                val initialBuf = ByteArray(maxInitial)
                val initialRead = readUpTo(stream, initialBuf, maxInitial)
                val initialData = if (initialRead == initialBuf.size) {
                    initialBuf
                } else {
                    initialBuf.copyOf(initialRead)
                }

                var sentBytes = initialRead.toLong()
                var seq = 0
                val singleFrame = sentBytes >= totalLen
                val startFlags = if (singleFrame) {
                    (ProtocolConstants.FLAG_START.toInt() or ProtocolConstants.FLAG_END.toInt()).toShort()
                } else {
                    ProtocolConstants.FLAG_START
                }

                val metaPayload = StreamStartMeta(totalLen, mime, initialData).encode()
                mux.sendFrame(
                    Frame(
                        channelId = channel,
                        streamId = sid,
                        flags = startFlags,
                        sequenceNumber = seq++,
                        payload = metaPayload
                    )
                )

                if (!singleFrame) {
                    val chunkBuf = ByteArray(ProtocolConstants.MAX_PAYLOAD_SIZE)
                    while (sentBytes < totalLen) {
                        val toRead = (totalLen - sentBytes)
                            .coerceAtMost(ProtocolConstants.MAX_PAYLOAD_SIZE.toLong())
                            .toInt()
                        val read = readUpTo(stream, chunkBuf, toRead)
                        if (read <= 0) {
                            sendAbortFrame(mux, channel, sid)
                            return false
                        }
                        sentBytes += read
                        val isEnd = sentBytes >= totalLen
                        val flags = if (isEnd) ProtocolConstants.FLAG_END else 0.toShort()
                        mux.sendFrame(
                            Frame(
                                channelId = channel,
                                streamId = sid,
                                flags = flags,
                                sequenceNumber = seq++,
                                payload = chunkBuf.copyOf(read)
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            sendAbortFrame(mux, channel, sid)
            return false
        }

        val contentType = if (mime.startsWith("image/", ignoreCase = true)) {
            ClipboardContentType.IMAGE
        } else {
            ClipboardContentType.BINARY
        }

        recordHistory(
            ClipboardEntry(
                id = "${sessionId}_${sid}_${System.nanoTime()}",
                direction = ClipboardDirection.OUTBOUND,
                contentType = contentType,
                mime = mime,
                sizeBytes = totalLen,
                fileUri = recordUri
            )
        )
        return true
    }

    private fun spoolInputToTempFile(input: InputStream): TempSpoolScan? {
        if (!spoolDir.exists()) spoolDir.mkdirs()
        val tempFile = File(spoolDir, "out_${sessionId}_${System.nanoTime()}.tmp")
        return try {
            val scan = copyStreamToSpoolFile(input, tempFile)
            if (scan == null) {
                tempFile.delete()
                null
            } else {
                TempSpoolScan(
                    file = tempFile,
                    totalLen = scan.totalLen,
                    head = scan.head,
                    tail = scan.tail
                )
            }
        } catch (_: Exception) {
            tempFile.delete()
            null
        }
    }

    private fun transcodeFileToPng(sourceFile: File): TempSpoolScan? {
        if (!spoolDir.exists()) spoolDir.mkdirs()
        val pngFile = File(spoolDir, "png_${sessionId}_${System.nanoTime()}.tmp")
        var bitmap: Bitmap? = null
        return try {
            val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(sourceFile)) { decoder, info, _ ->
                val w = info.size.width.toLong()
                val h = info.size.height.toLong()
                if (w <= 0L || h <= 0L || w * h > MAX_IMAGE_TRANSCODE_PIXELS) {
                    throw IOException("Image exceeds transcode pixel limit: ${w}x${h}")
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            bitmap = decoded
            FileOutputStream(pngFile).use { out ->
                if (!decoded.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IOException("Bitmap.compress(PNG) returned false")
                }
                out.flush()
            }
            val scan = FileInputStream(pngFile).use { scanStreamOnly(it) } ?: return null
            TempSpoolScan(
                file = pngFile,
                totalLen = scan.totalLen,
                head = scan.head,
                tail = scan.tail
            )
        } catch (t: Throwable) {
            Log.w(TAG, "PNG transcode failed for ${sourceFile.name}", t)
            pngFile.delete()
            null
        } finally {
            runCatching { bitmap?.recycle() }
        }
    }

    private fun copyStreamToSpoolFile(input: InputStream, targetFile: File): UriScanResult? {
        val buf = ByteArray(ProtocolConstants.MAX_PAYLOAD_SIZE)
        val head = ByteArray(ProtocolConstants.ECHO_SLICE_LEN)
        var headLen = 0
        val tail = RollingTailBuffer(ProtocolConstants.ECHO_SLICE_LEN)
        var totalLen = 0L

        FileOutputStream(targetFile).use { out ->
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                if (totalLen + read > ProtocolConstants.MAX_SPOOL_BYTES) {
                    return null
                }
                out.write(buf, 0, read)
                if (headLen < head.size) {
                    val copyLen = (head.size - headLen).coerceAtMost(read)
                    System.arraycopy(buf, 0, head, headLen, copyLen)
                    headLen += copyLen
                }
                tail.append(buf, 0, read)
                totalLen += read
            }
            out.flush()
        }

        return UriScanResult(
            totalLen = totalLen,
            head = if (headLen == head.size) head else head.copyOf(headLen),
            tail = tail.toByteArray()
        )
    }

    private fun scanStreamOnly(input: InputStream): UriScanResult? {
        val buf = ByteArray(ProtocolConstants.MAX_PAYLOAD_SIZE)
        val head = ByteArray(ProtocolConstants.ECHO_SLICE_LEN)
        var headLen = 0
        val tail = RollingTailBuffer(ProtocolConstants.ECHO_SLICE_LEN)
        var totalLen = 0L
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            if (totalLen + read > ProtocolConstants.MAX_SPOOL_BYTES) {
                return null
            }
            if (headLen < head.size) {
                val copyLen = (head.size - headLen).coerceAtMost(read)
                System.arraycopy(buf, 0, head, headLen, copyLen)
                headLen += copyLen
            }
            tail.append(buf, 0, read)
            totalLen += read
        }
        return UriScanResult(
            totalLen = totalLen,
            head = if (headLen == head.size) head else head.copyOf(headLen),
            tail = tail.toByteArray()
        )
    }

    private fun scanUriForHash(uri: Uri): UriScanResult? {
        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return null
        return try {
            stream.use { scanStreamOnly(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun isControidReceivedUri(uriString: String): Boolean {
        if (uriString.isEmpty()) return false
        return uriString.startsWith("content://${context.packageName}.fileprovider/clipboard_received/")
    }

    private fun resolveEffectiveMime(rawMime: String, head: ByteArray): String {
        val clean = rawMime.substringBefore(';').trim().lowercase(Locale.ROOT)
        val sniffed = sniffImageMime(head)
        return when {
            clean.isEmpty() || clean == "*/*" || clean == "application/octet-stream" || clean == "image/*" ->
                sniffed ?: clean.ifEmpty { "application/octet-stream" }
            clean == "image/jpg" -> "image/jpeg"
            else -> clean
        }
    }

    private fun shouldTranscodeToPng(mime: String, totalLen: Long): Boolean {
        if (totalLen > MAX_IMAGE_TRANSCODE_SOURCE_BYTES) return false
        return mime.startsWith("image/") &&
            mime != "image/png" &&
            mime != "image/gif" &&
            mime != "image/svg+xml"
    }

    private fun sniffImageMime(head: ByteArray): String? {
        if (head.size >= 8 &&
            head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
            head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() &&
            head[4] == 0x0D.toByte() && head[5] == 0x0A.toByte() &&
            head[6] == 0x1A.toByte() && head[7] == 0x0A.toByte()
        ) {
            return "image/png"
        }
        if (head.size >= 3 &&
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        if (head.size >= 12 &&
            head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
            head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
            head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() &&
            head[10] == 'B'.code.toByte() && head[11] == 'P'.code.toByte()
        ) {
            return "image/webp"
        }
        if (head.size >= 6 &&
            head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() &&
            head[2] == 'F'.code.toByte() && head[3] == '8'.code.toByte() &&
            (head[4] == '7'.code.toByte() || head[4] == '9'.code.toByte()) &&
            head[5] == 'a'.code.toByte()
        ) {
            return "image/gif"
        }
        if (head.size >= 2 && head[0] == 'B'.code.toByte() && head[1] == 'M'.code.toByte()) {
            return "image/bmp"
        }
        if (head.size >= 12 &&
            head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
        ) {
            val brand = String(head, 8, 4, Charsets.US_ASCII)
            if (brand in setOf("heic", "heix", "heim", "heis", "mif1", "msf1")) {
                return "image/heic"
            }
        }
        return null
    }

    private data class TempSpoolScan(
        val file: File,
        val totalLen: Long,
        val head: ByteArray,
        val tail: ByteArray
    )

    private fun sendAbortFrame(mux: Multiplexer, channel: Byte, sid: Short) {
        mux.trySendUrgent(
            Frame(
                channelId = channel,
                streamId = sid,
                flags = (ProtocolConstants.FLAG_ABORT.toInt() or
                    ProtocolConstants.FLAG_URGENT.toInt()).toShort(),
                sequenceNumber = 0,
                payload = Frame.EMPTY_PAYLOAD,
                payloadLength = 0
            )
        )
    }

    private fun cleanupInbound(sid: Int) {
        val stream = activeInbound.remove(sid) ?: return
        stream.closeResources()
        stream.deleteSpoolFile()
    }

    private fun purgeSpoolDirectory() {
        runCatching {
            if (!spoolDir.exists()) return
            spoolDir.listFiles()?.forEach { file ->
                if (file.isFile) file.delete()
            }
        }
    }

    private fun pruneOldReceivedFiles() {
        runCatching {
            val files = receivedDir.listFiles()?.filter { it.isFile } ?: return
            if (files.size <= MAX_CACHED_RECEIVED_FILES) return
            files.sortedBy { it.lastModified() }
                .take(files.size - MAX_CACHED_RECEIVED_FILES)
                .forEach { it.delete() }
        }
    }

    private fun recordHistory(entry: ClipboardEntry) {
        _history.update { current ->
            (listOf(entry) + current).take(MAX_HISTORY_ITEMS)
        }
    }

    private fun resolveMimeType(uri: Uri, clip: ClipData): String {
        val fromResolver = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (!fromResolver.isNullOrBlank()) return fromResolver
        val desc = clip.description
        if (desc != null && desc.mimeTypeCount > 0) {
            for (i in 0 until desc.mimeTypeCount) {
                val m = desc.getMimeType(i)
                if (!m.isNullOrBlank() && m.startsWith("image/", ignoreCase = true) && m != "image/*") {
                    return m
                }
            }
            for (i in 0 until desc.mimeTypeCount) {
                val fromDesc = desc.getMimeType(i)
                if (!fromDesc.isNullOrBlank() && fromDesc != "*/*") return fromDesc
            }
        }
        return "application/octet-stream"
    }

    private fun truncateMime(mime: String): String {
        val bytes = mime.toByteArray(Charsets.UTF_8)
        return if (bytes.size <= 255) mime else "application/octet-stream"
    }

    private fun extensionFromMime(mime: String): String {
        val clean = mime.substringBefore(';').trim()
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(clean)
        if (!ext.isNullOrBlank()) return ext
        return when {
            clean.startsWith("image/png") -> "png"
            clean.startsWith("image/jpeg") -> "jpg"
            clean.startsWith("image/webp") -> "webp"
            else -> "bin"
        }
    }

    private fun copyFileChunked(src: File, dst: File) {
        val buf = ByteArray(ProtocolConstants.MAX_PAYLOAD_SIZE)
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { out ->
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                }
                out.flush()
            }
        }
    }

    private fun readUpTo(input: InputStream, dst: ByteArray, length: Int): Int {
        var total = 0
        while (total < length) {
            val read = input.read(dst, total, length - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun nextStreamId(): Short {
        while (true) {
            val current = streamIdCounter.get()
            val next = if (current >= 0x7FFF) 1 else current + 1
            if (streamIdCounter.compareAndSet(current, next)) {
                return current.toShort()
            }
        }
    }

    private fun runOnMainOrDirect(block: () -> Unit) {
        val handler = mainHandler
        if (handler == null || Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            handler.post(block)
        }
    }

    private class UriScanResult(
        val totalLen: Long,
        val head: ByteArray,
        val tail: ByteArray
    )

    private class InboundStream(
        val streamId: Int,
        val expectedTotalLen: Long,
        val mime: String,
        val isText: Boolean,
        val ramStream: ByteArrayOutputStream?,
        val spoolFile: File?,
        val fileChannel: FileChannel?
    ) {
        private val headBytes = ByteArray(ProtocolConstants.ECHO_SLICE_LEN)
        private var headLen = 0
        private val tailBuffer = RollingTailBuffer(ProtocolConstants.ECHO_SLICE_LEN)
        var receivedBytes: Long = 0L
            private set

        fun append(src: ByteArray, offset: Int, length: Int): Boolean {
            if (length < 0 || offset < 0 || offset + length > src.size) return false
            if (receivedBytes + length > ProtocolConstants.MAX_SPOOL_BYTES) return false
            if (length == 0) return true

            if (headLen < headBytes.size) {
                val copyLen = (headBytes.size - headLen).coerceAtMost(length)
                System.arraycopy(src, offset, headBytes, headLen, copyLen)
                headLen += copyLen
            }
            tailBuffer.append(src, offset, length)

            if (fileChannel != null) {
                val bb = ByteBuffer.wrap(src, offset, length)
                while (bb.hasRemaining()) {
                    fileChannel.write(bb)
                }
            } else {
                ramStream?.write(src, offset, length)
            }

            receivedBytes += length
            return true
        }

        fun headSnapshot(): ByteArray =
            if (headLen == headBytes.size) headBytes else headBytes.copyOf(headLen)

        fun tailSnapshot(): ByteArray = tailBuffer.toByteArray()

        fun closeResources() {
            runCatching { fileChannel?.close() }
        }

        fun deleteSpoolFile() {
            runCatching { spoolFile?.delete() }
        }
    }

    private class RollingTailBuffer(private val capacity: Int) {
        private val buffer = ByteArray(capacity)
        private var count = 0
        private var writeIdx = 0

        fun append(src: ByteArray, offset: Int, length: Int) {
            if (length <= 0 || capacity <= 0) return
            if (length >= capacity) {
                System.arraycopy(src, offset + length - capacity, buffer, 0, capacity)
                count = capacity
                writeIdx = 0
                return
            }
            val firstPart = (capacity - writeIdx).coerceAtMost(length)
            System.arraycopy(src, offset, buffer, writeIdx, firstPart)
            val remaining = length - firstPart
            if (remaining > 0) {
                System.arraycopy(src, offset + firstPart, buffer, 0, remaining)
            }
            writeIdx = (writeIdx + length) % capacity
            count = (count + length).coerceAtMost(capacity)
        }

        fun toByteArray(): ByteArray {
            if (count == 0) return ByteArray(0)
            if (count < capacity) {
                return buffer.copyOf(count)
            }
            val out = ByteArray(capacity)
            val tailPart = capacity - writeIdx
            System.arraycopy(buffer, writeIdx, out, 0, tailPart)
            if (writeIdx > 0) {
                System.arraycopy(buffer, 0, out, tailPart, writeIdx)
            }
            return out
        }
    }

    companion object {
        private const val TAG = "ClipboardSyncManager"
        private const val TEXT_META_CHANNEL_LIMIT = 32 * 1024
        private const val MAX_HISTORY_ITEMS = 25
        private const val MAX_CACHED_RECEIVED_FILES = 10
        private const val MAX_IMAGE_TRANSCODE_SOURCE_BYTES = 24L * 1024L * 1024L
        private const val MAX_IMAGE_TRANSCODE_PIXELS = 36_000_000L
    }
}
