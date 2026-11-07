```
Reescreve a Leitura como grade de widgets com filtro por intervalo e chips
```

```md
## Descrição

Antes, a Leitura mostrava um mês por vez com 4 gráficos fixos (Ritmo, História, Ranking, Calendário), navegação mês a mês e abas de Marcadores/Estabelecimentos.

Agora a Leitura tem um filtro único no topo: um intervalo de dias e um campo com chips (verba, marcador, estabelecimento, recado) que se somam por OU. Abaixo, uma grade de widgets organizada em três contextos — Quanto, Para onde, Quando — e cada widget abre uma página própria com filtro editável, opções, gráfico grande e lista Maiores/Recentes. A busca recolhe com a rolagem e volta pela lupa.

## Alterações Propostas

- `Types.kt`: `LeituraQuery` (intervalo + chips + Separar) e `Chip`/`ChipKind` como valor; `LeituraQuery()` vazio = sem filtro.
- `Db.kt`:
  - `leituraLines(q)`: uma leitura com filtro e OU dos chips em SQL; os widgets agrupam em Kotlin.
  - `window(month)`: janela do mês, para a tela.
  - `read()`/`importFrom`: a conexão de leitura é tomada sem cancelamento. Antes, uma leitura cancelada enquanto esperava conexão podia perder o leitor de vez; com dois perdidos, toda leitura do app travava. A Leitura nova, com ~20 widgets lendo e cancelando juntos, tornava isso frequente.
- `Insights.kt`: reescrito — filtro, grade em três contextos com título preso, cabeçalho que recolhe, folha de intervalo, os widgets e `LeituraWidgetPage`. Ações da página (Só / + ou / Usar como intervalo) mudam a Leitura inteira com Desfazer.
  - "Primeira vez", "Lugares que voltam" e "O que ficou fora" reaproveitam as leituras da grade em vez de repeti-las (abertura do mês: 409 → 239 ms com 5 anos de dados).
- `MainActivity.kt`: `Screen.LeituraWidget(widget, query)` na pilha (D18); a consulta da grade vive no App e zera ao trocar de aba (D-5).
- `Components.kt` / `Transactions.kt`: `SearchLine` sai de Transactions e fica pública em Components; `OptionPicker` ganha opções desabilitadas.
- Docs: spec §7.5 reescrita e §2.13 (previsto em aberto tracejado); D76 em architecture substitui D45 e revisa D17; glossário ganha Intervalo e Costume.

Regras de comparação: "Total e costume" e "O que cresceu" comparam o intervalo com trechos anteriores do mesmo tamanho, cortados no mesmo dia; um mês anterior mais curto não invade o mês atual.

## Testes

- `LeituraUiTest` / `LeituraLifecycleTest`: ACs da feature — filtro e chips, intervalo, páginas dos widgets, ações com Desfazer, recriação e virada do mês.
- `LeituraInvariantsTest` reescrito: ações aleatórias no Db conferindo resumo, Calendário, Dia da semana, Mês a mês, Para onde foi, Ritmo e Margem contra somas feitas à mão.
- `MargemUiTest` / `ReservaUiTest` ajustados para a tela nova.
- `ConcurrencyTest`: 300 rodadas de leituras canceladas ao acaso; `close()` precisa terminar.
- `Repro_*`: reproduções das correções da rodada de verificação.

## Screenshots

## Informações Adicionais (OPCIONAL)

- API exploratória: o Db ganhou uma leitura (`leituraLines`) em vez das seis previstas no manifesto; o formato se prova com os widgets usando-a.
- Fora desta MR, conhecidos:
  - chip de recado casa só pela fonte, não pelo texto (A-2);
  - Separar mostra legenda com totais, sem dividir os gráficos por chip;
  - "Com previstos" não desenha previsto em aberto tracejado;
  - "O que ficou fora" não lista previstos pulados e ignora chips;
  - sem arrastar no Ritmo para virar intervalo, e várias opções da tabela §5 de alguns widgets;
  - Calendário começa na segunda, não no início de semana dos Ajustes;
  - colunas tocáveis de Semanas e Mês a mês sem rótulo para o TalkBack;
  - Desfazer de "Só X" depois de sair e voltar à aba devolve o filtro antigo (raro).
- A branch inclui o commit `chore: add dev pipeline` (scripts/pipeline, .agents).
```
