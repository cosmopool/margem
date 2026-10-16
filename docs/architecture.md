# Margem — arquitetura

Status: **decidido** em 2026-10-09; revisado em 2026-10-14 (KMP + UI nativa, D26–D31; bloco 1 do `shared`, D32–D33; bloco 2 do `androidApp`, D1, D16, D23, D28, D33, §7; bloco 3, Verbas, D34–D35, §5–§7, §10; bloco 4, ciclo do recado, D36–D38, §4, §7, §9, §10; bloco 5, Ajustes e importação, D3, D4, D14, D40–D41, §2, §7, §9, §10; teto, D42, §4, §5; início do mês sem sumir verba, D43); em 2026-10-16 (bloco 6, Leitura, D44–D45, §6, §9, §10; teto, D46, §5, §9, §10). O que está em §11 espera resposta, uma por vez.
Produto: [spec.md](spec.md). Vocabulário: [glossary.md](glossary.md). Visual: [design_system.html](design_system.html).
Estilo: Codin' Dirty (CLAUDE.md): poucas camadas, funções grandes onde está o miolo, testes de integração.

## 1. Decidido

| # | Decisão | Por quê |
|---|---|---|
| D1 | **Projeto novo** no mesmo repo: os módulos `shared` e `androidApp` (D26) substituem `app/`, removido no bloco 2 (D33). applicationId `com.kaiodelphino.margem`. | App novo com id próprio. Sem comparação lado a lado com o cobudget, que não será mais usado; o histórico dele entra pela importação (D3, D4). |
| D2 | Do código atual, entram **captura, dedupe e extração** (Nubank + genérico, `parseBrl`) e os casos de teste deles, adaptados. O resto é refeito. | É o código mais testado contra notificações reais. |
| D3 | **Importação de um backup do cobudget** (`.db` da última versão do schema dele, a 7), em Ajustes. Traz **só os recados** (`captured_notifications`): riscado lá (`dismissed = 1`) entra riscado; os outros entram pendentes, inclusive os que tinham lançamento. Fontes, marcadores, apelidos, lançamentos e `genda_outbox` ficam para trás. Pode ser repetida: o dedupe (D4) torna a repetição quase sempre inofensiva. Recado com `when` é sempre repetido de si mesmo; sem `when`, a janela compara com o recado mais recente da chave, que a primeira importação pode ter mudado, e em casos raros ele entra de novo (aceito na triagem do bloco 5, sem mudar a regra). Outra versão, ou arquivo que não é do cobudget, é recusado sem escrever nada. Decidido no bloco 5. | O histórico de recados não se perde; o que a pessoa decidiu lá ela decide de novo no Margem, com verba. Só a v7 porque só a pessoa usou o cobudget, sempre atualizado. O importador é código descartável. |
| D4 | A importação passa cada recado, em ordem de `postedAt`, pela **mesma regra de dedupe da captura** (mesma chave + conteúdo + `when` ≠ 0, ou o último da mesma chave com o mesmo conteúdo a menos de `DUPLICATE_WINDOW_MS`), contra o banco e contra os já importados, e descarta recado sem texto, como a captura. A regra de fonte da captura **não** vale: entram recados de apps que não são (mais) fonte. | Os dois apps capturam ao mesmo tempo; a mesma regra evita recados duplicados. O recado já foi anotado no cobudget: a fonte só decide o que se anota daqui em diante. |
| D5 | **SQLite direto pela API KMP do `androidx.sqlite`** (`SQLiteDriver`, `SQLiteConnection`), SQL à mão, sem Room, SQLDelight ou KSP. | Sem código gerado: o que se lê é o que roda. Roda no `commonMain`. Os testes cobrem cada consulta. |
| D6 | **Reatividade por um contador global**: `Db.version: StateFlow<Long>`, incrementado ao fim de toda escrita por um único `write { }`, que em seguida chama `onWrite` (D21). Única exceção: `ensureMonths` (D30). | Uma regra só, impossível de esquecer por tabela. As consultas são pequenas. |
| D7 | **Uma linha por parcela**, cada uma com a sua **data** (compra + k−1 meses; se, com dia de início 29–31, essa data cair fora do k-ésimo mês, vai para o dia mais próximo dentro dele). À vista = 1 parcela; no máximo 400. | "Gasto do mês" vira um `SUM` sobre parcelas. A data permite mudar o dia de início do mês sem regravar nada. |
| D8 | **Cota: uma linha por verba e mês**, criada **sob demanda** ao carregar o mês (D30), copiando a anterior e preenchendo meses pulados. | Consulta trivial; sem job agendado; funciona com o app semanas fechado. |
| D9 | **Passo editado** gravado por verba + data de início da semana. Só as semanas editadas têm linha. | O passo padrão é calculado. |
| D10 | Mudar o dia de início da semana ou do mês **apaga as edições de passo do mês atual em diante** (o app avisa antes). Meses passados são recalculados com o dia novo; edição que não casar com uma semana nova é ignorada. | As semanas mudam; converter edições seria difícil de explicar e testar. |
| D11 | **Centavos dos passos**: todos são arredondados para baixo; a **última semana não editada** recebe a diferença. | A soma dos passos é sempre igual à cota. |
| D12 | **O SQL lê, uma função Kotlin calcula**: `budgetMonth(...)` produz gasto, margem, passos, ritmo ideal, estado e projeção. `budgetMonth` e a extração são `internal` no `shared`: telas, widgets e Leitura recebem `BudgetMonth` e `Parsed` prontos do `Db` (`budgetsOverview`, `budgetDetail`, `pending`, `transaction`). | Fácil de testar e de depurar com breakpoint. Uma API pública só (o `Db`), sem duas formas de chegar ao mesmo número. |
| D13 | **Ajustes e fontes em tabelas** do próprio SQLite. | Entram no backup e na mesma reatividade: mudar o início do mês recalcula tudo. |
| D14 | **Backup = cópia do arquivo do banco**, feita no `shared` com `kotlinx-io`: exportar = `VACUUM INTO` o caminho dado; importar fecha o escritor e os leitores, substitui o arquivo (apagando `-wal`/`-shm`), reabre, migra e incrementa `version`. A escolha do arquivo (SAF) fica no `androidApp`, que entrega um caminho local. Na tela, importar pede confirmação num diálogo antes de chamar `importFrom` (bloco 5). | Sem formato próprio para manter; o mesmo código nos dois apps e nos testes da JVM. |
| D15 | **UI em Compose Foundation, sem Material 3.** Tokens do book num objeto `Margem` e cerca de 10 componentes próprios. | Fiel ao book, sem desligar padrões do Material. |
| D16 | **Fontes variáveis empacotadas** em `res/font`, 4 arquivos do `google/fonts` (OFL): Newsreader e Newsreader Italic (`opsz`, `wght`), Source Sans 3 e Source Sans 3 Italic (`wght`). Cada estilo do book vira um `Font` com `FontVariation` (`wght` e, na Newsreader, `opsz` = corpo em sp, limitado a 6–72). Pesos do book: Newsreader 300–600 e itálico 400/500; Source Sans 3 400–700. | Funciona offline desde o primeiro uso, inclusive nos widgets. Um arquivo por família e estilo cobre todos os pesos, e o eixo óptico faz o mesmo desenho ficar robusto pequeno e fino grande, como no book. |
| D17 | **Gráficos em Canvas próprio**, um Composable por gráfico. | São 4 gráficos simples, no estilo do book; sem dependência. |
| D18 | **Navegação sem biblioteca**: `sealed interface Screen`, pilha em `rememberSaveable`, um `when` e `BackHandler`. | Explícita; depura fácil. |
| D19 | **Sem ViewModel.** Cada tela observa `Db.version` e consulta o Db direto; formulários em `rememberSaveable`. | O banco já é o estado; menos classes. |
| D20 | **Widgets em Jetpack Glance**, redimensionáveis (`SizeMode.Responsive`). | Mesmo jeito de pensar da UI; layout por tamanho. |
| D21 | **Widgets atualizam** a cada escrita: o `Db` recebe `onWrite: () -> Unit` no construtor e o `MargemApp` passa um lambda que chama `updateAll`. Na virada do dia, pelo `updatePeriodMillis` do sistema. | O `shared` não conhece Glance; o elo escrita → widget fica explícito e segue no debugger. Aceito: o widget pode mostrar o ritmo do dia anterior por um tempo. |
| D22 | **Dois módulos (D26), poucos arquivos grandes** por assunto (§9). Sem camadas repository/usecase. | Codin' Dirty. |
| D23 | **Testes**: `commonTest`, rodando na JVM (`jvmTest`) com SQLite real em arquivo temporário, sem Robolectric. **Integração pela API pública** (`Db`, `capture`, `parseBrl`); teste unitário (enxergando `internal`) só em três contratos puros: `budgetMonth`, extração e SHA-256. Robolectric só no `androidApp`, para a conversão de `StatusBarNotification` e os testes de UI Compose (lançar, editar passos); nesses o `Db` usa `AndroidSQLiteDriver` (exceção à D28). | `.agents/rules/testing.md`. Testes do miolo rápidos e fora do emulador, e que sobrevivem a refatorações. |
| D24 | **minSdk 29** (Android 10). | Mesmo do cobudget; Glance sem restrições. |
| D25 | **Captura grava direto no Db**: o `NotificationListenerService` converte o `StatusBarNotification` em `RawNotice` (parse na fronteira) e chama `capture(db, raw)` do `shared`, onde ficam extração e dedupe. | Sem fila; a mesma escrita incrementa a versão e atualiza os widgets. |
| D26 | **Kotlin Multiplatform + UI nativa.** `shared` (KMP) tem dados e regras: tipos, Db, `budgetMonth`, extração, dedupe, `capture()`. `androidApp` tem Compose, Glance, o serviço de notificações, backup e importação. **Na v1 só o app Android é codado**; o iOS entra depois como `iosApp/` (Xcode + SwiftUI), consumindo o `shared`. | Android 100% nativo; a lógica fica pronta para o iOS sem custo de performance. |
| D27 | **Alvos do `shared`**: Android pelo plugin `com.android.kotlin.multiplatform.library` (`androidLibrary { }`, não `androidTarget`), `jvm`, `iosArm64`, `iosSimulatorArm64`. Os alvos iOS são declarados na v1 mesmo sem app. | Plugin feito para biblioteca KMP (uma variante só, sem build types). Com iOS declarado, o compilador barra `java.*` e `android.*` no `commonMain` desde o primeiro dia. `jvm` roda os testes sem emulador. |
| D28 | **`BundledSQLiteDriver` em tudo** (Android, JVM dos testes, iOS). A conversão UTF-16↔UTF-8 do SQLite perde U+FEFF no início, U+FFFE, U+FFFF e surrogates soltos; por isso todo texto é **normalizado na entrada** (esses viram U+FFFD) e o que se lê é sempre o que foi gravado. **Exceção**: os testes Robolectric do `androidApp` usam `AndroidSQLiteDriver` (`testImplementation` de `androidx.sqlite:sqlite-framework`). | A mesma versão do SQLite em teste e em produção; um caminho de código só. Aceito: ~1 MB de lib nativa por ABI no APK. A exceção existe porque o AAR Android do `sqlite-bundled` só traz `.so` de Android, e o Robolectric roda na JVM do Mac; a semântica do SQL continua coberta pelo `shared` com Bundled (D23), e o `androidApp` só testa a fronteira (`StatusBarNotification` → `capture`) e a UI. Descartados: testes instrumentados no emulador (lentos, fora do `./gradlew test`) e o Robolectric carregando a `.dylib` do artefato JVM (frágil: classpath e lib nativa à mão). |
| D29 | **Um escritor + 2 leitores.** Escrita numa conexão própria sob `Mutex`; leitura em 2 conexões com `PRAGMA query_only = 1` num `Channel`: `read { }` pega uma, usa e devolve. WAL ligado. | Com WAL, leitura não espera escrita: tela, widget e captura não se bloqueiam. 2 leitores bastam para esses três. |
| D30 | **`ensureMonths(month)`** roda no escritor **sem incrementar `version`**: `INSERT OR IGNORE` copiando a cota anterior e preenchendo meses pulados. Telas e widgets chamam antes de consultar o mês. | Leitores são `query_only`. As linhas criadas só materializam a cota que já valia: nada na tela muda, e incrementar `version` faria a tela reconsultar em loop. |
| D31 | **Datas com `kotlinx-datetime`** (`LocalDate`, `LocalTime`, `YearMonth`, `DayOfWeek`) no `shared` e no `androidApp`. Sem `java.time`. | `java.time` não existe no `commonMain`; um tipo de data só nos dois módulos. |
| D32 | **`content_hash` = SHA-256 escrito à mão no `commonMain`** (sem `java.security`), sobre título, texto, texto longo, subtexto e linhas, cada um como tamanho em bytes (4 bytes) + UTF-8. | Sem dependência de criptografia para um hash de dedupe. O prefixo de tamanho evita colisão entre campos (com o byte 0 do cobudget, `"\0\0"+"É"` = `"\0"+"\0É"`). Não precisa ser igual ao do cobudget: a importação (D4) passa cada recado pelo `capture()`, que recalcula o hash. |
| D34 | **Ordem dos blocos**: 1 `shared` · 2 fundação do `androidApp` · **3 Verbas** · 4 ciclo do recado (Pauta, lançar/editar, estorno, Lançamentos) · 5 Ajustes + importação · 6 Leitura · 7 widgets. | Lançar exige uma verba: as Verbas vêm antes do ciclo do recado. |
| D35 | **Testes de UI acham elementos por `testTag`**: cada arquivo de tela tem um objeto de tags (`BudgetTags` em `ui/Budgets.kt`), uma `const val` por elemento que os testes tocam ou leem, com KDoc dizendo o que ele mostra. O objeto é parte do contrato do bloco. | A rodada de agentes escreve testes de UI antes das telas existirem; as tags fixas são a API pública da tela. Texto muda com o glossário; a tag não. |
| D36 | **Seletor de data e hora próprio** (`DateTimeDialog` em `widgets/Components.kt`, book 8.9), sem o `DatePicker` do Material: grade do mês com a semana dos Ajustes, dias futuros desabilitados, hora HH:MM. Tags em `DateTimeTags`. | D15: sem Material 3. Um componente, usado pelo formulário de lançar/editar/manual. |
| D37 | **Datas de 0001-01-01 a 9999-12-31**: lançar, lançamento manual e editar recusam o formulário quando alguma parcela cai fora desse intervalo (decidido na triagem do bloco 4). Nas leituras, os limites de um mês são presos ao intervalo, para um mês que o atravessa (dezembro de 9999 com início no dia 15) achar as suas parcelas. | Datas são texto ISO (§3), que só ordena como data nos anos 0001–9999: "+10000-01-31" viria antes de "0001-01-01". Recusar na fronteira mantém o texto ordenável sem migrar o schema. |
| D38 | **Id de lançamento nunca é reaproveitado** (`AUTOINCREMENT`), como o de verba. Os dois estão direto no schema v1, sem migração: ninguém usou o app ainda. Decidido na triagem do bloco 4. | Apagar o lançamento mais novo e criar outro reaproveitaria o id: uma edição aberta, ou restaurada depois de o processo morrer, abriria outro lançamento. |
| D39 | **Build de debug é o Margem-Dev**: applicationId com sufixo `.dev` (`com.kaiodelphino.margem.dev`) e nome `Margem-Dev` (`src/debug/res`), assinado pela chave de debug do repo. O build de release, `com.kaiodelphino.margem`, é assinado pela chave de release, fora do repo (caminho e senhas em `local.properties`). | Os dois convivem no mesmo celular, cada um com seus dados e seu acesso às notificações; um APK assinado com a chave de debug, que é pública, nunca substitui o app de verdade. |
| D40 | **Importador do cobudget no `shared`**: `importCobudget(db, path): CobudgetImport`, público, em `Capture.kt` ao lado do dedupe. Lê o `.db` antigo numa conexão própria do mesmo driver (`Db.driver` vira `internal`) e grava tudo numa **escrita só** (um incremento de `version`, um `onWrite`). Devolve quantos entraram pendentes, quantos riscados e quantos ficaram de fora (repetidos ou sem texto); `opened = false` quando o arquivo é recusado. Sem mudança no schema do Margem (v1). O `androidApp` só copia o arquivo do SAF para um caminho local (`Backup.kt`); some o `Import.kt`. Decidido no bloco 5. | Uma transação: milhares de recados não viram milhares de escritas e de `updateAll` nos widgets. Testado na JVM com SQLite real (D23), como o resto do `Db`. |
| D41 | **Aviso do início do mês e da semana só quando apaga algo**: `startDropsEdits(monthStartDay, weekStart, today)` diz se `setMonthStartDay(monthStartDay)` seguido de `setWeekStart(weekStart)` apagaria alguma edição de passo (mesmo corte das duas, D10), sem gravar. A tela de Ajustes junta os dois num rascunho e grava os dois no Salvar. Decidido no bloco 5. | Mesma regra do aviso de cota (`limitDropsEdits`): sem aviso quando nada é apagado. O rascunho evita uma escrita (e um aviso) por toque no stepper. |
| D42 | **Teto: uma linha por mês** em `ceiling_months`, criada sob demanda pelo `ensureMonths` (D30) copiando o **padrão** (`settings.ceiling`), não o mês anterior; meses pulados também recebem o padrão. `0` = sem teto (zero é inicialização). "Só este mês" grava só a linha do mês atual; "deste mês em diante" grava o padrão e a linha do mês atual e apaga as linhas de meses depois dele. Migração v2. | Cada mês guarda o teto que valia nele, como a cota (D8): a Leitura pode mostrá-lo depois sem migrar de novo. Copiar o padrão, e não o mês anterior, é o que faz o "só este mês" não vazar para o mês seguinte. |
| D43 | **Mudar o início do mês copia a cota para o mês atual novo**: `setMonthStartDay`, na mesma escrita, garante a linha de cada verba no mês atual antigo (como o `ensureMonths`) e copia essa cota para o mês atual novo com `INSERT OR IGNORE`: onde já havia linha, fica a que estava. Quando o mês atual volta um mês, esse vira o primeiro mês das verbas criadas no mês de antes. Decidido em 2026-10-14, depois de verbas sumirem da aba Verbas e do Lançar ao mudar o dia 1 para um dia depois de hoje. | O `ensureMonths` só preenche para a frente (D8, D30), e o detalhe da verba o chama com meses passados, então preencher para trás ali criaria cota antes de a verba existir. Na troca, o único mês que pode ficar sem linha é o atual novo. |
| D44 | **Ritmo ideal dia a dia no `shared`**: `BudgetMonth.idealByDay`, um valor por dia da janela do mês (índice 0 = `start`), = o ritmo ideal de §6.4 com hoje = aquele dia; o dia de hoje é igual a `ideal`. A Leitura só desenha. Decidido no bloco 6. | D12: a regra do ritmo num lugar só. |
| D45 | **Leitura sem consulta nova**: a tela usa `history`, `tagRanking`, `merchantRanking`, `spentByDay` e `budgetDetail` (linhas do mês para o gasto acumulado; `budgetsOverview` para a ordem de risco). Cor de série = posição por id entre as verbas que existem, calculada na tela (spec §7.5). Frases, degraus do calendário e escala dos gráficos são cálculo de apresentação em `ui/Insights.kt`. Tags em `InsightsTags` (D35). Decidido no bloco 6. | As leituras existem desde o bloco 1 e já são testadas; sem schema novo, sem disputar versão com teto e legenda. |
| D46 | **Teto no `Db`** (decidido em 2026-10-16, T1–T6 de `docs/teto-plan.md`): `ceiling(month): Ceiling` lê o teto do mês (`cents`; mês sem linha lê 0 = sem teto), o padrão (`standard`, `settings.ceiling`) e a soma das cotas do mês (`limits`). `setCeiling(cents, onward, today)`: 0 tira; negativo ou acima do máximo é recusado (false). Antes de gravar, preenche os meses pulados até o atual com o padrão antigo; `onward` grava o padrão e a linha do mês atual e apaga as linhas depois dele (D42), senão só a linha do mês atual. `previewCeiling(budget, limit, today)`: o `Ceiling` do mês atual com a cota daquela verba trocada por `limit` (verba 0 = nova); a tela avisa quando a soma sobe e fica acima do teto (spec §3). **Desfazer** = duas chamadas: `setCeiling(antes.standard, onward = true)` e `setCeiling(antes.cents, onward = false)`. **Mudar o início do mês** não mexe no teto: o mês atual novo fica com a linha que tinha ou ganha o padrão no `ensureMonths` (diferente da D43: nada some). Tags do cabeçalho, do diálogo e dos avisos em `BudgetTags` (D35). | API pequena: a soma e o teto vêm prontos do `shared` (D12), e o aviso de cota usa a mesma soma. Duas escritas no Desfazer evitam uma função só para ele; a ordem (padrão, depois mês) reconstrói os dois valores. |
| D33 | **Encerrada no bloco 2**: `app/` (cobudget) ficou no build até o bloco do `androidApp` e foi removido no começo dele; o build é `:shared` + `:androidApp`. O que ainda serve de base (listener, `loadLauncherApps`, casos do `CaptureTest`) é lido do histórico (`git show 7616cd2:app/...`). | O cobudget não será mais usado. A importação do backup `.db` dele (D3, D4) continua e não depende do código de `app/`. |

