# Manifest — new-leitura-page

## Files to touch
- `shared/src/commonMain/kotlin/com/kaiodelphino/margem/Types.kt`:
  - O filtro da Leitura como valor: `LeituraQuery(from, to, chips)` e `Chip(kind, key, label)`, com `ChipKind` fechado: BUDGET · TAG · MERCHANT · NOTICE. "Zero é inicialização": `LeituraQuery()` vazio = sem filtro.
  - As linhas que as leituras novas devolvem: `LeituraRow`, `LeituraSuggestion`, `GroupRow`.
- `shared/src/commonMain/kotlin/com/kaiodelphino/margem/Db.kt`: leituras por widget (D-9), todas `read {}` com o filtro em SQL por um único construtor de WHERE privado, sem escrita:
  - `leituraSuggestions(text, from, to)`: autocompletar tipado, com contagem e soma. Verba e marcador saem do SQL pela chave. Estabelecimento sai por `alias_key`/`merchant_key`, comparado com `fold`. Recado sai pela fonte e, pelo texto, via `extract()` em Kotlin (A-2).
  - `leituraLines(q)`: uma linha por parcela no intervalo, com transação, data, hora, valor, verba, marcadores, estabelecimento, fonte, previsto e se é parcela 1. Alimenta a lista das páginas, Tamanho, Dia e hora e Retrato.
  - `leituraGroups(q, by, then)`: soma e contagem agrupadas por DAY · WEEK · MONTH · WEEKDAY · HOUR · BUDGET · TAG · TAGSET · MERCHANT · SOURCE, com segundo nível opcional. Alimenta Calendário, Dia da semana, Semanas, Mês a mês, Para onde foi, Fluxo (BUDGET × TAGSET pela regra C) e O que cresceu.
  - `firstSeen(from, to)`: estabelecimentos e marcadores cuja primeira data de todo o histórico cai no intervalo (Primeira vez, selo "novo").
  - `plannedBetween(from, to, today)`: ocorrências de todas as verbas no intervalo, inclusive puladas, e as "Todo mês" projetadas além do horizonte de `planned_dates` sem gravar nada (Previstos do mês, Meses à frente, O que ficou fora).
  - `installmentsAfter(today, months)`: parcelas futuras (Meses à frente).
  - Ritmo, Margem e Semanas continuam em `budgetsOverview`/`budgetDetail`/`history`; o mês do fim do intervalo vem de `monthOf`.
  - `window(month)`: a janela do mês de orçamento pelo dia de início atual, sobre o `monthWindow` interno (Month.kt:18), que o androidApp não alcança. Insights.kt a usa para os intervalos por mês.
  - `read`/`close`: a espera pelo leitor e a devolução dele passam a rodar em `NonCancellable`, para que uma leitura cancelada enquanto espera nunca perca o leitor.
- `androidApp/src/main/kotlin/com/kaiodelphino/margem/ui/Insights.kt`: reescrita.
  - `Insights(db, query, go, back, snack)` com: filtro (campo + chips + intervalo + resumo + Separar), grade em três contextos com títulos e barra presa, busca que recolhe e lupa com selo.
  - Os 18 widgets: um `@Composable` privado por widget, com miniatura e versão grande no mesmo corpo e um parâmetro `big`.
  - `LeituraWidgetPage(db, widget, query, …)`: filtro editável, frase, opções, gráfico, pedaço tocado, lista Maiores/Recentes e ações Só / + ou / Usar como intervalo.
  - As frases por modelo fixo, como hoje.
  - `InsightsTags` reescrito: novo contrato D35, em que cada widget e cada ação têm tag.
- `androidApp/src/main/kotlin/com/kaiodelphino/margem/ui/MainActivity.kt`:
  - `Screen.LeituraWidget(widget, query)` com encode/decode (D18) e `tabOf`.
  - `Screen.Insights` passa a carregar `query` e chama `Insights(db, query, go, back, snack)`.
- `androidApp/src/main/kotlin/com/kaiodelphino/margem/widgets/Components.kt`:
  - `SearchLine` e `TransactionLine` saem de Transactions.kt e viram públicos aqui, sem mudar de forma.
  - `IconButton` ganha `badge: Int = 0`.
  - Novos: `PinnedBar` (título de contexto preso), `CollapsingHeader` (recolhe pela rolagem), `RangeSheet` (folha de intervalo com atalhos e calendário de início/fim), `TypedChip` (TagChip com tipo).
- `androidApp/src/main/kotlin/com/kaiodelphino/margem/ui/Transactions.kt`: remove as cópias privadas de `SearchLine` e `TransactionLine` e passa a usar as de Components.kt.
- Testes:
  - Reescritos: `androidApp/src/test/.../LeituraUiTest.kt`, `LeituraInvariantsTest.kt`, `LeituraLifecycleTest.kt`; `shared/src/commonTest/.../LeituraEdgeTest.kt`, `LeituraFuzzTest.kt`.
  - Ajustados: `ModelTest.kt` (leituras novas no modelo, D47), `MutationKillTest.kt` (SQL novo, D48) e `VolumeTest.kt` (teto das leituras novas, D49).
  - Ajustes de tag: `ReservaUiTest.kt`, `MargemUiTest.kt` e `TetoLifecycleTest.kt`.
  - `shared/src/commonTest/.../ConcurrencyTest.kt`: `readsCancelledWhileWaitingForAReaderNeverLoseIt`, que cobre a correção de cancelamento em `Db.read`/`close`.
