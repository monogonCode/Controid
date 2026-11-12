use futures::StreamExt;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;
use tracing::{debug, warn};
use zbus::message::Type as MessageType;
use zbus::{MatchRule, MessageStream};

pub struct PowerMonitor;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PowerEvent {
    Suspending,
    Resumed,
}

pub fn spawn_sleep_monitor(
    conn: zbus::Connection,
    tx: mpsc::Sender<PowerEvent>,
) -> JoinHandle<()> {
    tokio::spawn(async move {
        let rule = match MatchRule::builder()
            .msg_type(MessageType::Signal)
            .sender("org.freedesktop.login1")
            .and_then(|b| b.path("/org/freedesktop/login1"))
            .and_then(|b| b.interface("org.freedesktop.login1.Manager"))
            .and_then(|b| b.member("PrepareForSleep"))
            .map(|b| b.build())
        {
            Ok(rule) => rule,
            Err(err) => {
                warn!(error = %err, "failed to build logind PrepareForSleep match rule");
                return;
            }
        };

        let mut stream = match MessageStream::for_match_rule(rule, &conn, Some(16)).await {
            Ok(s) => s,
            Err(err) => {
                warn!(error = %err, "failed to subscribe to logind PrepareForSleep");
                return;
            }
        };

        while let Some(item) = stream.next().await {
            let msg = match item {
                Ok(m) => m,
                Err(err) => {
                    debug!(error = %err, "logind signal stream error");
                    continue;
                }
            };
            match msg.body().deserialize::<bool>() {
                Ok(suspending) => {
                    let ev = if suspending {
                        PowerEvent::Suspending
                    } else {
                        PowerEvent::Resumed
                    };
                    if tx.send(ev).await.is_err() {
                        break;
                    }
                }
                Err(err) => {
                    debug!(error = %err, "failed to decode PrepareForSleep body");
                }
            }
        }
    })
}
