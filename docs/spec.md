# Margem — spec do produto

Status: **decidido** em 2026-10-09; revisado em 2026-10-14 (bloco 4, ciclo do recado: §2.5, §2.8, §2.10, §7.3, §7.4; bloco 5, Ajustes e importação: §4, §7.6; teto: §3, §7.1, §10; início do mês sem sumir verba: §3); em 2026-10-16 (legenda do lançamento: §2.10, §2.12, §7.1, §7.3, §7.4, §7.5; bloco 6, Leitura: §7.5; cabeçalho do teto: §7.1, §10; teto, T1–T10: §3, §7.1); em 2026-10-20 (legenda: quebras de linha em §2.12, nome da prévia em §7.3; apelidos iguais no ranking: §7.5); em 2026-10-22 (previsto, P1–P11 de [previsto-plan.md](previsto-plan.md): §2.12, §2.13, §7.2, §7.3, §7.4; S1; acentos no ranking, §7.5; formulário enxuto, verba numa folha e marcadores por busca: §2.5, §7.3); em 2026-10-23 (pendências E1 do formulário enxuto respondidas: §7.3). O que está em §10 espera resposta, uma por vez.
Escopo: só produto (o quê e por quê). Implementação: [architecture.md](architecture.md).
Vocabulário: [glossary.md](glossary.md). Identidade visual: [design_system.html](design_system.html).

## 1. Objetivo

Ajudar a pessoa a **ficar dentro das verbas que ela mesma definiu** e a **entender para onde o dinheiro vai**, com gráficos.
A anotação automática é a planilha se preenchendo sozinha: cada notificação de banco/cartão vira um recado que a pessoa só confirma.

O app trata **só gastos**. Entradas de dinheiro (salário, Pix recebido) não têm lugar; esses recados são riscados.
O estorno não é crédito: é só o registro de que uma compra não conta (§6).

## 2. Do recado ao lançamento

1. **Anotação automática** guarda as notificações das **fontes** escolhidas como **recados**.
2. Recados não decididos ficam na **Pauta**. Para cada um, a pessoa:
   - **Lança** — vira lançamento (no recado de estorno: **Registrar estorno**, §6); ou
   - **Risca** — não é gasto (mensagem, propaganda, entrada).
3. Riscar mostra **Desfazer** na hora; depois, o recado pode voltar à Pauta pela lista **Riscados** (em Ajustes).
4. **Lançar exige exatamente uma verba e pelo menos um marcador.**
5. **Sugestão:** se o estabelecimento já teve lançamento, verba e marcadores do último lançamento dele vêm pré-selecionados. Sem histórico, nada vem marcado.
   Verba e marcadores sugeridos **já contam** e vêm **tracejados**: o tracejado só diz que vieram da sugestão, não pede confirmação. O primeiro toque num marcador tracejado o tira, como num cheio. A verba tracejada fica cheia quando a pessoa escolhe uma verba na folha (§7.3), mesmo a sugerida. Lançar grava o que estiver escolhido, tracejado ou não (2026-10-22; antes, tocar num tracejado o confirmava).
6. Valor, estabelecimento e data vêm pré-preenchidos do texto do recado; a pessoa pode **emendar**.
7. **Lançamento manual:** a pessoa cria um lançamento sem recado (dinheiro, Pix de outro celular), com os mesmos campos e as mesmas exigências.
8. Apagar um lançamento devolve o recado dele à Pauta (o manual simplesmente some). Pede confirmação num diálogo, que diz o que volta à Pauta; não tem Desfazer.
   Se ele estava estornado, o estorno é apagado junto: o recado de estorno também volta à Pauta.
9. Apagar um marcador apaga os lançamentos que ficam sem nenhum marcador; os recados deles voltam à Pauta.
10. **Apelido** do estabelecimento substitui o texto do banco em todo lugar. Ao lançar, deixar o apelido em branco não o remove: ele fica como está. Num lançamento com legenda (§2.12), a legenda vem antes.
11. Recados que chegaram desde a última vez que a Pauta foi aberta aparecem como **novos**.
12. **Legenda** do lançamento: texto curto, opcional, que a pessoa escreve sobre um lançamento só ("Bolo do aniversário da Ana"). Quando existe, é o nome do lançamento nas listas; sem legenda, vale o apelido, senão o texto do banco.
    - Só deste lançamento: não vem na sugestão (§2.5), não é emenda (não marca "‸ emendado") e não entra na Leitura (§7.5).
    - Até 80 caracteres. Todo invisível nas pontas sai — espaço, tabulação, VT/FF/NEL, a família NBSP (U+00A0 e afins) e os controles — mas fica no meio do texto; só espaços = sem legenda; qualquer quebra de linha (LF, CRLF, CR ou invisível U+2028/U+2029) vira um espaço.
    - Na edição, apagar o texto tira a legenda (diferente do apelido, §2.10).
    - Apagar o lançamento (ou Desfazer logo depois de lançar) apaga a legenda junto: o recado volta à Pauta sem ela, sem aviso a mais.
    - Aparece no formulário (§7.3), em Lançamentos (§7.4), no detalhe da verba (§7.1) e na escolha da compra estornada (§7.3). Recados (Pauta, Riscados, widget da Pauta) não têm legenda; snackbars e diálogos não a mostram.
    - Lançamento de um previsto (§2.13) nasce com o nome do previsto como legenda.
