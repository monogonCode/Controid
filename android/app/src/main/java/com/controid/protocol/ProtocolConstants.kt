package com.controid.protocol

object ProtocolConstants {
    const val MAGIC_BYTE: Byte = 0xAA.toByte()
    const val HEADER_SIZE: Int = 12
    const val MAX_PAYLOAD_SIZE: Int = 16 * 1024
    const val MAX_FRAME_SIZE: Int = HEADER_SIZE + MAX_PAYLOAD_SIZE
    const val INITIAL_WINDOW_CREDITS: Int = 64
    const val CREDIT_THRESHOLD_BYTES: Int = 512 * 1024
    const val RAM_THRESHOLD_BYTES: Long = 32L * 1024L * 1024L
    const val MAX_SPOOL_BYTES: Long = 512L * 1024L * 1024L
    const val DEFAULT_PORT: Int = 55777

    const val HEARTBEAT_INTERVAL_MS: Long = 5_000L
    const val STREAM_TOMBSTONE_TTL_MS: Long = 5_000L
    const val STREAM_INACTIVE_TIMEOUT_MS: Long = 10_000L
    const val ECHO_TTL_MS: Long = 2_000L
    const val ECHO_SLICE_LEN: Int = 1024

    const val CHANNEL_CTRL: Byte = 0x00
    const val CHANNEL_CLIP_META: Byte = 0x01
    const val CHANNEL_CLIP_DATA: Byte = 0x02
    const val CHANNEL_MEDIA_STREAM: Byte = 0x03
    const val CHANNEL_INPUT_FALLBACK: Byte = 0x04
    const val CHANNEL_REMOTE_INPUT: Byte = 0x05
    const val CHANNEL_FILES: Byte = 0x06

    const val FLAG_START: Short = 0x0001
    const val FLAG_END: Short = 0x0002
    const val FLAG_ABORT: Short = 0x0004
    const val FLAG_URGENT: Short = 0x0008
    const val FLAG_ACK_REQ: Short = 0x0010

    const val MSG_CREDIT_GRANT: Byte = 0x01
    const val MSG_HEARTBEAT: Byte = 0x02
    const val MSG_AUTH_CHALLENGE: Byte = 0x03
    const val MSG_AUTH_RESPONSE: Byte = 0x04
    const val MSG_AUTH_RESULT: Byte = 0x05
    const val MSG_STREAM_REQ: Byte = 0x10
    const val MSG_STREAM_ACK: Byte = 0x11
    const val MSG_STREAM_STOP: Byte = 0x12
    const val MSG_STREAM_STOPPED: Byte = 0x13
    const val MSG_ACK: Byte = 0x20
    const val MSG_DEVICE_STATUS: Byte = 0x30

    const val HEARTBEAT_PING: Byte = 0x01
    const val HEARTBEAT_PONG: Byte = 0x02

    const val AUTH_OK: Byte = 0x00
    const val AUTH_REJECTED: Byte = 0x01

    const val CODEC_H264: Byte = 0x01
    const val CODEC_H265: Byte = 0x02

    const val INPUT_MOUSE_REL: Byte = 0x01
    const val INPUT_MOUSE_BTN: Byte = 0x02
    const val INPUT_KEY_EVENT: Byte = 0x03

    const val REMOTE_TOUCH: Byte = 0x01
    const val REMOTE_SCROLL: Byte = 0x02
    const val REMOTE_KEY: Byte = 0x03
    const val REMOTE_TEXT: Byte = 0x04

    const val FS_OP_STATFS: Byte = 0x01
    const val FS_OP_GETATTR: Byte = 0x02
    const val FS_OP_READDIR: Byte = 0x03
    const val FS_OP_READ: Byte = 0x04
    const val FS_OP_WRITE: Byte = 0x05
    const val FS_OP_CREATE: Byte = 0x06
    const val FS_OP_TRUNCATE: Byte = 0x07
    const val FS_OP_MKDIR: Byte = 0x08
    const val FS_OP_UNLINK: Byte = 0x09
    const val FS_OP_RMDIR: Byte = 0x0A
    const val FS_OP_RENAME: Byte = 0x0B
}
