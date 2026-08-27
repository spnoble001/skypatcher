use jni::objects::JClass;
use jni::sys::{jboolean, jdouble, jfloat, jint, jlong};
use jni::JNIEnv;
use std::collections::{HashSet, VecDeque};
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

const SCAN_HALF_EXTENT: f64 = 6.5;
/// Mirrors Java's distance-sorted 12x12x12 offset table. Keeping the cursor in
/// Rust makes scan publication atomic even though Java reads the world.
/// Near offsets are ordered first. This budget keeps anchors close to the
/// player responsive without allowing a dense obsidian field to monopolize a
/// client tick.
const SCAN_POSITIONS_PER_BATCH: i32 = 864;
const SCAN_VOLUME_POSITION_COUNT: i32 = 1_728;
const MAX_VISIBLE_BLOCKS: usize = 256;
const MAX_VISIBLE_ANCHORS: usize = 512;
/// A one-block drift is tolerated so the two-tick local scan can finish; a
/// larger displacement starts a fresh volume around the new player position.
const SCAN_RESTART_DISTANCE: i64 = 1;
/// A valid client tick advances the scan cursor every 50 ms. Two seconds is
/// intentionally generous and only recovers a genuinely wedged transaction.
const SCAN_STALL_TIMEOUT_MS: i64 = 2_000;
const ACTION_STALL_TIMEOUT_TICKS: i32 = 20;
const USER_ANCHOR_DELAY_MS: i64 = 2_000;
const USER_ANCHOR_MEMORY_MS: i64 = 3_000;
const CLICKER_ACTION_RELEASE: i32 = 1;
const CLICKER_ACTION_CLICK: i32 = 2;
const CLICKER_OPERATION_NEXT: i32 = 0;
const CLICKER_OPERATION_DEACTIVATE: i32 = 1;
const CLICKER_OPERATION_RESET_CONTROL: i32 = 2;
const WORK_BEGIN_PLACEMENT_CHECKS: i32 = 0;
const WORK_PLACEMENT_RESULT: i32 = 4;
const WORK_BEGIN_LOCAL_REFRESHES: i32 = 8;
const WORK_LOCAL_REFRESH_RESULT: i32 = 16;
const RENDER_FACE_SHIFT: u32 = 34;
const RENDER_FACE_MASK: i64 = 0xf_i64 << RENDER_FACE_SHIFT;

#[derive(Clone, Copy, Eq, Hash, PartialEq)]
struct AnchorRecord {
    position: i64,
    face: i32,
}

/// Immutable admission candidate produced by PrePatch's own AND gate.
/// `origin` is exactly `spread` blocks away from the obsidian terminal in the
/// selected exposed-face direction, so SimpleAssist places exactly `spread`
/// cells after stepping off the already-solid terminal.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct PrePatchIntent {
    pub origin: i64,
    pub terminal: i64,
    pub face: i32,
}

#[derive(Clone, Copy)]
struct PlacementCheck {
    position: i64,
    ticks_remaining: i32,
}

#[derive(Clone, Copy)]
struct UserPlacement {
    position: i64,
    placed_at: i64,
    activated: bool,
}

struct PrePatchClickerState {
    last_click: i64,
    last_down: i64,
    released: bool,
    click_delay: f64,
    drops: bool,
    spikes: bool,
    drops_reset: i32,
    spikes_reset: i32,
    drop_counter: i32,
    spike_counter: i32,
    clicks: i32,
    clicks_reset: i32,
    edited_cps: f32,
    rng_state: u64,
}

impl PrePatchClickerState {
    fn new() -> Self {
        Self {
            last_click: 0,
            last_down: 0,
            released: true,
            click_delay: -1.0,
            drops: false,
            spikes: false,
            drops_reset: 0,
            spikes_reset: 0,
            drop_counter: 0,
            spike_counter: 0,
            clicks: 0,
            clicks_reset: 0,
            edited_cps: 0.0,
            rng_state: initial_rng_seed().rotate_left(29) ^ 0xD1B5_4A32_D192_ED03,
        }
    }

