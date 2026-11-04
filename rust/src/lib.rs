#![allow(non_snake_case, dead_code)]
use std::ffi::{c_char, c_int, c_void, CString};
use std::io::Cursor;
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use rustls::pki_types::pem::PemObject;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, ServerName};
use rustls::{ClientConfig, ClientConnection};

mod audio;
mod cert;
mod decoder;
mod protocol;
mod verifier;

use audio::NativeAudioSink;
use decoder::HardwareVideoDecoder;
use protocol::*;
use verifier::AndroidAutoServerVerifier;

#[repr(C)]
struct ANativeWindow_Buffer {
    width: i32,
    height: i32,
    stride: i32,
    format: i32,
    bits: *mut c_void,
    reserved: [u32; 6],
}

extern "C" {
    fn __android_log_print(prio: c_int, tag: *const c_char, fmt: *const c_char, ...) -> c_int;
    fn ANativeWindow_fromSurface(env: *mut c_void, surface: *mut c_void) -> *mut c_void;
    fn ANativeWindow_release(window: *mut c_void);
    fn ANativeWindow_setBuffersGeometry(window: *mut c_void, width: i32, height: i32, format: i32) -> i32;
    fn ANativeWindow_lock(window: *mut c_void, outBuffer: *mut ANativeWindow_Buffer, inOutDirtyBounds: *mut c_void) -> i32;
    fn ANativeWindow_unlockAndPost(window: *mut c_void) -> i32;
    fn setpriority(which: c_int, who: c_int, prio: c_int) -> c_int;
    fn setsockopt(sockfd: c_int, level: c_int, optname: c_int, optval: *const c_void, optlen: u32) -> c_int;
}

static IS_RUNNING: AtomicBool = AtomicBool::new(false);
static IS_PROJECTING: AtomicBool = AtomicBool::new(false);
static SESSION_STATE: AtomicI32 = AtomicI32::new(0);
pub static RENDERED_FRAMES: AtomicU64 = AtomicU64::new(0);

struct ActiveSession {
    write_stream: TcpStream,
    tls: Arc<Mutex<Option<ClientConnection>>>,
}

static ACTIVE_SESSION: Mutex<Option<ActiveSession>> = Mutex::new(None);

fn log_info(msg: &str) {
    let tag = CString::new("RustHeadunit").unwrap();
    let c_msg = CString::new(msg).unwrap();
    unsafe {
        __android_log_print(4, tag.as_ptr(), c_msg.as_ptr());
    }
}

fn log_err(msg: &str) {
    let tag = CString::new("RustHeadunit").unwrap();
    let c_msg = CString::new(msg).unwrap();
    unsafe {
        __android_log_print(6, tag.as_ptr(), c_msg.as_ptr());
    }
}

#[inline(always)]
fn rgba(r: u8, g: u8, b: u8, a: u8) -> u32 {
    (a as u32) << 24 | (b as u32) << 16 | (g as u32) << 8 | (r as u32)
}

#[derive(Debug)]
struct CustomClientCertResolver(Arc<rustls::sign::CertifiedKey>);

impl rustls::client::ResolvesClientCert for CustomClientCertResolver {
    fn resolve(
        &self,
        _acceptable_issuers: &[&[u8]],
        _sigschemes: &[rustls::SignatureScheme],
    ) -> Option<Arc<rustls::sign::CertifiedKey>> {
        Some(self.0.clone())
    }
    fn has_certs(&self) -> bool {
        true
    }
}

fn create_tls_client() -> Result<ClientConnection, Box<dyn std::error::Error>> {
    let cert_der = CertificateDer::from_pem_slice(cert::CERTIFICATE.as_bytes())?;
    let key_der = PrivateKeyDer::from_pem_slice(cert::PRIVATE_KEY.as_bytes())?;

    let provider = rustls::crypto::ring::default_provider();
    let signing_key = provider.key_provider.load_private_key(key_der)?;

    let certified_key = Arc::new(rustls::sign::CertifiedKey {
        cert: vec![cert_der],
        key: signing_key,
        ocsp: None,
    });

    let config = ClientConfig::builder_with_provider(Arc::new(provider))
        .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(AndroidAutoServerVerifier))
        .with_client_cert_resolver(Arc::new(CustomClientCertResolver(certified_key)));

    let server_name = ServerName::try_from("androidauto")?;
    let conn = ClientConnection::new(Arc::new(config), server_name)?;
    Ok(conn)
}

