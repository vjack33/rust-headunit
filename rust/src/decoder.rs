#![allow(non_snake_case, dead_code)]
use std::ffi::{c_char, c_int, c_void, CString};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::thread;

#[repr(C)]
struct AMediaCodecBufferInfo {
    offset: i32,
    size: i32,
    presentationTimeUs: i64,
    flags: u32,
}

extern "C" {
    fn __android_log_print(prio: c_int, tag: *const c_char, fmt: *const c_char, ...) -> c_int;
    fn AMediaFormat_new() -> *mut c_void;
    fn AMediaFormat_delete(format: *mut c_void) -> c_int;
    fn AMediaFormat_setString(format: *mut c_void, name: *const c_char, value: *const c_char);
    fn AMediaFormat_setInt32(format: *mut c_void, name: *const c_char, value: i32);

    fn AMediaCodec_createDecoderByType(mime_type: *const c_char) -> *mut c_void;
    fn AMediaCodec_configure(
        codec: *mut c_void,
        format: *const c_void,
        surface: *mut c_void,
        crypto: *mut c_void,
        flags: u32,
    ) -> c_int;
    fn AMediaCodec_start(codec: *mut c_void) -> c_int;
    fn AMediaCodec_stop(codec: *mut c_void) -> c_int;
    fn AMediaCodec_delete(codec: *mut c_void) -> c_int;

    fn AMediaCodec_dequeueInputBuffer(codec: *mut c_void, timeoutUs: i64) -> isize;
    fn AMediaCodec_getInputBuffer(codec: *mut c_void, idx: usize, out_size: *mut usize) -> *mut u8;
    fn AMediaCodec_queueInputBuffer(
        codec: *mut c_void,
        idx: usize,
        offset: isize,
        size: usize,
        pts: u64,
        flags: u32,
    ) -> c_int;

    fn AMediaCodec_dequeueOutputBuffer(
        codec: *mut c_void,
        info: *mut AMediaCodecBufferInfo,
        timeoutUs: i64,
    ) -> isize;
    fn AMediaCodec_releaseOutputBuffer(codec: *mut c_void, idx: usize, render: bool) -> c_int;

    fn setpriority(which: c_int, who: c_int, prio: c_int) -> c_int;
}

fn log(msg: &str) {
    let tag = CString::new("RustDecoder").unwrap();
    let c_msg = CString::new(msg).unwrap();
    unsafe {
        __android_log_print(4, tag.as_ptr(), c_msg.as_ptr());
    }
}

pub struct HardwareVideoDecoder {
    codec: *mut c_void,
    is_running: Arc<AtomicBool>,
    output_thread: Option<thread::JoinHandle<()>>,
    frame_count: Arc<AtomicU64>,
}

unsafe impl Send for HardwareVideoDecoder {}
unsafe impl Sync for HardwareVideoDecoder {}