## 2. Componentes

```
androidApp                                          shared
──────────                                          ──────
NotificationListenerService ─RawNotice─► capture() ──┐
Ajustes › cobudget (SAF → caminho) ───► importCobudget ┤
Telas (Compose) ──ações──────────────────────────────┼──► Db.write { } ──► SQLite (margem.db, WAL)
                                                     │       │   (escritor, Mutex)
                                                     │       ├─► version++ ──► telas reconsultam
MargemApp: onWrite = { Glance updateAll } ◄──────────┼───────┘
                                                     │
Telas / widgets / Leitura ◄── Db.read { } ───────────┘   (2 leitores, Channel)
        └── budgetMonth(linhas do mês, hoje, ajustes) ──► BudgetMonth
```

- Um único `Db`, criado no `MargemApp` com `BundledSQLiteDriver`, o caminho de `context.getDatabasePath("margem.db")` e o `onWrite` dos widgets. Usado pela Activity, pelo serviço e pelos widgets (mesmo processo).
- Leituras e escritas rodam em `Dispatchers.IO`.
- O `shared` não conhece `Context`, `StatusBarNotification` nem Glance: tudo que é Android entra por parâmetro (caminho, `RawNotice`, `onWrite`).

## 3. Tipos

Distintos por construção (CLAUDE.md), no `commonMain`:

