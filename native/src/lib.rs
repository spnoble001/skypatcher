use jni::objects::{JClass, JLongArray, JObject, JString, JValue};
use jni::sys::{jboolean, jfloat, jint, jlong, jlongArray, JNI_VERSION_1_8};
use jni::{JNIEnv, JavaVM};
use std::ffi::c_void;
use std::sync::atomic::Ordering;
use std::sync::{Mutex, OnceLock};

/*
 * Aiming upward does not imply that a valid solid impact must end above the
 * player's instantaneous feet.  Water buoyancy, stair edges and uneven solid
 * ground routinely move that reference by a small amount.  Keep this narrow:
 * a genuinely lower throw still belongs to the descent gate.
 */
const SMART_PEARL_ASCENT_FALL_TOLERANCE: f64 = 2.0;

mod anti_fall;
mod lifecycle;
mod license;
mod patch_crumbs;
mod pre_patch;
mod simple_assist;

use anti_fall::*;
use patch_crumbs::*;
use pre_patch::*;
use simple_assist::*;

const MAX_AIM_STEPS: usize = 10;
const MAX_CANDIDATES: usize = 128;
const THROW_POT_ACTION_SELECT: i64 = 1;
const THROW_POT_ACTION_SEND_SLOT: i64 = 2;
const THROW_POT_ACTION_USE: i64 = 4;
const THROW_POT_ACTION_RESTORE: i64 = 8;
const THROW_POT_ACTION_FINISHED: i64 = 16;
const FLAG_VISIBLE: i32 = 1;
const FLAG_TEAMMATE: i32 = 2;
const FLAG_SELF: i32 = 4;
const FLAG_DEAD: i32 = 8;
const FLAG_POINTED: i32 = 16;

