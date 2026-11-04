#![allow(dead_code)]
use std::io::{Cursor, Read, Write};
use std::net::TcpStream;
use rustls::ClientConnection;

// Channel IDs (Exact match with Andre Knieriem open-headunit Channel.kt)
pub const ID_CTR: u8 = 0;   // Control Channel
pub const ID_SEN: u8 = 1;   // Sensors (Driving Status, Night)
pub const ID_VID: u8 = 2;   // Video (H.264 / H.265)
pub const ID_INP: u8 = 3;   // Input (Touchscreen / Keycodes)
pub const ID_AU1: u8 = 4;   // Audio 1 (Speech / Guidance, 16kHz mono)
pub const ID_AU2: u8 = 5;   // Audio 2 (System Audio, 16kHz mono)
pub const ID_AUD: u8 = 6;   // Audio 0 (Media Audio, 48kHz stereo)
pub const ID_MIC: u8 = 7;   // Microphone

// Framing Flags (Exact match with open-headunit AapMessageFraming.kt)
pub const FLAG_FIRST: u8 = 0x01;
pub const FLAG_LAST: u8 = 0x02;
pub const FLAG_SINGLE: u8 = 0x03; // FLAG_FIRST | FLAG_LAST
pub const FLAG_CONTROL: u8 = 0x04;
pub const FLAG_ENCRYPTED: u8 = 0x08;

#[derive(Debug, Clone)]
pub struct AapFrame {
    pub channel: u8,
    pub flags: u8,
    pub payload: Vec<u8>,
}

use std::sync::{Arc, Mutex};

pub fn read_frame(
    stream: &mut TcpStream,
    tls: &Arc<Mutex<Option<ClientConnection>>>,
) -> std::io::Result<AapFrame> {
    let mut header = [0u8; 4];
    stream.read_exact(&mut header)?;

    let channel = header[0];
    let flags = header[1];
    let len = u16::from_be_bytes([header[2], header[3]]) as usize;

    let mut _declared_total = None;
    if (flags & FLAG_FIRST != 0) && (flags & FLAG_LAST == 0) {
        let mut total_len_buf = [0u8; 4];
        stream.read_exact(&mut total_len_buf)?;
        _declared_total = Some(u32::from_be_bytes(total_len_buf));
    }

    let mut body = vec![0u8; len];
    if len > 0 {
        stream.read_exact(&mut body)?;
    }

    if (flags & FLAG_ENCRYPTED) != 0 {
        let mut guard = tls.lock().unwrap();
        if let Some(ref mut conn) = *guard {
            let mut cursor = Cursor::new(&body);
            let mut plain = Vec::with_capacity(body.len());

            // Must loop read_tls until the entire AAP body (which may exceed a single read_tls buffer chunk) is consumed
            while (cursor.position() as usize) < body.len() {
                let n = conn.read_tls(&mut cursor)?;
                let io_state = match conn.process_new_packets() {
                    Ok(s) => s,
                    Err(e) => {
                        crate::log_err(&format!(
                            "[read_frame] process_new_packets error: {} (hdr={:02X?}, body_len={}, cursor_pos={}, body_preview={:02X?})",
                            e, header, body.len(), cursor.position(), &body[..std::cmp::min(body.len(), 32)]
                        ));
                        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, e));
                    }
                };

                if io_state.plaintext_bytes_to_read() > 0 {
                    let _ = conn.reader().read_to_end(&mut plain);
                }

                if n == 0 {
                    break;
                }
            }

            // Final drain for any remaining decrypted plaintext in rustls
            let io_state = match conn.process_new_packets() {
                Ok(s) => s,
                Err(e) => {
                    crate::log_err(&format!(
                        "[read_frame] final process_new_packets error: {} (hdr={:02X?}, body_len={})",
                        e, header, body.len()
                    ));
                    return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, e));
                }
            };
            if io_state.plaintext_bytes_to_read() > 0 {
                let _ = conn.reader().read_to_end(&mut plain);
            }

            return Ok(AapFrame {
                channel,
                flags: flags & !FLAG_ENCRYPTED,
                payload: plain,
            });
        }
    }

    Ok(AapFrame {
        channel,
        flags,
        payload: body,
    })
}