```kotlin
@JvmInline value class Cents(val v: Long)
@JvmInline value class NoticeId(val v: Long)
@JvmInline value class TransactionId(val v: Long)
@JvmInline value class BudgetId(val v: Long)
@JvmInline value class TagId(val v: Long)

// Fronteira da captura: o androidApp preenche a partir do StatusBarNotification (D25).
class RawNotice(
    val packageName: String, val appLabel: String, val key: String, val postedAt: Long, val whenMs: Long,
    val title: String, val text: String, val bigText: String, val subText: String, val textLines: String,
    val category: String, val channelId: String, val extrasJson: String,
)
```

- Datas são `kotlinx.datetime.LocalDate`/`LocalTime` (D31), gravadas como texto ISO (`2026-10-09`, `11:43`): legíveis e ordenáveis em SQL.
- **Mês** é identificado por `YearMonth` do mês do calendário em que ele começa (`2026-10` = de D/out até a véspera de D/nov).
- Zero é inicialização: `0` em vez de `NULL` para "sem referência" (ex.: lançamento manual tem `notice_id = 0`).

## 4. Banco (esboço do schema)

Migrações: ao abrir, o `Db` lê `PRAGMA user_version` no escritor e aplica um `if (v < N)` por versão numa transação, gravando a versão nova no fim.

