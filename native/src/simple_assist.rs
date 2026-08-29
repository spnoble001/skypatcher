use jni::objects::JClass;
use jni::sys::{jboolean, jfloat, jint, jlong};
use jni::JNIEnv;
use std::sync::{Mutex, OnceLock};

const SIMPLE_ASSIST_MAX_CURSOR_STEPS: i32 = 256;
const SIMPLE_ASSIST_REQUEST_MISS_LIMIT: i32 = 3;
const SIMPLE_ASSIST_FAILURE_LIMIT: i32 = 3;
const SIMPLE_ASSIST_GRACE_TICKS: i32 = 50;
/// A virtual captured patch remains usable while the player stays within this
/// radius of its capture position.
const SIMPLE_ASSIST_CAPTURE_RADIUS_SQUARED: f64 = 144.0;
/// A capture that cannot confirm a placement or complete its cursor walk must
/// not require manual world interaction to recover.
const SIMPLE_ASSIST_STALL_TIMEOUT_TICKS: i32 = 80;
const SIMPLE_ASSIST_OBSIDIAN_ID: i32 = 49;
const SIMPLE_ASSIST_BEDROCK_ID: i32 = 7;
const SIMPLE_ASSIST_MIN_CPS: f32 = 1.0;
const SIMPLE_ASSIST_MAX_CPS: f32 = 20.0;
const SIMPLE_ASSIST_SNAPSHOT_REPLACEABLE: i32 = 1 << 16;
const SIMPLE_ASSIST_SNAPSHOT_COLLIDABLE: i32 = 1 << 17;
const SIMPLE_ASSIST_MAX_PATCHES: usize = 128;
const CAPTURE_SOURCE_NONE: i32 = 0;
const CAPTURE_SOURCE_REAL_CRUMB: i32 = 1;
const CAPTURE_SOURCE_PRE_PATCH: i32 = 2;

static SIMPLE_ASSIST_STATE: OnceLock<Mutex<SimpleAssistState>> = OnceLock::new();
static SIMPLE_ASSIST_PATCHES: OnceLock<Mutex<SimpleAssistPatchFrame>> = OnceLock::new();

#[derive(Clone, Copy)]
struct SimpleAssistPatch {
    origin: i64,
    terminal: i64,
}

impl SimpleAssistPatch {
    const EMPTY: Self = Self {
        origin: 0,
        terminal: 0,
    };
}

struct SimpleAssistPatchFrame {
    len: usize,
    patches: [SimpleAssistPatch; SIMPLE_ASSIST_MAX_PATCHES],
}

impl SimpleAssistPatchFrame {
    fn new() -> Self {
        Self {
            len: 0,
            patches: [SimpleAssistPatch::EMPTY; SIMPLE_ASSIST_MAX_PATCHES],
        }
    }
}

pub(crate) fn reset_patch_frame() {
    if let Ok(mut frame) = SIMPLE_ASSIST_PATCHES
        .get_or_init(|| Mutex::new(SimpleAssistPatchFrame::new()))
        .lock()
    {
        frame.len = 0;
    }
}

fn capture_is_reconciled_with_real_frame(active: bool, capture_source: i32) -> bool {
    active && capture_source == CAPTURE_SOURCE_REAL_CRUMB
}