pub fn write_frame(
    stream: &mut TcpStream,
    tls: &Arc<Mutex<Option<ClientConnection>>>,
    channel: u8,
    mut flags: u8,
    payload: &[u8],
    encrypt: bool,
) -> std::io::Result<()> {
    // AAP Protocol framing rule (matching open-headunit AapMessage.kt):
    // Any control message (type 1..=26) sent on non-zero channels MUST carry FLAG_CONTROL (0x04)
    if channel != ID_CTR && payload.len() >= 2 {
        let msg_type = u16::from_be_bytes([payload[0], payload[1]]);
        if msg_type >= 1 && msg_type <= 26 {
            flags |= FLAG_CONTROL;
        }
    }

    if encrypt {
        let mut guard = tls.lock().unwrap();
        if let Some(ref mut conn) = *guard {
            conn.writer().write_all(payload)?;
            let mut cipher = Vec::new();
            conn.write_tls(&mut cipher)?;

            flags |= FLAG_ENCRYPTED;
            let len = cipher.len() as u16;
            let mut frame_buf = Vec::with_capacity(4 + cipher.len());
            frame_buf.extend_from_slice(&[channel, flags, (len >> 8) as u8, len as u8]);
            frame_buf.extend_from_slice(&cipher);
            stream.write_all(&frame_buf)?;
            stream.flush()?;
            return Ok(());
        }
    }

    let len = payload.len() as u16;
    let mut frame_buf = Vec::with_capacity(4 + payload.len());
    frame_buf.extend_from_slice(&[channel, flags, (len >> 8) as u8, len as u8]);
    if !payload.is_empty() {
        frame_buf.extend_from_slice(payload);
    }
    stream.write_all(&frame_buf)?;
    stream.flush()?;
    Ok(())
}

// Video Access Unit Assembler (Zero-allocation H.264 stream reassembler)
#[derive(Debug)]
pub struct VideoAccessUnit<'a> {
    pub is_config: bool,
    pub data: &'a [u8],
    pub completes_data: bool,
    pub timestamp_us: u64,
}

pub struct VideoAssembler {
    assembling: bool,
    current_type: u16,
    timestamp_us: u64,
    buffer: Vec<u8>,
}

impl VideoAssembler {
    pub fn new() -> Self {
        Self {
            assembling: false,
            current_type: 0,
            timestamp_us: 0,
            buffer: Vec::with_capacity(128 * 1024),
        }
    }

    pub fn on_fragment<'a>(&'a mut self, flags: u8, payload: &[u8]) -> Option<VideoAccessUnit<'a>> {
        let is_first = (flags & FLAG_FIRST) != 0;
        let is_last = (flags & FLAG_LAST) != 0;

        if is_first {
            if payload.len() < 2 {
                return None;
            }
            let msg_type = u16::from_be_bytes([payload[0], payload[1]]);
            self.current_type = msg_type;

            if msg_type == 0 {
                // Media Data: 2 bytes type + 8 bytes timestamp + NAL payload
                if payload.len() < 10 {
                    return None;
                }
                self.timestamp_us = u64::from_be_bytes([
                    payload[2], payload[3], payload[4], payload[5],
                    payload[6], payload[7], payload[8], payload[9],
                ]);
                self.buffer.clear();
                self.buffer.extend_from_slice(&payload[10..]);

                if is_last {
                    self.assembling = false;
                    return Some(VideoAccessUnit {
                        is_config: false,
                        data: &self.buffer,
                        completes_data: true,
                        timestamp_us: self.timestamp_us,
                    });
                } else {
                    self.assembling = true;
                    return None;
                }
            } else if msg_type == 1 {
                // Codec Config / CSD: 2 bytes type + NAL payload (SPS / PPS)
                self.buffer.clear();
                self.buffer.extend_from_slice(&payload[2..]);

                if is_last {
                    self.assembling = false;
                    return Some(VideoAccessUnit {
                        is_config: true,
                        data: &self.buffer,
                        completes_data: false,
                        timestamp_us: 0,
                    });
                } else {
                    self.assembling = true;
                    return None;
                }
            } else {
                return None;
            }
        } else {
            // Continuation fragment (MIDDLE or LAST)
            if !self.assembling {
                return None; // Orphaned fragment
            }
            self.buffer.extend_from_slice(payload);

            if is_last {
                self.assembling = false;
                let is_cfg = self.current_type == 1;
                let completes = self.current_type == 0;
                let ts = self.timestamp_us;
                return Some(VideoAccessUnit {
                    is_config: is_cfg,
                    data: &self.buffer,
                    completes_data: completes,
                    timestamp_us: ts,
                });
            } else {
                return None;
            }
        }
    }
}

// Protobuf Varint and Wire encoding helpers
pub fn encode_varint(mut v: u64, buf: &mut Vec<u8>) {
    while v > 0x7F {
        buf.push(((v & 0x7F) as u8) | 0x80);
        v >>= 7;
    }
    buf.push((v & 0x7F) as u8);
}