- Documentação:
  - `docs/spec.md`: §7.5 reescrita e §2.13 com previsto em aberto tracejado.
  - `docs/architecture.md`: D-row nova substitui D45 e D17; §9 se mudar arquivo.
  - `docs/glossary.md`: Intervalo e Costume.

## New files
- None. A Leitura continua em `ui/Insights.kt` (§9: um arquivo por aba); as peças compartilhadas vão para `widgets/Components.kt`.

## Public symbols to add
- `Types.kt`:
  - `LeituraQuery`, `Chip`, `ChipKind`: o filtro como valor, encodável na pilha (D18).
  - `LeituraRow`, `LeituraSuggestion`, `GroupRow`: o retorno das leituras.
  - `Group`: o enum do agrupamento.
- `Db.kt`: `leituraSuggestions`, `leituraLines`, `leituraGroups`, `firstSeen`, `plannedBetween`, `installmentsAfter`. São seis leituras, a superfície mínima para D-9 sem uma função por widget. Mais `window`, a janela do mês para o androidApp.
- `MainActivity.kt`: `Screen.LeituraWidget`.
- `Components.kt`:
  - `PinnedBar`, `CollapsingHeader`, `RangeSheet`, `TypedChip`;
  - `SearchLine` e `TransactionLine`, que deixam de ser privados.
- `Insights.kt`: `LeituraWidgetPage`.
- Total: 21. `const val` de tags não contam (D75).

## Reused, not rebuilt
- Filtro e busca:
  - `fold` (Extract.kt:43) e `foldedMatch` (Extract.kt:63): estabelecimento e recado sem diferença de maiúsculas e acentos.
  - `MerchantKey.of` (Extract.kt:96): a chave do estabelecimento.
  - `NOT_REFUNDED` (Db:1522): estornos fora de toda leitura nova.
  - `TagPicker` (Transactions.kt:1521): o modelo do campo com sugestões, contagem e letras em negrito.
  - `SearchLine` (Transactions.kt:1435), movido.
  - `TagChip` (Components.kt:544), base do `TypedChip`.
- Leituras e cálculo existentes:
  - `transactionRows` (Db:1219): carga de marcadores numa consulta só.
  - `plannedRows` (Db:1429) e `missingOccurrences` (Db:1405): base de `plannedBetween`.
  - `installmentDate` (Month:33).
  - `budgetsOverview` (Db:759), `budgetDetail` (Db:782) e `history` (Db:987): Margem, Ritmo, Semanas e Mês a mês por verba.
  - `budgetMonth` (Month:51): ritmo ideal, passos e projeção.
  - `monthOf` (Db:211): a janela do mês.
- Gráficos de hoje em Insights.kt:
  - `Pace` (:340) vira Ritmo acumulado; `History` (:502) vira Mês a mês; `Ranking` (:547) vira Para onde foi; `Calendar` (:606) vira Calendário.
  - `grid` (:463) e `axisStep` (:454): eixos.
  - `seriesColor` (:328): cor por ordem de criação.
- Componentes:
  - Estrutura: `TopBar` (Comp:917), `PageHeader` (Comp:1000) e `SectionLine` (Comp:1008), que viram o título de contexto.
  - Controles: `OptionPicker` (Comp:980), `Check` (Comp:526), `IconButton` (Comp:258).
  - Listas: `DayHeader` (Comp:1021) e `ListRow` (Comp:1031).
  - Medidores: `Meter` (Comp:755) e `TotalMeter` (Comp:803), na Margem.
  - Folhas e avisos: `BottomSheet` (Comp:1133), `Snackbar`/`SnackHost` (Comp:1193-1250), `EmptyState` (Comp:1053), `InfoBand` (Comp:1098).
  - Desenho: `hatch` (Comp:734) e `dashedBox` (Comp:787), para previsto em aberto tracejado.
- Navegação e estado:
  - `query()` (Main:175) com `db.version` (D6/D19): releitura.
  - `LocalToday` (Main:181): o dia de hoje.
  - `StackSaver` (Main:98): a pilha sobrevive à recriação.
  - `go`/`back` (Main:241): navegação.

## Budget
added 4200 · new files 0 · new public symbols 21

Motivo, declarado antes da implementação: a feature reescreve a Leitura inteira, com 18 widgets em miniatura e em página, filtro, navegação e três peças novas de UI, além de 6 leituras novas com SQL. A Insights.kt de hoje tem 681 linhas para 4 gráficos. A estimativa é:
- ~2600 linhas para os widgets e as páginas;
- ~600 para filtro, grade e cabeçalhos;
- ~500 para as leituras no Db;
- ~350 para Components;
- ~50 para MainActivity;
- menos ~280 de linhas removidas que voltam como reuso.

Muito acima dos padrões (300/3/8 do config; 600/1/10 da D75), por decisão D-1 ("tudo de uma vez").

Maturidade: `LeituraQuery`, `Group` e as seis leituras são abstrações novas; o formato delas só se prova quando os 18 widgets as usam. Com `exploratory`: um candidato em vez da corrida de 3, ACs de fumaça primeiro e suíte completa quando a API assentar (RULES.md). Para virar `settled`: os 18 widgets lendo só por essas seis funções, sem leitura nova a pedido de nenhum widget, e o `/dev-verify` rodado de novo.

## API
exploratory
