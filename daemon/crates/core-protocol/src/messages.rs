use bytes::{BufMut, BytesMut};
use thiserror::Error;

pub const MSG_CREDIT_GRANT: u8 = 0x01;
pub const MSG_HEARTBEAT: u8 = 0x02;
pub const MSG_AUTH_CHALLENGE: u8 = 0x03;
pub const MSG_AUTH_RESPONSE: u8 = 0x04;
pub const MSG_AUTH_RESULT: u8 = 0x05;
pub const MSG_STREAM_REQ: u8 = 0x10;
pub const MSG_STREAM_ACK: u8 = 0x11;
pub const MSG_STREAM_STOP: u8 = 0x12;
pub const MSG_STREAM_STOPPED: u8 = 0x13;
pub const MSG_ACK: u8 = 0x20;
pub const MSG_DEVICE_STATUS: u8 = 0x30;

pub const HEARTBEAT_PING: u8 = 0x01;
pub const HEARTBEAT_PONG: u8 = 0x02;

pub const AUTH_OK: u8 = 0x00;
pub const AUTH_REJECTED: u8 = 0x01;

pub const CODEC_H264: u8 = 0x01;
pub const CODEC_H265: u8 = 0x02;

pub const INPUT_MOUSE_REL: u8 = 0x01;
pub const INPUT_MOUSE_BTN: u8 = 0x02;
pub const INPUT_KEY_EVENT: u8 = 0x03;

pub const REMOTE_TOUCH: u8 = 0x01;
pub const REMOTE_SCROLL: u8 = 0x02;
pub const REMOTE_KEY: u8 = 0x03;
pub const REMOTE_TEXT: u8 = 0x04;

