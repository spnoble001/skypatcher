//! Native lifecycle: soft quiesce + ACTIVE_CALLS drain + UnregisterNatives.
//!
//! Physical FreeLibrary/dlclose is intentionally not done here: HotSpot keeps
//! System.load libraries pinned to the ClassLoader for the process lifetime.

use std::sync::atomic::{AtomicU32, AtomicU8, Ordering};
use std::time::{Duration, Instant};

pub const LIFECYCLE_ACTIVE: u8 = 1;
pub const LIFECYCLE_QUIESCING: u8 = 2;
pub const LIFECYCLE_DORMANT: u8 = 3;

static NATIVE_LIFECYCLE: AtomicU8 = AtomicU8::new(LIFECYCLE_ACTIVE);
static ACTIVE_CALLS: AtomicU32 = AtomicU32::new(0);

/// RAII ticket held for the duration of a guarded JNI entry.
pub struct JniCallGuard;

pub fn lifecycle() -> u8 {
    NATIVE_LIFECYCLE.load(Ordering::Acquire)
}

pub fn is_dormant() -> bool {
    lifecycle() == LIFECYCLE_DORMANT
}

/// Enter a normal JNI export. Fails once quiesce has begun.
pub fn enter() -> Option<JniCallGuard> {
    if NATIVE_LIFECYCLE.load(Ordering::Acquire) != LIFECYCLE_ACTIVE {
        return None;
    }
    ACTIVE_CALLS.fetch_add(1, Ordering::AcqRel);
    if NATIVE_LIFECYCLE.load(Ordering::Acquire) != LIFECYCLE_ACTIVE {
        ACTIVE_CALLS.fetch_sub(1, Ordering::AcqRel);
        return None;
    }
    Some(JniCallGuard)
}

impl Drop for JniCallGuard {
    fn drop(&mut self) {
        ACTIVE_CALLS.fetch_sub(1, Ordering::Release);
    }
}

/// ACTIVE -> QUIESCING. Returns false if already quiescing/dormant.
pub fn begin_quiesce() -> bool {
    NATIVE_LIFECYCLE
        .compare_exchange(
            LIFECYCLE_ACTIVE,
            LIFECYCLE_QUIESCING,
            Ordering::AcqRel,
            Ordering::Acquire,
        )
        .is_ok()
}

/// Block until every guarded JNI call has returned (or timeout).
pub fn wait_until_idle() {
    let start = Instant::now();
    while ACTIVE_CALLS.load(Ordering::Acquire) != 0 {
        if start.elapsed() > Duration::from_secs(5) {
            break;
        }
        std::thread::yield_now();
        std::thread::sleep(Duration::from_micros(100));
    }
}

pub fn finish_dormant() {
    NATIVE_LIFECYCLE.store(LIFECYCLE_DORMANT, Ordering::Release);
}

macro_rules! jni_guarded {
    () => {
        let Some(_pp_jni_guard) = $crate::lifecycle::enter() else {
            return;
        };
    };
    ($default:expr) => {
        let Some(_pp_jni_guard) = $crate::lifecycle::enter() else {
            return $default;
        };
    };
}
pub(crate) use jni_guarded;
