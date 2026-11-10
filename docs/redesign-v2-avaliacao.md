# Redesenho v2: avaliação da ideia

Ideia avaliada: verbas bem simples (lista e detalhe do mock `redesign-ab (2).html`) e cada conceito num widget próprio da Análise, com ícone de ajuda. Não li os mocks `redesign-v2-*`.

## 1. Serve ao objetivo?

O objetivo tem duas metades: "ficar dentro das verbas que ela mesma definiu" e "entender para onde o dinheiro vai" (spec.md:9). A primeira é controle, todo dia; a segunda, reflexão, de vez em quando. A ideia leva conceitos da primeira para o lugar da segunda.

A favor:
- As telas de verba pesam. A linha destaca o gasto e deixa a margem em letra pequena (Budgets.kt:589-600), a régua empilha seis marcas sobre o trilho (Components.kt:755-784) e o glossário tem 16 verbetes só para o mês da verba (glossary.md:40-51, 56-59).
- Realocar guarda o que o mock corta: passo, teto, marca do ritmo (mock :400-404).
- A página de widget já tem forma de lição: frase, gráfico grande, opções, lista (spec.md:217). Explicar com os números da pessoa ensina mais que glossário.

Contra:
- Quase tudo que a ideia move é sinal de controle. O app não notifica: "os widgets cumprem esse papel" (spec.md:261). Aviso guardado numa aba aberta pouco não avisa, e o mock admite a perda (mock :1140).
- Os conceitos são uma conta só: cota = previstos + cota livre; cota livre = soma dos passos; ritmo ideal = passos até hoje; margem = cota livre − gasto variável (Month.kt:65, 118-119). Um widget por conceito ensina as peças e esconde a conta.
- O passo decide o estado da lista e a ordem por risco (Month.kt:121-140, 182-193). Editável só na Análise, que hoje não edita nada (spec.md:200-230), vira um plano invisível que muda a tela diária. Mercado, cota R$ 1.200, 30 dias, semana 1 editada para R$ 500 (a feira): no dia 7, com R$ 450 gastos, o plano diz "R$ 50 abaixo do ritmo ideal"; a conta linear do mock (esperado R$ 280), "gastando rápido".
- Ajuda por conceito trata o sintoma. O book tem a regra: "nome que precisa de explicação já perdeu" (design_system.html:725).

**Veredito.** Simplificar as verbas, sim. Levar todos os conceitos para a Análise, não. O critério é a pergunta que o conceito responde. "Estou bem hoje?" fica onde se olha todo dia: lista, widget da tela inicial, prévia do Lançar. "Por quê?" e "como funciona?" vão para o detalhe, como conta, e para a Análise, como gráfico. Mover a explicação, não o sinal. Nenhum conceito some; alguns perdem o nome e viram linha de conta.

## 2. Conceito por conceito

A lista abre todo dia; a Análise, pouco. O lugar segue a frequência.

| Conceito | Quando | Onde fica | Por quê |
|---|---|---|---|
| Margem | todo dia; ao lançar | Número principal da lista, do widget da tela inicial, do detalhe e da prévia. Um nome só. | É a resposta do app. Hoje o destaque da linha é o gasto (Budgets.kt:592-593). |
| Cota | ao olhar; ao planejar | "de R$ 1.200" ao lado da margem; edição em Editar verba. | Dá escala ao número; dispensa ajuda. |
| Previstos reservando | quando a barra enche sem gasto | Lista: "R$ 2.000 reservados" se houver em aberto. Detalhe e Análise: as listas que já existem. | A Casa 72% usada no dia 4 com R$ 40 gastos (mock :1143) parece problema. A razão fica onde está a surpresa. |
| Cota livre, gasto variável | ao entender o ritmo | Sem nome. Linha da conta no detalhe ("R$ 3.000 − R$ 2.000 reservados = R$ 1.000 para o dia a dia"); na Análise, o filtro Variáveis que já existe (spec.md:205). | Passos intermediários de cálculo, não conceitos. |
| Ritmo, ritmo ideal, No ritmo, Acima do ritmo | todo dia | Lista e widget da tela inicial: rótulo em reais só fora do normal ("No ritmo" some). Detalhe: uma frase com o "esperado até hoje". Análise: gráfico de uma verba. | Único aviso antes de passar da cota; o ideal é só a referência dele. Sem o rótulo, a ordem por risco parece aleatória (Month.kt:182-193). |
| Fora da margem | todo dia | Lista, widget da tela inicial, detalhe, prévia: "R$ 12 além" (já é assim). | Alarme principal. |
| Passo semanal | ao planejar o mês | Detalhe, recolhido em "Plano do mês", com a edição. Análise: Semanas (já existe). | Editar é planejar e muda o estado diário (seção 5, item 5). |
| Projeção | semanal, após a 1ª semana | Só no gráfico de ritmo, como "se continuar assim". | No dia 4, R$ 262 viram R$ 1.965 (mock :679): cedo, ela amplia qualquer dia pesado. |
| Teto | ao criar ou mudar cota | Sai do cabeçalho de Verbas (Budgets.kt:484): aviso no formulário da cota, edição em Ajustes. Nenhum widget. | Limite de planejamento, não de gasto (spec.md:57). |
| Total do mês | às vezes | Linha pequena no cabeçalho; na Análise, o widget Margem. Sem estado nos dois. | Total com estado esconde a verba fora (design_system.html:1713). |
| Régua em camadas | todo dia | Lista e widget da tela inicial: gasto, reservado e a marca de hoje. Detalhe: régua completa com legenda escrita. | A marca de hoje é o ritmo desenhado (Components.kt:780). |