pub const FS_OP_STATFS: u8 = 0x01;
pub const FS_OP_GETATTR: u8 = 0x02;
pub const FS_OP_READDIR: u8 = 0x03;
pub const FS_OP_READ: u8 = 0x04;
pub const FS_OP_WRITE: u8 = 0x05;
pub const FS_OP_CREATE: u8 = 0x06;
pub const FS_OP_TRUNCATE: u8 = 0x07;
pub const FS_OP_MKDIR: u8 = 0x08;
pub const FS_OP_UNLINK: u8 = 0x09;
pub const FS_OP_RMDIR: u8 = 0x0A;
pub const FS_OP_RENAME: u8 = 0x0B;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum MessageError {
    #[error("invalid control message length")]
    InvalidLength,
    #[error("unknown message type: 0x{0:02x}")]
    UnknownType(u8),
    #[error("invalid utf-8 in metadata")]
    InvalidUtf8,
    #[error("invalid message field")]
    InvalidField,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum LinkStatus {
    Idle = 0x00,
    Starting = 0x01,
    Active = 0x02,
    ErrUnknown = 0x80,
    ErrShizukuUnavailable = 0x81,
    ErrProvisioningFailed = 0x82,
    ErrWifiHardware = 0x83,
    ErrAssociationTimeout = 0x84,
}

impl LinkStatus {
    pub fn as_u8(self) -> u8 {
        self as u8
    }
}

impl TryFrom<u8> for LinkStatus {
    type Error = MessageError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            0x00 => Ok(Self::Idle),
            0x01 => Ok(Self::Starting),
            0x02 => Ok(Self::Active),
            0x80 => Ok(Self::ErrUnknown),
            0x81 => Ok(Self::ErrShizukuUnavailable),
            0x82 => Ok(Self::ErrProvisioningFailed),
            0x83 => Ok(Self::ErrWifiHardware),
            0x84 => Ok(Self::ErrAssociationTimeout),
            _ => Err(MessageError::InvalidField),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ControlMessage {
    CreditGrant {
        target_channel: u8,
        credits_granted: u16,
        cumulative_bytes_consumed: u32,
    },
    Heartbeat {
        is_pong: bool,
        timestamp_ms: u32,
    },
    AuthChallenge {
        nonce: [u8; 32],
    },
    AuthResponse {
        pubkey: [u8; 32],
        signature: [u8; 64],
    },
    AuthResult {
        ok: bool,
    },
    StreamReq {
        max_fps: u16,
        bitrate_mbps: u8,
        codec: u8,
    },
    StreamAck {
        status: u8,
        width: u16,
        height: u16,
    },
    StreamStop,
    StreamStopped,
    Ack {
        stream_id: u16,
        sequence_number: u32,
    },
    DeviceStatus {
        battery_pct: u8,
        charging: bool,
        tethering: bool,
        hid_connected: bool,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StreamStartMeta {
    pub total_len: u64,
    pub mime: String,
    pub initial_data: BytesMut,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InputFallbackEvent {
    MouseRel { dx: i16, dy: i16, wheel: i8 },
    MouseBtn { button: u8, pressed: bool },
    KeyEvent { linux_keycode: u16, pressed: bool },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RemoteInputEvent {
    Touch {
        action: u8,
        pointer_id: u8,
        buttons: u8,
        x_norm: u16,
        y_norm: u16,
    },
    Scroll {
        x_norm: u16,
        y_norm: u16,
        h_scroll: i16,
        v_scroll: i16,
    },
    Key {
        action: u8,
        keycode: u16,
        metastate: u32,
        repeat: u16,
    },
    Text {
        text: String,
    },
}

impl ControlMessage {
    pub fn encode(&self) -> BytesMut {
        let mut buf = BytesMut::new();
        match self {
            Self::CreditGrant {
                target_channel,
                credits_granted,
                cumulative_bytes_consumed,
            } => {
                buf.reserve(8);
                buf.put_u8(MSG_CREDIT_GRANT);
                buf.put_u8(*target_channel);
                buf.put_u16(*credits_granted);
                buf.put_u32(*cumulative_bytes_consumed);
            }
            Self::Heartbeat {
                is_pong,
                timestamp_ms,
            } => {
                buf.reserve(8);
                buf.put_u8(MSG_HEARTBEAT);
                buf.put_u8(if *is_pong { HEARTBEAT_PONG } else { HEARTBEAT_PING });
                buf.put_u16(0);
                buf.put_u32(*timestamp_ms);
            }
            Self::AuthChallenge { nonce } => {
                buf.reserve(33);
                buf.put_u8(MSG_AUTH_CHALLENGE);
                buf.extend_from_slice(nonce);
            }
            Self::AuthResponse { pubkey, signature } => {
                buf.reserve(97);
                buf.put_u8(MSG_AUTH_RESPONSE);
                buf.extend_from_slice(pubkey);
                buf.extend_from_slice(signature);
            }
            Self::AuthResult { ok } => {
                buf.reserve(2);
                buf.put_u8(MSG_AUTH_RESULT);
                buf.put_u8(if *ok { AUTH_OK } else { AUTH_REJECTED });
            }
            Self::StreamReq {
                max_fps,
                bitrate_mbps,
                codec,
            } => {
                buf.reserve(5);
                buf.put_u8(MSG_STREAM_REQ);
                buf.put_u16(*max_fps);
                buf.put_u8(*bitrate_mbps);
                buf.put_u8(*codec);
            }
            Self::StreamAck {
                status,
                width,
                height,
            } => {
                buf.reserve(6);
                buf.put_u8(MSG_STREAM_ACK);
                buf.put_u8(*status);
                buf.put_u16(*width);
                buf.put_u16(*height);
            }
            Self::StreamStop => {
                buf.reserve(1);
                buf.put_u8(MSG_STREAM_STOP);
            }
            Self::StreamStopped => {
                buf.reserve(1);
                buf.put_u8(MSG_STREAM_STOPPED);
            }
            Self::Ack {
                stream_id,
                sequence_number,
            } => {
                buf.reserve(7);
                buf.put_u8(MSG_ACK);
                buf.put_u16(*stream_id);
                buf.put_u32(*sequence_number);
            }
            Self::DeviceStatus {
                battery_pct,
                charging,
                tethering,
                hid_connected,
            } => {
                buf.reserve(5);
                buf.put_u8(MSG_DEVICE_STATUS);
                buf.put_u8(*battery_pct);
                buf.put_u8(u8::from(*charging));
                buf.put_u8(u8::from(*tethering));
                buf.put_u8(u8::from(*hid_connected));
            }
        }
        buf
    }

    pub fn decode(buf: &[u8]) -> Result<Self, MessageError> {
        if buf.is_empty() {
            return Err(MessageError::InvalidLength);
        }

        match buf[0] {
            MSG_CREDIT_GRANT => {
                if buf.len() != 8 {
                    return Err(MessageError::InvalidLength);
                }
                Ok(Self::CreditGrant {
                    target_channel: buf[1],
                    credits_granted: u16::from_be_bytes([buf[2], buf[3]]),
                    cumulative_bytes_consumed: u32::from_be_bytes([buf[4], buf[5], buf[6], buf[7]]),
                })
            }
            MSG_HEARTBEAT => {
                if buf.len() != 8 {
                    return Err(MessageError::InvalidLength);
                }
                let is_pong = match buf[1] {
                    HEARTBEAT_PING => false,
                    HEARTBEAT_PONG => true,
                    _ => return Err(MessageError::InvalidField),
                };
                Ok(Self::Heartbeat {
                    is_pong,
                    timestamp_ms: u32::from_be_bytes([buf[4], buf[5], buf[6], buf[7]]),
                })
            }
            MSG_AUTH_CHALLENGE => {
                if buf.len() != 33 {
                    return Err(MessageError::InvalidLength);
                }
                let mut nonce = [0u8; 32];
                nonce.copy_from_slice(&buf[1..33]);
                Ok(Self::AuthChallenge { nonce })
            }
            MSG_AUTH_RESPONSE => {
                if buf.len() != 97 {
                    return Err(MessageError::InvalidLength);
                }
                let mut pubkey = [0u8; 32];
                let mut signature = [0u8; 64];
                pubkey.copy_from_slice(&buf[1..33]);
                signature.copy_from_slice(&buf[33..97]);
                Ok(Self::AuthResponse { pubkey, signature })
            }
            MSG_AUTH_RESULT => {
                if buf.len() != 2 {
                    return Err(MessageError::InvalidLength);
                }
                let ok = match buf[1] {
                    AUTH_OK => true,
                    AUTH_REJECTED => false,
                    _ => return Err(MessageError::InvalidField),
                };
                Ok(Self::AuthResult { ok })
            }
            MSG_STREAM_REQ => {
                if buf.len() != 5 {
                    return Err(MessageError::InvalidLength);
                }
                Ok(Self::StreamReq {
                    max_fps: u16::from_be_bytes([buf[1], buf[2]]),
                    bitrate_mbps: buf[3],
                    codec: buf[4],
                })
            }
            MSG_STREAM_ACK => {
                if buf.len() != 6 {
                    return Err(MessageError::InvalidLength);
                }
                Ok(Self::StreamAck {
                    status: buf[1],
                    width: u16::from_be_bytes([buf[2], buf[3]]),
                    height: u16::from_be_bytes([buf[4], buf[5]]),
                })
            }
            MSG_STREAM_STOP => {
                if buf.len() != 1 {
                    return Err(MessageError::InvalidLength);
                }
                Ok(Self::StreamStop)
            }
            MSG_STREAM_STOPPED => {
                if buf.len() != 1 {
                    return Err(MessageError::InvalidLength);
                }
                Ok(Self::StreamStopped)
            }
            MSG_ACK => {
                if buf.len() != 7 {
                    return Err(MessageError::InvalidLength);
                }
                Ok(Self::Ack {
                    stream_id: u16::from_be_bytes([buf[1], buf[2]]),
                    sequence_number: u32::from_be_bytes([buf[3], buf[4], buf[5], buf[6]]),
                })
            }
            MSG_DEVICE_STATUS => {
                if buf.len() != 5 {
                    return Err(MessageError::InvalidLength);
                }
                Ok(Self::DeviceStatus {
                    battery_pct: buf[1],
                    charging: buf[2] != 0,
                    tethering: buf[3] != 0,
                    hid_connected: buf[4] != 0,
                })
            }
            other => Err(MessageError::UnknownType(other)),
        }
    }
}

impl StreamStartMeta {
    pub fn encode(&self) -> Result<BytesMut, MessageError> {
        let mime_bytes = self.mime.as_bytes();
        let mime_len = u16::try_from(mime_bytes.len()).map_err(|_| MessageError::InvalidLength)?;
        let mut buf = BytesMut::with_capacity(10 + mime_bytes.len() + self.initial_data.len());
        buf.put_u64(self.total_len);
        buf.put_u16(mime_len);
        buf.extend_from_slice(mime_bytes);
        buf.extend_from_slice(&self.initial_data);
        Ok(buf)
    }

    pub fn decode(buf: &[u8]) -> Result<Self, MessageError> {
        if buf.len() < 10 {
            return Err(MessageError::InvalidLength);
        }
        let total_len = u64::from_be_bytes(buf[0..8].try_into().unwrap());
        let mime_len = u16::from_be_bytes([buf[8], buf[9]]) as usize;
        if buf.len() - 10 < mime_len {
            return Err(MessageError::InvalidLength);
        }
        let mime = std::str::from_utf8(&buf[10..10 + mime_len])
            .map_err(|_| MessageError::InvalidUtf8)?
            .to_owned();
        let initial_data = BytesMut::from(&buf[10 + mime_len..]);
        Ok(Self {
            total_len,
            mime,
            initial_data,
        })
    }
}

impl InputFallbackEvent {
    pub fn encode(&self) -> BytesMut {
        let mut buf = BytesMut::new();
        match self {
            Self::MouseRel { dx, dy, wheel } => {
                buf.reserve(6);
                buf.put_u8(INPUT_MOUSE_REL);
                buf.put_i16(*dx);
                buf.put_i16(*dy);
                buf.put_i8(*wheel);
            }
            Self::MouseBtn { button, pressed } => {
                buf.reserve(3);
                buf.put_u8(INPUT_MOUSE_BTN);
                buf.put_u8(*button);
                buf.put_u8(u8::from(*pressed));
            }
            Self::KeyEvent {
                linux_keycode,
                pressed,
            } => {
                buf.reserve(4);
                buf.put_u8(INPUT_KEY_EVENT);
                buf.put_u16(*linux_keycode);
                buf.put_u8(u8::from(*pressed));
            }
        }
        buf
    }

    pub fn decode(buf: &[u8]) -> Result<Self, MessageError> {
        if buf.is_empty() {
            return Err(MessageError::InvalidLength);
        }

        match buf[0] {
            INPUT_MOUSE_REL => {
                if buf.len() != 6 {
                    return Err(MessageError::InvalidLength);
                }
                Ok(Self::MouseRel {
                    dx: i16::from_be_bytes([buf[1], buf[2]]),
                    dy: i16::from_be_bytes([buf[3], buf[4]]),
                    wheel: buf[5] as i8,
                })
            }
            INPUT_MOUSE_BTN => {
                if buf.len() != 3 {
                    return Err(MessageError::InvalidLength);
                }
                Ok(Self::MouseBtn {
                    button: buf[1],
                    pressed: buf[2] != 0,
                })
            }
            INPUT_KEY_EVENT => {
                if buf.len() != 4 {
                    return Err(MessageError::InvalidLength);
                }
                Ok(Self::KeyEvent {
                    linux_keycode: u16::from_be_bytes([buf[1], buf[2]]),
                    pressed: buf[3] != 0,
                })
            }
            other => Err(MessageError::UnknownType(other)),
        }
    }
}

impl RemoteInputEvent {
    pub fn encode(&self) -> Result<BytesMut, MessageError> {
        let mut buf = BytesMut::new();
        match self {
            Self::Touch {
                action,
                pointer_id,
                buttons,
                x_norm,
                y_norm,
            } => {
                buf.reserve(8);
                buf.put_u8(REMOTE_TOUCH);
                buf.put_u8(*action);
                buf.put_u8(*pointer_id);
                buf.put_u8(*buttons);
                buf.put_u16(*x_norm);
                buf.put_u16(*y_norm);
            }
            Self::Scroll {
                x_norm,
                y_norm,
                h_scroll,
                v_scroll,
            } => {
                buf.reserve(9);
                buf.put_u8(REMOTE_SCROLL);
                buf.put_u16(*x_norm);
                buf.put_u16(*y_norm);
                buf.put_i16(*h_scroll);
                buf.put_i16(*v_scroll);
            }
            Self::Key {
                action,
                keycode,
                metastate,
                repeat,
            } => {
                buf.reserve(10);
                buf.put_u8(REMOTE_KEY);
                buf.put_u8(*action);
                buf.put_u16(*keycode);
                buf.put_u32(*metastate);
                buf.put_u16(*repeat);
            }
            Self::Text { text } => {
                let raw = text.as_bytes();
                if raw.len() > 1024 {
                    return Err(MessageError::InvalidLength);
                }
                buf.reserve(1 + raw.len());
                buf.put_u8(REMOTE_TEXT);
                buf.extend_from_slice(raw);
            }
        }
        Ok(buf)
    }

    pub fn decode(buf: &[u8]) -> Result<Self, MessageError> {
        if buf.is_empty() {
            return Err(MessageError::InvalidLength);
        }
        match buf[0] {
            REMOTE_TOUCH => {
                if buf.len() != 8 {
                    return Err(MessageError::InvalidLength);
                }
                Ok(Self::Touch {
                    action: buf[1],
                    pointer_id: buf[2],
                    buttons: buf[3],
                    x_norm: u16::from_be_bytes([buf[4], buf[5]]),
                    y_norm: u16::from_be_bytes([buf[6], buf[7]]),
                })
            }
            REMOTE_SCROLL => {
                if buf.len() != 9 {
                    return Err(MessageError::InvalidLength);
                }
                Ok(Self::Scroll {
                    x_norm: u16::from_be_bytes([buf[1], buf[2]]),
                    y_norm: u16::from_be_bytes([buf[3], buf[4]]),
                    h_scroll: i16::from_be_bytes([buf[5], buf[6]]),
                    v_scroll: i16::from_be_bytes([buf[7], buf[8]]),
                })
            }
            REMOTE_KEY => {
                if buf.len() != 10 {
                    return Err(MessageError::InvalidLength);
                }
                Ok(Self::Key {
                    action: buf[1],
                    keycode: u16::from_be_bytes([buf[2], buf[3]]),
                    metastate: u32::from_be_bytes([buf[4], buf[5], buf[6], buf[7]]),
                    repeat: u16::from_be_bytes([buf[8], buf[9]]),
                })
            }
            REMOTE_TEXT => {
                if buf.len() > 1025 {
                    return Err(MessageError::InvalidLength);
                }
                let text = std::str::from_utf8(&buf[1..])
                    .map_err(|_| MessageError::InvalidUtf8)?
                    .to_owned();
                Ok(Self::Text { text })
            }
            other => Err(MessageError::UnknownType(other)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn all_control_messages_roundtrip() {
        let msgs = vec![
            ControlMessage::CreditGrant {
                target_channel: 0x02,
                credits_granted: 32,
                cumulative_bytes_consumed: 524_288,
            },
            ControlMessage::Heartbeat {
                is_pong: false,
                timestamp_ms: 123_456_789,
            },
            ControlMessage::Heartbeat {
                is_pong: true,
                timestamp_ms: 987_654_321,
            },
            ControlMessage::AuthChallenge { nonce: [0x11; 32] },
            ControlMessage::AuthResponse {
                pubkey: [0x22; 32],
                signature: [0x33; 64],
            },
            ControlMessage::AuthResult { ok: true },
            ControlMessage::AuthResult { ok: false },
            ControlMessage::StreamReq {
                max_fps: 120,
                bitrate_mbps: 25,
                codec: CODEC_H265,
            },
            ControlMessage::StreamAck {
                status: 0,
                width: 1920,
                height: 1080,
            },
            ControlMessage::StreamStop,
            ControlMessage::StreamStopped,
            ControlMessage::Ack {
                stream_id: 99,
                sequence_number: 42,
            },
            ControlMessage::DeviceStatus {
                battery_pct: 87,
                charging: true,
                tethering: false,
                hid_connected: true,
            },
        ];

        for msg in msgs {
            let encoded = msg.encode();
            let decoded = ControlMessage::decode(&encoded).unwrap();
            assert_eq!(decoded, msg);
        }
    }

    #[test]
    fn exact_wire_bytes_for_control_messages() {
        let cg = ControlMessage::CreditGrant {
            target_channel: 0x02,
            credits_granted: 0x0020,
            cumulative_bytes_consumed: 0x0008_0000,
        }
        .encode();
        assert_eq!(&cg[..], &[0x01, 0x02, 0x00, 0x20, 0x00, 0x08, 0x00, 0x00]);

        let ping = ControlMessage::Heartbeat {
            is_pong: false,
            timestamp_ms: 0x0102_0304,
        }
        .encode();
        assert_eq!(&ping[..], &[0x02, 0x01, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04]);

        let ack = ControlMessage::Ack {
            stream_id: 0x0102,
            sequence_number: 0x0304_0506,
        }
        .encode();
        assert_eq!(&ack[..], &[0x20, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06]);
    }

    #[test]
    fn reject_invalid_control_messages() {
        assert_eq!(ControlMessage::decode(&[]), Err(MessageError::InvalidLength));
        assert_eq!(
            ControlMessage::decode(&[0x99]),
            Err(MessageError::UnknownType(0x99))
        );
        assert_eq!(
            ControlMessage::decode(&[MSG_CREDIT_GRANT, 0x02, 0x00]),
            Err(MessageError::InvalidLength)
        );
        assert_eq!(
            ControlMessage::decode(&[MSG_HEARTBEAT, 0x03, 0, 0, 0, 0, 0, 1]),
            Err(MessageError::InvalidField)
        );
        assert_eq!(
            ControlMessage::decode(&[MSG_AUTH_RESULT, 0x02]),
            Err(MessageError::InvalidField)
        );
    }

    #[test]
    fn stream_start_meta_roundtrip_and_errors() {
        let meta = StreamStartMeta {
            total_len: 33_554_433,
            mime: "image/png".to_owned(),
            initial_data: BytesMut::from(&[0x89, 0x50, 0x4E, 0x47][..]),
        };
        let encoded = meta.encode().unwrap();
        assert_eq!(encoded.len(), 8 + 2 + 9 + 4);
        assert_eq!(&encoded[0..8], &33_554_433u64.to_be_bytes());
        assert_eq!(&encoded[8..10], &9u16.to_be_bytes());
        assert_eq!(&encoded[10..19], b"image/png");
        assert_eq!(&encoded[19..23], &[0x89, 0x50, 0x4E, 0x47]);

        let decoded = StreamStartMeta::decode(&encoded).unwrap();
        assert_eq!(decoded, meta);

        // Truncated header
        assert_eq!(
            StreamStartMeta::decode(&encoded[..9]),
            Err(MessageError::InvalidLength)
        );
        // Truncated mime
        assert_eq!(
            StreamStartMeta::decode(&encoded[..14]),
            Err(MessageError::InvalidLength)
        );
        // Invalid UTF-8 mime
        let mut bad_utf8 = encoded.clone();
        bad_utf8[10] = 0xFF;
        assert_eq!(
            StreamStartMeta::decode(&bad_utf8),
            Err(MessageError::InvalidUtf8)
        );
    }

    #[test]
    fn input_fallback_roundtrip_and_errors() {
        let events = [
            InputFallbackEvent::MouseRel {
                dx: -120,
                dy: 340,
                wheel: -1,
            },
            InputFallbackEvent::MouseBtn {
                button: 1,
                pressed: true,
            },
            InputFallbackEvent::KeyEvent {
                linux_keycode: 28,
                pressed: false,
            },
        ];

        for ev in events {
            let enc = ev.encode();
            assert_eq!(InputFallbackEvent::decode(&enc).unwrap(), ev);
        }

        assert_eq!(
            InputFallbackEvent::decode(&[INPUT_MOUSE_REL, 0, 1]),
            Err(MessageError::InvalidLength)
        );
        assert_eq!(
            InputFallbackEvent::decode(&[0x7F]),
            Err(MessageError::UnknownType(0x7F))
        );
    }

    #[test]
    fn remote_input_roundtrip_and_errors() {
        let events = [
            RemoteInputEvent::Touch {
                action: 0,
                pointer_id: 1,
                buttons: 1,
                x_norm: 32768,
                y_norm: 16384,
            },
            RemoteInputEvent::Scroll {
                x_norm: 1000,
                y_norm: 2000,
                h_scroll: -50,
                v_scroll: 100,
            },
            RemoteInputEvent::Key {
                action: 2,
                keycode: 4,
                metastate: 0,
                repeat: 0,
            },
            RemoteInputEvent::Text {
                text: "hola android".to_owned(),
            },
        ];
        for ev in events {
            let enc = ev.encode().unwrap();
            assert_eq!(RemoteInputEvent::decode(&enc).unwrap(), ev);
        }
        assert_eq!(
            RemoteInputEvent::decode(&[REMOTE_TOUCH, 0]),
            Err(MessageError::InvalidLength)
        );
    }

    #[test]
    fn link_status_roundtrip() {
        for status in [
            LinkStatus::Idle,
            LinkStatus::Starting,
            LinkStatus::Active,
            LinkStatus::ErrUnknown,
            LinkStatus::ErrShizukuUnavailable,
            LinkStatus::ErrProvisioningFailed,
            LinkStatus::ErrWifiHardware,
            LinkStatus::ErrAssociationTimeout,
        ] {
            assert_eq!(LinkStatus::try_from(status.as_u8()).unwrap(), status);
        }
        assert_eq!(LinkStatus::try_from(0x42), Err(MessageError::InvalidField));
    }
}
