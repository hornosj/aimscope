//! Captura de mouse via Raw Input (WM_INPUT) com timestamps QPC,
//! escrita em mouse.parquet.
//!
//! Arquitetura: duas threads.
//! - Thread de captura: janela message-only + RIDEV_INPUTSINK, prioridade alta,
//!   zero alocação no caminho quente; só carimba QPC e empurra num canal.
//! - Thread de escrita: bufferiza e grava row groups Parquet periodicamente.
//!
//! 100% observador externo: nenhum handle em processo de jogo, nenhum hook.

use anyhow::{anyhow, bail, Result};
use serde::Serialize;
use std::ffi::c_void;
use std::fs::File;
use std::mem::size_of;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc;
use std::sync::Arc;
use std::thread::JoinHandle;

pub mod recorder;
pub mod screen;

use arrow_array::{Int16Array, Int32Array, Int64Array, RecordBatch, UInt16Array};
use arrow_schema::{DataType, Field, Schema};
use parquet::arrow::ArrowWriter;
use parquet::basic::{Compression, ZstdLevel};
use parquet::file::properties::WriterProperties;

use windows_sys::Win32::Devices::HumanInterfaceDevice::{
    HID_USAGE_GENERIC_MOUSE, HID_USAGE_PAGE_GENERIC,
};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::System::Performance::{QueryPerformanceCounter, QueryPerformanceFrequency};
use windows_sys::Win32::System::Threading::{
    GetCurrentThread, GetCurrentThreadId, SetThreadPriority, THREAD_PRIORITY_HIGHEST,
};
use windows_sys::Win32::UI::Input::{
    GetRawInputData, RegisterRawInputDevices, RAWINPUT, RAWINPUTDEVICE, RAWINPUTHEADER, RID_INPUT,
    RIDEV_INPUTSINK, RIM_TYPEMOUSE,
};
use windows_sys::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DispatchMessageW, GetMessageW, PostThreadMessageW,
    RegisterClassW, HWND_MESSAGE, MSG, WM_INPUT, WM_QUIT, WNDCLASSW,
};

/// Flags de botão do RAWMOUSE (usButtonFlags).
pub const LEFT_DOWN: u16 = 0x0001;
pub const LEFT_UP: u16 = 0x0002;
pub const RIGHT_DOWN: u16 = 0x0004;
pub const WHEEL: u16 = 0x0400;

#[derive(Debug, Clone, Copy)]
struct MouseEvent {
    ts_qpc: i64,
    dx: i32,
    dy: i32,
    button_flags: u16,
    button_data: i16,
    device_flags: u16,
}

#[derive(Debug, Clone, Serialize)]
pub struct CaptureStats {
    pub events: u64,
    pub left_clicks: u64,
    pub duration_s: f64,
    pub mean_rate_hz: f64,
    pub p50_interval_ms: f64,
    pub p95_interval_ms: f64,
    pub p99_interval_ms: f64,
    pub max_gap_ms: f64,
}

pub fn qpc_now() -> i64 {
    let mut t: i64 = 0;
    unsafe { QueryPerformanceCounter(&mut t) };
    t
}

pub fn qpc_frequency() -> i64 {
    let mut f: i64 = 0;
    unsafe { QueryPerformanceFrequency(&mut f) };
    f
}

/// Contadores atualizados pela thread de captura, para exibição ao vivo.
#[derive(Default)]
pub struct LiveCounters {
    pub events: AtomicU64,
    pub left_clicks: AtomicU64,
}

pub struct Capture {
    msg_thread: JoinHandle<Result<()>>,
    writer_thread: JoinHandle<Result<CaptureStats>>,
    win_thread_id: u32,
    live: Arc<LiveCounters>,
}

impl Capture {
    /// Inicia a captura. Retorna quando o Raw Input já está registrado.
    pub fn start(out_path: PathBuf) -> Result<Capture> {
        let (tx_event, rx_event) = mpsc::channel::<MouseEvent>();
        let (tx_ready, rx_ready) = mpsc::channel::<Result<u32>>();
        let live = Arc::new(LiveCounters::default());
        let live_cap = live.clone();

        let msg_thread = std::thread::Builder::new()
            .name("aimscope-capture".into())
            .spawn(move || capture_thread(tx_event, tx_ready, live_cap))?;

        let writer_thread = std::thread::Builder::new()
            .name("aimscope-writer".into())
            .spawn(move || writer_thread(out_path, rx_event))?;

        let win_thread_id = rx_ready
            .recv()
            .map_err(|_| anyhow!("thread de captura morreu antes de inicializar"))??;

        Ok(Capture {
            msg_thread,
            writer_thread,
            win_thread_id,
            live,
        })
    }