## 3. Riscos

1. **O sinal sai da tela diária.** Na lista, o único sinal visual da reserva é o tracejado da régua (Components.kt:769); "em previstos" só existe no TalkBack (Budgets.kt:583). Sem ritmo nem reserva à vista, a barra alarma sem motivo e a ordem por risco fica sem explicação. O widget da tela inicial ainda não existe (MargemApp.kt:13), e a spec pede nele a marca do ritmo (spec.md:253): ele é o canal de aviso do app.
2. **Números que discordam.** Já acontece. No Lançar, o campo da verba mostra a margem com a reserva (Transactions.kt:1059) e a prévia usa cota − gasto (Transactions.kt:1075-1076, 1324-1325; Db.kt:505-511): com o aluguel em aberto, "margem R$ 960" no campo e "R$ 2.960 → ..." na prévia (pendente desde margem-plan.md:95). O mesmo número tem quatro nomes: "margem" (Budgets.kt:599), "Sobram" (Budgets.kt:981), "Margem ... de" (Components.kt:854), "cabem" (Insights.kt:891). A tolerância é zero na spec (spec.md:106) e 10% no mock (mock :1160): com 10% na lista e zero na Análise, a lista não diz nada e a Análise diz "+ R$ 3,00 sobre o ritmo ideal" (Insights.kt:1016). Condição: todo número sai de `BudgetMonth` (D12) e nenhuma tela recalcula; a Análise já recalcula um estado (Insights.kt:510-521).
3. **Ajuda como muleta.** O app já remenda o modelo com notas, como "a margem conta tudo" (Insights.kt:894) e "o ritmo ideal de hoje conta as semanas passadas" (Budgets.kt:762). Cada nota marca uma costura; um "?" por conceito é mais uma camada do mesmo remendo. A fórmula da projeção (spec.md:107) não cabe numa explicação de cinco segundos.
4. **Widgets demais.** `CONTEXTS` tem 20 ids (Insights.kt:236-249), e quase todo conceito já tem casa: Margem, Ritmo acumulado, Previstos do mês, Semanas com a marca do passo, filtro Variáveis. Um widget por conceito leva a grade para perto de 30, duplicando esses. Numa aba aberta pouco, o 25º widget quase nunca é visto.
5. **Por verba × somado.** Cota livre, passo, ritmo, projeção e estado são da verba (D9); a Análise sem chip lê todas e já soma. `plan()` junta o mês de todas as verbas (Insights.kt:496-507) e `sum()` inventa um estado para o total (Insights.kt:517), que pinta a régua do widget Margem (Insights.kt:892). O book proíbe as duas coisas (design_system.html:1713, 1935); a spec da Leitura permite (spec.md:221). Semanas ainda mistura as bases: em Tudo, o tipo de fábrica, as barras somam o aluguel e a marca é o passo da cota livre, de que o aluguel não faz parte (Insights.kt:1418-1423).

## 4. Ícone de ajuda

Renomear antes de explicar. Com palavras do dia a dia, a maior parte da ajuda sobra:

| Hoje | Na tela |
|---|---|
| margem | sobram, em todas as telas ("Margem" fica como nome do app) |
| previstos em aberto | reservado |
| cota livre, gasto variável | dia a dia, só dentro da conta |
| ritmo ideal | esperado até hoje |
| acima do ritmo · fora da margem | R$ 51 acima do esperado · R$ 12 além da cota |
| no passo atual | se continuar assim |

