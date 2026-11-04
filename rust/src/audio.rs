#![allow(non_snake_case, dead_code)]
use std::ffi::{c_char, c_int, c_void, CString};

extern "C" {
    fn __android_log_print(prio: c_int, tag: *const c_char, fmt: *const c_char, ...) -> c_int;
    fn AAudio_createStreamBuilder(builder: *mut *mut c_void) -> c_int;
    fn AAudioStreamBuilder_delete(builder: *mut c_void) -> c_int;
    fn AAudioStreamBuilder_setSampleRate(builder: *mut c_void, sampleRate: i32);
    fn AAudioStreamBuilder_setChannelCount(builder: *mut c_void, channelCount: i32);
    fn AAudioStreamBuilder_setFormat(builder: *mut c_void, format: i32);
    fn AAudioStreamBuilder_setPerformanceMode(builder: *mut c_void, mode: i32);
    fn AAudioStreamBuilder_openStream(builder: *mut c_void, stream: *mut *mut c_void) -> c_int;

    fn AAudioStream_requestStart(stream: *mut c_void) -> c_int;
    fn AAudioStream_requestStop(stream: *mut c_void) -> c_int;
    fn AAudioStream_close(stream: *mut c_void) -> c_int;
    fn AAudioStream_write(
        stream: *mut c_void,
        buffer: *const c_void,
        numFrames: i32,
        timeoutNanoseconds: i64,
    ) -> i32;
}

fn log(msg: &str) {
    let tag = CString::new("RustAudio").unwrap();
    let c_msg = CString::new(msg).unwrap();
    unsafe {
        __android_log_print(4, tag.as_ptr(), c_msg.as_ptr());
    }
}

pub struct NativeAudioSink {
    stream: *mut c_void,
}

unsafe impl Send for NativeAudioSink {}
unsafe impl Sync for NativeAudioSink {}

impl NativeAudioSink {
    pub fn new(sample_rate: i32, channels: i32) -> Option<Self> {
        unsafe {
            let mut builder: *mut c_void = std::ptr::null_mut();
            let res = AAudio_createStreamBuilder(&mut builder);
            if res != 0 || builder.is_null() {
                log("[FAIL] Could not create AAudio builder");
                return None;
            }

            AAudioStreamBuilder_setSampleRate(builder, sample_rate);
            AAudioStreamBuilder_setChannelCount(builder, channels);
            AAudioStreamBuilder_setFormat(builder, 1); // AAUDIO_FORMAT_PCM_I16 = 1
            AAudioStreamBuilder_setPerformanceMode(builder, 12); // LOW_LATENCY = 12

            let mut stream: *mut c_void = std::ptr::null_mut();
            let open_res = AAudioStreamBuilder_openStream(builder, &mut stream);
            AAudioStreamBuilder_delete(builder);

            if open_res != 0 || stream.is_null() {
                log(&format!("[FAIL] AAudioStreamBuilder_openStream returned {}", open_res));
                return None;
            }

            let start_res = AAudioStream_requestStart(stream);
            if start_res != 0 {
                log(&format!("[FAIL] AAudioStream_requestStart returned {}", start_res));
                AAudioStream_close(stream);
                return None;
            }

            log(&format!("[OK] AAudio low-latency sink active: {} Hz, {} ch", sample_rate, channels));
            Some(Self { stream })
        }
    }

    pub fn write_pcm_i16(&mut self, pcm_bytes: &[u8]) {
        if self.stream.is_null() || pcm_bytes.is_empty() {
            return;
        }

        // 16-bit stereo = 4 bytes per frame
        let num_frames = (pcm_bytes.len() / 4) as i32;
        if num_frames > 0 {
            unsafe {
                AAudioStream_write(
                    self.stream,
                    pcm_bytes.as_ptr() as *const c_void,
                    num_frames,
                    0, // Non-blocking
                );
            }
        }
    }

    pub fn close(&mut self) {
        if !self.stream.is_null() {
            unsafe {
                AAudioStream_requestStop(self.stream);
                AAudioStream_close(self.stream);
            }
            self.stream = std::ptr::null_mut();
            log("AAudio sink closed.");
        }
    }
}

impl Drop for NativeAudioSink {
    fn drop(&mut self) {
        self.close();
    }
}