struct RightClickState {
    owner: i32,
    priority: i32,
}
impl RightClickState {
    fn new() -> Self {
        Self {
            owner: 0,
            priority: 0,
        }
    }
}
static AIM_STATE: OnceLock<Mutex<AimState>> = OnceLock::new();
static LEFT_CLICK_STATE: OnceLock<Mutex<LeftClickState>> = OnceLock::new();
static REFILL_RANDOM: OnceLock<Mutex<u64>> = OnceLock::new();
static REFILL_DELAY: OnceLock<Mutex<i32>> = OnceLock::new();
static RIGHT_CLICK_STATE: OnceLock<Mutex<RightClickState>> = OnceLock::new();

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_acquireRightClickNative(
    _env: JNIEnv,
    _class: JClass,
    requester: jint,
    requested_priority: jint,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    let Ok(mut state) = RIGHT_CLICK_STATE
        .get_or_init(|| Mutex::new(RightClickState::new()))
        .lock()
    else {
        return -1;
    };
    if requester <= 0 {
        return -1;
    }
    if state.owner == requester {
        return 0;
    }
    if state.owner != 0 && requested_priority <= state.priority {
        return -1;
    }
    let displaced = state.owner;
    state.owner = requester;
    state.priority = requested_priority;
    displaced
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_releaseRightClickNative(
    _env: JNIEnv,
    _class: JClass,
    requester: jint,
) {
    crate::lifecycle::jni_guarded!();

    if let Ok(mut state) = RIGHT_CLICK_STATE
        .get_or_init(|| Mutex::new(RightClickState::new()))
        .lock()
    {
        if state.owner == requester {
            state.owner = 0;
            state.priority = 0;
        }
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_isRightClickOwnerNative(
    _env: JNIEnv,
    _class: JClass,
    requester: jint,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    RIGHT_CLICK_STATE
        .get_or_init(|| Mutex::new(RightClickState::new()))
        .lock()
        .map(|state| (state.owner == requester) as jboolean)
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_rightClickOwnerNative(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    RIGHT_CLICK_STATE
        .get_or_init(|| Mutex::new(RightClickState::new()))
        .lock()
        .map(|state| state.owner)
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_rightClickPriorityNative(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    RIGHT_CLICK_STATE
        .get_or_init(|| Mutex::new(RightClickState::new()))
        .lock()
        .map(|state| state.priority)
        .unwrap_or(0)
}

static THROW_POT_STATE: OnceLock<Mutex<ThrowPotState>> = OnceLock::new();
static EQUIPO_STATE: OnceLock<Mutex<Vec<TeamMember>>> = OnceLock::new();

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: *mut jni::sys::JavaVM, _reserved: *mut c_void) -> jint {
    let Ok(vm) = (unsafe { JavaVM::from_raw(vm) }) else {
        return 0;
    };
    license::initialize_claim(jvm_system_property(&vm, "perfectpatch.game.dir"));
    JNI_VERSION_1_8
}

fn jvm_system_property(vm: &JavaVM, key: &str) -> Option<String> {
    let mut env = vm.get_env().ok()?;
    let system = env.find_class("java/lang/System").ok()?;
    let key = env.new_string(key).ok()?;
    let key = JObject::from(key);
    let value = env.call_static_method(
        system,
        "getProperty",
        "(Ljava/lang/String;)Ljava/lang/String;",
        &[JValue::Object(&key)],
    ).ok()?.l().ok()?;
    if value.is_null() { return None; }
    let value = JString::from(value);
    let value = env.get_string(&value).ok()?;
    Some(value.to_string_lossy().into_owned())
}

/** The sole Java JNI entry point. `convert_long_array` copies the JVM array,
 * so no pinned elements or explicit release operation can leak across calls. */
#[no_mangle]
pub unsafe extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_invoke(
    mut env: JNIEnv,
    class: JClass,
    opcode: jint,
    args: jlongArray,
) -> jlong {
    let array = unsafe { JLongArray::from_raw(args) };
    let length = match env.get_array_length(&array) {
        Ok(length) if length >= 0 && length <= 64 => length as usize,
        _ => return 0,
    };
    let mut values = vec![0 as jlong; length];
    if env.get_long_array_region(&array, 0, &mut values).is_err() {
        return 0;
    }
    let get = |index: usize| -> jlong { *values.get(index).unwrap_or(&0) };
    if opcode == 224 && values.len() >= 5 {
        let first_hash = get(0) as jint;
        let first_length = get(1) as jint;
        let second_hash = get(2) as jint;
        let second_length = get(3) as jint;
        let trailing = get(4) != 0;
        return if !trailing && first_length == 6 && first_hash == 1423379674 {
            1
        } else if !trailing
            && first_length == 3
            && first_hash == 47843
            && (second_length == 0 || (second_length == 8 && second_hash == 570403011))
        {
            2
        } else if !trailing && first_length == 9 && first_hash == -520810230 {
            8
        } else {
            4
        };
    }
    if protected_simple_assist_opcode(opcode) && !license::simple_assist_authorized() {
        return 0;
    }
    match values.len() {
        0 => dispatch0(env, class, opcode),
        1 | 2 => dispatch2(env, class, opcode, get(0), get(1)),
        3 => dispatch3(env, class, opcode, get(0), get(1), get(2)),
        4 => dispatch4(env, class, opcode, get(0), get(1), get(2), get(3)),
        5 | 6 => dispatch6(
            env,
            class,
            opcode,
            get(0),
            get(1),
            get(2),
            get(3),
            get(4),
            get(5),
        ),
        7 | 8 => dispatch8(
            env,
            class,
            opcode,
            get(0),
            get(1),
            get(2),
            get(3),
            get(4),
            get(5),
            get(6),
            get(7),
        ),
        9 | 10 => dispatch10(
            env,
            class,
            opcode,
            get(0),
            get(1),
            get(2),
            get(3),
            get(4),
            get(5),
            get(6),
            get(7),
            get(8),
            get(9),
        ),
        _ => dispatch16(
            env,
            class,
            opcode,
            get(0),
            get(1),
            get(2),
            get(3),
            get(4),
            get(5),
            get(6),
            get(7),
            get(8),
            get(9),
            get(10),
            get(11),
            get(12),
            get(13),
            get(14),
            get(15),
        ),
    }
}

fn protected_simple_assist_opcode(opcode: jint) -> bool {
    matches!(opcode,
        147..=178 | 192..=199 | 204 | 205 | 206 | 208..=212 |
        216..=220 | 225..=232 | 234 | 236..=238)
}

/// Replays the sampled pearl flight entirely in Rust. Java supplies only the
/// compact material/ray-hit observations needed to avoid JNI calls per step.
#[no_mangle]
pub unsafe extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_simulateSmartPearlGateNative(
    mut env: JNIEnv,
    _class: JClass,
    mut x: jni::sys::jdouble,
    mut y: jni::sys::jdouble,
    mut z: jni::sys::jdouble,
    mut vx: jni::sys::jdouble,
    mut vy: jni::sys::jdouble,
    mut vz: jni::sys::jdouble,
    player_y: jni::sys::jdouble,
    minimum_gain: jfloat,
    mode: jint,
    samples: jlongArray,
) -> jint {
    crate::lifecycle::jni_guarded!(0);
    let array = unsafe { JLongArray::from_raw(samples) };
    let length = match env.get_array_length(&array) {
        Ok(length) if length >= 7 => length as usize,
        _ => return 0,
    };
    let mut words = vec![0_i64; length.min(7)];
    if env.get_long_array_region(&array, 0, &mut words).is_err() { return 0; }
    for tick in 0..100_usize {
        let sample = ((words[tick >> 4] as u64 >> ((tick & 15) << 2)) & 0xF) as i32;
        let water = sample & 1 != 0;
        let drag = if water { 0.80 } else { 0.99 };
        let gravity = if water { 0.015 } else { 0.03 };
        vx *= drag;
        vy = vy * drag - gravity;
        vz *= drag;
        x += vx;
        y += vy;
        z += vz;
        if sample & 2 != 0 {
            return match mode {
                // Ascenso has a soft vertical admission band.  A clear gain
                // still satisfies MinY normally; impacts up to two blocks
                // below the instantaneous player Y remain valid only because
                // they already have a solid ray-hit terminal.  This absorbs
                // water buoyancy and small terrain transitions without
                // admitting a true low descent as an ascent throw.
                1 => (y >= player_y + minimum_gain as f64
                    || y >= player_y - SMART_PEARL_ASCENT_FALL_TOLERANCE) as jint,
                2 => ((tick + 1) <= 20) as jint,
                _ => 0,
            };
        }
    }
    0
}

extern "system" fn dispatch4(
    env: JNIEnv,
    class: JClass,
    opcode: jint,
    first: jlong,
    second: jlong,
    third: jlong,
    fourth: jlong,
) -> jlong {
    match opcode {
        214 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_submitPatchCrumbScanLaneNative(
                env, class, first, second, third, fourth,
            );
            0
        }
        220 => {
            let center = first as u64;
            let x = ((center as i64) << 43 >> 43) as jint;
            let y = (((center >> 21) as i64) << 43 >> 43) as jint;
            let z = (((center >> 42) as i64) << 43 >> 43) as jint;
            Java_perfectpathc_dev_nativecore_NativeBridge_finishPrePatchTickNative(
                env,
                class,
                x,
                y,
                z,
                second,
                third as jint,
                fourth as jint,
                (fourth >> 32) as jint,
            );
            0
        }
        233 => Java_perfectpathc_dev_nativecore_NativeBridge_selectObsidianRefillActionNative(
            env,
            class,
            first as jint,
            second as jint,
            third as jint,
            f32::from_bits(fourth as u32),
        ),
        235 => Java_perfectpathc_dev_nativecore_NativeBridge_selectPotionRefillActionNative(
            env,
            class,
            first as jint,
            second as jint,
            f32::from_bits(third as u32),
            (fourth != 0) as jboolean,
        ),
        _ => 0,
    }
}

extern "system" fn dispatch16(
    env: JNIEnv,
    class: JClass,
    opcode: jint,
    first: jlong,
    second: jlong,
    third: jlong,
    fourth: jlong,
    fifth: jlong,
    sixth: jlong,
    seventh: jlong,
    eighth: jlong,
    ninth: jlong,
    tenth: jlong,
    eleventh: jlong,
    twelfth: jlong,
    thirteenth: jlong,
    fourteenth: jlong,
    fifteenth: jlong,
    sixteenth: jlong,
) -> jlong {
    match opcode {
        135 => Java_perfectpathc_dev_nativecore_NativeBridge_nextCapturedAimActionPackedNative(
            env,
            class,
            f32::from_bits(first as u32),
            f32::from_bits(second as u32),
            f32::from_bits(third as u32),
            f32::from_bits(fourth as u32),
            f32::from_bits(fifth as u32),
            sixth as jint,
            f32::from_bits(seventh as u32),
            f32::from_bits(eighth as u32),
            f32::from_bits(ninth as u32),
            f32::from_bits(tenth as u32),
            f32::from_bits(eleventh as u32),
            f32::from_bits(twelfth as u32),
            (thirteenth & 1 != 0) as jboolean,
            (thirteenth & 2 != 0) as jboolean,
            (thirteenth & 4 != 0) as jboolean,
            (thirteenth & 8 != 0) as jboolean,
            (thirteenth & 16 != 0) as jboolean,
            fourteenth as jint,
            f32::from_bits(fifteenth as u32),
            f32::from_bits(sixteenth as u32),
        ),
        222 => Java_perfectpathc_dev_nativecore_NativeBridge_nextAntiFallActionNative(
            env,
            class,
            (first & 1 != 0) as jboolean,
            f32::from_bits(second as u32),
            f32::from_bits((second >> 32) as u32),
            f32::from_bits(third as u32),
            f32::from_bits((third >> 32) as u32),
            f64::from_bits(fifth as u64),
            f64::from_bits(sixth as u64),
            f64::from_bits(seventh as u64),
            f64::from_bits(eighth as u64),
            f64::from_bits(ninth as u64),
            f64::from_bits(tenth as u64),
            f64::from_bits(eleventh as u64),
            f64::from_bits(twelfth as u64),
            (first & 2 != 0) as jboolean,
            (first & 4 != 0) as jboolean,
            (first & 8 != 0) as jboolean,
            f32::from_bits(fourth as u32),
            (first & 16 != 0) as jboolean,
            (first & 32 != 0) as jboolean,
            thirteenth,
            fourteenth,
            fifteenth,
        ) as jlong,
        _ => 0,
    }
}

extern "system" fn dispatch10(
    env: JNIEnv,
    class: JClass,
    opcode: jint,
    first: jlong,
    second: jlong,
    third: jlong,
    fourth: jlong,
    fifth: jlong,
    sixth: jlong,
    seventh: jlong,
    eighth: jlong,
    ninth: jlong,
    tenth: jlong,
) -> jlong {
    match opcode {
        134 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_captureAimCandidateNative(
                env,
                class,
                first as jint,
                second as jint,
                f32::from_bits(third as u32),
                f32::from_bits(fourth as u32),
                f32::from_bits(fifth as u32),
                f32::from_bits(sixth as u32),
                f32::from_bits(seventh as u32),
                f32::from_bits(eighth as u32),
                f32::from_bits(ninth as u32),
                f32::from_bits(tenth as u32),
            );
            0
        }
        218 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_pushSimpleAssistNaturalCandidateNative(
                env,
                class,
                first,
                second as jint,
                f64::from_bits(third as u64),
                f64::from_bits(fourth as u64),
                f64::from_bits(fifth as u64),
                sixth as jint,
                f64::from_bits(seventh as u64),
                f64::from_bits(eighth as u64),
                f64::from_bits(ninth as u64),
                f64::from_bits(tenth as u64),
            );
            0
        }
        221 => Java_perfectpathc_dev_nativecore_NativeBridge_preparePatchCrumbTntCaptureNative(
            env,
            class,
            (first != 0) as jboolean,
            f64::from_bits(second as u64),
            f64::from_bits(third as u64),
            f64::from_bits(fourth as u64),
            f64::from_bits(fifth as u64),
            f64::from_bits(sixth as u64),
            f64::from_bits(seventh as u64),
            (eighth != 0) as jboolean,
            ninth,
        ) as jlong,
        _ => 0,
    }
}

extern "system" fn dispatch8(
    env: JNIEnv,
    class: JClass,
    opcode: jint,
    first: jlong,
    second: jlong,
    third: jlong,
    fourth: jlong,
    fifth: jlong,
    sixth: jlong,
    seventh: jlong,
    eighth: jlong,
) -> jlong {
    match opcode {
        133 => Java_perfectpathc_dev_nativecore_NativeBridge_selectCapturedAimTargetPackedNative(
            env,
            class,
            f32::from_bits(first as u32),
            f32::from_bits(second as u32),
            f32::from_bits(third as u32),
            f32::from_bits(fourth as u32),
            f32::from_bits(fifth as u32),
            f32::from_bits(sixth as u32),
            f32::from_bits(seventh as u32),
            (eighth != 0) as jboolean,
        ),
        _ => 0,
    }
}

extern "system" fn dispatch6(
    env: JNIEnv,
    class: JClass,
    opcode: jint,
    first: jlong,
    second: jlong,
    third: jlong,
    fourth: jlong,
    fifth: jlong,
    sixth: jlong,
) -> jlong {
    match opcode {
        96 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_startThrowPotNative(
                env,
                class,
                f32::from_bits(first as u32),
                second as jint,
                third as jint,
                (fourth & 1 != 0) as jboolean,
                (fourth & 2 != 0) as jboolean,
                (fourth & 4 != 0) as jboolean,
                fifth,
                sixth as jint,
            );
            0
        }
        131 => Java_perfectpathc_dev_nativecore_NativeBridge_nextAimStepNative(
            env,
            class,
            f32::from_bits(first as u32),
            f32::from_bits(second as u32),
            f32::from_bits(third as u32),
            (fourth != 0) as jboolean,
        )
        .to_bits() as jlong,
        132 => Java_perfectpathc_dev_nativecore_NativeBridge_nextAimNoiseDeltaNative(
            env,
            class,
            first as jint,
            f32::from_bits(second as u32),
            f32::from_bits(third as u32),
            f32::from_bits(fourth as u32),
            (fifth != 0) as jboolean,
        ),
        211 => Java_perfectpathc_dev_nativecore_NativeBridge_isSimpleAssistCaptureValidNative(
            env,
            class,
            first as jint,
            f64::from_bits(second as u64),
            f64::from_bits(third as u64),
            f64::from_bits(fourth as u64),
        ) as jlong,
        213 => Java_perfectpathc_dev_nativecore_NativeBridge_nextLeftClickerActionPackedNative(
            env,
            class,
            f32::from_bits(first as u32),
            f32::from_bits(second as u32),
            (third != 0) as jboolean,
            fourth,
            fifth,
            (sixth != 0) as jboolean,
        ),
        215 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_capturePatchCrumbNative(
                env,
                class,
                f64::from_bits(first as u64),
                f64::from_bits(second as u64),
                f64::from_bits(third as u64),
                fourth,
                fifth,
            );
            0
        }
        216 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_pushSimpleAssistGuidanceCandidateNative(
                env,
                class,
                first as jint,
                second as jint,
                f64::from_bits(third as u64),
                f64::from_bits(fourth as u64),
                f64::from_bits(fifth as u64),
                f64::from_bits(sixth as u64),
            );
            0
        }
        217 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_startSimpleAssistPatchNative(
                env,
                class,
                first,
                second,
                f64::from_bits(third as u64),
                f64::from_bits(fourth as u64),
                f64::from_bits(fifth as u64),
                sixth as jint,
            );
            0
        }
        219 => {
            let center = fifth as u64;
            let x = ((center as i64) << 43 >> 43) as jint;
            let y = (((center >> 21) as i64) << 43 >> 43) as jint;
            let z = (((center >> 42) as i64) << 43 >> 43) as jint;
            Java_perfectpathc_dev_nativecore_NativeBridge_beginPrePatchTickAndScanNative(
                env,
                class,
                first,
                f64::from_bits(second as u64),
                f64::from_bits(third as u64),
                f64::from_bits(fourth as u64),
                x,
                y,
                z,
                ((center >> 63) != 0) as jboolean,
            )
        }
        234 => Java_perfectpathc_dev_nativecore_NativeBridge_arbitrateSimpleAssistPatchNative(
            env,
            class,
            first,
            sixth as jint,
            f64::from_bits(second as u64),
            f64::from_bits(third as u64),
            f64::from_bits(fourth as u64),
            fifth as jint,
        ) as jlong,
        _ => 0,
    }
}