pub fn encode_uint32(field: u32, v: u32, buf: &mut Vec<u8>) {
    encode_varint(((field as u64) << 3) | 0, buf);
    encode_varint(v as u64, buf);
}

pub fn encode_bool(field: u32, b: bool, buf: &mut Vec<u8>) {
    encode_uint32(field, if b { 1 } else { 0 }, buf);
}

pub fn encode_string(field: u32, s: &str, buf: &mut Vec<u8>) {
    encode_varint(((field as u64) << 3) | 2, buf);
    encode_varint(s.len() as u64, buf);
    buf.extend_from_slice(s.as_bytes());
}

pub fn encode_submsg(field: u32, msg: &[u8], buf: &mut Vec<u8>) {
    encode_varint(((field as u64) << 3) | 2, buf);
    encode_varint(msg.len() as u64, buf);
    buf.extend_from_slice(msg);
}

// Construct ServiceDiscoveryResponse payload (Matching open-headunit architecture)
pub fn build_service_discovery_response() -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&[0x00, 0x06]); // SERVICE_DISCOVERY_RESPONSE type

    // 1. Channel 1: Sensors (Driving status & Night)
    let mut sensors_sub = Vec::new();
    let mut s1 = Vec::new();
    encode_uint32(1, 13, &mut s1); // DRIVING_STATUS (13)
    encode_submsg(1, &s1, &mut sensors_sub);
    let mut s2 = Vec::new();
    encode_uint32(1, 10, &mut s2); // NIGHT (10)
    encode_submsg(1, &s2, &mut sensors_sub);

    let mut chan_sen = Vec::new();
    encode_uint32(1, ID_SEN as u32, &mut chan_sen);
    encode_submsg(2, &sensors_sub, &mut chan_sen); // sensor_source_service = 2

    // 2. Channel 2: Video (Dynamic Resolution, FPS & DPI)
    let codec_res = crate::CONFIG_CODEC_RES.load(std::sync::atomic::Ordering::SeqCst);
    let density = crate::CONFIG_DENSITY.load(std::sync::atomic::Ordering::SeqCst);
    let width = crate::CONFIG_WIDTH.load(std::sync::atomic::Ordering::SeqCst);
    let height = crate::CONFIG_HEIGHT.load(std::sync::atomic::Ordering::SeqCst);
    let vehicle_type = crate::CONFIG_VEHICLE_TYPE.load(std::sync::atomic::Ordering::SeqCst);
    let target_fps = crate::CONFIG_FPS.load(std::sync::atomic::Ordering::SeqCst);

    let mut vc_primary = Vec::new();
    encode_uint32(1, codec_res, &mut vc_primary); // codec_resolution (1=_800x480, 2=_1280x720, 3=_1920x1080)
    encode_uint32(2, if target_fps == 30 { 2 } else { 1 }, &mut vc_primary); // frame_rate: 1=60fps, 2=30fps
    encode_uint32(3, 0, &mut vc_primary); // margin width = 0
    encode_uint32(4, 0, &mut vc_primary); // margin height = 0
    encode_uint32(5, density, &mut vc_primary); // density
    encode_uint32(8, 10000, &mut vc_primary); // pixel_aspect_ratio_e4 = 10000 (1:1 square)
    encode_uint32(10, 3, &mut vc_primary); // video_codec_type = MEDIA_CODEC_VIDEO_H264_BP (3)

    let mut av_vid = Vec::new();
    encode_uint32(1, 3, &mut av_vid); // available_type = H264_BP (3)
    encode_uint32(2, 0, &mut av_vid); // audio_type = NONE (0)
    encode_submsg(4, &vc_primary, &mut av_vid); // video_configs[0]

    if target_fps != 30 {
        let mut vc30 = Vec::new();
        encode_uint32(1, codec_res, &mut vc30);
        encode_uint32(2, 2, &mut vc30); // 30 FPS fallback
        encode_uint32(3, 0, &mut vc30);
        encode_uint32(4, 0, &mut vc30);
        encode_uint32(5, density, &mut vc30);
        encode_uint32(8, 10000, &mut vc30);
        encode_uint32(10, 3, &mut vc30);
        encode_submsg(4, &vc30, &mut av_vid);
    }
    encode_bool(5, true, &mut av_vid); // available_while_in_call = true

    let mut chan_vid = Vec::new();
    encode_uint32(1, ID_VID as u32, &mut chan_vid);
    encode_submsg(3, &av_vid, &mut chan_vid); // media_sink_service = 3

    // 3. Channel 3: Input (Touchscreen width x height)
    let mut tc = Vec::new();
    encode_uint32(1, width, &mut tc);
    encode_uint32(2, height, &mut tc);

    let mut inp = Vec::new();
    encode_submsg(2, &tc, &mut inp); // touchscreen = 2

    let mut chan_inp = Vec::new();
    encode_uint32(1, ID_INP as u32, &mut chan_inp);
    encode_submsg(4, &inp, &mut chan_inp); // input_source_service = 4

    // 4. Channel 5: System Audio (16000 Hz, 16-bit, 1 ch)
    let mut ac_sys = Vec::new();
    encode_uint32(1, 16000, &mut ac_sys);
    encode_uint32(2, 16, &mut ac_sys);
    encode_uint32(3, 1, &mut ac_sys);

    let mut av_sys = Vec::new();
    encode_uint32(1, 1, &mut av_sys); // PCM (1)
    encode_uint32(2, 2, &mut av_sys); // SYSTEM (2)
    encode_submsg(3, &ac_sys, &mut av_sys);

    let mut chan_sys = Vec::new();
    encode_uint32(1, ID_AU2 as u32, &mut chan_sys);
    encode_submsg(3, &av_sys, &mut chan_sys);

    // 5. Channel 4: Speech Audio (16000 Hz, 16-bit, 1 ch)
    let mut ac_sp = Vec::new();
    encode_uint32(1, 16000, &mut ac_sp);
    encode_uint32(2, 16, &mut ac_sp);
    encode_uint32(3, 1, &mut ac_sp);

    let mut av_sp = Vec::new();
    encode_uint32(1, 1, &mut av_sp); // PCM (1)
    encode_uint32(2, 1, &mut av_sp); // SPEECH (1)
    encode_submsg(3, &ac_sp, &mut av_sp);

    let mut chan_sp = Vec::new();
    encode_uint32(1, ID_AU1 as u32, &mut chan_sp);
    encode_submsg(3, &av_sp, &mut chan_sp);

    // 6. Channel 6: Media Audio (48000 Hz, 16-bit, 2 ch)
    let mut ac_med = Vec::new();
    encode_uint32(1, 48000, &mut ac_med);
    encode_uint32(2, 16, &mut ac_med);
    encode_uint32(3, 2, &mut ac_med);

    let mut av_med = Vec::new();
    encode_uint32(1, 1, &mut av_med); // PCM (1)
    encode_uint32(2, 3, &mut av_med); // MEDIA (3)
    encode_submsg(3, &ac_med, &mut av_med);

    let mut chan_med = Vec::new();
    encode_uint32(1, ID_AUD as u32, &mut chan_med);
    encode_submsg(3, &av_med, &mut chan_med);

    // Assemble all services (Field 1)
    encode_submsg(1, &chan_sen, &mut out);
    encode_submsg(1, &chan_vid, &mut out);
    encode_submsg(1, &chan_inp, &mut out);
    encode_submsg(1, &chan_sys, &mut out);
    encode_submsg(1, &chan_sp, &mut out);
    encode_submsg(1, &chan_med, &mut out);

    // Vehicle strings & session parameters
    encode_string(2, "Kawasaki", &mut out);
    encode_string(3, "Ninja", &mut out);
    encode_string(4, "2025", &mut out);
    encode_string(5, "OpenHeadUnit-Rust-01", &mut out);
    encode_uint32(6, 0, &mut out); // driver_position = LEFT (0)
    encode_string(7, "OpenHeadunit", &mut out);
    encode_string(8, "Rust-MSM8916", &mut out);
    encode_string(9, "1", &mut out);
    encode_string(10, "1.0.0", &mut out);
    encode_bool(11, false, &mut out); // can_play_native_media_during_vr
    encode_bool(12, false, &mut out); // hide_projected_clock
    encode_string(14, "Oppo A37 AA", &mut out);

    // HeadUnitInfo (Field 17) - Dynamic vehicle type (3 = Bike/Motorcycle, 1 = Car)
    let (make, model) = if vehicle_type == 3 {
        ("Kawasaki", "Ninja") // Motorcycle
    } else {
        ("Honda", "Civic") // Car
    };
    let mut hui = Vec::new();
    encode_string(1, make, &mut hui);
    encode_string(2, model, &mut hui);
    encode_string(3, "2025", &mut hui);
    encode_string(4, "OpenHeadUnit-Rust-01", &mut hui);
    encode_string(5, "OpenHeadunit", &mut hui);
    encode_string(6, "Rust-MSM8916", &mut hui);
    encode_string(7, "1", &mut hui);
    encode_string(8, "1.0.0", &mut hui);
    encode_uint32(9, vehicle_type, &mut hui);
    encode_submsg(17, &hui, &mut out);

    out
}

