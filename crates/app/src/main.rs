//! aimscope-ui — interface desktop simples (egui) para gravar e analisar sessões.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use anyhow::Result;
use capture::recorder::{self, RecordParams, Recording};
use eframe::egui;
use serde::{Deserialize, Serialize};
use session::tools;
use std::path::PathBuf;
use std::sync::mpsc::{Receiver, TryRecvError};
use std::time::Instant;

fn main() -> eframe::Result {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([720.0, 620.0])
            .with_min_inner_size([560.0, 480.0]),
        ..Default::default()
    };
    eframe::run_native(
        "aimscope",
        options,
        Box::new(|cc| {
            cc.egui_ctx.set_pixels_per_point(1.15);
            Ok(Box::new(App::new()))
        }),
    )
}

fn default_true() -> bool {
    true
}

#[derive(Serialize, Deserialize, Clone)]
struct Config {
    dpi: String,
    sens: String,
    scale: String,
    notes: String,
    #[serde(default = "default_true")]
    screen: bool,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            dpi: "800".into(),
            sens: "0.4".into(),
            scale: "valorant".into(),
            notes: String::new(),
            screen: true,
        }
    }
}

fn config_path() -> PathBuf {
    tools::default_base_dir()
        .parent()
        .map(|p| p.to_path_buf())
        .unwrap_or_default()
        .join("config.json")
}

struct SessionRow {
    dir: PathBuf,
    name: String,
    csvs: usize,
    analyzed: bool,
}

/// Saídas do coach (JSON) carregadas de %LOCALAPPDATA%\aimscope\coach.
#[derive(Default)]
struct CoachView {
    diagnosis: Option<serde_json::Value>,
    plan: Option<serde_json::Value>,
    outcome: Option<serde_json::Value>,
    available: bool, // coach/ + clojure existem?
}

fn load_coach_view() -> CoachView {
    let d = tools::coach_out_dir();
    let load = |name: &str| -> Option<serde_json::Value> {
        std::fs::read_to_string(d.join(name))
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
    };
    CoachView {
        diagnosis: load("diagnosis.json"),
        plan: load("plan.json"),
        outcome: load("outcome.json"),
        available: tools::find_coach_dir().is_some(),
    }
}

/// Perfil de objetivo — MESMO arquivo que o coach lê (profile.json).
#[derive(Clone)]
struct ProfileUi {
    goal: String,   // fixed-sens | sens-range | game-transfer
    target: String, // ex.: com/bounce-180
    budget: String, // min/dia
}

impl Default for ProfileUi {
    fn default() -> Self {
        ProfileUi {
            goal: "fixed-sens".into(),
            target: "com/bounce-180".into(),
            budget: "45".into(),
        }
    }
}

fn profile_path() -> PathBuf {
    tools::coach_out_dir()
        .parent()
        .map(|p| p.to_path_buf())
        .unwrap_or_default()
        .join("profile.json")
}

fn load_profile_ui() -> ProfileUi {
    let mut p = ProfileUi::default();
    if let Ok(s) = std::fs::read_to_string(profile_path()) {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&s) {
            if let Some(g) = v.get("player/goal").and_then(|x| x.as_str()) {
                p.goal = g.into();
            }
            if let Some(t) = v.pointer("/player~1target/scenario").and_then(|x| x.as_str()) {
                p.target = t.into();
            }
            if let Some(b) = v.get("player/time-budget-min").and_then(|x| x.as_f64()) {
                p.budget = format!("{}", b as i64);
            }
        }
    }
    p
}

fn save_profile_ui(p: &ProfileUi, cfg: &Config) -> anyhow::Result<()> {
    let sens: f64 = cfg.sens.trim().replace(',', ".").parse().unwrap_or(0.4);
    let dpi: f64 = cfg.dpi.trim().parse().unwrap_or(800.0);
    let v = serde_json::json!({
        "player/goal": p.goal,
        "player/sens": {"value": sens, "scale": cfg.scale, "dpi": dpi},
        "player/sens-range": serde_json::Value::Null,
        "player/target": {"benchmark": "voltaic-s5", "scenario": p.target},
        "player/time-budget-min": p.budget.trim().parse::<i64>().unwrap_or(45),
    });
    let f = profile_path();
    if let Some(dir) = f.parent() {
        std::fs::create_dir_all(dir)?;
    }
    std::fs::write(f, serde_json::to_string_pretty(&v)?)?;
    Ok(())
}