```sql
-- Ajustes: dia de início do mês e da semana, tema, ordem das verbas (aba e widget), pauta_seen_at.
CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);

-- Fontes: apps cujas notificações viram recados.
CREATE TABLE sources(package TEXT PRIMARY KEY, label TEXT NOT NULL, added_at INTEGER NOT NULL);

-- Recados: a notificação como chegou. Pendente = não riscado e não referenciado (§5).
CREATE TABLE notices(
  id INTEGER PRIMARY KEY,
  package TEXT NOT NULL, app_label TEXT NOT NULL,
  key TEXT NOT NULL, posted_at INTEGER NOT NULL, when_ms INTEGER NOT NULL,
  title TEXT NOT NULL, text TEXT NOT NULL, big_text TEXT NOT NULL, sub_text TEXT NOT NULL, text_lines TEXT NOT NULL,
  category TEXT NOT NULL, channel_id TEXT NOT NULL, extras_json TEXT NOT NULL,
  content_hash TEXT NOT NULL,
  dismissed INTEGER NOT NULL DEFAULT 0
);

-- AUTOINCREMENT: id de verba apagada nunca volta; tela restaurada ou widget com o id antigo não abre outra verba.
CREATE TABLE budgets(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL COLLATE NOCASE UNIQUE, position INTEGER NOT NULL);

-- Cota de cada mês (D8, D30).
CREATE TABLE budget_months(budget_id INTEGER NOT NULL, month TEXT NOT NULL, limit_cents INTEGER NOT NULL,
  PRIMARY KEY(budget_id, month));

-- Teto de cada mês (D42): soma máxima das cotas; 0 = sem teto. Padrão em settings.ceiling.
CREATE TABLE ceiling_months(month TEXT PRIMARY KEY, cents INTEGER NOT NULL);

-- Passos editados (D9).
CREATE TABLE week_steps(budget_id INTEGER NOT NULL, week_start TEXT NOT NULL, cents INTEGER NOT NULL,
  PRIMARY KEY(budget_id, week_start));

-- Lançamentos. notice_id = 0: manual. corrected = emendado.
-- AUTOINCREMENT: id de lançamento apagado nunca volta, como o das verbas (D38).
CREATE TABLE transactions(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  notice_id INTEGER NOT NULL, budget_id INTEGER NOT NULL,
  merchant TEXT NOT NULL, date TEXT NOT NULL, time TEXT NOT NULL,
  total_cents INTEGER NOT NULL, corrected INTEGER NOT NULL DEFAULT 0
);

-- Parcelas (D7): k = 1..n; a última absorve os centavos.
CREATE TABLE installments(transaction_id INTEGER NOT NULL, k INTEGER NOT NULL, date TEXT NOT NULL, cents INTEGER NOT NULL,
  PRIMARY KEY(transaction_id, k));

-- Estorno: só um registro de que a compra não conta. O alvo é um lançamento OU um recado ainda não lançado.
CREATE TABLE refunds(estorno_notice_id INTEGER PRIMARY KEY, transaction_id INTEGER NOT NULL, notice_id INTEGER NOT NULL);

CREATE TABLE tags(id INTEGER PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE UNIQUE);
CREATE TABLE transaction_tags(transaction_id INTEGER NOT NULL, tag_id INTEGER NOT NULL, PRIMARY KEY(transaction_id, tag_id));

CREATE TABLE merchant_aliases(merchant_key TEXT PRIMARY KEY, alias TEXT NOT NULL);
```

