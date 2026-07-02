//! aimscope — gravador e analisador de mecânica de mira (produto KovaaK's, v1).

use anyhow::{bail, Context, Result};
use capture::recorder::{self, RecordParams};
use clap::{Parser, Subcommand};
use session::tools;
use std::path::PathBuf;
use std::time::Duration;

#[derive(Parser)]
#[command(name = "aimscope", version, about = "Análise de mecânica de mira/mouse control")]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Grava uma sessão de treino (Raw Input 1000Hz+ com timestamps QPC).
    Record {
        /// Diretório base das sessões (default: %LOCALAPPDATA%\aimscope\sessions)
        #[arg(long)]
        out: Option<PathBuf>,
        /// DPI do mouse (necessário para converter counts em graus/cm)
        #[arg(long)]
        dpi: Option<f64>,
        /// Sensibilidade in-game
        #[arg(long)]
        sens: Option<f64>,
        /// Escala da sens: valorant | cs | overwatch (default: valorant)
        #[arg(long, default_value = "valorant")]
        sens_scale: String,
        /// Pasta de stats do KovaaK's (default: autodetectar Steam)
        #[arg(long)]
        kovaaks_stats: Option<PathBuf>,
        /// Parar automaticamente após N segundos (para testes; default: Ctrl+C)
        #[arg(long)]
        duration: Option<u64>,
        /// Nota livre sobre a sessão (ex.: "descansado", "pós-café")
        #[arg(long)]
        notes: Option<String>,
        /// Capturar tela (DXGI) p/ reaction time — jogo em borderless!
        #[arg(long)]
        screen: bool,
    },
    /// Roda a análise (sidecar Python) numa sessão gravada.
    Analyze {
        /// Diretório da sessão (ou "latest" para a mais recente)
        session: String,
        /// Diretório base das sessões (para resolver "latest")
        #[arg(long)]
        out: Option<PathBuf>,
        /// Caminho do python.exe (default: autodetectar venv do sidecar)
        #[arg(long)]
        python: Option<PathBuf>,
        /// Abrir o report.html no navegador ao terminar
        #[arg(long)]
        open: bool,
    },
    /// Lista as sessões gravadas.
    List {
        #[arg(long)]
        out: Option<PathBuf>,
    },
}

fn base_dir(out: Option<PathBuf>) -> PathBuf {
    out.unwrap_or_else(tools::default_base_dir)
}

fn main() -> Result<()> {
    let cli = Cli::parse();
    match cli.cmd {
        Cmd::Record { out, dpi, sens, sens_scale, kovaaks_stats, duration, notes, screen } => {
            record(out, dpi, sens, sens_scale, kovaaks_stats, duration, notes, screen)
        }
        Cmd::Analyze { session, out, python, open } => analyze(session, out, python, open),
        Cmd::List { out } => list(out),
    }
}

fn record(
    out: Option<PathBuf>,
    dpi: Option<f64>,
    sens: Option<f64>,
    sens_scale: String,
    kovaaks_stats: Option<PathBuf>,
    duration: Option<u64>,
    notes: Option<String>,
    screen: bool,
) -> Result<()> {
    if dpi.is_none() {
        eprintln!("[aviso] --dpi não informado: métricas ficarão em counts, não em graus/cm.");
    }
    let params = RecordParams { dpi, sens, sens_scale, notes, screen };
    let rec = recorder::begin(&base_dir(out), &params)?;
    if let Some(w) = &rec.screen_warning {
        eprintln!("[aviso] {w}");
    }
    println!("Gravando em {}", rec.dir.display());

    match duration {
        Some(secs) => {
            println!("Parando automaticamente em {secs}s...");
            std::thread::sleep(Duration::from_secs(secs));
        }
        None => {
            println!("Treine no KovaaK's. Ctrl+C para parar e salvar.");
            let (tx, rx) = std::sync::mpsc::channel();
            ctrlc::set_handler(move || {
                let _ = tx.send(());
            })?;
            let _ = rx.recv();
            println!("\nParando...");
        }
    }

    let dir = rec.dir.clone();
    let (stats, screen_stats, copied) = rec.finish(kovaaks_stats)?;

    println!(
        "Capturados {} eventos em {:.1}s ({:.0} Hz médio) | intervalo p50 {:.2}ms p99 {:.2}ms | maior gap {:.1}ms | {} cliques",
        stats.events, stats.duration_s, stats.mean_rate_hz,
        stats.p50_interval_ms, stats.p99_interval_ms, stats.max_gap_ms, stats.left_clicks
    );
    if let Some(ss) = screen_stats {
        println!(
            "Tela: {} frames em {:.1}s ({:.0} fps) @ {}x{}",
            ss.frames, ss.duration_s, ss.mean_fps, ss.width, ss.height
        );
    }
    if copied == 0 {
        println!("[aviso] nenhum CSV novo do KovaaK's encontrado.");
    } else {
        println!("{copied} CSV(s) do KovaaK's copiados para a sessão.");
    }
    println!("Sessão salva: {}", dir.display());
    println!("Analise com: aimscope analyze \"{}\"", dir.display());
    Ok(())
}

fn resolve_session(session: &str, out: Option<PathBuf>) -> Result<PathBuf> {
    if session == "latest" {
        let sessions = session::list_sessions(&base_dir(out))?;
        return sessions.into_iter().next().context("nenhuma sessão encontrada");
    }
    let p = PathBuf::from(session);
    if !p.join("manifest.json").exists() {
        bail!("{} não é um diretório de sessão válido (sem manifest.json)", p.display());
    }
    Ok(p)
}

fn analyze(session: String, out: Option<PathBuf>, python: Option<PathBuf>, open: bool) -> Result<()> {
    let dir = resolve_session(&session, out)?;
    let py = tools::find_python(python);
    println!("Analisando {} (python: {})", dir.display(), py.display());
    tools::run_sidecar(&py, &dir, false)?;

    let report = dir.join("report.html");
    if open && report.exists() {
        tools::open_path(&report)?;
    }
    Ok(())
}

fn list(out: Option<PathBuf>) -> Result<()> {
    let base = base_dir(out);
    let sessions = session::list_sessions(&base)?;
    if sessions.is_empty() {
        println!("Nenhuma sessão em {}", base.display());
        return Ok(());
    }
    for s in sessions {
        let analyzed = s.join("metrics.json").exists();
        let csvs = std::fs::read_dir(&s)?
            .filter_map(|e| e.ok())
            .filter(|e| e.path().extension().and_then(|x| x.to_str()) == Some("csv"))
            .count();
        println!(
            "{}  [{} CSV{}]{}",
            s.display(),
            csvs,
            if csvs == 1 { "" } else { "s" },
            if analyzed { "  (analisada)" } else { "" }
        );
    }
    Ok(())
}
