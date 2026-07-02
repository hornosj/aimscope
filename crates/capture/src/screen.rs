//! Captura de tela via DXGI Desktop Duplication (v0.3).
//!
//! Ponto-chave do design: `DXGI_OUTDUPL_FRAME_INFO.LastPresentTime` vem em
//! unidades de QueryPerformanceCounter — a MESMA base de tempo do Raw Input.
//! Sincronização mouse↔tela dissolvida por construção.
//!
//! Não gravamos vídeo: por frame, reduzimos uma ROI central a uma grade 3×3 de
//! luminância média e escrevemos em screen.parquet o delta absoluto por célula
//! + o delta global. O sensor Python detecta eventos visuais (spawn de alvo,
//! kill-pop) offline nesse sinal. Leve em disco (~KB/min), zero ffmpeg.
//!
//! Limitação conhecida: fullscreen EXCLUSIVO não é capturado pelo Desktop
//! Duplication — jogue em borderless/janela (como para OBS Display Capture).

use anyhow::{anyhow, Context, Result};
use std::fs::File;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;

use arrow_array::{Float32Array, Int64Array, RecordBatch};
use arrow_schema::{DataType, Field, Schema};
use parquet::arrow::ArrowWriter;
use parquet::basic::{Compression, ZstdLevel};
use parquet::file::properties::WriterProperties;

use serde::Serialize;
use windows::core::Interface;
use windows::Win32::Graphics::Direct3D::{D3D_DRIVER_TYPE_HARDWARE, D3D_FEATURE_LEVEL};
use windows::Win32::Graphics::Direct3D11::{
    D3D11CreateDevice, ID3D11Device, ID3D11DeviceContext, ID3D11Texture2D,
    D3D11_CPU_ACCESS_READ, D3D11_CREATE_DEVICE_FLAG, D3D11_MAPPED_SUBRESOURCE, D3D11_MAP_READ,
    D3D11_SDK_VERSION, D3D11_TEXTURE2D_DESC, D3D11_USAGE_STAGING,
};
use windows::Win32::Graphics::Dxgi::{
    IDXGIAdapter, IDXGIDevice, IDXGIOutput1, IDXGIOutputDuplication, IDXGIResource,
    DXGI_ERROR_ACCESS_LOST, DXGI_ERROR_WAIT_TIMEOUT, DXGI_OUTDUPL_FRAME_INFO,
};

const GRID: usize = 3; // 3x3 células na ROI central (agregadas da grade fina)
const ROI_FRACTION: f32 = 0.6; // 60% central da tela (onde o jogo acontece)
// Grade FINA interna: base do centroide de mudança (posição do alvo).
// 48x27 = mesma proporção 16:9; não é armazenada — só cx/cy/cw derivados.
const FINE_X: usize = 48;
const FINE_Y: usize = 27;

#[derive(Debug, Clone, Serialize)]
pub struct ScreenStats {
    pub frames: u64,
    pub duration_s: f64,
    pub mean_fps: f64,
    pub width: u32,
    pub height: u32,
}

pub struct ScreenCapture {
    thread: JoinHandle<Result<ScreenStats>>,
    stop: Arc<AtomicBool>,
    frames: Arc<AtomicU64>,
}

impl ScreenCapture {
    pub fn start(out_path: PathBuf) -> Result<ScreenCapture> {
        let stop = Arc::new(AtomicBool::new(false));
        let frames = Arc::new(AtomicU64::new(0));
        let (tx_ready, rx_ready) = std::sync::mpsc::channel::<Result<()>>();
        let stop2 = stop.clone();
        let frames2 = frames.clone();
        let thread = std::thread::Builder::new()
            .name("aimscope-screen".into())
            .spawn(move || capture_loop(out_path, stop2, frames2, tx_ready))?;
        rx_ready
            .recv()
            .map_err(|_| anyhow!("thread de tela morreu na inicialização"))??;
        Ok(ScreenCapture { thread, stop, frames })
    }

    pub fn live_frames(&self) -> u64 {
        self.frames.load(Ordering::Relaxed)
    }

