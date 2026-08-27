use jni::objects::JClass;
use jni::sys::{jboolean, jdouble, jfloat, jint, jlong};
use jni::JNIEnv;
use std::sync::{Mutex, OnceLock};

const STRATEGY_ALLOW: i32 = 0;
const STRATEGY_JUMP: i32 = 1;
const STRATEGY_REDIRECT: i32 = 2;
const STRATEGY_RECOVER: i32 = 3;
const FLAG_SET_INPUT: i32 = 1 << 2;
const FLAG_SET_MOTION: i32 = 1 << 3;
const INPUT_LATCH_TICKS: i32 = 3;
const MIN_SPEED: f64 = 1.0e-4;
const MIN_TARGET_ALIGNMENT: f64 = 0.15;
const MIN_AIR_ASSIST_SPEED: f64 = 0.28;
const MAX_AIR_ASSIST_SPEED: f64 = 0.62;
const INSTANT_AIR_ACCELERATION: f64 = 0.045;
const AIR_ASSIST_DELTA_MAX: f64 = 0.02;
const AIR_ACCELERATION_ESTIMATE: f64 = 0.02;
const GRAVITY: f64 = 0.08;
const INPUT_SPEED: f64 = 0.10;
const INPUTS: [(i32, i32); 8] = [
    (1, 0),
    (-1, 0),
    (0, 1),
    (0, -1),
    (1, 1),
    (1, -1),
    (-1, 1),
    (-1, -1),
];

struct AntiFallState {
    latched_forward: i32,
    latched_strafe: i32,
    latch_ticks: i32,
    output_motion_x: f64,
    output_motion_z: f64,
    strategy: i32,
}

#[derive(Clone, Copy)]
struct RecoveryTarget {
    x: i32,
    z: i32,
    drop: i32,
    preserves_trajectory: bool,
}

impl AntiFallState {
    fn new() -> Self {
        Self {
            latched_forward: 0,
            latched_strafe: 0,
            latch_ticks: 0,
            output_motion_x: 0.0,
            output_motion_z: 0.0,
            strategy: STRATEGY_ALLOW,
        }
    }

    fn reset_latch(&mut self) {
        self.latched_forward = 0;
        self.latched_strafe = 0;
        self.latch_ticks = 0;
    }

    fn reset(&mut self) {
        self.reset_latch();
        self.output_motion_x = 0.0;
        self.output_motion_z = 0.0;
        self.strategy = STRATEGY_ALLOW;
    }
}

static STATE: OnceLock<Mutex<AntiFallState>> = OnceLock::new();

pub(crate) fn quiesce() {
    if let Ok(mut state) = STATE
        .get_or_init(|| Mutex::new(AntiFallState::new()))
        .lock()
    {
        state.reset();
    }
}

