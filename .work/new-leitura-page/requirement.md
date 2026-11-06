# Leitura v2 — grade por contexto, busca única e páginas de widget (new-leitura-page)

## Summary
Substitui a tela Leitura (`ui/Insights.kt`) por uma grade de 18 widgets agrupados em três contextos (Quanto · Para onde · Quando), filtrada por um intervalo de dias e por um campo único de chips (verba, marcador, estabelecimento, recado) que somam com OU. Cada widget abre uma página inteira com opções, a lista de lançamentos ordenável e ações que mudam a consulta da Leitura inteira. A UI segue o mock `docs/mockups/analise-v8-widgets.html` (fita lateral, título de contexto com barra presa, busca que recolhe e lupa com selo, peças do app conforme o mapa de `analise-v4-ui-1-app.html`). Cada widget lê seus dados com a própria consulta ao `Db`, com o filtro aplicado em SQL.

## Specified
Do `requirement-raw.md` (citações por seção):
- Filtro por intervalo de dias com atalhos e personalizado (R1: "A Leitura filtra por um intervalo de-até em dias").
- Campo único com autocompletar em seções fixas e chips tipados (R2); chips somam com OU (R3: "Um lançamento entra se bater com qualquer chip"); linha de resumo (R4); Separar com ≥ 2 chips, cor segue o chip, "a + b" (R5).
- Sem abas no topo; grade em três contextos (R7); título de contexto com barra presa (R8); widget com fita lateral (R9); busca que recolhe com lupa e selo (R10); pele do app (R11).
- Página inteira por widget, nunca modal (R12); lista Maiores/Recentes (R13); "Só X", "+ ou X", "Usar como intervalo" mudam a Leitura inteira e voltam com Desfazer; "Mês a mês de X" abre o Mês a mês filtrado (R14).
- Regra C, "nada se divide" (R16); ritmo ideal, cota e projeção só com mês inteiro e filtro vazio ou só de verbas (R17); comparações seguem o intervalo (R18); estornos e riscados não contam (R20); hora só de lançamento com recado (R21).
- Widgets, tamanhos, opções e ordens das tabelas da §5 de `requirement-raw.md`.
- Sem mudança de schema (R22).

## Decided
- **D-1 Escopo: tudo de uma vez.** Os 18 widgets, as páginas e o filtro numa feature só, com o orçamento elevado no manifest antes do stage 1. Rejeitado: fatias por contexto ou por camada.
- **D-2 Tipo de chip "recado", não "aviso".** "Aviso" já é a caixa de aviso (`Notice`, Components.kt) e os "avisos de cota" (spec §3). O campo diz "Buscar verba, marcador, estabelecimento ou recado"; seções Verbas · Marcadores · Estabelecimentos · Recados. Um chip de recado casa pela fonte (app) ou pelo texto do recado. Rejeitado: dois tipos (fonte e recado); manter "aviso".
- **D-3 "Estabelecimento", não "lugar".** Em todo texto: tipo de chip, "Estabelecimentos que voltam", "Retrato do estabelecimento", "Agrupar: estabelecimento". Rejeitado: "lugar" como sinônimo curto.
- **D-4 "Com previstos" inclui lançados e em aberto.** Em aberto aparecem tracejados e separados do gasto. Muda o spec §2.13, que hoje tira o previsto em aberto dos rankings e do calendário. Rejeitado: só lançados.
- **D-5 O estado zera ao trocar de aba**, como hoje (Main:279). Filtro e opções sobrevivem a rotação e morte do processo enquanto a aba está aberta. Rejeitado: lembrar filtro e opções entre abas.
- **D-6 Entram os 18 widgets da grade do v8**, incluindo Dia e hora e Volta todo mês.
- **D-7 Falha de leitura do Db: igual às outras telas**, sem tratamento novo (ver A-1).
- **D-8 A Leitura abre no mês atual.** Rejeitado: mês passado; regra dos 5 primeiros dias.
- **D-9 Leituras por widget.** Cada widget chama a própria consulta SQL com o filtro (intervalo + chips) aplicado, como os rankings fazem hoje. Substitui a D45 por uma D-row nova; cada SQL novo precisa de cobertura no MutationKillTest (D48) e de teto no VolumeTest (D49). Rejeitado: uma carga ampla mais cálculo em memória.
- **D-10 Margem e Previstos do mês falam do mês em que o intervalo termina**, com o mês escrito na frase.
- **D-11 Meses à frente** olha sempre de hoje em diante, mesmo com intervalo passado, e diz isso na frase.
- **D-12 Glossário:** entram "Intervalo" (dias de-até escolhidos na Leitura; Evitar: período) e "Costume" (média dos meses anteriores no mesmo trecho).
- **D-13 Abas: as cinco de hoje** (spec §7: Verbas · Pauta · Lançamentos · Leitura · Ajustes). As quatro abas dos mocks são do redesign e não fazem parte desta feature. Decidido aqui por ser a regra vigente do spec.
- **D-14 Nome da área: "Leitura"** (glossário; "Análise" era só o nome dos mocks).
- **D-15 Documentação.** Reescrever spec §7.5 e §2.13; trocar a D45 e a D17 ("4 gráficos simples") em architecture.md; atualizar §9 se surgir arquivo novo.

