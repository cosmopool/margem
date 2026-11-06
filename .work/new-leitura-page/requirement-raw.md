# Leitura v2: grade por contexto, busca única e páginas de widget

Fonte visual e de comportamento: `docs/mockups/analise-v8-widgets.html` (UI e widgets).
Catálogos de onde vieram os widgets: `docs/mockups/analise-v4-ctx-quanto.html`, `analise-v4-ctx-para-onde.html` e `analise-v4-ctx-quando.html`.
Pele: `docs/mockups/analise-v4-ui-1-app.html` (seção "1b · Mapa": cada elemento → componente do app).
Substitui a tela Leitura atual (`androidApp/.../ui/Insights.kt`).

## 1. Filtro (consulta da Leitura)

R1. **Intervalo de dias.** A Leitura filtra por um intervalo de-até em dias.
- Atalhos: este mês, mês passado, 3 meses, 6 meses, personalizado.
- O personalizado se escolhe num calendário: o primeiro toque marca o início, o segundo o fim.
- O seletor abre numa folha de baixo; é a única folha da Leitura.

R2. **Campo único com chips.** Um campo "Buscar verba, marcador, lugar ou aviso" faz autocompletar.
- As sugestões vêm em seções fixas: Verbas · Marcadores · Lugares · Avisos.
- Cada sugestão mostra a contagem e a soma no intervalo.
- Lugar casa pelo apelido ou pelo texto do banco. Aviso casa pela fonte ou pelo texto do recado.
- Escolher uma sugestão vira um chip, com o tipo indicado.
- Grupo e busca, que hoje são separados, viram essa coisa só.

R3. **Os chips somam com OU.** Um lançamento entra se bater com qualquer chip (verba ‖ marcador ‖ lugar ‖ aviso).
- Entre os chips aparece "ou".
- Sem chip, entra tudo.

R4. **Linha de resumo** sob o filtro: total, número de lançamentos e "até hoje" quando cabe.

R5. **Separar.** Com 2 ou mais chips aparece Separar.
- Nos widgets que suportam, cada chip vira uma série com cor própria. A cor segue o chip, não a posição.
- Um lançamento que bate em dois chips aparece uma vez, em "a + b".
- Widgets que suportam: Ritmo acumulado, Total e costume, Fluxo, Calendário, Dia da semana, Semanas, Mês a mês, Meses à frente e Dia e hora.

R6. **Abertura.** A Leitura abre num intervalo padrão (ver Q2).

## 2. Estrutura da tela

R7. **Sem abas no topo.** A tela é uma grade de widgets de meia ou largura inteira, agrupados em três contextos, nesta ordem:
- **Quanto** (total e ritmo)
- **Para onde** (verba, marcador, lugar)
- **Quando** (dias, semanas, meses)

R8. **Título de contexto (cabeçalho "b" do v6/v7).**
- Composição: rótulo pequeno, nome em Newsreader 29, pergunta curta em itálico e fio de tinta.
- Barra presa: quando o título sai da tela, uma barra de 44 px com o nome do contexto fica presa sob a TopBar até o próximo contexto empurrá-la.

R9. **Widget com fita lateral (variação 3 do v5).**
- Sem cartão nem cápsula: cada widget pende de um fio vertical de 2 dp.
- Num par de meias, o fio do widget da direita é o divisor.
- O Fluxo usa o fio duplo argila.
- Composição: rótulo, frase de uma linha e gráfico pequeno.
- Pressionado: o fio vira tinta e a área afunda.

R10. **Busca que recolhe (v7).**
- Ao rolar além da altura do cabeçalho, o filtro inteiro (campo, chips, intervalo, resumo, Separar) recolhe com animação.
- Recolhida, a TopBar mostra uma lupa e o intervalo escrito ao lado. Com chips ativos, a lupa leva um selo com o número de chips.
- Tocar na lupa reabre o filtro na posição atual e esmaece o conteúdo. Tocar fora ou rolar mais de 12 px recolhe de novo.
- Voltar ao topo, ou voltar de uma página de widget, reabre o filtro.
- Rolar para cima não reabre (ver Q5).

R11. **Pele do app.** Cada peça visual sai dos tokens e componentes existentes (Theme.kt, Components.kt), conforme o mapa da ui-1:
- barras de ponta reta;
- calor em quadrados sem raio;
- cota em fio duplo argila;
- ritmo ideal tracejado;
- dias por vir sombreados;
- OptionPicker para as opções e Check para os interruptores;
- DayHeader e TransactionLine nas listas;
- Snackbar para o Desfazer.

Peças novas no design system: barra presa, cabeçalho que recolhe e IconButton com selo.

## 3. Página do widget