    pub fn stop(self) -> Result<ScreenStats> {
        self.stop.store(true, Ordering::Relaxed);
        self.thread
            .join()
            .map_err(|_| anyhow!("panic na thread de tela"))?
    }
}

/// Amostra a grade FINA de luminância da ROI central de um frame BGRA mapeado.
fn sample_fine(data: *const u8, pitch: usize, w: usize, h: usize, out: &mut [f32]) {
    let roi_w = (w as f32 * ROI_FRACTION) as usize;
    let roi_h = (h as f32 * ROI_FRACTION) as usize;
    let x0 = (w - roi_w) / 2;
    let y0 = (h - roi_h) / 2;
    for fy in 0..FINE_Y {
        let y = y0 + fy * roi_h / FINE_Y + roi_h / (2 * FINE_Y);
        for fx in 0..FINE_X {
            let x = x0 + fx * roi_w / FINE_X + roi_w / (2 * FINE_X);
            let p = unsafe { data.add(y * pitch + x * 4) };
            // BGRA -> luma aproximada (B+2G+R)/4, inteiro e rápido
            let (b, g, r) = unsafe { (*p as u32, *p.add(1) as u32, *p.add(2) as u32) };
            out[fy * FINE_X + fx] = ((b + 2 * g + r) / 4) as f32;
        }
    }
}

/// Deriva da grade fina: células 3x3 (média), e centroide de |delta| em
/// coordenadas normalizadas da ROI: cx,cy ∈ [-1,1], +x direita, +y PARA BAIXO
/// (convenção de tela; o sensor Python inverte y ao casar com a cinemática).
/// cw = massa total de mudança (para o Python filtrar frames sem sinal).
fn derive(fine: &[f32], prev: &[f32], cells: &mut [f32; GRID * GRID]) -> (f32, f32, f32, f32) {
    let mut cell_acc = [0f32; GRID * GRID];
    let mut cell_n = [0u32; GRID * GRID];
    let (mut wsum, mut wx, mut wy) = (0f64, 0f64, 0f64);

    for fy in 0..FINE_Y {
        let cy = fy * GRID / FINE_Y;
        for fx in 0..FINE_X {
            let cxi = fx * GRID / FINE_X;
            let i = fy * FINE_X + fx;
            let d = (fine[i] - prev[i]).abs();
            cell_acc[cy * GRID + cxi] += d;
            cell_n[cy * GRID + cxi] += 1;
            let w = d as f64;
            wsum += w;
            wx += w * (2.0 * (fx as f64 + 0.5) / FINE_X as f64 - 1.0);
            wy += w * (2.0 * (fy as f64 + 0.5) / FINE_Y as f64 - 1.0);
        }
    }
    for i in 0..GRID * GRID {
        cells[i] = cell_acc[i] / cell_n[i].max(1) as f32;
    }
    let diff = cells.iter().sum::<f32>() / (GRID * GRID) as f32;
    if wsum > 1e-6 {
        (diff, (wx / wsum) as f32, (wy / wsum) as f32, wsum as f32)
    } else {
        (diff, 0.0, 0.0, 0.0)
    }
}

