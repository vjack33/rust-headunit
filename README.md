# Rust Headunit

A high-performance, pure native **Rust Android Auto Receiver** (`libheadunit_core.so`) for Android devices, featuring direct zero-copy Qualcomm Venus hardware H.264 video decoding, low-latency audio via AAudio, dual TLS 1.2 / TLS 1.3 encryption, and wireless Wi-Fi Direct projection.

Engineered and aligned directly with the specification from [`andreknieriem/open-headunit`](https://github.com/andreknieriem/open-headunit) (powers Headunit Revived).

---

## Key Features

- **Pure Native Rust Core**: Core AAP protocol stack, session state machine, and media demuxing written in Rust with minimal memory footprint (~50MB RSS) and near-zero CPU usage.
- **Hardware H.264 Video Decoding**: Binds Android NDK `AMediaCodec` directly to `ANativeWindow` for zero-copy hardware decoding (tested on Qualcomm MSM8916 Venus decoder at 720p 30/60 FPS).
- **Zero-Copy Multi-Fragment Video Reassembler**: Wire-level reassembler supporting AAP `FLAG_FIRST`, `FLAG_MIDDLE`, and `FLAG_LAST` fragments, including the 4-byte big-endian `declared_total` length prefix.
- **Dual TLS 1.2 & TLS 1.3 Handshake**: Built on `rustls` 0.23 with `ring` crypto provider. Includes embedded Kenwood X.509 v3 certificates and custom server verifier for compatibility across modern (One UI 7 / Android 15) and legacy Android devices.
- **Exact Open-Headunit Channel Mapping**:
  - `ID_CTR = 0`: Control (Service Discovery, Ping, ByeBye, Audio Focus)
  - `ID_SEN = 1`: Sensors (Announces Driving Status `13` & Night `10`)
  - `ID_VID = 2`: Video (720p Baseline Profile H.264)
  - `ID_INP = 3`: Input (Touchscreen & Keycode bindings)
  - `ID_AU1 = 4`: Speech / Guidance Audio (16 kHz mono)
  - `ID_AU2 = 5`: System Audio (16 kHz mono)
  - `ID_AUD = 6`: Media Audio (48 kHz stereo)
- **Motorcycle Profile (Microphone Bypass)**: Declares `vehicle_type = 3` (Motorcycle) in `HeadUnitInfo`. Android Auto uses the phone's internal microphone for Google Assistant, preventing setup termination on devices without dedicated car microphones.
- **Flow Control & Media ACKs**: Real-time emission of `MEDIA_MESSAGE_ACK` (`0x8004`) acknowledging completed access units, preventing phone transmission window stalls.
- **Wireless Projection & Bluetooth Triggering**: Full RFCOMM trigger server (`4de17a00-52cb-11e6-bdf4-0800200c9a66`) initiating Wi-Fi Direct socket handoffs to port `5277`.

---

## Project Structure

```text
rust_headunit/
├── rust/
│   ├── Cargo.toml            # Rust crate manifest (cdylib, rustls + ring)
│   ├── .cargo/config.toml    # NDK clang linker configuration for armv7
│   └── src/
│       ├── lib.rs            # Main engine, TCP server, JNI exports, AAP state machine
│       ├── protocol.rs       # AAP wire framing, multi-fragment reassembler, protobuf encoders
│       ├── decoder.rs        # Qualcomm Venus AMediaCodec hardware video decoder
│       ├── audio.rs          # AAudio low-latency PCM audio player
│       ├── cert.rs           # Embedded X.509 v3 Kenwood certificate & RSA private key
│       └── verifier.rs       # Custom ServerCertVerifier for Android Auto
├── src/com/openheadunit/rust/
│   ├── MainActivity.java     # Fullscreen immersive SurfaceView & Bluetooth RFCOMM trigger
│   └── NativeCore.java       # JNI declarations for Rust native runtime
├── AndroidManifest.xml       # Permissions (Bluetooth, Wi-Fi Direct, Audio, Location)
├── build_and_deploy.sh       # Automated build & deployment script (Cargo + D8 + AAPT2)
└── test_handshake_full.py    # Python simulation test harness for AAP wire handshake
```

---

## Prerequisites

1. **Android NDK**: r26 or newer (tested with NDK `26.3.11579264`).
2. **Rust Toolchain**: `rustup target add armv7-linux-androideabi`.
3. **Android SDK Build Tools**: `aapt2`, `d8`, `zipalign`, `apksigner`.
4. **Target Device**: Any 32-bit `armeabi-v7a` or 64-bit `aarch64` Android device connected via ADB.

---

## Build & Deploy

Run the automated build script:

```bash
./build_and_deploy.sh
```

The script will:
1. Compile the Rust crate in release mode targeting `armv7-linux-androideabi`.
2. Compile Java sources with `javac --release 17`.
3. Dex bytecode using Android `d8`.
4. Compile resources and package the APK using `aapt2`.
5. Align and sign the APK with `debug.keystore`.
6. Stream install and launch `com.openheadunit.rust` on the target device via ADB.

---

## Test & Verification

To verify the AAP protocol and hardware decoder pipeline locally via ADB:

```bash
adb forward tcp:5277 tcp:5277
python3 test_handshake_full.py
```

---

## License

GPL-3.0 / Apache-2.0