    /// Contadores ao vivo (eventos/cliques), para UI.
    pub fn live(&self) -> Arc<LiveCounters> {
        self.live.clone()
    }

    /// Para a captura e finaliza o Parquet. Retorna estatísticas.
    pub fn stop(self) -> Result<CaptureStats> {
        unsafe {
            PostThreadMessageW(self.win_thread_id, WM_QUIT, 0, 0);
        }
        self.msg_thread
            .join()
            .map_err(|_| anyhow!("panic na thread de captura"))??;
        // Com a thread de captura encerrada, o Sender caiu e o writer drena e fecha.
        self.writer_thread
            .join()
            .map_err(|_| anyhow!("panic na thread de escrita"))?
    }
}

unsafe extern "system" fn wndproc(
    hwnd: windows_sys::Win32::Foundation::HWND,
    msg: u32,
    wparam: usize,
    lparam: isize,
) -> isize {
    DefWindowProcW(hwnd, msg, wparam, lparam)
}

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

fn capture_thread(
    tx: mpsc::Sender<MouseEvent>,
    ready: mpsc::Sender<Result<u32>>,
    live: Arc<LiveCounters>,
) -> Result<()> {
    unsafe {
        SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_HIGHEST);

        let hinstance = GetModuleHandleW(std::ptr::null());
        let class_name = wide("aimscope_rawinput_sink");
        let mut wc: WNDCLASSW = std::mem::zeroed();
        wc.lpfnWndProc = Some(wndproc);
        wc.hInstance = hinstance;
        wc.lpszClassName = class_name.as_ptr();
        RegisterClassW(&wc); // 0 se já registrada neste processo: ok.

        let hwnd = CreateWindowExW(
            0,
            class_name.as_ptr(),
            wide("aimscope").as_ptr(),
            0,
            0,
            0,
            0,
            0,
            HWND_MESSAGE,
            std::ptr::null_mut(),
            hinstance,
            std::ptr::null(),
        );
        if hwnd.is_null() {
            let _ = ready.send(Err(anyhow!("CreateWindowExW falhou")));
            bail!("CreateWindowExW falhou");
        }

        let rid = RAWINPUTDEVICE {
            usUsagePage: HID_USAGE_PAGE_GENERIC,
            usUsage: HID_USAGE_GENERIC_MOUSE,
            dwFlags: RIDEV_INPUTSINK, // recebe input mesmo em background
            hwndTarget: hwnd,
        };
        if RegisterRawInputDevices(&rid, 1, size_of::<RAWINPUTDEVICE>() as u32) == 0 {
            let _ = ready.send(Err(anyhow!("RegisterRawInputDevices falhou")));
            bail!("RegisterRawInputDevices falhou");
        }

        let _ = ready.send(Ok(GetCurrentThreadId()));

        // Buffer fixo alinhado: zero alocação no caminho quente.
        #[repr(C, align(8))]
        struct RawBuf([u8; 1024]);
        let mut buf = RawBuf([0u8; 1024]);

        let mut msg: MSG = std::mem::zeroed();
        loop {
            let r = GetMessageW(&mut msg, std::ptr::null_mut(), 0, 0);
            if r == 0 {
                break; // WM_QUIT
            }
            if r == -1 {
                bail!("GetMessageW retornou erro");
            }
            if msg.message == WM_INPUT {
                let ts = qpc_now();
                let mut size: u32 = buf.0.len() as u32;
                let got = GetRawInputData(
                    msg.lParam as _,
                    RID_INPUT,
                    buf.0.as_mut_ptr() as *mut c_void,
                    &mut size,
                    size_of::<RAWINPUTHEADER>() as u32,
                );
                if got != u32::MAX && got > 0 {
                    let ri = &*(buf.0.as_ptr() as *const RAWINPUT);
                    if ri.header.dwType == RIM_TYPEMOUSE {
                        let m = &ri.data.mouse;
                        let ev = MouseEvent {
                            ts_qpc: ts,
                            dx: m.lLastX,
                            dy: m.lLastY,
                            button_flags: m.Anonymous.Anonymous.usButtonFlags,
                            button_data: m.Anonymous.Anonymous.usButtonData as i16,
                            device_flags: m.usFlags,
                        };
                        live.events.fetch_add(1, Ordering::Relaxed);
                        if ev.button_flags & LEFT_DOWN != 0 {
                            live.left_clicks.fetch_add(1, Ordering::Relaxed);
                        }
                        if tx.send(ev).is_err() {
                            break; // writer morreu
                        }
                    }
                }
            }
            DispatchMessageW(&msg);
        }
    }
    Ok(())
}