Índices conforme as consultas (ex.: `installments(date)`, `transactions(budget_id)`, `notices(key)`, `notices(posted_at)`).

## 5. Regras no banco

- **Recado pendente** = `dismissed = 0` e nenhuma destas referências: `transactions.notice_id`, `refunds.estorno_notice_id`, `refunds.notice_id`. Processado é derivado, nunca gravado.
- **Gasto do mês de uma verba** = `SUM(installments.cents)` das parcelas com data dentro do mês, de lançamentos da verba **não estornados** (sem linha em `refunds` apontando para eles). Estorno de parcelada tira todas as parcelas.
- **Estorno de recado não lançado**: os dois recados (compra e estorno) saem da Pauta e nada conta.
- **Desfazer estorno** = apagar a linha de `refunds`: a compra volta a contar e os recados voltam a ser pendentes.
- **Apagar lançamento**: apaga parcelas, ligações de marcador e as linhas de `refunds` que apontam para ele; o recado dele e o recado de estorno voltam a ser pendentes.
- **Apagar verba**: apaga lançamentos, parcelas, ligações de marcador, cotas e passos dela; os recados voltam à Pauta.
- **Apagar marcador**: apaga os lançamentos que ficam sem marcador (recados voltam à Pauta).
- **Lançar**: exige verba e ≥1 marcador; grava lançamento + parcelas + marcadores numa transação; `corrected` quando difere da sugestão.
- **Voltar ao automático** (`clearStep`): apaga a linha de `week_steps` da semana; a semana volta a dividir o que sobra com as outras automáticas.
- **Semana travada** (`Week.locked`): a única semana não editada do mês; `setStep` a recusa. Num mês sem linha de cota (antes do primeiro mês da verba), todas as semanas são travadas (decidido na triagem do bloco 5). `Week.max` = cota − Σ outras editadas (0 na travada): é o que `setStep` aceita.
- **Aviso de cota** (`limitDropsEdits`): mesma regra do `setLimit` (algum mês, do atual em diante, com Σ editadas > cota nova), sem gravar; a tela pergunta antes de chamar `setLimit`.
- **Teto** (D42, D46): compara a soma de `budget_months.limit_cents` do mês com `ceiling_months.cents` do mês; só informa, nunca recusa escrita (spec §3). `ensureMonths` preenche `ceiling_months` até o mês pedido com o padrão, a partir da última linha antes dele (sem nenhuma linha, cria só a do mês pedido); não preenche para trás.
- **Estado "novo" do recado**: `posted_at > settings.pauta_seen_at`; abrir a Pauta atualiza `pauta_seen_at`.