// Media Sink Setup -> Config Response (MEDIA_MESSAGE_CONFIG: 0x8003)
pub fn build_media_config_response(max_unacked: u32) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&[0x80, 0x03]); // MEDIA_MESSAGE_CONFIG type
    encode_uint32(1, 2, &mut out); // status = STATUS_READY (2) [Media.proto ConfigStatus: STATUS_WAIT=1, STATUS_READY=2]
    encode_uint32(2, max_unacked, &mut out); // max_unacked
    encode_uint32(3, 0, &mut out); // configuration_indices = 0
    out
}

// Video Focus Notification (MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION: 0x8008)
pub fn build_video_focus_notification() -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&[0x80, 0x08]); // MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION
    encode_uint32(1, 1, &mut out); // mode = VIDEO_FOCUS_PROJECTED (1)
    encode_bool(2, true, &mut out); // unsolicited = true
    out
}

// Audio Focus Notification (MESSAGE_AUDIO_FOCUS_NOTIFICATION: 0x0013 on Channel 0)
pub fn build_audio_focus_notification() -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&[0x00, 0x13]); // MESSAGE_AUDIO_FOCUS_NOTIFICATION
    encode_uint32(1, 1, &mut out); // focus_state = STATE_GAIN (1)
    encode_bool(2, true, &mut out); // unsolicited = true
    out
}