extern "system" fn dispatch3(
    env: JNIEnv,
    class: JClass,
    opcode: jint,
    first: jlong,
    second: jlong,
    third: jlong,
) -> jlong {
    match opcode {
        32 => Java_perfectpathc_dev_nativecore_NativeBridge_packBlockPosNative(
            env,
            class,
            first as jint,
            second as jint,
            third as jint,
        ),
        34 => Java_perfectpathc_dev_nativecore_NativeBridge_packBlockFlagsNative(
            env,
            class,
            first as jint,
            second as jint,
            third as jint,
        ) as jlong,
        62 => Java_perfectpathc_dev_nativecore_NativeBridge_resolvePatchCrumbImpactYNative(
            env,
            class,
            first as jint,
            second as jint,
            third as jint,
        ) as jlong,
        67 => Java_perfectpathc_dev_nativecore_NativeBridge_classifyItemPackedNative(
            env,
            class,
            first as jint,
            second as jint,
            third as jint,
        ) as jlong,
        68 => Java_perfectpathc_dev_nativecore_NativeBridge_selectRefillActionNative(
            env,
            class,
            first as jint,
            f32::from_bits(second as u32),
            (third != 0) as jboolean,
        ),
        202 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_resolvePatchCrumbDirectionNative(
                env,
                class,
                first as jint,
                (second != 0) as jboolean,
                (third != 0) as jboolean,
            );
            0
        }
        203 => Java_perfectpathc_dev_nativecore_NativeBridge_shouldScanPatchCrumbGuideNative(
            env,
            class,
            first as jint,
            second,
            third,
            ((first >> 32) as i16) as jint,
            ((first >> 48) & 0xFFFF) as jint,
        ) as jlong,
        204 => Java_perfectpathc_dev_nativecore_NativeBridge_updatePrePatchClickerNative(
            env,
            class,
            first as jint,
            second,
            f32::from_bits(third as u32),
        ) as jlong,
        205 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_submitPrePatchScanCandidateNative(
                env,
                class,
                first,
                (second != 0) as jboolean,
                (third != 0) as jboolean,
            );
            0
        }
        208 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_beginSimpleAssistPlacementNative(
                env,
                class,
                first,
                second as jint,
                third as jint,
            );
            0
        }
        209 => Java_perfectpathc_dev_nativecore_NativeBridge_confirmSimpleAssistPlacementNative(
            env,
            class,
            first as jint,
            second as jint,
            third as jint,
        ) as jlong,
        225 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_watchdogPrePatchNative(env, class, first)
                as jlong
        }
        224 => {
            let first_hash = first as jint;
            let first_length = (first >> 32) as jint;
            let second_hash = second as jint;
            let second_length = (second >> 32) as jint;
            let trailing = third != 0;
            if !trailing && first_length == 6 && first_hash == 1423379674 {
                1
            } else if !trailing
                && first_length == 3
                && first_hash == 47843
                && (second_length == 0 || (second_length == 8 && second_hash == 570403011))
            {
                2
            } else if !trailing && first_length == 9 && first_hash == -520810230 {
                8
            } else {
                4
            }
        }
        _ => 0,
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn patch_scan_side_round_trips_as_signed_i16() {
        let side = -1_i64;
        let control = (side & 0xffff) << 32;
        assert_eq!(((control >> 32) as i16) as i32, -1);
    }
}

extern "system" fn dispatch0(env: JNIEnv, class: JClass, opcode: jint) -> jlong {
    match opcode {
        1 => Java_perfectpathc_dev_nativecore_NativeBridge_quiesceNativeCoreNative(env, class)
            as jlong,
        2 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_nativeLifecycleNative(env, class) as jlong
        }
        48 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_resetPatchCrumbStateNative(env, class);
            0
        }
        49 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_completePatchCrumbTntCaptureNative(
                env, class,
            );
            0
        }
        50 => Java_perfectpathc_dev_nativecore_NativeBridge_patchCrumbTntSnapshotPackedNative(
            env, class,
        ),
        51 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_clearPatchCrumbActivePatchesNative(
                env, class,
            );
            0
        }
        53 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_syncPatchCrumbsToSimpleAssistNative(
                env, class,
            );
            0
        }
        54 => Java_perfectpathc_dev_nativecore_NativeBridge_patchCrumbLineCountNative(env, class)
            as jlong,
        55 => Java_perfectpathc_dev_nativecore_NativeBridge_patchCrumbRevisionNative(env, class),
        56 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_patchCrumbFlagsNative(env, class) as jlong
        }
        64 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_resetLeftClickerNative(env, class);
            0
        }
        65 => Java_perfectpathc_dev_nativecore_NativeBridge_advanceRefillDelayNative(env, class)
            as jlong,
        80 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_initializeEquipoNative(env, class);
            0
        }
        98 => Java_perfectpathc_dev_nativecore_NativeBridge_isThrowPotActiveNative(env, class)
            as jlong,
        99 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_resetThrowPotNative(env, class);
            0
        }
        112 => Java_perfectpathc_dev_nativecore_NativeBridge_antiFallMotionXNative(env, class)
            .to_bits() as jlong,
        113 => Java_perfectpathc_dev_nativecore_NativeBridge_antiFallMotionZNative(env, class)
            .to_bits() as jlong,
        114 => Java_perfectpathc_dev_nativecore_NativeBridge_antiFallStrategyNative(env, class)
            as jlong,
        115 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_resetAntiFallNative(env, class);
            0
        }
        128 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_initializeAimStateNative(env, class);
            0
        }
        129 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_resetAimStateNative(env, class);
            0
        }
        130 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_beginAimCaptureNative(env, class);
            0
        }
        147 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_resetSimpleAssistNative(env, class);
            0
        }
        148 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_hasSimpleAssistRequestNative(env, class)
                as jlong
        }
        149 => Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistRequestTargetNative(
            env, class,
        ),
        150 => Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistRequestSupportNative(
            env, class,
        ),
        151 => Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistRequestFaceNative(
            env, class,
        ) as jlong,
        152 => Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistRequestHitXNative(
            env, class,
        )
        .to_bits() as jlong,
        153 => Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistRequestHitYNative(
            env, class,
        )
        .to_bits() as jlong,
        154 => Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistRequestHitZNative(
            env, class,
        )
        .to_bits() as jlong,
        155 => Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistPendingTargetNative(
            env, class,
        ),
        156 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistPreviewNative(env, class)
        }
        157 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistPreviewXNative(env, class)
                as jlong
        }
        158 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistPreviewYNative(env, class)
                as jlong
        }
        159 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistPreviewZNative(env, class)
                as jlong
        }
        160 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_beginSimpleAssistPatchFrameNative(
                env, class,
            );
            0
        }
        161 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_simpleAssistPatchCountNative(env, class)
                as jlong
        }
        162 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistOriginNative(env, class)
        }
        163 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistTerminalNative(env, class)
        }
        164 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistCursorNative(env, class)
        }
        165 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_isSimpleAssistCapturedNative(env, class)
                as jlong
        }
        166 => Java_perfectpathc_dev_nativecore_NativeBridge_simpleAssistMaxCpsNative(env, class)
            .to_bits() as jlong,
        167 => Java_perfectpathc_dev_nativecore_NativeBridge_recordSimpleAssistRequestMissNative(
            env, class,
        ) as jlong,
        168 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_resetSimpleAssistRequestMissesNative(
                env, class,
            );
            0
        }
        192 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_resetPrePatchScanNative(env, class);
            0
        }
        193 => Java_perfectpathc_dev_nativecore_NativeBridge_prePatchRenderCountsNative(env, class),
        194 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_prePatchSyntheticSpreadNative(env, class)
                as jlong
        }
        236 => Java_perfectpathc_dev_nativecore_NativeBridge_prePatchActiveIntentFaceNative(env, class)
            as jlong,
        237 => Java_perfectpathc_dev_nativecore_NativeBridge_prePatchActiveIntentOriginNative(env, class),
        238 => Java_perfectpathc_dev_nativecore_NativeBridge_prePatchActiveIntentTerminalNative(env, class),
        239 => license::simple_assist_authorized() as jlong,
        _ => 0,
    }
}

