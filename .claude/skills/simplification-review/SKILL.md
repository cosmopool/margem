---
name: simplification-review
description: >
  Analisa se a branch do Margem está mais complexa do que o problema exige e propõe simplificações
  concretas, calibrado pelo Codin' Dirty do AGENTS.md e pelas decisões de docs/architecture.md
  (Db único, value classes, objetos de tags, zero como inicialização). Não procura bugs nem estilo —
  isso é do /margem-review. Gera simplification_review.md. Use when user says "revisar
  complexidade", "simplification review", "isso está overengineering", "dá pra simplificar essa
  branch", or invokes /simplification-review.
---

# Simplification Review (Margem)

Analisa a diff da branch atual contra uma branch alvo e responde **uma** pergunta:

> Existe uma solução significativamente mais simples que mantém o mesmo comportamento e respeita
> as decisões do projeto (`AGENTS.md`, `docs/architecture.md`)?

Saída: `simplification_review.md` na raiz do repo.

## Escopo

- Só **código introduzido ou reestruturado pela branch**. O resto do `Db.kt` não é alvo porque a
  diff mexeu em uma consulta.
- **Não** procurar bugs, SQL errado, ciclo de vida, glossário ou estilo: são do `/margem-review`.
  Bug encontrado no caminho vira uma linha no fim do relatório.
- Nenhum finding desta skill bloqueia merge.
- O Codin' Dirty corta para os dois lados: **conceito, arquivo, camada e função a mais** são
  complexidade; **função grande e linear** não é. Partir uma função de 200 linhas lineares em cinco
  não é simplificação.

## Regra de evidência

Todo finding precisa demonstrar a cadeia completa:

`complexidade adicionada → ausência de necessidade atual (provada por comando) →
alternativa mais simples → impacto da simplificação`

Se qualquer elo faltar, não reportar.

### Fato vs. inferência

**Fato** — comprovável rodando um comando no repo: quantas classes implementam uma
interface; quantos call sites um método tem; quais valores um parâmetro recebe; se um
símbolo aparece em `src/test` ou `src/commonTest`.

**Inferência** — não comprovável: intenção do desenvolvedor; se a abstração "vai" ser
usada; se outro dev acharia confuso; se existe um design "melhor".

Findings são baseados em fatos. Inferência não vira finding.

### Não especular

Não reportar, em nenhuma severidade:

- "parece overengineering";
- "poderia ser mais simples";
- "eu faria diferente";
- "talvez não precise dessa classe";
- "essa camada provavelmente é desnecessária";
- "adiciona complexidade sem motivo claro".

Também **não** aceitar como justificativa para manter complexidade:

- "podemos precisar disso depois";
- "quando tivermos outro provider";
- "quando houver mais implementações";
- "para facilitar uma futura migração";
- "para deixar extensível".

Se a necessidade futura não existe no código atual, ela não conta. Mas o inverso também
vale: a ausência de necessidade precisa ser **provada**, não presumida.

### Tamanho não é complexidade

Quantidade de linhas, arquivos ou classes **não é evidência**. Contar conceitos,
indireções e decisões que um dev precisa entender para modificar o fluxo. Uma classe de
200 linhas com um fluxo linear é mais simples que 5 classes de 20 linhas encadeadas.

---

## Lista de exclusão — ler ANTES de analisar

Padrões obrigatórios ou decididos neste repo. **Nunca** reportar:

- **`Db` como API pública única** (D12) e o tamanho de `Db.kt`: as leituras e escritas vivem ali
  de propósito; não sugerir repository, DAO, separar em arquivos ou camada entre tela e `Db`.
- **`budgetMonth` e extração `internal` e puros** (D12, D23): não sugerir torná-los públicos nem
  movê-los para o `Db`.
- **Value classes de id, dinheiro e chave** (`BudgetId`, `OccurrenceId`, `Cents`, `MerchantKey`
  com construtor privado, D61): são correção por construção (AGENTS.md), não abstração.
- **Zero como inicialização e sentinelas** (`NO_DATE`, `NO_MONTH`, `Planned()` vazio, id 0 =
  nenhum): substituem null e ramos no chamador (AGENTS.md "Fewer codepaths").
- **Objetos de tags por tela** (`BudgetTags`, `TransactionTags`…, D35): contrato dos testes de UI.
- **`LocalToday`** e o parâmetro `today` nas leituras: tornam o tempo determinístico nos testes.
- **Um arquivo por área de tela** (`Budgets.kt`, `Transactions.kt`…) com composables privados
  grandes; `widgets/Components.kt` como o design system inteiro.
- **SQL à mão sem ORM** (D5) e a regra "o SQL lê, uma função Kotlin calcula" (D12).
- **`ComposingKeyboard`**, o shadow `ActiveKeyboard` e `tools/pit-sql`: infraestrutura de teste
  decidida (arquitetura §10, D48).

Remover complexidade **acidental**, nunca uma decisão registrada. Se a branch contraria uma linha D,
isso é finding do `/margem-review`, não daqui.

---

## Categorias e gate obrigatório

Cada categoria só libera finding com o output do comando **colado na evidência**.