/// Reconciles a captured path with a fully published PatchCrumbs frame.
/// A capture remains live while its exact geometry is present. Once it
/// disappears, its frozen geometry becomes a short-lived virtual patch rather
/// than being discarded at the first recalculation.
pub(crate) fn reconcile_patch_frame(tick: i32) {
    let captured = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .ok()
        .and_then(|state| {
            if capture_is_reconciled_with_real_frame(state.active, state.capture_source) {
                Some((state.origin, state.terminal))
            } else {
                None
            }
        });
    let Some((origin, terminal)) = captured else {
        return;
    };
    let present = SIMPLE_ASSIST_PATCHES
        .get_or_init(|| Mutex::new(SimpleAssistPatchFrame::new()))
        .lock()
        .map(|frame| {
            frame.patches[..frame.len]
                .iter()
                .any(|patch| patch.origin == origin && patch.terminal == terminal)
        })
        .unwrap_or(true);
    let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    else {
        return;
    };
    if !state.active || state.origin != origin || state.terminal != terminal {
        return;
    }
    if present {
        state.grace_until = i32::MIN;
    } else if state.grace_until == i32::MIN {
        state.grace_until = tick.saturating_add(SIMPLE_ASSIST_GRACE_TICKS);
    } else if tick > state.grace_until {
        reset_state_preserving_block_ids(&mut state);
    }
}

pub(crate) fn quiesce() {
    if let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    {
        *state = SimpleAssistState::new();
    }
    if let Ok(mut frame) = SIMPLE_ASSIST_PATCHES
        .get_or_init(|| Mutex::new(SimpleAssistPatchFrame::new()))
        .lock()
    {
        for patch in frame.patches.iter_mut() {
            *patch = SimpleAssistPatch::EMPTY;
        }
        frame.len = 0;
    }
}