// Channel Open Response (MESSAGE_CHANNEL_OPEN_RESPONSE: 0x0008)
pub fn build_channel_open_response() -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&[0x00, 0x08]); // MESSAGE_CHANNEL_OPEN_RESPONSE
    encode_uint32(1, 0, &mut out); // status = STATUS_SUCCESS (0)
    out
}

// Media ACK (MEDIA_MESSAGE_ACK: 0x8004)
pub fn build_media_ack(session_id: i32) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&[0x80, 0x04]); // MEDIA_MESSAGE_ACK
    encode_varint((1 << 3) | 0, &mut out);
    encode_varint(session_id as u64, &mut out);
    encode_uint32(2, 1, &mut out); // ack = 1
    out
}

// Sensor Start Response (SENSOR_STARTRESPONSE: 0x8002)
pub fn build_sensor_start_response() -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&[0x80, 0x02]); // SENSOR_STARTRESPONSE
    encode_uint32(1, 0, &mut out); // status = STATUS_SUCCESS (0)
    out
}

// Sensor Event - Driving Status (SENSOR_EVENT: 0x8003)
pub fn build_sensor_driving_status() -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&[0x80, 0x03]); // SENSOR_EVENT
    let mut ds = Vec::new();
    encode_uint32(1, 0, &mut ds); // status = UNRESTRICTED (0)
    encode_submsg(13, &ds, &mut out); // driving_status = 13
    out
}

// Input Binding Response (BINDINGRESPONSE: 0x8003)
pub fn build_input_binding_response() -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&[0x80, 0x03]); // BINDINGRESPONSE
    encode_uint32(1, 0, &mut out); // status = STATUS_SUCCESS (0)
    out
}

// Ping Response (MESSAGE_PING_RESPONSE: 0x000C on Channel 0)
pub fn build_ping_response(timestamp: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&[0x00, 0x0C]); // MESSAGE_PING_RESPONSE
    out.extend_from_slice(timestamp);
    out
}

// Build touch input event indication (EVENT: 0x8001 on Channel 3)
pub fn build_touch_event_indication(action: u32, x: u32, y: u32, timestamp_us: u64) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&[0x80, 0x01]); // EVENT

    // Pointer data
    let mut ptr = Vec::new();
    encode_uint32(1, x, &mut ptr);
    encode_uint32(2, y, &mut ptr);
    encode_uint32(3, 0, &mut ptr); // pointer_id = 0

    // TouchEvent
    let mut te = Vec::new();
    encode_submsg(1, &ptr, &mut te);
    encode_uint32(2, 0, &mut te); // action_index = 0
    encode_uint32(3, action, &mut te); // touch_action: 0=DOWN, 1=UP, 2=MOVE

    // InputReport
    encode_varint((1 << 3) | 0, &mut out);
    encode_varint(timestamp_us, &mut out);
    encode_submsg(3, &te, &mut out);

    out
}
