use jni::objects::JClass;
use jni::sys::{jboolean, jdouble, jint, jlong};
use jni::JNIEnv;
use std::sync::{Mutex, OnceLock};

const FLAG_DRAW_NORTH_SOUTH: i32 = 1;
const FLAG_DRAW_EAST_WEST: i32 = 1 << 1;
const FLAG_TRAJECTORY_NORTH_SOUTH: i32 = 1 << 2;
const IMPACT_Y_CONFIRMATIONS: i32 = 3;
const IMPACT_Y_OFFSETS: [i32; 5] = [0, -1, 1, -2, 2];
const STOPPED_TNT_SPEED_SQ: f64 = 0.0001;
const TNT_CAPTURE_NONE: i32 = 0;
const TNT_CAPTURE_DIRECT: i32 = 1;
const TNT_CAPTURE_SNAPSHOT: i32 = 2;
const MAX_TNT_SNAPSHOTS: usize = 256;
const MAX_ACTIVE_PATCHES: usize = 128;
const MAX_PATCH_LINES: usize = 256;

fn unpack_block_x(packed: i64) -> i32 {
    (packed >> 38) as i32
}

fn unpack_block_y(packed: i64) -> i32 {
    (packed << 26 >> 52) as i32
}

fn unpack_block_z(packed: i64) -> i32 {
    (packed << 38 >> 38) as i32
}

fn pack_block_pos(x: i32, y: i32, z: i32) -> i64 {
    ((x as i64 & 0x3ff_ffff) << 38) | ((y as i64 & 0xfff) << 26) | (z as i64 & 0x3ff_ffff)
}

#[derive(Clone, Copy)]
struct PatchRecord {
    origin: i64,
    terminal: i64,
}

impl PatchRecord {
    const EMPTY: Self = Self {
        origin: 0,
        terminal: 0,
    };
}

#[derive(Clone, Copy)]
struct PatchLine {
    origin: i64,
    terminal: i64,
    expires_at_ms: i64,
}

impl PatchLine {
    const EMPTY: Self = Self {
        origin: 0,
        terminal: 0,
        expires_at_ms: 0,
    };
}

#[derive(Clone, Copy)]
struct TntSnapshot {
    x: f64,
    y: f64,
    z: f64,
}

impl TntSnapshot {
    const EMPTY: Self = Self {
        x: 0.0,
        y: 0.0,
        z: 0.0,
    };
}

struct PatchCrumbState {
    active: bool,
    x: f64,
    y: f64,
    z: f64,
    expires_at_ms: i64,
    detection_timeout_ms: i64,
    flags: i32,
    revision: i64,
    has_stable_impact_y: bool,
    stable_impact_y: i32,
    pending_impact_y: i32,
    pending_confirmations: i32,
    tnt_snapshots: [TntSnapshot; MAX_TNT_SNAPSHOTS],
    tnt_snapshot_count: usize,
    selected_tnt_packed: i64,
    active_patches: [PatchRecord; MAX_ACTIVE_PATCHES],
    active_patch_count: usize,
    patch_lines: [PatchLine; MAX_PATCH_LINES],
    patch_line_count: usize,
    has_scan_schedule: bool,
    last_scan_tick: i32,
    last_scan_revision: i64,
    last_scan_player_cell: i64,
    last_scan_side: i32,
    checking: bool,
}

impl PatchCrumbState {
    fn new() -> Self {
        Self {
            active: false,
            x: f64::NAN,
            y: f64::NAN,
            z: f64::NAN,
            expires_at_ms: 0,
            detection_timeout_ms: 0,
            flags: FLAG_TRAJECTORY_NORTH_SOUTH,
            revision: 0,
            has_stable_impact_y: false,
            stable_impact_y: 0,
            pending_impact_y: 0,
            pending_confirmations: 0,
            tnt_snapshots: [TntSnapshot::EMPTY; MAX_TNT_SNAPSHOTS],
            tnt_snapshot_count: 0,
            selected_tnt_packed: i64::MIN,
            active_patches: [PatchRecord::EMPTY; MAX_ACTIVE_PATCHES],
            active_patch_count: 0,
            patch_lines: [PatchLine::EMPTY; MAX_PATCH_LINES],
            patch_line_count: 0,
            has_scan_schedule: false,
            last_scan_tick: i32::MIN,
            last_scan_revision: i64::MIN,
            last_scan_player_cell: i64::MIN,
            last_scan_side: 0,
            checking: false,
        }
    }