pub(crate) fn push_patch_frame(origin: i64, terminal: i64) {
    if let Ok(mut frame) = SIMPLE_ASSIST_PATCHES
        .get_or_init(|| Mutex::new(SimpleAssistPatchFrame::new()))
        .lock()
    {
        if frame.len < SIMPLE_ASSIST_MAX_PATCHES {
            let index = frame.len;
            frame.patches[index] = SimpleAssistPatch { origin, terminal };
            frame.len += 1;
        }
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_beginSimpleAssistPatchFrameNative(
    _env: JNIEnv,
    _class: JClass,
) {
    crate::lifecycle::jni_guarded!();

    reset_patch_frame();
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_reconcileSimpleAssistPatchFrameNative(
    _env: JNIEnv,
    _class: JClass,
    tick: jint,
) {
    crate::lifecycle::jni_guarded!();
    reconcile_patch_frame(tick);
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_simpleAssistPatchCountNative(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_PATCHES
        .get_or_init(|| Mutex::new(SimpleAssistPatchFrame::new()))
        .lock()
        .map(|frame| frame.len as jint)
        .unwrap_or(0)
}

const ARBITRATION_NONE: i32 = 0;
const ARBITRATION_REAL_CRUMB: i32 = 1;
const ARBITRATION_PRE_PATCH: i32 = 2;

fn arbitration_choice(active: bool, has_real_crumb: bool, has_pre_patch: bool) -> i32 {
    if active {
        ARBITRATION_NONE
    } else if has_real_crumb {
        ARBITRATION_REAL_CRUMB
    } else if has_pre_patch {
        ARBITRATION_PRE_PATCH
    } else {
        ARBITRATION_NONE
    }
}

/// Single admission point with two independent producers. PatchCrumbs owns the
/// real frame; PrePatch owns one live position+face intent. Neither producer is
/// copied into the other, and a real crumb always wins when both match.
pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_arbitrateSimpleAssistPatchNative(
    _env: JNIEnv,
    _class: JClass,
    hit: jlong,
    hit_face: jint,
    x: f64,
    y: f64,
    z: f64,
    tick: jint,
) -> jint {
    crate::lifecycle::jni_guarded!(ARBITRATION_NONE);

    let real_crumb = SIMPLE_ASSIST_PATCHES
        .get_or_init(|| Mutex::new(SimpleAssistPatchFrame::new()))
        .lock()
        .ok()
        .and_then(|frame| {
            frame.patches[..frame.len]
                .iter()
                .find(|patch| patch.terminal == hit)
                .copied()
        });
    // Collect both candidates before mutating the executor. Position and face
    // are revalidated against PrePatch's authoritative state here.
    let pre_patch = if real_crumb.is_none() {
        crate::pre_patch::intent_matching(hit, hit_face)
    } else {
        None
    };
    let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    else {
        return ARBITRATION_NONE;
    };
    let choice = arbitration_choice(state.active, real_crumb.is_some(), pre_patch.is_some());
    match choice {
        ARBITRATION_REAL_CRUMB => {
            let patch = real_crumb.unwrap();
            activate_capture(
                &mut state,
                patch.origin,
                patch.terminal,
                x,
                y,
                z,
                tick,
                CAPTURE_SOURCE_REAL_CRUMB,
            );
        }
        ARBITRATION_PRE_PATCH => {
            let intent = pre_patch.unwrap();
            activate_capture(
                &mut state,
                intent.origin,
                intent.terminal,
                x,
                y,
                z,
                tick,
                CAPTURE_SOURCE_PRE_PATCH,
            );
        }
        _ => {}
    }
    choice
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_matchesSimpleAssistPatchHitNative(
    _env: JNIEnv,
    _class: JClass,
    hit: jlong,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_PATCHES
        .get_or_init(|| Mutex::new(SimpleAssistPatchFrame::new()))
        .lock()
        .map(|frame| {
            frame.patches[..frame.len]
                .iter()
                .any(|patch| patch.terminal == hit) as jboolean
        })
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistOriginNative(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| {
            if state.active {
                state.origin
            } else {
                jlong::MIN
            }
        })
        .unwrap_or(jlong::MIN)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistTerminalNative(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| {
            if state.active {
                state.terminal
            } else {
                jlong::MIN
            }
        })
        .unwrap_or(jlong::MIN)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistCursorNative(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| {
            if state.active {
                state.cursor
            } else {
                jlong::MIN
            }
        })
        .unwrap_or(jlong::MIN)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_isSimpleAssistCapturedNative(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| state.active as jboolean)
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_isSimpleAssistAcceptedBlockNative(
    _env: JNIEnv,
    _class: JClass,
    block_id: jint,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| (block_id == state.obsidian_id || block_id == state.bedrock_id) as jboolean)
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_configureSimpleAssistAcceptedBlocksNative(
    _env: JNIEnv,
    _class: JClass,
    obsidian_id: jint,
    bedrock_id: jint,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    {
        state.obsidian_id = obsidian_id;
        state.bedrock_id = bedrock_id;
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_simpleAssistMaxCpsNative(
    _env: JNIEnv,
    _class: JClass,
) -> jfloat {
    crate::lifecycle::jni_guarded!(0.0);

    SIMPLE_ASSIST_MAX_CPS
}

struct SimpleAssistState {
    active: bool,
    capture_source: i32,
    origin: i64,
    terminal: i64,
    cursor: i64,
    cursor_steps: i32,
    preview_target: i64,
    request_target: i64,
    request_support: i64,
    request_face: i32,
    request_hit_x: f64,
    request_hit_y: f64,
    request_hit_z: f64,
    request_score: f64,
    grace_until: i32,
    player_x: f64,
    player_y: f64,
    player_z: f64,
    pending_target: i64,
    expected_block: i32,
    sent_tick: i32,
    failures: i32,
    failed_until: i32,
    request_misses: i32,
    credit: f64,
    credit_tick: i32,
    placement_tick: i32,
    last_progress_tick: i32,
    obsidian_id: i32,
    bedrock_id: i32,
}
impl SimpleAssistState {
    fn new() -> Self {
        Self {
            active: false,
            capture_source: CAPTURE_SOURCE_NONE,
            origin: 0,
            terminal: 0,
            cursor: 0,
            cursor_steps: 0,
            preview_target: jlong::MIN,
            request_target: jlong::MIN,
            request_support: jlong::MIN,
            request_face: -1,
            request_hit_x: 0.0,
            request_hit_y: 0.0,
            request_hit_z: 0.0,
            request_score: f64::INFINITY,
            grace_until: i32::MIN,
            player_x: 0.0,
            player_y: 0.0,
            player_z: 0.0,
            pending_target: jlong::MIN,
            expected_block: -1,
            sent_tick: 0,
            failures: 0,
            failed_until: 0,
            request_misses: 0,
            credit: 1.0,
            credit_tick: i32::MIN,
            placement_tick: i32::MIN,
            last_progress_tick: i32::MIN,
            obsidian_id: SIMPLE_ASSIST_OBSIDIAN_ID,
            bedrock_id: SIMPLE_ASSIST_BEDROCK_ID,
        }
    }
}

fn reset_state_preserving_block_ids(state: &mut SimpleAssistState) {
    let (obsidian_id, bedrock_id) = (state.obsidian_id, state.bedrock_id);
    *state = SimpleAssistState::new();
    state.obsidian_id = obsidian_id;
    state.bedrock_id = bedrock_id;
}

fn activate_capture(
    state: &mut SimpleAssistState,
    origin: i64,
    terminal: i64,
    player_x: f64,
    player_y: f64,
    player_z: f64,
    tick: i32,
    capture_source: i32,
) {
    reset_state_preserving_block_ids(state);
    state.active = true;
    state.capture_source = capture_source;
    state.origin = origin;
    state.terminal = terminal;
    state.cursor = terminal;
    state.player_x = player_x;
    state.player_y = player_y;
    state.player_z = player_z;
    state.credit = 1.0;
    state.credit_tick = tick;
    state.last_progress_tick = tick;
}

/**
 * V2 may move a PrePatch endpoint only farther along its already-captured
 * horizontal V1 ray.  This cannot turn the session into a 3D route, shorten
 * it behind the cursor, or alter the terminal/ancla.
 */
pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_retargetSimpleAssistPrePatchNative(
    _env: JNIEnv,
    _class: JClass,
    requested_origin: jlong,
) {
    crate::lifecycle::jni_guarded!();
    let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    else {
        return;
    };
    if !state.active || state.capture_source != CAPTURE_SOURCE_PRE_PATCH {
        return;
    }
    let (tx, ty, tz) = (state.terminal >> 38, state.terminal << 26 >> 52,
            state.terminal << 38 >> 38);
    let (ox, oy, oz) = (state.origin >> 38, state.origin << 26 >> 52,
            state.origin << 38 >> 38);
    let (rx, ry, rz) = (requested_origin >> 38, requested_origin << 26 >> 52,
            requested_origin << 38 >> 38);
    if oy != ty || ry != ty { return; }
    let (base, requested) = if ox != tx && oz == tz && rz == tz {
        (ox - tx, rx - tx)
    } else if oz != tz && ox == tx && rx == tx {
        (oz - tz, rz - tz)
    } else {
        return;
    };
    if base == 0 || requested.signum() != base.signum() || requested.abs() <= base.abs() {
        return;
    }
    state.origin = requested_origin;
}

fn consider_simple_assist_request_candidate(
    state: &mut SimpleAssistState,
    support: jlong,
    face: jint,
    hit_x: f64,
    hit_y: f64,
    hit_z: f64,
    eye_x: f64,
    eye_y: f64,
    eye_z: f64,
    reach: f64,
) {
    let dx = hit_x - eye_x;
    let dy = hit_y - eye_y;
    let dz = hit_z - eye_z;
    let distance = (dx * dx + dy * dy + dz * dz).sqrt();
    if distance <= reach && distance < state.request_score {
        state.request_support = support;
        state.request_face = face;
        state.request_hit_x = hit_x;
        state.request_hit_y = hit_y;
        state.request_hit_z = hit_z;
        state.request_score = distance;
    }
}

fn offset_simple_assist_position(packed: jlong, face: jint) -> jlong {
    let x = packed >> 38;
    let y = packed << 26 >> 52;
    let z = packed << 38 >> 38;
    let (dx, dy, dz) = match face {
        0 => (0, -1, 0),
        1 => (0, 1, 0),
        2 => (0, 0, -1),
        3 => (0, 0, 1),
        4 => (-1, 0, 0),
        5 => (1, 0, 0),
        _ => return jlong::MIN,
    };
    (((x + dx) & 0x3FF_FFFF) << 38) | (((y + dy) & 0xFFF) << 26) | ((z + dz) & 0x3FF_FFFF)
}

fn opposite_simple_assist_face(face: jint) -> jint {
    match face {
        0 => 1,
        1 => 0,
        2 => 3,
        3 => 2,
        4 => 5,
        5 => 4,
        _ => -1,
    }
}

fn simple_assist_snapshot_replaceable(snapshot: jint) -> bool {
    snapshot >= 0 && snapshot & SIMPLE_ASSIST_SNAPSHOT_REPLACEABLE != 0
}

fn simple_assist_snapshot_collidable(snapshot: jint) -> bool {
    snapshot >= 0 && snapshot & SIMPLE_ASSIST_SNAPSHOT_COLLIDABLE != 0
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_startSimpleAssistPatchNative(
    _env: JNIEnv,
    _class: JClass,
    origin: jlong,
    terminal: jlong,
    player_x: f64,
    player_y: f64,
    player_z: f64,
    tick: jint,
) {
    crate::lifecycle::jni_guarded!();
    if let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    {
        activate_capture(
            &mut state,
            origin,
            terminal,
            player_x,
            player_y,
            player_z,
            tick,
            CAPTURE_SOURCE_REAL_CRUMB,
        );
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_beginSimpleAssistRequestNative(
    _env: JNIEnv,
    _class: JClass,
    target: jlong,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    {
        state.request_target = target;
        state.request_support = jlong::MIN;
        state.request_face = -1;
        state.request_score = f64::INFINITY;
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_pushSimpleAssistNaturalCandidateNative(
    _env: JNIEnv,
    _class: JClass,
    support: jlong,
    face: jint,
    hit_x: f64,
    hit_y: f64,
    hit_z: f64,
    support_snapshot: jint,
    eye_x: f64,
    eye_y: f64,
    eye_z: f64,
    reach: f64,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    {
        let support_replaceable = simple_assist_snapshot_replaceable(support_snapshot);
        if !state.active
            || state.request_target == jlong::MIN
            || (!support_replaceable && !simple_assist_snapshot_collidable(support_snapshot))
        {
            return;
        }
        let placement_target = if support_replaceable {
            support
        } else {
            offset_simple_assist_position(support, face)
        };
        if placement_target != state.request_target {
            return;
        }
        consider_simple_assist_request_candidate(
            &mut state, support, face, hit_x, hit_y, hit_z, eye_x, eye_y, eye_z, reach,
        );
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistGuidanceSupportNative(
    _env: JNIEnv,
    _class: JClass,
    face: jint,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| {
            let opposite = opposite_simple_assist_face(face);
            if state.active && state.request_target != jlong::MIN && opposite >= 0 {
                offset_simple_assist_position(state.request_target, opposite)
            } else {
                jlong::MIN
            }
        })
        .unwrap_or(jlong::MIN)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_pushSimpleAssistGuidanceCandidateNative(
    _env: JNIEnv,
    _class: JClass,
    face: jint,
    support_snapshot: jint,
    eye_x: f64,
    eye_y: f64,
    eye_z: f64,
    reach: f64,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    {
        let opposite = opposite_simple_assist_face(face);
        if !state.active
            || state.request_target == jlong::MIN
            || opposite < 0
            || simple_assist_snapshot_replaceable(support_snapshot)
            || !simple_assist_snapshot_collidable(support_snapshot)
        {
            return;
        }
        let support = offset_simple_assist_position(state.request_target, opposite);
        let x = support >> 38;
        let y = support << 26 >> 52;
        let z = support << 38 >> 38;
        let (dx, dy, dz) = match face {
            0 => (0.0, -0.5, 0.0),
            1 => (0.0, 0.5, 0.0),
            2 => (0.0, 0.0, -0.5),
            3 => (0.0, 0.0, 0.5),
            4 => (-0.5, 0.0, 0.0),
            5 => (0.5, 0.0, 0.0),
            _ => return,
        };
        consider_simple_assist_request_candidate(
            &mut state,
            support,
            face,
            x as f64 + 0.5 + dx,
            y as f64 + 0.5 + dy,
            z as f64 + 0.5 + dz,
            eye_x,
            eye_y,
            eye_z,
            reach,
        );
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_isSimpleAssistReplaceableSnapshotNative(
    _env: JNIEnv,
    _class: JClass,
    snapshot: jint,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    simple_assist_snapshot_replaceable(snapshot) as jboolean
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_hasSimpleAssistRequestNative(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| (state.active && state.request_face >= 0) as jboolean)
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistRequestTargetNative(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| {
            if state.active && state.request_face >= 0 {
                state.request_target
            } else {
                jlong::MIN
            }
        })
        .unwrap_or(jlong::MIN)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistRequestSupportNative(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| {
            if state.active && state.request_face >= 0 {
                state.request_support
            } else {
                jlong::MIN
            }
        })
        .unwrap_or(jlong::MIN)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistRequestFaceNative(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| if state.active { state.request_face } else { -1 })
        .unwrap_or(-1)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistRequestHitXNative(
    _env: JNIEnv,
    _class: JClass,
) -> f64 {
    crate::lifecycle::jni_guarded!(0.0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| {
            if state.request_face >= 0 {
                state.request_hit_x
            } else {
                0.0
            }
        })
        .unwrap_or(0.0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistRequestHitYNative(
    _env: JNIEnv,
    _class: JClass,
) -> f64 {
    crate::lifecycle::jni_guarded!(0.0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| {
            if state.request_face >= 0 {
                state.request_hit_y
            } else {
                0.0
            }
        })
        .unwrap_or(0.0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistRequestHitZNative(
    _env: JNIEnv,
    _class: JClass,
) -> f64 {
    crate::lifecycle::jni_guarded!(0.0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| {
            if state.request_face >= 0 {
                state.request_hit_z
            } else {
                0.0
            }
        })
        .unwrap_or(0.0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_recordSimpleAssistRequestMissNative(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    else {
        return 1;
    };
    state.request_misses = state.request_misses.saturating_add(1);
    (state.request_misses >= SIMPLE_ASSIST_REQUEST_MISS_LIMIT) as jboolean
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_resetSimpleAssistRequestMissesNative(
    _env: JNIEnv,
    _class: JClass,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    {
        state.request_misses = 0;
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_consumeSimpleAssistCreditNative(
    _env: JNIEnv,
    _class: JClass,
    tick: jint,
    cps: jfloat,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    else {
        return 0;
    };
    let rate = cps.max(SIMPLE_ASSIST_MIN_CPS).min(SIMPLE_ASSIST_MAX_CPS) as f64;
    if tick > state.credit_tick {
        state.credit = (state.credit + (tick - state.credit_tick) as f64 * rate / 20.0).min(1.999);
        state.credit_tick = tick;
    }
    if state.credit < 1.0 || state.placement_tick == tick {
        return 0;
    }
    state.credit -= 1.0;
    state.placement_tick = tick;
    1
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_hasSimpleAssistCreditNative(
    _env: JNIEnv,
    _class: JClass,
    tick: jint,
    cps: jfloat,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    else {
        return 0;
    };
    let rate = cps.max(SIMPLE_ASSIST_MIN_CPS).min(SIMPLE_ASSIST_MAX_CPS) as f64;
    if tick > state.credit_tick {
        state.credit = (state.credit + (tick - state.credit_tick) as f64 * rate / 20.0).min(1.999);
        state.credit_tick = tick;
    }
    (state.credit >= 1.0 && state.placement_tick != tick) as jboolean
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_isSimpleAssistCaptureValidNative(
    _env: JNIEnv,
    _class: JClass,
    _tick: jint,
    x: f64,
    y: f64,
    z: f64,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    let Ok(state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    else {
        return 0;
    };
    if !state.active {
        return 0;
    }
    let dx = x - state.player_x;
    let dy = y - state.player_y;
    let dz = z - state.player_z;
    if dx * dx + dy * dy + dz * dz <= SIMPLE_ASSIST_CAPTURE_RADIUS_SQUARED {
        1
    } else {
        0
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_hasSimpleAssistGraceNative(
    _env: JNIEnv,
    _class: JClass,
    tick: jint,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| (state.active && tick <= state.grace_until) as jboolean)
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_extendSimpleAssistGraceNative(
    _env: JNIEnv,
    _class: JClass,
    tick: jint,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    {
        if state.grace_until != i32::MIN {
            state.grace_until = tick.saturating_add(SIMPLE_ASSIST_GRACE_TICKS);
        }
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_resetSimpleAssistNative(
    _env: JNIEnv,
    _class: JClass,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    {
        reset_state_preserving_block_ids(&mut state);
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_beginSimpleAssistPlacementNative(
    _env: JNIEnv,
    _class: JClass,
    target: jlong,
    expected_block: jint,
    tick: jint,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    {
        state.pending_target = target;
        state.expected_block = expected_block;
        state.sent_tick = tick;
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistPendingTargetNative(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| {
            if state.active && state.expected_block >= 0 {
                state.pending_target
            } else {
                jlong::MIN
            }
        })
        .unwrap_or(jlong::MIN)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_setSimpleAssistPreviewNative(
    _env: JNIEnv,
    _class: JClass,
    target: jlong,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    {
        state.preview_target = target;
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistPreviewNative(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| {
            if state.active {
                state.preview_target
            } else {
                jlong::MIN
            }
        })
        .unwrap_or(jlong::MIN)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistPreviewXNative(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| {
            if state.active && state.preview_target != jlong::MIN {
                (state.preview_target >> 38) as jint
            } else {
                0
            }
        })
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistPreviewYNative(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| {
            if state.active && state.preview_target != jlong::MIN {
                (state.preview_target << 26 >> 52) as jint
            } else {
                0
            }
        })
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistPreviewZNative(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| {
            if state.active && state.preview_target != jlong::MIN {
                (state.preview_target << 38 >> 38) as jint
            } else {
                0
            }
        })
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_confirmSimpleAssistPlacementNative(
    _env: JNIEnv,
    _class: JClass,
    actual_block: jint,
    tick: jint,
    ping: jint,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    else {
        return 0;
    };
    let timeout = ((ping.max(0).saturating_add(49) / 50).saturating_add(2)).clamp(3, 10);
    if state.expected_block < 0 {
        return 0;
    }
    if actual_block == state.expected_block && tick > state.sent_tick {
        state.cursor = state.pending_target;
        state.expected_block = -1;
        state.failures = 0;
        state.last_progress_tick = tick;
        return 1;
    }
    if tick - state.sent_tick >= timeout {
        state.expected_block = -1;
        state.failures += 1;
        state.failed_until = tick.saturating_add(2);
        if state.failures >= SIMPLE_ASSIST_FAILURE_LIMIT {
            state.active = false;
        }
        return -1;
    }
    0
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_isSimpleAssistBackoffReadyNative(
    _env: JNIEnv,
    _class: JClass,
    tick: jint,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| (state.active && tick >= state.failed_until) as jboolean)
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_simpleAssistPhaseNative(
    _env: JNIEnv,
    _class: JClass,
    tick: jint,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
        .map(|state| {
            if !state.active {
                3
            } else if state.expected_block >= 0 {
                1
            } else if tick < state.failed_until {
                2
            } else {
                0
            }
        })
        .unwrap_or(3)
}

/// Returns true when a stalled capture was abandoned.  This covers paths that
/// never reach placement confirmation (for example, no valid support after a
/// geometry refresh), which the placement timeout cannot observe.
pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_watchdogSimpleAssistNative(
    _env: JNIEnv,
    _class: JClass,
    tick: jint,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    else {
        return 0;
    };
    if state.active
        && tick.saturating_sub(state.last_progress_tick) > SIMPLE_ASSIST_STALL_TIMEOUT_TICKS
    {
        reset_state_preserving_block_ids(&mut state);
        return 1;
    }
    0
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_suspendSimpleAssistWatchdogNative(
    _env: JNIEnv,
    _class: JClass,
    tick: jint,
) {
    crate::lifecycle::jni_guarded!();
    if let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    {
        if state.active {
            state.last_progress_tick = tick;
        }
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_recordSimpleAssistRejectedInteractionNative(
    _env: JNIEnv,
    _class: JClass,
    tick: jint,
) {
    crate::lifecycle::jni_guarded!();
    if let Ok(mut state) = SIMPLE_ASSIST_STATE
        .get_or_init(|| Mutex::new(SimpleAssistState::new()))
        .lock()
    {
        if !state.active {
            return;
        }
        state.failures = state.failures.saturating_add(1);
        state.failed_until = tick.saturating_add(2);
        if state.failures >= SIMPLE_ASSIST_FAILURE_LIMIT {
            state.active = false;
        }
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_nextSimpleAssistCursorNative(
    _env: JNIEnv,
    _class: JClass,
    cursor_is_accepted: jboolean,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    let state = SIMPLE_ASSIST_STATE.get_or_init(|| Mutex::new(SimpleAssistState::new()));
    let Ok(mut state) = state.lock() else {
        return jlong::MIN;
    };
    if !state.active {
        return jlong::MIN;
    }
    if cursor_is_accepted != 0 {
        if state.cursor == state.origin {
            state.active = false;
            return jlong::MIN;
        }
        if state.cursor_steps >= SIMPLE_ASSIST_MAX_CURSOR_STEPS {
            state.active = false;
            return jlong::MIN;
        }
        state.cursor_steps += 1;
        let x = state.cursor >> 38;
        let y = (state.cursor << 26) >> 52;
        let z = (state.cursor << 38) >> 38;
        let ox = state.origin >> 38;
        let oy = (state.origin << 26) >> 52;
        let oz = (state.origin << 38) >> 38;
        state.cursor = (((x + (ox - x).signum()) & 0x3FF_FFFF) << 38)
            | (((y + (oy - y).signum()) & 0xFFF) << 26)
            | ((z + (oz - z).signum()) & 0x3FF_FFFF);
    }
    state.cursor
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn published_patch_is_admitted_when_idle() {
        assert_eq!(
            arbitration_choice(false, true, true),
            ARBITRATION_REAL_CRUMB
        );
    }

    #[test]
    fn missing_patch_is_not_admitted() {
        assert_eq!(arbitration_choice(false, false, false), ARBITRATION_NONE);
    }

    #[test]
    fn active_capture_is_not_preempted() {
        assert_eq!(arbitration_choice(true, true, true), ARBITRATION_NONE);
    }

    #[test]
    fn prepatch_is_admitted_only_when_no_real_crumb_matches() {
        assert_eq!(
            arbitration_choice(false, false, true),
            ARBITRATION_PRE_PATCH
        );
    }

    #[test]
    fn prepatch_execution_is_not_reconciled_against_patchcrumbs_frame() {
        assert!(!capture_is_reconciled_with_real_frame(
            true,
            CAPTURE_SOURCE_PRE_PATCH
        ));
        assert!(capture_is_reconciled_with_real_frame(
            true,
            CAPTURE_SOURCE_REAL_CRUMB
        ));
    }
}
