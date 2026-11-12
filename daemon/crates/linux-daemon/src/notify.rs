use std::collections::HashMap;
use std::time::Duration;
use tracing::debug;
use zbus::zvariant::Value;

pub struct DesktopNotifier;

pub fn notify_fire_and_forget(conn: zbus::Connection, summary: &str, body: &str, urgency: u8) {
    let summary = summary.to_owned();
    let body = body.to_owned();

    tokio::spawn(async move {
        let mut hints: HashMap<&str, Value<'_>> = HashMap::new();
        hints.insert("urgency", Value::U8(urgency));

        let args = (
            "Controid",
            0u32,
            "controid",
            summary.as_str(),
            body.as_str(),
            Vec::<&str>::new(),
            hints,
            5000i32,
        );
        let call = conn.call_method(
            Some("org.freedesktop.Notifications"),
            "/org/freedesktop/Notifications",
            Some("org.freedesktop.Notifications"),
            "Notify",
            &args,
        );

        match tokio::time::timeout(Duration::from_secs(2), call).await {
            Ok(Ok(_)) => {}
            Ok(Err(err)) => debug!(error = %err, "desktop notification failed"),
            Err(_) => debug!("desktop notification timed out"),
        }
    });
}
