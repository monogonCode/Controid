use core_protocol::{
    CHANNEL_CLIP_DATA, CHANNEL_CLIP_META, CHANNEL_CTRL, CHANNEL_INPUT_FALLBACK,
    CHANNEL_MEDIA_STREAM, FLAG_ABORT, FLAG_URGENT, Frame, ProtocolCodec,
};
use futures::SinkExt;
use futures::stream::SplitSink;
use std::io;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::sync::mpsc;
use tokio_util::codec::Framed;

pub const DEFAULT_HIGH_CHANNEL_CAPACITY: usize = 256;
pub const DEFAULT_LOW_CHANNEL_CAPACITY: usize = 128;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OutgoingPriority {
    High,
    Low,
}

#[derive(Debug, Clone)]
pub struct PrioritySender {
    high_tx: mpsc::Sender<Frame>,
    low_tx: mpsc::Sender<Frame>,
}

impl PrioritySender {
    pub fn new(high_tx: mpsc::Sender<Frame>, low_tx: mpsc::Sender<Frame>) -> Self {
        Self { high_tx, low_tx }
    }

    pub fn channel(
        high_capacity: usize,
        low_capacity: usize,
    ) -> (Self, mpsc::Receiver<Frame>, mpsc::Receiver<Frame>) {
        let (high_tx, high_rx) = mpsc::channel(high_capacity.max(1));
        let (low_tx, low_rx) = mpsc::channel(low_capacity.max(1));
        (Self::new(high_tx, low_tx), high_rx, low_rx)
    }

    pub fn priority_for_frame(frame: &Frame) -> OutgoingPriority {
        if (frame.flags & (FLAG_ABORT | FLAG_URGENT)) != 0 {
            return OutgoingPriority::High;
        }
        match frame.channel_id {
            CHANNEL_CTRL | CHANNEL_CLIP_META | CHANNEL_INPUT_FALLBACK => OutgoingPriority::High,
            CHANNEL_CLIP_DATA | CHANNEL_MEDIA_STREAM => OutgoingPriority::Low,
            _ => OutgoingPriority::Low,
        }
    }

    pub async fn send(
        &self,
        frame: Frame,
        priority: OutgoingPriority,
    ) -> Result<(), mpsc::error::SendError<Frame>> {
        match priority {
            OutgoingPriority::High => self.high_tx.send(frame).await,
            OutgoingPriority::Low => self.low_tx.send(frame).await,
        }
    }

    pub async fn send_high(&self, frame: Frame) -> Result<(), mpsc::error::SendError<Frame>> {
        self.high_tx.send(frame).await
    }

    pub async fn send_low(&self, frame: Frame) -> Result<(), mpsc::error::SendError<Frame>> {
        self.low_tx.send(frame).await
    }

    pub async fn send_auto(&self, frame: Frame) -> Result<(), mpsc::error::SendError<Frame>> {
        let priority = Self::priority_for_frame(&frame);
        self.send(frame, priority).await
    }

    pub fn try_send_high(&self, frame: Frame) -> Result<(), mpsc::error::TrySendError<Frame>> {
        self.high_tx.try_send(frame)
    }

    pub fn try_send_low(&self, frame: Frame) -> Result<(), mpsc::error::TrySendError<Frame>> {
        self.low_tx.try_send(frame)
    }

    pub fn is_closed(&self) -> bool {
        self.high_tx.is_closed() || self.low_tx.is_closed()
    }
}

pub async fn run_priority_emitter<T>(
    mut sink: SplitSink<Framed<T, ProtocolCodec>, Frame>,
    mut high_rx: mpsc::Receiver<Frame>,
    mut low_rx: mpsc::Receiver<Frame>,
) -> io::Result<()>
where
    T: AsyncRead + AsyncWrite + Unpin,
{
    let mut high_closed = false;
    let mut low_closed = false;

    loop {
        if high_closed && low_closed {
            break;
        }

        let frame = tokio::select! {
            biased;

            maybe_high = high_rx.recv(), if !high_closed => match maybe_high {
                Some(f) => f,
                None => {
                    high_closed = true;
                    continue;
                }
            },

            maybe_low = low_rx.recv(), if !low_closed => match maybe_low {
                Some(f) => f,
                None => {
                    low_closed = true;
                    continue;
                }
            },
        };

        sink.send(frame).await?;
    }

    let _ = sink.close().await;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use bytes::BytesMut;
    use core_protocol::{FLAG_END, FLAG_START};
    use futures::StreamExt;
    use tokio::net::{TcpListener, TcpStream};

    #[test]
    fn frame_priority_classification() {
        assert_eq!(
            PrioritySender::priority_for_frame(&Frame::ctrl(BytesMut::new())),
            OutgoingPriority::High
        );
        assert_eq!(
            PrioritySender::priority_for_frame(&Frame::new(
                CHANNEL_CLIP_META,
                1,
                FLAG_START | FLAG_END,
                0,
                BytesMut::new()
            )),
            OutgoingPriority::High
        );
        assert_eq!(
            PrioritySender::priority_for_frame(&Frame::new(
                CHANNEL_INPUT_FALLBACK,
                0,
                0,
                0,
                BytesMut::new()
            )),
            OutgoingPriority::High
        );
        assert_eq!(
            PrioritySender::priority_for_frame(&Frame::new(
                CHANNEL_CLIP_DATA,
                2,
                0,
                1,
                BytesMut::new()
            )),
            OutgoingPriority::Low
        );
        assert_eq!(
            PrioritySender::priority_for_frame(&Frame::new(
                CHANNEL_CLIP_DATA,
                2,
                FLAG_ABORT,
                0,
                BytesMut::new()
            )),
            OutgoingPriority::High
        );
    }

    #[tokio::test]
    async fn biased_emitter_drains_high_before_low_over_tcp() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        let accept_task = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let mut framed = Framed::new(stream, ProtocolCodec::new());
            let mut received = Vec::new();
            while let Some(res) = framed.next().await {
                received.push(res.unwrap());
            }
            received
        });

        let client = TcpStream::connect(addr).await.unwrap();
        let (sink, _stream) = Framed::new(client, ProtocolCodec::new()).split();
        let (sender, high_rx, low_rx) = PrioritySender::channel(16, 16);

        // Enqueue low-priority frames first, then high-priority frames, before emitter runs.
        sender
            .send_low(Frame::new(
                CHANNEL_CLIP_DATA,
                10,
                0,
                0,
                BytesMut::from(&b"low-0"[..]),
            ))
            .await
            .unwrap();
        sender
            .send_low(Frame::new(
                CHANNEL_MEDIA_STREAM,
                11,
                0,
                1,
                BytesMut::from(&b"low-1"[..]),
            ))
            .await
            .unwrap();
        sender
            .send_high(Frame::ctrl(BytesMut::from(&b"high-0"[..])))
            .await
            .unwrap();
        sender
            .send_high(Frame::new(
                CHANNEL_CLIP_DATA,
                10,
                FLAG_ABORT,
                0,
                BytesMut::new(),
            ))
            .await
            .unwrap();

        drop(sender);
        run_priority_emitter(sink, high_rx, low_rx).await.unwrap();

        let received = accept_task.await.unwrap();
        assert_eq!(received.len(), 4);
        assert_eq!(received[0].channel_id, CHANNEL_CTRL);
        assert_eq!(received[1].flags, FLAG_ABORT);
        assert_eq!(received[2].channel_id, CHANNEL_CLIP_DATA);
        assert_eq!(received[3].channel_id, CHANNEL_MEDIA_STREAM);
    }
}
