//! aimscope-ui — desktop (egui). Layout: painel lateral (gravação + sessões)
//! e dashboard central do coach. Tema/marcas: theme.rs (paleta validada do
//! design system; texto em tinta, série colorida só nas marcas).
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod chat;
mod theme;

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
            .with_inner_size([1010.0, 660.0])
            .with_min_inner_size([840.0, 560.0]),
        ..Default::default()
    };
    eframe::run_native(
        "aimscope",
        options,
        Box::new(|cc| {
            cc.egui_ctx.set_pixels_per_point(1.15);
            theme::apply(&cc.egui_ctx);
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
    benchmarks: Option<serde_json::Value>, // benchmarks.json (API kovaaks.com)
    narrative: Option<String>,             // narrative.md — exibida DENTRO do app
    available: bool,                       // coach/ + clojure existem?
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
        benchmarks: load("benchmarks.json"),
        narrative: std::fs::read_to_string(d.join("narrative.md")).ok(),
        available: tools::find_coach_dir().is_some(),
    }
}

/// Pontuação/threshold no formato da planilha do bench: 8271 -> "8.271".
fn fmt_score(v: f64) -> String {
    let n = v.round() as i64;
    let s = n.abs().to_string();
    let mut out = String::new();
    for (i, ch) in s.chars().enumerate() {
        if i > 0 && (s.len() - i) % 3 == 0 {
            out.push('.');
        }
        out.push(ch);
    }
    if n < 0 { format!("-{out}") } else { out }
}

/// "#ffaaaa" -> Color32; branco/vazio vira MUTED (cabeçalho neutro no dark).
fn parse_hex_color(s: &str) -> egui::Color32 {
    let h = s.trim().trim_start_matches('#');
    if h.len() == 6 {
        if let (Ok(r), Ok(g), Ok(b)) = (
            u8::from_str_radix(&h[0..2], 16),
            u8::from_str_radix(&h[2..4], 16),
            u8::from_str_radix(&h[4..6], 16),
        ) {
            if !(r > 0xf0 && g > 0xf0 && b > 0xf0) {
                return egui::Color32::from_rgb(r, g, b);
            }
        }
    }
    theme::MUTED
}

/// Markdown minimalista da narrativa -> egui (títulos, bullets, texto).
fn narrative_md(ui: &mut egui::Ui, md: &str) {
    for line in md.lines() {
        let clean = line.replace("**", "");
        if let Some(h) = clean.strip_prefix("## ") {
            ui.add_space(6.0);
            ui.label(egui::RichText::new(h).color(theme::INK).strong().size(14.0));
        } else if let Some(b) = clean.strip_prefix("- ") {
            ui.horizontal_wrapped(|ui| {
                ui.label("•");
                ui.label(b.to_string());
            });
        } else if !clean.trim().is_empty() {
            ui.horizontal_wrapped(|ui| {
                ui.label(clean.trim().to_string());
            });
        }
    }
}

/// Perfil de objetivo — MESMO arquivo que o coach lê (profile.json).
/// ADR 0005: eixos ortogonais (política de sens × alvo de jogo × foco).
#[derive(Clone)]
struct ProfileUi {
    sens_policy: String, // fixed | range | search
    game_target: String, // kovaaks | transfer
    sens_min: String,    // cm/360 (só política range)
    sens_max: String,    // cm/360 (só política range)
    budget: String,      // min/dia
    objective: String,   // texto livre; o agente interpreta -> objective.json
    steam: String,       // conta Steam (link/ID64/vanity) — porta de entrada dos benchmarks
}

impl Default for ProfileUi {
    fn default() -> Self {
        ProfileUi {
            sens_policy: "fixed".into(),
            game_target: "kovaaks".into(),
            sens_min: String::new(),
            sens_max: String::new(),
            budget: "45".into(),
            objective: String::new(),
            steam: String::new(),
        }
    }
}

/// Resumo humano do objetivo interpretado (objective.json), se existir.
fn load_objective_summary() -> Option<String> {
    let f = tools::coach_out_dir().parent()?.join("objective.json");
    let v: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(f).ok()?).ok()?;
    v.get("resumo-humano").and_then(|x| x.as_str()).map(|s| s.to_string())
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
            if let Some(g) = v.get("player/sens-policy").and_then(|x| x.as_str()) {
                p.sens_policy = g.into();
            } else if let Some(g) = v.get("player/goal").and_then(|x| x.as_str()) {
                // legado pré-eixos (ADR 0005)
                p.sens_policy = match g {
                    "sens-range" => "range".into(),
                    _ => "fixed".into(),
                };
                if g == "game-transfer" {
                    p.game_target = "transfer".into();
                }
            }
            if let Some(t) = v.get("player/game-target").and_then(|x| x.as_str()) {
                p.game_target = t.into();
            }
            if let Some(r) = v.get("player/sens-range").and_then(|x| x.as_array()) {
                if let (Some(a), Some(b)) = (r.first().and_then(|x| x.as_f64()),
                                             r.get(1).and_then(|x| x.as_f64())) {
                    p.sens_min = format!("{a:.0}");
                    p.sens_max = format!("{b:.0}");
                }
            }
            if let Some(u) = v.get("player/steam")
                .or_else(|| v.get("player/kovaaks-username"))
                .and_then(|x| x.as_str())
            {
                p.steam = u.into();
            }
            if let Some(b) = v.get("player/time-budget-min").and_then(|x| x.as_f64()) {
                p.budget = format!("{}", b as i64);
            }
            if let Some(o) = v.get("player/objective-text").and_then(|x| x.as_str()) {
                p.objective = o.into();
            }
        }
    }
    p
}