"Cota" pode virar "limite"; o glossário veta "limite" por causa do teto (glossary.md:40), conflito que some se o teto sair da tela diária. Decisão sua.

O ritmo cabe na unidade da margem: desde a D68, "No ritmo" equivale a margem ≥ cota livre − ritmo ideal. No exemplo da spec (Casa, cota R$ 3.000, aluguel de R$ 2.000 em aberto, ideal R$ 64,52; spec.md:130, 147): "Sobram R$ 960,00. Pelo plano, bastava sobrar R$ 935,48 hoje." Um número e a referência dele, sem termo novo. Os "dois juízes que discordam" do mock (:336) viram problema de nome, não de regra.

Uma boa explicação tem, nesta ordem: uma frase com essas palavras; a conta da pessoa em linhas ("cota 3.000 − gasto 40 − reservado 2.000 = sobram 960"); o que o conceito não é ("não bloqueia nada"); onde mudar. No máximo quatro linhas além da conta, sem definição genérica.

Onde mora: o próprio número é o alvo. Tocar em "sobram" ou no estado abre uma folha com a conta (`BottomSheet`, Components.kt:1166), como o cabeçalho de Verbas já abre o teto (Budgets.kt:469-480). Nada de "?" nos widgets da grade; uma página "Como o Margem conta", em Ajustes e no pé de cada folha, junta tudo. Opcional, e decisão sua porque a spec não tem onboarding (spec.md:262): na primeira vez que um estado aparece, uma linha de explicação embaixo, uma vez só.

## 5. Recomendações

1. Consertar a consistência antes de redesenhar, porque vale para qualquer desenho: prévia do Lançar com a reserva, total sem estado na Análise, Semanas comparando só o dia a dia com o passo.
2. Tela diária (lista, widget da tela inicial, prévia): "sobram R$ X de R$ Y", barra com a marca de hoje, "R$ X reservados" quando houver e rótulo de estado só fora do normal, com forma e valor (design_system.html:893). Nada além.
3. Detalhe: a conta em linhas, uma frase de ritmo em reais, previstos, lançamentos, passos recolhidos. Para "quanto posso hoje", um número só, do mesmo plano do estado: com passos, "nesta semana cabem R$ X"; sem passos, "R$ X por dia". Nunca os dois.
4. Análise: nenhum widget novo. Margem, Ritmo acumulado e Semanas ficam por verba: sem chip, uma linha por verba na ordem de risco; com uma verba, o gráfico. A explicação mora na página do widget que já mostra o conceito.
5. Passo: decidir uma vez. Fica, com a edição no detalhe, ou sai, com ritmo linear em todo lugar, como no mock. Editável só na Análise, não.
6. Teto: aviso no formulário da cota e edição em Ajustes.

A versão com widgets de conceito só funciona se cada widget for de uma verba e ler o mesmo `BudgetMonth` da lista; se o estado ficar na lista e no widget da tela inicial, com o toque abrindo o widget daquela verba; se o widget tiver nome de pergunta ("Estou gastando rápido?"), não de termo; se para cada widget que entra um sair; e se a Análise for aberta ao menos uma vez por semana, o que dá para medir antes.

## 6. Como testar com uso real

O app tem um usuário, que já sabe todos os termos (architecture.md:76). O uso dele mede o controle, não o aprendizado; daí dois testes.

Uso do dono, duas viradas, contra os meses já gravados, com um registro local por dia (só no Margem-Dev):
- verbas-mês fechadas além da cota e o excesso em R$;
- dias entre o primeiro "acima do ritmo" e o primeiro "fora da margem": a antecedência do aviso;
- aberturas de Verbas, detalhe e Análise; toques em cada explicação; edições de passo e de teto;
- num diário curto, cada vez que dois números pareceram discordar.

Pessoas novas, três a cinco, 20 minutos com dados de exemplo e sem ajuda: "Dá para gastar R$ 80 em Lazer hoje?", "Por que a barra da Casa está quase cheia no dia 4?", "Mercado está bem para o dia 10?" (marca de hoje contra só rótulo). Contar acertos, tempo e onde tocam primeiro.

Regras de decisão:
- Análise aberta menos de uma vez por semana → nada essencial mora só lá.
- Mesma explicação aberta três vezes pela mesma pessoa → refazer o nome ou o conceito, não aumentar o texto.
- Nenhuma edição de passo em dois meses → cortar o passo e usar ritmo linear.
- Aviso mais em cima da hora, ou mais excesso, depois da mudança → o sinal saiu cedo demais da tela diária.