## Assumed
*Volta ao dono antes do `/dev-build`.*
- **A-1 Falha de leitura.** O Db não lança para ids ou datas desconhecidos (Db:34-46, D37) e uma exceção de SQLite derruba a tela, como em todo `ui/`. Se errado: falta um estado de erro e um AC de falha. Confirma: dono.
- **A-2 Texto do recado em SQL.** O texto do recado só existe em Kotlin depois de `extract()` (D60), e a D61 proíbe o SQL de comparar texto cru. O chip de recado então casa pelo texto assim: o Kotlin dobra (`fold`) os recados ligados a lançamentos e manda o conjunto de ids para o SQL. Isso conflita com D-9 (leituras por widget) só na resolução do chip. Se errado: o chip de recado casa só pela fonte. Confirma: dono.
- **A-3 Rolar para cima não reabre a busca** (escolha do mock v7). Se errado: uma condição a mais no recolher. Confirma: dono.
- **A-4 "Salvar como previsto"** em Volta todo mês fica de fora (não-objetivo). Confirma: dono.
- **A-5 Hora.** "Veio de recado" é `notice_id != 0`. Vale a hora guardada, mesmo se emendada (`corrected`) ou mantida por Lançar previsto (D72). Parcela k > 1 não entra em Dia e hora. Se errado: muda o conjunto contado. Confirma: dono.
- **A-6 Separar com mais de 4 chips** mostra 4 séries e junta o resto em "outros" (catálogo). Confirma: dono.
- **A-7 Opções da página** valem só enquanto ela está aberta. Ao voltar, a miniatura não guarda Agrupar/Métrica. Confirma: dono.
- **A-8 Atalhos do intervalo.** "Este mês" e "mês passado" são meses da verba (dia de início dos Ajustes). "3 meses" e "6 meses" são os N meses da verba terminando no atual. `monthWindow` é interno (Month:18), então a tela pede a janela ao Db. Confirma: dono.

## Non-goals
- Esconder, reordenar ou redimensionar widgets pela pessoa; widgets escondidos de fábrica.
- Lembrar filtro ou opções entre abas (D-5).
- "Salvar como previsto" e "Não é" em Volta todo mês.
- Filtro com NÃO / exclusão; mais de um intervalo.
- Teto na Leitura (spec §3).
- Mudança de schema ou migração.
- Mudar as abas do app (D-13) ou a navegação fora da Leitura.
- Retrato do marcador, Fontes, Marcadores juntos, Passos da semana.
- Pedir hora no lançamento manual.
- Tratamento novo de erro de leitura (D-7).

## Acceptance criteria

### Filtro
- AC-1: Given lançamentos em out e nov, when a Leitura abre em 6 nov, then o intervalo é o mês da verba atual e a linha de resumo mostra o total, o número de lançamentos e "até hoje".
- AC-2: Given o seletor de intervalo, when a pessoa escolhe "mês passado", "3 meses", "6 meses" ou toca início e fim no calendário, then todos os widgets passam a contar só os lançamentos (parcelas pela data de cada uma) dentro do intervalo.
- AC-3: Given o campo de busca, when a pessoa digita "pa", then as sugestões vêm nas seções Verbas · Marcadores · Estabelecimentos · Recados, cada uma com contagem e soma no intervalo, e estabelecimento casa pelo apelido ou pelo texto do banco, sem diferença de maiúsculas e acentos.
- AC-4: Given os chips "Delivery" (verba) e "padaria" (marcador), when os widgets contam, then entra todo lançamento que bate em qualquer um dos dois, uma vez só, e "ou" aparece entre os chips.
- AC-5: Given um chip de recado com o nome de uma fonte, when os widgets contam, then entram os lançamentos vindos de recados daquela fonte; lançamento manual não entra.
- AC-6: Given ≥ 2 chips, when Separar é ligado, then cada widget que suporta Separar mostra uma série por chip com a cor do chip; tirar um chip não repinta os outros; um lançamento que bate em dois aparece em "a + b".
- AC-7: Given chips com legenda, when a busca roda, then a legenda nunca casa (spec §2.12).

