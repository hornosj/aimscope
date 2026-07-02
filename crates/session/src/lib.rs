//! Formato de sessão do aimscope.
//!
//! Uma sessão é um diretório:
//! ```text
//! kovaaks_2026-07-01_21-30-05/
//!   manifest.json        # metadados + base de tempo (QPC <-> relógio de parede)
//!   mouse.parquet        # eventos Raw Input carimbados com QPC
//!   capture_stats.json   # estatísticas de jitter/taxa da captura
//!   *.csv                # CSVs do KovaaK's copiados da pasta de stats
//!   metrics.json         # (pós-análise, escrito pelo sidecar Python)
//!   report.html          # (pós-análise)
//! ```
//! Este formato é o CONTRATO entre o gravador Rust e o laboratório/sidecar Python.

pub mod tools;

use anyhow::{Context, Result};
use chrono::Local;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

pub const SCHEMA_VERSION: u32 = 1;

/// Graus por count de mouse com sens = 1.0, por escala de sensibilidade.
pub fn deg_per_count(scale: &str, sens: f64) -> Option<f64> {
    let base = match scale.to_ascii_lowercase().as_str() {
        "valorant" => 0.07,
        "cs" | "csgo" | "cs2" | "quakecs" | "quake" | "apex" | "source" => 0.022,
        "overwatch" | "ow" => 0.0066,
        _ => return None,
    };
    Some(base * sens)
}

/// cm de mousepad para girar 360 graus.
pub fn cm_per_360(deg_per_count: f64, dpi: f64) -> f64 {
    let counts = 360.0 / deg_per_count;
    counts / dpi * 2.54
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct Manifest {
    pub schema_version: u32,
    pub app_version: String,
    pub game: String,
    pub created_local: String,
    /// Nanossegundos Unix no instante de `qpc_at_start` — âncora QPC <-> parede.
    pub unix_ns_at_start: i64,
    pub qpc_at_start: i64,
    pub qpc_frequency: i64,
    pub dpi: Option<f64>,
    pub sens: Option<f64>,
    pub sens_scale: Option<String>,
    pub cm_per_360: Option<f64>,
    pub notes: Option<String>,
}

impl Manifest {
    pub fn save(&self, session_dir: &Path) -> Result<()> {
        let path = session_dir.join("manifest.json");
        let json = serde_json::to_string_pretty(self)?;
        std::fs::write(&path, json)
            .with_context(|| format!("escrevendo {}", path.display()))?;
        Ok(())
    }

    pub fn load(session_dir: &Path) -> Result<Self> {
        let path = session_dir.join("manifest.json");
        let data = std::fs::read_to_string(&path)
            .with_context(|| format!("lendo {}", path.display()))?;
        Ok(serde_json::from_str(&data)?)
    }
}

/// Cria o diretório de uma nova sessão com nome timestampado.
pub fn create_session_dir(base: &Path, game: &str) -> Result<PathBuf> {
    let stamp = Local::now().format("%Y-%m-%d_%H-%M-%S");
    let dir = base.join(format!("{game}_{stamp}"));
    std::fs::create_dir_all(&dir)
        .with_context(|| format!("criando {}", dir.display()))?;
    Ok(dir)
}

/// Lista sessões (subdiretórios com manifest.json), mais recentes primeiro.
pub fn list_sessions(base: &Path) -> Result<Vec<PathBuf>> {
    let mut out = Vec::new();
    if !base.exists() {
        return Ok(out);
    }
    for entry in std::fs::read_dir(base)? {
        let p = entry?.path();
        if p.is_dir() && p.join("manifest.json").exists() {
            out.push(p);
        }
    }
    out.sort();
    out.reverse();
    Ok(out)
}