## 6. Cálculo do mês

```kotlin
fun budgetMonth(
    month: YearMonth, monthStartDay: Int, weekStartDay: DayOfWeek, today: LocalDate,
    limit: Cents, editedSteps: Map<LocalDate, Cents>, spentByDay: Map<LocalDate, Cents>,
): BudgetMonth
```

Tipos de `kotlinx.datetime` (D31). Depois de `ensureMonths` (D30), uma consulta traz `limit`, os passos editados e o gasto por dia; a função faz o resto:

1. **Janela do mês**: do dia D até a véspera de D no mês seguinte (D inexistente → último dia).
2. **Semanas**: de calendário, a partir de `weekStartDay`, cortadas na janela.
3. **Passos**: editados ficam; os demais dividem `limit − Σ editados` proporcionalmente aos dias, arredondados para baixo; a última semana não editada recebe a diferença (D11).
4. **Ritmo ideal** (hoje) = passos das semanas fechadas + passo da semana atual × dias corridos dela (com hoje) ÷ dias dela.
5. **Estado**: gasto > limit → Fora da margem; gasto > ritmo ideal → Acima do ritmo; senão No ritmo.
6. **Projeção** = gasto × (Σ passos do mês ÷ ritmo ideal de hoje); sem projeção quando o ritmo ideal de hoje é 0.

Saída:

```kotlin
class BudgetMonth(
    val start: LocalDate, val end: LocalDate, val limit: Cents, val spent: Cents, val remaining: Cents,
    val weeks: List<Week>, val ideal: Cents, val status: PaceStatus, val projection: Cents,
    val idealByDay: List<Cents>,   // D44: um por dia da janela
)
class Week(val start: LocalDate, val end: LocalDate, val step: Cents, val edited: Boolean, val spent: Cents)
enum class PaceStatus { ON_PACE, AHEAD, OVERRUN }
```

Validação de edição de passo (parse na fronteira): aceita no máximo `limit − Σ outras editadas` e nunca a única semana não editada (spec §4: uma semana fica sempre automática). `budgetMonth` já devolve isso em cada `Week` (`max`, `locked`), para a tela dizer o máximo antes de salvar.

## 7. UI (`androidApp`)

- **Activity única**: `MainActivity` com `setContent { MargemTheme { App(db) } }`.
- **Navegação** (D18):