### Estrutura e navegação
- AC-8: Given a Leitura, when ela abre, then mostra os contextos Quanto, Para onde e Quando, nessa ordem, com os widgets da §5 nos tamanhos indicados e sem abas no topo.
- AC-9: Given a rolagem passa da altura do filtro, then o filtro recolhe, a TopBar mostra a lupa com o intervalo escrito, e com N chips a lupa leva o selo N.
- AC-10: Given o filtro recolhido, when a pessoa toca na lupa, then o filtro reabre na posição atual; tocar fora ou rolar recolhe de novo; voltar ao topo reabre; rolar para cima não reabre.
- AC-11: Given a rolagem dentro de um contexto cujo título saiu da tela, then a barra com o nome do contexto fica presa sob a TopBar até o próximo contexto.
- AC-12: Given um widget, when a pessoa toca nele, then abre a página do widget (empilhada, com voltar) com filtro editável, frase, opções, gráfico grande e lista; voltar retorna à grade na mesma consulta.
- AC-14: Given qualquer filtro, when a pessoa troca de aba e volta, then a Leitura abre no mês atual sem chips.

### Mudar a consulta de dentro do widget
- AC-15: Given a página de Para onde foi, when a pessoa toca em "padaria" e escolhe "Só padaria", then a Leitura passa a ter só o chip padaria, volta à grade e mostra um Snackbar com Desfazer; Desfazer restaura os chips anteriores.
- AC-16: Given o mesmo toque, when escolhe "+ ou padaria", then o chip é somado aos existentes.
- AC-17: Given um gráfico de tempo, when a pessoa toca num dia, semana ou mês, ou arrasta um trecho no Ritmo, and escolhe "Usar como intervalo", then o intervalo da Leitura vira aquele trecho, com Desfazer.
- AC-18: Given O que cresceu, when a pessoa toca num item e escolhe "Mês a mês de X", then abre a página do Mês a mês já filtrada por X.

### Lista da página
- AC-19: Given a lista em Maiores, then os lançamentos vêm do maior para o menor, numerados, com a parte de cada um; em Recentes, agrupados por dia com o total do dia; widgets com ordens próprias (Hora, Vieram/A caminho, Acaba antes…) as oferecem.

### Regras de conta
- AC-20: Given um lançamento de R$ 30 com padaria e café, then Para onde foi mostra R$ 30 em padaria e R$ 30 em café, e o Fluxo leva R$ 30 a um destino "padaria + café", somando exatamente o total.
- AC-21: Given o Fluxo, when a pessoa toca em padaria, then acendem "padaria" e "padaria + café", e a ficha mostra a divisão, que soma o número de Para onde foi.
- AC-22: Given um intervalo de um mês inteiro e filtro vazio ou só de verbas, then o Ritmo mostra o ritmo ideal, a cota e "no passo atual"; given intervalo de outro tamanho ou chip de marcador, estabelecimento ou recado, then o Ritmo compara com o intervalo anterior e a opção de ritmo aparece apagada com o porquê.
- AC-23: Given o intervalo "este mês" no dia 6, then comparar usa o mês anterior até o dia 6; "3 meses" usa os 3 meses anteriores; personalizado de N dias usa os N dias anteriores.
- AC-24: Given "com previstos" ligado, then previstos lançados entram no gasto, e em aberto aparecem tracejados e separados, sem somar no total gasto; desligado, só o variável.
- AC-25: Given lançamentos estornados e recados riscados, then nenhum widget os soma; O que ficou fora os lista quando há algum no intervalo, junto dos recados na Pauta e dos previstos pulados.
- AC-26: Given lançamentos manuais, sem recado e parcelas k > 1, then Dia e hora não os conta e diz quantos ficaram de fora.

