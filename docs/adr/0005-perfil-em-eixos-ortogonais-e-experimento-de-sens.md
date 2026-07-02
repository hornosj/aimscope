# 0005 — Perfil de objetivo em eixos ortogonais; experimento de sens como entidade

Data: 2026-07-02 · Status: aceito

## Contexto

O perfil tinha um campo único `:player/goal` com três modos que se atropelam:
`:fixed-sens`, `:sens-range`, `:game-transfer`. Política de sens e alvo de jogo
são dimensões diferentes — o caso real "quero melhorar no Valorant SEM trocar de
sens" não cabia num modo só (o interpretador de objetivo já devolvia três eixos
espremidos num campo). Além disso, cada CSV do KovaaK's já registra a sens da
run, mas nada consumia esse dado: não existia forma do coach raciocinar sobre
"o jogador está testando outra sens".

## Decisão

1. **O perfil vira três eixos ortogonais:** política de sens
   (`:fixed` | `:range [min max]` | `:search`) × alvo de jogo
   (`:kovaaks` | `:valorant-transfer`) × foco (categorias). O interpretador de
   objetivo em linguagem natural preenche os três.
2. **Experimento de sens é entidade de primeira classe** (só sob `:range` ou
   `:search`): período declarado de runs numa sens diferente, atribuídas
   automaticamente pela sens registrada na run. Veredito só após mínimo de runs
   por cenário e sempre POR skill (melhorou/piorou/neutro + o que ensina),
   nunca aprovada/reprovada global; queda inicial por adaptação é esperada e
   descontada. Na política `:search`, a sequência de experimentos É a
   calibração de sens.
3. **Política fixa não amordaça o coach:** comentário geral de população é
   permitido ("sens baixa costuma dar flick mais consistente e leitura mais
   difícil"); prescrição direta de mudança, não. Faixas canônicas em cm/360:
   alta < 40, média 40–60, baixa > 60.

## Justificativa

Eixos ortogonais eliminam estados irrepresentáveis em vez de adicionar um 4º
modo (rejeitado: "busca de sens" como modo manteria o conflito com
transferência). O veredito por skill existe porque sens diferentes favorecem
skills diferentes — um veredito global por energia média condenaria toda sens
nova (jogador sempre piora no começo) e perderia exatamente a lição que o
experimento existe pra extrair.