```kotlin
sealed interface Screen {
    data object Budgets : Screen; data object Inbox : Screen; data object Transactions : Screen
    data object Insights : Screen; data object Settings : Screen
    data class Budget(val id: BudgetId, val month: YearMonth) : Screen
    data object NewBudget : Screen                        // Nova verba
    data class EditBudget(val id: BudgetId) : Screen      // nome, cota, apagar
    data class Post(val notice: NoticeId) : Screen        // lançar um recado
    data class Refund(val estorno: NoticeId) : Screen     // registrar estorno (escolher a compra)
    data class Edit(val id: TransactionId) : Screen       // editar lançamento
    data object NewTransaction : Screen                   // lançamento manual
    data object Dismissed : Screen                        // Riscados
    data object Sources : Screen                          // Ajustes › Fontes
    data object Tags : Screen                             // Ajustes › Marcadores
    data object StartDays : Screen                        // Ajustes › Início do mês e da semana
    data object Backup : Screen                           // Ajustes › Backup
    data object Cobudget : Screen                         // Ajustes › Importar do cobudget
}
```

  Abrir pelo widget: Intent com extra que vira a aba inicial.
- **Dados na tela** (D19): um helper `@Composable fun <T> query(db, block: (Db) -> T): T?` que observa `db.version` e roda `block` em IO. Telas de mês chamam `db.ensureMonths(month)` antes (D30). Ações chamam funções de escrita num `rememberCoroutineScope`.
- **Tema** (D15): `MargemTheme` fornece `Margem.colors`, `Margem.type`, `Margem.space` por `CompositionLocal`; claro/escuro conforme o ajuste (sistema · claro · escuro).
- **Componentes** (`widgets/Components.kt`): botão, campo de valor, chip de marcador, cartão de recado, régua, seletor de mês, editor de passos, barra de abas, diálogo, snackbar; mais seletor de opções, aviso, cabeçalho de página e linha de lista. **Nascem todos no bloco 2**, com o visual das seções 8.x do book.
- **Campos de dígitos** (`MoneyField`, hora do `DateTimeDialog`): guardam o `TextFieldValue` do teclado, com a composição; só um valor vindo de fora (Desfazer, valor carregado) o substitui. Recriar o valor a cada recomposição descartava a composição de teclados que compõem o número inteiro (FlorisBoard), reiniciava a entrada e perdia dígitos. A `OffsetMapping` da máscara (`withTypedTail`) leva cada dígito digitado a um espaço de largura zero depois do valor: a composição cai em caracteres reais (o cursor do teclado não quebra) e o sublinhado que o Compose põe sob ela, que não está no book, fica com largura zero. Um toque no campo põe o cursor no fim.
- **Catálogo** (só no debug): `src/debug/.../CatalogActivity.kt`, segunda entrada no launcher ("Margem-Dev · catálogo"), com todos os componentes nos estados do book, alternando claro e escuro. A conferência dos componentes é visual, nele; não ganham teste unitário.
- **Gráficos** (`ui/Insights.kt`, D17, D45): ritmo do mês, verbas por mês, ranking, calendário de gastos, um Composable de Canvas cada; o mês do topo e a verba escolhida em `rememberSaveable`.

## 8. Widgets (`androidApp`)

- Dois `GlanceAppWidget`: **Verbas** e **Pauta**, redimensionáveis de 2×2 a 4×4.
  - Verbas: 2×2 = 1 verba, 4×2 = 3, 4×4 = 6, na ordem escolhida em Ajustes.
  - Pauta: 2×2 = contagem, 4×2 = contagem + 2 recados, 4×4 = contagem + 5.
- Leem o mesmo `Db` (`ensureMonths` + `read { }`) e usam `budgetMonth`. Tema segue o ajuste do app.
- Toque abre a aba correspondente. Atualização: D21.

## 9. Arquivos

Pacote Kotlin `com.kaiodelphino.margem` nos dois módulos. Namespace Android: `com.kaiodelphino.margem` no app (D1) e `com.kaiodelphino.margem.shared` no `shared`.

**`shared/src/commonMain/kotlin/com/kaiodelphino/margem/`**

| Arquivo | Conteúdo |
|---|---|
| `Db.kt` | Escritor + leitores (D29), schema, migrações, `write { }`, `read { }`, `version`, `onWrite`, `ensureMonths`, todas as consultas e escritas, exportar/importar (D14). Verbas (bloco 3): `createBudget`, `renameBudget`, `setLimit`, `limitDropsEdits`, `deleteBudget`, `reorderBudgets`, `setStep`, `clearStep`, `budgetsOverview`, `budgetDetail` (com `lines` e `firstMonth`), `previewBudget`. Ciclo do recado (bloco 4): `transactions(today)` com `TransactionRow.now` (a parcela do mês de hoje), `previewRefund`. Ajustes (bloco 5): `startDropsEdits` (D41). Teto: `ceiling`, `setCeiling`, `previewCeiling` (D46). |
| `Types.kt` | Value classes, `RawNotice`, `Parsed`, `PaceStatus`, ajustes e linhas de leitura. |
| `Capture.kt` | `capture(db, raw)`, dedupe (usado também pela importação, D4), SHA-256 (D32), `importCobudget(db, path)` (D3, D40). |
| `Extract.kt` | Leitura de valor/estabelecimento/data (`internal`); `parseBrl` (público). |
| `Month.kt` | `budgetMonth()`, janela do mês, limite da edição de passo, ordem por risco (`internal`). |

**`androidApp/src/main/kotlin/com/kaiodelphino/margem/`**