impl HardwareVideoDecoder {
    pub fn new(native_window: *mut c_void) -> Option<Self> {
        if native_window.is_null() {
            log("[FAIL] native_window is null");
            return None;
        }

        let codec = unsafe {
            let mime = CString::new("video/avc").unwrap();
            let c = AMediaCodec_createDecoderByType(mime.as_ptr());
            if c.is_null() {
                log("[FAIL] Could not create video/avc decoder");
                return None;
            }

            let format = AMediaFormat_new();
            let key_mime = CString::new("mime").unwrap();
            let key_width = CString::new("width").unwrap();
            let key_height = CString::new("height").unwrap();
            let key_max_input = CString::new("max-input-size").unwrap();
            AMediaFormat_setString(format, key_mime.as_ptr(), mime.as_ptr());
            AMediaFormat_setInt32(format, key_width.as_ptr(), 1280);
            AMediaFormat_setInt32(format, key_height.as_ptr(), 720);
            AMediaFormat_setInt32(format, key_max_input.as_ptr(), 2 * 1024 * 1024); // 2MB buffer

            let res = AMediaCodec_configure(c, format, native_window, std::ptr::null_mut(), 0);
            AMediaFormat_delete(format);

            if res != 0 {
                log(&format!("[FAIL] AMediaCodec_configure returned {}", res));
                AMediaCodec_delete(c);
                return None;
            }

            let start_res = AMediaCodec_start(c);
            if start_res != 0 {
                log(&format!("[FAIL] AMediaCodec_start returned {}", start_res));
                AMediaCodec_delete(c);
                return None;
            }

            log("[OK] Qualcomm Venus H.264 HW Decoder started cleanly");
            c
        };

        let is_running = Arc::new(AtomicBool::new(true));
        let running_clone = Arc::clone(&is_running);
        let frame_count = Arc::new(AtomicU64::new(0));
        let count_clone = Arc::clone(&frame_count);
        let codec_usize = codec as usize;

        // Dedicated high-priority output thread for immediate zero-latency frame presentation
        let output_thread = thread::Builder::new()
            .name("VenusOutput".to_string())
            .spawn(move || {
                unsafe {
                    setpriority(0, 0, -10); // Realtime display scheduling priority
                    let codec_ptr = codec_usize as *mut c_void;
                    let mut info = AMediaCodecBufferInfo {
                        offset: 0,
                        size: 0,
                        presentationTimeUs: 0,
                        flags: 0,
                    };

                    while running_clone.load(Ordering::Relaxed) {
                        let out_idx = AMediaCodec_dequeueOutputBuffer(codec_ptr, &mut info, 5000); // 5ms timeout
                        if out_idx >= 0 {
                            AMediaCodec_releaseOutputBuffer(codec_ptr, out_idx as usize, true);
                            let total = count_clone.fetch_add(1, Ordering::Relaxed) + 1;
                            if total % 300 == 0 {
                                log(&format!("[Decoder] Rendered {} frames smoothly", total));
                            }
                            // Drain any additional immediately ready frames with 0 timeout
                            loop {
                                let next_idx = AMediaCodec_dequeueOutputBuffer(codec_ptr, &mut info, 0);
                                if next_idx >= 0 {
                                    AMediaCodec_releaseOutputBuffer(codec_ptr, next_idx as usize, true);
                                    count_clone.fetch_add(1, Ordering::Relaxed);
                                } else {
                                    break;
                                }
                            }
                        } else if out_idx == -2 || out_idx == -3 {
                            continue;
                        }
                    }
                }
            })
            .ok();

        Some(Self {
            codec,
            is_running,
            output_thread,
            frame_count,
        })
    }

    pub fn feed_packet(&mut self, data: &[u8], pts: u64, is_config: bool) {
        if self.codec.is_null() || data.is_empty() {
            return;
        }

        unsafe {
            let mut in_idx = AMediaCodec_dequeueInputBuffer(self.codec, 10000); // 10ms timeout
            if in_idx < 0 {
                in_idx = AMediaCodec_dequeueInputBuffer(self.codec, 5000);
            }
            if in_idx >= 0 {
                let mut out_size: usize = 0;
                let in_ptr = AMediaCodec_getInputBuffer(self.codec, in_idx as usize, &mut out_size);
                if !in_ptr.is_null() && out_size >= data.len() {
                    std::ptr::copy_nonoverlapping(data.as_ptr(), in_ptr, data.len());
                    let flags: u32 = if is_config { 2 } else { 0 }; // 2 = BUFFER_FLAG_CODEC_CONFIG
                    AMediaCodec_queueInputBuffer(
                        self.codec,
                        in_idx as usize,
                        0,
                        data.len(),
                        pts,
                        flags,
                    );
                }
            }
        }
    }

    pub fn close(&mut self) {
        if !self.codec.is_null() {
            self.is_running.store(false, Ordering::SeqCst);
            if let Some(handle) = self.output_thread.take() {
                let _ = handle.join();
            }
            unsafe {
                AMediaCodec_stop(self.codec);
                AMediaCodec_delete(self.codec);
            }
            self.codec = std::ptr::null_mut();
            log("Qualcomm Venus H.264 Decoder stopped.");
        }
    }
}

impl Drop for HardwareVideoDecoder {
    fn drop(&mut self) {
        self.close();
    }
}
