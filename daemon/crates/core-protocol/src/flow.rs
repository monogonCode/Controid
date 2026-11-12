use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use tokio::sync::Semaphore;

pub const INITIAL_WINDOW_CREDITS: usize = 64;
pub const CREDIT_THRESHOLD_BYTES: usize = 512 * 1024;

#[derive(Debug, Clone)]
pub struct FlowController {
    semaphore: Arc<Semaphore>,
    cumulative_bytes: Arc<AtomicUsize>,
    pending_bytes: Arc<AtomicUsize>,
    pending_chunks: Arc<AtomicUsize>,
    chunk_threshold: usize,
}

impl Default for FlowController {
    fn default() -> Self {
        Self::new(INITIAL_WINDOW_CREDITS)
    }
}

impl FlowController {
    pub fn new(initial_credits: usize) -> Self {
        Self {
            semaphore: Arc::new(Semaphore::new(initial_credits)),
            cumulative_bytes: Arc::new(AtomicUsize::new(0)),
            pending_bytes: Arc::new(AtomicUsize::new(0)),
            pending_chunks: Arc::new(AtomicUsize::new(0)),
            chunk_threshold: (initial_credits / 2).max(1),
        }
    }

    pub async fn acquire_credit(&self) -> bool {
        if let Ok(permit) = self.semaphore.acquire().await {
            permit.forget();
            true
        } else {
            false
        }
    }

    pub fn try_acquire_credit(&self) -> bool {
        match self.semaphore.try_acquire() {
            Ok(permit) => {
                permit.forget();
                true
            }
            Err(_) => false,
        }
    }

    pub fn grant_credits(&self, credits: u16, bytes_consumed: u32) {
        self.cumulative_bytes
            .store(bytes_consumed as usize, Ordering::Relaxed);
        self.semaphore.add_permits(credits as usize);
    }

    pub fn should_grant_credits(consumed: usize) -> bool {
        consumed >= CREDIT_THRESHOLD_BYTES
    }

    /// Reconciles inbound consumed bytes and emits `(credits_to_grant, cumulative_bytes_u32)`
    /// once either `CREDIT_THRESHOLD_BYTES` (512 KB) or half-window chunks (32) are consumed,
    /// preventing deadlock on small payloads.
    pub fn reconcile_bytes(&self, bytes: usize) -> Option<(u16, u32)> {
        let total = self.cumulative_bytes.fetch_add(bytes, Ordering::Relaxed) + bytes;
        let pending_b = self.pending_bytes.fetch_add(bytes, Ordering::Relaxed) + bytes;
        let pending_c = self.pending_chunks.fetch_add(1, Ordering::Relaxed) + 1;

        if Self::should_grant_credits(pending_b) || pending_c >= self.chunk_threshold {
            let chunks = self.pending_chunks.swap(0, Ordering::AcqRel);
            self.pending_bytes.swap(0, Ordering::AcqRel);
            if chunks > 0 {
                let credits = chunks.min(u16::MAX as usize) as u16;
                return Some((credits, total as u32));
            }
        }
        None
    }

    pub fn available_credits(&self) -> usize {
        self.semaphore.available_permits()
    }

    pub fn cumulative_bytes(&self) -> usize {
        self.cumulative_bytes.load(Ordering::Relaxed)
    }

    pub fn close(&self) {
        self.semaphore.close();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn try_acquire_and_grant_credits() {
        let flow = FlowController::new(2);
        assert_eq!(flow.available_credits(), 2);
        assert!(flow.try_acquire_credit());
        assert!(flow.try_acquire_credit());
        assert!(!flow.try_acquire_credit());
        assert_eq!(flow.available_credits(), 0);

        flow.grant_credits(2, 32 * 1024);
        assert_eq!(flow.available_credits(), 2);
        assert_eq!(flow.cumulative_bytes(), 32 * 1024);
        assert!(flow.try_acquire_credit());
    }

    #[tokio::test]
    async fn async_acquire_unblocks_on_grant() {
        let flow = FlowController::new(1);
        assert!(flow.try_acquire_credit());

        let waiter = flow.clone();
        let handle = tokio::spawn(async move { waiter.acquire_credit().await });
        tokio::task::yield_now().await;
        assert!(!handle.is_finished());

        flow.grant_credits(1, 16 * 1024);
        assert!(handle.await.unwrap());
        assert_eq!(flow.available_credits(), 0);
    }

    #[test]
    fn reconcile_bytes_grants_at_512kb_threshold() {
        let flow = FlowController::new(INITIAL_WINDOW_CREDITS);
        assert!(!FlowController::should_grant_credits(CREDIT_THRESHOLD_BYTES - 1));
        assert!(FlowController::should_grant_credits(CREDIT_THRESHOLD_BYTES));

        for i in 1..32 {
            assert_eq!(flow.reconcile_bytes(16 * 1024), None, "chunk {i}");
        }
        let grant = flow.reconcile_bytes(16 * 1024);
        assert_eq!(grant, Some((32, CREDIT_THRESHOLD_BYTES as u32)));
        assert_eq!(flow.cumulative_bytes(), CREDIT_THRESHOLD_BYTES);
    }

    #[test]
    fn reconcile_bytes_prevents_deadlock_on_small_frames() {
        let flow = FlowController::new(INITIAL_WINDOW_CREDITS);
        for _ in 0..31 {
            assert_eq!(flow.reconcile_bytes(64), None);
        }
        assert_eq!(flow.reconcile_bytes(64), Some((32, 32 * 64)));
    }
}
