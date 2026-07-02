//! Utilitários de runtime compartilhados entre o CLI e a UI:
//! localizar o Python do sidecar, rodar a análise, achar/copiar CSVs do KovaaK's.

use anyhow::{bail, Context, Result};
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime};

pub fn default_base_dir() -> PathBuf {
    dirs::data_local_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("aimscope")
        .join("sessions")
}

pub fn kovaaks_stats_dir(explicit: Option<PathBuf>) -> Option<PathBuf> {
    if let Some(p) = explicit {
        return p.exists().then_some(p);
    }
    [
        r"C:\Program Files (x86)\Steam\steamapps\common\FPSAimTrainer\FPSAimTrainer\stats",
        r"C:\Program Files\Steam\steamapps\common\FPSAimTrainer\FPSAimTrainer\stats",
    ]
    .iter()
    .map(PathBuf::from)
    .find(|p| p.exists())
}

/// Copia CSVs modificados desde `since` (margem de 5s) para a sessão.
pub fn copy_new_csvs(stats_dir: &Path, session_dir: &Path, since: SystemTime) -> Result<usize> {
    let cutoff = since.checked_sub(Duration::from_secs(5)).unwrap_or(since);
    let mut n = 0;
    for entry in std::fs::read_dir(stats_dir)? {
        let p = entry?.path();
        if p.extension().and_then(|e| e.to_str()).is_some_and(|e| e.eq_ignore_ascii_case("csv"))
            && p.metadata()?.modified()? >= cutoff
        {
            let dest = session_dir.join(p.file_name().unwrap());
            std::fs::copy(&p, &dest).with_context(|| format!("copiando {}", p.display()))?;
            n += 1;
        }
    }
    Ok(n)
}

/// Localiza o python do sidecar: explícito > AIMSCOPE_PYTHON > venv do repo > "python".
pub fn find_python(explicit: Option<PathBuf>) -> PathBuf {
    if let Some(p) = explicit {
        return p;
    }
    if let Ok(p) = std::env::var("AIMSCOPE_PYTHON") {
        return PathBuf::from(p);
    }
    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        let mut d = exe.parent().map(|p| p.to_path_buf());
        while let Some(dir) = d {
            candidates.push(dir.join("sidecar").join(".venv").join("Scripts").join("python.exe"));
            d = dir.parent().map(|p| p.to_path_buf());
        }
    }
    if let Ok(cwd) = std::env::current_dir() {
        candidates.push(cwd.join("sidecar").join(".venv").join("Scripts").join("python.exe"));
    }
    candidates
        .into_iter()
        .find(|p| p.exists())
        .unwrap_or_else(|| PathBuf::from("python"))
}

/// Roda o sidecar. `quiet`: captura a saída (para UI, sem janela de console);
/// caso contrário herda o stdio (CLI).
pub fn run_sidecar(python: &Path, session_dir: &Path, quiet: bool) -> Result<String> {
    let mut cmd = std::process::Command::new(python);
    cmd.arg("-m").arg("aimscope_sidecar").arg(session_dir);
    if quiet {
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
        }
        let out = cmd.output().with_context(|| format!("executando {}", python.display()))?;
        let text = format!(
            "{}{}",
            String::from_utf8_lossy(&out.stdout),
            String::from_utf8_lossy(&out.stderr)
        );
        if !out.status.success() {
            bail!("sidecar falhou:\n{text}");
        }
        Ok(text)
    } else {
        let status = cmd.status().with_context(|| format!("executando {}", python.display()))?;
        if !status.success() {
            bail!("sidecar Python terminou com erro ({status})");
        }
        Ok(String::new())
    }
}

/// Roda o agente Embabel de narrativa (fat-jar Maven do coach). Exige o jar
/// buildado (`mvn package` em coach/) e Java 21; LLM via NVIDIA_APIKEY (NVIDIA
/// build) — sem chave o agente degrada pro fallback determinístico e ainda
/// entrega.
pub fn run_narrate() -> Result<String> {
    run_agent_jar(&[])
}