R12. **Tocar num widget empurra uma página inteira, com voltar; nunca modal.** A página tem, de cima para baixo:
- o filtro, editável;
- a frase;
- a barra de opções, só com as que valem para aquele widget;
- o gráfico grande;
- o pedaço tocado;
- a lista de lançamentos.

R13. **Lista de lançamentos da página.** Ordenável. As ordens padrão são **Maiores** e **Recentes**; alguns widgets têm ordens próprias.
- Maiores numera e mostra a parte de cada lançamento.
- Recentes agrupa por dia, com o total do dia.

R14. **Mudar a consulta de dentro do widget.**
- Tocar num pedaço oferece "Só X" (troca os chips) e "+ ou X" (soma um chip).
- Dia, semana, mês ou trecho arrastado oferecem "Usar como intervalo".
- Editar o filtro no topo da página também muda a consulta.
- A mudança vale para toda a Leitura: volta para a grade com a consulta nova e um Snackbar com Desfazer.
- Exceção: "Mês a mês de X" abre o Mês a mês já filtrado.

R15. **Opções da página.** As escolhas de opção valem enquanto a página está aberta (ver Q6).

## 4. Regras de conta

R16. **Nada se divide (regra C).**
- Todo número de marcador conta o lançamento inteiro: ranking, filtro, frases e Mês a mês de ….
- No Fluxo, um lançamento com vários marcadores vai para um destino próprio ("padaria + café"), e o Fluxo soma exatamente o total.
- Tocar num marcador acende o destino simples e os combinados; a ficha mostra a divisão, que soma o número do ranking.

R17. **Ritmo ideal, cota e projeção** só aparecem com um mês inteiro e filtro vazio ou só de verbas, porque a cota é da verba.
- Fora disso, o Ritmo compara com o intervalo anterior equivalente.
- A opção aparece apagada, com o porquê.

R18. **Comparações seguem o intervalo:**
- mês: o mês anterior até o mesmo dia;
- 3 ou 6 meses: os mesmos meses antes;
- personalizado: os N dias anteriores.

R19. **Previstos.**
- Em aberto, um previsto não é gasto. Os widgets de "para onde", "quando" e "tamanho" mostram o variável por padrão, com a opção "com previstos" onde existe.
- Previsto em aberto não tem lugar nem fonte, então só entra num filtro de lugar ou aviso depois de lançado ou ligado.

R20. **Estornos e riscados** não contam em nenhum widget (spec atual).

R21. **Hora do dia** só vale para lançamentos que vieram de recado, na parcela 1 e sem previstos.
- O widget que usa a hora diz quantos lançamentos ficaram de fora.

## 5. Widgets (grade padrão do v8)

Opções e ordens conforme o v8; os detalhes de cada um estão nos catálogos v4-ctx.

### Quanto
| Widget | Tamanho | Pergunta | Opções na página | Lista |
|---|---|---|---|---|
| Margem | meia | Quanto ainda cabe? Régua do total. Na página, uma régua por verba; com chip de marcador ou lugar, diz em que verba ele cai | ordem: risco · margem · A–Z | Maiores · Recentes |
| Total e costume | meia | Este total é normal? Mesmo trecho contra os meses anteriores, com link "Ver o que mudou ›" | Costume: média de 6 · média de 3 · o anterior; Somar: tudo · só variável · só previstos | Maiores · Recentes |
| Ritmo acumulado | inteira | Estou no plano? Acumulado × ritmo ideal, faixa de previstos no pé, cota em fio duplo, "no passo atual" | Comparar: ritmo · intervalo anterior · costume; arrastar = intervalo | Maiores · Recentes |
| Previstos do mês | meia | O que veio, o que vem, o que atrasou, no dia de cada um, com a diferença do valor | — | Vieram · A caminho |
| História | meia | Até 3 frases; tocar abre a página da História, em que cada frase leva ao widget | — | — |
| O que ficou fora | faixa | Recados na Pauta, estornos e previstos pulados no intervalo. Só aparece quando há algo | — | — |

### Para onde
| Widget | Tamanho | Pergunta | Opções na página | Lista |
|---|---|---|---|---|
| Fluxo verba → marcador | inteira, destaque | De onde sai e para onde vai? | De: verba · fonte; Para: marcador · lugar · marcador e lugar; Previstos: dentro · fora; Comparar: nada · intervalo anterior; tocar acende o caminho | Maiores · Recentes |
| Para onde foi | meia | Onde vai o dinheiro? | Agrupar: verba · marcador · lugar; Métrica: soma · vezes · média; Previstos: marcados · fora; Comparar; Ordem: maior · mudou mais | Maiores · Recentes |
| O que cresceu | meia | Onde está escapando? Barras para os dois lados contra a média; tocar oferece Só, + ou e Mês a mês de … | contra: média de 3 meses · intervalo anterior; só o que voltou 3 ou mais vezes | Maiores · Recentes |
| Lugares que voltam | inteira | Onde volto sempre? Idas no intervalo, valor típico, selo "novo" | ordens: vezes · valor · … | Maiores · Recentes |
| Primeira vez | meia | O que é novo? Lugares e marcadores vistos pela primeira vez. Some quando não há nada, e o Tamanho ocupa a linha | — | Maiores · Recentes |
| Tamanho | meia | Muito miúdo ou pouco graúdo? Histograma com mediana; vista "por lugar" | — | Maiores · Recentes |
| Retrato do lugar | inteira, condicional | Só com filtro de um lugar só: entra no topo, e saem Lugares que voltam, O que cresceu e Primeira vez | — | — |

