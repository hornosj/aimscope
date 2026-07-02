//! Fluxo completo de gravação de uma sessão (usado pelo CLI e pela UI):
//! cria o diretório, escreve o manifest, roda a captura e, ao finalizar,
//! grava estatísticas e copia os CSVs novos do KovaaK's.

use crate::{qpc_frequency, qpc_now, Capture, CaptureStats, LiveCounters};
use anyhow::Result;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone, Default)]
pub struct RecordParams {
    pub dpi: Option<f64>,
    pub sens: Option<f64>,
    pub sens_scale: String,
    pub notes: Option<String>,
    /// Capturar tela (DXGI) para reaction time. Exige jogo em borderless.
    pub screen: bool,
}

pub struct Recording {
    pub dir: PathBuf,
    pub start_wall: SystemTime,
    cap: Capture,
    screen: Option<crate::screen::ScreenCapture>,
    /// Aviso não-fatal (ex.: DXGI indisponível) — mostrado na UI/CLI.
    pub screen_warning: Option<String>,
}

pub fn begin(base: &Path, params: &RecordParams) -> Result<Recording> {
    std::fs::create_dir_all(base)?;
    let dir = session::create_session_dir(base, "kovaaks")?;

    let qpc_at_start = qpc_now();
    let unix_ns_at_start = SystemTime::now().duration_since(UNIX_EPOCH)?.as_nanos() as i64;

    let scale = if params.sens_scale.is_empty() {
        "valorant".to_string()
    } else {
        params.sens_scale.clone()
    };
    let dpc = params.sens.and_then(|s| session::deg_per_count(&scale, s));
    let cm360 = match (dpc, params.dpi) {
        (Some(d), Some(dpi)) => Some(session::cm_per_360(d, dpi)),
        _ => None,
    };

    let manifest = session::Manifest {
        schema_version: session::SCHEMA_VERSION,
        app_version: env!("CARGO_PKG_VERSION").to_string(),
        game: "kovaaks".into(),
        created_local: format!("{:?}", SystemTime::now()),
        unix_ns_at_start,
        qpc_at_start,
        qpc_frequency: qpc_frequency(),
        dpi: params.dpi,
        sens: params.sens,
        sens_scale: Some(scale),
        cm_per_360: cm360,
        notes: params.notes.clone(),
    };
    manifest.save(&dir)?;

    let start_wall = SystemTime::now();
    let cap = Capture::start(dir.join("mouse.parquet"))?;

    // Tela é best-effort: falha de DXGI não derruba a gravação de mouse.
    let (screen, screen_warning) = if params.screen {
        match crate::screen::ScreenCapture::start(dir.join("screen.parquet")) {
            Ok(s) => (Some(s), None),
            Err(e) => (None, Some(format!("captura de tela indisponível: {e:#}"))),
        }
    } else {
        (None, None)
    };

    Ok(Recording { dir, start_wall, cap, screen, screen_warning })
}

impl Recording {
    pub fn live(&self) -> Arc<LiveCounters> {
        self.cap.live()
    }

    pub fn live_screen_frames(&self) -> Option<u64> {
        self.screen.as_ref().map(|s| s.live_frames())
    }

    /// Para as capturas, grava stats e copia CSVs do KovaaK's.
    /// Retorna (estatísticas de mouse, frames de tela, nº de CSVs copiados).
    pub fn finish(
        self,
        kovaaks_stats: Option<PathBuf>,
    ) -> Result<(CaptureStats, Option<crate::screen::ScreenStats>, usize)> {
        let screen_stats = match self.screen {
            Some(s) => match s.stop() {
                Ok(st) => {
                    std::fs::write(
                        self.dir.join("screen_stats.json"),
                        serde_json::to_string_pretty(&st)?,
                    )?;
                    Some(st)
                }
                Err(e) => {
                    eprintln!("[screen] erro ao finalizar: {e:#}");
                    None
                }
            },
            None => None,
        };
        let stats = self.cap.stop()?;
        std::fs::write(
            self.dir.join("capture_stats.json"),
            serde_json::to_string_pretty(&stats)?,
        )?;
        let copied = match session::tools::kovaaks_stats_dir(kovaaks_stats) {
            Some(sd) => session::tools::copy_new_csvs(&sd, &self.dir, self.start_wall)?,
            None => 0,
        };
        Ok((stats, screen_stats, copied))
    }
}