/// Interpreta o objetivo em linguagem natural (agente "objective" do fat-jar).
/// A UI grava o texto em profile.json antes; o agente escreve objective.json
/// e atualiza o próprio profile.json (modo/foco/jogo).
pub fn run_objective() -> Result<String> {
    run_agent_jar(&["objective"])
}

fn run_agent_jar(args: &[&str]) -> Result<String> {
    let coach = find_coach_dir().context("coach/ não encontrado")?;
    let jar = coach.join("target").join("aimscope-coach.jar");
    if !jar.exists() {
        bail!(
            "aimscope-coach.jar não encontrado — rode `mvn -q -DskipTests package` em {}",
            coach.display()
        );
    }
    let mut cmd = std::process::Command::new("java");
    cmd.arg("-jar").arg(&jar).args(args).current_dir(&coach);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    let out = cmd.output().context("executando java (JDK 21 instalado?)")?;
    let text = format!(
        "{}{}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr)
    );
    if !out.status.success() {
        bail!("agente de narrativa falhou:\n{text}");
    }
    Ok(text)
}

/// Reset do coach: apaga as sessões gravadas, o user.db e as saídas do coach.
/// Zera o estado APRENDIDO, não as preferências: config.json, profile/objetivo
/// e o cache da API do KovaaK's ficam intactos.
pub fn reset_coach_data() -> Result<()> {
    let base = dirs::data_local_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("aimscope");
    let sessions = default_base_dir();
    if sessions.exists() {
        std::fs::remove_dir_all(&sessions)
            .with_context(|| format!("apagando {}", sessions.display()))?;
    }
    std::fs::create_dir_all(&sessions)?;
    let db = base.join("user.db");
    if db.exists() {
        std::fs::remove_file(&db).with_context(|| format!("apagando {}", db.display()))?;
    }
    let out = coach_out_dir();
    if out.exists() {
        std::fs::remove_dir_all(&out).with_context(|| format!("apagando {}", out.display()))?;
    }
    Ok(())
}

/// Abre um arquivo com o app padrão do Windows (ex.: report.html no navegador).
pub fn open_path(path: &Path) -> Result<()> {
    std::process::Command::new("cmd")
        .args(["/C", "start", ""])
        .arg(path)
        .spawn()?;
    Ok(())
}

/// Localiza o diretório coach/ (deps.edn) subindo a partir do exe e do cwd.
pub fn find_coach_dir() -> Option<PathBuf> {
    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        let mut d = exe.parent().map(|p| p.to_path_buf());
        while let Some(dir) = d {
            candidates.push(dir.join("coach"));
            d = dir.parent().map(|p| p.to_path_buf());
        }
    }
    if let Ok(cwd) = std::env::current_dir() {
        candidates.push(cwd.join("coach"));
    }
    candidates.into_iter().find(|p| p.join("deps.edn").exists())
}

/// Saída do coach (diagnosis.json / plan.json / outcome.json).
pub fn coach_out_dir() -> PathBuf {
    dirs::data_local_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("aimscope")
        .join("coach")
}

/// Roda o coach Clojure (`clojure -M:run <cmd> --sessions <dir>`), silencioso.
/// Best-effort por design: sem Clojure instalado, o produto base segue vivo
/// (ADR 0002: o coach é opcional em runtime).
pub fn run_coach(command: &str, sessions: &Path) -> Result<String> {
    let coach = find_coach_dir()
        .context("diretório coach/ não encontrado (rode a UI a partir do repo)")?;
    // via cmd: o `clojure` do scoop é um shim; cmd resolve PATH normalmente
    let mut cmd = std::process::Command::new("cmd");
    cmd.args(["/C", "clojure", "-M:run", command, "--sessions"])
        .arg(sessions)
        .current_dir(&coach);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    let out = cmd.output().context("executando clojure (instalado?)")?;
    let text = format!(
        "{}{}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr)
    );
    if !out.status.success() {
        bail!("coach falhou:\n{text}");
    }
    Ok(text)
}