    fn next_random_u64(&mut self) -> u64 {
        let mut value = self.rng_state;
        value ^= value >> 12;
        value ^= value << 25;
        value ^= value >> 27;
        self.rng_state = value;
        value.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    fn random_range(&mut self, minimum: i32, maximum: i32) -> i32 {
        if minimum >= maximum {
            return minimum;
        }
        minimum + (self.next_random_u64() % (maximum - minimum) as u64) as i32
    }

    fn random_delay(&mut self, cps: f32) -> f64 {
        let safe_cps = cps.max(1.0);
        if self.clicks_reset == 0 || self.clicks >= self.clicks_reset {
            self.clicks_reset = self.random_range(5, 15);
            self.clicks = 0;
            self.edited_cps = (safe_cps - 3.0 + self.random_range(0, 6) as f32).max(1.0);
        }
        self.clicks += 1;
        if !self.drops && !self.spikes && self.random_range(0, 100) <= 40 {
            self.drops = true;
            self.drops_reset = self.random_range(15, 25);
        }
        if !self.drops && !self.spikes && self.random_range(0, 100) <= 25 {
            self.spikes = true;
            self.spikes_reset = self.random_range(15, 25);
        }
        if self.drops {
            let counter = self.drop_counter;
            self.drop_counter += 1;
            if counter <= self.drops_reset {
                return f64::from(self.random_range(1050, 1100)) / f64::from(self.edited_cps);
            }
            self.drop_counter = 0;
            self.drops = false;
        }
        if self.spikes {
            let counter = self.spike_counter;
            self.spike_counter += 1;
            if counter <= self.spikes_reset {
                return f64::from(self.random_range(900, 950)) / f64::from(self.edited_cps);
            }
            self.spike_counter = 0;
            self.spikes = false;
        }
        let minimum = self.random_range(925, 975);
        let maximum = self.random_range(1025, 1075);
        f64::from(self.random_range(minimum, maximum)) / f64::from(self.edited_cps)
    }

    fn next_action(&mut self, now: i64, cps: f32) -> i32 {
        let mut action = 0;
        if !self.released && now.wrapping_sub(self.last_down) > 5 {
            self.released = true;
            action |= CLICKER_ACTION_RELEASE;
        }
        if self.click_delay < 0.0 || (now.wrapping_sub(self.last_click) as f64) >= self.click_delay
        {
            self.last_click = now;
            self.last_down = now;
            self.released = false;
            self.click_delay = self.random_delay(cps);
            action |= CLICKER_ACTION_CLICK;
        }
        action
    }

    fn deactivate(&mut self) -> bool {
        let was_pressed = !self.released;
        self.released = true;
        self.click_delay = -1.0;
        was_pressed
    }

    fn reset_control(&mut self) {
        self.released = true;
        self.click_delay = -1.0;
    }

    fn update(&mut self, operation: i32, now: i64, cps: f32) -> i32 {
        match operation {
            CLICKER_OPERATION_NEXT => self.next_action(now, cps),
            CLICKER_OPERATION_DEACTIVATE => {
                if self.deactivate() {
                    CLICKER_ACTION_RELEASE
                } else {
                    0
                }
            }
            CLICKER_OPERATION_RESET_CONTROL => {
                self.reset_control();
                0
            }
            _ => 0,
        }
    }
}

struct PrePatchScanState {
    initialized: bool,
    east_west: bool,
    center_x: i32,
    center_y: i32,
    center_z: i32,
    frame_now: i64,
    last_scan_progress_ms: i64,
    player_x: f64,
    player_y: f64,
    player_z: f64,
    cursor_y: i32,
    max_y: i32,
    accumulating: Vec<AnchorRecord>,
    accumulating_index: HashSet<AnchorRecord>,
    visible: Vec<AnchorRecord>,
    visible_index: HashSet<AnchorRecord>,
    visible_positions: Vec<i64>,
    synthetic_anchor: Option<AnchorRecord>,
    synthetic_spread: i32,
    action_idle_ticks: i32,
    rng_state: u64,
    pending_local_refreshes: VecDeque<i64>,
    pending_local_refresh_index: HashSet<i64>,
    active_local_refresh: Option<i64>,
    pending_placement_checks: VecDeque<PlacementCheck>,
    active_placement_check: Option<PlacementCheck>,
    placement_checks_remaining: usize,
    user_placements: Vec<UserPlacement>,
}

impl PrePatchScanState {
    fn new() -> Self {
        Self {
            initialized: false,
            east_west: true,
            center_x: 0,
            center_y: 0,
            center_z: 0,
            frame_now: 0,
            last_scan_progress_ms: 0,
            player_x: 0.0,
            player_y: 0.0,
            player_z: 0.0,
            cursor_y: i32::MIN,
            max_y: i32::MIN,
            accumulating: Vec::new(),
            accumulating_index: HashSet::new(),
            visible: Vec::new(),
            visible_index: HashSet::new(),
            visible_positions: Vec::new(),
            synthetic_anchor: None,
            synthetic_spread: 0,
            action_idle_ticks: 0,
            rng_state: initial_rng_seed(),
            pending_local_refreshes: VecDeque::new(),
            pending_local_refresh_index: HashSet::new(),
            active_local_refresh: None,
            pending_placement_checks: VecDeque::new(),
            active_placement_check: None,
            placement_checks_remaining: 0,
            user_placements: Vec::new(),
        }
    }

    fn mark_user_placement(&mut self, position: i64, now: i64) {
        self.mark_action_progress();
        if let Some(placement) = self
            .user_placements
            .iter_mut()
            .find(|placement| placement.position == position)
        {
            placement.placed_at = now;
            placement.activated = false;
            return;
        }
        self.user_placements.push(UserPlacement {
            position,
            placed_at: now,
            activated: false,
        });
    }

    fn update_user_placement_timers(&mut self, now: i64) {
        let mut newly_activated = Vec::new();
        self.user_placements.retain_mut(|placement| {
            let age = now.wrapping_sub(placement.placed_at);
            if age >= USER_ANCHOR_DELAY_MS + USER_ANCHOR_MEMORY_MS {
                return false;
            }
            if age >= USER_ANCHOR_DELAY_MS && !placement.activated {
                placement.activated = true;
                newly_activated.push(placement.position);
            }
            true
        });
        for position in newly_activated {
            self.queue_local_refresh(position);
        }
    }

