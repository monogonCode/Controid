use bytes::Bytes;
use core_protocol::{CODEC_H264, CODEC_H265};
use std::ffi::OsStr;
use std::io;
use std::path::PathBuf;
use std::process::Stdio;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::process::{Child, ChildStdin, ChildStdout, Command};
use tokio::sync::mpsc;
use tokio::task::JoinHandle;
use tracing::{debug, warn};

const MPV_STOP_TIMEOUT: Duration = Duration::from_secs(2);
const INPUT_CHANNEL_CAPACITY: usize = 128;

#[derive(Debug)]
pub struct MpvVideoSink {
    child: Child,
    stdin: Option<ChildStdin>,
    input_reader_handle: Option<JoinHandle<()>>,
}

impl Drop for MpvVideoSink {
    fn drop(&mut self) {
        if let Some(handle) = self.input_reader_handle.take() {
            handle.abort();
        }
    }
}

pub fn build_mpv_args(codec: u8) -> io::Result<Vec<&'static str>> {
    let lavf_format_flag = match codec {
        CODEC_H264 => "--demuxer-lavf-format=h264",
        CODEC_H265 => "--demuxer-lavf-format=hevc",
        other => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("unsupported video codec: 0x{other:02x}"),
            ));
        }
    };

    Ok(vec![
        "--no-cache",
        "--untimed",
        "--no-correct-pts",
        "--no-demuxer-thread",
        "--vd-lavc-threads=1",
        "--profile=low-latency",
        "--hwdec=auto",
        "--demuxer=lavf",
        lavf_format_flag,
        "--title=Controid Screen",
        "-",
    ])
}

pub fn build_viewer_args(codec: u8) -> io::Result<Vec<&'static str>> {
    let codec_flag = match codec {
        CODEC_H264 => "--codec=h264",
        CODEC_H265 => "--codec=h265",
        other => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("unsupported video codec: 0x{other:02x}"),
            ));
        }
    };
    Ok(vec![codec_flag])
}

fn spawn_input_reader(mut stdout: ChildStdout, tx: mpsc::Sender<Bytes>) -> JoinHandle<()> {
    tokio::spawn(async move {
        let mut len_buf = [0u8; 2];
        loop {
            match stdout.read_exact(&mut len_buf).await {
                Ok(_) => {}
                Err(err) if err.kind() == io::ErrorKind::UnexpectedEof => break,
                Err(err) => {
                    debug!(?err, "viewer input stream read error");
                    break;
                }
            }

            let len = u16::from_be_bytes(len_buf) as usize;
            if len == 0 {
                continue;
            }

            let mut payload = vec![0u8; len];
            if let Err(err) = stdout.read_exact(&mut payload).await {
                if err.kind() != io::ErrorKind::UnexpectedEof {
                    debug!(?err, "viewer input payload read error");
                }
                break;
            }

            match tx.try_send(Bytes::from(payload)) {
                Ok(()) => {}
                Err(mpsc::error::TrySendError::Full(_)) => {
                    debug!("dropping remote input packet: channel full");
                }
                Err(mpsc::error::TrySendError::Closed(_)) => break,
            }
        }
    })
}

impl MpvVideoSink {
    pub async fn start(codec: u8) -> io::Result<Self> {
        let args = build_mpv_args(codec)?;
        Self::start_command("mpv", args).await
    }

    pub async fn start_interactive(codec: u8) -> io::Result<(Self, mpsc::Receiver<Bytes>)> {
        let args = build_viewer_args(codec)?;

        match Self::start_interactive_command("controid-viewer", &args).await {
            Ok(res) => return Ok(res),
            Err(err) if err.kind() == io::ErrorKind::NotFound => {}
            Err(err) => return Err(err),
        }

        if let Some(home) = std::env::var_os("HOME").filter(|h| !h.is_empty()) {
            let local_bin: PathBuf = PathBuf::from(home)
                .join(".local")
                .join("bin")
                .join("controid-viewer");
            match Self::start_interactive_command(&local_bin, &args).await {
                Ok(res) => return Ok(res),
                Err(err) if err.kind() == io::ErrorKind::NotFound => {}
                Err(err) => return Err(err),
            }
        }

        debug!("controid-viewer not found; falling back to mpv");
        let sink = Self::start(codec).await?;
        let (_tx, rx) = mpsc::channel(1);
        Ok((sink, rx))
    }

    pub(crate) async fn start_command<S, I, A>(program: S, args: I) -> io::Result<Self>
    where
        S: AsRef<OsStr>,
        I: IntoIterator<Item = A>,
        A: AsRef<OsStr>,
    {
        let mut cmd = Command::new(program);
        cmd.args(args)
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .kill_on_drop(true);

        let mut child = cmd.spawn()?;
        let stdin = child.stdin.take().ok_or_else(|| {
            io::Error::new(io::ErrorKind::BrokenPipe, "failed to capture child stdin pipe")
        })?;

        debug!(pid = child.id(), "started mpv video sink");
        Ok(Self {
            child,
            stdin: Some(stdin),
            input_reader_handle: None,
        })
    }