enum State {
    Idle,
    Recording {
        rec: Option<Recording>,
        started: Instant,
        last_events: u64,
        last_at: Instant,
        rate_hz: f64,
    },
    Busy {
        what: String,
        rx: Receiver<Result<String>>,
    },
}

struct App {
    cfg: Config,
    state: State,
    sessions: Vec<SessionRow>,
    status: String,
    error: Option<String>,
    coach: CoachView,
    prof: ProfileUi,
}

impl App {
    fn new() -> Self {
        let cfg = std::fs::read_to_string(config_path())
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default();
        let mut app = App {
            cfg,
            state: State::Idle,
            sessions: Vec::new(),
            status: "Pronto. Configure e clique em Iniciar gravação.".into(),
            error: None,
            coach: load_coach_view(),
            prof: load_profile_ui(),
        };
        app.refresh_sessions();
        app
    }

    fn save_config(&self) {
        if let Some(dir) = config_path().parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        let _ = std::fs::write(
            config_path(),
            serde_json::to_string_pretty(&self.cfg).unwrap_or_default(),
        );
    }

    fn refresh_sessions(&mut self) {
        self.sessions.clear();
        if let Ok(dirs) = session::list_sessions(&tools::default_base_dir()) {
            for d in dirs.into_iter().take(30) {
                let csvs = std::fs::read_dir(&d)
                    .map(|it| {
                        it.filter_map(|e| e.ok())
                            .filter(|e| {
                                e.path().extension().and_then(|x| x.to_str()) == Some("csv")
                            })
                            .count()
                    })
                    .unwrap_or(0);
                self.sessions.push(SessionRow {
                    name: d
                        .file_name()
                        .map(|n| n.to_string_lossy().into_owned())
                        .unwrap_or_default(),
                    analyzed: d.join("metrics.json").exists(),
                    csvs,
                    dir: d,
                });
            }
        }
    }

    fn params(&self) -> RecordParams {
        RecordParams {
            dpi: self.cfg.dpi.trim().replace(',', ".").parse().ok(),
            sens: self.cfg.sens.trim().replace(',', ".").parse().ok(),
            sens_scale: self.cfg.scale.clone(),
            notes: (!self.cfg.notes.trim().is_empty()).then(|| self.cfg.notes.trim().to_string()),
            screen: self.cfg.screen,
        }
    }

    fn start_recording(&mut self) {
        self.error = None;
        self.save_config();
        match recorder::begin(&tools::default_base_dir(), &self.params()) {
            Ok(rec) => {
                self.status = match &rec.screen_warning {
                    Some(w) => format!("Gravando (SEM tela: {w})"),
                    None => format!("Gravando em {}", rec.dir.display()),
                };
                self.state = State::Recording {
                    rec: Some(rec),
                    started: Instant::now(),
                    last_events: 0,
                    last_at: Instant::now(),
                    rate_hz: 0.0,
                };
            }
            Err(e) => self.error = Some(format!("Falha ao iniciar captura: {e:#}")),
        }
    }

    fn stop_and_analyze(&mut self, rec: Recording) {
        let (tx, rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let result = (|| -> Result<String> {
                let dir = rec.dir.clone();
                let (stats, screen_stats, copied) = rec.finish(None)?;
                let screen_note = match screen_stats {
                    Some(ss) => format!("{} frames de tela ({:.0} fps).", ss.frames, ss.mean_fps),
                    None => String::new(),
                };
                let py = tools::find_python(None);
                tools::run_sidecar(&py, &dir, true)?;
                let report = dir.join("report.html");
                if report.exists() {
                    let _ = tools::open_path(&report);
                }
                // coach best-effort: sem Clojure o produto base segue vivo
                let coach_note = match tools::run_coach("all", &tools::default_base_dir()) {
                    Ok(_) => "Coach atualizado.",
                    Err(_) => "(coach indisponível — instale Clojure p/ diagnóstico e plano)",
                };
                Ok(format!(
                    "Sessão analisada: {} eventos ({:.0} Hz), {} clique(s), {} CSV(s). {} Relatório no navegador. {}",
                    stats.events, stats.mean_rate_hz, stats.left_clicks, copied, screen_note, coach_note
                ))
            })();
            let _ = tx.send(result);
        });
        self.state = State::Busy {
            what: "Finalizando: análise + coach...".into(),
            rx,
        };
    }

    fn analyze_existing(&mut self, dir: PathBuf) {
        let (tx, rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let result = (|| -> Result<String> {
                let py = tools::find_python(None);
                tools::run_sidecar(&py, &dir, true)?;
                let report = dir.join("report.html");
                if report.exists() {
                    let _ = tools::open_path(&report);
                }
                let _ = tools::run_coach("all", &tools::default_base_dir());
                Ok("Análise concluída; relatório aberto no navegador.".into())
            })();
            let _ = tx.send(result);
        });
        self.state = State::Busy {
            what: "Analisando sessão...".into(),
            rx,
        };
    }

    fn run_coach_job(&mut self, command: &'static str, label: &str) {
        let (tx, rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let result = if command == "__narrate__" {
                tools::run_narrate().map(|_| {
                    let md = tools::coach_out_dir().join("narrative.md");
                    if md.exists() {
                        let _ = tools::open_path(&md);
                    }
                    "Narrativa gerada e aberta.".to_string()
                })
            } else {
                tools::run_coach(command, &tools::default_base_dir())
                    .map(|_| "Coach atualizado.".to_string())
            };
            let _ = tx.send(result);
        });
        self.state = State::Busy { what: label.into(), rx };
    }
}