const FLUSH_EVERY: usize = 250_000; // ~4 min a 1000 Hz por row group

fn writer_thread(out_path: PathBuf, rx: mpsc::Receiver<MouseEvent>) -> Result<CaptureStats> {
    let schema = Arc::new(Schema::new(vec![
        Field::new("ts_qpc", DataType::Int64, false),
        Field::new("dx", DataType::Int32, false),
        Field::new("dy", DataType::Int32, false),
        Field::new("button_flags", DataType::UInt16, false),
        Field::new("button_data", DataType::Int16, false),
        Field::new("device_flags", DataType::UInt16, false),
    ]));

    let file = File::create(&out_path)?;
    let props = WriterProperties::builder()
        .set_compression(Compression::ZSTD(ZstdLevel::default()))
        .build();
    let mut writer = ArrowWriter::try_new(file, schema.clone(), Some(props))?;

    let mut ts: Vec<i64> = Vec::new();
    let mut dx: Vec<i32> = Vec::new();
    let mut dy: Vec<i32> = Vec::new();
    let mut bf: Vec<u16> = Vec::new();
    let mut bd: Vec<i16> = Vec::new();
    let mut df: Vec<u16> = Vec::new();

    let mut all_ts: Vec<i64> = Vec::new();
    let mut left_clicks: u64 = 0;
    let mut total: u64 = 0;

    let flush = |writer: &mut ArrowWriter<File>,
                 ts: &mut Vec<i64>,
                 dx: &mut Vec<i32>,
                 dy: &mut Vec<i32>,
                 bf: &mut Vec<u16>,
                 bd: &mut Vec<i16>,
                 df: &mut Vec<u16>|
     -> Result<()> {
        if ts.is_empty() {
            return Ok(());
        }
        let batch = RecordBatch::try_new(
            schema.clone(),
            vec![
                Arc::new(Int64Array::from(std::mem::take(ts))),
                Arc::new(Int32Array::from(std::mem::take(dx))),
                Arc::new(Int32Array::from(std::mem::take(dy))),
                Arc::new(UInt16Array::from(std::mem::take(bf))),
                Arc::new(Int16Array::from(std::mem::take(bd))),
                Arc::new(UInt16Array::from(std::mem::take(df))),
            ],
        )?;
        writer.write(&batch)?;
        Ok(())
    };

    for ev in rx.iter() {
        total += 1;
        if ev.button_flags & LEFT_DOWN != 0 {
            left_clicks += 1;
        }
        all_ts.push(ev.ts_qpc);
        ts.push(ev.ts_qpc);
        dx.push(ev.dx);
        dy.push(ev.dy);
        bf.push(ev.button_flags);
        bd.push(ev.button_data);
        df.push(ev.device_flags);
        if ts.len() >= FLUSH_EVERY {
            flush(&mut writer, &mut ts, &mut dx, &mut dy, &mut bf, &mut bd, &mut df)?;
        }
    }
    flush(&mut writer, &mut ts, &mut dx, &mut dy, &mut bf, &mut bd, &mut df)?;
    writer.close()?;

    Ok(compute_stats(&all_ts, total, left_clicks))
}

fn compute_stats(all_ts: &[i64], total: u64, left_clicks: u64) -> CaptureStats {
    let freq = qpc_frequency() as f64;
    let to_ms = |ticks: i64| ticks as f64 / freq * 1000.0;

    let (duration_s, p50, p95, p99, max_gap) = if all_ts.len() >= 2 {
        let dur = (all_ts[all_ts.len() - 1] - all_ts[0]) as f64 / freq;
        let mut deltas: Vec<i64> = all_ts.windows(2).map(|w| w[1] - w[0]).collect();
        deltas.sort_unstable();
        let pct = |p: f64| to_ms(deltas[((deltas.len() - 1) as f64 * p) as usize]);
        (dur, pct(0.50), pct(0.95), pct(0.99), to_ms(deltas[deltas.len() - 1]))
    } else {
        (0.0, 0.0, 0.0, 0.0, 0.0)
    };

    CaptureStats {
        events: total,
        left_clicks,
        duration_s,
        mean_rate_hz: if duration_s > 0.0 {
            (total.saturating_sub(1)) as f64 / duration_s
        } else {
            0.0
        },
        p50_interval_ms: p50,
        p95_interval_ms: p95,
        p99_interval_ms: p99,
        max_gap_ms: max_gap,
    }
}
