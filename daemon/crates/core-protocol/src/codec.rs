use bytes::{Buf, BufMut, BytesMut};
use std::io::{Error, ErrorKind};
use tokio_util::codec::{Decoder, Encoder};

pub const MAGIC_BYTE: u8 = 0xAA;
pub const HEADER_SIZE: usize = 12;
pub const MAX_PAYLOAD_SIZE: usize = 16 * 1024;
pub const MAX_FRAME_SIZE: usize = HEADER_SIZE + MAX_PAYLOAD_SIZE;

pub const CHANNEL_CTRL: u8 = 0x00;
pub const CHANNEL_CLIP_META: u8 = 0x01;
pub const CHANNEL_CLIP_DATA: u8 = 0x02;
pub const CHANNEL_MEDIA_STREAM: u8 = 0x03;
pub const CHANNEL_INPUT_FALLBACK: u8 = 0x04;
pub const CHANNEL_REMOTE_INPUT: u8 = 0x05;
pub const CHANNEL_FILES: u8 = 0x06;

pub const FLAG_START: u16 = 0x0001;
pub const FLAG_END: u16 = 0x0002;
pub const FLAG_ABORT: u16 = 0x0004;
pub const FLAG_URGENT: u16 = 0x0008;
pub const FLAG_ACK_REQ: u16 = 0x0010;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub channel_id: u8,
    pub stream_id: u16,
    pub flags: u16,
    pub sequence_number: u32,
    pub payload: BytesMut,
}

impl Frame {
    pub fn new(
        channel_id: u8,
        stream_id: u16,
        flags: u16,
        sequence_number: u32,
        payload: impl Into<BytesMut>,
    ) -> Self {
        Self {
            channel_id,
            stream_id,
            flags,
            sequence_number,
            payload: payload.into(),
        }
    }

    pub fn ctrl(payload: impl Into<BytesMut>) -> Self {
        Self::new(CHANNEL_CTRL, 0, 0, 0, payload)
    }

    pub fn ctrl_urgent(payload: impl Into<BytesMut>) -> Self {
        Self::new(CHANNEL_CTRL, 0, FLAG_URGENT, 0, payload)
    }
}

#[derive(Debug, Default, Clone, Copy)]
pub struct ProtocolCodec;

impl ProtocolCodec {
    pub fn new() -> Self {
        Self
    }
}

impl Decoder for ProtocolCodec {
    type Item = Frame;
    type Error = Error;

    fn decode(&mut self, src: &mut BytesMut) -> Result<Option<Self::Item>, Self::Error> {
        if src.is_empty() {
            return Ok(None);
        }

        if src[0] != MAGIC_BYTE {
            return Err(Error::new(
                ErrorKind::InvalidData,
                format!("invalid magic byte: 0x{:02x}", src[0]),
            ));
        }

        if src.len() < HEADER_SIZE {
            src.reserve(HEADER_SIZE - src.len());
            return Ok(None);
        }

        let payload_len = u16::from_be_bytes([src[6], src[7]]) as usize;
        if payload_len > MAX_PAYLOAD_SIZE {
            return Err(Error::new(
                ErrorKind::InvalidData,
                format!("payload length {payload_len} exceeds max {MAX_PAYLOAD_SIZE}"),
            ));
        }

        let total_len = HEADER_SIZE + payload_len;
        if src.len() < total_len {
            src.reserve(total_len - src.len());
            return Ok(None);
        }

        let channel_id = src[1];
        let stream_id = u16::from_be_bytes([src[2], src[3]]);
        let flags = u16::from_be_bytes([src[4], src[5]]);
        let sequence_number = u32::from_be_bytes([src[8], src[9], src[10], src[11]]);

        src.advance(HEADER_SIZE);
        let payload = src.split_to(payload_len);

        Ok(Some(Frame {
            channel_id,
            stream_id,
            flags,
            sequence_number,
            payload,
        }))
    }
}

impl Encoder<Frame> for ProtocolCodec {
    type Error = Error;