    pub(crate) async fn start_interactive_command<S, I, A>(
        program: S,
        args: I,
    ) -> io::Result<(Self, mpsc::Receiver<Bytes>)>
    where
        S: AsRef<OsStr>,
        I: IntoIterator<Item = A>,
        A: AsRef<OsStr>,
    {
        let mut cmd = Command::new(program);
        cmd.args(args)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .kill_on_drop(true);

        let mut child = cmd.spawn()?;
        let stdin = child.stdin.take().ok_or_else(|| {
            io::Error::new(io::ErrorKind::BrokenPipe, "failed to capture child stdin pipe")
        })?;
        let stdout = child.stdout.take().ok_or_else(|| {
            io::Error::new(io::ErrorKind::BrokenPipe, "failed to capture child stdout pipe")
        })?;

        let (tx, rx) = mpsc::channel(INPUT_CHANNEL_CAPACITY);
        let input_reader_handle = Some(spawn_input_reader(stdout, tx));

        debug!(pid = child.id(), "started interactive video sink");
        Ok((
            Self {
                child,
                stdin: Some(stdin),
                input_reader_handle,
            },
            rx,
        ))
    }

    pub async fn write_nal(&mut self, data: Bytes) -> io::Result<()> {
        if data.is_empty() {
            return Ok(());
        }

        let stdin = self.stdin.as_mut().ok_or_else(|| {
            io::Error::new(io::ErrorKind::BrokenPipe, "mpv video sink stdin closed")
        })?;

        stdin.write_all(&data).await
    }

    pub async fn stop(mut self) -> io::Result<()> {
        if let Some(handle) = self.input_reader_handle.take() {
            handle.abort();
        }
        // Closing stdin sends EOF so child exits cleanly without lingering.
        drop(self.stdin.take());

        match tokio::time::timeout(MPV_STOP_TIMEOUT, self.child.wait()).await {
            Ok(wait_res) => {
                let status = wait_res?;
                debug!(?status, "mpv video sink exited cleanly");
                Ok(())
            }
            Err(_) => {
                warn!("video sink did not exit after stdin EOF; terminating child");
                let _ = self.child.start_kill();
                let status = self.child.wait().await?;
                debug!(?status, "video sink reaped after kill");
                Ok(())
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mpv_args_select_demuxer_by_codec() {
        let h264 = build_mpv_args(CODEC_H264).unwrap();
        assert_eq!(
            h264,
            vec![
                "--no-cache",
                "--untimed",
                "--no-correct-pts",
                "--no-demuxer-thread",
                "--vd-lavc-threads=1",
                "--profile=low-latency",
                "--hwdec=auto",
                "--demuxer=lavf",
                "--demuxer-lavf-format=h264",
                "--title=Controid Screen",
                "-",
            ]
        );

        let h265 = build_mpv_args(CODEC_H265).unwrap();
        assert!(h265.contains(&"--demuxer=lavf"));
        assert!(h265.contains(&"--demuxer-lavf-format=hevc"));
        assert_eq!(h265.last(), Some(&"-"));

        let err = build_mpv_args(0x99).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
    }

    #[test]
    fn viewer_args_select_codec_flag() {
        assert_eq!(build_viewer_args(CODEC_H264).unwrap(), vec!["--codec=h264"]);
        assert_eq!(build_viewer_args(CODEC_H265).unwrap(), vec!["--codec=h265"]);
        let err = build_viewer_args(0x99).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
    }

    #[tokio::test]
    async fn video_sink_pipes_bytes_and_reaps_child_on_stop() {
        let mut sink = MpvVideoSink::start_command("cat", std::iter::empty::<&str>())
            .await
            .unwrap();
        sink.write_nal(Bytes::from_static(b"\x00\x00\x00\x01\x67"))
            .await
            .unwrap();
        sink.write_nal(Bytes::new()).await.unwrap();
        sink.stop().await.unwrap();
    }

    #[tokio::test]
    async fn video_sink_kills_and_reaps_stubborn_child_on_stop() {
        let sink = MpvVideoSink::start_command("sleep", ["30"])
            .await
            .unwrap();
        sink.stop().await.unwrap();
    }

    #[tokio::test]
    async fn interactive_reader_parses_length_prefixed_packets() {
        let (sink, mut rx) = MpvVideoSink::start_interactive_command(
            "printf",
            ["%b", r"\x00\x05hello\x00\x00\x00\x03bye"],
        )
        .await
        .unwrap();

        let p1 = rx.recv().await.unwrap();
        let p2 = rx.recv().await.unwrap();
        assert_eq!(p1, Bytes::from_static(b"hello"));
        assert_eq!(p2, Bytes::from_static(b"bye"));
        assert!(rx.recv().await.is_none());

        sink.stop().await.unwrap();
    }
}
