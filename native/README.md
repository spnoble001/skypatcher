# PerfectPatch native core

The native core deliberately exposes only batch-oriented numerical routines.
Forge callbacks and Minecraft objects stay in Java. This includes ThrowPot:
its state machine, tick credit, bounded log-normal delay, detour decision,
slots and post-throw timing live in Rust. Java is a stateless Forge adapter
which applies the compact native action as a slot change, packet or use-key
event on the client thread.

Build the host library with:

```sh
cargo build --release
```

The generated library must be copied to one of these resource paths before it
can be loaded from a packaged JAR:

```text
natives/windows/x86_64/perfectpatch_native.dll
natives/linux/x86_64/libperfectpatch_native.so
```

`NativeBridge` falls back to its Java implementation when no compatible native
library is packaged.