13. **Previsto**: gasto que a pessoa já sabe que vem (aluguel, internet, conta de luz), anotado antes de acontecer com valor, nome, dia, verba e marcadores, à espera de um recado. Não é lançamento: **não conta** em verba, Leitura nem widget até ser lançado. Desenho: book 8.11 ([design_system.html](design_system.html#comp)).
    - **Criar**: lançamento manual com data à frente vira previsto (§7.3). Mesmas exigências de lançar: uma verba e ao menos um marcador.
    - **Repete**: "Só uma vez" ou "Todo mês". Todo mês mantém o dia; num mês sem ele (29–31), vale o último dia. Sem data de fim: para parar, apaga-se o previsto ou muda-se para "Só uma vez". "Repete" aparece sempre no formulário — lançamento manual (com qualquer data), Lançar recado e edição. Num lançamento de hoje ou do passado, "Todo mês" lança agora e cria os previstos a partir do mês seguinte; no Lançar recado e na edição de um lançamento com recado, o previsto nasce **já ligado** à fonte e ao texto do banco desse recado, com o nome do lançamento (legenda, senão apelido, senão texto do banco).
    - **Ligar**: dizer qual recado é o previsto. A primeira vez é à mão, no Lançar do recado ("É um previsto? Ligar", §7.3). A ligação guarda a **fonte e o texto do banco** do recado.
    - **Reconhecer**: da segunda vez em diante, recado da mesma fonte com o mesmo texto do banco (sem diferença de maiúsculas e acentos), chegado até **10 dias antes ou depois** do dia do previsto, é reconhecido como dele; o valor pode variar. Dois previstos para o mesmo recado: fica o de dia mais próximo. Fora da janela, é um recado comum, que pode ser ligado à mão. Recado sem texto do banco (ex.: Pix enviado sem estabelecimento lido) **nunca liga nem é reconhecido**: pode ser lançado com o previsto escolhido à mão, que fecha a ocorrência sem gravar ligação (R2).
    - **Lançar previsto**: o recado reconhecido lança direto da Pauta (§7.2), sem formulário: verba, marcadores e nome (como legenda) vêm do previsto; valor, estabelecimento e data vêm do recado.
    - **Valor diferente**: vale o do recado. Não é emenda (sem "‸ emendado") e o valor do previsto não aparece ao lado. O previsto do mês seguinte continua com o valor dele; só editar o previsto o muda.
    - **Sem recado**: o previsto **fica esperando**, depois do dia e da virada ("esperando desde 5 out"); a ocorrência do mês seguinte aparece no dia dela mesmo assim. Na folha do previsto (§7.4): **Lançar sem recado** (abre o lançamento manual preenchido, na data do previsto, sem ligação; só aparece quando o dia da ocorrência já chegou, R6), **Pular este mês** (some sem lançar; o mês seguinte continua), **Editar previsto** e **Apagar previsto** (este e os próximos meses; pede confirmação num diálogo: "Apagar o previsto Academia?", "Este e os próximos meses somem. O que já foi lançado continua."). "Salvar previsto" volta a Lançamentos sem snackbar; um previsto apagado com a tela aberta responde "Este previsto não existe mais." (2026-10-22).
    - Desfazer logo depois de lançar um previsto, ou apagar esse lançamento mais tarde, devolve o recado à Pauta e a ocorrência do previsto a "A caminho", esperando e ligada, como estavam (S1, 2026-10-22).

## 3. Verba e cota

- **Verba**: nome + **cota mensal**. Cada lançamento pertence a exatamente uma verba.
- **Mês da verba**: começa no **dia de início do mês**, escolhido em Ajustes, o mesmo para todas as verbas (padrão: dia 1).
  Vai do dia D até a véspera do dia D do mês seguinte. Se D não existe no mês (29–31), usa o último dia.
- **Mudar o dia de início não tira verba do mês atual**: se o mês atual passa a ser outro (ex.: dia 1 → 15 em 14 out volta para o mês que começou em 15 set), cada verba fica nele com a cota que tinha no mês atual de antes; se já tinha cota nele, fica a que estava.
- **Mudar a cota** vale do **mês atual em diante**. Cada mês guarda a cota que valia nele; a Leitura compara cada mês com a sua.
- **Virada**: a cota recomeça inteira todo mês. Sobra e excesso não passam para o mês seguinte; ficam só no histórico.
- **Teto** (opcional; sem teto por padrão): valor máximo para a **soma das cotas** do mês (ex.: a renda). É limite de planejamento, não de gasto: o gasto continua medido por verba, e não existe margem, ritmo ou estado do total.
  - **Passar do teto é permitido**: criar verba ou mudar cota que deixa a soma das cotas do mês atual acima do teto avisa enquanto digita (texto sob o campo) e de novo ao salvar (diálogo), e grava se a pessoa confirmar.
    Só avisa a mudança que **aumenta** a soma: baixar uma cota com a soma ainda acima do teto, ou só renomear, não avisa.
  - **Mudar o teto** pergunta ao salvar: **só este mês** (no mês seguinte volta o padrão) ou **deste mês em diante** (vira o padrão dos próximos meses). A pessoa pode tirar o teto.
  - Aparece só no cabeçalho de Verbas (§7.1) e nos avisos de cota; widget e Leitura não mostram o teto.
- **Verba criada no meio do mês** tem a cota cheia no mês inteiro (o ritmo ideal de hoje já conta as semanas passadas).
- **Apagar uma verba** apaga os lançamentos dela; os recados voltam à Pauta.
- **Margem** = cota − gasto do mês.
- **Gasto do mês** = soma das parcelas (§6; à vista = 1 parcela) dos lançamentos da verba que caem no mês, sem os estornados.

## 4. Passo semanal

Uma **visualização** de quanto cabe por semana. Não é limite e não muda com o que foi gasto.

- **Semanas**: semanas de calendário, com **dia de início da semana** escolhido em Ajustes (padrão: segunda), cortadas no mês da verba.
  A primeira e a última semana do mês podem ser parciais.
- **Passo padrão** de uma semana = cota × dias da semana no mês ÷ dias do mês.
  Ex.: cota R$ 600, mês de 30 dias → semana cheia R$ 140,00; semana de 2 dias R$ 40,00.
- **Editar**: a pessoa muda o passo de uma semana do mês (ex.: gastar menos no começo).
  As semanas **não editadas** se ajustam, proporcionalmente aos dias, para a soma dos passos continuar igual à cota.
- **Uma semana fica sempre automática**: quando só resta uma semana não editada, ela não pode ser editada (aparece em tinta 3, sem toque). Enquanto houver mais de uma, qualquer semana pode ser editada; a última não editada do mês absorve a diferença.
- **Voltar ao automático**: numa semana editada, a pessoa pode desfazer a edição; ela volta a se ajustar com as outras.
- **Limite da edição**: um passo aceita no máximo cota − outras semanas editadas; semana automática nunca fica abaixo de R$ 0,00. A mensagem diz o máximo possível.
- **Centavos**: passos automáticos são arredondados para baixo; a última semana não editada recebe a diferença.
  Ex.: R$ 100,00 em 3 semanas iguais → 33,33 · 33,33 · 33,34.
- Edições valem para aquele mês daquela verba. Mudar a cota redistribui só as semanas não editadas.
  Se a cota nova ficar abaixo da soma dos passos editados de um mês (do atual em diante), as edições daquele mês são apagadas e todas as semanas voltam ao automático.
  O app **avisa antes** (Confirmar/Cancelar) quando a cota nova vai apagar edições; sem aviso quando nada é apagado.
- **Mudar o dia de início da semana ou do mês** apaga as edições de passo do mês atual em diante; o app avisa antes (Cancelar/Mudar) quando há edição a apagar, e muda direto quando não há. Meses passados são recalculados com o dia novo.

## 5. Ritmo

- **Ritmo ideal** (hoje) = passos das semanas já fechadas do mês + passo da semana atual × dias corridos dela (com hoje) ÷ dias dela.
  Segue o plano editado: se a semana 1 foi baixada, o ritmo ideal também começa mais baixo.
- **Estados** da verba no mês:

| Estado | Quando |
|---|---|
| **No ritmo** | gasto ≤ ritmo ideal |
| **Acima do ritmo** | gasto > ritmo ideal e gasto ≤ cota |
| **Fora da margem** | gasto > cota (o excesso é dito em reais: "R$ 12,00 além") |

- **Sem tolerância**: um centavo acima do ritmo ideal já é Acima do ritmo.
- **No passo atual** (projeção) = gasto do mês × (soma dos passos do mês ÷ ritmo ideal de hoje). Segue o plano: quem está exatamente no ritmo projeta a cota.
  Sem projeção quando o ritmo ideal de hoje é R$ 0,00.

## 6. Estorno e parcelas

- **Estorno** não é lançamento; é só um registro: a compra estornada **não é somada** na verba nem na Leitura, no mês em que foi feita (o mês passado ganha margem retroativa se o estorno chega depois).
  Não pede verba nem marcador.
- **Registrar um estorno** = escolher a compra estornada:
  - um **lançamento** — ele deixa de contar; ou
  - um **recado ainda não lançado** — os dois recados (compra e estorno) saem da Pauta e nada conta.
- **Desfazer estorno**: snackbar com **Desfazer** logo após registrar; depois, a ação **Desfazer estorno** na compra estornada (em Lançamentos).
  A compra volta a contar e o recado de estorno volta à Pauta (o recado da compra não lançada também volta).
- **Parcelas**: ao lançar, a pessoa informa o **valor total** e o número de parcelas; a tela mostra a prévia ("10 × R$ 120,00").
  A parcela k conta na verba no k-ésimo mês a partir do mês da compra; a última parcela absorve os centavos. De 1 a 400 parcelas.
  Ex.: R$ 1.200 em 10× → R$ 120 por mês, em 10 meses.
- **Estorno de compra parcelada**: tira **todas** as parcelas, inclusive as de meses passados.
- **Apagar um lançamento estornado** apaga o estorno junto: o recado da compra e o recado de estorno voltam à Pauta.

## 7. Telas

Cinco abas: **Verbas** (início) · **Pauta** · **Lançamentos** · **Leitura** · **Ajustes**.

### 7.1 Verbas
- Lista das verbas do mês atual: nome, régua (gasto, marca do ritmo ideal, cota), margem, estado.
- **Cabeçalho com o teto** (§3): desenho do book, [8.10 Cabeçalho do teto](design_system.html) e tela 10.1: chapéu, "Dia 9 de 31.", linha fina e, embaixo, o fio do teto com a legenda. O teto não ganha frase na manchete.
  - **Fio do teto**: régua sem ritmo; trilho em fio forte, cotas em tinta (não é gasto: sem cor de estado), fio duplo de argila no teto, os mesmos 9% reservados ao excesso, em hachura.
  - **Legenda**: "cotas R$ 3.720,00 · teto R$ 4.100,00"; acima do teto, "+ 300" em lacre na faixa reservada (reais inteiros; com centavos só quando há: "+ 300,50"); teto só deste mês (o do mês difere do padrão), "teto R$ 4.400,00 · só outubro".
  - **Sem teto**: o fio pontilhado, "As cotas somam R$ 3.720,00." e o link "Pôr um teto". Sem verbas, a linha some. Mês sem teto com padrão que tem (tirado só deste mês): o mesmo, sem menção ao padrão.
  - **Toque**: do chapéu à legenda, um alvo só, que abre o teto. Leitor de tela: "Teto de outubro: R$ 4.100,00. Cotas somam R$ 3.720,00, a R$ 380,00 do teto. Tocar para mudar." (no teto: "fecham no teto"; acima: "R$ 300,00 além do teto").
  - **Diálogo**: "Teto de outubro", campo "Máximo para a soma das cotas", dica ao digitar ("As cotas de outubro somam R$ 3.720,00: ficam a R$ 680,00 do teto." / "…: ficariam R$ 220,00 além do teto. Dá para salvar assim."), ação "Tirar teto". O campo começa com o teto do mês (vazio sem teto); Salvar fica desabilitado em R$ 0,00, e tirar é só "Tirar teto", que aparece só quando o mês tem teto. Salvar pergunta "Vale para quais meses?": "De outubro em diante" · "Só em outubro" ("Em novembro, volta a R$ 4.100,00." ou "volta a não ter teto"); tocar a opção salva. Tirar pergunta "Tirar o teto de quais meses?", com as mesmas opções.
  - **Snackbar** com Desfazer: "Teto de R$ 4.400,00 só em outubro." · "Teto de R$ 4.400,00 de outubro em diante." · "Outubro em diante sem teto." · "Outubro sem teto."; Desfazer volta o padrão e o teto do mês ao que eram.
  - **Textos sem desenho no book** (decididos em 2026-10-16): dica no teto exato, "As cotas de outubro somam R$ 4.100,00: fecham no teto."; ao tirar, a sub-linha de "De outubro em diante" é "Os próximos meses ficam sem teto."; leitor de tela sem teto: "As cotas de outubro somam R$ 3.720,00. Sem teto. Tocar para pôr um teto."; corpo de "Passar do teto?": "As cotas de outubro somariam R$ 4.400,00, R$ 300,00 além do teto de R$ 4.100,00. O teto é só um limite de planejamento."
  - **Nova verba / cota**: sob o campo, "Com esta cota, as cotas de outubro somam R$ 4.400,00: R$ 300,00 além do teto."; ao salvar, "Passar do teto?" · Voltar · "Criar mesmo assim" / "Salvar mesmo assim". Em Editar verba, quando a cota nova também apaga passos editados (§4), o diálogo do teto vem primeiro e o dos passos depois; Voltar em qualquer um não grava nada.
- **Ordenar**: seletor no topo — **risco** (padrão; mesma regra do widget, §8.1) · **manual** (arrastar) · **A–Z**. A escolha é lembrada.
- Criar verba: nome + cota mensal, com a prévia dos passos semanais do mês atual.
- **Detalhe da verba**: seletor de mês (do primeiro mês da verba até o atual); régua; passos semanais (editáveis, com "Voltar ao automático", §4); lançamentos do mês com a soma corrida e a marca "cota atingida aqui" (parcelada conta a parcela do mês; estornada aparece riscada e não soma; com legenda, a linha é a de Lançamentos, §7.4); editar nome e cota; apagar.
- Vazio: explica o que é uma verba e como criar a primeira.

### 7.2 Pauta
- Recados esperando decisão, mais recentes no topo: fonte, valor e estabelecimento lidos, hora.
- Ações: Lançar (abre a tela de lançar; no recado de estorno, **Registrar estorno**) e Riscar (com Desfazer). Sem gestos de deslizar.
- Recados **novos** (§2.11) ficam destacados.
- **Recado reconhecido** de um previsto (§2.13): o cartão ganha a faixa "É o previsto Internet" (verba · marcadores · dia) e as ações **Não é** (tira a faixa enquanto a tela está aberta, sem gravar nada; reaberta a Pauta, o recado volta reconhecido), Riscar e **Lançar previsto** (lança direto). Tocar no corpo do cartão abre o Lançar já ligado. Snackbar: "Lançado: Internet, em Casa. O próximo é 10 nov." com Desfazer ("O próximo …" só em "Todo mês").
- **Parece propaganda**: recado do qual não se leu valor nem estabelecimento ganha esse selo, e o botão vira "Lançar mesmo assim".
- Aviso de **anotação pausada** quando o acesso às notificações está desligado.
- Vazio: "Pauta limpa."

### 7.3 Lançar / editar lançamento
- Valor total, estabelecimento (com apelido), legenda (opcional), data e hora, verba (obrigatória), marcadores (≥1), número de parcelas.
- **Estabelecimento em dois campos**: o principal é o **apelido** (vale para este e os próximos lançamentos do estabelecimento). Embaixo, "No banco: …"; tocar vira campo para **emendar o texto do banco** (marca emendado, com Desfazer). No lançamento manual há um campo só, o nome.
- **Legenda** (§2.12), recolhida: um link "+ Legenda · só deste lançamento" logo abaixo do estabelecimento (do "No banco" no recado; do nome no manual). Tocar abre o campo no lugar, em itálico, com a dica "Aparece no lugar de Padaria do Zé, só neste lançamento."; com legenda, a edição já abre com ele à vista.
  - O ✕ esvazia o campo e o recolhe. Na edição, até Salvar, mostra "legenda tirada: ~~Bolo do aniversário da Ana~~ Desfazer" (o desenho da emenda, sem "‸").
  - Perto do limite, a contagem aparece à direita da dica ("72/80"); em 80 o campo para de aceitar.
  - Desenho: [legenda recolhida](mockups/descricao-b-recolhida.html).
- **Data e hora** por um seletor próprio (book 8.9): grade do mês com a semana começando no dia dos Ajustes, hoje marcado, dias futuros desabilitados (lançamento é gasto feito) e hora HH:MM. **Exceção**: no lançamento manual, dias futuros são aceitos, e escolher um faz do lançamento um previsto (§2.13): a faixa "Data à frente: vira previsto. Fica em Lançamentos esperando o recado, sem contar na verba." e o botão "Salvar previsto".
- **Repete** (§2.13): "Só uma vez" · "Todo mês, dia 12", sempre à vista. No Lançar recado com "Todo mês", a dica "Em novembro, o recado “SPOTIFY” chega ligado."; com o recado ligado a um previsto, o campo sai (a repetição é a do previsto, dita na faixa). Na **edição**, o "Repete" abre como está o previsto do lançamento (o que ele criou ou o de que ele veio): "Todo mês" de novo não cria outro; trocar para "Só uma vez" e salvar apaga esse previsto (este e os próximos meses; os lançamentos feitos continuam) (2026-10-22).
- **Ligar a um previsto**: no Lançar de um recado comum e no lançamento manual (R6b: pago antes do dia; fecha a ocorrência sem gravar ligação, que o manual não tem texto do banco), o link "É um previsto? Ligar" (sob a citação, como "É um estorno?") abre "Qual previsto é este recado?" ("Qual previsto é este lançamento?" no manual), com todos os previstos em aberto, os mais prováveis no topo (dia perto da data do recado, valor perto; o primeiro leva "mais provável"), e o botão "Ligar à Conta de luz". Ligado, a faixa "Ligado à Conta de luz · Todo mês, dia 12. Verba, marcadores e nome vieram do previsto. Desligar" toma o lugar do link; verba e marcadores vêm cheios (não tracejados) e o nome entra como legenda. "Desligar" volta a tela ao que era.
- **Verba** (2026-10-22, [mock A](mockups/lancamento-a-modal-busca.html)): um campo só, com a verba escolhida e a margem dela ("Lazer · margem R$ 34,00"); vazio, "Escolher a verba"; sugerida, tracejado (§2.5). Tocar abre a folha "Em qual verba entra?":
  - busca "Buscar verba" no topo, sempre, sem diferença de maiúsculas e acentos;
  - as verbas na ordem da aba Verbas, cada uma com a margem atual ("R$ 23,50 além" quando passou), sem prever o efeito deste valor (isso é da prévia); busca sem achado: "Nenhuma verba com “xyz”." (2026-10-23);
  - "Nova verba" no fim: vai para a tela de criação; ao voltar, a verba criada vem escolhida (cheia). O mesmo vale para o "Nova verba" de "Crie uma verba antes de lançar." (2026-10-23).
  - Um toque escolhe e fecha. A margem, no campo e na folha, é a do mês da data do formulário, o mesmo da prévia. Verba sem cota nesse mês (data antes de ela existir): o campo diz "sem cota em setembro" e a folha "sem cota" (2026-10-23).
- **Marcadores** (2026-10-22): só os escolhidos aparecem, como chips (cheios; tracejados os sugeridos); um toque em qualquer um o tira. A lista inteira nunca aparece no formulário. Embaixo, o campo "Buscar ou criar marcador":
  - com foco e sem texto, "Mais usados": os 8 marcadores mais usados no geral, fora os já escolhidos;
  - digitando, todos os que contêm o texto, sem diferença de maiúsculas e acentos: os que começam com ele primeiro, depois os mais usados, cada um com quantos lançamentos tem ("33 lançamentos"). Os já escolhidos também aparecem, com ✓ e nada mais, e tocar neles não faz nada. A lista rola sozinha e cabe acima do teclado: nada fica atrás dele (2026-10-23);
  - por último, "Criar “festa junina”" (já em minúsculas e sem espaços nas pontas, com "novo, só é gravado ao lançar"), que não aparece quando o texto é o nome exato de um marcador ("cafe" com "café" existente ainda oferece criar, 2026-10-23); sem nenhum achado, "Nenhum marcador com “pizzaria”." e só essa linha;
  - tocar num resultado marca, limpa o texto e mantém o foco, para marcar vários seguidos; "Pronto" no teclado marca o marcador de mesmo nome, senão cria;
  - os chips ficam na ordem em que foram escolhidos (sugestão ou previsto primeiro), e o tracejado também leva ✕ (2026-10-23);
  - com o teclado aberto na busca, o rodapé (Riscar / Lançar) fica atrás dele.
- Mostra o texto original do recado e marca o que foi emendado.
- Enquanto falta verba ou marcador, o botão de lançar fica desabilitado e a tela diz o que falta.
- **Prévia**: mostra o efeito na verba antes de confirmar ("Mercado: margem R$ 300,00 → R$ 254,00"). O nome é sempre o da verba; a legenda não entra aqui (2026-10-20).
- **Recado de estorno**: em vez dos campos, a pessoa escolhe a compra estornada (lançamento ou recado, §6); o app sugere a mais provável. O botão é **Registrar estorno**.
  - A escolha é a lista completa de candidatos, os mais prováveis no topo, com **busca** (lupa) por legenda, apelido, texto do banco ou valor, sem diferença de maiúsculas e acentos ("estação" acha LOJA ESTACAO).
  - Candidato com legenda: a legenda em itálico (uma linha, "…" se passar) e "em" + o estabelecimento embaixo.
  - Prévia na verba quando a compra é um lançamento ("Casa: margem R$ 738,70 → R$ 798,60").
- **Leitura errada**: a pessoa troca na própria tela. Em Registrar estorno, "Não é estorno? Lançar como compra"; no Lançar de um recado comum, "É um estorno? Registrar estorno".
- **Na edição**: "Apagar lançamento" (§2.8); compra estornada mostra "estornada" e **Desfazer estorno**.
- Se o recado deixou de estar na Pauta enquanto a tela estava aberta (lançado ou riscado por outro caminho), lançar é recusado: a tela avisa e volta.

### 7.4 Lançamentos
- **A caminho** (§2.13, book 8.11): os previstos ficam no topo, num grupo que **sempre abre recolhido** (não lembra; aberto, fica aberto enquanto a tela estiver aberta), para a lista abrir nos lançamentos feitos. Recolhido, é uma linha: rubrica "A caminho", "4 previstos · R$ 539,80" (soma dos valores; não conta em verba) e "1 com recado na Pauta · 1 atrasado" (só as partes que existem, em tinta 2, sem cor de estado). Sem previsto, a linha não existe. Todo previsto entra em "A caminho" 10 dias antes do dia dele (quando o recado dele já pode chegar, §2.13): o do mês seguinte e também um "Salvar previsto" com data longe, que fica guardado até lá (decisão do usuário, 2026-10-22). Aberto, os previstos pelo dia, o atrasado primeiro: folhinha do dia tracejada (em tinta 3 no atrasado), nome, "verba · marcadores · todo mês", "recado na Pauta" (anil) ou "esperando desde 5 out", e o valor em tinta 2. Tocar num previsto abre a folha dele (§2.13).
- Todos os lançamentos, mais recentes no topo, **agrupados por dia** como a Pauta: estabelecimento, valor, verba, marcadores; "‸ emendado" quando foi emendado; compras estornadas riscadas como "estornada" (o estorno não tem linha própria).
- **Com legenda** (§2.12): a legenda é o nome, em itálico, até 2 linhas ("…" se passar); embaixo, "em Padaria do Zé" (apelido, senão texto do banco; sem estabelecimento, essa linha não existe); depois a linha de sempre. Estornada: legenda e "em …" em tinta 3, sem risco; só o valor riscado, como o nome hoje.
- Parcelada mostra a parcela do mês atual ("Parcela 3 de 10", com o valor dela); sem parcela no mês atual, mostra o total e "10×".
- Compra estornada: ação **Desfazer estorno** (§6).
- Tocar abre a edição (7.3). Botão de **lançamento manual**.

### 7.5 Leitura
- **Um mês para a página toda**: seletor de mês no topo (do primeiro mês com verba até o atual; "1 a 31 · até sex, 9" no mês atual). Ritmo, ranking e calendário são desse mês; Verbas por mês termina nele.
- **Ritmo do mês**: gasto acumulado × plano dos passos (ritmo ideal dia a dia), por verba, com a projeção "no passo atual".
  - **Uma verba por vez**: o nome dela, na linha da seção, abre a escolha entre as verbas com cota no mês; vale também para Verbas por mês. Padrão: a mais em risco no mês (ordem do §8.1). A escolha fica enquanto a tela está aberta; num mês em que a verba não tem cota, volta ao padrão.
  - Mês atual: o resto do mês sombreado e em branco; frase "+ R$ 51,10 sobre o ritmo ideal. No passo atual, o mês fecharia em R$ 796." (abaixo do ritmo: "R$ 20,00 abaixo do ritmo ideal. …"; fora da margem: "R$ 12,00 além da cota. …"; sem projeção quando o ritmo ideal de hoje é R$ 0,00, §5, ou quando nada foi gasto no mês).
  - Mês passado: o mês inteiro, sem sombra e sem projeção; frase "Fechou em R$ 1.180,00, R$ 60,00 dentro da cota." ou "… R$ 12,00 além da cota."
- **Verbas por mês**: a verba escolhida nos 6 meses até o mês do topo, gasto × cota de cada mês (a cota de cada mês, §3), excesso em hachura com o valor. Meses antes do primeiro mês da verba ficam no lugar, vazios. Frase: "Dois meses fora da margem." / "Nenhum mês fora da margem."
- **Marcadores e estabelecimentos**: ranking **por valor** no mês do topo, com a contagem ao lado (estabelecimentos pelo apelido; o mesmo apelido é o mesmo lugar, sem diferença de maiúsculas e acentos; a legenda não entra).
  - Seletor "Marcadores · Estabelecimentos" numa seção só; 6 linhas e depois "Ver todos (n)", que abre o resto no lugar. A escolha fica enquanto a tela está aberta.
  - Um lançamento com vários marcadores conta inteiro em cada um; a tela avisa que a soma dos marcadores pode passar do total.
  - Frase: "feira puxa a fila."
- **Calendário de gastos**: mapa de calor por dia do mês do topo, com a semana dos Ajustes. Degrau 0 = sem gasto; degraus 1–4 em faixas iguais: degrau = maior dia do mês ÷ 4, arredondado para cima em R$ 10; o dia vai ao degrau ⌈gasto ÷ degrau⌉. Legenda "cada degrau ≈ R$ 60". Frase pelo dia da semana com mais gasto: "Os sábados pesam." e "31% do mês em quatro sábados".
- Ranking e calendário contam a compra parcelada **pela data de cada parcela**, como o Gasto do mês (§3); a contagem do ranking é de lançamentos com parcela no período. Compras estornadas não contam.
- **Frases** de conclusão por modelos fixos (acima); sem anotações com fio-guia.
- **Acessibilidade**: cada gráfico tem descrição para o TalkBack com a frase de conclusão e os números principais; sem tabela equivalente.
- **Vazio**: sem verba, "Nada para ler ainda."; com verba mas nada lançado no mês, Ritmo e Verbas por mês desenham em zero, e ranking e calendário dizem "Nada lançado em outubro." no lugar do gráfico.
- **Cor da verba** (book 9.1): pela ordem de criação entre as verbas que existem (a primeira é Índigo); da 7ª em diante, "Outras". Apagar uma verba muda a cor das mais novas.

### 7.6 Ajustes
- Fontes (quais apps são anotados).
- **Riscados**: os recados riscados, mais recentes no topo; **Trazer de volta** devolve o recado à Pauta na hora, sem Desfazer (se foi engano, risca de novo na Pauta).
- **Marcadores** (§2.9): lista com quantos lançamentos usam cada um. Tocar abre o nome para renomear; se o nome digitado já é de outro marcador, o botão vira **Juntar com …** e os lançamentos passam para ele. **Apagar** pede confirmação num diálogo que diz quantos lançamentos ficam sem marcador e somem (os recados deles voltam à Pauta).
- **Dia de início do mês e dia de início da semana**, numa tela própria: o dia e a semana são rascunho até **Salvar**. Salvar avisa antes quando vai apagar edições de passo (§4).
- Tema: sistema (padrão) · claro · escuro. Vale também para os widgets.
- Ordem do widget de Verbas: risco (padrão) · manual · A–Z.
- **Backup**: exportar grava uma cópia do banco num arquivo escolhido pela pessoa. Importar **restaura** o backup inteiro no lugar dos dados atuais: pede confirmação num diálogo antes; arquivo que não é backup do Margem é recusado com aviso, sem mudar nada.
- **Importar do cobudget**: traz **só os recados** de um backup da última versão do app antigo; fontes, marcadores, apelidos e lançamentos não vêm. Os riscados lá entram em Riscados; os outros, inclusive os que tinham lançamento, entram na Pauta, para lançar ou riscar. Recados repetidos (mesma regra da anotação automática) não entram, por isso importar de novo quase nunca duplica algo (só, raramente, um recado sem hora própria na notificação). A tela diz quantos entraram na Pauta, quantos em Riscados e quantos não entraram. Arquivo que não é backup do cobudget é recusado com aviso, sem mudar nada.

## 8. Widgets

Os dois são redimensionáveis de 2×2 a 4×4; o conteúdo se adapta. Tema segue o ajuste do app.
Tocar em qualquer ponto abre a aba correspondente (Verbas ou Pauta).

### 8.1 Verbas
- Mostra **todas** as verbas, cabendo quantas o tamanho permitir (2×2 = 1, 4×2 = 3, 4×4 = 6), na ordem escolhida em Ajustes. Padrão: das mais em risco para as menos:
  1. Fora da margem — maior excesso em R$ primeiro;
  2. Acima do ritmo — maior diferença para o ritmo ideal primeiro;
  3. No ritmo — menor margem em % da cota primeiro.
- Cada verba: nome, margem, régua com a marca do ritmo.

### 8.2 Pauta
- Contagem de recados e os mais recentes (valor, estabelecimento): 2×2 = só a contagem, 4×2 = + 2 recados, 4×4 = + 5.
- Sem ações no widget.

## 9. Fora do escopo

- **Notificações do próprio app**: nenhuma; os widgets cumprem esse papel.
- **Onboarding**: não há; o app abre em Verbas, e os estados vazios e o aviso de anotação pausada guiam a pessoa.
- **genda**: fica fora deste spec.
- **Entradas de dinheiro**: §1.

## 10. Em aberto

| # | Questão |
|---|---|
| — | Nada em aberto. |
