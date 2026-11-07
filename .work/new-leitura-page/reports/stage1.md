# Stage 1 — new-leitura-page

## AC-to-test map

Source: `scripts/pipeline/ac-map.sh --slug new-leitura-page`. The script reports no uncovered and no unknown ACs. AC-13 does not appear in the map, and the script does not list it as uncovered either.

- `androidApp/src/test/kotlin/com/kaiodelphino/margem/LeituraUiTest.kt:113` covers AC-46 and AC-F1 (with no budget, Leitura shows today's empty state).
- `LeituraUiTest.kt:121` covers AC-1, AC-2, AC-8 to AC-11 and AC-25 to AC-45 (the main grid test).
- `LeituraUiTest.kt:180` covers AC-3 to AC-7 (the single field suggests per section, and the chips combine).
- `LeituraUiTest.kt:217` covers AC-12 and AC-15 to AC-21 (the widget page).
- `LeituraUiTest.kt:276` covers AC-22 to AC-24 (Ritmo shows the ideal pace and quota only for a full month with no filter).
- `LeituraLifecycleTest.kt:93` covers AC-14 and AC-F4 (Desfazer after leaving the tab).
- `LeituraLifecycleTest.kt:119` covers AC-F3 (widget page, filter and options survive recreation).
- `LeituraLifecycleTest.kt:136` covers AC-F2 (month rollover with Leitura open re-reads with the current today).

Test files in scope: LeituraInvariantsTest.kt, LeituraLifecycleTest.kt, LeituraUiTest.kt, MargemUiTest.kt, ReservaUiTest.kt (androidApp) and ConcurrencyTest.kt (shared commonTest).

## Candidate spread

Not run here, because this stage resumed after apply. The only candidate on disk is `candidates/1.patch`.

## Winner

No earlier stage 1 report exists, so no winner was recorded. The applied code is commit 1d2a1d1, "feat(leitura): rewrite Leitura as filtered widget grid".

## Review verdict

The diff is lean overall. budget.sh passes: 1275 added against a 4200 limit, and 11 new public symbols against 21. It also implements far less than the manifest allowed. Db gets one new read (leituraLines) instead of the six approved, and Insights.kt keeps RangeSheet private and holds the widget helpers inline. TransactionLine was not moved. I found no duplication against an existing symbol: the helpers I checked either replace the old Insights.kt copies or differ from the existing CeilingLine sum and weekRange. A competent engineer would not have written much less. The only violations are bookkeeping: two public symbols and one test file that manifest.md does not approve. Each one is used and justified, so the fix is a manifest amendment, or narrowing visibility where possible, not a code cut.

## Flagged but not fixed

- Manifest drift: two public symbols and one test file are not approved in manifest.md. The fix is either a manifest amendment or narrower visibility. Neither was applied.
- `reports/apply.log` ends with LeituraInvariantsTest failures and then `make: *** [Makefile:44: test-android] Error 143`, which means the run was killed. The failing cases are seed 11 day 1 week from monday, seed 23 day 5 week from sunday, and seed 37 day 1 week from saturday. Each throws an AssertionError at LeituraInvariantsTest.kt:695, caused by an IllegalStateException at :382. This report does not resolve those failures; stage 2 should look at them.