    fn reset(&mut self) {
        let revision = self.revision.saturating_add(1);
        *self = Self::new();
        self.revision = revision;
    }

    fn stabilize_y(&mut self, candidate: i32) -> i32 {
        if !self.has_stable_impact_y {
            self.has_stable_impact_y = true;
            self.stable_impact_y = candidate;
            self.pending_impact_y = 0;
            self.pending_confirmations = 0;
            return candidate;
        }
        if candidate == self.stable_impact_y {
            self.pending_impact_y = 0;
            self.pending_confirmations = 0;
            return self.stable_impact_y;
        }
        if (candidate as i64 - self.stable_impact_y as i64).abs() > 1 {
            self.stable_impact_y = candidate;
            self.pending_impact_y = 0;
            self.pending_confirmations = 0;
            return candidate;
        }
        if candidate != self.pending_impact_y {
            self.pending_impact_y = candidate;
            self.pending_confirmations = 1;
        } else {
            self.pending_confirmations = self.pending_confirmations.saturating_add(1);
        }
        if self.pending_confirmations >= IMPACT_Y_CONFIRMATIONS {
            self.stable_impact_y = candidate;
            self.pending_impact_y = 0;
            self.pending_confirmations = 0;
        }
        self.stable_impact_y
    }

    fn resolve_impact_y(&mut self, base_y: i32, obsidian_mask: i32, solid_mask: i32) -> i32 {
        let selected_mask = if obsidian_mask != 0 {
            obsidian_mask
        } else {
            solid_mask
        };
        let mut candidate = base_y;
        for (index, offset) in IMPACT_Y_OFFSETS.iter().enumerate() {
            if selected_mask & (1 << index) != 0 {
                candidate = base_y.saturating_add(*offset);
                break;
            }
        }
        self.stabilize_y(candidate)
    }

    fn remove_expired_lines(&mut self, now_ms: i64) {
        let mut write = 0;
        for read in 0..self.patch_line_count {
            let line = self.patch_lines[read];
            if line.expires_at_ms > now_ms {
                self.patch_lines[write] = line;
                write += 1;
            }
        }
        self.patch_line_count = write;
    }

    fn clear_patch_geometry(&mut self) {
        self.active_patch_count = 0;
        self.patch_line_count = 0;
        self.reset_scan_schedule();
    }

    fn reset_scan_schedule(&mut self) {
        self.has_scan_schedule = false;
        self.last_scan_tick = i32::MIN;
        self.last_scan_revision = i64::MIN;
        self.last_scan_player_cell = i64::MIN;
        self.last_scan_side = 0;
    }

    fn push_tnt_snapshot(&mut self, x: f64, y: f64, z: f64) {
        if self.tnt_snapshot_count == MAX_TNT_SNAPSHOTS {
            self.tnt_snapshots.copy_within(1..MAX_TNT_SNAPSHOTS, 0);
            self.tnt_snapshot_count -= 1;
        }
        let index = self.tnt_snapshot_count;
        self.tnt_snapshots[index] = TntSnapshot { x, y, z };
        self.tnt_snapshot_count += 1;
    }

    fn is_same_crumb(&self, x: f64, z: f64) -> bool {
        self.x.ceil() == x.ceil() && self.z.ceil() == z.ceil()
    }