extern "system" fn dispatch2(
    env: JNIEnv,
    class: JClass,
    opcode: jint,
    first: jlong,
    second: jlong,
) -> jlong {
    match opcode {
        16 => Java_perfectpathc_dev_nativecore_NativeBridge_acquireRightClickNative(
            env,
            class,
            first as jint,
            second as jint,
        ) as jlong,
        17 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_releaseRightClickNative(
                env,
                class,
                first as jint,
            );
            0
        }
        18 => Java_perfectpathc_dev_nativecore_NativeBridge_isRightClickOwnerNative(
            env,
            class,
            first as jint,
        ) as jlong,
        19 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_rightClickOwnerNative(env, class) as jlong
        }
        20 => Java_perfectpathc_dev_nativecore_NativeBridge_rightClickPriorityNative(env, class)
            as jlong,
        33 => Java_perfectpathc_dev_nativecore_NativeBridge_unpackBlockPosComponentNative(
            env,
            class,
            first,
            second as jint,
        ) as jlong,
        57 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_expirePatchCrumbNative(env, class, first)
                as jlong
        }
        58 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_beginPatchCrumbPatchScanNative(
                env, class, first,
            );
            0
        }
        59 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_expirePatchCrumbLinesNative(
                env, class, first,
            );
            0
        }
        60 => Java_perfectpathc_dev_nativecore_NativeBridge_patchCrumbLineOriginNative(
            env,
            class,
            first as jint,
        ),
        61 => Java_perfectpathc_dev_nativecore_NativeBridge_patchCrumbLineTerminalNative(
            env,
            class,
            first as jint,
        ),
        66 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_setRefillDelayNative(
                env,
                class,
                first as jint,
            );
            0
        }
        81 => Java_perfectpathc_dev_nativecore_NativeBridge_equipoContainsNative(
            env, class, first, second,
        ) as jlong,
        82 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_equipoAddNative(env, class, first, second)
                as jlong
        }
        83 => Java_perfectpathc_dev_nativecore_NativeBridge_equipoRemoveNative(
            env, class, first, second,
        ) as jlong,
        169 => Java_perfectpathc_dev_nativecore_NativeBridge_hasSimpleAssistGraceNative(
            env,
            class,
            first as jint,
        ) as jlong,
        170 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_extendSimpleAssistGraceNative(
                env,
                class,
                first as jint,
            );
            0
        }
        171 => Java_perfectpathc_dev_nativecore_NativeBridge_isSimpleAssistAcceptedBlockNative(
            env,
            class,
            first as jint,
        ) as jlong,
        172 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_isSimpleAssistReplaceableSnapshotNative(
                env,
                class,
                first as jint,
            ) as jlong
        }
        173 => Java_perfectpathc_dev_nativecore_NativeBridge_isSimpleAssistBackoffReadyNative(
            env,
            class,
            first as jint,
        ) as jlong,
        174 => Java_perfectpathc_dev_nativecore_NativeBridge_simpleAssistPhaseNative(
            env,
            class,
            first as jint,
        ) as jlong,
        175 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_beginSimpleAssistRequestNative(
                env, class, first,
            );
            0
        }
        176 => Java_perfectpathc_dev_nativecore_NativeBridge_nextSimpleAssistCursorNative(
            env,
            class,
            (first != 0) as jboolean,
        ),
        177 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_setSimpleAssistPreviewNative(
                env, class, first,
            );
            0
        }
        178 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_configureSimpleAssistAcceptedBlocksNative(
                env,
                class,
                first as jint,
                second as jint,
            );
            0
        }
        195 => Java_perfectpathc_dev_nativecore_NativeBridge_hasPrePatchVisibleAnchorNative(
            env,
            class,
            first,
            second as jint,
        ) as jlong,
        196 => Java_perfectpathc_dev_nativecore_NativeBridge_prePatchRenderRecordNative(
            env,
            class,
            first as jint,
        ),
        197 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_queuePrePatchPlacementCheckNative(
                env,
                class,
                first,
                second as jint,
            );
            0
        }
        198 => Java_perfectpathc_dev_nativecore_NativeBridge_pollPrePatchWorkNative(
            env,
            class,
            first as jint,
            second,
        ),
        199 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_recordPrePatchBlockPlacedNative(
                env, class, first, second,
            );
            0
        }
        206 => Java_perfectpathc_dev_nativecore_NativeBridge_matchesSimpleAssistPatchHitNative(
            env, class, first,
        ) as jlong,
        210 => Java_perfectpathc_dev_nativecore_NativeBridge_consumeSimpleAssistCreditNative(
            env,
            class,
            first as jint,
            f32::from_bits(second as u32),
        ) as jlong,
        225 => Java_perfectpathc_dev_nativecore_NativeBridge_hasSimpleAssistCreditNative(
            env,
            class,
            first as jint,
            f32::from_bits(second as u32),
        ) as jlong,
        226 => Java_perfectpathc_dev_nativecore_NativeBridge_watchdogSimpleAssistNative(
            env,
            class,
            first as jint,
        ) as jlong,
        227 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_reconcileSimpleAssistPatchFrameNative(
                env,
                class,
                first as jint,
            );
            0
        }
        228 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_recordSimpleAssistRejectedInteractionNative(
                env, class, first as jint,
            );
            0
        }
        229 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_suspendSimpleAssistWatchdogNative(
                env,
                class,
                first as jint,
            );
            0
        }
        230 => Java_perfectpathc_dev_nativecore_NativeBridge_prePatchDiagnosticTimingNative(
            env, class, first,
        ),
        231 => {
            Java_perfectpathc_dev_nativecore_NativeBridge_prePatchDiagnosticCountsNative(env, class)
        }
        232 => Java_perfectpathc_dev_nativecore_NativeBridge_matchesPrePatchIntentNative(
            env,
            class,
            first,
            second as jint,
        ) as jlong,
        212 => Java_perfectpathc_dev_nativecore_NativeBridge_pollSimpleAssistGuidanceSupportNative(
            env,
            class,
            first as jint,
        ),
        200 => Java_perfectpathc_dev_nativecore_NativeBridge_isPatchCrumbActiveNative(
            env, class, first,
        ) as jlong,
        201 => Java_perfectpathc_dev_nativecore_NativeBridge_patchCrumbPackedPosNative(
            env, class, first,
        ),
        97 => Java_perfectpathc_dev_nativecore_NativeBridge_nextThrowPotActionNative(
            env,
            class,
            first as jint,
            (second & 1 != 0) as jboolean,
            (second & 2 != 0) as jboolean,
        ),
        _ => 0,
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_nativeLifecycleNative(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    // Always readable — even after UnregisterNatives this export may already be cleared;
    // Java mainly uses its own lifecycle mirror after quiesce.
    lifecycle::lifecycle() as jint
}