### Widgets (um AC por comportamento visível; detalhes de opções conforme a §5 do raw)
- AC-27: Margem mostra a margem do mês em que o intervalo termina, com o mês na frase; na página, uma régua por verba ordenável por risco · margem · A–Z; com chip de marcador ou estabelecimento, diz em que verba ele cai.
- AC-28: Total e costume compara o total do intervalo com o costume (média de 6 · média de 3 · o anterior) no mesmo trecho; Somar: tudo · só variável · só previstos.
- AC-29: Ritmo acumulado mostra a faixa de previstos no pé, a cota em fio duplo e "no passo atual"; Comparar: ritmo · intervalo anterior · costume.
- AC-30: Previstos do mês lista Vieram (com a diferença para o valor do previsto) e A caminho (com atrasados como "esperando desde …") do mês em que o intervalo termina.
- AC-31: História mostra até 3 frases na grade; a página tem todas as frases, cada uma levando à página do widget correspondente.
- AC-32: Fluxo verba → marcador com De: verba · fonte; Para: marcador · estabelecimento · os dois; Previstos dentro · fora; Comparar com o intervalo anterior.
- AC-33: Para onde foi com Agrupar: verba · marcador · estabelecimento; Métrica: soma · vezes · média; Ordem: maior · mudou mais.
- AC-34: O que cresceu mostra barras para os dois lados contra a média de 3 meses ou o intervalo anterior, com "só o que voltou 3 ou mais vezes".
- AC-35: Estabelecimentos que voltam mostra idas no intervalo, valor típico e selo "novo".
- AC-36: Primeira vez lista estabelecimentos e marcadores cuja primeira data de todo o histórico cai no intervalo; sem nenhum, some, e Tamanho ocupa a linha.
- AC-37: Tamanho mostra o histograma com a mediana e a vista por estabelecimento.
- AC-38: Com um só chip de estabelecimento, Retrato do estabelecimento entra no topo de Para onde e saem Estabelecimentos que voltam, O que cresceu e Primeira vez.
- AC-39: Calendário mostra o mapa de calor por dia e a lista "Dias mais pesados"; Métrica soma · contagem.
- AC-40: Dia da semana mostra a média por dia da semana, com um ponto por dia; Ordem seg a dom · maior primeiro.
- AC-41: Semanas mostra uma coluna por semana (início da semana dos Ajustes) com a marca do passo semanal; Agrupar: semana · semana do mês.
- AC-42: Mês a mês mostra 3 · 6 · 12 meses; Dividir: não · previstos e variável · por verba; "ano anterior" só habilita com 13 meses de dados.
- AC-43: Meses à frente mostra parcelas e previstos de hoje em diante (previstos "Todo mês" projetados além do horizonte de `planned_dates`), mesmo com intervalo passado, e diz "de hoje em diante".
- AC-44: Volta todo mês lista estabelecimentos presentes em todo mês da janela (6 · 12) com valor parecido e sem previsto ligado.
- AC-45: Dia e hora mostra a grade 7 dias × 6 faixas, contagem por padrão, "Só até R$ 50" ligado.

### Vazio e falha
- AC-46: Given nenhuma verba, then a Leitura mostra o vazio de hoje ("Nada para ler ainda."); given intervalo sem lançamento, then cada widget mostra a frase de vazio dele e a grade não pula.
- AC-F1: Given a leitura do Db lança exceção, then o comportamento é o mesmo das outras telas (sem tratamento novo; A-1). Sem teste de injeção enquanto A-1 valer.
- AC-F3: Given uma página de widget aberta, when o app é recriado (rotação ou morte do processo), then a pilha, o filtro e as opções voltam iguais.
- AC-F4: Given o Snackbar de "Só X" ainda na tela, when a pessoa troca de aba e então toca Desfazer, then nada quebra e a Leitura, que já zerou (D-5), continua no mês atual sem chips.
- AC-F2: Given o dia vira (meia-noite ou virada do mês) com a Leitura aberta, when o Db muda (version bump), then os widgets releem com o `today` atual (LocalToday).

## Touched boundaries
- SQLite: leituras novas por widget em `read {}` (Db.kt) — AC-F1 (A-1).
- Escritas escondidas em leituras (`fill(today)`, `ensureMonths`) e virada do dia com a tela aberta — AC-F2.
- Relógio (`LocalToday`, `Clock`) — AC-F2.
- Ciclo de vida Android: recriação e morte do processo com página de widget na pilha (D18) — AC-F3.
- Snackbar no escopo do app: Desfazer depois que a Leitura saiu da pilha — AC-F4.
