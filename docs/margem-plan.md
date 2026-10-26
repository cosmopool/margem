# Margem honesta — plan

Status: **product decided** 2026-10-26 (M1–M10), recorded in spec §2.13, §3, §4, §5, §7.1, §7.4, §7.5 and the glossary. Open: the mock of the Verbas header (M9) and of the reserved part in the régua, both in the book; the implementation plan (architecture D-rows, contract round).

## 1. Goal

The briefing: show the pace of spending simply and clearly, so the person stays within the verbas they set. Today a fixed bill (previsto) does not count until it is lançado: the verba looks loose before the bill and jumps to "Acima do ritmo" the day it lands (cota R$ 3.000, aluguel R$ 2.000 on day 5 → ideal ≈ R$ 484, projection ≈ R$ 12.400). And there is no total of the month. Found by the 5-persona review of 2026-10-26.

The rule in the owner's words: verba − previsto = quanto sobra para gastar; o ritmo é só sobre essa sobra; um lançamento maior que o previsto diminui o disponível e recalcula o ritmo.

## 2. Decided (product, 2026-10-26)

| # | Rule | Decision |
|---|---|---|
| M1 | Previsto in the margem | **Reserves.** Margem = cota − gasto do mês − previstos em aberto do mês. Every open occurrence whose day falls in the month counts, not only those inside the R1 window (today + 10). The régua shows the reserved part after the gasto. Gasto is still only what was lançado. |
| M2 | Previstos do mês | Occurrences with their day in the month, minus the skipped ones. A lançada one counts at the lançado value; an open one (late included) at the previsto's value. |
| M3 | Ritmo | **Only of the cota livre.** Cota livre = cota − previstos do mês (≥ R$ 0,00). Ritmo ideal comes from the passos of the cota livre; it is compared with the **gasto variável** (gasto do mês without the lançamentos of previstos). No ritmo: variável ≤ ideal. Acima do ritmo: variável > ideal and margem ≥ 0. Fora da margem: margem < 0. The payment day of a fixed bill no longer matters. |
| M4 | Different value | The whole lançamento stays out of the ritmo at its real value; a bigger value lowers the cota livre and the ritmo is recalculated. The difference never becomes gasto variável. |
| M5 | Passo semanal | The passos split the **cota livre**; edit limit = cota livre − other edited weeks. Previstos are shown apart in the verba detail, on their day. When a previsto change (create, edit, delete, skip, lançar at another value) leaves the cota livre below that month's edited passos, the month's edits are erased **without warning** and every week goes back to automatic. |
| M6 | Projection | No passo atual = gasto variável × (cota livre ÷ ritmo ideal de hoje) + previstos do mês. Exactly on pace projects the cota. None when the ideal of today is R$ 0,00. |
| M7 | Pauta pendente | **Only the count**, as today ("40 recados na pauta"). Verbas don't change with pending recados. |
| M8 | Late or early previsto | Lançar the previsto counts **in the previsto's month**: when the recado's date falls in another verba month than the previsto's day, the lançamento takes the previsto's day, without "‸ emendado" (late and early alike). An open late occurrence keeps reserving in its own month. To count in the month paid, the person makes an ordinary lançamento and leaves the previsto open, skips or deletes it. |
| M9 | Total of the month | **Fused with the teto line** in the Verbas header, **without ritmo or state**. Track up to the greater of the sum of cotas and the teto; gasto in solid ink, open previstos hatched; a thin mark at the sum of cotas; the teto's double clay rule as today; cotas above the teto in lacre as today. Legend: "Margem R$ 1.570,00 de R$ 5.720,00" / "gasto 1.850 · previstos 2.300 · teto 6.000"; without teto the second line ends in "Pôr um teto". Tap opens the teto, as today. Final drawing by mock (book 8.10). |
| M10 | Names | **Margem** keeps its name with the M1 formula; cota − previstos do mês is the **Cota livre** (`FreeLimit`). |

## 3. Consequences recorded without a separate question

- Leitura "Ritmo do mês" (§7.5) plots the gasto variável against the ideal of the cota livre; its projection is M6. Rankings and calendar still count only lançamentos.
- Widget Verbas (§8.1, not built yet) shows the M1 margem and the M3 states; the risk order is unchanged.
- "A caminho" (§7.4) still shows today + 10 only; the reserve (M1) does not depend on that window.

## 4. Open for the mock

- Hatch is already the excess in the régua; the reserved part needs another mark (book 8.x régua, 8.10 header).
- The verba detail: previstos of the month on their day, apart from the passos.