#[no_mangle]
pub extern "system" fn Java_com_openheadunit_rust_NativeCore_init(
    _env: *mut c_void,
    _class: *mut c_void,
) -> c_int {
    log_info("=================================================");
    log_info("Rust Native Headunit Core (open-headunit engine)");
    log_info("Target: armv7-linux-androideabi (Snapdragon 410)");
    log_info("Architecture: Pure native Rust AAP protocol stack");
    log_info("=================================================");
    1
}

#[no_mangle]
pub extern "system" fn Java_com_openheadunit_rust_NativeCore_sendTouch(
    _env: *mut c_void,
    _class: *mut c_void,
    action: c_int,
    x: c_int,
    y: c_int,
) {
    if let Ok(mut guard) = ACTIVE_SESSION.lock() {
        if let Some(session) = guard.as_mut() {
            let ts = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_micros() as u64;
            let touch_payload = build_touch_event_indication(action as u32, x as u32, y as u32, ts);
            let _ = write_frame(&mut session.write_stream, &session.tls, ID_INP, FLAG_SINGLE, &touch_payload, true);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_openheadunit_rust_NativeCore_isProjecting(
    _env: *mut c_void,
    _class: *mut c_void,
) -> bool {
    IS_PROJECTING.load(Ordering::SeqCst)
}

#[no_mangle]
pub extern "system" fn Java_com_openheadunit_rust_NativeCore_getSessionState(
    _env: *mut c_void,
    _class: *mut c_void,
) -> c_int {
    SESSION_STATE.load(Ordering::SeqCst)
}

#[no_mangle]
pub extern "system" fn Java_com_openheadunit_rust_NativeCore_getRenderedFrames(
    _env: *mut c_void,
    _class: *mut c_void,
) -> i64 {
    RENDERED_FRAMES.load(Ordering::Relaxed) as i64
}

#[no_mangle]
pub extern "system" fn Java_com_openheadunit_rust_NativeCore_startEngine(
    env: *mut c_void,
    _class: *mut c_void,
    surface: *mut c_void,
    port: c_int,
) {
    if surface.is_null() {
        log_err("Surface is null! Cannot initialize ANativeWindow");
        return;
    }

    unsafe {
        let native_window = ANativeWindow_fromSurface(env, surface);
        if native_window.is_null() {
            log_err("Failed to get ANativeWindow from Surface");
            return;
        }

        log_info("ANativeWindow acquired from Surface for Qualcomm Video Decoder");

        IS_RUNNING.store(true, Ordering::SeqCst);
        IS_PROJECTING.store(false, Ordering::SeqCst);

        let window_ptr = native_window as usize;
        let tcp_port = port as u16;

        // Spawn Main Headunit AAP Engine Thread
        thread::spawn(move || {
            let window = window_ptr as *mut c_void;
            let addr = format!("0.0.0.0:{}", tcp_port);
            let listener = match TcpListener::bind(&addr) {
                Ok(l) => {
                    log_info(&format!("[AAP Server] Listening for phone connection on {}", addr));
                    l.set_nonblocking(true).ok();
                    l
                }
                Err(e) => {
                    log_err(&format!("[AAP Server] Failed to bind to {}: {}", addr, e));
                    ANativeWindow_release(window);
                    return;
                }
            };

            // Audio sink for media playback (48kHz stereo)
            let mut audio_sink = NativeAudioSink::new(48000, 2);

            while IS_RUNNING.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, client_addr)) => {
                        let local_p = stream.local_addr().map(|a| a.port()).unwrap_or(0);
                        log_info(&format!("[AAP Server] Phone connected from: {} (local port: {})", client_addr, local_p));
                        stream.set_nodelay(true).ok();
                        stream.set_read_timeout(Some(Duration::from_secs(5))).ok();

                        // Optimize TCP receive buffer to 1MB and boost thread priority to realtime (-10)
                        use std::os::unix::io::AsRawFd;
                        let fd = stream.as_raw_fd();
                        let rcv_buf: c_int = 1024 * 1024;
                        setsockopt(fd, 1, 8, &rcv_buf as *const _ as *const c_void, std::mem::size_of_val(&rcv_buf) as u32);
                        setpriority(0, 0, -10);

                        // Instantiate hardware video decoder bound to native window
                        let mut decoder = HardwareVideoDecoder::new(window);
                        SESSION_STATE.store(1, Ordering::SeqCst); // TCP Connected
                        IS_PROJECTING.store(false, Ordering::SeqCst);

                        handle_aap_session(&mut stream, &mut decoder, &mut audio_sink);

                        SESSION_STATE.store(5, Ordering::SeqCst); // Disconnected
                        IS_PROJECTING.store(false, Ordering::SeqCst);
                        log_info("[AAP Server] Phone session ended.");
                    }
                    Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(50));
                    }
                    Err(e) => {
                        log_err(&format!("[AAP Server] Accept error: {}", e));
                        break;
                    }
                }
            }

            ANativeWindow_release(window);
            log_info("[AAP Server] Server stopped.");
        });
    }
}

