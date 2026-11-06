---

name: mr-description

description: >
  Gera título e descrição de Merge Request seguindo o template do projeto (.gitlab/merge_request_templates/merge_request.md).
  Analisa diff e commits da branch atual contra a branch alvo. Use when user says "descrição de MR",
  "criar MR", "escrever MR", "MR description", "write MR description", "descrever MR", or invokes /mr-description.
---

---

# MR Title & Description Generator

## Process

1. Get target branch from user (default: `main`)

2. Run:

   ```bash
   git log <target>..HEAD --oneline
   git diff <target>...HEAD
   ```

3. Generate MR title (see Title rules)

4. Fill template — Portuguese, simple and direct

5. Output: title first (own code block), then description as markdown code block

## Title

Format: `[<ISSUE>] <resumo>`

- `<ISSUE>`: extrair do nome da branch (`APP-1807`, `APP-123`, etc.). Sem issue → omitir colchetes
- `<resumo>`: pt-BR, imperativo, ≤ 70 chars, resume a mudança principal (ex.: `[APP-3115] Implementa funcionamento da performance no modal de datetime`)
- Sem ponto final

## Template

```md
## Descrição

## Alterações Propostas

## Issues Relacionadas
APP-<ISSUE>

## Checklist de Revisão
* [ ] Merge Request referente a minha branch subtask -> task/defeat/user-story.
* [ ] O código segue as diretrizes de estilo e padrões do projeto.
* [ ] As alterações foram revisadas em multiplos devices(Android/Ios).
* [ ] O Merge Request foi testado localmente antes de ser submetido.

## Screenshots

## Informações Adicionais (OPCIONAL)
```

## Rules

- pt-BR, simples e direto — sem floreios e sem linguagem excessivamente técnica quando não for necessária.
- Descrição: explicar brevemente o problema atual e a solução adotada. Pode usar 2-4 frases ou uma pequena lista quando isso deixar a mudança mais clara.
- A descrição deve deixar claro qual comportamento existia antes e qual comportamento passa a existir depois da alteração quando isso for relevante.
- Alterações Propostas: organizar em bullets por arquivo/módulo, explicando de forma curta o que mudou e, quando necessário, qual comportamento foi alterado. Não limitar artificialmente a uma única linha por arquivo.
- Regras de negócio: quando a alteração envolver ordenação, filtros, prioridades, estados ou outros critérios de comportamento, descrever explicitamente a nova regra e seus critérios de desempate/prioridade, quando existirem.
- Testes: quando houver alterações ou novos testes relevantes no diff, incluir uma seção `## Testes` após `## Alterações Propostas`, resumindo os principais cenários cobertos.
- Não criar uma seção de testes apenas para dizer que testes foram executados localmente; nesse caso, o checklist já cobre isso.
- Issues Relacionadas: extrair do nome da branch (`APP-1807`, `APP-123`, etc.).
- Screenshots: usar "Sem mudanças visuais." se não houver alteração visual.
- Informações Adicionais: omitir se vazio. Usar essa seção para registrar decisões de escopo, limitações ou comportamentos que o reviewer precisa saber e que não ficam claros no diff.
- Quando houver algo explicitamente fora do escopo da MR, registrar em Informações Adicionais.
- Não inventar alterações, testes ou decisões que não estejam evidenciados no diff, commits ou informações fornecidas pelo usuário.
- Checklist: manter desmarcado — o autor preenche.
- Evitar transformar a descrição em documentação extensa. O objetivo é facilitar a revisão do MR, não explicar toda a implementação.
- Priorizar clareza do comportamento sobre quantidade de detalhes.