| Arquivo | Conteúdo |
|---|---|
| `MargemApp.kt` | `Application`: cria o `Db` (driver, caminho, `onWrite` → Glance). |
| `NoticeListener.kt` | `NotificationListenerService`: `StatusBarNotification` → `RawNotice` → `capture()`. |
| `Backup.kt` | Cópia entre um `Uri` do SAF e um arquivo local: exportar (`exportTo` num arquivo temporário → `Uri`), importar o backup e o `.db` do cobudget (`Uri` → arquivo temporário → `importFrom` / `importCobudget`). |
| `ui/MainActivity.kt` | Activity, `Screen`, navegação, `query()`. |
| `ui/Budgets.kt` · `ui/Inbox.kt` · `ui/Transactions.kt` · `ui/Insights.kt` · `ui/Settings.kt` | Uma por aba, com suas sub-telas. `Inbox.kt`: Pauta e Registrar estorno (`InboxTags`). `Transactions.kt`: Lançamentos e o formulário único de lançar/editar/manual (`TransactionTags`). `Settings.kt`: Ajustes e as sub-telas Fontes, Riscados, Marcadores, Início do mês e da semana, Backup e Importar do cobudget (`SettingsTags`). |
| `ui/HomeWidgets.kt` | Os dois widgets de home screen (Glance). |
| `widgets/Theme.kt` · `widgets/Components.kt` | Tokens e componentes do book. |

**`iosApp/`**: não existe na v1 (D26).

Dependências:
- `shared`: `kotlinx-coroutines-core`, `kotlinx-datetime`, `kotlinx-io-core` (backup, D14), `androidx.sqlite:sqlite-bundled`; testes: `kotlin-test`, `kotlinx-coroutines-test`.
- `androidApp`: Compose (foundation, ui, activity-compose), Glance appwidget; testes: JUnit, Robolectric, androidx.test core, `sqlite-framework` (D28), compose ui-test.
- Sem Room, SQLDelight, Material 3, ViewModel, Navigation ou bibliotecas de gráfico.

## 10. Testes

- **Regra** (D23): integração pela API pública; unitário só em `budgetMonth`, extração e SHA-256.
- **Banco** (`shared/commonTest`, rodando em `jvmTest`, SQLite em arquivo temporário por teste, D23): captura → Pauta → lançar → mês da verba; riscar e trazer de volta; estorno de lançamento e de recado; parcelas atravessando meses; virada (`ensureMonths` cria a linha de cota, preenche meses pulados e não incrementa `version`); mudar dia de início (edições descartadas); apagar verba e marcador; leitura durante escrita (D29); backup e reabertura; importação do cobudget com dedupe (o `.db` antigo é criado no teste com o schema v7 do cobudget, que o KDoc de `importCobudget` traz).
- **`budgetMonth`** (`commonTest`): semanas parciais, passos editados e bloqueios, centavos, dia de início 29–31, verba criada no meio do mês, estados e projeção.
- **Extração** (`commonTest`): os casos atuais de `ExtractTest`/`CaptureTest`, portados para `RawNotice`.
- **Android** (`androidApp`, Robolectric, `Db` com `AndroidSQLiteDriver`, D28): conversão `StatusBarNotification` → `RawNotice` → `capture` → `pending()`; UI Compose: fluxo de lançar (verba + marcador obrigatórios, parcelas, prévia) e editor de passos.
- **Rodada de 4 agentes** sobre o contrato fechado (tipos + assinaturas com KDoc, corpos `TODO()`), em paralelo com a implementação, cada um num worktree e num arquivo: **borda** (`EdgeTest`), **fuzz** (`FuzzTest`, seed fixa e tempo limitado), **concorrência e ciclo de vida** (`ConcurrencyTest`), **invariantes** (`InvariantsTest`, sequências aleatórias de ações checando §5). Só veem spec, glossário, arquitetura e contrato. Cada falha é triada pela spec: bug no código → corrige o código; teste que leu a spec errado → corrige o teste, com o porquê. Teste nunca é afrouxado para passar.
- **Rodada em todo bloco**: o bloco 2 ficou sem rodada (o contrato novo era só `rawNotice`). Do bloco 3 em diante, toda rodada cobre os acréscimos do `shared` **e a UI**: as telas entram no contrato como stubs `TODO()` com o objeto de tags (D35), e o teste de UI roda com Robolectric + Compose UI test e `AndroidSQLiteDriver` (D28); o teste instrumentado não roda no worktree de um agente. Os arquivos do bloco levam o prefixo dele (`BudgetsEdgeTest`, …; no bloco 4, `Recado…`). No bloco 4 o contrato de UI é `InboxTags`, `TransactionTags` e `DateTimeTags` (D35, D36). No bloco 5 os arquivos levam o prefixo `Ajustes…`, o contrato de UI é `SettingsTags`, e a escolha de arquivo (SAF) entra no teste por um `ActivityResultRegistryOwner` próprio (`LocalActivityResultRegistryOwner`) que devolve o `Uri` de um arquivo temporário. No bloco 6 os arquivos levam o prefixo `Leitura…`, o contrato do `shared` é `idealByDay` (D44) e o de UI é `InsightsTags`. No teto, o prefixo é `Teto…`, o contrato do `shared` é `Ceiling` e as funções da D46, e o de UI são as tags novas de `BudgetTags`.
- **Teclado que compõe**: `typeComposing` (`androidApp/src/test`) digita pelo `InputConnection` da view como o FlorisBoard (marca o número como palavra em composição e a troca a cada tecla, pedindo a posição do cursor); o shadow `ActiveKeyboard` faz o Robolectric ter um teclado ativo. Cobre cota (Nova e Editar verba), passo da semana, valor do lançamento e hora.
- O alvo `iosSimulatorArm64Test` existe, mas fica fora do loop da v1.

## 11. Em aberto

| # | Questão |
|---|---|
| Q1 | (Depois da v1) O iOS não deixa um app ler notificações de outros apps: de onde vêm os recados no `iosApp`? |