### Quando
| Widget | Tamanho | Pergunta | Opções na página | Lista |
|---|---|---|---|---|
| Calendário | inteira | Que dias pesam? Mapa de calor por dia, com a lista "Dias mais pesados" | Métrica: soma · contagem; Com previstos; Separar | Maiores · Recentes |
| Dia da semana | meia | Qual dia da semana pesa? Barras da média, com um ponto por dia | Métrica: média · soma · contagem; Ordem: seg a dom · maior primeiro; Com previstos; Separar | Maiores · Recentes · Hora |
| Semanas | meia | Há semana ruim? Uma coluna por semana, com o passo semanal | Agrupar: semana · semana do mês; Métrica: soma · contagem; Comparar: passo · média · nada; Com previstos; Separar | Maiores · Recentes |
| Mês a mês | meia | Está subindo? | Métrica: soma · contagem · média; Meses: 3 · 6 · 12; Dividir: não · previstos e variável · por verba; Comparar: nada · média · ano anterior (só com 13 meses de dados); Separar | Maiores · Recentes |
| Meses à frente | meia | Quanto parcelas e previstos já pesam? Ignora o intervalo: sempre de hoje em diante, e diz isso | Meses: 3 · 6 · 12; Dividir: parcela e previsto · por verba; Separar | Recentes · Maiores |
| Volta todo mês | meia | Que gasto se repete e não virou previsto? Lugar todo mês com valor parecido; tira de pontos por mês | Olhar: 6 · 12 meses; Ordem: valor · meses · dia do mês; Mostrar: sem previsto · com previstos | Recentes · Maiores |
| Dia e hora | meia | Que dia e hora gasto por impulso? Grade de 7 dias × 6 faixas de hora | Métrica: contagem · soma; Só até R$ 50; Separar | Hora · Maiores · Recentes |

Fora da grade padrão: Passos da semana (o passo já está em Semanas), Fontes, Marcadores juntos e Retrato do marcador.

## 6. Dados

R22. **Sem mudança de schema.** Consultas novas:
- fonte de um lançamento (lançamento → recado → app);
- primeira data de cada estabelecimento e de cada marcador;
- previstos de todas as verbas, juntos;
- em que verbas cai um marcador;
- previstos "Todo mês" projetados em memória para os meses à frente (`planned_dates` só vai até o mês atual).

## 7. Não-objetivos
- Esconder, reordenar ou redimensionar widgets pela pessoa.
- "Salvar como previsto" a partir de Volta todo mês (ver Q8).
- "Não é" para dispensar um falso positivo de Volta todo mês (exigiria tabela nova).
- Filtro com NÃO / exclusão.
- Teto na Leitura (o spec §3 não deixa).

## 8. Em aberto (decidir no /req-gather)
- Q1. "Aviso" como tipo de chip não está no glossário, que tem fonte e recado. Entra no glossário, ou o tipo vira "recado"? Ainda: "lugar" ou "estabelecimento"? E "intervalo" no lugar de "período"?
- Q2. A Leitura abre no mês atual (ralo nos primeiros dias) ou no mês passado?
- Q3. A grade padrão tem 18 widgets mais a faixa. Esconder de fábrica Dia e hora e Volta todo mês?
- Q4. Margem e Previstos do mês leem só o mês do fim do intervalo. Isso basta, com o mês escrito na frase?
- Q5. Rolar para cima não reabre a busca (escolha do mock). Confirmar?
- Q6. O filtro e as opções de cada widget ficam lembrados ao sair da Leitura?
- Q7. "Costume" entra no glossário?
- Q8. Volta todo mês ganha "Salvar como previsto", que abre o lançamento manual já preenchido?
- Q9. Navegação: os mocks usam 4 abas e o app tem 5 (Verbas, Pauta, Lançamentos, Leitura, Ajustes). A Leitura v2 mantém as 5 abas atuais?
- Q10. Nome da área: "Leitura", como no app e no glossário, ou "Análise"?
