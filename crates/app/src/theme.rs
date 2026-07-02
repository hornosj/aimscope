//! Tema do aimscope — instância da paleta de referência do design system
//! (dataviz/palette.md, modo dark, validada p/ CVD e contraste na superfície
//! #1a1a19). Regras que este módulo materializa:
//! - texto usa tokens de tinta (primary/secondary/muted), NUNCA a cor da série;
//! - grid/eixos recessivos (hairline, um passo acima da superfície);
//! - status (ponto fraco = serious) é reservado e nunca vira cor de série.

use eframe::egui::{self, Color32};

// superfícies e tinta (palette.md § chart chrome, dark)
pub const PAGE: Color32 = Color32::from_rgb(0x0d, 0x0d, 0x0d); // plano da página
pub const SURFACE: Color32 = Color32::from_rgb(0x1a, 0x1a, 0x19); // cards/gráfico
pub const INK: Color32 = Color32::from_rgb(0xff, 0xff, 0xff);
pub const INK_2: Color32 = Color32::from_rgb(0xc3, 0xc2, 0xb7);
pub const MUTED: Color32 = Color32::from_rgb(0x89, 0x87, 0x81);
pub const GRID: Color32 = Color32::from_rgb(0x2c, 0x2c, 0x2a); // hairline
#[allow(dead_code)] // parte da instância da paleta; eixos custom ainda não usam
pub const BASELINE: Color32 = Color32::from_rgb(0x38, 0x38, 0x35);

// categórica dark (8 slots, ordem FIXA — é o mecanismo de segurança CVD)
pub const SERIES: [Color32; 8] = [
    Color32::from_rgb(0x39, 0x87, 0xe5), // blue
    Color32::from_rgb(0x19, 0x9e, 0x70), // aqua
    Color32::from_rgb(0xc9, 0x85, 0x00), // yellow
    Color32::from_rgb(0x00, 0x83, 0x00), // green
    Color32::from_rgb(0x90, 0x85, 0xe9), // violet
    Color32::from_rgb(0xe6, 0x67, 0x67), // red
    Color32::from_rgb(0xd5, 0x51, 0x81), // magenta
    Color32::from_rgb(0xd9, 0x59, 0x26), // orange
];

// acento de UI (slot 1 da categórica) e status reservados
pub const ACCENT: Color32 = Color32::from_rgb(0x39, 0x87, 0xe5);
#[allow(dead_code)] // status reservado (deltas positivos futuros)
pub const GOOD: Color32 = Color32::from_rgb(0x0c, 0xa3, 0x0c);
pub const SERIOUS: Color32 = Color32::from_rgb(0xec, 0x83, 0x5a);
pub const CRITICAL: Color32 = Color32::from_rgb(0xd0, 0x3b, 0x3b);

pub fn apply(ctx: &egui::Context) {
    let mut v = egui::Visuals::dark();
    v.override_text_color = Some(INK_2);
    v.panel_fill = PAGE;
    v.window_fill = PAGE;
    v.extreme_bg_color = SURFACE; // fundo de TextEdit etc.
    v.faint_bg_color = SURFACE;
    v.widgets.noninteractive.bg_stroke = egui::Stroke::new(1.0, GRID);
    v.widgets.inactive.bg_fill = Color32::from_rgb(0x24, 0x24, 0x22);
    v.widgets.inactive.weak_bg_fill = Color32::from_rgb(0x24, 0x24, 0x22);
    v.widgets.hovered.bg_fill = Color32::from_rgb(0x2e, 0x2e, 0x2c);
    v.widgets.hovered.weak_bg_fill = Color32::from_rgb(0x2e, 0x2e, 0x2c);
    v.widgets.active.bg_fill = Color32::from_rgb(0x33, 0x33, 0x30);
    v.selection.bg_fill = ACCENT.gamma_multiply(0.35);
    v.slider_trailing_fill = true;
    ctx.set_visuals(v);

    let mut style = (*ctx.style()).clone();
    style.spacing.item_spacing = egui::vec2(8.0, 6.0);
    style.spacing.button_padding = egui::vec2(12.0, 6.0);
    ctx.set_style(style);
}

/// Card: superfície um passo acima da página, hairline, cantos 8px.
pub fn card() -> egui::Frame {
    egui::Frame::new()
        .fill(SURFACE)
        .stroke(egui::Stroke::new(1.0, Color32::from_white_alpha(14)))
        .corner_radius(8.0)
        .inner_margin(egui::Margin::same(12))
}

/// Título de seção dentro de um card (sentence case, sem dois-pontos).
pub fn section_title(ui: &mut egui::Ui, text: &str) {
    ui.label(egui::RichText::new(text).color(INK).strong().size(14.0));
    ui.add_space(2.0);
}

/// Barra 0-100 no spec de marca: fina, ponta de dado arredondada (4px) e base
/// quadrada, trilha recessiva; o VALOR fica fora da barra, em tinta.
pub fn stat_bar(ui: &mut egui::Ui, label: &str, value: f64, fill: Color32, hint: Option<&str>) {
    ui.horizontal(|ui| {
        let lbl = ui.add_sized(
            [150.0, 16.0],
            egui::Label::new(egui::RichText::new(label).color(INK_2).small()),
        );
        if let Some(h) = hint {
            lbl.on_hover_text(h);
        }
        let (rect, _) =
            ui.allocate_exact_size(egui::vec2(180.0, 10.0), egui::Sense::hover());
        let p = ui.painter();
        p.rect_filled(rect, 4.0, GRID); // trilha
        let w = (rect.width() * (value / 100.0).clamp(0.0, 1.0) as f32).max(4.0);
        let fill_rect =
            egui::Rect::from_min_size(rect.min, egui::vec2(w, rect.height()));
        p.rect_filled(
            fill_rect,
            egui::CornerRadius { nw: 0, sw: 0, ne: 4, se: 4 },
            fill,
        );
        ui.label(
            egui::RichText::new(format!("{value:.0}"))
                .color(INK)
                .small()
                .strong(),
        );
    });
}