fn capture_loop(
    out_path: PathBuf,
    stop: Arc<AtomicBool>,
    frames_ctr: Arc<AtomicU64>,
    ready: std::sync::mpsc::Sender<Result<()>>,
) -> Result<ScreenStats> {
    // ---- D3D11 + duplicação -------------------------------------------------
    let mut device: Option<ID3D11Device> = None;
    let mut context: Option<ID3D11DeviceContext> = None;
    let init = (|| -> Result<(ID3D11Device, ID3D11DeviceContext, IDXGIOutputDuplication, u32, u32)> {
        unsafe {
            D3D11CreateDevice(
                None,
                D3D_DRIVER_TYPE_HARDWARE,
                windows::Win32::Foundation::HMODULE::default(),
                D3D11_CREATE_DEVICE_FLAG(0),
                None,
                D3D11_SDK_VERSION,
                Some(&mut device),
                Some(&mut D3D_FEATURE_LEVEL::default()),
                Some(&mut context),
            )
            .context("D3D11CreateDevice")?;
        }
        let device = device.clone().context("sem device D3D11")?;
        let context = context.clone().context("sem context D3D11")?;
        let dxgi: IDXGIDevice = device.cast().context("cast IDXGIDevice")?;
        let adapter: IDXGIAdapter = unsafe { dxgi.GetAdapter() }.context("GetAdapter")?;
        let output = unsafe { adapter.EnumOutputs(0) }.context("EnumOutputs(0)")?;
        let output1: IDXGIOutput1 = output.cast().context("cast IDXGIOutput1")?;
        let dupl = unsafe { output1.DuplicateOutput(&device) }
            .context("DuplicateOutput (outro app capturando em exclusivo?)")?;
        let desc = unsafe { dupl.GetDesc() };
        Ok((device, context, dupl, desc.ModeDesc.Width, desc.ModeDesc.Height))
    })();

    let (device, context, dupl, width, height) = match init {
        Ok(v) => {
            let _ = ready.send(Ok(()));
            v
        }
        Err(e) => {
            let msg = format!("{e:#}");
            let _ = ready.send(Err(anyhow!(msg)));
            return Err(e);
        }
    };

    // staging texture (CPU-readable), criada uma vez no tamanho do desktop
    let mut staging: Option<ID3D11Texture2D> = None;

    // ---- writer parquet ------------------------------------------------------
    let mut fields = vec![
        Field::new("ts_qpc", DataType::Int64, false),
        Field::new("diff", DataType::Float32, false),
        Field::new("lum", DataType::Float32, false),
    ];
    for i in 0..GRID * GRID {
        fields.push(Field::new(format!("c{i}"), DataType::Float32, false));
    }
    // centroide da mudança visual (posição do alvo em ROI normalizada)
    fields.push(Field::new("cx", DataType::Float32, false));
    fields.push(Field::new("cy", DataType::Float32, false));
    fields.push(Field::new("cw", DataType::Float32, false));
    let schema = Arc::new(Schema::new(fields));
    let props = WriterProperties::builder()
        .set_compression(Compression::ZSTD(ZstdLevel::default()))
        .build();
    let mut writer = ArrowWriter::try_new(File::create(&out_path)?, schema.clone(), Some(props))?;

    let mut ts_col: Vec<i64> = Vec::new();
    let mut diff_col: Vec<f32> = Vec::new();
    let mut lum_col: Vec<f32> = Vec::new();
    let mut cell_cols: Vec<Vec<f32>> = vec![Vec::new(); GRID * GRID];
    let mut cx_col: Vec<f32> = Vec::new();
    let mut cy_col: Vec<f32> = Vec::new();
    let mut cw_col: Vec<f32> = Vec::new();

    let mut fine_cur = vec![0f32; FINE_X * FINE_Y];
    let mut fine_prev = vec![0f32; FINE_X * FINE_Y];
    let mut cells = [0f32; GRID * GRID];
    let mut have_prev = false;
    let mut first_ts: Option<i64> = None;
    let mut last_ts: i64 = 0;
    let mut total: u64 = 0;

    let flush = |writer: &mut ArrowWriter<File>,
                 ts: &mut Vec<i64>,
                 diff: &mut Vec<f32>,
                 lum: &mut Vec<f32>,
                 cells: &mut Vec<Vec<f32>>,
                 cx: &mut Vec<f32>,
                 cy: &mut Vec<f32>,
                 cw: &mut Vec<f32>|
     -> Result<()> {
        if ts.is_empty() {
            return Ok(());
        }
        let mut cols: Vec<std::sync::Arc<dyn arrow_array::Array>> = vec![
            Arc::new(Int64Array::from(std::mem::take(ts))),
            Arc::new(Float32Array::from(std::mem::take(diff))),
            Arc::new(Float32Array::from(std::mem::take(lum))),
        ];
        for c in cells.iter_mut() {
            cols.push(Arc::new(Float32Array::from(std::mem::take(c))));
        }
        cols.push(Arc::new(Float32Array::from(std::mem::take(cx))));
        cols.push(Arc::new(Float32Array::from(std::mem::take(cy))));
        cols.push(Arc::new(Float32Array::from(std::mem::take(cw))));
        writer.write(&RecordBatch::try_new(schema.clone(), cols)?)?;
        Ok(())
    };

    // ---- loop ---------------------------------------------------------------
    while !stop.load(Ordering::Relaxed) {
        let mut info = DXGI_OUTDUPL_FRAME_INFO::default();
        let mut resource: Option<IDXGIResource> = None;
        let acq = unsafe { dupl.AcquireNextFrame(16, &mut info, &mut resource) };
        match acq {
            Err(e) if e.code() == DXGI_ERROR_WAIT_TIMEOUT => continue,
            Err(e) if e.code() == DXGI_ERROR_ACCESS_LOST => {
                eprintln!("[screen] access lost (troca de modo?) — encerrando captura de tela");
                break;
            }
            Err(e) => return Err(anyhow!("AcquireNextFrame: {e}")),
            Ok(()) => {}
        }

        // frames sem update de imagem (só cursor) têm LastPresentTime == 0
        if info.LastPresentTime == 0 {
            unsafe { dupl.ReleaseFrame().ok() };
            continue;
        }
        let ts = info.LastPresentTime;

        let ok = (|| -> Result<()> {
            let res = resource.as_ref().context("sem resource")?;
            let tex: ID3D11Texture2D = res.cast()?;
            if staging.is_none() {
                let mut desc = D3D11_TEXTURE2D_DESC::default();
                unsafe { tex.GetDesc(&mut desc) };
                desc.Usage = D3D11_USAGE_STAGING;
                desc.BindFlags = 0;
                desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ.0 as u32;
                desc.MiscFlags = 0;
                let mut st: Option<ID3D11Texture2D> = None;
                unsafe { device.CreateTexture2D(&desc, None, Some(&mut st)) }?;
                staging = st;
            }
            let st = staging.as_ref().unwrap();
            unsafe { context.CopyResource(st, &tex) };
            let mut mapped = D3D11_MAPPED_SUBRESOURCE::default();
            unsafe { context.Map(st, 0, D3D11_MAP_READ, 0, Some(&mut mapped)) }?;
            sample_fine(
                mapped.pData as *const u8,
                mapped.RowPitch as usize,
                width as usize,
                height as usize,
                &mut fine_cur,
            );
            unsafe { context.Unmap(st, 0) };
            Ok(())
        })();
        unsafe { dupl.ReleaseFrame().ok() };
        ok?;

        let lum = fine_cur.iter().sum::<f32>() / fine_cur.len() as f32;
        let (diff, ccx, ccy, ccw) = if have_prev {
            derive(&fine_cur, &fine_prev, &mut cells)
        } else {
            cells = [0f32; GRID * GRID];
            (0.0, 0.0, 0.0, 0.0)
        };

        ts_col.push(ts);
        diff_col.push(diff);
        lum_col.push(lum);
        for i in 0..GRID * GRID {
            cell_cols[i].push(cells[i]);
        }
        cx_col.push(ccx);
        cy_col.push(ccy);
        cw_col.push(ccw);

        std::mem::swap(&mut fine_prev, &mut fine_cur);
        have_prev = true;
        first_ts.get_or_insert(ts);
        last_ts = ts;
        total += 1;
        frames_ctr.store(total, Ordering::Relaxed);

        if ts_col.len() >= 30_000 {
            flush(&mut writer, &mut ts_col, &mut diff_col, &mut lum_col, &mut cell_cols,
                  &mut cx_col, &mut cy_col, &mut cw_col)?;
        }
    }

    flush(&mut writer, &mut ts_col, &mut diff_col, &mut lum_col, &mut cell_cols,
          &mut cx_col, &mut cy_col, &mut cw_col)?;
    writer.close()?;

    let freq = crate::qpc_frequency() as f64;
    let duration = match first_ts {
        Some(f) if last_ts > f => (last_ts - f) as f64 / freq,
        _ => 0.0,
    };
    Ok(ScreenStats {
        frames: total,
        duration_s: duration,
        mean_fps: if duration > 0.0 { (total.saturating_sub(1)) as f64 / duration } else { 0.0 },
        width,
        height,
    })
}