fn save_profile_ui(p: &ProfileUi, cfg: &Config) -> anyhow::Result<()> {
    let sens: f64 = cfg.sens.trim().replace(',', ".").parse().unwrap_or(0.4);
    let dpi: f64 = cfg.dpi.trim().parse().unwrap_or(800.0);
    let f = profile_path();
    // MERGE sobre o existente: o agente de objetivo grava campos próprios
    // (player/focus-categories, player/game) que a UI não pode clobberar
    let mut v: serde_json::Value = std::fs::read_to_string(&f)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_else(|| serde_json::json!({}));
    let o = v.as_object_mut().expect("profile.json raiz é objeto");
    o.insert("player/sens-policy".into(), serde_json::json!(p.sens_policy));
    o.insert("player/game-target".into(), serde_json::json!(p.game_target));
    o.remove("player/goal"); // campo único pré-eixos (ADR 0005)
    let range = (p.sens_min.trim().parse::<f64>().ok())
        .zip(p.sens_max.trim().parse::<f64>().ok())
        .filter(|(a, b)| p.sens_policy == "range" && *a > 0.0 && *b >= *a);
    o.insert("player/sens-range".into(),
        match range { Some((a, b)) => serde_json::json!([a, b]), None => serde_json::json!(null) });
    o.insert("player/sens".into(),
        serde_json::json!({"value": sens, "scale": cfg.scale, "dpi": dpi}));
    // player/target saiu da UI (QA 2026-07-02): o plano mira a skill mais
    // fraca automaticamente; valor legado no arquivo fica como fallback
    o.insert("player/time-budget-min".into(),
        serde_json::json!(p.budget.trim().parse::<i64>().unwrap_or(45)));
    o.insert("player/objective-text".into(), serde_json::json!(p.objective.trim()));
    o.insert("player/steam".into(), serde_json::json!(p.steam.trim()));
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

#[derive(PartialEq, Clone, Copy)]
enum Tab {
    Coach,
    Benchmarks,
}

struct App {
    cfg: Config,
    state: State,
    sessions: Vec<SessionRow>,
    status: String,
    error: Option<String>,
    coach: CoachView,
    prof: ProfileUi,
    confirm_reset: bool,
    tab: Tab,
    // ---- chat do coach (LLM) ----
    chat_open: bool,
    chat_msgs: Vec<chat::ChatMsg>,
    chat_input: String,
    chat_topic: Option<String>, // contexto do "conversar sobre isso"
    chat_rx: Option<Receiver<Result<String>>>,
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
            status: "Pronto. Configure e clique em Gravar sessão.".into(),
            error: None,
            coach: load_coach_view(),
            prof: load_profile_ui(),
            confirm_reset: false,
            tab: Tab::Coach,
            chat_open: false,
            chat_msgs: Vec::new(),
            chat_input: String::new(),
            chat_topic: None,
            chat_rx: None,
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
                // a narrativa aparece DENTRO do app; nada de editor externo
                tools::run_narrate().map(|_| "Explicação atualizada no painel.".to_string())
            } else if command == "__objective__" {
                tools::run_objective()
                    .map(|_| "Objetivo interpretado — atualize o diagnóstico para replanejar.".to_string())
            } else {
                tools::run_coach(command, &tools::default_base_dir())
                    .map(|_| "Coach atualizado.".to_string())
            };
            let _ = tx.send(result);
        });
        self.state = State::Busy { what: label.into(), rx };
    }

    // ------------------------------------------------------------ benchmarks
    fn draw_benchmarks(&mut self, ui: &mut egui::Ui) {
        let mut refresh = false;
        let mut talk_about: Option<String> = None;

        ui.horizontal(|ui| {
            ui.label(egui::RichText::new("Benchmarks").color(theme::INK).size(18.0).strong());
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if ui.button("🔄 Atualizar pontuações")
                    .on_hover_text("busca suas pontuações oficiais no kovaaks.com")
                    .clicked()
                {
                    refresh = true;
                }
                ui.add(
                    egui::TextEdit::singleline(&mut self.prof.steam)
                        .hint_text("link do seu perfil Steam")
                        .desired_width(210.0),
                ).on_hover_text(
                    "cole o link do seu perfil Steam (steamcommunity.com/...), \
                     seu SteamID64 ou vanity name — é por ele que buscamos suas \
                     pontuações nos benchmarks",
                );
            });
        });
        ui.add_space(6.0);

        let Some(b) = self.coach.benchmarks.clone() else {
            theme::card().show(ui, |ui| {
                ui.label(
                    "Sem pontuações ainda — informe seu usuário do kovaaks.com \
                     acima e clique em Atualizar pontuações.",
                );
            });
            if refresh {
                self.refresh_benchmarks();
            }
            return;
        };

        if let Some(erro) = b.get("erro").and_then(|x| x.as_str()) {
            theme::card().show(ui, |ui| {
                ui.label(egui::RichText::new(erro).color(theme::SERIOUS));
            });
        }

        egui::ScrollArea::vertical().show(ui, |ui| {
            // ---- guia: o plano em uma olhada -----------------------------
            if let Some(guia) = b.get("guia").and_then(|g| g.as_array()) {
                if !guia.is_empty() {
                    theme::card().show(ui, |ui| {
                        theme::section_title(ui, "Guia — onde atacar primeiro");
                        for g in guia.iter().take(4) {
                            let label = g.get("label").and_then(|x| x.as_str()).unwrap_or("?");
                            let nivel = g.get("nivel-medio").and_then(|x| x.as_f64()).unwrap_or(0.0);
                            let mapas = g.get("mapas").and_then(|m| m.as_array())
                                .map(|a| a.iter().filter_map(|x| x.as_str())
                                    .collect::<Vec<_>>().join(", "))
                                .unwrap_or_default();
                            ui.horizontal_wrapped(|ui| {
                                ui.label(egui::RichText::new(format!("{label} (nível {nivel:.1})"))
                                    .color(theme::INK_2).strong().small());
                                ui.label(egui::RichText::new(format!("→ {mapas}"))
                                    .color(theme::MUTED).small());
                            });
                        }
                    });
                    ui.add_space(8.0);
                }
            }

            // ---- benchmarks: categorias -> cenários ----------------------
            if let Some(benches) = b.get("benchmarks").and_then(|x| x.as_array()) {
                for bm in benches {
                    let nome = bm.get("nome").and_then(|x| x.as_str()).unwrap_or("?");
                    let titulo = match bm.get("overall-rank-name").and_then(|x| x.as_str()) {
                        Some(r) if r != "No Rank" => format!("{nome}   —   {r}"),
                        _ => nome.to_string(),
                    };
                    egui::CollapsingHeader::new(
                        egui::RichText::new(titulo).color(theme::INK).strong(),
                    )
                    .default_open(false)
                    .show(ui, |ui| {
                        let Some(cats) = bm.get("categories").and_then(|x| x.as_array()) else {
                            return;
                        };
                        // colunas da planilha do bench: nomes/cores oficiais dos ranks
                        let rank_names: Vec<String> = bm.get("rank-names")
                            .and_then(|x| x.as_array())
                            .map(|a| a.iter().filter_map(|x| x.as_str())
                                .map(|s| s.to_string()).collect())
                            .unwrap_or_default();
                        let rank_colors: Vec<egui::Color32> = bm.get("rank-colors")
                            .and_then(|x| x.as_array())
                            .map(|a| a.iter()
                                .map(|x| parse_hex_color(x.as_str().unwrap_or("")))
                                .collect())
                            .unwrap_or_default();
                        egui::ScrollArea::horizontal()
                            .id_salt(format!("hscroll_{nome}"))
                            .show(ui, |ui| {
                                egui::Grid::new(format!("bench_{nome}"))
                                    .num_columns(2 + rank_names.len())
                                    .spacing([16.0, 4.0])
                                    .show(ui, |ui| {
                                        ui.label(egui::RichText::new("CENÁRIO")
                                            .color(theme::MUTED).small().strong());
                                        ui.label(egui::RichText::new("SCORE")
                                            .color(theme::MUTED).small().strong());
                                        for (i, rn) in rank_names.iter().enumerate() {
                                            let c = rank_colors.get(i).copied()
                                                .unwrap_or(theme::MUTED);
                                            ui.label(egui::RichText::new(rn.to_uppercase())
                                                .color(c).small().strong());
                                        }
                                        ui.end_row();
                                        for cat in cats {
                                            let cnome = cat.get("nome")
                                                .and_then(|x| x.as_str()).unwrap_or("");
                                            ui.label(egui::RichText::new(cnome.to_uppercase())
                                                .color(theme::MUTED).small());
                                            ui.end_row();
                                            let Some(rows) = cat.get("scenarios")
                                                .and_then(|x| x.as_array()) else { continue };
                                            for r in rows {
                                                let scen = r.get("scenario")
                                                    .and_then(|x| x.as_str()).unwrap_or("?");
                                                let weak = r.get("weak?")
                                                    .and_then(|x| x.as_bool()).unwrap_or(false);
                                                let tier = r.get("tier")
                                                    .and_then(|x| x.as_i64()).unwrap_or(0);
                                                let tiers = r.get("tiers")
                                                    .and_then(|x| x.as_i64()).unwrap_or(9);
                                                let score = r.get("score").and_then(|x| x.as_f64());
                                                let skill = r.get("skill-label")
                                                    .and_then(|x| x.as_str());
                                                // -- cenário (clique direito = conversar) --
                                                let cor = if weak { theme::SERIOUS } else { theme::INK_2 };
                                                let mut resp = ui.add(
                                                    egui::Label::new(
                                                        egui::RichText::new(scen).color(cor).small())
                                                        .sense(egui::Sense::click()),
                                                );
                                                if let Some(sk) = skill {
                                                    let extra = if weak { " · ponto fraco" } else { "" };
                                                    resp = resp.on_hover_text(format!("{sk}{extra}"));
                                                }
                                                resp.context_menu(|ui| {
                                                    if ui.button("💬 conversar sobre isso").clicked() {
                                                        let mut topic = format!(
                                                            "Cenário: {scen} (benchmark {nome}, coluna {cnome})."
                                                        );
                                                        match score {
                                                            Some(s) => topic.push_str(&format!(
                                                                " Pontuação do jogador: {s:.0}, tier {tier} de {tiers}{}.",
                                                                rank_names.get((tier as usize).wrapping_sub(1))
                                                                    .map(|r| format!(" (rank {r})"))
                                                                    .unwrap_or_default()
                                                            )),
                                                            None => topic.push_str(
                                                                " O jogador ainda não jogou este cenário."),
                                                        }
                                                        if let Some(sk) = skill {
                                                            topic.push_str(&format!(
                                                                " Skill dominante do mapa: {sk}."));
                                                        }
                                                        if let Some(nt) = r.get("next-threshold")
                                                            .and_then(|x| x.as_f64())
                                                        {
                                                            topic.push_str(&format!(
                                                                " Próximo tier em: {nt:.0}."));
                                                        }
                                                        if weak {
                                                            topic.push_str(
                                                                " Este é um dos pontos FRACOS do jogador no bench.");
                                                        }
                                                        talk_about = Some(topic);
                                                        ui.close_menu();
                                                    }
                                                });
                                                // -- score (+% até o próximo tier) --
                                                match score {
                                                    Some(s) => {
                                                        ui.horizontal(|ui| {
                                                            ui.label(egui::RichText::new(fmt_score(s))
                                                                .color(theme::INK).small().strong());
                                                            if let Some(nt) = r.get("next-threshold")
                                                                .and_then(|x| x.as_f64())
                                                            {
                                                                if nt > 0.0 {
                                                                    ui.label(egui::RichText::new(
                                                                        format!("{:.0}%", 100.0 * s / nt))
                                                                        .color(theme::MUTED).small());
                                                                }
                                                            }
                                                        });
                                                    }
                                                    None => {
                                                        ui.label(egui::RichText::new("—")
                                                            .color(theme::MUTED).small());
                                                    }
                                                }
                                                // -- thresholds sob as colunas de rank --
                                                let ths: Vec<f64> = r.get("thresholds")
                                                    .and_then(|x| x.as_array())
                                                    .map(|a| a.iter()
                                                        .filter_map(|x| x.as_f64()).collect())
                                                    .unwrap_or_default();
                                                for (i, th) in ths.iter().enumerate() {
                                                    let atual = (i as i64) == tier - 1; // último alcançado
                                                    let feito = (i as i64) < tier;
                                                    if atual {
                                                        egui::Frame::new()
                                                            .fill(theme::GRID)
                                                            .corner_radius(8.0)
                                                            .inner_margin(egui::Margin::symmetric(8, 1))
                                                            .show(ui, |ui| {
                                                                ui.label(egui::RichText::new(fmt_score(*th))
                                                                    .color(theme::INK).small().strong());
                                                            });
                                                    } else {
                                                        let c = if feito { theme::INK_2 } else { theme::MUTED };
                                                        ui.label(egui::RichText::new(fmt_score(*th))
                                                            .color(c).small());
                                                    }
                                                }
                                                ui.end_row();
                                            }
                                        }
                                    });
                            });
                    });
                    ui.add_space(4.0);
                }
            }
        });

        if refresh {
            self.refresh_benchmarks();
        }
        if let Some(topic) = talk_about {
            self.chat_topic = Some(topic.clone());
            self.chat_open = true;
            self.chat_msgs.push(chat::ChatMsg {
                role: "assistant".into(),
                content: format!("Vamos falar de: {topic}\nO que você quer saber?"),
            });
        }
    }

    fn refresh_benchmarks(&mut self) {
        // usuário digitado precisa chegar ao profile.json ANTES do comando
        if let Err(e) = save_profile_ui(&self.prof, &self.cfg) {
            self.error = Some(format!("salvando perfil: {e:#}"));
            return;
        }
        self.run_coach_job("bench", "Buscando suas pontuações no kovaaks.com...");
    }

    // ------------------------------------------------------------------ chat
    fn draw_chat(&mut self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            ui.label(egui::RichText::new("Coach IA").color(theme::INK).size(15.0).strong());
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if ui.small_button("limpar").on_hover_text("recomeça a conversa").clicked() {
                    self.chat_msgs.clear();
                    self.chat_topic = None;
                }
            });
        });
        ui.add_space(4.0);

        let input_h = 64.0;
        let list_h = ui.available_height() - input_h;
        egui::ScrollArea::vertical()
            .max_height(list_h)
            .stick_to_bottom(true)
            .show(ui, |ui| {
                if self.chat_msgs.is_empty() {
                    ui.label(egui::RichText::new(
                        "Pergunte qualquer coisa sobre sua mira: \"por que meu \
                         tracking de pulso está fraco?\", \"o que treinar hoje em \
                         20 min?\", \"como levar isso pro Valorant?\" — ou clique \
                         com o botão direito num mapa da aba Benchmarks.",
                    ).color(theme::MUTED).small());
                }
                for m in &self.chat_msgs {
                    let user = m.role == "user";
                    let bg = if user { theme::ACCENT.gamma_multiply(0.18) } else { theme::SURFACE };
                    egui::Frame::new()
                        .fill(bg)
                        .corner_radius(6.0)
                        .inner_margin(egui::Margin::same(8))
                        .show(ui, |ui| {
                            ui.set_width(ui.available_width());
                            ui.label(egui::RichText::new(&m.content).color(theme::INK).small());
                        });
                    ui.add_space(4.0);
                }
                if self.chat_rx.is_some() {
                    ui.horizontal(|ui| {
                        ui.add(egui::Spinner::new().size(14.0));
                        ui.label(egui::RichText::new("pensando...").color(theme::MUTED).small());
                    });
                }
            });

        ui.add_space(4.0);
        let mut send = false;
        ui.horizontal(|ui| {
            let edit = ui.add_sized(
                [ui.available_width() - 64.0, 48.0],
                egui::TextEdit::multiline(&mut self.chat_input)
                    .hint_text("pergunte ao coach... (Enter envia)")
                    .desired_rows(2),
            );
            if edit.has_focus()
                && ui.input(|i| i.key_pressed(egui::Key::Enter) && !i.modifiers.shift)
            {
                send = true;
            }
            if ui.add_enabled(
                self.chat_rx.is_none() && !self.chat_input.trim().is_empty(),
                egui::Button::new("enviar"),
            ).clicked() {
                send = true;
            }
        });
        if send && self.chat_rx.is_none() && !self.chat_input.trim().is_empty() {
            self.chat_send();
        }
    }

    fn chat_send(&mut self) {
        let text = std::mem::take(&mut self.chat_input).trim().to_string();
        // Enter deixa um \n pendurado no multiline; o trim acima resolve
        self.chat_msgs.push(chat::ChatMsg { role: "user".into(), content: text });
        let system = chat::system_prompt(
            self.coach.diagnosis.as_ref(),
            self.coach.benchmarks.as_ref(),
            self.chat_topic.as_deref(),
        );
        // só user/assistant vão como histórico (mensagens de erro ⚠ inclusive:
        // são inofensivas e mantêm o fio da conversa)
        let history: Vec<chat::ChatMsg> = self.chat_msgs.clone();
        let (tx, rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let _ = tx.send(chat::complete(&system, &history));
        });
        self.chat_rx = Some(rx);
    }

    // ------------------------------------------------------------------ side
    fn draw_side(&mut self, ui: &mut egui::Ui) {
        let idle = matches!(self.state, State::Idle);

        ui.add_space(4.0);
        ui.label(egui::RichText::new("aimscope").color(theme::INK).size(22.0).strong());
        ui.label(egui::RichText::new("coach de mira — KovaaK's").color(theme::MUTED).small());
        ui.add_space(10.0);

        // ---- gravação -----------------------------------------------------
        theme::card().show(ui, |ui| {
            theme::section_title(ui, "Sessão de treino");
            egui::Grid::new("cfg").num_columns(2).spacing([10.0, 6.0]).show(ui, |ui| {
                ui.label(egui::RichText::new("DPI").small());
                ui.add(egui::TextEdit::singleline(&mut self.cfg.dpi).desired_width(70.0));
                ui.end_row();
                ui.label(egui::RichText::new("Sens").small());
                ui.add(egui::TextEdit::singleline(&mut self.cfg.sens).desired_width(70.0));
                ui.end_row();
                ui.label(egui::RichText::new("Jogo").small());
                egui::ComboBox::from_id_salt("scale")
                    .selected_text(&self.cfg.scale)
                    .width(110.0)
                    .show_ui(ui, |ui| {
                        ui.selectable_value(&mut self.cfg.scale, "valorant".into(), "Valorant");
                        ui.selectable_value(&mut self.cfg.scale, "cs".into(), "CS2 / Source");
                        ui.selectable_value(&mut self.cfg.scale, "overwatch".into(), "Overwatch");
                    });
                ui.end_row();
                ui.label(egui::RichText::new("Notas").small());
                ui.add(
                    egui::TextEdit::singleline(&mut self.cfg.notes)
                        .hint_text("descansado, pós-café…")
                        .desired_width(140.0),
                );
                ui.end_row();
            });
            ui.checkbox(&mut self.cfg.screen, "captura de tela (reaction time)")
                .on_hover_text("exige o jogo em borderless");
            if let (Ok(dpi), Ok(sens)) = (
                self.cfg.dpi.trim().parse::<f64>(),
                self.cfg.sens.trim().replace(',', ".").parse::<f64>(),
            ) {
                if let Some(dpc) = session::deg_per_count(&self.cfg.scale, sens) {
                    ui.label(
                        egui::RichText::new(format!("cm/360: {:.1} cm", session::cm_per_360(dpc, dpi)))
                            .color(theme::MUTED)
                            .small(),
                    );
                }
            }
            ui.add_space(6.0);
            let btn = egui::Button::new(
                egui::RichText::new("▶  Gravar sessão").color(theme::INK).size(16.0),
            )
            .fill(theme::ACCENT.gamma_multiply(if idle { 1.0 } else { 0.4 }))
            .min_size(egui::vec2(ui.available_width(), 36.0));
            if ui.add_enabled(idle, btn).clicked() {
                self.start_recording();
            }
        });

        ui.add_space(8.0);

        // ---- perfil de objetivo --------------------------------------------
        let mut save_prof = false;
        let mut interpret_objective = false;
        theme::card().show(ui, |ui| {
            theme::section_title(ui, "Seu objetivo");
            // linguagem natural primeiro: o jogador DESCREVE; a IA estrutura
            ui.add(
                egui::TextEdit::multiline(&mut self.prof.objective)
                    .hint_text("ex.: quero melhorar minha mira no Valorant sem trocar de sens, focando em flicks")
                    .desired_rows(2)
                    .desired_width(ui.available_width() - 8.0),
            );
            ui.horizontal(|ui| {
                let can = idle && !self.prof.objective.trim().is_empty();
                if ui.add_enabled(can, egui::Button::new("✨ Interpretar objetivo").small())
                    .on_hover_text("a IA converte seu texto em modo + foco de treino (com fallback por regras)")
                    .clicked()
                {
                    interpret_objective = true;
                }
            });
            if let Some(s) = load_objective_summary() {
                ui.label(egui::RichText::new(format!("Entendi: {s}"))
                    .color(theme::INK_2).small().italics());
            }
            ui.add_space(4.0);
            // eixo 1: política de sens (ADR 0005)
            egui::ComboBox::from_id_salt("sens_policy")
                .width(ui.available_width() - 8.0)
                .selected_text(match self.prof.sens_policy.as_str() {
                    "range" => "Tenho um range de sens",
                    "search" => "Procuro a melhor sens pra mim",
                    _ => "Já tenho uma sens (fixa)",
                })
                .show_ui(ui, |ui| {
                    ui.selectable_value(&mut self.prof.sens_policy, "fixed".into(),
                        "Já tenho uma sens (fixa) — o coach nunca sugere trocar");
                    ui.selectable_value(&mut self.prof.sens_policy, "range".into(),
                        "Tenho um range — trocar dentro dele é permitido (com custo)");
                    ui.selectable_value(&mut self.prof.sens_policy, "search".into(),
                        "Procuro a melhor sens — experimentos guiados pelo coach");
                });
            if self.prof.sens_policy == "range" {
                ui.horizontal(|ui| {
                    ui.label(egui::RichText::new("Range (cm/360)").small());
                    ui.add(egui::TextEdit::singleline(&mut self.prof.sens_min)
                        .hint_text("min").desired_width(40.0));
                    ui.label(egui::RichText::new("–").small());
                    ui.add(egui::TextEdit::singleline(&mut self.prof.sens_max)
                        .hint_text("max").desired_width(40.0));
                });
            }
            // eixo 2: alvo de jogo
            egui::ComboBox::from_id_salt("game_target")
                .width(ui.available_width() - 8.0)
                .selected_text(match self.prof.game_target.as_str() {
                    "transfer" => "Transferir pro jogo (Valorant...)",
                    _ => "KovaaK's por si só",
                })
                .show_ui(ui, |ui| {
                    ui.selectable_value(&mut self.prof.game_target, "kovaaks".into(),
                        "KovaaK's por si só — subir rank no benchmark");
                    ui.selectable_value(&mut self.prof.game_target, "transfer".into(),
                        "Transferir pro jogo — prioriza o que transfere");
                });
            ui.horizontal(|ui| {
                ui.label(egui::RichText::new("Minutos/dia").small());
                ui.add(egui::TextEdit::singleline(&mut self.prof.budget).desired_width(46.0));
                if ui.small_button("Salvar objetivo").clicked() {
                    save_prof = true;
                }
            });
        });
        if save_prof {
            match save_profile_ui(&self.prof, &self.cfg) {
                Ok(_) => self.status = "Objetivo salvo. Clique em “Atualizar diagnóstico” para replanejar.".into(),
                Err(e) => self.error = Some(format!("salvando perfil: {e:#}")),
            }
        }
        if interpret_objective {
            // grava o texto ANTES: o agente lê player/objective-text do profile.json
            match save_profile_ui(&self.prof, &self.cfg) {
                Ok(_) => self.run_coach_job("__objective__", "Interpretando seu objetivo..."),
                Err(e) => self.error = Some(format!("salvando perfil: {e:#}")),
            }
        }

        ui.add_space(8.0);

        // ---- sessões --------------------------------------------------------
        let mut to_analyze: Option<PathBuf> = None;
        let mut to_open: Option<PathBuf> = None;
        theme::card().show(ui, |ui| {
            ui.horizontal(|ui| {
                theme::section_title(ui, "Sessões");
                if ui.small_button("↻").on_hover_text("atualizar lista").clicked() {
                    self.refresh_sessions();
                }
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if self.confirm_reset {
                        if ui
                            .small_button(egui::RichText::new("apagar tudo").color(theme::SERIOUS))
                            .clicked()
                        {
                            match tools::reset_coach_data() {
                                Ok(()) => {
                                    self.status = "Sessões apagadas e coach zerado. \
                                                   Perfil e objetivo mantidos."
                                        .into();
                                    self.error = None;
                                }
                                Err(e) => self.error = Some(format!("Falha no reset: {e:#}")),
                            }
                            self.confirm_reset = false;
                            self.refresh_sessions();
                            self.coach = load_coach_view();
                        }
                        if ui.small_button("cancelar").clicked() {
                            self.confirm_reset = false;
                        }
                    } else if ui
                        .add_enabled(idle, egui::Button::new("limpar").small())
                        .on_hover_text(
                            "apaga todas as sessões e reseta o coach \
                             (perfil, objetivo e config ficam)",
                        )
                        .clicked()
                    {
                        self.confirm_reset = true;
                    }
                });
            });
            egui::ScrollArea::vertical().max_height(190.0).show(ui, |ui| {
                for row in &self.sessions {
                    ui.horizontal(|ui| {
                        // kovaaks_2026-07-02_01-13-34 -> 07-02 01:13
                        let short = row
                            .name
                            .strip_prefix("kovaaks_")
                            .unwrap_or(&row.name)
                            .replace('_', " ");
                        ui.label(egui::RichText::new(short).small());
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if row.analyzed {
                                if ui.small_button("relatório").clicked() {
                                    to_open = Some(row.dir.join("report.html"));
                                }
                            } else if ui
                                .add_enabled(idle, egui::Button::new("analisar").small())
                                .clicked()
                            {
                                to_analyze = Some(row.dir.clone());
                            }
                        });
                    });
                }
                if self.sessions.is_empty() {
                    ui.label(egui::RichText::new("nenhuma sessão ainda").color(theme::MUTED).small());
                }
            });
        });
        if let Some(p) = to_open {
            let _ = tools::open_path(&p);
        }
        if let Some(d) = to_analyze {
            self.analyze_existing(d);
        }

        ui.add_space(6.0);
        ui.label(egui::RichText::new(&self.status).color(theme::MUTED).small());
    }

    // ----------------------------------------------------------------- coach
    fn draw_coach(&mut self, ui: &mut egui::Ui) {
        let mut coach_cmd: Option<(&'static str, &'static str)> = None;

        ui.horizontal(|ui| {
            ui.label(egui::RichText::new("Coach").color(theme::INK).size(18.0).strong());
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if ui.button("💬 Explicar em texto").clicked() {
                    coach_cmd = Some(("__narrate__", "Gerando explicação do diagnóstico..."));
                }
                if ui.button("🔄 Atualizar diagnóstico").on_hover_text(
                    "lê as sessões novas, rediagnostica, replaneja e confere a previsão anterior",
                ).clicked() {
                    coach_cmd = Some(("all", "Coach: lendo sessões, diagnosticando e planejando..."));
                }
            });
        });
        ui.add_space(6.0);

        if !self.coach.available {
            theme::card().show(ui, |ui| {
                ui.label("coach/ não encontrado ou Clojure não instalado — o diagnóstico fica desabilitado (gravação e relatórios seguem funcionando).");
            });
            return;
        }

        let Some(d) = self.coach.diagnosis.clone() else {
            theme::card().show(ui, |ui| {
                ui.label("Sem diagnóstico ainda — grave sessões de treino e clique em Atualizar.");
            });
            if let Some((cmd, label)) = coach_cmd {
                self.run_coach_job(cmd, label);
            }
            return;
        };

        egui::ScrollArea::vertical().show(ui, |ui| {
            // ---- hero: ponto fraco ------------------------------------------
            if let Some(g) = d.get("gargalo-global") {
                let nome = g.get("label").or_else(|| g.get("skill"))
                    .and_then(|s| s.as_str()).unwrap_or("?").to_string();
                let valor = g.get("value").and_then(|x| x.as_f64());
                let hint = g.get("hint").and_then(|x| x.as_str()).unwrap_or("").to_string();
                theme::card().show(ui, |ui| {
                    ui.horizontal(|ui| {
                        // marca de status carrega a severidade; o texto fica em tinta
                        let (r, _) = ui.allocate_exact_size(
                            egui::vec2(4.0, 44.0), egui::Sense::hover());
                        ui.painter().rect_filled(r, 2.0, theme::SERIOUS);
                        ui.vertical(|ui| {
                            ui.label(egui::RichText::new("Ponto fraco agora")
                                .color(theme::MUTED).small());
                            ui.horizontal(|ui| {
                                ui.label(egui::RichText::new(&nome)
                                    .color(theme::INK).size(24.0).strong());
                                if let Some(v) = valor {
                                    ui.label(egui::RichText::new(format!("{v:.0}"))
                                        .color(theme::INK).size(24.0));
                                    ui.label(egui::RichText::new("/100 · neutro = 50")
                                        .color(theme::MUTED).small());
                                }
                            });
                            if !hint.is_empty() {
                                ui.label(egui::RichText::new(hint).color(theme::INK_2).small());
                            }
                        });
                    });
                });
                ui.add_space(8.0);
            }

            // ---- habilidades (barras) + evolução ----------------------------
            let mut labels: std::collections::BTreeMap<String, String> = Default::default();
            let weakest = d.get("gargalo-global").and_then(|g| g.get("skill"))
                .and_then(|s| s.as_str()).unwrap_or("").to_string();
            let mut top6: Vec<String> = Vec::new();
            // tendência por skill (ADR 0004): direção vs. a própria média,
            // desenhada como seta ao lado da barra — nunca como nível
            let trends: std::collections::HashMap<String, String> = d
                .get("skills/tendencia")
                .and_then(|t| t.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|t| {
                            Some((
                                t.get("skill")?.as_str()?.to_string(),
                                t.get("dir")?.as_str()?.to_string(),
                            ))
                        })
                        .collect()
                })
                .unwrap_or_default();

            theme::card().show(ui, |ui| {
                theme::section_title(ui, "Suas habilidades");
                if let Some(ranked) = d.get("skills/ranked").and_then(|r| r.as_array()) {
                    for s in ranked.iter() {
                        if let (Some(k), Some(l)) = (
                            s.get("skill").and_then(|x| x.as_str()),
                            s.get("label").and_then(|x| x.as_str()),
                        ) {
                            labels.insert(k.into(), l.into());
                        }
                    }
                    for s in ranked.iter().take(6) {
                        if let Some(k) = s.get("skill").and_then(|x| x.as_str()) {
                            top6.push(k.to_string());
                        }
                    }
                }
                if let Some(cats) = d.get("skills/por-categoria").and_then(|c| c.as_array()) {
                    // taxonomia do bench Viscose: 4 categorias -> subskills
                    for (i, cat) in cats.iter().enumerate() {
                        if i > 0 {
                            ui.add_space(6.0);
                        }
                        if let Some(cl) = cat.get("label").and_then(|x| x.as_str()) {
                            ui.label(egui::RichText::new(cl.to_uppercase())
                                .color(theme::MUTED).small().strong());
                        }
                        if let Some(sks) = cat.get("skills").and_then(|x| x.as_array()) {
                            for s in sks {
                                let k = s.get("skill").and_then(|x| x.as_str()).unwrap_or("?");
                                let name = s.get("label").and_then(|x| x.as_str())
                                    .unwrap_or(k).to_string();
                                let hint = s.get("hint").and_then(|x| x.as_str());
                                match s.get("value").and_then(|x| x.as_f64()) {
                                    Some(v) => {
                                        // magnitude em um matiz só; o ponto fraco leva o status
                                        let fill = if k == weakest { theme::SERIOUS } else { theme::ACCENT };
                                        theme::stat_bar_trend(ui, &name, v, fill, hint,
                                            trends.get(k).map(|s| s.as_str()));
                                    }
                                    None => {
                                        let row = ui.horizontal(|ui| {
                                            ui.add_sized(
                                                [150.0, 16.0],
                                                egui::Label::new(egui::RichText::new(&name)
                                                    .color(theme::MUTED).small()),
                                            );
                                            ui.label(egui::RichText::new("sem medida ainda")
                                                .color(theme::MUTED).small().italics());
                                        });
                                        if let Some(h) = hint {
                                            row.response.on_hover_text(h);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if let Some(ranked) = d.get("skills/ranked").and_then(|r| r.as_array()) {
                    // diagnosis.json antigo, sem a taxonomia agrupada
                    for s in ranked.iter().take(6) {
                        if let (Some(k), Some(v)) = (
                            s.get("skill").and_then(|x| x.as_str()),
                            s.get("value").and_then(|x| x.as_f64()),
                        ) {
                            let name = labels.get(k).cloned().unwrap_or_else(|| k.to_string());
                            let hint = s.get("hint").and_then(|x| x.as_str());
                            let fill = if k == weakest { theme::SERIOUS } else { theme::ACCENT };
                            theme::stat_bar(ui, &name, v, fill, hint);
                        }
                    }
                }
                if d.get("skills/por-categoria").is_none() {
                    if let Some(sem) = d.get("skills/sem-evidencia-labels")
                        .or_else(|| d.get("skills/sem-evidencia"))
                        .and_then(|r| r.as_array())
                    {
                        if !sem.is_empty() {
                            ui.add_space(2.0);
                            ui.label(egui::RichText::new(format!(
                                "sem medida ainda: {} (chegam com a captura de tela ligada)",
                                sem.iter().filter_map(|x| x.as_str()).collect::<Vec<_>>().join(", ")
                            )).color(theme::MUTED).small());
                        }
                    }
                }
                // percentis globais (comando enrich)
                if let Some(pcts) = d.get("percentiles").and_then(|p| p.as_array()) {
                    for p in pcts.iter().take(3) {
                        if let (Some(scen), Some(top)) = (
                            p.get("scenario").and_then(|x| x.as_str()),
                            p.get("top-pct").and_then(|x| x.as_f64()),
                        ) {
                            ui.label(egui::RichText::new(format!("🌍 {scen}: top {top:.1}% do mundo"))
                                .color(theme::INK_2).small());
                        }
                    }
                }
            });
            ui.add_space(8.0);

            // evolução: só séries com 2+ pontos, só as top-6, rótulos amigáveis
            if let Some(hist) = d.get("history").and_then(|h| h.as_array()) {
                let mut series: std::collections::BTreeMap<String, Vec<[f64; 2]>> = Default::default();
                for (i, snap) in hist.iter().enumerate() {
                    if let Some(sk) = snap.get("skills").and_then(|s| s.as_object()) {
                        for (name, v) in sk {
                            if let Some(val) = v.as_f64() {
                                series.entry(name.clone()).or_default().push([i as f64, val]);
                            }
                        }
                    }
                }
                series.retain(|k, pts| pts.len() >= 2 && top6.contains(k));
                if !series.is_empty() {
                    theme::card().show(ui, |ui| {
                        theme::section_title(ui, "Evolução por diagnóstico");
                        egui_plot::Plot::new("skills_hist")
                            .height(170.0)
                            .include_y(0.0)
                            .include_y(100.0)
                            .show_axes([false, true])
                            .legend(egui_plot::Legend::default())
                            .show(ui, |plot_ui| {
                                // cor por slot na ordem fixa do conjunto exibido
                                // (≤6 séries; legenda sempre presente carrega a identidade)
                                for (i, (name, pts)) in series.iter().enumerate() {
                                    let disp = labels.get(name).cloned()
                                        .unwrap_or_else(|| name.clone());
                                    plot_ui.line(
                                        egui_plot::Line::new(egui_plot::PlotPoints::from(pts.clone()))
                                            .color(theme::SERIES[i % theme::SERIES.len()])
                                            .width(2.0)
                                            .name(disp),
                                    );
                                }
                            });
                    });
                    ui.add_space(8.0);
                }
            }

            // ---- teste inicial (balanceamento) -------------------------------
            if let Some(pl) = d.get("placement") {
                let completo = pl.get("completo?").and_then(|x| x.as_bool()).unwrap_or(true);
                if !completo {
                    let faltam = pl.get("faltam").and_then(|x| x.as_i64()).unwrap_or(0);
                    theme::card().show(ui, |ui| {
                        theme::section_title(ui, &format!("Teste inicial — {faltam} mapa(s) faltando"));
                        ui.label(egui::RichText::new(
                            "Jogue 1x cada mapa com a gravação ligada; cada um mede um par de habilidades.")
                            .color(theme::MUTED).small());
                        if let Some(itens) = pl.get("itens").and_then(|x| x.as_array()) {
                            for it in itens {
                                let feito = it.get("jogado?").and_then(|x| x.as_bool()).unwrap_or(false);
                                let scen = it.get("scenario").and_then(|x| x.as_str()).unwrap_or("?");
                                let mede = it.get("mede-labels").and_then(|x| x.as_array())
                                    .map(|a| a.iter().filter_map(|x| x.as_str())
                                        .collect::<Vec<_>>().join(" + "))
                                    .unwrap_or_default();
                                ui.horizontal(|ui| {
                                    ui.label(if feito { "✅" } else { "⬜" });
                                    ui.label(egui::RichText::new(scen).color(theme::INK_2));
                                    ui.label(egui::RichText::new(format!("— {mede}"))
                                        .color(theme::MUTED).small());
                                });
                            }
                        }
                    });
                    ui.add_space(8.0);
                }
            }

            // ---- plano -------------------------------------------------------
            if let Some(p) = self.coach.plan.clone() {
                let target = p.get("target-label").or_else(|| p.get("target"))
                    .and_then(|x| x.as_str()).unwrap_or("?").to_string();
                theme::card().show(ui, |ui| {
                    match p.get("status").and_then(|x| x.as_str()) {
                        Some("ok") => {
                            theme::section_title(ui, &format!("Plano de treino — alvo: {target}"));
                            if let Some(steps) = p.get("steps").and_then(|s| s.as_array()) {
                                for st in steps {
                                    let scen = st.get("scenario-label")
                                        .or_else(|| st.get("scenario"))
                                        .and_then(|x| x.as_str()).unwrap_or("?");
                                    let skill = st.get("skill-label")
                                        .or_else(|| st.get("skill"))
                                        .and_then(|x| x.as_str()).unwrap_or("?");
                                    let min = st.get("minutes").and_then(|x| x.as_f64()).unwrap_or(15.0);
                                    let delta = st.get("expected-delta").and_then(|x| x.as_str()).unwrap_or("");
                                    ui.horizontal(|ui| {
                                        ui.label(egui::RichText::new(format!("{min:.0} min"))
                                            .color(theme::INK).strong());
                                        ui.label(egui::RichText::new(scen).color(theme::INK_2));
                                        ui.label(egui::RichText::new(format!("→ {skill} {delta}"))
                                            .color(theme::MUTED).small());
                                    });
                                }
                            }
                        }
                        Some("ja-destravado") => {
                            theme::section_title(ui, "Plano de treino");
                            ui.label(format!("✅ {target}: você já tem o necessário — jogue o cenário e registre scores!"));
                        }
                        _ => {
                            theme::section_title(ui, "Plano de treino");
                            ui.label(egui::RichText::new("sem plano viável com as habilidades atuais")
                                .color(theme::MUTED));
                        }
                    }
                    if let Some(o) = &self.coach.outcome {
                        if let Some(rec) = o.get("recomendacao").and_then(|x| x.as_str()) {
                            ui.add_space(4.0);
                            ui.label(egui::RichText::new(format!("Plano anterior: {rec}"))
                                .color(theme::MUTED).italics().small())
                                .on_hover_text("comparação automática entre o que o plano previa e o que seus scores mostraram");
                        }
                    }
                });
                ui.add_space(8.0);
            }

            // ---- explicação em texto (narrativa) ------------------------------
            if let Some(md) = self.coach.narrative.clone() {
                theme::card().show(ui, |ui| {
                    egui::CollapsingHeader::new(
                        egui::RichText::new("Explicação do coach").color(theme::INK).strong())
                        .default_open(false)
                        .show(ui, |ui| {
                            egui::ScrollArea::vertical().max_height(280.0)
                                .show(ui, |ui| narrative_md(ui, &md));
                        });
                });
            }
        });

        if let Some((cmd, label)) = coach_cmd {
            self.run_coach_job(cmd, label);
        }
    }
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

        // resposta do chat (thread LLM) — fora do closure de desenho
        if let Some(rx) = &self.chat_rx {
            match rx.try_recv() {
                Ok(Ok(text)) => {
                    self.chat_msgs.push(chat::ChatMsg { role: "assistant".into(), content: text });
                    self.chat_rx = None;
                }
                Ok(Err(e)) => {
                    self.chat_msgs.push(chat::ChatMsg {
                        role: "assistant".into(),
                        content: format!("⚠ {e:#}"),
                    });
                    self.chat_rx = None;
                }
                Err(TryRecvError::Empty) => {
                    ctx.request_repaint_after(std::time::Duration::from_millis(150));
                }
                Err(TryRecvError::Disconnected) => {
                    self.chat_msgs.push(chat::ChatMsg {
                        role: "assistant".into(),
                        content: "⚠ o coach não respondeu (thread morreu)".into(),
                    });
                    self.chat_rx = None;
                }
            }
        }

        egui::SidePanel::left("side")
            .exact_width(300.0)
            .resizable(false)
            .frame(egui::Frame::new().fill(theme::PAGE).inner_margin(egui::Margin::same(10)))
            .show(ctx, |ui| {
                self.draw_side(ui);
            });

        if self.chat_open {
            egui::SidePanel::right("chat")
                .default_width(340.0)
                .min_width(280.0)
                .max_width(420.0)
                .frame(egui::Frame::new().fill(theme::PAGE).inner_margin(egui::Margin::same(10)))
                .show(ctx, |ui| {
                    self.draw_chat(ui);
                });
        }

        egui::CentralPanel::default()
            .frame(egui::Frame::new().fill(theme::PAGE).inner_margin(egui::Margin::same(14)))
            .show(ctx, |ui| {
                if let Some(err) = self.error.clone() {
                    theme::card().show(ui, |ui| {
                        ui.label(egui::RichText::new(err).color(theme::CRITICAL));
                    });
                    ui.add_space(6.0);
                }

                match &mut self.state {
                    State::Recording { rec, started, rate_hz, .. } => {
                        let elapsed = started.elapsed().as_secs();
                        let rate = *rate_hz;
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
                        let screen_txt = rec
                            .as_ref()
                            .and_then(|r| r.live_screen_frames())
                            .map(|f| format!("  ·  {f} frames de tela"))
                            .unwrap_or_default();

                        let mut stop: Option<Recording> = None;
                        ui.add_space(30.0);
                        ui.vertical_centered(|ui| {
                            ui.label(
                                egui::RichText::new("● GRAVANDO")
                                    .color(theme::CRITICAL)
                                    .size(26.0)
                                    .strong(),
                            );
                            ui.add_space(6.0);
                            ui.label(
                                egui::RichText::new(format!("{:02}:{:02}", elapsed / 60, elapsed % 60))
                                    .color(theme::INK)
                                    .size(48.0),
                            );
                            ui.label(
                                egui::RichText::new(format!(
                                    "{ev} eventos · {rate:.0} Hz · {clicks} cliques{screen_txt}"
                                ))
                                .color(theme::INK_2),
                            );
                            ui.label(
                                egui::RichText::new("Treine no KovaaK's. Ao terminar o cenário, volte aqui e pare.")
                                    .color(theme::MUTED)
                                    .small(),
                            );
                            ui.add_space(14.0);
                            let btn = egui::Button::new(
                                egui::RichText::new("■  Parar e analisar").color(theme::INK).size(17.0),
                            )
                            .fill(theme::CRITICAL.gamma_multiply(0.85))
                            .min_size(egui::vec2(240.0, 42.0));
                            if ui.add(btn).clicked() {
                                stop = rec.take();
                            }
                        });
                        if let Some(r) = stop {
                            self.stop_and_analyze(r);
                        }
                    }

                    State::Busy { what, .. } => {
                        ui.add_space(30.0);
                        ui.vertical_centered(|ui| {
                            ui.add(egui::Spinner::new().size(26.0));
                            ui.add_space(6.0);
                            ui.label(egui::RichText::new(what.as_str()).color(theme::INK_2));
                        });
                    }

                    State::Idle => {
                        ui.horizontal(|ui| {
                            ui.selectable_value(&mut self.tab, Tab::Coach, "Coach");
                            ui.selectable_value(&mut self.tab, Tab::Benchmarks, "Benchmarks");
                            ui.with_layout(
                                egui::Layout::right_to_left(egui::Align::Center),
                                |ui| {
                                    let label = if self.chat_open { "✕ fechar chat" } else { "💬 Coach IA" };
                                    if ui.button(label).clicked() {
                                        self.chat_open = !self.chat_open;
                                    }
                                },
                            );
                        });
                        ui.add_space(6.0);
                        match self.tab {
                            Tab::Coach => self.draw_coach(ui),
                            Tab::Benchmarks => self.draw_benchmarks(ui),
                        }
                    }
                }
            });
    }
}
