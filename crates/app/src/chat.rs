//! Chat do coach de mira: chamada direta à API OpenAI-compatível do NVIDIA
//! build (mesma configuração do agente embabel: env NVIDIA_APIKEY, cadeia de
//! modelos por latência). O system prompt é montado com os dados REAIS do
//! jogador (diagnóstico + benchmarks) — o modelo orienta, nunca inventa número.

use anyhow::{bail, Context, Result};

const BASE: &str = "https://integrate.api.nvidia.com/v1/chat/completions";

/// Cadeia por latência (espelho de models/openai-models.yml). AIMSCOPE_LLM
/// na frente quando definido.
fn model_chain() -> Vec<String> {
    let defaults = [
        "z-ai/glm-5.2",
        "minimaxai/minimax-m3",
        "qwen/qwen3.5-397b-a17b",
        "moonshotai/kimi-k2.6",
    ];
    let mut chain: Vec<String> = Vec::new();
    if let Ok(m) = std::env::var("AIMSCOPE_LLM") {
        if !m.trim().is_empty() {
            chain.push(m.trim().to_string());
        }
    }
    chain.extend(defaults.iter().map(|s| s.to_string()));
    chain
}

#[derive(Clone)]
pub struct ChatMsg {
    pub role: String, // "user" | "assistant"
    pub content: String,
}

/// System prompt sólido de coach de mira, com os dados do jogador embutidos.
/// `sens_policy` (fixed|range|search) liga o guardrail de sens (ADR 0005):
/// política fixa não amordaça — comentário geral sim, prescrição direta não.
pub fn system_prompt(
    diagnosis: Option<&serde_json::Value>,
    benchmarks: Option<&serde_json::Value>,
    topic: Option<&str>,
    sens_policy: Option<&str>,
) -> String {
    let mut p = String::from(
        "Você é o coach de mira do aimscope, especialista em KovaaK's e na \
         transferência de treino de mira para jogos (Valorant, CS2, Apex...). \
         Você conhece a fundo os benchmarks Voltaic e Viscose e a taxonomia de \
         skills do bench da Viscose: Control Tracking (braço, pulso, dedos, \
         combinado), Reactive Tracking (controle, velocidade, leitura), Flick \
         Tech (velocidade, estabilidade, micro, pós-flick) e Click Timing \
         (leitura, precisão, consistência).\n\
         \n\
         Regras:\n\
         - Responda em português brasileiro, direto e acionável, como um coach \
           experiente falando com um aluno. 2-6 frases por resposta na maioria \
           dos casos; listas curtas quando prescrever treino.\n\
         - Use APENAS os números fornecidos abaixo. Se um dado não estiver aqui, \
           diga que ainda não foi medido — NUNCA invente pontuação, rank ou métrica.\n\
         - Toda prescrição aponta cenários CONCRETOS (nomes reais de mapas) e o \
           porquê em termos da skill treinada.\n\
         - Escala das skills internas: 0-100, 50 = neutro. Tier dos benchmarks: \
           posição na régua oficial do bench (0 = sem rank).\n\
         - O assunto é mira e treino. Se perguntarem outra coisa, redirecione \
           com bom humor para o treino.\n",
    );
    if let Some(d) = diagnosis {
        if let Some(ranked) = d.get("skills/ranked").and_then(|r| r.as_array()) {
            p.push_str("\n## Skills do jogador (medidas pelo sensor, 0-100)\n");
            for s in ranked {
                if let (Some(l), Some(v)) = (
                    s.get("label").and_then(|x| x.as_str()),
                    s.get("value").and_then(|x| x.as_f64()),
                ) {
                    p.push_str(&format!("- {l}: {v:.0}\n"));
                }
            }
        }
        if let Some(g) = d.get("gargalo-global") {
            if let Some(l) = g.get("label").and_then(|x| x.as_str()) {
                p.push_str(&format!("Ponto mais fraco agora: {l}.\n"));
            }
        }
    }
    if let Some(b) = benchmarks {
        if let Some(guia) = b.get("guia").and_then(|g| g.as_array()) {
            p.push_str(
                "\n## Guia dos benchmarks (nível médio por skill na régua oficial; \
                 menor = mais fraco)\n",
            );
            for g in guia.iter().take(8) {
                if let (Some(l), Some(n)) = (
                    g.get("label").and_then(|x| x.as_str()),
                    g.get("nivel-medio").and_then(|x| x.as_f64()),
                ) {
                    let mapas = g
                        .get("mapas")
                        .and_then(|m| m.as_array())
                        .map(|a| {
                            a.iter()
                                .filter_map(|x| x.as_str())
                                .collect::<Vec<_>>()
                                .join(", ")
                        })
                        .unwrap_or_default();
                    p.push_str(&format!("- {l}: nível {n:.1} — treinar em: {mapas}\n"));
                }
            }
        }
    }
    match sens_policy {
        Some("fixed") => p.push_str(
            "\n## Política de sens do jogador: FIXA\n\
             Ele decidiu dominar a sens atual. NUNCA recomende diretamente trocar \
             de sens nem proponha experimentos de sens. Conhecimento geral é \
             permitido e bem-vindo (ex.: 'jogadores de sens baixa costumam ter \
             flick mais consistente e leitura mais difícil'). Faixas canônicas \
             em cm/360: alta < 40, média 40-60, baixa > 60.\n",
        ),
        Some("range") | Some("search") => p.push_str(
            "\n## Política de sens do jogador: aberta a experimentar\n\
             Sugestões de sens são permitidas DENTRO do processo de experimento \
             do coach (mínimo de runs, veredito por habilidade, queda inicial é \
             adaptação). Faixas canônicas em cm/360: alta < 40, média 40-60, \
             baixa > 60.\n",
        ),
        _ => {}
    }
    if let Some(t) = topic {
        p.push_str("\n## Assunto desta conversa (clicado pelo jogador)\n");
        p.push_str(t);
        p.push('\n');
    }
    p
}