    #[allow(clippy::too_many_arguments)]
    fn prepare_tnt_capture(
        &mut self,
        sand_mode: bool,
        x: f64,
        y: f64,
        z: f64,
        motion_x: f64,
        motion_y: f64,
        motion_z: f64,
        sand_below: bool,
        now_ms: i64,
    ) -> i32 {
        if now_ms <= self.detection_timeout_ms {
            return TNT_CAPTURE_NONE;
        }
        if !sand_mode {
            return if self.is_same_crumb(x, z) {
                TNT_CAPTURE_NONE
            } else {
                TNT_CAPTURE_DIRECT
            };
        }

        let speed_sq = motion_x * motion_x + motion_y * motion_y + motion_z * motion_z;
        if !self.checking && speed_sq < STOPPED_TNT_SPEED_SQ && sand_below {
            let selected = self.tnt_snapshots[..self.tnt_snapshot_count]
                .iter()
                .find(|snapshot| snapshot.x == x && snapshot.z == z)
                .copied();
            self.tnt_snapshot_count = 0;
            if let Some(snapshot) = selected {
                self.selected_tnt_packed = pack_block_pos(
                    snapshot.x.floor() as i32,
                    (snapshot.y + 0.5).floor() as i32,
                    snapshot.z.floor() as i32,
                );
                if !self.is_same_crumb(snapshot.x, snapshot.z) {
                    return TNT_CAPTURE_SNAPSHOT;
                }
            }
            return TNT_CAPTURE_NONE;
        }

        self.checking = false;
        self.push_tnt_snapshot(x, y, z);
        TNT_CAPTURE_NONE
    }

    fn push_scan_result(&mut self, origin: i64, terminal: i64, expires_at_ms: i64) {
        if self.active_patch_count < MAX_ACTIVE_PATCHES {
            let index = self.active_patch_count;
            self.active_patches[index] = PatchRecord { origin, terminal };
            self.active_patch_count += 1;
        }
        if let Some(index) = self.patch_lines[..self.patch_line_count]
            .iter()
            .position(|line| line.origin == origin && line.terminal == terminal)
        {
            self.patch_lines[index].expires_at_ms = expires_at_ms;
            return;
        }
        if self.patch_line_count == MAX_PATCH_LINES {
            self.patch_lines.copy_within(1..MAX_PATCH_LINES, 0);
            self.patch_line_count -= 1;
        }
        let index = self.patch_line_count;
        self.patch_lines[index] = PatchLine {
            origin,
            terminal,
            expires_at_ms,
        };
        self.patch_line_count += 1;
    }

    fn submit_scan_lane(
        &mut self,
        origin: i64,
        obsidian_mask: i64,
        blocking_mask: i64,
        expires_at_ms: i64,
    ) {
        let blocking_mask = blocking_mask as u64;
        if blocking_mask == 0 {
            return;
        }
        let first_index = blocking_mask.trailing_zeros();
        let first_bit = 1_u64 << first_index;
        if obsidian_mask as u64 & first_bit == 0 {
            return;
        }
        let distance = first_index as i32 + 1;
        let trajectory_north_south = self.flags & FLAG_TRAJECTORY_NORTH_SOUTH != 0;
        let step_x = if trajectory_north_south {
            self.last_scan_side
        } else {
            0
        };
        let step_z = if trajectory_north_south {
            0
        } else {
            self.last_scan_side
        };
        let terminal = pack_block_pos(
            unpack_block_x(origin) + step_x * distance,
            unpack_block_y(origin),
            unpack_block_z(origin) + step_z * distance,
        );
        self.push_scan_result(origin, terminal, expires_at_ms);
    }
}

static PATCH_CRUMB_STATE: OnceLock<Mutex<PatchCrumbState>> = OnceLock::new();

pub(crate) fn quiesce() {
    if let Ok(mut state) = PATCH_CRUMB_STATE
        .get_or_init(|| Mutex::new(PatchCrumbState::new()))
        .lock()
    {
        for snapshot in state.tnt_snapshots.iter_mut() {
            *snapshot = TntSnapshot::EMPTY;
        }
        for patch in state.active_patches.iter_mut() {
            *patch = PatchRecord::EMPTY;
        }
        for line in state.patch_lines.iter_mut() {
            *line = PatchLine::EMPTY;
        }
        *state = PatchCrumbState::new();
    }
}