/// Soft unload: drain ACTIVE_CALLS, wipe state, UnregisterNatives, mark DORMANT.
/// Does not FreeLibrary/dlclose (unsafe under HotSpot while the ClassLoader lives).
pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_quiesceNativeCoreNative(
    mut env: JNIEnv,
    class: JClass,
) -> jboolean {
    if !lifecycle::begin_quiesce() {
        return lifecycle::is_dormant() as jboolean;
    }

    // Reject new guarded entries, then wait for in-flight JNI calls to leave the .so.
    lifecycle::wait_until_idle();

    anti_fall::quiesce();
    simple_assist::quiesce();
    patch_crumbs::quiesce();
    pre_patch::quiesce();

    if let Ok(mut state) = RIGHT_CLICK_STATE
        .get_or_init(|| Mutex::new(RightClickState::new()))
        .lock()
    {
        state.owner = 0;
        state.priority = 0;
    }
    if let Ok(mut state) = THROW_POT_STATE
        .get_or_init(|| Mutex::new(ThrowPotState::new()))
        .lock()
    {
        state.reset();
    }
    if let Ok(mut state) = LEFT_CLICK_STATE
        .get_or_init(|| Mutex::new(LeftClickState::new()))
        .lock()
    {
        state.reset();
        state.random = 0;
    }
    if let Ok(mut state) = AIM_STATE
        .get_or_init(|| Mutex::new(AimState::new(0, 0)))
        .lock()
    {
        for candidate in state.captured_candidates.iter_mut() {
            candidate.id = 0;
            candidate.flags = 0;
            candidate.values = [0.0; 9];
        }
        state.captured_candidates.clear();
        state.captured_candidates.shrink_to_fit();
        *state = AimState::new(0, 0);
        state.aim_step_random = 0;
    }
    if let Ok(mut state) = REFILL_RANDOM.get_or_init(|| Mutex::new(0)).lock() {
        *state = 0;
    }
    if let Ok(mut state) = REFILL_DELAY.get_or_init(|| Mutex::new(0)).lock() {
        *state = 0;
    }
    if let Ok(mut state) = EQUIPO_STATE.get_or_init(|| Mutex::new(Vec::new())).lock() {
        for member in state.iter_mut() {
            member.most = 0;
            member.least = 0;
        }
        state.clear();
        state.shrink_to_fit();
    }

    // Drop JVM native method pointers for NativeBridge (HotSpot clears all natives on class).
    let _ = env.unregister_native_methods(class);

    std::sync::atomic::compiler_fence(Ordering::SeqCst);
    lifecycle::finish_dormant();
    1
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_packBlockPosNative(
    _env: JNIEnv,
    _class: JClass,
    x: jint,
    y: jint,
    z: jint,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);
    ((x as i64 & 0x3FF_FFFF) << 38) | ((y as i64 & 0xFFF) << 26) | (z as i64 & 0x3FF_FFFF)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_unpackBlockPosComponentNative(
    _env: JNIEnv,
    _class: JClass,
    packed: jlong,
    component: jint,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    match component {
        0 => (packed >> 38) as jint,
        1 => ((packed << 26) >> 52) as jint,
        2 => ((packed << 38) >> 38) as jint,
        _ => 0,
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_packBlockFlagsNative(
    _env: JNIEnv,
    _class: JClass,
    face: jint,
    action: jint,
    ticks: jint,
) -> jint {
    crate::lifecycle::jni_guarded!(0);
    (face & 0xF) | ((action & 0xF) << 4) | ((ticks & 0xFF) << 8)
}

#[derive(Clone, Copy)]
struct TeamMember {
    most: i64,
    least: i64,
}

struct ThrowPotState {
    active: bool,
    phase: u8,
    initial_slot: i32,
    potion_slot: i32,
    detour_slot: i32,
    total_delay_ms: i64,
    detour_delay_ms: i64,
    phase_delay_ticks: f64,
    phase_credit: f64,
    last_credit_tick: i32,
    transition_noise: Perlin1d,
    sequence: u64,
    transition_initialized: bool,
}

impl ThrowPotState {
    fn new() -> Self {
        Self {
            active: false,
            phase: 0,
            initial_slot: 0,
            potion_slot: 0,
            detour_slot: -1,
            total_delay_ms: 0,
            detour_delay_ms: 0,
            phase_delay_ticks: 0.0,
            phase_credit: 0.0,
            last_credit_tick: i32::MIN,
            transition_noise: Perlin1d::new(0),
            sequence: 0,
            transition_initialized: false,
        }
    }

    fn reset(&mut self) {
        self.active = false;
        self.phase = 0;
        self.initial_slot = 0;
        self.potion_slot = 0;
        self.detour_slot = -1;
        self.total_delay_ms = 0;
        self.detour_delay_ms = 0;
        self.phase_delay_ticks = 0.0;
        self.phase_credit = 0.0;
        self.last_credit_tick = i32::MIN;
    }
}

struct LeftClickState {
    random: u64,
    pressed: bool,
    organic: bool,
    next_press_ms: i64,
    release_ms: i64,
    next_press_ns: i64,
    release_ns: i64,
    trend: f64,
}

impl LeftClickState {
    fn new() -> Self {
        Self {
            random: 0xA076_1D64_78BD_642F,
            pressed: false,
            organic: false,
            next_press_ms: 0,
            release_ms: 0,
            next_press_ns: 0,
            release_ns: 0,
            trend: 0.0,
        }
    }

    fn reset(&mut self) {
        *self = Self::new();
    }
}

struct AimState {
    yaw_noise: Perlin1d,
    pitch_noise: Perlin1d,
    previous_yaw_noise: f64,
    previous_pitch_noise: f64,
    noise_initialized: bool,
    has_target: bool,
    target_id: i32,
    captured_candidates: Vec<CapturedCandidate>,
    aim_steps: [f32; MAX_AIM_STEPS],
    aim_step_cursor: usize,
    aim_step_random: u64,
}

struct CapturedCandidate {
    id: i32,
    flags: i32,
    values: [f32; 9],
}

impl AimState {
    fn new(yaw_seed: u64, pitch_seed: u64) -> Self {
        Self {
            yaw_noise: Perlin1d::new(yaw_seed),
            pitch_noise: Perlin1d::new(pitch_seed),
            previous_yaw_noise: 0.0,
            previous_pitch_noise: 0.0,
            noise_initialized: false,
            has_target: false,
            target_id: 0,
            captured_candidates: Vec::with_capacity(MAX_CANDIDATES),
            aim_steps: [0.0; MAX_AIM_STEPS],
            aim_step_cursor: MAX_AIM_STEPS,
            aim_step_random: yaw_seed ^ pitch_seed,
        }
    }

    fn reset(&mut self, yaw_seed: u64, pitch_seed: u64) {
        *self = Self::new(yaw_seed, pitch_seed);
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_nextAimStepNative(
    _env: JNIEnv,
    _class: JClass,
    difference: jfloat,
    speed: jfloat,
    sensitivity: jfloat,
    discard: jboolean,
) -> jfloat {
    crate::lifecycle::jni_guarded!(0.0);

    let state = AIM_STATE.get_or_init(|| Mutex::new(AimState::new(0, 0)));
    let Ok(mut state) = state.lock() else {
        return 0.0;
    };
    if discard != 0 {
        state.aim_step_cursor = MAX_AIM_STEPS;
    }
    if state.aim_step_cursor >= MAX_AIM_STEPS {
        let mut remaining = difference;
        let mut random = state.aim_step_random;
        for index in 0..MAX_AIM_STEPS {
            let multiplier = 1.0 + unit_random(&mut random);
            let divisor = 5.0 + (next_u32(&mut random) % 9) as f32;
            let adjusted = if remaining.abs() < 7.0 {
                remaining * 2.0
            } else {
                remaining
            };
            let delta = ((adjusted * sensitivity * (speed * 100.0 + multiplier) / divisor) as i32)
                as f32
                * 0.0015;
            state.aim_steps[index] = delta;
            remaining -= delta;
        }
        state.aim_step_random = random;
        state.aim_step_cursor = 0;
    }
    let step = state.aim_steps[state.aim_step_cursor];
    state.aim_step_cursor += 1;
    step
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_nextAimNoiseDeltaNative(
    _env: JNIEnv,
    _class: JClass,
    ticks: jint,
    partial: jfloat,
    strength: jfloat,
    speed: jfloat,
    target_changed: jboolean,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    let state = AIM_STATE.get_or_init(|| Mutex::new(AimState::new(0, 0)));
    let Ok(mut state) = state.lock() else {
        return 0;
    };
    let time = (ticks as f64 + partial as f64) * speed as f64;
    let yaw = state.yaw_noise.sample(time);
    let pitch = state.pitch_noise.sample(time + 137.31);
    let (yaw_delta, pitch_delta) = if target_changed != 0 || !state.noise_initialized {
        state.previous_yaw_noise = yaw;
        state.previous_pitch_noise = pitch;
        state.noise_initialized = true;
        (0.0_f32, 0.0_f32)
    } else {
        let yd = ((yaw - state.previous_yaw_noise) * strength as f64) as f32;
        let pd =
            ((pitch - state.previous_pitch_noise) * strength as f64 * 0.6499999761581421) as f32;
        state.previous_yaw_noise = yaw;
        state.previous_pitch_noise = pitch;
        (yd, pd)
    };
    ((yaw_delta.to_bits() as u64 as jlong) << 32) | pitch_delta.to_bits() as jlong
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_beginAimCaptureNative(
    _env: JNIEnv,
    _class: JClass,
) {
    crate::lifecycle::jni_guarded!();

    let state = AIM_STATE.get_or_init(|| Mutex::new(AimState::new(0, 0)));
    if let Ok(mut state) = state.lock() {
        state.captured_candidates.clear();
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_captureAimCandidateNative(
    _env: JNIEnv,
    _class: JClass,
    id: jint,
    flags: jint,
    x: jfloat,
    y: jfloat,
    z: jfloat,
    min_x: jfloat,
    max_x: jfloat,
    min_z: jfloat,
    max_z: jfloat,
    max_y: jfloat,
) {
    crate::lifecycle::jni_guarded!();

    let state = AIM_STATE.get_or_init(|| Mutex::new(AimState::new(0, 0)));
    if let Ok(mut state) = state.lock() {
        if state.captured_candidates.len() < MAX_CANDIDATES {
            state.captured_candidates.push(CapturedCandidate {
                id,
                flags,
                values: [x, y, z, min_x, max_x, min_z, max_z, max_y, 0.0],
            });
        }
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_selectCapturedAimTargetPackedNative(
    _env: JNIEnv,
    _class: JClass,
    player_x: jfloat,
    player_y: jfloat,
    player_z: jfloat,
    player_yaw: jfloat,
    fov: jfloat,
    minimum_distance: jfloat,
    maximum_distance: jfloat,
    lock: jboolean,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    let state = AIM_STATE.get_or_init(|| Mutex::new(AimState::new(0, 0)));
    let Ok(mut state) = state.lock() else {
        return jlong::MIN;
    };
    let mut selected = None;
    let mut closest = f32::MAX;
    for (index, candidate) in state.captured_candidates.iter().enumerate() {
        if candidate.flags & (FLAG_VISIBLE | FLAG_TEAMMATE | FLAG_SELF | FLAG_DEAD) != FLAG_VISIBLE
        {
            continue;
        }
        let distance = entity_distance(
            player_x,
            player_y,
            player_z,
            candidate.values[0],
            candidate.values[1],
            candidate.values[2],
        );
        let yaw = required_yaw(&candidate.values, player_x, player_z, player_yaw, 0.0, 0.0);
        if yaw.abs() > fov || distance < minimum_distance || distance > maximum_distance {
            continue;
        }
        if lock != 0 && state.has_target && state.target_id == candidate.id {
            selected = Some(index);
            break;
        }
        if distance <= closest {
            closest = distance;
            selected = Some(index);
        }
    }
    let Some(index) = selected else {
        return jlong::MIN;
    };
    if state.captured_candidates[index].flags & FLAG_POINTED != 0 {
        state.noise_initialized = false;
        return jlong::MIN;
    }
    let id = state.captured_candidates[index].id;
    let values = state.captured_candidates[index].values;
    let yaw = required_yaw(&values, player_x, player_z, player_yaw, 0.0, 0.0);
    let changed = !state.has_target || state.target_id != id;
    state.has_target = true;
    state.target_id = id;
    let packed_id = (id as u32 & 0x7FFF_FFFF) | if changed { 0x8000_0000 } else { 0 };
    ((yaw.to_bits() as u64 as jlong) << 32) | packed_id as jlong
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_nextCapturedAimActionPackedNative(
    _env: JNIEnv,
    _class: JClass,
    player_x: jfloat,
    player_y: jfloat,
    player_z: jfloat,
    player_yaw: jfloat,
    player_pitch: jfloat,
    ticks: jint,
    partial_tick: jfloat,
    mouse_sensitivity: jfloat,
    fov: jfloat,
    speed: jfloat,
    minimum_distance: jfloat,
    maximum_distance: jfloat,
    lock: jboolean,
    click_required: jboolean,
    click_down: jboolean,
    sword_only: jboolean,
    axe_only: jboolean,
    held_item_kind: jint,
    noise_strength: jfloat,
    noise_speed: jfloat,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    let state = AIM_STATE.get_or_init(|| Mutex::new(AimState::new(0, 0)));
    let Ok(mut state) = state.lock() else {
        return jlong::MIN;
    };

    let allowed_tool = (if sword_only != 0 { 1 } else { 0 }) | (if axe_only != 0 { 2 } else { 0 });
    if (click_required != 0 && click_down == 0)
        || (allowed_tool != 0 && held_item_kind & allowed_tool == 0)
    {
        state.noise_initialized = false;
        return jlong::MIN;
    }

    let mut selected = None;
    let mut closest = f32::MAX;
    for (index, candidate) in state.captured_candidates.iter().enumerate() {
        if candidate.flags & (FLAG_VISIBLE | FLAG_TEAMMATE | FLAG_SELF | FLAG_DEAD) != FLAG_VISIBLE
        {
            continue;
        }
        let distance = entity_distance(
            player_x,
            player_y,
            player_z,
            candidate.values[0],
            candidate.values[1],
            candidate.values[2],
        );
        let yaw = required_yaw(&candidate.values, player_x, player_z, player_yaw, 0.0, 0.0);
        if yaw.abs() > fov || distance < minimum_distance || distance > maximum_distance {
            continue;
        }
        if lock != 0 && state.has_target && state.target_id == candidate.id {
            selected = Some(index);
            break;
        }
        if distance <= closest {
            closest = distance;
            selected = Some(index);
        }
    }
    let Some(index) = selected else {
        state.noise_initialized = false;
        return jlong::MIN;
    };

    let candidate = &state.captured_candidates[index];
    if candidate.flags & FLAG_POINTED != 0 {
        // The original selects the closest target first, then declines to aim
        // when that selected target is the entity under the crosshair.
        state.noise_initialized = false;
        return jlong::MIN;
    }
    let id = candidate.id;
    let values = candidate.values;
    let required_yaw = required_yaw(&values, player_x, player_z, player_yaw, 0.0, 0.0);
    let target_changed = !state.has_target || state.target_id != id;
    state.has_target = true;
    state.target_id = id;
    // Same per-render calculation as the original randIt method.  Do not
    // amortize the rotation into a multi-tick sequence: that changes both the
    // responsiveness and its random cadence.
    let sensitivity_base = mouse_sensitivity * 0.6 + 0.2;
    let sensitivity = sensitivity_base.powi(3) * 8.0;
    let difference = if required_yaw.abs() < 7.0 {
        required_yaw * 2.0
    } else {
        required_yaw
    };
    let mut random = state.aim_step_random;
    let multiplier = 1.0 + unit_random(&mut random);
    let divisor = 5.0 + (next_u32(&mut random) % 9) as f32;
    let aim_delta = ((difference * sensitivity * (speed * 100.0 + multiplier) / divisor) as i32)
        as f32
        * 0.0015;
    state.aim_step_random = random;

    let time = (ticks as f64 + partial_tick as f64) * noise_speed as f64;
    let yaw_noise = state.yaw_noise.sample(time);
    let pitch_noise = state.pitch_noise.sample(time + 137.31);
    let (noise_yaw, noise_pitch) = if target_changed || !state.noise_initialized {
        state.previous_yaw_noise = yaw_noise;
        state.previous_pitch_noise = pitch_noise;
        state.noise_initialized = true;
        (0.0_f32, 0.0_f32)
    } else {
        let yaw = ((yaw_noise - state.previous_yaw_noise) * noise_strength as f64) as f32;
        let pitch = ((pitch_noise - state.previous_pitch_noise)
            * noise_strength as f64
            * 0.6499999761581421) as f32;
        state.previous_yaw_noise = yaw_noise;
        state.previous_pitch_noise = pitch_noise;
        (yaw, pitch)
    };
    let yaw_delta = aim_delta + noise_yaw;
    let pitch = (player_pitch + noise_pitch).max(-90.0).min(90.0);
    ((yaw_delta.to_bits() as u64 as jlong) << 32) | pitch.to_bits() as jlong
}

struct Perlin1d {
    permutation: [u8; 512],
}

impl Perlin1d {
    fn new(mut seed: u64) -> Self {
        let mut values = [0_u8; 256];
        for (index, value) in values.iter_mut().enumerate() {
            *value = index as u8;
        }
        for index in (1..values.len()).rev() {
            let swap = (next_u32(&mut seed) as usize) % (index + 1);
            values.swap(index, swap);
        }
        let mut permutation = [0_u8; 512];
        for index in 0..permutation.len() {
            permutation[index] = values[index & 0xFF];
        }
        Self { permutation }
    }

    fn sample(&self, value: f64) -> f64 {
        let cell = fast_floor(value);
        let local = value - cell as f64;
        let left_hash = self.permutation[(cell & 0xFF) as usize];
        let right_hash = self.permutation[((cell + 1) & 0xFF) as usize];
        let left = gradient(left_hash, local);
        let right = gradient(right_hash, local - 1.0);
        lerp(left, right, fade(local)) * 2.0
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_initializeAimStateNative(
    _env: JNIEnv,
    _class: JClass,
) {
    crate::lifecycle::jni_guarded!();

    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|value| value.as_nanos() as u64)
        .unwrap_or(0xA076_1D64_78BD_642F);
    let state = AIM_STATE.get_or_init(|| Mutex::new(AimState::new(nanos, nanos.rotate_left(29))));
    if let Ok(mut state) = state.lock() {
        state.reset(nanos, nanos.rotate_left(29));
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_resetAimStateNative(
    _env: JNIEnv,
    _class: JClass,
) {
    crate::lifecycle::jni_guarded!();

    let state = AIM_STATE.get_or_init(|| Mutex::new(AimState::new(0, 0)));
    if let Ok(mut state) = state.lock() {
        state.has_target = false;
        state.target_id = 0;
        state.captured_candidates.clear();
        state.aim_step_cursor = MAX_AIM_STEPS;
        state.noise_initialized = false;
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_resetLeftClickerNative(
    _env: JNIEnv,
    _class: JClass,
) {
    crate::lifecycle::jni_guarded!();

    let state = LEFT_CLICK_STATE.get_or_init(|| Mutex::new(LeftClickState::new()));
    if let Ok(mut state) = state.lock() {
        state.reset();
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_nextLeftClickerActionPackedNative(
    _env: JNIEnv,
    _class: JClass,
    minimum_cps: jfloat,
    maximum_cps: jfloat,
    organic: jboolean,
    now_millis: jlong,
    now_nanos: jlong,
    block: jboolean,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    let state = LEFT_CLICK_STATE.get_or_init(|| Mutex::new(LeftClickState::new()));
    let Ok(mut state) = state.lock() else {
        return 0;
    };
    let minimum = minimum_cps.min(maximum_cps).max(0.5) as f64;
    let maximum = maximum_cps.max(minimum_cps).max(0.5) as f64;
    if state.organic != (organic != 0) {
        state.reset();
        state.organic = organic != 0;
    }
    let mut action = 0_i64;
    let mut should_block = false;
    if organic != 0 {
        if state.pressed && now_nanos >= state.release_ns {
            state.pressed = false;
            action = 2;
            should_block = block != 0 && next_u32(&mut state.random) % 100 <= 10;
        }
        if !state.pressed && now_nanos >= state.next_press_ns {
            let interval = organic_interval(&mut state, minimum, maximum);
            let hold = organic_hold(&mut state, interval);
            state.pressed = true;
            state.next_press_ns = now_nanos + interval;
            state.release_ns = now_nanos + hold;
            action = 1;
        }
    } else {
        if state.next_press_ms == 0 {
            schedule_normal(&mut state, minimum, maximum, now_millis);
        }
        if state.pressed && now_millis >= state.release_ms {
            state.pressed = false;
            action = 2;
            should_block = block != 0 && next_u32(&mut state.random) % 100 <= 10;
        }
        if !state.pressed && now_millis >= state.next_press_ms {
            state.pressed = true;
            action = 1;
            schedule_normal(&mut state, minimum, maximum, now_millis);
        }
    }
    action | if should_block { 1_i64 << 8 } else { 0 }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_selectRefillActionNative(
    _env: JNIEnv,
    _class: JClass,
    candidate_mask: jint,
    speed: jfloat,
    random: jboolean,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    let mask = candidate_mask as u32 & ((1_u32 << 27) - 1);
    if mask == 0 {
        return -1;
    }
    let state = REFILL_RANDOM.get_or_init(|| Mutex::new(0x8CB9_2BA7_2F3D_8DD7));
    let Ok(mut state) = state.lock() else {
        return -1;
    };
    let count = mask.count_ones();
    let ordinal = if random != 0 {
        next_u32(&mut state) % count
    } else {
        0
    };
    let mut seen = 0;
    let mut slot = 0_i32;
    for index in 0..27 {
        if mask & (1_u32 << index) == 0 {
            continue;
        }
        if seen == ordinal {
            slot = index as i32 + 9;
            break;
        }
        seen += 1;
    }
    let bounded = speed.max(1.0).min(12.0);
    let lower = (10.0 / bounded).floor().max(1.0) as i32;
    let upper = (10.0 / bounded).ceil().max(lower as f32) as i32;
    let delay = lower + (next_u32(&mut state) % (upper - lower + 1) as u32) as i32;
    slot as jlong | ((delay as jlong & 0xFF) << 8)
}

/// Chooses a single deterministic inventory-to-hotbar obsidian swap. Java only
/// observes inventory tuples and executes the click; target admission and
/// pacing stay native. A selected hotbar position is satisfied only by ID 49.
pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_selectObsidianRefillActionNative(
    _env: JNIEnv,
    _class: JClass,
    candidate_mask: jint,
    target_mask: jint,
    obsidian_hotbar_mask: jint,
    speed: jfloat,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    let candidates = candidate_mask as u32 & ((1_u32 << 27) - 1);
    let targets = target_mask as u32 & 0x1ff;
    let missing = targets & !(obsidian_hotbar_mask as u32 & 0x1ff);
    if candidates == 0 || missing == 0 {
        return -1;
    }
    let target = missing.trailing_zeros() as i32;
    let source = candidates.trailing_zeros() as i32 + 9;
    let bounded = speed.max(1.0).min(12.0);
    let delay = (10.0 / bounded).ceil().max(1.0) as i32;
    target as jlong | ((source as jlong & 0xff) << 8) | ((delay as jlong & 0xff) << 16)
}

/// Selects an inventory potion and an exact empty target outside the obsidian
/// reservation. The action format matches the directed obsidian swap.
pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_selectPotionRefillActionNative(
    _env: JNIEnv,
    _class: JClass,
    candidate_mask: jint,
    allowed_empty_hotbar_mask: jint,
    speed: jfloat,
    random: jboolean,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    let candidates = candidate_mask as u32 & ((1_u32 << 27) - 1);
    let targets = allowed_empty_hotbar_mask as u32 & 0x1ff;
    if candidates == 0 || targets == 0 {
        return -1;
    }
    let state = REFILL_RANDOM.get_or_init(|| Mutex::new(0x8CB9_2BA7_2F3D_8DD7));
    let Ok(mut state) = state.lock() else {
        return -1;
    };
    let ordinal = if random != 0 {
        next_u32(&mut state) % candidates.count_ones()
    } else {
        0
    };
    let mut seen = 0;
    let mut source = 9_i32;
    for index in 0..27 {
        if candidates & (1_u32 << index) != 0 {
            if seen == ordinal {
                source = index as i32 + 9;
                break;
            }
            seen += 1;
        }
    }
    let target = targets.trailing_zeros() as i32;
    let bounded = speed.max(1.0).min(12.0);
    let delay = (10.0 / bounded).ceil().max(1.0) as i32;
    target as jlong | ((source as jlong & 0xff) << 8) | ((delay as jlong & 0xff) << 16)
}


pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_classifyItemPackedNative(
    _env: JNIEnv,
    _class: JClass,
    item_id: jint,
    metadata: jint,
    _stack_size: jint,
) -> jint {
    crate::lifecycle::jni_guarded!(0);

    let mut flags = 0;
    // Minecraft 1.8.9 registry IDs. Java forwards only primitive item attributes.
    if item_id == 373 && metadata & 0x4000 != 0 {
        flags |= 1;
    }
    if item_id == 49 {
        flags |= 2;
    }
    if matches!(item_id, 268 | 272 | 267 | 276 | 283) {
        flags |= 4;
    }
    if matches!(item_id, 271 | 275 | 258 | 279 | 286) {
        flags |= 8;
    }
    flags
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_advanceRefillDelayNative(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    let delay = REFILL_DELAY.get_or_init(|| Mutex::new(0));
    let Ok(mut delay) = delay.lock() else {
        return 0;
    };
    if *delay > 0 {
        *delay -= 1;
        0
    } else {
        1
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_setRefillDelayNative(
    _env: JNIEnv,
    _class: JClass,
    ticks: jint,
) {
    crate::lifecycle::jni_guarded!();
    if let Ok(mut delay) = REFILL_DELAY.get_or_init(|| Mutex::new(0)).lock() {
        *delay = ticks.max(0);
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_startThrowPotNative(
    _env: JNIEnv,
    _class: JClass,
    requested_center: jfloat,
    initial_slot: jint,
    potion_slot: jint,
    detour_enabled: jboolean,
    _archon: jboolean,
    _debug: jboolean,
    random_state: jlong,
    current_tick: jint,
) {
    crate::lifecycle::jni_guarded!();

    let mut state = random_state as u64;
    if state == 0 {
        state = 0xD1B5_4A32_D192_ED03;
    }
    let center = requested_center.max(100.0).min(300.0) as f64;
    let delay = sample_throw_delay(&mut state, center);
    let machine = THROW_POT_STATE.get_or_init(|| Mutex::new(ThrowPotState::new()));
    if let Ok(mut machine) = machine.lock() {
        if !machine.transition_initialized {
            machine.transition_noise = Perlin1d::new(next_u32(&mut state) as u64);
            machine.transition_initialized = true;
        }
        // Preserve the original call order: chooseDetourSlot observes sequence,
        // then shouldUseDetour samples it and advances that sequence.
        let candidate = choose_throw_detour_slot(
            &machine.transition_noise,
            machine.sequence,
            initial_slot,
            potion_slot,
        );
        let use_detour = if detour_enabled != 0 && candidate >= 0 {
            let correlated = (machine
                .transition_noise
                .sample(machine.sequence as f64 * 0.17)
                + 1.0)
                * 0.5;
            machine.sequence += 1;
            correlated > 0.52
        } else {
            false
        };
        let detour_at = if use_detour {
            detour_delay(&machine.transition_noise, machine.sequence, delay)
        } else {
            0
        };
        machine.active = true;
        machine.phase = 0;
        machine.initial_slot = initial_slot;
        machine.potion_slot = potion_slot;
        machine.detour_slot = if use_detour { candidate } else { -1 };
        machine.total_delay_ms = delay;
        machine.detour_delay_ms = detour_at;
        // Phase 0 executes immediately; subsequent phases wait exactly the
        // same portions of the sampled delay as the old worker.
        machine.phase_delay_ticks = 0.0;
        machine.phase_credit = 0.0;
        machine.last_credit_tick = current_tick;
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_nextThrowPotActionNative(
    _env: JNIEnv,
    _class: JClass,
    current_tick: jint,
    can_continue: jboolean,
    splash_potion_held: jboolean,
) -> jlong {
    crate::lifecycle::jni_guarded!(0);

    let machine = THROW_POT_STATE.get_or_init(|| Mutex::new(ThrowPotState::new()));
    let Ok(mut machine) = machine.lock() else {
        return 0;
    };
    if !machine.active {
        return 0;
    }
    if can_continue == 0 {
        let slot = machine.initial_slot;
        machine.reset();
        return pack_throw_action(
            THROW_POT_ACTION_SELECT
                | THROW_POT_ACTION_SEND_SLOT
                | THROW_POT_ACTION_RESTORE
                | THROW_POT_ACTION_FINISHED,
            slot,
        );
    }
    if current_tick > machine.last_credit_tick {
        machine.phase_credit += (current_tick - machine.last_credit_tick) as f64;
        machine.last_credit_tick = current_tick;
    }
    if machine.phase_credit < machine.phase_delay_ticks {
        return 0;
    }
    machine.phase_credit -= machine.phase_delay_ticks;
    match machine.phase {
        // Original: select detour, sleep detourDelay, then select/use potion.
        0 => {
            if machine.detour_slot >= 0 {
                machine.phase = 1;
                machine.phase_delay_ticks = milliseconds_to_ticks(machine.detour_delay_ms);
                pack_throw_action(
                    THROW_POT_ACTION_SELECT | THROW_POT_ACTION_SEND_SLOT,
                    machine.detour_slot,
                )
            } else {
                machine.phase = 2;
                machine.phase_delay_ticks = milliseconds_to_ticks(machine.total_delay_ms);
                pack_throw_action(
                    THROW_POT_ACTION_SELECT | THROW_POT_ACTION_SEND_SLOT | THROW_POT_ACTION_USE,
                    machine.potion_slot,
                )
            }
        }
        // The delayed use must still have a splash potion in hand.
        1 => {
            if splash_potion_held == 0 {
                let slot = machine.initial_slot;
                machine.reset();
                return pack_throw_action(
                    THROW_POT_ACTION_SELECT
                        | THROW_POT_ACTION_SEND_SLOT
                        | THROW_POT_ACTION_RESTORE
                        | THROW_POT_ACTION_FINISHED,
                    slot,
                );
            }
            machine.phase = 2;
            machine.phase_delay_ticks =
                milliseconds_to_ticks((machine.total_delay_ms - machine.detour_delay_ms).max(20));
            pack_throw_action(
                THROW_POT_ACTION_SELECT | THROW_POT_ACTION_SEND_SLOT | THROW_POT_ACTION_USE,
                machine.potion_slot,
            )
        }
        _ => {
            let slot = machine.initial_slot;
            machine.reset();
            pack_throw_action(
                THROW_POT_ACTION_SELECT
                    | THROW_POT_ACTION_SEND_SLOT
                    | THROW_POT_ACTION_RESTORE
                    | THROW_POT_ACTION_FINISHED,
                slot,
            )
        }
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_isThrowPotActiveNative(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    let machine = THROW_POT_STATE.get_or_init(|| Mutex::new(ThrowPotState::new()));
    machine
        .lock()
        .map(|machine| if machine.active { 1 } else { 0 })
        .unwrap_or(0)
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_resetThrowPotNative(
    _env: JNIEnv,
    _class: JClass,
) {
    crate::lifecycle::jni_guarded!();

    let machine = THROW_POT_STATE.get_or_init(|| Mutex::new(ThrowPotState::new()));
    if let Ok(mut machine) = machine.lock() {
        machine.reset();
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_initializeEquipoNative(
    _env: JNIEnv,
    _class: JClass,
) {
    crate::lifecycle::jni_guarded!();

    let state = EQUIPO_STATE.get_or_init(|| Mutex::new(load_equipo()));
    if let Ok(mut state) = state.lock() {
        *state = load_equipo();
    }
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_equipoContainsNative(
    _env: JNIEnv,
    _class: JClass,
    most: jlong,
    least: jlong,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    equipo_contains(most, least) as jboolean
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_equipoAddNative(
    _env: JNIEnv,
    _class: JClass,
    most: jlong,
    least: jlong,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    let state = EQUIPO_STATE.get_or_init(|| Mutex::new(load_equipo()));
    let Ok(mut state) = state.lock() else {
        return 0;
    };
    let added = !state
        .iter()
        .any(|member| member.most == most && member.least == least);
    if added {
        state.push(TeamMember { most, least });
        save_equipo(&state);
    }
    added as jboolean
}

pub extern "system" fn Java_perfectpathc_dev_nativecore_NativeBridge_equipoRemoveNative(
    _env: JNIEnv,
    _class: JClass,
    most: jlong,
    least: jlong,
) -> jboolean {
    crate::lifecycle::jni_guarded!(0);

    let state = EQUIPO_STATE.get_or_init(|| Mutex::new(load_equipo()));
    let Ok(mut state) = state.lock() else {
        return 0;
    };
    let count = state.len();
    state.retain(|member| member.most != most || member.least != least);
    let removed = state.len() != count;
    if removed {
        save_equipo(&state);
    }
    removed as jboolean
}

fn equipo_contains(most: jlong, least: jlong) -> bool {
    let state = EQUIPO_STATE.get_or_init(|| Mutex::new(load_equipo()));
    state
        .lock()
        .map(|state| {
            state
                .iter()
                .any(|member| member.most == most && member.least == least)
        })
        .unwrap_or(false)
}

fn equipo_path() -> std::path::PathBuf {
    std::path::PathBuf::from("config/perfectpatch/equipo.txt")
}

fn load_equipo() -> Vec<TeamMember> {
    let Ok(contents) = std::fs::read_to_string(equipo_path()) else {
        return Vec::new();
    };
    contents
        .lines()
        .filter_map(|line| {
            let mut fields = line.splitn(3, '\t');
            let first = fields.next()?;
            let second = fields.next()?;
            if let (Ok(most), Ok(least)) = (first.parse(), second.parse()) {
                return Some(TeamMember { most, least });
            }
            // Also accept the previous Java UUID<TAB>name format.
            let bits = u128::from_str_radix(&first.replace('-', ""), 16).ok()?;
            Some(TeamMember {
                most: (bits >> 64) as u64 as i64,
                least: bits as u64 as i64,
            })
        })
        .collect()
}

fn save_equipo(members: &[TeamMember]) {
    let path = equipo_path();
    if let Some(parent) = path.parent() {
        if std::fs::create_dir_all(parent).is_err() {
            return;
        }
    }
    let contents = members
        .iter()
        .map(|member| format!("{}\t{}", member.most, member.least))
        .collect::<Vec<_>>()
        .join("\n");
    let _ = std::fs::write(path, contents);
}

fn next_u32(state: &mut u64) -> u32 {
    let mut value = *state;
    if value == 0 {
        value = 0x9E37_79B9_7F4A_7C15;
    }
    value ^= value << 13;
    value ^= value >> 7;
    value ^= value << 17;
    *state = value;
    (value >> 32) as u32
}

fn sample_throw_delay(state: &mut u64, center: f64) -> i64 {
    // Same bounded log-normal distribution as the Java implementation.
    let sigma = 0.12_f64;
    let mu = center.ln() - sigma * sigma * 0.5;
    for _ in 0..16 {
        let first = (unit_random(state) as f64).max(f64::MIN_POSITIVE);
        let second = unit_random(state) as f64;
        let gaussian = (-2.0 * first.ln()).sqrt() * (2.0 * std::f64::consts::PI * second).cos();
        let sample = (mu + sigma * gaussian).exp().round() as i64;
        if (100..=300).contains(&sample) {
            return sample;
        }
    }
    center.round() as i64
}

fn choose_throw_detour_slot(
    noise: &Perlin1d,
    sequence: u64,
    initial_slot: jint,
    potion_slot: jint,
) -> i32 {
    let start = (noise.sample(sequence as f64 * 0.23).abs() * 9.0).floor() as i32 % 9;
    for offset in 0..9 {
        let candidate = (start + offset) % 9;
        if candidate != initial_slot && candidate != potion_slot {
            return candidate;
        }
    }
    -1
}

fn detour_delay(noise: &Perlin1d, sequence: u64, total_delay: i64) -> i64 {
    let noise = (noise.sample(sequence as f64 * 0.31 + 41.0) + 1.0) * 0.5;
    let ratio = 0.12 + noise * 0.13;
    (total_delay as f64 * ratio)
        .round()
        .max(20.0)
        .min((total_delay - 20).max(20) as f64) as i64
}

fn milliseconds_to_ticks(milliseconds: i64) -> f64 {
    (milliseconds as f64 / 50.0).max(1.0)
}

fn pack_throw_action(flags: i64, slot: i32) -> jlong {
    flags | ((slot as i64 & 0xFF) << 8)
}

fn unit_random(state: &mut u64) -> f32 {
    next_u32(state) as f32 / u32::MAX as f32
}

fn fast_floor(value: f64) -> i32 {
    let integer = value as i32;
    if value < integer as f64 {
        integer - 1
    } else {
        integer
    }
}

fn fade(value: f64) -> f64 {
    value * value * value * (value * (value * 6.0 - 15.0) + 10.0)
}

fn gradient(hash: u8, distance: f64) -> f64 {
    if hash & 1 == 0 {
        distance
    } else {
        -distance
    }
}

fn lerp(first: f64, second: f64, amount: f64) -> f64 {
    first + amount * (second - first)
}

fn schedule_normal(state: &mut LeftClickState, minimum: f64, maximum: f64, now: i64) {
    let cps = minimum + (maximum - minimum) * unit_random(&mut state.random) as f64;
    let delay = (1000.0 / cps.max(0.5)) as i64;
    let jitter =
        (next_u32(&mut state.random) % 15) as i64 - (next_u32(&mut state.random) % 15) as i64;
    state.release_ms = now + delay / 2 + jitter;
    state.next_press_ms = now + delay;
}

fn organic_interval(state: &mut LeftClickState, minimum: f64, maximum: f64) -> i64 {
    state.trend = (state.trend * 0.85 + (unit_random(&mut state.random) as f64 - 0.5) * 0.4)
        .max(-1.0)
        .min(1.0);
    let mut cps = (minimum + maximum) * 0.5
        + state.trend
        + (unit_random(&mut state.random) as f64 - 0.5) * 2.0;
    if next_u32(&mut state.random) % 100 < 3 {
        cps += 1.0 + unit_random(&mut state.random) as f64 * 2.0;
    }
    if next_u32(&mut state.random) % 100 < 4 {
        cps -= 1.0 + unit_random(&mut state.random) as f64 * 2.0;
    }
    cps = cps.max(minimum - 0.3).min(maximum + 0.3).max(0.5);
    (1_000_000_000.0 / cps) as i64
}

fn organic_hold(state: &mut LeftClickState, interval: i64) -> i64 {
    let cps = 1_000_000_000.0 / interval as f64;
    let mut hold =
        35.0 * (1.0 - cps / 25.0).max(0.8) + (unit_random(&mut state.random) as f64 - 0.5) * 16.0;
    if next_u32(&mut state.random) % 100 < 3 {
        hold += 15.0 + unit_random(&mut state.random) as f64 * 25.0;
    }
    hold = hold.max(15.0).min(75.0);
    ((hold * 1_000_000.0) as i64).min((interval as f64 * 0.85) as i64)
}

fn entity_distance(
    player_x: f32,
    player_eye_y: f32,
    player_z: f32,
    entity_x: f32,
    entity_y: f32,
    entity_z: f32,
) -> f32 {
    let dx = entity_x - player_x;
    let dy = entity_y - player_eye_y;
    let dz = entity_z - player_z;
    (dx * dx + dy * dy + dz * dz).sqrt()
}

fn required_yaw(
    candidate: &[f32],
    player_x: f32,
    player_z: f32,
    player_yaw: f32,
    change_x: f64,
    change_z: f64,
) -> f32 {
    let target_x = (candidate[3] + candidate[4]) as f64 * 0.5 + change_x;
    let target_z = (candidate[5] + candidate[6]) as f64 * 0.5 + change_z;
    let dx = target_x - player_x as f64;
    let dz = target_z - player_z as f64;
    let yaw = (dz.atan2(dx).to_degrees() as f32 - 90.0 + 540.0) % 360.0 - 180.0;
    angle_difference(yaw, player_yaw)
}

fn angle_difference(first: f32, second: f32) -> f32 {
    ((first - second) % 360.0 + 540.0) % 360.0 - 180.0
}