    fn user_placement_eligible(&mut self, position: i64, now: i64) -> bool {
        let Some(index) = self
            .user_placements
            .iter()
            .position(|placement| placement.position == position)
        else {
            return true;
        };
        let age = now.wrapping_sub(self.user_placements[index].placed_at);
        if age < USER_ANCHOR_DELAY_MS {
            return false;
        }
        if age >= USER_ANCHOR_DELAY_MS + USER_ANCHOR_MEMORY_MS {
            self.user_placements.remove(index);
        }
        true
    }

    fn queue_placement_check(&mut self, position: i64) {
        if let Some(check) = self
            .pending_placement_checks
            .iter_mut()
            .find(|check| check.position == position)
        {
            check.ticks_remaining = 3;
            return;
        }
        if let Some(check) = self.active_placement_check.as_mut() {
            if check.position == position {
                check.ticks_remaining = 3;
                return;
            }
        }
        self.pending_placement_checks.push_back(PlacementCheck {
            position,
            ticks_remaining: 3,
        });
    }

    fn cancel_placement_check(&mut self, position: i64) {
        self.pending_placement_checks
            .retain(|check| check.position != position);
        if self
            .active_placement_check
            .is_some_and(|check| check.position == position)
        {
            self.active_placement_check = None;
        }
    }

    fn poll_placement_check(&mut self) -> i64 {
        if let Some(check) = self.active_placement_check {
            return check.position;
        }
        let Some(check) = self.pending_placement_checks.pop_front() else {
            return i64::MIN;
        };
        self.active_placement_check = Some(check);
        check.position
    }

    fn begin_placement_checks(&mut self) -> i64 {
        self.placement_checks_remaining = self
            .pending_placement_checks
            .len()
            .saturating_add(usize::from(self.active_placement_check.is_some()));
        self.poll_placement_check()
    }

    fn resolve_placement_check(&mut self, placed: bool, now: i64) {
        let Some(mut check) = self.active_placement_check.take() else {
            return;
        };
        if placed {
            self.mark_user_placement(check.position, now);
            self.queue_local_refresh(check.position);
        } else if check.ticks_remaining > 1 {
            check.ticks_remaining -= 1;
            self.pending_placement_checks.push_back(check);
        }
    }

    fn resolve_and_poll_placement_check(&mut self, placed: bool, now: i64) -> i64 {
        self.resolve_placement_check(placed, now);
        self.placement_checks_remaining = self.placement_checks_remaining.saturating_sub(1);
        if self.placement_checks_remaining == 0 {
            return i64::MIN;
        }
        self.poll_placement_check()
    }

    fn record_block_placed(&mut self, position: i64, now: i64) {
        self.cancel_placement_check(position);
        self.mark_user_placement(position, now);
        self.queue_local_refresh(position);
    }

    fn queue_local_refresh_position(&mut self, position: i64) {
        if self.active_local_refresh != Some(position)
            && self.pending_local_refresh_index.insert(position)
        {
            self.pending_local_refreshes.push_back(position);
        }
    }

    fn queue_local_refresh(&mut self, position: i64) {
        self.queue_local_refresh_position(position);
        let (x, y, z) = unpack_block_pos(position);
        for face in 0..6 {
            let (offset_x, offset_y, offset_z) = face_offset(face).unwrap();
            self.queue_local_refresh_position(pack_block_pos(
                x.saturating_add(offset_x),
                y.saturating_add(offset_y),
                z.saturating_add(offset_z),
            ));
        }
    }

    fn poll_local_refresh(&mut self) -> i64 {
        if let Some(position) = self.active_local_refresh {
            return position;
        }
        let Some(position) = self.pending_local_refreshes.pop_front() else {
            return i64::MIN;
        };
        self.pending_local_refresh_index.remove(&position);
        self.active_local_refresh = Some(position);
        position
    }

    fn resolve_and_poll_local_refresh(
        &mut self,
        world_valid: bool,
        first_exposed: bool,
        second_exposed: bool,
    ) -> i64 {
        let Some(position) = self.active_local_refresh.take() else {
            return self.poll_local_refresh();
        };
        let valid = world_valid && self.is_within_scan_radius(position);
        self.refresh_candidate(position, valid, first_exposed, second_exposed);
        self.last_scan_progress_ms = self.frame_now;
        self.poll_local_refresh()
    }

    fn poll_work(&mut self, action: i32, now: i64) -> i64 {
        match action {
            WORK_BEGIN_PLACEMENT_CHECKS => self.begin_placement_checks(),
            action if action == WORK_PLACEMENT_RESULT || action == WORK_PLACEMENT_RESULT + 1 => {
                self.resolve_and_poll_placement_check(action & 1 != 0, now)
            }
            WORK_BEGIN_LOCAL_REFRESHES => self.poll_local_refresh(),
            action if action & WORK_LOCAL_REFRESH_RESULT != 0 => self
                .resolve_and_poll_local_refresh(action & 1 != 0, action & 2 != 0, action & 4 != 0),
            _ => i64::MIN,
        }
    }