    fn encode(&mut self, item: Frame, dst: &mut BytesMut) -> Result<(), Self::Error> {
        let payload_len = item.payload.len();
        if payload_len > MAX_PAYLOAD_SIZE {
            return Err(Error::new(
                ErrorKind::InvalidData,
                format!("payload length {payload_len} exceeds max {MAX_PAYLOAD_SIZE}"),
            ));
        }

        dst.reserve(HEADER_SIZE + payload_len);
        dst.put_u8(MAGIC_BYTE);
        dst.put_u8(item.channel_id);
        dst.put_u16(item.stream_id);
        dst.put_u16(item.flags);
        dst.put_u16(payload_len as u16);
        dst.put_u32(item.sequence_number);
        dst.extend_from_slice(&item.payload);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frame_roundtrip_and_zero_copy_split() {
        let mut codec = ProtocolCodec::new();
        let original = Frame::new(
            CHANNEL_CLIP_DATA,
            42,
            FLAG_START | FLAG_URGENT,
            7,
            BytesMut::from(&b"hello-world"[..]),
        );

        let mut wire = BytesMut::new();
        codec.encode(original.clone(), &mut wire).unwrap();

        assert_eq!(wire.len(), HEADER_SIZE + 11);
        assert_eq!(wire[0], MAGIC_BYTE);
        assert_eq!(wire[1], CHANNEL_CLIP_DATA);
        assert_eq!(&wire[2..4], &42u16.to_be_bytes());
        assert_eq!(&wire[4..6], &(FLAG_START | FLAG_URGENT).to_be_bytes());
        assert_eq!(&wire[6..8], &11u16.to_be_bytes());
        assert_eq!(&wire[8..12], &7u32.to_be_bytes());

        // Partial read returns None until full frame arrives
        let mut partial = wire.split_to(8);
        assert!(codec.decode(&mut partial).unwrap().is_none());
        partial.extend_from_slice(&wire);

        let decoded = codec.decode(&mut partial).unwrap().unwrap();
        assert_eq!(decoded, original);
        assert!(partial.is_empty());
    }

    #[test]
    fn reject_invalid_magic_immediately() {
        let mut codec = ProtocolCodec::new();
        let mut bad = BytesMut::from(&[0xABu8][..]);
        let err = codec.decode(&mut bad).unwrap_err();
        assert_eq!(err.kind(), ErrorKind::InvalidData);
    }

    #[test]
    fn reject_oversized_payload_before_allocating() {
        let mut codec = ProtocolCodec::new();
        let mut bad = BytesMut::with_capacity(HEADER_SIZE);
        bad.put_u8(MAGIC_BYTE);
        bad.put_u8(CHANNEL_CLIP_DATA);
        bad.put_u16(1);
        bad.put_u16(0);
        bad.put_u16((MAX_PAYLOAD_SIZE + 1) as u16);
        bad.put_u32(0);

        let err = codec.decode(&mut bad).unwrap_err();
        assert_eq!(err.kind(), ErrorKind::InvalidData);
    }

    #[test]
    fn reject_oversized_encode() {
        let mut codec = ProtocolCodec::new();
        let frame = Frame::new(
            CHANNEL_MEDIA_STREAM,
            1,
            0,
            0,
            BytesMut::from(vec![0u8; MAX_PAYLOAD_SIZE + 1].as_slice()),
        );
        let mut out = BytesMut::new();
        let err = codec.encode(frame, &mut out).unwrap_err();
        assert_eq!(err.kind(), ErrorKind::InvalidData);
    }

    #[test]
    fn max_payload_size_boundary_succeeds() {
        let mut codec = ProtocolCodec::new();
        let payload = vec![0x5Au8; MAX_PAYLOAD_SIZE];
        let frame = Frame::new(CHANNEL_CLIP_DATA, 0xFFFF, FLAG_END, u32::MAX, payload.as_slice());
        let mut wire = BytesMut::new();
        codec.encode(frame.clone(), &mut wire).unwrap();
        let decoded = codec.decode(&mut wire).unwrap().unwrap();
        assert_eq!(decoded, frame);
    }

    #[test]
    fn decode_multiple_concatenated_frames_zero_copy() {
        let mut codec = ProtocolCodec::new();
        let f1 = Frame::ctrl(BytesMut::from(&[0x05u8, 0x00u8][..]));
        let f2 = Frame::new(CHANNEL_MEDIA_STREAM, 3, FLAG_START, 0, BytesMut::from(&b"nal1"[..]));
        let f3 = Frame::new(CHANNEL_MEDIA_STREAM, 3, FLAG_END, 1, BytesMut::from(&b"nal2"[..]));

        let mut wire = BytesMut::new();
        codec.encode(f1.clone(), &mut wire).unwrap();
        codec.encode(f2.clone(), &mut wire).unwrap();
        codec.encode(f3.clone(), &mut wire).unwrap();

        assert_eq!(codec.decode(&mut wire).unwrap(), Some(f1));
        assert_eq!(codec.decode(&mut wire).unwrap(), Some(f2));
        assert_eq!(codec.decode(&mut wire).unwrap(), Some(f3));
        assert_eq!(codec.decode(&mut wire).unwrap(), None);
    }
}
