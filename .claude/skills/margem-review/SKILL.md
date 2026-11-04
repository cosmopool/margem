---
name: margem-review
description: >
  Review multi-agente de uma branch do Margem (Kotlin Multiplatform, Compose, SQLite à mão) contra
  a branch alvo, em 7 dimensões (corretude, Db/SQL/schema, regra do mês e dinheiro, arquitetura e
  Codin' Dirty, duplicação e reuso, Compose/estado/ciclo de vida, glossário/texto/hygiene), valida
  os findings com um reviewer final e gera margem_review.html. A regra de evidência também calibra
  os hunters do /dev-verify. Use when user says "revisar branch", "review da branch", "margem
  review", "review", or invokes /margem-review.
---

# Review de branch (Margem)

Review da branch atual contra uma branch alvo (normalmente `main`). Análise multi-agente da diff
com busca de contexto no repositório inteiro e nos docs. Saída: `margem_review.html` na raiz.

**Princípio geral:** encontrar problemas **introduzidos ou expostos pela branch**, não listar
melhorias possíveis. Todo finding tem evidência no código e uma ação concreta. Na dúvida, reduzir
a severidade ou descartar.

**Fonte de verdade das regras:** `docs/spec.md` (regra de produto, por §), `docs/glossary.md`
(palavras), `docs/architecture.md` (decisões D-n, schema, §10 testes), `AGENTS.md` (Codin' Dirty)
e `.agents/rules/testing.md`. Comportamento que uma linha D ou a spec define como intencional não
é bug — no máximo uma pergunta ao autor.

## Regra de evidência — não especular

O objetivo do review é encontrar **problemas reais introduzidos ou expostos pela branch**, e não listar possibilidades, preferências ou cenários hipotéticos.

Um finding só deve ser reportado quando for possível demonstrar, usando a diff e o contexto real do repositório, uma relação causal:

`código alterado → comportamento executado → causa do problema → impacto`

O reviewer deve conseguir explicar **exatamente por que o problema acontece no código atual**.

### O que um finding precisa demonstrar

Antes de reportar um problema, o agente deve identificar:

1. **O código alterado** que causa o problema.
2. **O fluxo existente** que executa ou consome esse código.
3. **O estado/entrada** necessário para chegar ao problema.
4. **O comportamento incorreto** produzido.
5. **O impacto concreto** desse comportamento.
6. **A causa técnica** que conecta o código alterado ao impacto.
7. **Um fix concreto**, quando aplicável.

O finding deve ser baseado em código que realmente existe no repositório.

### Não especular

Não reportar como problema factual afirmações como:

- "Isso pode quebrar se..."
- "Talvez aconteça..."
- "Pode causar problemas..."
- "Existe risco de..."
- "Seria melhor..."
- "Possivelmente..."
- "Caso alguma outra parte faça..."
- "No futuro isso pode..."
- "Se houver concorrência..."
- "Se essa função for chamada duas vezes..."

quando o cenário não puder ser demonstrado no código atual.

Se o cenário hipotético não puder ser encontrado ou demonstrado no repositório, **não classificar como bug**.

Se houver uma suspeita tecnicamente relevante, mas não for possível comprovar o fluxo, classificar como 🔵 e declarar explicitamente a incerteza.

### Regra especial para 🔴

Um finding 🔴 precisa ter evidência concreta.

Não classificar como 🔴 problemas baseados apenas em:

- possibilidade teórica;
- preferência arquitetural;
- código que "poderia" ser mais seguro;
- ausência de teste sem evidência de regressão;
- possível race condition sem fluxo concorrente demonstrável;
- possível problema de performance sem caminho quente ou impacto demonstrável;
- comportamento futuro;
- suposições sobre como outra parte do sistema poderia usar o código.

Para 🔴, o reviewer deve conseguir explicar um fluxo concreto que reproduza o problema.

### Fato vs. inferência

O agente deve distinguir claramente entre fatos observáveis no código e inferências.

**Fato** — pode ser comprovado diretamente pelo repositório:

- uma função chama outra;
- uma tabela é alterada;
- um estado é atualizado;
- uma requisição é disparada;
- uma lista é filtrada;
- um registro é removido;
- um resultado é consumido;
- um `LaunchedEffect` recarrega com determinada chave;
- um composable lê determinado estado;
- um teste cobre determinado comportamento.

**Inferência** — não é diretamente demonstrável:

- comportamento futuro;
- cenário que depende de código inexistente;
- hipótese sobre intenção do desenvolvedor;
- hipótese sobre como outro sistema externo poderia se comportar;
- possibilidade de concorrência sem concorrência demonstrável;
- possibilidade de corrupção sem fluxo que produza a corrupção.

Findings 🔴 e 🟡 devem ser baseados em fatos e relações causais verificáveis.

Inferências só podem aparecer como 🔵, com a dúvida explicitamente indicada.

### Fluxo concreto obrigatório

Antes de reportar um finding, o agente deve conseguir descrever mentalmente o fluxo que reproduz o problema:

1. **Estado inicial** — qual é o estado relevante?
2. **Ação** — o que o usuário, sistema ou processo executa?
3. **Código alterado** — qual arquivo e linha são responsáveis?
4. **Execução** — o que o código realmente faz?
5. **Estado resultante** — o que fica incorreto?
6. **Impacto** — o que o usuário, sistema, persistência ou integração sofre?

Se o fluxo não puder ser completado com informações presentes no repositório, **não reportar o problema como bug**.

### Obrigação de buscar contexto

A diff não deve ser analisada isoladamente.

Antes de concluir que existe um problema, o agente deve procurar no repositório:

- quem chama o código alterado;
- quem consome seu resultado;
- implementações da mesma interface;
- interfaces/base classes;
- as leituras e escritas do `Db` envolvidas;
- as telas e composables que mostram o dado;
- as linhas D da `docs/architecture.md` e as seções da `docs/spec.md` que definem a regra;
- testes existentes;
- fluxos de persistência;
- fluxos de sincronização;
- usos do símbolo alterado;
- implementações semelhantes;
- contratos existentes.

A diff mostra **onde a mudança ocorreu**. O restante do repositório deve ser usado para determinar **se essa mudança realmente produz um problema**.

Não concluir que um comportamento é incorreto apenas olhando a implementação isolada quando o contrato ou comportamento esperado está definido em outro ponto do projeto.

### Evidência obrigatória no finding

Cada agente deve produzir findings internamente com:

```text
arquivo:linha
severidade
confidence

problema:
<o que está incorreto>

evidence:
<evidência concreta encontrada no código>

fluxo:
<como o código chega ao problema>

impacto:
<efeito concreto>

causa:
<por que o código produz esse comportamento>

fix:
<correção sugerida>
```

## Process

### 1. Branch alvo

Argumento da skill ou pergunta ao usuário (uma pergunta só), sugerindo `main`.

### 2. Coletar diff + objetivo

```bash
git log <target>..HEAD --oneline
git log <target>..HEAD --format=%B
git diff <target>...HEAD --stat
git diff <target>...HEAD
```

Se houver `.work/<slug>/requirement.md` ou um `docs/*-plan.md` da feature, ler: são o objetivo e
os critérios de aceite. Registrar **objetivo** (1-2 frases), **comportamentos esperados** e
**áreas afetadas** — vão no prompt de todos os agentes.

### 3. Classificar arquivos alterados

- `shared/src/commonMain/` → regra e dados: `Db.kt` (única API pública, D12), `Month.kt`
  (`budgetMonth`, puro), `Types.kt`, `Extract.kt`, `Capture.kt`
- `androidApp/src/main/kotlin/.../ui/` → telas (um arquivo por área, objeto de tags D35)
- `androidApp/src/main/kotlin/.../widgets/` → design system (`Components.kt`, `Theme.kt`); o livro
  é `docs/design_system.html`
- `androidApp/src/main/kotlin/.../NoticeListener.kt`, `Backup.kt` → fronteiras Android
- `*/src/commonTest`, `*/src/test` → testes; `tools/pit-sql` → mutação (D48)
- `docs/` → spec, glossário, arquitetura, planos
- Para cada arquivo: `git show <target>:<arquivo>` (falha = arquivo novo).

### 4. Fan-out multi-agente

Lançar 7 agentes Explore **em paralelo** (uma mensagem, várias chamadas de Agent). Cada um recebe:
objetivo + comportamentos esperados; a lista classificada de arquivos; a diff (ou a instrução de
rodar `git diff <target>...HEAD` nos arquivos da sua dimensão); a instrução de ler `AGENTS.md`,
`.agents/rules/testing.md`, as seções da spec e as linhas D que a diff toca; a seção **Regra de
evidência — não especular** na íntegra; as regras comuns; o formato de **Evidência obrigatória no
finding**.

**Regras comuns:**

- **Regra de evidência**, na íntegra.
- **Contexto antes de concluir:** quem chama, quem consome, a spec, as linhas D, os testes.
- **Código pré-existente** só quando a diff muda seu comportamento, passa a depender dele de forma
  incorreta, expõe um bug antes inacessível, deveria tê-lo reutilizado, ou o deixa sem uso ao lado
  de um substituto. Confirmar com `git show <target>:<arquivo>`.
- **Docs como checklist:** violação de regra escrita (AGENTS.md, linha D, spec §) é finding e cita
  `arquivo:linha` da regra no campo `evidence`.
- **Pergunta ao autor em vez de acusação** quando há indício de algo já existente que não dá para
  provar equivalente.

**Agente 1 — Corretude & regressões.** Para cada mudança de comportamento, traçar tela →
`Db` → SQL → `budgetMonth` → o que a tela mostra, e responder: entrada, transformação,
persistência, erro, duas execuções, estado anterior, dado perdido/duplicado/órfão. Blast radius: para
cada tipo ou assinatura pública alterada (`Db`, `Types.kt`), buscar todos os consumidores, inclusive
`CatalogActivity` e os widgets. Teste que semeia estado que nenhum código de produção grava valida
código morto. Gap de teste só com risco concreto de regressão.

**Agente 2 — Db, SQL e schema.** Uma escrita só por vez e dentro da transação; `version` e
`onWrite` incrementados uma vez por escrita (nem zero, nem duas); leitura durante escrita (D29);
schema mudou sem migração a partir da v1 (D67); exclusão que deixa linha órfã em `budget_months`,
`week_steps`, `installments`, `transaction_tags`, `refunds`; texto cru em `WHERE`/`JOIN`/`GROUP
BY` em vez de coluna de chave (D61); `SUM` sobre junção que multiplica linhas; índice que falta numa
leitura que cresce com os meses (D49); `bindAll` com tipo novo não tratado.

**Agente 3 — Regra do mês e dinheiro.** Mês da verba com dia de início 29–31 e meses curtos (§3);
semanas parciais, passos com o resto na última semana (D11); ritmo e projeção (§5); parcelas (D7);
estorno (§6); previstos e reserva (D68–D74); `Cents` sem `Double`; "hoje" (`LocalToday`) lido
uma vez; `TimeZone` certo; virada do dia e do mês.

**Agente 4 — Arquitetura & Codin' Dirty.** `AGENTS.md` regra a regra: arquivo, classe, camada ou
conceito novo sem necessidade; ViewModel, repository, use case ou mapper (o Margem não tem); função
pública nova no `Db` quando uma leitura existente basta (D12, D45); `budgetMonth`/extração
saindo de `internal`; `java.*`/`android.*` no `commonMain` (D27); validação espalhada em vez de
parse na fronteira; branch extra que o chamador precisa tratar (zero é inicialização, sem null de
lookup).

**Agente 5 — Duplicação & reuso (+ design system).** Leitura do `Db` igual ou parecida com uma
existente (por nome e por semântica: mesmos joins e filtros); cálculo de apresentação repetido entre
telas (`Insights.kt`, `Budgets.kt`); composable novo quando `widgets/Components.kt` já tem; cor,
tipografia ou espaçamento fora do `Theme.kt`/livro (`docs/design_system.html`); dobra de texto fora
do `fold` (D61). Sem certeza de equivalência → pergunta ao autor.

**Agente 6 — Compose, estado e ciclo de vida.** Chamada ao `Db` na thread principal;
`LaunchedEffect`/`produceState` com chave errada (dado velho ao trocar data/mês, ou recarga a cada
recomposição); estado que devia sobreviver à pilha (`rememberSaveable`, D18) ou devia sumir;
referência a estado de composição antiga em callback; widgets sem `updateAll` após escrita;
TalkBack (`contentDescription`/semântica) faltando ou divergente do texto visível; tag de teste
(D35) removida ou renomeada sem atualizar os testes.

**Agente 7 — Glossário, texto & hygiene.** Palavras da UI e do código contra `docs/glossary.md`
(verba, cota, margem, passo, ritmo, recado, Pauta, lançamento, previsto…); texto da tela divergente
do mock/livro; formatação de dinheiro e data fora das funções existentes. Hygiene (nunca bloqueia
sozinho): mudança sem relação com o objetivo, debug, TODO esquecido, `local.properties`/keystore/
segredo na diff, decisão tomada no código e não registrada em `docs/` (regra do projeto: toda
decisão vai para spec ou arquitetura).

### 5. Reviewer final

Papel estrito — **pode remover findings, não pode inventar**. Valida só 🔴 e 🟡 (🔵 passa direto):
1. causado/exposto pela diff? 2. evidência no código? 3. arquivo/linha corretos? 4. duplicado?
5. o compilador ou `scripts/pipeline/dead.sh` já pega? 6. no escopo? 7. severidade certa (regra
especial do 🔴)? 8. ação concreta? 9. finding de arquitetura/duplicação candidato a bloquear cita a
regra (`AGENTS.md:linha`, linha D, spec §) ou o código existente? Sem citação, vira follow-up.

### 6. Sintetizar

- Deduplicar entre dimensões (mesmo arquivo:linha + mesmo problema → a dimensão mais específica).
- Destino da ação:
  - **Bloqueia merge** — bug provável, dado corrompido/órfão, regressão, segredo na diff, texto
    quebrado visível, violação de regra escrita (citando), duplicação de leitura/cálculo existente
    (citando), versão antiga deixada sem uso ao lado da nova. Design system, nomes e hygiene nunca
    bloqueiam sozinhos.
  - **Quick win** — fix mecânico < 5 min.
  - **Follow-up por tema**.
  - **Perguntas ao autor** — seção própria.
- Severidade (🔴/🟡/🔵) é tag no item. 🔴 = bug ou perda de dados, com cenário: "Quando
  [condição], ao [ação], o app [comportamento errado]."
- IDs (`B1`, `QW2`, `P3`…), esforço P/M/G nos bloqueadores, no máximo ~8 itens abertos por tema
  (excedente no apêndice, nunca omitido).

### 7. Gravar relatório

`margem_review.html` na raiz (avisar que não deve ser commitado) e, no terminal, só o veredito e
os bloqueadores. HTML puro, self-contained, CSS inline, sem JS nem recursos externos.

## Formato do relatório

Estrutura fixa abaixo. Manter ids, classes e ordem das seções; preencher só o conteúdo. Seções vazias
("Bloqueia merge" sem itens, por exemplo) permanecem no documento com o texto "Nenhum item.".

O relatório é lido em varredura: o leitor precisa achar severidade, ID, sintoma e arquivo sem ler
parágrafos. Por isso:

- **Todo item tem título curto** (`<h3>` nos bloqueadores, `.item-title` nos demais): ≤ 10 palavras,
  orientado a sintoma ("Badge não atualiza na virada do dia"), nunca a causa técnica.
- **Localização em chip próprio** (`<code class="loc">`), fora do texto corrido. Várias localizações
  viram vários chips.
- **Uma ideia por bloco:** problema em `.what`, correção em `.fix`. Nada de problema + evidência + fix no
  mesmo parágrafo.
- **Severidade na borda e no selo:** a classe `sev-red|sev-yellow|sev-blue` vai no `<section>`/`<li>`
  e no `.badge`. Quick wins usam `qw`; perguntas, `question`.
- **Veredito lista os bloqueadores** (`<ol>` com link para cada ID e esforço), sem prosa corrida.
- **Barra `nav.toc`** com a contagem de itens de cada seção; o sumário linka cada tema ao seu `<h3 id="tema-…">`.
- Texto longo (alternativas de fix, listas de arquivos, evidência extra) vai em
  `<details class="more">` dentro do item, recolhido.

```html
<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Review — <branch> → <target></title>
<style>
  :root { --red: #d1242f; --yellow: #bf8700; --blue: #0969da; --green: #1a7f37;
          --red-bg: #fff5f5; --yellow-bg: #fff8e5; --blue-bg: #f0f6ff; --green-bg: #f0fff4;
          --muted: #57606a; --border: #d0d7de; --bg: #f6f8fa; --text: #1f2328; }
  * { box-sizing: border-box; }
  body { font: 15px/1.55 -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
         max-width: 1000px; margin: 0 auto; padding: 0 1rem 4rem; color: var(--text); background: #fff; }
  h1 { font-size: 1.45rem; margin: 1.5rem 0 .25rem; line-height: 1.3; }
  h2 { font-size: 1.2rem; border-bottom: 2px solid var(--border); padding-bottom: .35rem;
       margin: 2.75rem 0 1rem; scroll-margin-top: 4rem; }
  h3 { font-size: 1.02rem; margin: 1.75rem 0 .4rem; scroll-margin-top: 4rem; }
  a { color: var(--blue); text-decoration: none; }
  a:hover { text-decoration: underline; }
  code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .86em;
         background: var(--bg); padding: .1em .35em; border-radius: 4px; }
  .meta { color: var(--muted); margin: 0 0 .75rem; }
  .goal { background: var(--bg); border-radius: 8px; padding: .75rem 1rem; margin: 0; }

  /* Navegação fixa: pular direto para a seção. */
  nav.toc { position: sticky; top: 0; z-index: 10; background: #fff;
            border-bottom: 1px solid var(--border); padding: .55rem 0; margin: 0 0 1rem;
            display: flex; flex-wrap: wrap; gap: .4rem; }
  nav.toc a { font-size: .85em; font-weight: 600; color: var(--text); background: var(--bg);
              border: 1px solid var(--border); border-radius: 999px; padding: .15rem .7rem; }
  nav.toc a:hover { text-decoration: none; border-color: var(--blue); }
  nav.toc .n { color: var(--muted); font-weight: 400; margin-left: .25rem; }

  /* Veredito: status + lista linkada dos bloqueadores. */
  .verdict { padding: 1rem 1.2rem; border-radius: 8px; border-left: 6px solid; }
  .verdict.blocked { border-color: var(--red); background: var(--red-bg); }
  .verdict.approved { border-color: var(--green); background: var(--green-bg); }
  .verdict .status { font-size: 1.1rem; font-weight: 700; display: block; margin-bottom: .35rem; }
  .verdict ol { margin: .5rem 0 0; padding-left: 1.3rem; }
  .verdict li { margin: .25rem 0; }
  .verdict .rest { color: var(--muted); margin: .6rem 0 0; }

  /* Sumário. */
  table { border-collapse: collapse; width: 100%; font-size: .95em; }
  th, td { border: 1px solid var(--border); padding: .4rem .7rem; text-align: left; }
  th { background: var(--bg); }
  td.num, th.num { text-align: center; width: 4.5rem; font-variant-numeric: tabular-nums; }
  td.zero { color: #afb8c1; }
  tr.total td { font-weight: 700; background: var(--bg); }

  /* Selos. */
  .badge { display: inline-block; font-size: .72em; font-weight: 700; padding: .12em .55em;
           border-radius: 999px; color: #fff; white-space: nowrap; }
  /* Severidade: o emoji já carrega a cor; sem pílula. */
  .badge.sev-red, .badge.sev-yellow, .badge.sev-blue { background: none; padding: 0; font-size: .95em; }
  .badge.effort { background: var(--muted); }
  .id { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-weight: 700;
        font-size: .85em; color: var(--muted); }
  code.loc { display: inline-block; background: #eaeef2; color: var(--text); font-size: .8em;
             overflow-wrap: anywhere; margin: .1rem .3rem .1rem 0; }

  /* Bloqueadores: card completo. */
  .finding { border: 1px solid var(--border); border-left: 6px solid var(--border);
             border-radius: 8px; padding: 1rem 1.2rem; margin: 1rem 0; scroll-margin-top: 4rem; }
  .finding.sev-red { border-left-color: var(--red); }
  .finding.sev-yellow { border-left-color: var(--yellow); }
  .finding.sev-blue { border-left-color: var(--blue); }
  .finding .head { display: flex; flex-wrap: wrap; align-items: center; gap: .5rem; }
  .finding h3 { margin: 0; font-size: 1.05rem; }
  .finding .locs { margin: .45rem 0 .6rem; }
  .finding dl { display: grid; grid-template-columns: 5.5rem 1fr; gap: .45rem .9rem; margin: 0; }
  .finding dt { font-weight: 700; color: var(--muted); font-size: .85em; text-transform: uppercase;
                letter-spacing: .03em; padding-top: .15rem; }
  .finding dd { margin: 0; }
  .finding dd.fix { background: var(--green-bg); border-radius: 6px; padding: .45rem .7rem; }

  /* Itens curtos (quick wins, perguntas, follow-ups, apêndice). */
  .theme-context { color: var(--muted); margin: 0 0 .6rem; font-size: .95em; }
  ul.items { list-style: none; padding: 0; margin: 0; }
  li.item { border: 1px solid var(--border); border-left: 4px solid var(--border); border-radius: 6px;
            padding: .6rem .9rem; margin: .5rem 0; scroll-margin-top: 4rem; }
  li.item.sev-red { border-left-color: var(--red); }
  li.item.sev-yellow { border-left-color: var(--yellow); }
  li.item.sev-blue { border-left-color: var(--blue); }
  li.item.qw { border-left-color: var(--green); }
  li.item.question { border-left-color: #8250df; }
  .item-head { display: flex; flex-wrap: wrap; align-items: center; gap: .45rem; }
  .item-title { font-weight: 700; }
  li.item p { margin: .3rem 0 0; }
  li.item .what { color: var(--text); }
  li.item .fix { color: var(--text); }
  li.item .fix strong, li.item .found strong { color: var(--green); }
  li.item .found { color: var(--muted); font-size: .93em; }
  li.item input[type=checkbox] { margin: 0; }
  details { margin: 1rem 0; }
  summary { cursor: pointer; font-weight: 700; }
  details.more summary { font-weight: 400; color: var(--blue); font-size: .9em; margin-top: .3rem; }
</style>
</head>
<body>

<h1>Review — <branch> → <target></h1>
<p class="meta"><data> · <N> arquivos · +X/−Y linhas</p>
<p class="goal"><strong>Objetivo da branch:</strong> <1-2 frases></p>

<nav class="toc">
  <a href="#veredito">Veredito</a>
  <a href="#bloqueia">🚫 Bloqueia<span class="n"><N></span></a>
  <a href="#quick-wins">⚡ Quick wins<span class="n"><N></span></a>
  <a href="#perguntas">❓ Perguntas<span class="n"><N></span></a>
  <a href="#follow-ups">Follow-ups<span class="n"><N></span></a>
  <a href="#apendice">Apêndice<span class="n"><N></span></a>
</nav>

<h2 id="veredito">Veredito</h2>
<div class="verdict blocked|approved">
  <span class="status">🚫 Bloqueado</span> | <span class="status">✅ Pode mergear</span>
  <!-- Se bloqueado: um <li> por bloqueador, na mesma ordem da seção "Bloqueia merge". -->
  <ol>
    <li><a href="#B1"><span class="id">B1</span></a> <título curto> <span class="badge effort">P</span></li>
  </ol>
  <p class="rest"><1 frase: o que vira follow-up.></p>
</div>

<h2 id="sumario">Sumário</h2>
<!-- Nome do tema linka para o <h3> do tema. Zero é "–" com class="num zero". -->
<table>
  <thead>
    <tr><th>Tema</th><th class="num">🔴</th><th class="num">🟡</th><th class="num">🔵</th>
        <th class="num">QW</th></tr>
  </thead>
  <tbody>
    <tr><td><a href="#tema-corretude">Corretude</a></td><td class="num">1</td><td class="num">2</td>
        <td class="num zero">–</td><td class="num zero">–</td></tr>
    <tr class="total"><td>Total</td><td class="num">…</td><td class="num">…</td><td class="num">…</td>
        <td class="num">…</td></tr>
  </tbody>
</table>

<h2 id="bloqueia">🚫 Bloqueia merge</h2>
<!-- Ordenado por impacto. Card completo por item. -->
<section class="finding sev-red" id="B1">
  <div class="head">
    <span class="badge sev-red">🔴</span><span class="id">B1</span>
    <h3><título curto orientado a sintoma, ≤ 10 palavras></h3>
    <span class="badge effort">Esforço: P|M|G</span>
  </div>
  <div class="locs"><code class="loc">Arquivo.kt:123</code> <code class="loc">Outro.kt:45</code></div>
  <dl>
    <dt>Impacto</dt><dd><o que o usuário/dev sofre, 1-2 frases>.</dd>
    <dt>Causa</dt><dd><1-2 frases técnicas; regra violada como <code>AGENTS.md:linha</code>>.</dd>
    <dt>Fix</dt><dd class="fix"><sugestão concreta, 1-2 frases>.</dd>
  </dl>
  <!-- Opcional: evidência longa (alternativas de fix, lista de arquivos) vai recolhida. -->
  <details class="more"><summary>Detalhes</summary><p>…</p></details>
</section>

<h2 id="quick-wins">⚡ Quick wins (&lt; 5 min cada)</h2>
<ul class="items">
  <li class="item qw" id="QW1">
    <div class="item-head"><input type="checkbox"><span class="id">QW1</span>
      <span class="item-title"><título curto></span><code class="loc">Arquivo.kt:83</code></div>
    <p class="fix"><fix mecânico, 1 frase></p>
  </li>
</ul>

<h2 id="perguntas">❓ Perguntas ao autor</h2>
<ul class="items">
  <li class="item question" id="Q1">
    <div class="item-head"><span class="id">Q1</span>
      <span class="item-title"><pergunta direta, 1 frase></span><code class="loc">Arquivo.kt:40</code></div>
    <p class="found"><strong>Encontrado:</strong> <code>caminho/Existente.kt:12</code> — <o que parece equivalente></p>
  </li>
</ul>

<h2 id="follow-ups">Follow-ups por tema</h2>

<h3 id="tema-<slug>"><Tema> — <apelido do padrão, se houver> <span class="n">(<N>)</span></h3>
<p class="theme-context"><≤ 2 frases de contexto comum; os itens abaixo não repetem esse contexto.></p>
<ul class="items">
  <li class="item sev-yellow" id="P1">
    <div class="item-head"><span class="badge sev-yellow">🟡</span><span class="id">P1</span>
      <span class="item-title"><título curto orientado a sintoma></span><code class="loc">Arquivo.kt:130</code></div>
    <p class="what"><problema, 1-2 frases></p>
    <p class="fix"><strong>Fix:</strong> <sugestão, 1 frase></p>
  </li>
</ul>

<h2 id="apendice">Apêndice — sugestões menores</h2>
<details>
  <summary>🔵 <N> itens (nomenclatura, polimento opcional)</summary>
  <ul class="items">
    <li class="item sev-blue" id="S1">
      <div class="item-head"><span class="badge sev-blue">🔵</span><span class="id">S1</span>
        <span class="item-title"><título curto></span><code class="loc">Arquivo.kt:12</code></div>
      <p class="fix"><strong>Fix:</strong> <sugestão></p>
    </li>
  </ul>
</details>

<h2 id="sem-achados">Dimensões sem achados</h2>
<ul>
  <li><dimensão> — <1 frase do que foi verificado></li>
</ul>

</body>
</html>
```

## Rules

- **pt-BR**, direto, sem elogios — só achados acionáveis.
- Todo finding cita `arquivo:linha` e tem ID único, que também é o `id` do elemento HTML.
- Título ≤ 10 palavras; `Impacto`, `Causa`, `Fix` e `.what` ≤ 2 frases; o resto em
  `<details class="more">`.
- Impacto antes de causa nos bloqueadores.
- Não reportar o que o compilador Kotlin (`w:`) ou o `scripts/pipeline/dead.sh` já pegam.
- Não pedir refatoração fora do escopo da diff.
- Achado incerto: 🔵 com a dúvida explícita.