| # | Categoria | Comando(s) | Libera o finding quando |
|---|---|---|---|
| 1 | Interface/abstração prematura | `grep -rn ": Nome\b\|: Nome(" --include=*.kt .` | 1 implementação em produção **e** nenhuma em teste |
| 2 | Tipo novo de um uso | `grep -rn "\bNome\b" --include=*.kt shared/src androidApp/src` | data class, sealed ou wrapper usado num lugar só, sem garantia de tipo (não é id/unidade) |
| 3 | Camada que só delega | ler as assinaturas | função repassa 1:1 a outra sem transformação nem contrato próprio |
| 4 | Generalização não usada | `grep -rn "nomeDoParam =\|nomeDaFuncao(" --include=*.kt .` | parâmetro, flag, default ou lambda recebe o mesmo valor em todos os call sites |
| 5 | Leitura ou cálculo paralelo | `grep -n "fun " shared/src/commonMain/kotlin/com/kaiodelphino/margem/Db.kt` + leitura | a branch cria consulta ou cálculo que uma leitura existente já entrega (D45) |
| 6 | Múltiplas fontes de verdade | rastrear escritas do mesmo dado | o mesmo dado guardado em ≥2 lugares introduzidos pela branch (coluna derivada, estado de tela duplicando o `Db`) |
| 7 | Indireção desnecessária | rastrear a cadeia de chamadas | ≥3 saltos sem mudança de comportamento |
| 8 | Ramo que o chamador precisa tratar | ler os call sites | nullable, `Result` ou exceção nova onde zero/sentinela daria um caminho só |

Interpretação da categoria 1: qualquer implementação em `src/test` ou `src/commonTest` (fake) mata o
finding — exceto se o fake substitui algo que não é fronteira de I/O.

---

## Processo

1. **Branch alvo** — argumento ou uma pergunta ao usuário, sugerindo `main`.
2. **Diff** — `git log <target>..HEAD --oneline`, `git diff <target>...HEAD --stat`,
   `git diff <target>...HEAD`. Derivar em 1-2 frases qual problema a branch resolve (há
   `.work/<slug>/requirement.md` ou `docs/*-plan.md`? ler). Separar arquivos novos de modificados.
3. **Convenções** — `AGENTS.md` e as linhas D que a diff toca.
4. **Fan-out — 3 agentes Explore em paralelo**, cada um com o problema, a lista de arquivos, a
   Regra de evidência e a Lista de exclusão na íntegra, e o formato de finding:
   - **A — abstrações e tipos:** categorias 1, 2, 3.
   - **B — generalização, indireção e ramos:** categorias 4, 7, 8.
   - **C — leituras e estado:** categorias 5, 6.
5. **Validação** — pode remover, não pode inventar: output colado e suficiente? na lista de
   exclusão? introduzido pela branch? mesma conduta depois? respeita as decisões? duplicado? piora
   testabilidade?
6. **Relatório** — `simplification_review.md` na raiz (não commitar); no terminal, veredito e
   contagem por categoria.

---

## Escala

- 🟢 **Simples** — não há abstração ou camada desnecessária identificável.
- 🟡 **Pode simplificar** — existe complexidade desnecessária, sem risco relevante.
- 🟠 **Complexidade desproporcional** — impacto concreto na compreensão ou manutenção.

Não usar 🟠 por preferência arquitetural. 🟠 exige demonstrar o custo concreto: quantos
arquivos um dev precisa abrir para mudar um comportamento, ou qual conceito ele precisa
entender que não corresponde a nenhuma regra de negócio.

**Nenhuma severidade bloqueia merge.**

---

## Formato do finding

```text
[S<n>] Arquivo.kt:linha — <severidade>

complexidade:
<o que a branch adicionou>

evidence:
<output do comando do gate, colado>

por que não é necessária:
<leitura do output — quantas impls, quantos call sites, quais valores>

simplificação:
<alternativa concreta, no ponto exato do código>

impacto:
<o que sai; o que continua exatamente igual>
```

## Template do relatório

````markdown
# Simplification Review — <branch> → <target>

<N> arquivos novos · <M> modificados · <K> findings

## Veredito

🟢 | 🟡 | 🟠 — <uma frase>

## Sumário

| Categoria | 🟠 | 🟡 |
|---|---|---|

## Findings

### [S1] Arquivo.kt:linha — 🟡 <categoria>

**Complexidade:** ...
**Evidence:**
```
<output>
```
**Por que não é necessária:** ...
**Simplificação:** ...
**Impacto:** ...

## Verificado e considerado justificado

- `MerchantKey` — wrapper de um uso nesta diff, mas é a garantia de tipo da D61. Mantido.

## Categorias sem achados
````

A seção **Verificado e considerado justificado** é obrigatória quando a análise descartou
findings: evita repetir a discussão a cada branch.

## Estilo

- **pt-BR**, direto, sem elogios. Só achados acionáveis.
- Impacto antes de causa.
- `arquivo:linha` + ID `S<n>` em todo finding.
- Não usar a escala 🔴/🔵 do `/margem-review` — as escalas são distintas de propósito.
- Não sugerir "considerar refatorar" — a simplificação proposta precisa ser executável
  como está escrita.