fn state() -> &'static Mutex<PatchCrumbState> {
    PATCH_CRUMB_STATE.get_or_init(|| Mutex::new(PatchCrumbState::new()))
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_resetPatchCrumbStateNative(
    _env: JNIEnv,
    _class: JClass,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = state().lock() {
        state.reset();
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_expirePatchCrumbNative(
    _env: JNIEnv,
    _class: JClass,
    now_ms: jlong,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    state()
        .lock()
        .map(|mut state| {
            if state.active && now_ms > state.expires_at_ms {
                state.active = false;
                state.flags &= FLAG_TRAJECTORY_NORTH_SOUTH;
                state.tnt_snapshot_count = 0;
                state.checking = false;
                state.clear_patch_geometry();
                1
            } else {
                0
            }
        })
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_preparePatchCrumbTntCaptureNative(
    _env: JNIEnv,
    _class: JClass,
    sand_mode: jboolean,
    x: jdouble,
    y: jdouble,
    z: jdouble,
    motion_x: jdouble,
    motion_y: jdouble,
    motion_z: jdouble,
    sand_below: jboolean,
    now_ms: jlong,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    state()
        .lock()
        .map(|mut state| {
            state.prepare_tnt_capture(
                sand_mode != 0,
                x,
                y,
                z,
                motion_x,
                motion_y,
                motion_z,
                sand_below != 0,
                now_ms,
            )
        })
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_completePatchCrumbTntCaptureNative(
    _env: JNIEnv,
    _class: JClass,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = state().lock() {
        state.checking = true;
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_patchCrumbTntSnapshotPackedNative(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    state()
        .lock()
        .map(|state| state.selected_tnt_packed)
        .unwrap_or(i64::MIN)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_beginPatchCrumbPatchScanNative(
    _env: JNIEnv,
    _class: JClass,
    now_ms: jlong,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = state().lock() {
        state.remove_expired_lines(now_ms);
        state.active_patch_count = 0;
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_shouldScanPatchCrumbGuideNative(
    _env: JNIEnv,
    _class: JClass,
    current_tick: jint,
    revision: jlong,
    player_cell: jlong,
    patch_side: jint,
    interval_ticks: jint,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    state()
        .lock()
        .map(|mut state| {
            if state.has_scan_schedule
                && state.last_scan_revision == revision
                && state.last_scan_player_cell == player_cell
                && state.last_scan_side == patch_side
                && current_tick.wrapping_sub(state.last_scan_tick) < interval_ticks
            {
                return 0;
            }
            state.has_scan_schedule = true;
            state.last_scan_tick = current_tick;
            state.last_scan_revision = revision;
            state.last_scan_player_cell = player_cell;
            state.last_scan_side = patch_side;
            1
        })
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_submitPatchCrumbScanLaneNative(
    _env: JNIEnv,
    _class: JClass,
    origin: jlong,
    obsidian_mask: jlong,
    blocking_mask: jlong,
    expires_at_ms: jlong,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = state().lock() {
        state.submit_scan_lane(origin, obsidian_mask, blocking_mask, expires_at_ms);
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_clearPatchCrumbActivePatchesNative(
    _env: JNIEnv,
    _class: JClass,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = state().lock() {
        state.active_patch_count = 0;
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_syncPatchCrumbsToSimpleAssistNative(
    _env: JNIEnv,
    _class: JClass,
) {
    crate::lifecycle::jni_guarded!();

    crate::simple_assist::reset_patch_frame();
    if let Ok(state) = state().lock() {
        for index in 0..state.active_patch_count {
            let patch = state.active_patches[index];
            crate::simple_assist::push_patch_frame(patch.origin, patch.terminal);
        }
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_expirePatchCrumbLinesNative(
    _env: JNIEnv,
    _class: JClass,
    now_ms: jlong,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = state().lock() {
        state.remove_expired_lines(now_ms);
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_patchCrumbLineCountNative(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    state()
        .lock()
        .map(|state| state.patch_line_count as jint)
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_patchCrumbLineOriginNative(
    _env: JNIEnv,
    _class: JClass,
    index: jint,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    if index < 0 {
        return i64::MIN;
    }
    state()
        .lock()
        .ok()
        .and_then(|state| {
            if index as usize >= state.patch_line_count {
                None
            } else {
                Some(state.patch_lines[index as usize].origin)
            }
        })
        .unwrap_or(i64::MIN)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_patchCrumbLineTerminalNative(
    _env: JNIEnv,
    _class: JClass,
    index: jint,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    if index < 0 {
        return i64::MIN;
    }
    state()
        .lock()
        .ok()
        .and_then(|state| {
            if index as usize >= state.patch_line_count {
                None
            } else {
                Some(state.patch_lines[index as usize].terminal)
            }
        })
        .unwrap_or(i64::MIN)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_resolvePatchCrumbImpactYNative(
    _env: JNIEnv,
    _class: JClass,
    base_y: jint,
    obsidian_mask: jint,
    solid_mask: jint,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    state()
        .lock()
        .map(|mut state| state.resolve_impact_y(base_y, obsidian_mask, solid_mask))
        .unwrap_or(base_y)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_resolvePatchCrumbDirectionNative(
    _env: JNIEnv,
    _class: JClass,
    direction_mode: jint,
    x_collision: jboolean,
    z_collision: jboolean,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = state().lock() {
        state.flags = match direction_mode {
            // Auto: preserve the original Java truth table exactly.
            0 => {
                let x_collision = x_collision != 0;
                let z_collision = z_collision != 0;
                FLAG_DRAW_NORTH_SOUTH
                    | if x_collision || !z_collision {
                        FLAG_DRAW_EAST_WEST
                    } else {
                        0
                    }
                    | if !x_collision {
                        FLAG_TRAJECTORY_NORTH_SOUTH
                    } else {
                        0
                    }
            }
            // North/South.
            2 => FLAG_DRAW_NORTH_SOUTH | FLAG_TRAJECTORY_NORTH_SOUTH,
            // East/West.
            3 => FLAG_DRAW_EAST_WEST,
            // Both and any unknown mode retain the legacy default.
            _ => FLAG_DRAW_NORTH_SOUTH | FLAG_DRAW_EAST_WEST | FLAG_TRAJECTORY_NORTH_SOUTH,
        };
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_capturePatchCrumbNative(
    _env: JNIEnv,
    _class: JClass,
    x: jdouble,
    y: jdouble,
    z: jdouble,
    now_ms: jlong,
    detection_cooldown_ms: jlong,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = state().lock() {
        state.active = true;
        // TNT positions contain small physics residuals around the block center.
        // Persist the logical block center so rendering and scan-side selection
        // cannot drift a few hundredths to either horizontal side.
        state.x = x.floor() + 0.5;
        state.y = y;
        state.z = z.floor() + 0.5;
        state.expires_at_ms = now_ms.saturating_add(6000);
        state.detection_timeout_ms = now_ms.saturating_add(detection_cooldown_ms.max(0));
        state.revision = state.revision.saturating_add(1);
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_isPatchCrumbActiveNative(
    _env: JNIEnv,
    _class: JClass,
    now_ms: jlong,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    state()
        .lock()
        .map(|state| (state.active && now_ms <= state.expires_at_ms) as jboolean)
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_patchCrumbPackedPosNative(
    _env: JNIEnv,
    _class: JClass,
    now_ms: jlong,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    state()
        .lock()
        .map(|state| {
            if state.active && now_ms <= state.expires_at_ms {
                pack_block_pos(
                    state.x.floor() as i32,
                    state.y.floor() as i32,
                    state.z.floor() as i32,
                )
            } else {
                i64::MIN
            }
        })
        .unwrap_or(i64::MIN)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_patchCrumbRevisionNative(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    state().lock().map(|state| state.revision).unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_patchCrumbFlagsNative(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    state().lock().map(|state| state.flags).unwrap_or(0)
}
