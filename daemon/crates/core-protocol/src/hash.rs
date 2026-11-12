use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

pub const ECHO_TTL: Duration = Duration::from_secs(2);
pub const ECHO_SLICE_LEN: usize = 1024;

pub fn compute_clipboard_echo_hash(
    is_text: bool,
    total_len: u64,
    head: &[u8],
    tail: &[u8],
) -> [u8; 32] {
    let mut hasher = Sha256::new();
    if is_text {
        hasher.update(head);
    } else {
        let head_slice = &head[..head.len().min(ECHO_SLICE_LEN)];
        let tail_start = tail.len().saturating_sub(ECHO_SLICE_LEN);
        let tail_slice = &tail[tail_start..];
        hasher.update(total_len.to_be_bytes());
        hasher.update(head_slice);
        hasher.update(tail_slice);
    }
    hasher.finalize().into()
}

pub fn compute_text_echo_hash(text: &str) -> [u8; 32] {
    let bytes = text.as_bytes();
    compute_clipboard_echo_hash(true, bytes.len() as u64, bytes, &[])
}

pub fn compute_binary_echo_hash(data: &[u8]) -> [u8; 32] {
    let head = &data[..data.len().min(ECHO_SLICE_LEN)];
    let tail = &data[data.len().saturating_sub(ECHO_SLICE_LEN)..];
    compute_clipboard_echo_hash(false, data.len() as u64, head, tail)
}

#[derive(Debug)]
pub struct EchoSuppressor {
    ttl: Duration,
    entries: Mutex<HashMap<[u8; 32], Instant>>,
}

impl Default for EchoSuppressor {
    fn default() -> Self {
        Self::new(ECHO_TTL)
    }
}

impl EchoSuppressor {
    pub fn new(ttl: Duration) -> Self {
        Self {
            ttl,
            entries: Mutex::new(HashMap::new()),
        }
    }

    pub fn record(&self, hash: [u8; 32]) {
        self.record_at(hash, Instant::now());
    }

    pub fn should_suppress(&self, hash: &[u8; 32]) -> bool {
        self.should_suppress_at(hash, Instant::now())
    }

    fn record_at(&self, hash: [u8; 32], now: Instant) {
        let mut map = self.entries.lock().expect("echo suppressor poisoned");
        map.retain(|_, exp| *exp > now);
        map.insert(hash, now + self.ttl);
    }

    fn should_suppress_at(&self, hash: &[u8; 32], now: Instant) -> bool {
        let mut map = self.entries.lock().expect("echo suppressor poisoned");
        map.retain(|_, exp| *exp > now);
        map.get(hash).is_some_and(|exp| *exp > now)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn text_echo_hash_matches_raw_sha256() {
        let text = "Hello Controid 🚀";
        let expected: [u8; 32] = Sha256::digest(text.as_bytes()).into();
        assert_eq!(compute_text_echo_hash(text), expected);
        assert_eq!(
            compute_clipboard_echo_hash(true, text.len() as u64, text.as_bytes(), &[]),
            expected
        );
    }

    #[test]
    fn binary_echo_hash_uses_total_len_head_1024_tail_1024() {
        let mut data = vec![0xAAu8; 4096];
        for (i, b) in data.iter_mut().enumerate() {
            *b = (i % 251) as u8;
        }
        let mut hasher = Sha256::new();
        hasher.update(4096u64.to_be_bytes());
        hasher.update(&data[..1024]);
        hasher.update(&data[3072..4096]);
        let expected: [u8; 32] = hasher.finalize().into();

        assert_eq!(compute_binary_echo_hash(&data), expected);
        assert_eq!(
            compute_clipboard_echo_hash(false, 4096, &data[..1024], &data[3072..]),
            expected
        );
    }

    #[test]
    fn binary_echo_hash_small_buffer_overlap() {
        let data = b"small-binary-payload";
        let mut hasher = Sha256::new();
        hasher.update((data.len() as u64).to_be_bytes());
        hasher.update(data);
        hasher.update(data);
        let expected: [u8; 32] = hasher.finalize().into();

        assert_eq!(compute_binary_echo_hash(data), expected);
    }

    #[test]
    fn echo_suppressor_respects_2s_ttl() {
        let suppressor = EchoSuppressor::default();
        let hash = compute_text_echo_hash("copied text");
        let t0 = Instant::now();

        suppressor.record_at(hash, t0);
        assert!(suppressor.should_suppress_at(&hash, t0 + Duration::from_millis(500)));
        assert!(suppressor.should_suppress_at(&hash, t0 + Duration::from_millis(1999)));
        assert!(!suppressor.should_suppress_at(&hash, t0 + Duration::from_secs(2)));
    }
}
