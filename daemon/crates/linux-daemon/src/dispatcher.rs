use crate::sender::PrioritySender;
use bytes::BytesMut;
use core_protocol::{
    CHANNEL_CLIP_DATA, CHANNEL_CLIP_META, CHANNEL_CTRL, CHANNEL_FILES, CHANNEL_INPUT_FALLBACK,
    CHANNEL_MEDIA_STREAM, ControlMessage, FLAG_ABORT, FLAG_END, FLAG_START, FlowController, Frame,
    INITIAL_WINDOW_CREDITS, IdentityKeyPair, ProtocolCodec,
};
use futures::{SinkExt, StreamExt};
use futures::stream::SplitStream;
use std::collections::HashMap;
use std::io::{self, ErrorKind};
use std::time::Duration;
use thiserror::Error;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::sync::{mpsc, watch};
use tokio::time::{Instant, MissedTickBehavior};
use tokio_util::codec::Framed;
use tracing::{debug, trace, warn};

pub const DEFAULT_STREAM_TIMEOUT: Duration = Duration::from_secs(10);
pub const DEFAULT_TOMBSTONE_TTL: Duration = Duration::from_secs(8);
pub const DEFAULT_SWEEP_INTERVAL: Duration = Duration::from_secs(1);
pub const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);

#[derive(Debug, Clone)]
pub struct ChannelRouter {
    pub ctrl_tx: mpsc::Sender<Frame>,
    pub clip_meta_tx: mpsc::Sender<Frame>,
    pub clip_data_tx: mpsc::Sender<Frame>,
    pub media_tx: mpsc::Sender<Frame>,
    pub input_tx: mpsc::Sender<Frame>,
    pub files_tx: mpsc::Sender<Frame>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StreamState {
    pub channel_id: u8,
    pub expected_seq: u32,
    pub started_at: Instant,
    pub last_activity: Instant,
}

#[derive(Debug, Error)]
pub enum HandshakeError {
    #[error("l7 handshake timed out")]
    Timeout,
    #[error("tcp connection closed during l7 handshake")]
    UnexpectedEof,
    #[error("protocol framing error during handshake: {0}")]
    Io(#[from] io::Error),
    #[error("unexpected frame on channel 0x{0:02x} during handshake")]
    UnexpectedChannel(u8),
    #[error("invalid control message during handshake: {0}")]
    InvalidControlMessage(#[from] core_protocol::MessageError),
    #[error("unexpected control message during handshake")]
    UnexpectedControlMessage,
    #[error("peer rejected authentication")]
    Rejected,
    #[error("peer public key mismatch")]
    PeerKeyMismatch,
}

#[derive(Debug, Error)]
pub enum DispatchError {
    #[error("fatal framing/protocol data error: {0}")]
    InvalidData(io::Error),
    #[error("transport i/o error: {0}")]
    Io(io::Error),
}

pub struct Dispatcher {
    router: ChannelRouter,
    sender: PrioritySender,
    active_streams: HashMap<u16, StreamState>,
    dead_streams: HashMap<u16, Instant>,
    inbound_clip_flow: FlowController,
    inbound_media_flow: FlowController,
    outbound_clip_flow: FlowController,
    stream_timeout: Duration,
    tombstone_ttl: Duration,
    sweep_interval: Duration,
}

impl Dispatcher {
    pub fn new(router: ChannelRouter, sender: PrioritySender) -> Self {
        Self {
            router,
            sender,
            active_streams: HashMap::new(),
            dead_streams: HashMap::new(),
            inbound_clip_flow: FlowController::new(INITIAL_WINDOW_CREDITS),
            inbound_media_flow: FlowController::new(INITIAL_WINDOW_CREDITS),
            outbound_clip_flow: FlowController::new(INITIAL_WINDOW_CREDITS),
            stream_timeout: DEFAULT_STREAM_TIMEOUT,
            tombstone_ttl: DEFAULT_TOMBSTONE_TTL,
            sweep_interval: DEFAULT_SWEEP_INTERVAL,
        }
    }

    pub fn with_outbound_clip_flow(mut self, outbound_clip_flow: FlowController) -> Self {
        self.outbound_clip_flow = outbound_clip_flow;
        self
    }

    pub fn with_timeouts(
        mut self,
        stream_timeout: Duration,
        tombstone_ttl: Duration,
        sweep_interval: Duration,
    ) -> Self {
        self.stream_timeout = stream_timeout;
        self.tombstone_ttl = tombstone_ttl;
        self.sweep_interval = sweep_interval;
        self
    }

    pub fn active_stream_count(&self) -> usize {
        self.active_streams.len()
    }

    pub fn is_tombstoned(&self, stream_id: u16, now: Instant) -> bool {
        self.dead_streams
            .get(&stream_id)
            .is_some_and(|expires_at| *expires_at > now)
    }

    pub async fn run<T>(
        mut self,
        mut stream: SplitStream<Framed<T, ProtocolCodec>>,
        mut shutdown: watch::Receiver<bool>,
    ) -> Result<(), DispatchError>
    where
        T: AsyncRead + AsyncWrite + Unpin,
    {
        let mut ticker = tokio::time::interval(self.sweep_interval);
        ticker.set_missed_tick_behavior(MissedTickBehavior::Delay);
        // Consume the immediate first tick so sweep runs after `sweep_interval`.
        ticker.tick().await;

        loop {
            tokio::select! {
                biased;

                changed = shutdown.changed() => {
                    if changed.is_err() || *shutdown.borrow() {
                        self.abort_all_active_local();
                        return Ok(());
                    }
                }

                maybe_frame = stream.next() => {
                    match maybe_frame {
                        Some(Ok(frame)) => {
                            self.handle_frame(frame, Instant::now());
                        }
                        Some(Err(err)) if err.kind() == ErrorKind::InvalidData => {
                            warn!(error = %err, "fatal protocol frame error; closing TCP stream");
                            self.abort_all_active_local();
                            return Err(DispatchError::InvalidData(err));
                        }
                        Some(Err(err)) => {
                            self.abort_all_active_local();
                            return Err(DispatchError::Io(err));
                        }
                        None => {
                            self.abort_all_active_local();
                            return Ok(());
                        }
                    }
                }

                _ = ticker.tick() => {
                    self.sweep_stale_streams(Instant::now());
                }
            }
        }
    }

    pub fn handle_frame(&mut self, frame: Frame, now: Instant) {
        self.dead_streams.retain(|_, expires_at| *expires_at > now);

        match frame.channel_id {
            CHANNEL_CTRL => {
                self.handle_ctrl_frame(frame);
            }
            CHANNEL_INPUT_FALLBACK if frame.stream_id == 0 && (frame.flags & (FLAG_START | FLAG_ABORT)) == 0 => {
                self.dispatch_to_worker(frame);
            }
            CHANNEL_CLIP_META | CHANNEL_CLIP_DATA | CHANNEL_MEDIA_STREAM | CHANNEL_INPUT_FALLBACK | CHANNEL_FILES => {
                self.handle_stream_frame(frame, now);
            }
            other => {
                debug!(channel_id = other, stream_id = frame.stream_id, "dropping unknown channel frame");
            }
        }
    }

    fn handle_ctrl_frame(&self, frame: Frame) {
        match ControlMessage::decode(&frame.payload) {
            Ok(ControlMessage::CreditGrant {
                target_channel,
                credits_granted,
                cumulative_bytes_consumed,
            }) => {
                if target_channel == CHANNEL_CLIP_DATA {
                    self.outbound_clip_flow
                        .grant_credits(credits_granted, cumulative_bytes_consumed);
                }
            }
            Ok(ControlMessage::Heartbeat {
                is_pong: false,
                timestamp_ms,
            }) => {
                let pong = Frame::ctrl_urgent(
                    ControlMessage::Heartbeat {
                        is_pong: true,
                        timestamp_ms,
                    }
                    .encode(),
                );
                if let Err(err) = self.sender.try_send_high(pong) {
                    warn!(error = %err, "failed to enqueue heartbeat pong");
                }
            }
            Ok(_) => {}
            Err(err) => {
                warn!(error = %err, "malformed control message payload");
                return;
            }
        }

        if let Err(err) = self.router.ctrl_tx.try_send(frame) {
            warn!(error = %err, "ctrl worker channel full/closed; dropping control frame");
        }
    }

    fn handle_stream_frame(&mut self, frame: Frame, now: Instant) {
        let stream_id = frame.stream_id;

        if stream_id == 0 {
            warn!(channel_id = frame.channel_id, "stream_id 0 reserved for CTRL; aborting");
            self.abort_stream(stream_id, frame.channel_id, now, true);
            return;
        }

        if self.is_tombstoned(stream_id, now) {
            trace!(stream_id, "dropping frame for tombstoned stream");
            return;
        }

        if (frame.flags & FLAG_ABORT) != 0 {
            debug!(stream_id, channel_id = frame.channel_id, "received peer FLAG_ABORT");
            let channel = self
                .active_streams
                .remove(&stream_id)
                .map(|s| s.channel_id)
                .unwrap_or(frame.channel_id);
            self.dead_streams.insert(stream_id, now + self.tombstone_ttl);
            self.dispatch_to_worker(Frame::new(channel, stream_id, FLAG_ABORT, 0, BytesMut::new()));
            return;
        }

        if (frame.flags & FLAG_START) != 0 {
            if frame.sequence_number != 0 {
                warn!(
                    stream_id,
                    seq = frame.sequence_number,
                    "FLAG_START with non-zero sequence; aborting stream"
                );
                self.abort_stream(stream_id, frame.channel_id, now, true);
                return;
            }
            if self.active_streams.contains_key(&stream_id) {
                warn!(stream_id, "duplicate FLAG_START on active stream; aborting stream");
                self.abort_stream(stream_id, frame.channel_id, now, true);
                return;
            }

            let payload_len = frame.payload.len();
            let channel_id = frame.channel_id;
            let is_end = (frame.flags & FLAG_END) != 0;

            if !self.dispatch_to_worker(frame) {
                self.abort_stream(stream_id, channel_id, now, true);
                return;
            }

            self.reconcile_inbound_flow(channel_id, payload_len);

            if !is_end {
                self.active_streams.insert(
                    stream_id,
                    StreamState {
                        channel_id,
                        expected_seq: 1,
                        started_at: now,
                        last_activity: now,
                    },
                );
            }
            return;
        }

        let Some(mut state) = self.active_streams.get(&stream_id).copied() else {
            warn!(
                stream_id,
                channel_id = frame.channel_id,
                seq = frame.sequence_number,
                "continuation frame for unknown stream; aborting stream"
            );
            self.abort_stream(stream_id, frame.channel_id, now, true);
            return;
        };

        if frame.channel_id != state.channel_id || frame.sequence_number != state.expected_seq {
            warn!(
                stream_id,
                expected_channel = state.channel_id,
                got_channel = frame.channel_id,
                expected_seq = state.expected_seq,
                got_seq = frame.sequence_number,
                "stream sequence/channel mismatch; aborting only stream_id"
            );
            self.abort_stream(stream_id, state.channel_id, now, true);
            return;
        }

        let Some(next_seq) = state.expected_seq.checked_add(1) else {
            warn!(stream_id, "sequence overflow; aborting stream");
            self.abort_stream(stream_id, state.channel_id, now, true);
            return;
        };

        let payload_len = frame.payload.len();
        let channel_id = frame.channel_id;
        let is_end = (frame.flags & FLAG_END) != 0;

        if !self.dispatch_to_worker(frame) {
            self.abort_stream(stream_id, channel_id, now, true);
            return;
        }

        self.reconcile_inbound_flow(channel_id, payload_len);

        if is_end {
            self.active_streams.remove(&stream_id);
        } else {
            state.expected_seq = next_seq;
            state.last_activity = now;
            self.active_streams.insert(stream_id, state);
        }
    }

    pub fn sweep_stale_streams(&mut self, now: Instant) -> Vec<u16> {
        self.dead_streams.retain(|_, expires_at| *expires_at > now);

        let expired: Vec<(u16, u8)> = self
            .active_streams
            .iter()
            .filter_map(|(&stream_id, state)| {
                if now.duration_since(state.last_activity) >= self.stream_timeout {
                    Some((stream_id, state.channel_id))
                } else {
                    None
                }
            })
            .collect();

        let mut aborted_ids = Vec::with_capacity(expired.len());
        for (stream_id, channel_id) in expired {
            warn!(stream_id, channel_id, "sweeping stale stream after timeout");
            self.abort_stream(stream_id, channel_id, now, true);
            aborted_ids.push(stream_id);
        }
        aborted_ids
    }

    fn abort_all_active_local(&mut self) {
        let active: Vec<(u16, u8)> = self
            .active_streams
            .drain()
            .map(|(id, state)| (id, state.channel_id))
            .collect();
        for (stream_id, channel_id) in active {
            self.dispatch_to_worker(Frame::new(
                channel_id,
                stream_id,
                FLAG_ABORT,
                0,
                BytesMut::new(),
            ));
        }
    }

    fn abort_stream(&mut self, stream_id: u16, fallback_channel: u8, now: Instant, notify_peer: bool) {
        let channel_id = self
            .active_streams
            .remove(&stream_id)
            .map(|s| s.channel_id)
            .unwrap_or(fallback_channel);

        if stream_id != 0 {
            self.dead_streams.insert(stream_id, now + self.tombstone_ttl);
        }

        let abort_frame = Frame::new(channel_id, stream_id, FLAG_ABORT, 0, BytesMut::new());
        if notify_peer
            && let Err(err) = self.sender.try_send_high(abort_frame.clone())
        {
            warn!(stream_id, error = %err, "failed to enqueue FLAG_ABORT to peer");
        }
        self.dispatch_to_worker(abort_frame);
    }

    fn reconcile_inbound_flow(&self, channel_id: u8, payload_len: usize) {
        let flow = match channel_id {
            CHANNEL_CLIP_DATA => &self.inbound_clip_flow,
            CHANNEL_MEDIA_STREAM => &self.inbound_media_flow,
            _ => return,
        };

        if let Some((credits_granted, cumulative_bytes_consumed)) = flow.reconcile_bytes(payload_len) {
            let msg = ControlMessage::CreditGrant {
                target_channel: channel_id,
                credits_granted,
                cumulative_bytes_consumed,
            };
            if let Err(err) = self.sender.try_send_high(Frame::ctrl_urgent(msg.encode())) {
                warn!(channel_id, error = %err, "failed to enqueue CreditGrant");
            }
        }
    }

    fn dispatch_to_worker(&self, frame: Frame) -> bool {
        let tx = match frame.channel_id {
            CHANNEL_CTRL => &self.router.ctrl_tx,
            CHANNEL_CLIP_META => &self.router.clip_meta_tx,
            CHANNEL_CLIP_DATA => &self.router.clip_data_tx,
            CHANNEL_MEDIA_STREAM => &self.router.media_tx,
            CHANNEL_INPUT_FALLBACK => &self.router.input_tx,
            CHANNEL_FILES => &self.router.files_tx,
            _ => return false,
        };

        match tx.try_send(frame) {
            Ok(()) => true,
            Err(mpsc::error::TrySendError::Full(dropped)) => {
                warn!(
                    channel_id = dropped.channel_id,
                    stream_id = dropped.stream_id,
                    "worker channel full; non-blocking drop/abort"
                );
                false
            }
            Err(mpsc::error::TrySendError::Closed(dropped)) => {
                debug!(
                    channel_id = dropped.channel_id,
                    stream_id = dropped.stream_id,
                    "worker channel closed"
                );
                false
            }
        }
    }
}

pub async fn perform_l7_handshake<T>(
    framed: &mut Framed<T, ProtocolCodec>,
    identity_key: &IdentityKeyPair,
    expected_peer_pubkey: Option<[u8; 32]>,
) -> Result<Option<[u8; 32]>, HandshakeError>
where
    T: AsyncRead + AsyncWrite + Unpin,
{
    let challenge_frame = tokio::time::timeout(HANDSHAKE_TIMEOUT, framed.next())
        .await
        .map_err(|_| HandshakeError::Timeout)?
        .ok_or(HandshakeError::UnexpectedEof)??;

    if challenge_frame.channel_id != CHANNEL_CTRL {
        return Err(HandshakeError::UnexpectedChannel(challenge_frame.channel_id));
    }

    let nonce = match ControlMessage::decode(&challenge_frame.payload)? {
        ControlMessage::AuthChallenge { nonce } => nonce,
        _ => return Err(HandshakeError::UnexpectedControlMessage),
    };

    let signature = identity_key.sign(&nonce);
    let response = ControlMessage::AuthResponse {
        pubkey: identity_key.public_key_bytes(),
        signature,
    };
    tokio::time::timeout(
        HANDSHAKE_TIMEOUT,
        framed.send(Frame::ctrl_urgent(response.encode())),
    )
    .await
    .map_err(|_| HandshakeError::Timeout)??;

    let mut verified_peer = expected_peer_pubkey;

    loop {
        let next_frame = tokio::time::timeout(HANDSHAKE_TIMEOUT, framed.next())
            .await
            .map_err(|_| HandshakeError::Timeout)?
            .ok_or(HandshakeError::UnexpectedEof)??;

        if next_frame.channel_id != CHANNEL_CTRL {
            return Err(HandshakeError::UnexpectedChannel(next_frame.channel_id));
        }

        match ControlMessage::decode(&next_frame.payload)? {
            ControlMessage::AuthResponse { pubkey, signature: _ } => {
                if let Some(expected) = expected_peer_pubkey
                    && expected != pubkey
                {
                    return Err(HandshakeError::PeerKeyMismatch);
                }
                verified_peer = Some(pubkey);
            }
            ControlMessage::AuthResult { ok: true } => return Ok(verified_peer),
            ControlMessage::AuthResult { ok: false } => return Err(HandshakeError::Rejected),
            _ => return Err(HandshakeError::UnexpectedControlMessage),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use bytes::BufMut;
    use core_protocol::{CREDIT_THRESHOLD_BYTES, MAX_PAYLOAD_SIZE, random_nonce_32};
    use tokio::io::AsyncWriteExt;
    use tokio::net::{TcpListener, TcpStream};

    #[allow(dead_code)]
    struct TestChannels {
        router: ChannelRouter,
        ctrl_rx: mpsc::Receiver<Frame>,
        clip_meta_rx: mpsc::Receiver<Frame>,
        clip_data_rx: mpsc::Receiver<Frame>,
        media_rx: mpsc::Receiver<Frame>,
        input_rx: mpsc::Receiver<Frame>,
        files_rx: mpsc::Receiver<Frame>,
    }

    fn make_channels(cap: usize) -> TestChannels {
        let (ctrl_tx, ctrl_rx) = mpsc::channel(cap);
        let (clip_meta_tx, clip_meta_rx) = mpsc::channel(cap);
        let (clip_data_tx, clip_data_rx) = mpsc::channel(cap);
        let (media_tx, media_rx) = mpsc::channel(cap);
        let (input_tx, input_rx) = mpsc::channel(cap);
        let (files_tx, files_rx) = mpsc::channel(cap);
        TestChannels {
            router: ChannelRouter {
                ctrl_tx,
                clip_meta_tx,
                clip_data_tx,
                media_tx,
                input_tx,
                files_tx,
            },
            ctrl_rx,
            clip_meta_rx,
            clip_data_rx,
            media_rx,
            input_rx,
            files_rx,
        }
    }

    #[tokio::test]
    async fn sequence_mismatch_aborts_only_affected_stream_and_keeps_tcp_alive() {
        let mut ch = make_channels(16);
        let (sender, mut high_rx, _low_rx) = PrioritySender::channel(16, 16);
        let (shutdown_tx, shutdown_rx) = watch::channel(false);

        let (client, server) = tokio::io::duplex(64 * 1024);
        let mut peer = Framed::new(server, ProtocolCodec::new());
        let (_sink, stream) = Framed::new(client, ProtocolCodec::new()).split();

        let dispatcher = Dispatcher::new(ch.router, sender);
        let handle = tokio::spawn(dispatcher.run(stream, shutdown_rx));

        // Start stream 1 (valid) and stream 2 (valid)
        peer.send(Frame::new(
            CHANNEL_CLIP_DATA,
            1,
            FLAG_START,
            0,
            BytesMut::from(&b"s1-0"[..]),
        ))
        .await
        .unwrap();
        peer.send(Frame::new(
            CHANNEL_CLIP_DATA,
            2,
            FLAG_START,
            0,
            BytesMut::from(&b"s2-0"[..]),
        ))
        .await
        .unwrap();

        // Out-of-order seq on stream 1 (expected 1, send 5) -> abort ONLY stream 1
        peer.send(Frame::new(
            CHANNEL_CLIP_DATA,
            1,
            0,
            5,
            BytesMut::from(&b"s1-bad"[..]),
        ))
        .await
        .unwrap();

        // Subsequent chunk for dead/tombstoned stream 1 should be ignored
        peer.send(Frame::new(
            CHANNEL_CLIP_DATA,
            1,
            0,
            1,
            BytesMut::from(&b"s1-after-abort"[..]),
        ))
        .await
        .unwrap();

        // Stream 2 continues unaffected and finishes cleanly
        peer.send(Frame::new(
            CHANNEL_CLIP_DATA,
            2,
            FLAG_END,
            1,
            BytesMut::from(&b"s2-1"[..]),
        ))
        .await
        .unwrap();

        // Verify PrioritySender received FLAG_ABORT for stream 1
        let abort_out = tokio::time::timeout(Duration::from_secs(1), high_rx.recv())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(abort_out.stream_id, 1);
        assert_eq!(abort_out.flags, FLAG_ABORT);

        // Verify worker received s1-0, s2-0, abort(1), s2-1 (no s1-bad or s1-after-abort)
        let f1 = ch.clip_data_rx.recv().await.unwrap();
        let f2 = ch.clip_data_rx.recv().await.unwrap();
        let f3 = ch.clip_data_rx.recv().await.unwrap();
        let f4 = ch.clip_data_rx.recv().await.unwrap();
        assert_eq!((f1.stream_id, f1.sequence_number), (1, 0));
        assert_eq!((f2.stream_id, f2.sequence_number), (2, 0));
        assert_eq!((f3.stream_id, f3.flags), (1, FLAG_ABORT));
        assert_eq!((f4.stream_id, f4.sequence_number, f4.flags), (2, 1, FLAG_END));

        let _ = shutdown_tx.send(true);
        handle.await.unwrap().unwrap();
    }

    #[tokio::test]
    async fn invalid_magic_drops_tcp_with_invalid_data() {
        let ch = make_channels(16);
        let (sender, _high_rx, _low_rx) = PrioritySender::channel(16, 16);
        let (_shutdown_tx, shutdown_rx) = watch::channel(false);

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        let peer_task = tokio::spawn(async move {
            let (mut sock, _) = listener.accept().await.unwrap();
            sock.write_all(&[0xBA, 0x00, 0x00, 0x01]).await.unwrap();
        });

        let client = TcpStream::connect(addr).await.unwrap();
        let (_sink, stream) = Framed::new(client, ProtocolCodec::new()).split();
        let dispatcher = Dispatcher::new(ch.router, sender);

        let res = dispatcher.run(stream, shutdown_rx).await;
        assert!(matches!(res, Err(DispatchError::InvalidData(_))));
        peer_task.await.unwrap();
    }

    #[tokio::test]
    async fn oversized_payload_length_drops_tcp_with_invalid_data() {
        let ch = make_channels(16);
        let (sender, _high_rx, _low_rx) = PrioritySender::channel(16, 16);
        let (_shutdown_tx, shutdown_rx) = watch::channel(false);

        let (client, mut server) = tokio::io::duplex(1024);
        let mut header = BytesMut::with_capacity(12);
        header.put_u8(core_protocol::MAGIC_BYTE);
        header.put_u8(CHANNEL_CLIP_DATA);
        header.put_u16(1);
        header.put_u16(FLAG_START);
        header.put_u16((MAX_PAYLOAD_SIZE + 1) as u16);
        header.put_u32(0);
        server.write_all(&header).await.unwrap();

        let (_sink, stream) = Framed::new(client, ProtocolCodec::new()).split();
        let dispatcher = Dispatcher::new(ch.router, sender);
        let res = dispatcher.run(stream, shutdown_rx).await;
        assert!(matches!(res, Err(DispatchError::InvalidData(_))));
    }

    #[tokio::test]
    async fn sweep_aborts_stale_streams_after_timeout_and_tombstones() {
        let mut ch = make_channels(16);
        let (sender, mut high_rx, _low_rx) = PrioritySender::channel(16, 16);
        let (_shutdown_tx, shutdown_rx) = watch::channel(false);

        let (client, server) = tokio::io::duplex(64 * 1024);
        let mut peer = Framed::new(server, ProtocolCodec::new());
        let (_sink, stream) = Framed::new(client, ProtocolCodec::new()).split();

        let dispatcher = Dispatcher::new(ch.router, sender).with_timeouts(
            Duration::from_millis(60),
            Duration::from_secs(5),
            Duration::from_millis(15),
        );
        assert_eq!(DEFAULT_STREAM_TIMEOUT, Duration::from_secs(10));
        let handle = tokio::spawn(dispatcher.run(stream, shutdown_rx));

        peer.send(Frame::new(
            CHANNEL_CLIP_DATA,
            77,
            FLAG_START,
            0,
            BytesMut::from(&b"stalled"[..]),
        ))
        .await
        .unwrap();

        // Receive initial frame in worker
        let first = ch.clip_data_rx.recv().await.unwrap();
        assert_eq!(first.stream_id, 77);

        let abort_out = tokio::time::timeout(Duration::from_secs(1), high_rx.recv())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(abort_out.stream_id, 77);
        assert_eq!(abort_out.flags, FLAG_ABORT);

        let abort_worker = tokio::time::timeout(Duration::from_secs(1), ch.clip_data_rx.recv())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(abort_worker.stream_id, 77);
        assert_eq!(abort_worker.flags, FLAG_ABORT);

        // Sending late chunk within tombstone TTL should be ignored (no second abort)
        peer.send(Frame::new(
            CHANNEL_CLIP_DATA,
            77,
            0,
            1,
            BytesMut::from(&b"late"[..]),
        ))
        .await
        .unwrap();
        tokio::time::sleep(Duration::from_millis(40)).await;
        assert!(high_rx.try_recv().is_err());

        drop(peer);
        handle.await.unwrap().unwrap();
    }

    #[tokio::test]
    async fn credit_grant_emitted_at_512kb_and_blocked_worker_never_freezes_reader() {
        // Blocked clip_data channel capacity = 1 and never drained; ctrl_tx has capacity = 16
        let (ctrl_tx, mut ctrl_rx) = mpsc::channel(16);
        let (clip_meta_tx, _clip_meta_rx) = mpsc::channel(1);
        let (clip_data_tx, mut clip_data_rx) = mpsc::channel(32);
        let (media_tx, _media_rx) = mpsc::channel(1); // intentionally un-drained!
        let (input_tx, _input_rx) = mpsc::channel(16);
        let (files_tx, _files_rx) = mpsc::channel(16);

        let router = ChannelRouter {
            ctrl_tx,
            clip_meta_tx,
            clip_data_tx,
            media_tx,
            input_tx,
            files_tx,
        };
        let (sender, mut high_rx, _low_rx) = PrioritySender::channel(32, 16);
        let mut dispatcher = Dispatcher::new(router, sender);

        let now = Instant::now();
        // Saturate media_tx (capacity 1) and send another frame -> must not block
        dispatcher.handle_frame(
            Frame::new(CHANNEL_MEDIA_STREAM, 10, FLAG_START, 0, BytesMut::from(&b"m0"[..])),
            now,
        );
        dispatcher.handle_frame(
            Frame::new(CHANNEL_MEDIA_STREAM, 10, FLAG_END, 1, BytesMut::from(&b"m1"[..])),
            now,
        );
        // CTRL still gets processed immediately
        dispatcher.handle_frame(
            Frame::ctrl(ControlMessage::Heartbeat { is_pong: false, timestamp_ms: 42 }.encode()),
            now,
        );
        // Expect FLAG_ABORT for stalled media stream 10, followed by Heartbeat Pong
        let abort_frame = high_rx.try_recv().unwrap();
        assert_eq!(abort_frame.channel_id, CHANNEL_MEDIA_STREAM);
        assert_eq!(abort_frame.stream_id, 10);
        assert_eq!(abort_frame.flags, FLAG_ABORT);

        let pong_frame = high_rx.try_recv().unwrap();
        assert_eq!(
            ControlMessage::decode(&pong_frame.payload).unwrap(),
            ControlMessage::Heartbeat {
                is_pong: true,
                timestamp_ms: 42
            }
        );
        assert!(ctrl_rx.try_recv().is_ok());

        let chunk = vec![0xABu8; MAX_PAYLOAD_SIZE];
        for seq in 0..32u32 {
            let flags = if seq == 0 {
                FLAG_START
            } else if seq == 31 {
                FLAG_END
            } else {
                0
            };
            dispatcher.handle_frame(
                Frame::new(CHANNEL_CLIP_DATA, 55, flags, seq, chunk.as_slice()),
                now,
            );
            let _ = clip_data_rx.try_recv();
        }

        let grant_frame = high_rx.try_recv().unwrap();
        assert_eq!(grant_frame.channel_id, CHANNEL_CTRL);
        assert_eq!(
            ControlMessage::decode(&grant_frame.payload).unwrap(),
            ControlMessage::CreditGrant {
                target_channel: CHANNEL_CLIP_DATA,
                credits_granted: 32,
                cumulative_bytes_consumed: CREDIT_THRESHOLD_BYTES as u32,
            }
        );
    }

    #[tokio::test]
    async fn l7_handshake_signs_challenge_and_accepts_ok() {
        let (client_key, _) = IdentityKeyPair::generate().unwrap();
        let expected_pub = client_key.public_key_bytes();
        let nonce = random_nonce_32().unwrap();

        let (client_io, server_io) = tokio::io::duplex(4096);
        let mut client_framed = Framed::new(client_io, ProtocolCodec::new());
        let mut server_framed = Framed::new(server_io, ProtocolCodec::new());

        let server_task = tokio::spawn(async move {
            server_framed
                .send(Frame::ctrl_urgent(
                    ControlMessage::AuthChallenge { nonce }.encode(),
                ))
                .await
                .unwrap();

            let resp_frame = server_framed.next().await.unwrap().unwrap();
            let ControlMessage::AuthResponse { pubkey, signature } =
                ControlMessage::decode(&resp_frame.payload).unwrap()
            else {
                panic!("expected AuthResponse");
            };
            assert_eq!(pubkey, expected_pub);
            IdentityKeyPair::verify_ed25519(&pubkey, &nonce, &signature).unwrap();

            server_framed
                .send(Frame::ctrl_urgent(
                    ControlMessage::AuthResult { ok: true }.encode(),
                ))
                .await
                .unwrap();
        });

        let res = perform_l7_handshake(&mut client_framed, &client_key, None).await;
        assert!(res.is_ok());
        server_task.await.unwrap();
    }

    #[tokio::test]
    async fn l7_handshake_returns_rejected_on_auth_failure() {
        let (client_key, _) = IdentityKeyPair::generate().unwrap();
        let nonce = random_nonce_32().unwrap();

        let (client_io, server_io) = tokio::io::duplex(4096);
        let mut client_framed = Framed::new(client_io, ProtocolCodec::new());
        let mut server_framed = Framed::new(server_io, ProtocolCodec::new());

        let server_task = tokio::spawn(async move {
            server_framed
                .send(Frame::ctrl_urgent(
                    ControlMessage::AuthChallenge { nonce }.encode(),
                ))
                .await
                .unwrap();
            let _ = server_framed.next().await.unwrap().unwrap();
            server_framed
                .send(Frame::ctrl_urgent(
                    ControlMessage::AuthResult { ok: false }.encode(),
                ))
                .await
                .unwrap();
        });

        let res = perform_l7_handshake(&mut client_framed, &client_key, None).await;
        assert!(matches!(res, Err(HandshakeError::Rejected)));
        server_task.await.unwrap();
    }

    #[tokio::test]
    async fn channel_files_routes_stream_frames_without_credit_grants() {
        let mut ch = make_channels(16);
        let (sender, mut high_rx, _low_rx) = PrioritySender::channel(16, 16);
        let mut dispatcher = Dispatcher::new(ch.router, sender);

        let now = Instant::now();
        dispatcher.handle_frame(
            Frame::new(CHANNEL_FILES, 3, FLAG_START, 0, BytesMut::from(&b"part0"[..])),
            now,
        );
        dispatcher.handle_frame(
            Frame::new(CHANNEL_FILES, 3, FLAG_END, 1, BytesMut::from(&b"part1"[..])),
            now,
        );

        let f0 = ch.files_rx.recv().await.unwrap();
        let f1 = ch.files_rx.recv().await.unwrap();
        assert_eq!((f0.channel_id, f0.stream_id, f0.flags), (CHANNEL_FILES, 3, FLAG_START));
        assert_eq!((f1.channel_id, f1.stream_id, f1.flags), (CHANNEL_FILES, 3, FLAG_END));
        assert!(high_rx.try_recv().is_err());
    }
}