fn handle_aap_session(
    stream: &mut TcpStream,
    decoder: &mut Option<HardwareVideoDecoder>,
    audio_sink: &mut Option<NativeAudioSink>,
) {
    log_info("[AAP Session] Starting Session Handler...");

    let mut write_stream = match stream.try_clone() {
        Ok(s) => s,
        Err(e) => {
            log_err(&format!("[AAP Session] try_clone failed: {}", e));
            return;
        }
    };
    let session_tls: Arc<Mutex<Option<ClientConnection>>> = Arc::new(Mutex::new(None));

    // Check if phone sent any initial bytes (peek non-blocking)
    stream.set_nonblocking(true).ok();
    let mut peek_buf = [0u8; 64];
    match stream.peek(&mut peek_buf) {
        Ok(n) if n > 0 => {
            log_info(&format!(
                "[AAP Session] Phone sent {} initial bytes before VersionRequest: {:02X?}",
                n, &peek_buf[..n]
            ));
        }
        _ => {}
    }
    stream.set_nonblocking(false).ok();
    stream.set_read_timeout(Some(Duration::from_secs(10))).ok();

    // 1. Send VERSION_REQUEST with retries matching open-headunit
    let ver_req = [0x00, 0x01, 0x00, 0x01, 0x00, 0x02];
    let mut ver_resp = None;

    for attempt in 1..=4 {
        log_info(&format!("[AAP Session] Sending Version Request (1.2) [attempt {}/4]...", attempt));
        if let Err(e) = write_frame(&mut write_stream, &session_tls, ID_CTR, FLAG_SINGLE, &ver_req, false) {
            log_err(&format!("[AAP Session] Failed to send VersionRequest: {}", e));
            return;
        }

        let deadline = std::time::Instant::now() + Duration::from_millis(2000);
        while std::time::Instant::now() < deadline {
            match read_frame(stream, &session_tls) {
                Ok(f) => {
                    log_info(&format!(
                        "[AAP Session] Received frame: chan={}, flags=0x{:02X}, len={}",
                        f.channel, f.flags, f.payload.len()
                    ));
                    if f.channel == ID_CTR && f.payload.len() >= 6 {
                        let msg_type = u16::from_be_bytes([f.payload[0], f.payload[1]]);
                        if msg_type == 0x0002 {
                            ver_resp = Some(f);
                            break;
                        }
                    }
                    log_info("[AAP Session] Ignoring unexpected message while waiting for VERSION_RESPONSE");
                }
                Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock || e.kind() == std::io::ErrorKind::TimedOut => {
                    log_info("[AAP Session] Read timeout waiting for VERSION_RESPONSE, will retry...");
                    break;
                }
                Err(ref e) if e.kind() == std::io::ErrorKind::UnexpectedEof => {
                    log_err("[AAP Session] Phone closed TCP socket during version handshake");
                    return;
                }
                Err(e) => {
                    log_err(&format!("[AAP Session] Read error: {}", e));
                    return;
                }
            }
        }

        if ver_resp.is_some() {
            break;
        }
        thread::sleep(Duration::from_millis(200));
    }

    let ver_resp = match ver_resp {
        Some(f) => f,
        None => {
            log_err("[AAP Session] Version exchange failed after 4 attempts.");
            return;
        }
    };

    if ver_resp.payload.len() >= 6 {
        let msg_type = u16::from_be_bytes([ver_resp.payload[0], ver_resp.payload[1]]);
        let major = u16::from_be_bytes([ver_resp.payload[2], ver_resp.payload[3]]);
        let minor = u16::from_be_bytes([ver_resp.payload[4], ver_resp.payload[5]]);
        log_info(&format!(
            "[AAP Session] Phone AAP version negotiated: type=0x{:04X}, version={}.{}",
            msg_type, major, minor
        ));
    }

    // 3. Setup TLS Client
    let mut tls_client = match create_tls_client() {
        Ok(c) => c,
        Err(e) => {
            log_err(&format!("[AAP Session] Failed to initialize TLS client: {}", e));
            return;
        }
    };

    log_info("[AAP Session] Beginning TLS Handshake...");
    SESSION_STATE.store(2, Ordering::SeqCst); // TLS Handshake

    // Send initial TLS ClientHello
    let mut client_hello = Vec::new();
    if let Err(e) = tls_client.write_tls(&mut client_hello) {
        log_err(&format!("[AAP Session] write_tls failed: {}", e));
        return;
    }

    let mut ssl_frame_data = vec![0x00, 0x03]; // MESSAGE_ENCAPSULATED_SSL
    ssl_frame_data.extend(client_hello);
    if let Err(e) = write_frame(&mut write_stream, &session_tls, ID_CTR, FLAG_SINGLE, &ssl_frame_data, false) {
        log_err(&format!("[AAP Session] Failed to send ClientHello: {}", e));
        return;
    }

    // Loop through SSL Handshake
    let mut hs_completed = false;
    for step in 0..20 {
        match read_frame(stream, &session_tls) {
            Ok(f) => {
                log_info(&format!(
                    "[AAP Session] TLS step {}: received frame chan={}, flags=0x{:02X}, len={}",
                    step, f.channel, f.flags, f.payload.len()
                ));
                if f.channel == ID_CTR && f.payload.len() >= 2 && f.payload[0] == 0x00 && f.payload[1] == 0x03 {
                    let mut cursor = Cursor::new(&f.payload[2..]);
                    if let Err(e) = tls_client.read_tls(&mut cursor) {
                        log_err(&format!("[AAP Session] TLS read error: {}", e));
                        break;
                    }
                    if let Err(e) = tls_client.process_new_packets() {
                        log_err(&format!("[AAP Session] TLS process error: {}", e));
                        break;
                    }

                    let mut next_data = Vec::new();
                    while let Ok(n) = tls_client.write_tls(&mut next_data) {
                        if n == 0 { break; }
                    }
                    if !next_data.is_empty() {
                        let mut next_frame = vec![0x00, 0x03];
                        next_frame.extend(next_data);
                        let _ = write_frame(&mut write_stream, &session_tls, ID_CTR, FLAG_SINGLE, &next_frame, false);
                    }

                    if !tls_client.is_handshaking() {
                        hs_completed = true;
                        log_info(&format!(
                            "[AAP Session] TLS Handshake Successful! (version={:?}, cipher_suite={:?})",
                            tls_client.protocol_version(),
                            tls_client.negotiated_cipher_suite()
                        ));
                        break;
                    }
                }
            }
            Err(e) => {
                log_err(&format!("[AAP Session] Error reading during handshake: {}", e));
                break;
            }
        }
    }

    if !hs_completed {
        log_err("[AAP Session] TLS Handshake did not complete in time.");
        return;
    }

    // 4. Send AUTH_COMPLETE (Plaintext AAP frame, unencrypted! As specified in open-headunit AapTlsWriter)
    log_info("[AAP Session] Sending AuthCompleteIndication (Plaintext STATUS_OK)...");
    let auth_complete = [0x00, 0x04, 0x08, 0x00]; // type=4, status=STATUS_SUCCESS (0)
    if let Err(e) = write_frame(&mut write_stream, &session_tls, ID_CTR, FLAG_SINGLE, &auth_complete, false) {
        log_err(&format!("[AAP Session] Failed to send AuthComplete: {}", e));
        return;
    }

    // Activate TLS for subsequent encrypted frames
    *session_tls.lock().unwrap() = Some(tls_client);

    // Register active session for instantaneous touch event forwarding
    if let Ok(mut guard) = ACTIVE_SESSION.lock() {
        if let Ok(ws) = stream.try_clone() {
            *guard = Some(ActiveSession {
                write_stream: ws,
                tls: Arc::clone(&session_tls),
            });
        }
    }

    // 5. Initialize Protocol State & Zero-Copy Assemblers
    log_info("[AAP Session] Entering Main AAP Service and Media Loop!");
    SESSION_STATE.store(3, Ordering::SeqCst); // Protocol Setup
    // Set 15s read timeout to match open-headunit and prevent partial-read framing desync
    stream.set_read_timeout(Some(Duration::from_secs(15))).ok();
    let mut video_assembler = VideoAssembler::new();
    let mut video_session_id: i32 = 0;
    let mut audio_session_id: i32 = 0;

    while IS_RUNNING.load(Ordering::Relaxed) {
        match read_frame(stream, &session_tls) {
            Ok(frame) => {
                let channel = frame.channel;
                let data = &frame.payload;
                let msg_type = if data.len() >= 2 {
                    u16::from_be_bytes([data[0], data[1]])
                } else {
                    0xFFFF
                };
                if channel != ID_VID && msg_type != 0x000B {
                    log_info(&format!(
                        "[AAP RX] chan={}, flags=0x{:02X}, len={}, msg_type=0x{:04X}",
                        channel, frame.flags, data.len(), msg_type
                    ));
                }

                if msg_type == 0x000B {
                    // PING_REQUEST -> PING_RESPONSE (respond immediately on any channel)
                    let ping_payload = if data.len() >= 2 { &data[2..] } else { &[] };
                    let ping_resp = build_ping_response(ping_payload);
                    let _ = write_frame(&mut write_stream, &session_tls, channel, FLAG_SINGLE, &ping_resp, true);
                    continue;
                }

                if data.is_empty() {
                    // Empty plaintext frame (e.g. TLS post-handshake control record / NewSessionTicket)
                    log_info(&format!("[AAP RX] Ignored empty plaintext frame on channel {}", channel));
                    continue;
                }

                match channel {
                    ID_CTR => {
                        // Channel 0: Control Channel
                        if data.len() >= 2 {
                            let msg_type = u16::from_be_bytes([data[0], data[1]]);
                            match msg_type {
                                0x0005 => {
                                    // SERVICE_DISCOVERY_REQUEST -> SERVICE_DISCOVERY_RESPONSE
                                    log_info("[AAP Session] Got ServiceDiscoveryRequest -> Sending comprehensive ServiceDiscoveryResponse");
                                    let sd_resp = build_service_discovery_response();
                                    let _ = write_frame(&mut write_stream, &session_tls, ID_CTR, FLAG_SINGLE, &sd_resp, true);
                                }
                                0x0007 => {
                                    // CHANNEL_OPEN_REQUEST
                                    let resp = build_channel_open_response();
                                    let _ = write_frame(&mut write_stream, &session_tls, ID_CTR, FLAG_SINGLE, &resp, true);
                                }
                                0x000B => {
                                    // PING_REQUEST -> PING_RESPONSE
                                    let ping_resp = build_ping_response(&data[2..]);
                                    let _ = write_frame(&mut write_stream, &session_tls, ID_CTR, FLAG_SINGLE, &ping_resp, true);
                                }
                                0x000F => {
                                    // BYEBYE_REQUEST -> BYEBYE_RESPONSE & quit
                                    log_info("[AAP Session] ByeByeRequest received from phone. Session terminating cleanly.");
                                    let byebye_resp = [0x00, 0x10]; // MESSAGE_BYEBYE_RESPONSE
                                    let _ = write_frame(&mut write_stream, &session_tls, ID_CTR, FLAG_SINGLE, &byebye_resp, true);
                                    break;
                                }
                                0x0012 => {
                                    // AUDIO_FOCUS_REQUEST -> AUDIO_FOCUS_NOTIFICATION (always grant)
                                    let af_resp = build_audio_focus_notification();
                                    let _ = write_frame(&mut write_stream, &session_tls, ID_CTR, FLAG_SINGLE, &af_resp, true);
                                }
                                _ => {}
                            }
                        }
                    }
                    ID_SEN => {
                        // Channel 1: Sensor Channel (open-headunit Channel.ID_SEN)
                        if data.len() >= 2 {
                            let msg_type = u16::from_be_bytes([data[0], data[1]]);
                            match msg_type {
                                0x0007 => {
                                    // CHANNEL_OPEN_REQUEST
                                    log_info("[AAP Session] Sensor Channel Open Request -> OK (sending OpenResponse & DrivingStatus UNRESTRICTED)");
                                    let resp = build_channel_open_response();
                                    let _ = write_frame(&mut write_stream, &session_tls, ID_SEN, FLAG_SINGLE, &resp, true);
                                    let ds = build_sensor_driving_status();
                                    let _ = write_frame(&mut write_stream, &session_tls, ID_SEN, FLAG_SINGLE, &ds, true);
                                }
                                0x8001 => {
                                    // SENSOR_STARTREQUEST -> SENSOR_STARTRESPONSE + SENSOR_EVENT (driving_status UNRESTRICTED)
                                    log_info("[AAP Session] Sensor Start Request -> Replying SUCCESS & DrivingStatus UNRESTRICTED");
                                    let resp = build_sensor_start_response();
                                    let _ = write_frame(&mut write_stream, &session_tls, ID_SEN, FLAG_SINGLE, &resp, true);
                                    let ds = build_sensor_driving_status();
                                    let _ = write_frame(&mut write_stream, &session_tls, ID_SEN, FLAG_SINGLE, &ds, true);
                                }
                                _ => {}
                            }
                        }
                    }
                    ID_VID => {
                        // Channel 2: Video Channel (open-headunit Channel.ID_VID)
                        let is_first = (frame.flags & FLAG_FIRST) != 0;
                        let is_ctrl = (frame.flags & FLAG_CONTROL != 0)
                            || (is_first && data.len() >= 2 && matches!(u16::from_be_bytes([data[0], data[1]]), 0x0007 | 0x8000 | 0x8001 | 0x8002 | 0x8007));

                        if is_ctrl && data.len() >= 2 {
                            let ctrl_type = u16::from_be_bytes([data[0], data[1]]);
                            match ctrl_type {
                                0x0007 => {
                                    // CHANNEL_OPEN_REQUEST
                                    log_info("[AAP Session] Video Channel Open Request -> OK");
                                    let resp = build_channel_open_response();
                                    let _ = write_frame(&mut write_stream, &session_tls, ID_VID, FLAG_SINGLE, &resp, true);
                                }
                                0x8000 => {
                                    // MEDIA_MESSAGE_SETUP -> MEDIA_MESSAGE_CONFIG & MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION
                                    log_info("[AAP Session] Video Setup Request -> Replying Config READY & Gaining Video Focus");
                                    let config_resp = build_media_config_response(16);
                                    let _ = write_frame(&mut write_stream, &session_tls, ID_VID, FLAG_SINGLE, &config_resp, true);
                                    let focus_notif = build_video_focus_notification();
                                    let _ = write_frame(&mut write_stream, &session_tls, ID_VID, FLAG_SINGLE, &focus_notif, true);
                                }
                                0x8001 => {
                                    // MEDIA_MESSAGE_START
                                    if data.len() >= 4 && data[2] == 0x08 {
                                        video_session_id = data[3] as i32;
                                    }
                                    log_info(&format!("[AAP Session] >>> VIDEO STREAM STARTED (session_id={}) <<<", video_session_id));
                                    SESSION_STATE.store(4, Ordering::SeqCst); // Projecting Live
                                    IS_PROJECTING.store(true, Ordering::SeqCst);
                                }
                                0x8002 => {
                                    // MEDIA_MESSAGE_STOP
                                    log_info("[AAP Session] Video Stream Stopped by phone");
                                }
                                0x8007 => {
                                    // MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST (informational - focus was already granted at setup)
                                    log_info("[AAP Session] Video Focus Request received from phone");
                                }
                                _ => {}
                            }
                        } else {
                            // H.264 Video Stream Fragment (CSD or Media Data Access Unit)
                            if let Some(unit) = video_assembler.on_fragment(frame.flags, &frame.payload) {
                                if let Some(dec) = decoder.as_mut() {
                                    dec.feed_packet(&unit.data, unit.timestamp_us, unit.is_config);
                                }
                                if unit.completes_data {
                                    let ack = build_media_ack(video_session_id);
                                    let _ = write_frame(&mut write_stream, &session_tls, ID_VID, FLAG_SINGLE, &ack, true);
                                }
                            }
                        }
                    }
                    ID_INP => {
                        // Channel 3: Input Channel (open-headunit Channel.ID_INP)
                        if data.len() >= 2 {
                            let msg_type = u16::from_be_bytes([data[0], data[1]]);
                            match msg_type {
                                0x0007 => {
                                    // CHANNEL_OPEN_REQUEST
                                    log_info("[AAP Session] Input Channel Open Request -> OK");
                                    let resp = build_channel_open_response();
                                    let _ = write_frame(&mut write_stream, &session_tls, ID_INP, FLAG_SINGLE, &resp, true);
                                }
                                0x8002 => {
                                    // BINDINGREQUEST -> BINDINGRESPONSE
                                    log_info("[AAP Session] Input KeyBinding Request -> Replying SUCCESS");
                                    let resp = build_input_binding_response();
                                    let _ = write_frame(&mut write_stream, &session_tls, ID_INP, FLAG_SINGLE, &resp, true);
                                }
                                _ => {}
                            }
                        }
                    }
                    ID_AU1 | ID_AU2 | ID_AUD => {
                        // Audio Channels: 4 (Speech), 5 (System), 6 (Media Audio)
                        if data.len() >= 2 {
                            let ctrl_type = u16::from_be_bytes([data[0], data[1]]);
                            match ctrl_type {
                                0x0007 => {
                                    // CHANNEL_OPEN_REQUEST
                                    log_info(&format!("[AAP Session] Audio Channel {} Open Request -> OK", channel));
                                    let resp = build_channel_open_response();
                                    let _ = write_frame(&mut write_stream, &session_tls, channel, FLAG_SINGLE, &resp, true);
                                }
                                0x8000 => {
                                    // MEDIA_MESSAGE_SETUP -> CONFIG & AUDIO_FOCUS_NOTIFICATION
                                    let config_resp = build_media_config_response(30);
                                    let _ = write_frame(&mut write_stream, &session_tls, channel, FLAG_SINGLE, &config_resp, true);
                                    let af_notif = build_audio_focus_notification();
                                    let _ = write_frame(&mut write_stream, &session_tls, ID_CTR, FLAG_SINGLE, &af_notif, true);
                                    log_info(&format!("[AAP Session] Audio Channel {} Setup -> Config READY & Audio Focus GAIN", channel));
                                }
                                0x8001 => {
                                    // MEDIA_MESSAGE_START
                                    if data.len() >= 4 {
                                        audio_session_id = data[3] as i32;
                                    }
                                    log_info(&format!("[AAP Session] Audio Channel {} Started (session_id={})", channel, audio_session_id));
                                }
                                0x0000 => {
                                    // MEDIA_MESSAGE_DATA (Raw PCM samples)
                                    let pcm_offset = if data.len() > 10 { 10 } else { 2 };
                                    if data.len() > pcm_offset {
                                        let pcm_bytes = &data[pcm_offset..];
                                        if let Some(sink) = audio_sink.as_mut() {
                                             sink.write_pcm_i16(pcm_bytes);
                                        }
                                    }
                                    let ack = build_media_ack(audio_session_id);
                                    let _ = write_frame(&mut write_stream, &session_tls, channel, FLAG_SINGLE, &ack, true);
                                }
                                _ => {}
                            }
                        }
                    }
                    _ => {}
                }
            }
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock || e.kind() == std::io::ErrorKind::TimedOut => {
                log_info("[AAP Session] Connection idle timeout (15s)");
                break;
            }
            Err(e) => {
                log_err(&format!("[AAP Session] Client disconnected or read error: {}", e));
                break;
            }
        }
    }

    if let Ok(mut guard) = ACTIVE_SESSION.lock() {
        *guard = None;
    }

    if let Some(dec) = decoder.as_mut() {
        dec.close();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_openheadunit_rust_NativeCore_stopEngine(
    _env: *mut c_void,
    _class: *mut c_void,
) {
    log_info("Stopping Rust Native Headunit Core...");
    IS_RUNNING.store(false, Ordering::SeqCst);
    IS_PROJECTING.store(false, Ordering::SeqCst);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_create_tls_client() {
        assert!(create_tls_client().is_ok());
    }
}