/// Chamada síncrona (rodar em thread). Percorre a cadeia de modelos até um
/// responder; devolve o texto do assistant.
pub fn complete(system: &str, history: &[ChatMsg]) -> Result<String> {
    let key = std::env::var("NVIDIA_APIKEY")
        .ok()
        .filter(|k| !k.trim().is_empty())
        .context("NVIDIA_APIKEY não configurada — defina a variável de ambiente para conversar com o coach")?;

    let mut messages = vec![serde_json::json!({"role": "system", "content": system})];
    for m in history {
        messages.push(serde_json::json!({"role": m.role, "content": m.content}));
    }

    let mut last_err = String::new();
    for model in model_chain() {
        let body = serde_json::json!({
            "model": model,
            "messages": messages,
            "temperature": 0.6,
            "max_tokens": 1024,
        });
        let resp = ureq::post(BASE)
            .set("authorization", &format!("Bearer {}", key.trim()))
            .set("content-type", "application/json")
            .timeout(std::time::Duration::from_secs(60))
            .send_string(&body.to_string());
        match resp {
            Ok(r) => {
                let v: serde_json::Value =
                    serde_json::from_str(&r.into_string()?).context("resposta não é JSON")?;
                if let Some(text) = v
                    .pointer("/choices/0/message/content")
                    .and_then(|x| x.as_str())
                {
                    let text = text.trim();
                    if !text.is_empty() {
                        return Ok(text.to_string());
                    }
                }
                last_err = format!("{model}: resposta vazia");
            }
            Err(ureq::Error::Status(code, r)) => {
                last_err = format!(
                    "{model}: HTTP {code} {}",
                    r.into_string().unwrap_or_default().chars().take(200).collect::<String>()
                );
            }
            Err(e) => last_err = format!("{model}: {e}"),
        }
    }
    bail!("nenhum modelo respondeu ({last_err})")
}