    fn is_within_scan_radius(&self, position: i64) -> bool {
        let (x, y, z) = unpack_block_pos(position);
        let dx = f64::from(x) + 0.5 - self.player_x;
        let dy = f64::from(y) + 0.5 - self.player_y;
        let dz = f64::from(z) + 0.5 - self.player_z;
        dx.abs() <= SCAN_HALF_EXTENT && dy.abs() <= SCAN_HALF_EXTENT && dz.abs() <= SCAN_HALF_EXTENT
    }

    fn clear_synthetic(&mut self) {
        self.synthetic_anchor = None;
        self.synthetic_spread = 0;
        self.action_idle_ticks = 0;
    }

    fn mark_action_progress(&mut self) {
        if self.synthetic_anchor.is_some() {
            self.action_idle_ticks = 0;
        }
    }

    fn next_random_u64(&mut self) -> u64 {
        let mut value = self.rng_state;
        value ^= value >> 12;
        value ^= value << 25;
        value ^= value >> 27;
        self.rng_state = value;
        value.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    fn next_uniform(&mut self) -> f64 {
        const SCALE: f64 = 1.0 / ((1_u64 << 53) as f64);
        ((self.next_random_u64() >> 11) as f64 + 0.5) * SCALE
    }

    fn next_gaussian(&mut self) -> f64 {
        let first = self.next_uniform();
        let second = self.next_uniform();
        (-2.0 * first.ln()).sqrt() * (std::f64::consts::TAU * second).cos()
    }

    fn sample_lognormal_spread(&mut self, minimum: i32, maximum: i32) -> i32 {
        if minimum >= maximum {
            return minimum;
        }
        let log_minimum = f64::from(minimum.max(1)).ln();
        let log_maximum = f64::from(maximum.max(1)).ln();
        let mean = (log_minimum + log_maximum) * 0.5;
        let deviation = ((log_maximum - log_minimum) / 3.92).max(0.01);
        for _ in 0..12 {
            let sample = (mean + deviation * self.next_gaussian()).exp().round() as i32;
            if sample >= minimum && sample <= maximum {
                return sample;
            }
        }
        ((mean.exp().round() as i32).max(minimum)).min(maximum)
    }

    fn resolve_synthetic(&mut self, anchor: AnchorRecord, minimum: i32, maximum: i32) -> i64 {
        if !self.visible_index.contains(&anchor) || face_offset(anchor.face).is_none() {
            self.clear_synthetic();
            return i64::MIN;
        }
        if self.synthetic_anchor != Some(anchor) {
            self.synthetic_spread = self.sample_lognormal_spread(minimum, maximum);
            self.synthetic_anchor = Some(anchor);
            self.action_idle_ticks = 0;
        }
        self.active_intent()
            .map(|intent| intent.origin)
            .unwrap_or(i64::MIN)
    }

    /// PrePatch is the sole owner of its candidate geometry. This is a live
    /// read-only intent, never a PatchCrumbs record or a SimpleAssist frame
    /// member.
    fn active_intent(&self) -> Option<PrePatchIntent> {
        let anchor = self.synthetic_anchor?;
        if !self.visible_index.contains(&anchor) {
            return None;
        }
        let (offset_x, offset_y, offset_z) = face_offset(anchor.face)?;
        let (x, y, z) = unpack_block_pos(anchor.position);
        let origin = pack_block_pos(
            x.saturating_add(offset_x.saturating_mul(self.synthetic_spread)),
            y.saturating_add(offset_y.saturating_mul(self.synthetic_spread)),
            z.saturating_add(offset_z.saturating_mul(self.synthetic_spread)),
        );
        Some(PrePatchIntent {
            origin,
            terminal: anchor.position,
            face: anchor.face,
        })
    }

    /// Starts a new scan generation. A true restart must discard the previous
    /// visible snapshot too: otherwise repeated interrupted scans eventually
    /// fill the visible cap with anchors from old world positions.
    fn restart(&mut self, center_x: i32, center_y: i32, center_z: i32, clear_visible: bool) {
        self.initialized = true;
        self.center_x = center_x;
        self.center_y = center_y;
        self.center_z = center_z;
        self.cursor_y = 0;
        self.max_y = SCAN_VOLUME_POSITION_COUNT;
        self.accumulating.clear();
        self.accumulating_index.clear();
        self.last_scan_progress_ms = self.frame_now;
        if clear_visible {
            self.visible.clear();
            self.visible_index.clear();
            self.visible_positions.clear();
            self.clear_synthetic();
        }
    }

    fn ensure(&mut self, center_x: i32, center_y: i32, center_z: i32, east_west: bool) -> bool {
        let delta_x = i64::from(center_x) - i64::from(self.center_x);
        let delta_y = i64::from(center_y) - i64::from(self.center_y);
        let delta_z = i64::from(center_z) - i64::from(self.center_z);
        let moved_beyond_active_batch = delta_x * delta_x + delta_y * delta_y + delta_z * delta_z
            > SCAN_RESTART_DISTANCE * SCAN_RESTART_DISTANCE;
        let changed = !self.initialized || self.east_west != east_west || moved_beyond_active_batch;
        if changed {
            self.east_west = east_west;
            self.restart(center_x, center_y, center_z, true);
        }
        changed
    }

    fn begin_tick(&mut self, now: i64, player_x: f64, player_y: f64, player_z: f64) {
        self.frame_now = now;
        self.player_x = player_x;
        self.player_y = player_y;
        self.player_z = player_z;
        self.update_user_placement_timers(now);
        if self.synthetic_anchor.is_some() {
            self.action_idle_ticks = self.action_idle_ticks.saturating_add(1);
        }
    }

    fn begin_scan_batch(
        &mut self,
        center_x: i32,
        center_y: i32,
        center_z: i32,
        east_west: bool,
    ) -> i64 {
        self.ensure(center_x, center_y, center_z, east_west);
        self.next_batch()
    }

    fn next_batch(&mut self) -> i64 {
        if !self.initialized || self.cursor_y >= self.max_y {
            return i64::MIN;
        }
        let first_offset = self.cursor_y;
        let remaining = self.max_y.saturating_sub(self.cursor_y);
        let count = remaining.min(SCAN_POSITIONS_PER_BATCH);
        self.cursor_y = self.cursor_y.saturating_add(count);
        self.last_scan_progress_ms = self.frame_now;
        (i64::from(first_offset) << 32) | i64::from(count as u32)
    }

    fn finish_batch(&mut self, center_x: i32, center_y: i32, center_z: i32) {
        if self.initialized && self.cursor_y >= self.max_y {
            self.publish_accumulating();
            self.last_scan_progress_ms = self.frame_now;
            // Publication itself is atomic; retain that completed snapshot
            // while the next generation is being collected.
            self.restart(center_x, center_y, center_z, false);
        }
    }

    fn add_anchor(&mut self, anchor: AnchorRecord) {
        if self.accumulating_index.insert(anchor) {
            self.accumulating.push(anchor);
        }
    }

    fn remove_anchor(&mut self, anchor: AnchorRecord) {
        if self.accumulating_index.remove(&anchor) {
            self.accumulating.retain(|candidate| *candidate != anchor);
        }
    }

    fn publish_accumulating(&mut self) {
        self.visible.clear();
        self.visible_index.clear();
        self.visible_positions.clear();
        // Scan insertion order is nearest-first, so clipping here preserves
        // the anchors with the lowest practical placement latency.
        for anchor in self.accumulating.iter().copied() {
            if self.visible.len() >= MAX_VISIBLE_ANCHORS {
                break;
            }
            let known_position = self.visible_positions.contains(&anchor.position);
            if !known_position && self.visible_positions.len() >= MAX_VISIBLE_BLOCKS {
                continue;
            }
            if self.visible_index.insert(anchor) {
                if !known_position {
                    self.visible_positions.push(anchor.position);
                }
                self.visible.push(anchor);
            }
        }
    }

    fn add_visible_anchor(&mut self, anchor: AnchorRecord) {
        if self.visible_index.contains(&anchor) || self.visible.len() >= MAX_VISIBLE_ANCHORS {
            return;
        }
        let known_position = self.visible_positions.contains(&anchor.position);
        if !known_position && self.visible_positions.len() >= MAX_VISIBLE_BLOCKS {
            return;
        }
        if self.visible_index.insert(anchor) {
            if !known_position {
                self.visible_positions.push(anchor.position);
            }
            self.visible.push(anchor);
        }
    }

    fn remove_visible_anchor(&mut self, anchor: AnchorRecord) {
        if self.visible_index.remove(&anchor) {
            self.visible.retain(|candidate| *candidate != anchor);
            if !self
                .visible
                .iter()
                .any(|candidate| candidate.position == anchor.position)
            {
                self.visible_positions
                    .retain(|position| *position != anchor.position);
            }
        }
    }

    fn directional_faces(&self) -> (i32, i32) {
        if self.east_west {
            (5, 4)
        } else {
            (2, 3)
        }
    }

    fn submit_scan_candidate(&mut self, position: i64, first_exposed: bool, second_exposed: bool) {
        if !self.user_placement_eligible(position, self.frame_now) {
            return;
        }
        let (first, second) = self.directional_faces();
        if first_exposed {
            let anchor = AnchorRecord {
                position,
                face: first,
            };
            self.add_anchor(anchor);
            // Near offsets are scanned first. Expose each valid anchor now so
            // a crosshair hit does not wait for the rest of the sphere.
            self.add_visible_anchor(anchor);
        }
        if second_exposed {
            let anchor = AnchorRecord {
                position,
                face: second,
            };
            self.add_anchor(anchor);
            self.add_visible_anchor(anchor);
        }
        self.last_scan_progress_ms = self.frame_now;
    }

    fn watchdog_reset_if_stalled(&mut self, now: i64) -> bool {
        if !self.initialized
            || self.last_scan_progress_ms <= 0
            || now.saturating_sub(self.last_scan_progress_ms) <= SCAN_STALL_TIMEOUT_MS
        {
            return false;
        }
        *self = Self::new();
        true
    }

    fn watchdog_reset_action_if_stalled(&mut self) -> bool {
        if self.synthetic_anchor.is_none() || self.action_idle_ticks < ACTION_STALL_TIMEOUT_TICKS {
            return false;
        }
        self.restart(self.center_x, self.center_y, self.center_z, true);
        true
    }

    fn refresh_candidate(
        &mut self,
        position: i64,
        valid: bool,
        first_exposed: bool,
        second_exposed: bool,
    ) {
        let (first, second) = self.directional_faces();
        let first_anchor = AnchorRecord {
            position,
            face: first,
        };
        let second_anchor = AnchorRecord {
            position,
            face: second,
        };
        self.remove_anchor(first_anchor);
        self.remove_anchor(second_anchor);
        self.remove_visible_anchor(first_anchor);
        self.remove_visible_anchor(second_anchor);
        if !valid || !self.user_placement_eligible(position, self.frame_now) {
            return;
        }
        if first_exposed {
            self.add_anchor(first_anchor);
            self.add_visible_anchor(first_anchor);
        }
        if second_exposed {
            self.add_anchor(second_anchor);
            self.add_visible_anchor(second_anchor);
        }
    }
}

fn initial_rng_seed() -> u64 {
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or(0);
    let seed = timestamp as u64 ^ (timestamp >> 64) as u64 ^ 0x9E37_79B9_7F4A_7C15;
    if seed == 0 {
        1
    } else {
        seed
    }
}

fn unpack_signed(value: i64, bits: u32) -> i32 {
    let shift = 64 - bits;
    (value << shift >> shift) as i32
}

fn unpack_block_pos(position: i64) -> (i32, i32, i32) {
    (
        unpack_signed(position >> 38, 26),
        unpack_signed((position >> 26) & 0xfff, 12),
        unpack_signed(position & 0x3ff_ffff, 26),
    )
}

fn pack_block_pos(x: i32, y: i32, z: i32) -> i64 {
    ((i64::from(x) & 0x3ff_ffff) << 38)
        | ((i64::from(y) & 0xfff) << 26)
        | (i64::from(z) & 0x3ff_ffff)
}

fn face_offset(face: i32) -> Option<(i32, i32, i32)> {
    match face {
        0 => Some((0, -1, 0)),
        1 => Some((0, 1, 0)),
        2 => Some((0, 0, -1)),
        3 => Some((0, 0, 1)),
        4 => Some((-1, 0, 0)),
        5 => Some((1, 0, 0)),
        _ => None,
    }
}

fn pack_render_face(anchor: AnchorRecord) -> i64 {
    (anchor.position & !RENDER_FACE_MASK)
        | (i64::from(anchor.face.saturating_add(1)) << RENDER_FACE_SHIFT)
}

static PRE_PATCH_SCAN_STATE: OnceLock<Mutex<PrePatchScanState>> = OnceLock::new();
static PRE_PATCH_CLICKER_STATE: OnceLock<Mutex<PrePatchClickerState>> = OnceLock::new();

pub(crate) fn quiesce() {
    if let Ok(mut state) = PRE_PATCH_SCAN_STATE
        .get_or_init(|| Mutex::new(PrePatchScanState::new()))
        .lock()
    {
        for value in state.accumulating.iter_mut() {
            *value = AnchorRecord {
                position: 0,
                face: 0,
            };
        }
        for value in state.visible.iter_mut() {
            *value = AnchorRecord {
                position: 0,
                face: 0,
            };
        }
        for value in state.visible_positions.iter_mut() {
            *value = 0;
        }
        for value in state.pending_local_refreshes.iter_mut() {
            *value = 0;
        }
        for value in state.pending_placement_checks.iter_mut() {
            *value = PlacementCheck {
                position: 0,
                ticks_remaining: 0,
            };
        }
        for value in state.user_placements.iter_mut() {
            *value = UserPlacement {
                position: 0,
                placed_at: 0,
                activated: false,
            };
        }
        state.accumulating_index.drain();
        state.visible_index.drain();
        state.pending_local_refresh_index.drain();
        *state = PrePatchScanState::new();
        state.rng_state = 0;
    }
    if let Ok(mut state) = PRE_PATCH_CLICKER_STATE
        .get_or_init(|| Mutex::new(PrePatchClickerState::new()))
        .lock()
    {
        *state = PrePatchClickerState::new();
        state.rng_state = 0;
    }
}

fn state() -> &'static Mutex<PrePatchScanState> {
    PRE_PATCH_SCAN_STATE.get_or_init(|| Mutex::new(PrePatchScanState::new()))
}

/// Exact PrePatch admission gate. Position and face must both still match the
/// candidate selected by PrePatch's own tick; no candidate is published into
/// PatchCrumbs or cached by SimpleAssist before arbitration.
pub(crate) fn intent_matching(hit: i64, face: i32) -> Option<PrePatchIntent> {
    state().lock().ok().and_then(|state| {
        state
            .active_intent()
            .filter(|intent| intent.terminal == hit && intent.face == face)
    })
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_matchesPrePatchIntentNative(
    _env: JNIEnv,
    _class: JClass,
    hit: jlong,
    face: jint,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);
    intent_matching(hit, face).is_some() as jboolean
}

/// Exposes only the face of the current native intent for the client HUD.  The
/// Java side must never synthesize an indicator from a stale scan or configured
/// axis: absence of a live intent is represented by -1.
pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_prePatchActiveIntentFaceNative(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    crate::lifecycle::jni_guarded!(-1);

    state()
        .lock()
        .ok()
        .and_then(|state| state.active_intent().map(|intent| intent.face))
        .unwrap_or(-1)
}

/// Read-only endpoints of the currently live PrePatch intent.  These are
/// intentionally kept separate from the 1D SimpleAssist state: a second
/// executor may adopt the exact same immutable intent without becoming an
/// owner of PrePatch's scan or synthetic-spread lifecycle.
pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_prePatchActiveIntentOriginNative(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    crate::lifecycle::jni_guarded!(i64::MIN);

    state()
        .lock()
        .ok()
        .and_then(|state| state.active_intent().map(|intent| intent.origin))
        .unwrap_or(i64::MIN)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_prePatchActiveIntentTerminalNative(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    crate::lifecycle::jni_guarded!(i64::MIN);

    state()
        .lock()
        .ok()
        .and_then(|state| state.active_intent().map(|intent| intent.terminal))
        .unwrap_or(i64::MIN)
}

fn clicker_state() -> &'static Mutex<PrePatchClickerState> {
    PRE_PATCH_CLICKER_STATE.get_or_init(|| Mutex::new(PrePatchClickerState::new()))
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_resetPrePatchScanNative(
    _env: JNIEnv,
    _class: JClass,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = state().lock() {
        *state = PrePatchScanState::new();
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_watchdogPrePatchNative(
    _env: JNIEnv,
    _class: JClass,
    now: jlong,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    let recovered = state()
        .lock()
        .map(|mut state| {
            if state.watchdog_reset_if_stalled(now) {
                true
            } else {
                state.watchdog_reset_action_if_stalled()
            }
        })
        .unwrap_or(false);
    recovered as jboolean
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_prePatchDiagnosticTimingNative(
    _env: JNIEnv,
    _class: JClass,
    now: jlong,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    state()
        .lock()
        .map(|state| {
            let scan_age = if state.last_scan_progress_ms <= 0 {
                u32::MAX
            } else {
                now.saturating_sub(state.last_scan_progress_ms)
                    .min(i64::from(u32::MAX)) as u32
            };
            i64::from(scan_age)
                | (i64::from(state.action_idle_ticks.clamp(0, u16::MAX as i32) as u16) << 32)
                | (i64::from(state.user_placements.len().min(u16::MAX as usize) as u16) << 48)
        })
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_prePatchDiagnosticCountsNative(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    state()
        .lock()
        .map(|state| {
            i64::from(state.cursor_y.clamp(0, u16::MAX as i32) as u16)
                | (i64::from(state.max_y.clamp(0, u16::MAX as i32) as u16) << 16)
                | (i64::from(state.accumulating.len().min(u16::MAX as usize) as u16) << 32)
                | (i64::from(state.visible.len().min(u16::MAX as usize) as u16) << 48)
        })
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_beginPrePatchTickAndScanNative(
    _env: JNIEnv,
    _class: JClass,
    now: jlong,
    player_x: jdouble,
    player_y: jdouble,
    player_z: jdouble,
    center_x: jint,
    center_y: jint,
    center_z: jint,
    east_west: jboolean,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    state()
        .lock()
        .map(|mut state| {
            state.begin_tick(now, player_x, player_y, player_z);
            state.begin_scan_batch(center_x, center_y, center_z, east_west != 0)
        })
        .unwrap_or(i64::MIN)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_finishPrePatchTickNative(
    _env: JNIEnv,
    _class: JClass,
    center_x: jint,
    center_y: jint,
    center_z: jint,
    position: jlong,
    face: jint,
    minimum: jint,
    maximum: jint,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = state().lock() {
        state.finish_batch(center_x, center_y, center_z);
        if position == i64::MIN {
            state.clear_synthetic();
        } else {
            state.resolve_synthetic(AnchorRecord { position, face }, minimum, maximum);
        }
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_hasPrePatchVisibleAnchorNative(
    _env: JNIEnv,
    _class: JClass,
    position: jlong,
    face: jint,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    state()
        .lock()
        .map(|state| {
            state
                .visible_index
                .contains(&AnchorRecord { position, face }) as jboolean
        })
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_prePatchRenderCountsNative(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    state()
        .lock()
        .map(|state| {
            let face_count = state.visible.len().min(i32::MAX as usize) as u32;
            let block_count = state.visible_positions.len().min(i32::MAX as usize) as u32;
            (i64::from(block_count) << 32) | i64::from(face_count)
        })
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_prePatchRenderRecordNative(
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
            let index = index as usize;
            if index < state.visible_positions.len() {
                state.visible_positions.get(index).copied()
            } else {
                state
                    .visible
                    .get(index - state.visible_positions.len())
                    .copied()
                    .map(pack_render_face)
            }
        })
        .unwrap_or(i64::MIN)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_prePatchSyntheticSpreadNative(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    state()
        .lock()
        .map(|state| state.synthetic_spread)
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_queuePrePatchPlacementCheckNative(
    _env: JNIEnv,
    _class: JClass,
    hit_position: jlong,
    face: jint,
) {
    crate::lifecycle::jni_guarded!();

    let Some((offset_x, offset_y, offset_z)) = face_offset(face) else {
        return;
    };
    let (x, y, z) = unpack_block_pos(hit_position);
    let position = pack_block_pos(
        x.saturating_add(offset_x),
        y.saturating_add(offset_y),
        z.saturating_add(offset_z),
    );
    if let Ok(mut state) = state().lock() {
        state.queue_placement_check(position);
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_pollPrePatchWorkNative(
    _env: JNIEnv,
    _class: JClass,
    action: jint,
    now: jlong,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    state()
        .lock()
        .map(|mut state| state.poll_work(action, now))
        .unwrap_or(i64::MIN)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_recordPrePatchBlockPlacedNative(
    _env: JNIEnv,
    _class: JClass,
    position: jlong,
    now: jlong,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = state().lock() {
        state.record_block_placed(position, now);
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_updatePrePatchClickerNative(
    _env: JNIEnv,
    _class: JClass,
    operation: jint,
    now: jlong,
    cps: jfloat,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    clicker_state()
        .lock()
        .map(|mut state| state.update(operation, now, cps))
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_submitPrePatchScanCandidateNative(
    _env: JNIEnv,
    _class: JClass,
    position: jlong,
    first_exposed: jboolean,
    second_exposed: jboolean,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = state().lock() {
        state.submit_scan_candidate(position, first_exposed != 0, second_exposed != 0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn armed_state(now: i64) -> (PrePatchScanState, AnchorRecord) {
        let mut state = PrePatchScanState::new();
        state.frame_now = now;
        let anchor = AnchorRecord {
            position: pack_block_pos(10, 64, 10),
            face: 5,
        };
        state.add_visible_anchor(anchor);
        assert_ne!(state.resolve_synthetic(anchor, 2, 3), i64::MIN);
        (state, anchor)
    }

    #[test]
    fn action_watchdog_discards_stalled_prepatch_anchors() {
        let (mut state, anchor) = armed_state(1_000);
        state.initialized = true;
        state.center_x = 10;
        state.center_y = 64;
        state.center_z = 10;
        for tick in 1..=ACTION_STALL_TIMEOUT_TICKS {
            state.begin_tick(1_000 + i64::from(tick) * 50, 10.5, 64.0, 10.5);
        }

        assert!(state.watchdog_reset_action_if_stalled());
        assert!(state.synthetic_anchor.is_none());
        assert_eq!(state.synthetic_spread, 0);
        assert!(!state.visible_index.contains(&anchor));
        assert!(state.visible.is_empty());
    }

    #[test]
    fn observed_placement_renews_action_progress() {
        let (mut state, _) = armed_state(1_000);
        state.mark_user_placement(pack_block_pos(11, 64, 10), 2_000);
        for tick in 1..ACTION_STALL_TIMEOUT_TICKS {
            state.begin_tick(2_000 + i64::from(tick) * 50, 10.5, 64.0, 10.5);
        }

        assert!(!state.watchdog_reset_action_if_stalled());
        assert!(state.synthetic_anchor.is_some());
    }

    #[test]
    fn active_intent_preserves_the_selected_obsidian_face_and_spread() {
        let mut state = PrePatchScanState::new();
        let position = pack_block_pos(10, 64, 10);
        let east = AnchorRecord { position, face: 5 };
        let west = AnchorRecord { position, face: 4 };
        state.add_visible_anchor(east);
        state.add_visible_anchor(west);

        assert_eq!(
            state.resolve_synthetic(east, 2, 2),
            pack_block_pos(12, 64, 10)
        );
        assert_eq!(
            state.active_intent(),
            Some(PrePatchIntent {
                origin: pack_block_pos(12, 64, 10),
                terminal: position,
                face: 5,
            })
        );

        assert_eq!(
            state.resolve_synthetic(west, 2, 2),
            pack_block_pos(8, 64, 10)
        );
        assert_eq!(
            state.active_intent(),
            Some(PrePatchIntent {
                origin: pack_block_pos(8, 64, 10),
                terminal: position,
                face: 4,
            })
        );
    }

    #[test]
    fn active_intent_disappears_when_its_authoritative_anchor_is_invalidated() {
        let (mut state, anchor) = armed_state(1_000);
        assert!(state.active_intent().is_some());

        state.remove_visible_anchor(anchor);

        assert_eq!(state.active_intent(), None);
    }

    #[test]
    fn direction_selects_the_faces_along_the_placement_axis() {
        let mut state = PrePatchScanState::new();
        state.east_west = true;
        // E/W placement extends along X from the lateral face of a N/S row.
        assert_eq!(state.directional_faces(), (5, 4));

        state.east_west = false;
        // N/S placement extends along Z from the lateral face of an E/W row.
        assert_eq!(state.directional_faces(), (2, 3));
    }
}