#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_nextAntiFallActionNative(
    _env: JNIEnv,
    _class: JClass,
    on_ground: jboolean,
    fall_distance: jfloat,
    yaw: jfloat,
    move_forward: jfloat,
    move_strafe: jfloat,
    pos_x: jdouble,
    pos_y: jdouble,
    pos_z: jdouble,
    motion_x: jdouble,
    motion_y: jdouble,
    motion_z: jdouble,
    move_x: jdouble,
    move_z: jdouble,
    supported: jboolean,
    x_supported: jboolean,
    z_supported: jboolean,
    jump_speed: jfloat,
    gap_jump: jboolean,
    instant_mode: jboolean,
    grid: jlong,
    recovery_low: jlong,
    recovery_high: jlong,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    let machine = STATE.get_or_init(|| Mutex::new(AntiFallState::new()));
    let Ok(mut state) = machine.lock() else {
        return 0;
    };
    state.output_motion_x = motion_x;
    state.output_motion_z = motion_z;

    if on_ground == 0 && motion_y < 0.0 && fall_distance > 0.5 {
        return recover(
            &mut state,
            yaw as f64,
            move_forward as f64,
            move_strafe as f64,
            pos_x,
            pos_y,
            pos_z,
            motion_x,
            motion_y,
            motion_z,
            instant_mode != 0,
            recovery_low as u64,
            recovery_high as u64,
        );
    }
    if on_ground == 0 {
        state.strategy = STRATEGY_ALLOW;
        return STRATEGY_ALLOW;
    }

    state.reset_latch();
    let speed = move_x.hypot(move_z);
    if speed <= MIN_SPEED || fall_distance > 3.0 {
        state.strategy = STRATEGY_ALLOW;
        return STRATEGY_ALLOW;
    }
    let dir_x = sign(move_x);
    let dir_z = sign(move_z);
    if dir_x == 0 && dir_z == 0 {
        return redirect(
            &mut state,
            yaw as f64,
            move_x,
            move_z,
            x_supported != 0,
            z_supported != 0,
        );
    }

    let current = tile(grid as u64, 2, 2);
    let next = tile(grid as u64, 2 + dir_x, 2 + dir_z);
    let target = tile(grid as u64, 2 + dir_x * 2, 2 + dir_z * 2);
    let gap = is_jumpable_gap(current, next, target);
    if gap
        && gap_jump != 0
        && speed + MIN_SPEED >= jump_speed as f64
        && move_forward > 0.0
    {
        state.strategy = STRATEGY_JUMP;
        return STRATEGY_JUMP;
    }
    if supported != 0 {
        state.strategy = STRATEGY_ALLOW;
        return STRATEGY_ALLOW;
    }
    redirect(
        &mut state,
        yaw as f64,
        move_x,
        move_z,
        x_supported != 0,
        z_supported != 0,
    )
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_antiFallMotionXNative(
    _env: JNIEnv,
    _class: JClass,
) -> jdouble {
    crate::lifecycle::jni_guarded!(0.0);

    STATE
        .get_or_init(|| Mutex::new(AntiFallState::new()))
        .lock()
        .map(|state| state.output_motion_x)
        .unwrap_or(0.0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_antiFallMotionZNative(
    _env: JNIEnv,
    _class: JClass,
) -> jdouble {
    crate::lifecycle::jni_guarded!(0.0);

    STATE
        .get_or_init(|| Mutex::new(AntiFallState::new()))
        .lock()
        .map(|state| state.output_motion_z)
        .unwrap_or(0.0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_antiFallStrategyNative(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    STATE
        .get_or_init(|| Mutex::new(AntiFallState::new()))
        .lock()
        .map(|state| state.strategy)
        .unwrap_or(STRATEGY_ALLOW)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_resetAntiFallNative(
    _env: JNIEnv,
    _class: JClass,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = STATE
        .get_or_init(|| Mutex::new(AntiFallState::new()))
        .lock()
    {
        state.reset();
    }
}

#[allow(clippy::too_many_arguments)]
fn recover(
    state: &mut AntiFallState,
    yaw: f64,
    move_forward: f64,
    move_strafe: f64,
    pos_x: f64,
    pos_y: f64,
    pos_z: f64,
    motion_x: f64,
    motion_y: f64,
    motion_z: f64,
    instant_mode: bool,
    recovery_low: u64,
    recovery_high: u64,
) -> i32 {
    let (intent_x, intent_z) = world_vector(yaw, move_forward, move_strafe);
    let intent_speed = intent_x.hypot(intent_z);
    let (mut preferred_x, mut preferred_z) = if intent_speed > MIN_SPEED {
        (intent_x, intent_z)
    } else {
        (motion_x, motion_z)
    };
    let preferred_speed = preferred_x.hypot(preferred_z);
    if preferred_speed <= MIN_SPEED {
        state.reset_latch();
        state.strategy = STRATEGY_ALLOW;
        return STRATEGY_ALLOW;
    }
    preferred_x /= preferred_speed;
    preferred_z /= preferred_speed;

    let Some(target) = choose_recovery_target(
        preferred_x,
        preferred_z,
        recovery_low,
        recovery_high,
    ) else {
        state.reset_latch();
        state.strategy = STRATEGY_ALLOW;
        return STRATEGY_ALLOW;
    };

    let base_x = pos_x.floor();
    let base_y = pos_y.floor();
    let base_z = pos_z.floor();
    let mut water_x = base_x + target.x as f64 + 0.5 - pos_x;
    let mut water_z = base_z + target.z as f64 + 0.5 - pos_z;
    let water_distance = water_x.hypot(water_z);
    state.strategy = STRATEGY_RECOVER;
    if water_distance <= MIN_SPEED {
        state.reset_latch();
        return STRATEGY_RECOVER;
    }
    water_x /= water_distance;
    water_z /= water_distance;

    if instant_mode {
        // Instant recovery must point at the selected water column. Keeping
        // the prior trajectory here defeats the fallback path by accelerating
        // further into the air column.
        let current_along = motion_x * water_x + motion_z * water_z;
        let next_speed = (current_along.max(MIN_AIR_ASSIST_SPEED) + INSTANT_AIR_ACCELERATION)
            .min(MAX_AIR_ASSIST_SPEED);
        let lateral_x = motion_x - current_along * water_x;
        let lateral_z = motion_z - current_along * water_z;
        state.output_motion_x = water_x * next_speed + lateral_x * 0.35;
        state.output_motion_z = water_z * next_speed + lateral_z * 0.35;
        state.reset_latch();
        return STRATEGY_RECOVER | FLAG_SET_MOTION;
    }

    let (selected_forward, selected_strafe, selected_x, selected_z) = choose_input(
        yaw,
        preferred_x,
        preferred_z,
        water_x,
        water_z,
        target.preserves_trajectory,
    );
    if state.latch_ticks <= 0 {
        state.latched_forward = selected_forward;
        state.latched_strafe = selected_strafe;
        state.latch_ticks = INPUT_LATCH_TICKS;
    } else {
        state.latch_ticks -= 1;
    }

    let target_y = base_y - target.drop as f64 + 1.0;
    let impact_ticks = ticks_until_y(pos_y, motion_y, target_y);
    let water_ticks = ticks_to_water(motion_x, motion_z, water_distance, selected_x, selected_z);
    let mut flags = FLAG_SET_INPUT;
    if impact_ticks <= water_ticks {
        let current_along = motion_x * selected_x + motion_z * selected_z;
        let desired = (current_along.max(MIN_AIR_ASSIST_SPEED) + AIR_ASSIST_DELTA_MAX)
            .min(MAX_AIR_ASSIST_SPEED);
        let delta = (desired - current_along)
            .max(-AIR_ASSIST_DELTA_MAX)
            .min(AIR_ASSIST_DELTA_MAX);
        state.output_motion_x = motion_x + selected_x * delta;
        state.output_motion_z = motion_z + selected_z * delta;
        flags |= FLAG_SET_MOTION;
    }
    pack_action(
        STRATEGY_RECOVER,
        flags,
        state.latched_forward,
        state.latched_strafe,
    )
}

/// Prefer the highest observed water column first (drop 0 preserves the
/// player's current Y). Within that Y level, preserve trajectory when
/// possible; otherwise use the nearest column at that same height.
fn choose_recovery_target(
    preferred_x: f64,
    preferred_z: f64,
    recovery_low: u64,
    recovery_high: u64,
) -> Option<RecoveryTarget> {
    let mut aligned: Option<(RecoveryTarget, f64)> = None;
    let mut fallback: Option<(RecoveryTarget, f64)> = None;
    let mut preferred_drop: Option<i32> = None;
    for x in -2..=2 {
        for z in -2..=2 {
            if x == 0 && z == 0 {
                continue;
            }
            let index = ((x + 2) * 5 + z + 2) as usize;
            let Some(drop) = recovery_drop(recovery_low, recovery_high, index) else {
                continue;
            };
            if preferred_drop.map_or(true, |best| drop < best) {
                preferred_drop = Some(drop);
                aligned = None;
                fallback = None;
            } else if preferred_drop != Some(drop) {
                continue;
            }
            let distance_sq = (x * x + z * z) as f64;
            let distance = distance_sq.sqrt();
            let alignment = (x as f64 * preferred_x + z as f64 * preferred_z) / distance;
            let lateral = (x as f64 * preferred_z - z as f64 * preferred_x).abs();
            let fallback_score = distance_sq + drop as f64 * 0.01;
            let fallback_target = RecoveryTarget {
                x,
                z,
                drop,
                preserves_trajectory: false,
            };
            if fallback.map_or(true, |(_, score)| fallback_score < score) {
                fallback = Some((fallback_target, fallback_score));
            }
            if alignment >= MIN_TARGET_ALIGNMENT {
                let score = distance_sq + drop as f64 * 2.0 + lateral * 2.5 - alignment * 2.0;
                let target = RecoveryTarget {
                    preserves_trajectory: true,
                    ..fallback_target
                };
                if aligned.map_or(true, |(_, best_score)| score < best_score) {
                    aligned = Some((target, score));
                }
            }
        }
    }
    aligned.or(fallback).map(|(target, _)| target)
}

fn choose_input(
    yaw: f64,
    intent_x: f64,
    intent_z: f64,
    water_x: f64,
    water_z: f64,
    preserves_trajectory: bool,
) -> (i32, i32, f64, f64) {
    let mut best_score = -f64::MAX;
    let mut selected = (0, 0, intent_x, intent_z);
    for &(forward, strafe) in &INPUTS {
        let (x, z) = world_vector(yaw, forward as f64, strafe as f64);
        let length = x.hypot(z);
        if length <= MIN_SPEED {
            continue;
        }
        let vx = x / length;
        let vz = z / length;
        let progress = vx * water_x + vz * water_z;
        let lateral = (vx * water_z - vz * water_x).abs();
        let intent_alignment = vx * intent_x + vz * intent_z;
        let score = progress - 0.65 * lateral
            + if preserves_trajectory { 0.85 * intent_alignment } else { 0.0 };
        if score > best_score {
            best_score = score;
            selected = (forward, strafe, vx, vz);
        }
    }
    selected
}

fn redirect(
    state: &mut AntiFallState,
    yaw: f64,
    move_x: f64,
    move_z: f64,
    x_safe: bool,
    z_safe: bool,
) -> i32 {
    let (x, z) = if x_safe && !z_safe {
        (move_x, 0.0)
    } else if z_safe && !x_safe {
        (0.0, move_z)
    } else {
        (move_x * 0.2, move_z * 0.2)
    };
    let radians = yaw.to_radians();
    let forward = (-x * radians.sin() + z * radians.cos()) / INPUT_SPEED;
    let strafe = (x * radians.cos() + z * radians.sin()) / INPUT_SPEED;
    state.strategy = STRATEGY_REDIRECT;
    pack_action(
        STRATEGY_REDIRECT,
        FLAG_SET_INPUT,
        discrete(forward),
        discrete(strafe),
    )
}

fn world_vector(yaw: f64, forward: f64, strafe: f64) -> (f64, f64) {
    let radians = yaw.to_radians();
    (
        (-forward * radians.sin() + strafe * radians.cos()) * INPUT_SPEED,
        (forward * radians.cos() + strafe * radians.sin()) * INPUT_SPEED,
    )
}

fn ticks_until_y(mut y: f64, mut velocity_y: f64, target_y: f64) -> i32 {
    for tick in 1..=40 {
        y += velocity_y;
        velocity_y = (velocity_y - GRAVITY) * 0.98;
        if y <= target_y {
            return tick;
        }
    }
    40
}

fn ticks_to_water(
    motion_x: f64,
    motion_z: f64,
    distance: f64,
    choice_x: f64,
    choice_z: f64,
) -> i32 {
    let mut speed = (motion_x * choice_x + motion_z * choice_z).max(0.0);
    let mut travelled = 0.0;
    for tick in 1..=40 {
        speed =
            (speed.max(MIN_AIR_ASSIST_SPEED) + AIR_ACCELERATION_ESTIMATE).min(MAX_AIR_ASSIST_SPEED);
        travelled += speed;
        if travelled >= distance {
            return tick;
        }
    }
    40
}

fn is_jumpable_gap(current: i32, next: i32, target: i32) -> bool {
    current == 2 && next == 0 && (target == 1 || target == 2)
}

fn tile(grid: u64, x: i32, z: i32) -> i32 {
    let index = ((x * 5 + z) * 2) as u32;
    ((grid >> index) & 3) as i32
}

fn recovery_drop(low: u64, high: u64, index: usize) -> Option<i32> {
    let encoded = if index < 16 {
        (low >> (index * 4)) & 0xF
    } else {
        (high >> ((index - 16) * 4)) & 0xF
    };
    if encoded == 0 {
        None
    } else {
        Some(encoded as i32 - 1)
    }
}

fn sign(value: f64) -> i32 {
    if value > 0.0 {
        1
    } else if value < 0.0 {
        -1
    } else {
        0
    }
}

fn discrete(value: f64) -> i32 {
    if value.abs() < 0.35 {
        0
    } else if value < 0.0 {
        -1
    } else {
        1
    }
}

fn pack_action(strategy: i32, flags: i32, forward: i32, strafe: i32) -> i32 {
    strategy | flags | ((forward + 1) << 8) | ((strafe + 1) << 10)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recovery_cells_span_both_words() {
        assert_eq!(recovery_drop(3_u64 << 60, 2, 15), Some(2));
        assert_eq!(recovery_drop(0, 2, 16), Some(1));
        assert_eq!(recovery_drop(0, 0, 24), None);
    }

    #[test]
    fn packed_inputs_round_trip() {
        let action = pack_action(STRATEGY_RECOVER, FLAG_SET_INPUT, -1, 1);
        assert_eq!((action >> 8) & 3, 0);
        assert_eq!((action >> 10) & 3, 2);
    }

    #[test]
    fn recovery_falls_back_to_nearest_water_when_trajectory_has_no_match() {
        // Cell (1, 0) has index 17 and therefore lives in the high word.
        let high = 1_u64 << 4;
        let target = choose_recovery_target(-1.0, 0.0, 0, high).unwrap();
        assert_eq!((target.x, target.z, target.drop), (1, 0, 0));
        assert!(!target.preserves_trajectory);
    }

    #[test]
    fn recovery_prefers_trajectory_preserving_water_over_fallback() {
        let high = 1_u64 << 4;
        let target = choose_recovery_target(1.0, 0.0, 0, high).unwrap();
        assert!(target.preserves_trajectory);
    }

    #[test]
    fn recovery_prioritizes_y_before_trajectory_alignment() {
        // Water behind at drop 0, and water ahead at drop 2. Preserving Y
        // wins, then fallback steering corrects toward the close-height water.
        let low = 3_u64 << 28; // (-1, 0), index 7, encoded drop 3 => drop 2
        let high = 1_u64 << 4; // (1, 0), index 17, encoded drop 1 => drop 0
        let target = choose_recovery_target(-1.0, 0.0, low, high).unwrap();
        assert_eq!((target.x, target.z, target.drop), (1, 0, 0));
        assert!(!target.preserves_trajectory);
    }

    #[test]
    fn jump_requires_solid_ground_then_an_air_gap() {
        assert!(is_jumpable_gap(2, 0, 1));
        assert!(is_jumpable_gap(2, 0, 2));
        assert!(!is_jumpable_gap(1, 0, 2));
    }
}