/// Barra 0-100 com rótulo — usada no ranking de skills.
fn skill_bar(ui: &mut egui::Ui, name: &str, value: f64) {
    ui.horizontal(|ui| {
        ui.add_sized([170.0, 16.0], egui::Label::new(egui::RichText::new(name).small()));
        let bar = egui::ProgressBar::new((value / 100.0) as f32)
            .text(format!("{value:.0}"))
            .desired_width(180.0);
        ui.add(bar);
    });
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        // Transições de estado fora do closure de desenho.
        let mut next_state: Option<State> = None;

        match &mut self.state {
            State::Busy { rx, .. } => match rx.try_recv() {
                Ok(Ok(msg)) => {
                    self.status = msg;
                    self.error = None;
                    next_state = Some(State::Idle);
                }
                Ok(Err(e)) => {
                    self.error = Some(format!("{e:#}"));
                    self.status = "Falhou.".into();
                    next_state = Some(State::Idle);
                }
                Err(TryRecvError::Empty) => {
                    ctx.request_repaint_after(std::time::Duration::from_millis(150));
                }
                Err(TryRecvError::Disconnected) => {
                    self.error = Some("tarefa de análise morreu sem resposta".into());
                    next_state = Some(State::Idle);
                }
            },
            State::Recording { rec, last_events, last_at, rate_hz, .. } => {
                if let Some(r) = rec {
                    let ev = r.live().events.load(std::sync::atomic::Ordering::Relaxed);
                    let dt = last_at.elapsed().as_secs_f64();
                    if dt >= 0.5 {
                        *rate_hz = (ev.saturating_sub(*last_events)) as f64 / dt;
                        *last_events = ev;
                        *last_at = Instant::now();
                    }
                }
                ctx.request_repaint_after(std::time::Duration::from_millis(200));
            }
            State::Idle => {}
        }
        if let Some(s) = next_state {
            self.state = s;
            self.refresh_sessions();
            self.coach = load_coach_view();
        }

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.heading("aimscope");
            ui.label(
                egui::RichText::new("análise de mecânica de mira — KovaaK's")
                    .weak()
                    .small(),
            );
            ui.add_space(8.0);

            if let Some(err) = &self.error {
                ui.colored_label(egui::Color32::from_rgb(200, 60, 50), err);
                ui.add_space(4.0);
            }

            match &mut self.state {
                State::Idle => {
                    egui::Grid::new("cfg")
                        .num_columns(2)
                        .spacing([12.0, 8.0])
                        .show(ui, |ui| {
                            ui.label("DPI do mouse");
                            ui.add(egui::TextEdit::singleline(&mut self.cfg.dpi).desired_width(90.0));
                            ui.end_row();

                            ui.label("Sens in-game (a SUA sens)");
                            ui.add(egui::TextEdit::singleline(&mut self.cfg.sens).desired_width(90.0));
                            ui.end_row();

                            ui.label("Escala da sens");
                            egui::ComboBox::from_id_salt("scale")
                                .selected_text(&self.cfg.scale)
                                .show_ui(ui, |ui| {
                                    ui.selectable_value(&mut self.cfg.scale, "valorant".into(), "Valorant");
                                    ui.selectable_value(&mut self.cfg.scale, "cs".into(), "CS2 / Source");
                                    ui.selectable_value(&mut self.cfg.scale, "overwatch".into(), "Overwatch");
                                });
                            ui.end_row();

                            ui.label("Notas da sessão");
                            ui.add(
                                egui::TextEdit::singleline(&mut self.cfg.notes)
                                    .hint_text("ex.: descansado, pós-café")
                                    .desired_width(220.0),
                            );
                            ui.end_row();

                            ui.label("Captura de tela");
                            ui.checkbox(
                                &mut self.cfg.screen,
                                "reaction time (jogo em borderless!)",
                            );
                            ui.end_row();
                        });

                    if let (Ok(dpi), Ok(sens)) = (
                        self.cfg.dpi.trim().parse::<f64>(),
                        self.cfg.sens.trim().replace(',', ".").parse::<f64>(),
                    ) {
                        if let Some(dpc) = session::deg_per_count(&self.cfg.scale, sens) {
                            ui.label(
                                egui::RichText::new(format!(
                                    "cm/360: {:.1} cm", session::cm_per_360(dpc, dpi)
                                ))
                                .weak(),
                            );
                        }
                    }

                    ui.add_space(10.0);
                    let btn = egui::Button::new(
                        egui::RichText::new("▶  Iniciar gravação").size(18.0),
                    )
                    .min_size(egui::vec2(220.0, 40.0));
                    if ui.add(btn).clicked() {
                        self.start_recording();
                    }
                    ui.label(
                        egui::RichText::new(&self.status).weak().small(),
                    );

                    ui.add_space(8.0);
                    ui.separator();

                    // ---------------- Perfil de objetivo ----------------
                    let mut save_prof = false;
                    egui::CollapsingHeader::new("🎯 Perfil de objetivo")
                        .default_open(false)
                        .show(ui, |ui| {
                            ui.horizontal(|ui| {
                                ui.label("Objetivo:");
                                egui::ComboBox::from_id_salt("goal")
                                    .selected_text(match self.prof.goal.as_str() {
                                        "sens-range" => "Melhorar overall (sens livre)",
                                        "game-transfer" => "Transferir pro Valorant",
                                        _ => "Dominar MINHA sens (fixa)",
                                    })
                                    .show_ui(ui, |ui| {
                                        ui.selectable_value(&mut self.prof.goal, "fixed-sens".into(),
                                            "Dominar MINHA sens (fixa) — coach nunca sugere mudar sens");
                                        ui.selectable_value(&mut self.prof.goal, "sens-range".into(),
                                            "Melhorar overall — mudança de sens permitida (com custo)");
                                        ui.selectable_value(&mut self.prof.goal, "game-transfer".into(),
                                            "Transferir pro Valorant — prioriza skills que transferem");
                                    });
                            });
                            ui.horizontal(|ui| {
                                ui.label("Cenário-alvo:");
                                ui.add(egui::TextEdit::singleline(&mut self.prof.target)
                                    .hint_text("ex.: com/bounce-180")
                                    .desired_width(180.0));
                                ui.label("Minutos/dia:");
                                ui.add(egui::TextEdit::singleline(&mut self.prof.budget)
                                    .desired_width(50.0));
                            });
                            if ui.button("Salvar perfil").clicked() {
                                save_prof = true;
                            }
                        });
                    if save_prof {
                        match save_profile_ui(&self.prof, &self.cfg) {
                            Ok(_) => self.status = "Perfil salvo. Rode o coach para replanejar.".into(),
                            Err(e) => self.error = Some(format!("salvando perfil: {e:#}")),
                        }
                    }

                    // ---------------- Coach ----------------
                    let mut coach_cmd: Option<(&'static str, &'static str)> = None;
                    egui::CollapsingHeader::new("🧠 Coach")
                        .default_open(self.coach.diagnosis.is_some())
                        .show(ui, |ui| {
                            if !self.coach.available {
                                ui.label(egui::RichText::new(
                                    "coach/ não encontrado ou Clojure não instalado — \
                                     o diagnóstico/plano fica desabilitado (o resto funciona)."
                                ).weak());
                                return;
                            }
                            if let Some(d) = &self.coach.diagnosis {
                                if let Some(g) = d.get("gargalo-global").and_then(|g| g.get("skill")).and_then(|s| s.as_str()) {
                                    ui.label(egui::RichText::new(format!("Gargalo: {g}"))
                                        .color(egui::Color32::from_rgb(200, 90, 40)).strong());
                                }
                                if let Some(ranked) = d.get("skills/ranked").and_then(|r| r.as_array()) {
                                    for s in ranked.iter().take(6) {
                                        if let (Some(name), Some(v)) = (
                                            s.get("skill").and_then(|x| x.as_str()),
                                            s.get("value").and_then(|x| x.as_f64()),
                                        ) {
                                            skill_bar(ui, name, v);
                                        }
                                    }
                                }
                                // percentis globais (comando enrich)
                                if let Some(pcts) = d.get("percentiles").and_then(|p| p.as_array()) {
                                    for p in pcts.iter().take(4) {
                                        if let (Some(scen), Some(top)) = (
                                            p.get("scenario").and_then(|x| x.as_str()),
                                            p.get("top-pct").and_then(|x| x.as_f64()),
                                        ) {
                                            ui.label(egui::RichText::new(format!(
                                                "🌍 {scen}: top {top:.1}% global"
                                            )).small());
                                        }
                                    }
                                }
                                // tendência das skills (snapshots do diagnose)
                                if let Some(hist) = d.get("history").and_then(|h| h.as_array()) {
                                    if hist.len() >= 2 {
                                        let mut series: std::collections::BTreeMap<String, Vec<[f64; 2]>> =
                                            Default::default();
                                        for (i, snap) in hist.iter().enumerate() {
                                            if let Some(sk) = snap.get("skills").and_then(|s| s.as_object()) {
                                                for (name, v) in sk {
                                                    if let Some(val) = v.as_f64() {
                                                        series.entry(name.clone()).or_default()
                                                            .push([i as f64, val]);
                                                    }
                                                }
                                            }
                                        }
                                        ui.add_space(4.0);
                                        egui_plot::Plot::new("skills_hist")
                                            .height(150.0)
                                            .include_y(0.0)
                                            .include_y(100.0)
                                            .legend(egui_plot::Legend::default())
                                            .show(ui, |plot_ui| {
                                                for (name, pts) in &series {
                                                    plot_ui.line(
                                                        egui_plot::Line::new(
                                                            egui_plot::PlotPoints::from(pts.clone()),
                                                        )
                                                        .name(name),
                                                    );
                                                }
                                            });
                                    }
                                }
                                if let Some(sem) = d.get("skills/sem-evidencia").and_then(|r| r.as_array()) {
                                    if !sem.is_empty() {
                                        ui.label(egui::RichText::new(format!(
                                            "sem evidência ainda: {} (chegam com a captura de tela)",
                                            sem.iter().filter_map(|x| x.as_str()).collect::<Vec<_>>().join(", ")
                                        )).weak().small());
                                    }
                                }
                            } else {
                                ui.label(egui::RichText::new("sem diagnóstico ainda — grave sessões e rode o coach").weak());
                            }

                            if let Some(p) = &self.coach.plan {
                                ui.add_space(6.0);
                                let target = p.get("target").and_then(|x| x.as_str()).unwrap_or("?");
                                match p.get("status").and_then(|x| x.as_str()) {
                                    Some("ok") => {
                                        ui.label(egui::RichText::new(format!("Plano para destravar {target}:")).strong());
                                        if let Some(steps) = p.get("steps").and_then(|s| s.as_array()) {
                                            for st in steps {
                                                let scen = st.get("scenario").and_then(|x| x.as_str()).unwrap_or("?");
                                                let skill = st.get("skill").and_then(|x| x.as_str()).unwrap_or("?");
                                                let min = st.get("minutes").and_then(|x| x.as_f64()).unwrap_or(15.0);
                                                let delta = st.get("expected-delta").and_then(|x| x.as_str()).unwrap_or("");
                                                ui.label(format!("  ▸ {min:.0} min de {scen} → {skill} {delta}"));
                                            }
                                        }
                                    }
                                    Some("ja-destravado") => {
                                        ui.label(format!("✅ {target}: suas skills já estão acima do gate — jogue o cenário!"));
                                    }
                                    _ => { ui.label(egui::RichText::new("sem plano viável com as skills atuais").weak()); }
                                }
                            }

                            if let Some(o) = &self.coach.outcome {
                                if let Some(rec) = o.get("recomendacao").and_then(|x| x.as_str()) {
                                    ui.add_space(4.0);
                                    ui.label(egui::RichText::new(format!("Previsto×realizado: {rec}")).italics().small());
                                }
                            }

                            ui.add_space(6.0);
                            ui.horizontal(|ui| {
                                if ui.button("🔄 Rodar coach").clicked() {
                                    coach_cmd = Some(("all", "Coach: ingerindo, diagnosticando e planejando..."));
                                }
                                if ui.button("📋 Conferir previsão").clicked() {
                                    coach_cmd = Some(("outcome", "Coach: conferindo previsto × realizado..."));
                                }
                                if ui.button("🤖 Narrativa (IA)").clicked() {
                                    coach_cmd = Some(("__narrate__", "Agente Embabel: gerando narrativa via OpenRouter..."));
                                }
                            });
                            let narrative = tools::coach_out_dir().join("narrative.md");
                            if narrative.exists() {
                                if ui.small_button("abrir última narrativa").clicked() {
                                    let _ = tools::open_path(&narrative);
                                }
                            }
                        });
                    if let Some((cmd, label)) = coach_cmd {
                        self.run_coach_job(cmd, label);
                    }

                    ui.add_space(8.0);
                    ui.separator();
                    ui.add_space(4.0);
                    ui.horizontal(|ui| {
                        ui.label(egui::RichText::new("Sessões").strong());
                        if ui.small_button("atualizar").clicked() {
                            self.refresh_sessions();
                        }
                    });

                    let mut to_analyze: Option<PathBuf> = None;
                    let mut to_open: Option<PathBuf> = None;
                    egui::ScrollArea::vertical().show(ui, |ui| {
                        for row in &self.sessions {
                            ui.horizontal(|ui| {
                                ui.label(&row.name);
                                ui.label(
                                    egui::RichText::new(format!("{} CSV", row.csvs)).weak().small(),
                                );
                                if row.analyzed {
                                    if ui.small_button("Relatório").clicked() {
                                        to_open = Some(row.dir.join("report.html"));
                                    }
                                    if ui.small_button("Reanalisar").clicked() {
                                        to_analyze = Some(row.dir.clone());
                                    }
                                } else if ui.small_button("Analisar").clicked() {
                                    to_analyze = Some(row.dir.clone());
                                }
                            });
                        }
                        if self.sessions.is_empty() {
                            ui.label(egui::RichText::new("nenhuma sessão ainda").weak());
                        }
                    });
                    if let Some(p) = to_open {
                        let _ = tools::open_path(&p);
                    }
                    if let Some(d) = to_analyze {
                        self.analyze_existing(d);
                    }
                }

                State::Recording { rec, started, rate_hz, .. } => {
                    let elapsed = started.elapsed().as_secs();
                    let (ev, clicks) = rec
                        .as_ref()
                        .map(|r| {
                            let l = r.live();
                            (
                                l.events.load(std::sync::atomic::Ordering::Relaxed),
                                l.left_clicks.load(std::sync::atomic::Ordering::Relaxed),
                            )
                        })
                        .unwrap_or((0, 0));

                    ui.add_space(10.0);
                    ui.label(
                        egui::RichText::new("● GRAVANDO")
                            .color(egui::Color32::from_rgb(210, 60, 50))
                            .size(20.0)
                            .strong(),
                    );
                    ui.add_space(6.0);
                    let screen_txt = rec
                        .as_ref()
                        .and_then(|r| r.live_screen_frames())
                        .map(|f| format!("  |  {f} frames de tela"))
                        .unwrap_or_default();
                    ui.label(format!(
                        "{:02}:{:02}  |  {} eventos  |  {:.0} Hz agora  |  {} cliques{}",
                        elapsed / 60, elapsed % 60, ev, rate_hz, clicks, screen_txt
                    ));
                    ui.label(
                        egui::RichText::new(
                            "Treine no KovaaK's. Ao terminar o cenário, volte aqui e pare.",
                        )
                        .weak(),
                    );
                    ui.add_space(10.0);

                    let btn = egui::Button::new(
                        egui::RichText::new("■  Parar e analisar").size(18.0),
                    )
                    .min_size(egui::vec2(220.0, 40.0));
                    if ui.add(btn).clicked() {
                        if let Some(r) = rec.take() {
                            self.stop_and_analyze(r);
                        }
                    }
                }

                State::Busy { what, .. } => {
                    ui.add_space(20.0);
                    ui.horizontal(|ui| {
                        ui.add(egui::Spinner::new().size(22.0));
                        ui.label(what.as_str());
                    });
                }
            }
        });
    }
}
