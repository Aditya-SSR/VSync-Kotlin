# V-Sync

> **🚧 Work in progress.** This project is still under development. Features are incomplete, things may break or change without notice, and it is not ready for production use.

A VTOP companion for VIT-AP students. Native Android app: **Kotlin + Jetpack Compose** on top of a **Rust** scraper.

## Layout

```
app/          Android app (Kotlin, Compose). Package com.minimallabs.vsync
  src/main/kotlin/com/minimallabs/vsync/
    bridge/   Native.kt: the JNI calls into Rust, VtopClient, VtopError
    core/     App state, VTOP session (single-flight login, OTP), storage, errors, files, notifications
    data/     Models (match the Rust JSON) and on-device storage
    feature/  One folder per screen group (auth, timetable, academics, calendar, skip, gpa, outing, ...)
    ui/       Theme, shared widgets, pickers
  src/test/   JVM unit tests (skip planner, session/OTP, parsing helpers, demo dataset)
rust/         lib_vtop: the VTOP scraper (HTTP + HTML parsers). Plain Rust, no app framework.
rust-bridge/  vsync_bridge: a thin JNI layer over lib_vtop, built into libvsync_bridge.so
build-rust.sh Builds the .so for Android into app/src/main/jniLibs
```

## How Kotlin talks to Rust

`rust-bridge` exposes three JNI functions to `bridge/Native.kt`:

- `newClient(username, password, userAgent)` → a handle to one VTOP session held in Rust
- `call(handle, method, argsJson, payload)` → runs one scraper call by name (`"attendance"`, `"login_otp"`, ...)
- `freeClient(handle)`

Replies are bytes: first byte `0` = UTF-8 text/JSON, `1` = raw file bytes, `2` = an error as JSON
(`type`, `message`, `debug`). Scraper panics are caught and returned as errors. Calls run on one shared
Tokio runtime; a per-session lock keeps one request at a time on a session. Kotlin decodes the JSON
with kotlinx.serialization.

Only `core/Session.kt` may log in: VTOP emails an OTP for every credentials POST from an unknown network,
so concurrent callers join the login already running.

## Look

Minimal, iOS-inspired, monochrome first (dark by default). Instrument Sans for headings and text,
Inter for small details. Liquid glass comes from [Kyant's Backdrop](https://github.com/Kyant0/AndroidLiquidGlass)
(`io.github.kyant0:backdrop`); it needs Android 12+ and can be turned off in Account > Appearance.
`ui/Glass.kt` is the only place that decides glass versus a plain surface.

## Build

Needs the Android SDK (compileSdk 37) + NDK (29.x), Rust with the Android targets, and JDK 17+.
Toolchain: Android Gradle Plugin 9.2, Gradle 9.4, Kotlin 2.4, Compose BOM 2026.09.

```bash
rustup target add aarch64-linux-android x86_64-linux-android armv7-linux-androideabi
./build-rust.sh            # arm64 phones (./build-rust.sh all for every ABI, x86_64 for the emulator)
./gradlew :app:assembleDebug
```

Tests:

```bash
./gradlew :app:testDebugUnitTest
cargo test --manifest-path rust/Cargo.toml --lib --tests --no-fail-fast   # not plain `cargo test`: doctests hit live VTOP
```

`rust/tests/calendar_fixture_test.rs` needs two captured HTML pages that are not in the repo and fails without them.

The debug build installs as `com.minimallabs.vsync.dev`, beside the Play release.

## Demo account

Registration number `25VSYNC`, password `dgklynx`: the whole app runs from `app/src/main/assets/demo/` without contacting VTOP.

## Licence

The scraper (`rust/`) is Apache-2.0, see `rust/LICENSE`.
