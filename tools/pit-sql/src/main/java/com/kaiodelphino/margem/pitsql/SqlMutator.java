package com.kaiodelphino.margem.pitsql;

import java.util.ArrayList;
import java.util.List;
import org.pitest.reloc.asm.Handle;
import org.pitest.reloc.asm.MethodVisitor;
import org.pitest.reloc.asm.Opcodes;
import org.pitest.mutationtest.engine.MutationIdentifier;
import org.pitest.mutationtest.engine.gregor.MethodInfo;
import org.pitest.mutationtest.engine.gregor.MethodMutatorFactory;
import org.pitest.mutationtest.engine.gregor.MutationContext;

/**
 * PIT mutator for the hand-written SQL of the Db (architecture D48). It mutates the SQL inside string
 * constants: `LDC` of a string, and the recipe of a `makeConcatWithConstants` (a Kotlin string template
 * with `$x` compiles to one, the `\u0001` slots standing for the values). A string is SQL when its first
 * word is SELECT, INSERT, UPDATE, DELETE, WITH or REPLACE, or, in the Db and Capture classes (where the
 * queries are put together from fragments like "t.budget_id = ? AND t.id = ?"), when it has an
 * uppercase SQL word (AND, OR, WHERE, JOIN, ORDER BY, SUM, IS NULL…). Operators: AND↔OR, `<`↔`<=`, `>`↔`>=`,
 * SUM↔MAX, LEFT JOIN↔JOIN, COALESCE(x, d)→x, ASC↔DESC, IS NULL↔IS NOT NULL; one mutant per
 * occurrence, outside quoted literals.
 *
 * Registered through PIT's ServiceLoader (META-INF/services) under the name "SQL": `--mutators DEFAULTS,SQL`.
 * PIT tells mutants apart by (method, instruction, mutator id), and one string holds many occurrences,
 * so each mutant gets its own id: `SQL_<OP>#<n>`, the n-th occurrence of that operator in the string.
 */
public final class SqlMutator implements MethodMutatorFactory {

    private static final String PREFIX = "SQL_";
    private static final String[] SQL_STARTS = {"SELECT", "INSERT", "UPDATE", "DELETE", "WITH", "REPLACE"};
    /** Uppercase only: the Db's SQL is written in capitals, its other strings (setting keys, messages) are not. */
    private static final java.util.Set<String> SQL_WORDS = java.util.Set.of(
            "AND", "OR", "WHERE", "JOIN", "ORDER", "GROUP", "HAVING", "SUM", "MAX", "MIN", "COUNT", "COALESCE",
            "IS", "NULL", "ASC", "DESC", "BETWEEN", "EXISTS", "IN", "NOT", "FROM", "SET", "VALUES", "LIMIT");
    private static final java.util.regex.Pattern WORD = java.util.regex.Pattern.compile("[A-Za-z_]+");

    @Override
    public String getGloballyUniqueId() {
        return "SQL";
    }

    @Override
    public String getName() {
        return "SQL";
    }

    @Override
    public boolean isMutatorFor(MutationIdentifier id) {
        return id.getMutator().startsWith(PREFIX);
    }

    @Override
    public MethodVisitor create(MutationContext context, MethodInfo info, MethodVisitor next) {
        String owner = info.getOwningClass().getName();
        boolean fragments = isQueryClass(owner);
        return new MethodVisitor(Opcodes.ASM9, next) {
            @Override
            public void visitLdcInsn(Object value) {
                super.visitLdcInsn(value instanceof String s ? mutate(context, s, fragments) : value);
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... args) {
                if (bsm.getOwner().equals("java/lang/invoke/StringConcatFactory") && name.equals("makeConcatWithConstants")
                        && args.length > 0 && args[0] instanceof String recipe) {
                    args = args.clone();
                    args[0] = mutate(context, recipe, fragments);
                }
                super.visitInvokeDynamicInsn(name, descriptor, bsm, args);
            }
        };
    }

    /** Registers every mutant of [s]; returns the active one's text, or [s] itself. */
    private static String mutate(MutationContext context, String s, boolean fragments) {
        if (!isSql(s) && !(fragments && hasSqlWord(s))) return s;
        String out = s;
        for (Mutant m : mutants(s)) {
            MutationIdentifier id = context.registerMutation(new Id(m.id), m.description);
            if (context.shouldMutate(id)) out = m.text;
        }
        return out;
    }

    static boolean isSql(String s) {
        String t = s.stripLeading();
        for (String start : SQL_STARTS) {
            if (t.regionMatches(true, 0, start, 0, start.length())
                    && (t.length() == start.length() || !isWordChar(t.charAt(start.length())))) return true;
        }
        return false;
    }

    /** Db, its inner classes, and Capture's file class (CaptureKt): where the SQL lives. */
    static boolean isQueryClass(String internalName) {
        String simple = internalName.substring(internalName.lastIndexOf('/') + 1);
        return simple.equals("Db") || simple.startsWith("Db$") || simple.startsWith("CaptureKt");
    }

    static boolean hasSqlWord(String s) {
        java.util.regex.Matcher m = WORD.matcher(s);
        while (m.find()) if (SQL_WORDS.contains(m.group())) return true;
        return false;
    }

    record Mutant(String id, String description, String text) {}

    private record Token(String text, int start, int end) {
        boolean is(String word) {
            return text.equalsIgnoreCase(word);
        }
    }

    /** Every mutant of the SQL [s], in a fixed order (finding and applying must number them the same way). */
    static List<Mutant> mutants(String s) {
        List<Token> t = tokens(s);
        List<Mutant> out = new ArrayList<>();
        // AND↔OR. Skipped: the AND of BETWEEN x AND y, and the OR of INSERT OR IGNORE / OR REPLACE (not boolean).
        int n = 0;
        int between = 0;
        for (int i = 0; i < t.size(); i++) {
            Token k = t.get(i);
            if (k.is("BETWEEN")) between++;
            else if (k.is("AND") && between > 0) between--;
            else if (k.is("AND")) out.add(replace(s, "AND_OR", ++n, k, "OR"));
            else if (k.is("OR") && !(i > 0 && (t.get(i - 1).is("INSERT") || t.get(i - 1).is("UPDATE")))) out.add(replace(s, "AND_OR", ++n, k, "AND"));
        }
        // < ↔ <=, > ↔ >=
        n = 0;
        for (Token k : t) {
            if (k.text.equals("<")) out.add(replace(s, "LT_LE", ++n, k, "<="));
            else if (k.text.equals("<=")) out.add(replace(s, "LT_LE", ++n, k, "<"));
        }
        n = 0;
        for (Token k : t) {
            if (k.text.equals(">")) out.add(replace(s, "GT_GE", ++n, k, ">="));
            else if (k.text.equals(">=")) out.add(replace(s, "GT_GE", ++n, k, ">"));
        }
        // SUM ↔ MAX (as functions)
        n = 0;
        for (int i = 0; i + 1 < t.size(); i++) {
            if (!t.get(i + 1).text.equals("(")) continue;
            if (t.get(i).is("SUM")) out.add(replace(s, "SUM_MAX", ++n, t.get(i), "MAX"));
            else if (t.get(i).is("MAX")) out.add(replace(s, "SUM_MAX", ++n, t.get(i), "SUM"));
        }
        // LEFT [OUTER] JOIN → JOIN, JOIN → LEFT JOIN
        n = 0;
        for (int i = 0; i < t.size(); i++) {
            Token k = t.get(i);
            if (k.is("LEFT")) {
                int j = i + 1 < t.size() && t.get(i + 1).is("OUTER") ? i + 2 : i + 1;
                if (j < t.size() && t.get(j).is("JOIN")) out.add(splice(s, "JOIN", ++n, k.start, t.get(j).end, "JOIN", "LEFT JOIN→JOIN"));
            } else if (k.is("JOIN") && !(i > 0 && (t.get(i - 1).is("LEFT") || t.get(i - 1).is("OUTER")))) {
                out.add(splice(s, "JOIN", ++n, k.start, k.end, "LEFT JOIN", "JOIN→LEFT JOIN"));
            }
        }
        // COALESCE(x, d, …) → x
        n = 0;
        for (int i = 0; i + 1 < t.size(); i++) {
            if (!t.get(i).is("COALESCE") || !t.get(i + 1).text.equals("(")) continue;
            int depth = 0;
            int comma = -1;
            for (int j = i + 1; j < t.size(); j++) {
                String x = t.get(j).text;
                if (x.equals("(")) depth++;
                else if (x.equals(")") && --depth == 0) {
                    if (comma >= 0) {
                        String first = s.substring(t.get(i + 1).end, t.get(comma).start).strip();
                        out.add(splice(s, "COALESCE", ++n, t.get(i).start, t.get(j).end, first, "COALESCE(x, d)→x"));
                    }
                    break;
                } else if (x.equals(",") && depth == 1 && comma < 0) comma = j;
            }
        }
        // ASC ↔ DESC
        n = 0;
        for (Token k : t) {
            if (k.is("ASC")) out.add(replace(s, "ASC_DESC", ++n, k, "DESC"));
            else if (k.is("DESC")) out.add(replace(s, "ASC_DESC", ++n, k, "ASC"));
        }
        // IS NULL ↔ IS NOT NULL
        n = 0;
        for (int i = 0; i + 1 < t.size(); i++) {
            if (!t.get(i).is("IS")) continue;
            if (t.get(i + 1).is("NULL")) out.add(splice(s, "IS_NULL", ++n, t.get(i).start, t.get(i + 1).end, "IS NOT NULL", "IS NULL→IS NOT NULL"));
            else if (i + 2 < t.size() && t.get(i + 1).is("NOT") && t.get(i + 2).is("NULL")) {
                out.add(splice(s, "IS_NULL", ++n, t.get(i).start, t.get(i + 2).end, "IS NULL", "IS NOT NULL→IS NULL"));
            }
        }
        // A string template's slots (\u0001, \u0002) must survive: the call site checks their count.
        out.removeIf(m -> slots(m.text) != slots(s));
        return out;
    }

    private static Mutant replace(String s, String op, int n, Token k, String with) {
        return splice(s, op, n, k.start, k.end, with, k.text.toUpperCase() + "→" + with);
    }

    private static Mutant splice(String s, String op, int n, int from, int to, String with, String what) {
        String text = s.substring(0, from) + with + s.substring(to);
        int a = Math.max(0, from - 30);
        int b = Math.min(s.length(), to + 30);
        String around = (a > 0 ? "…" : "") + s.substring(a, b).replace('\u0001', '$') + (b < s.length() ? "…" : "");
        return new Mutant(PREFIX + op + "#" + n, "SQL " + what + " in \"" + around + "\"", text);
    }

    private static long slots(String s) {
        return s.chars().filter(c -> c == 1 || c == 2).count();
    }

    /** Words, operators (`<=`, `>=`, `<>`, `!=`, …) and single characters; quoted literals and identifiers are skipped. */
    private static List<Token> tokens(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < s.length()) {
                    if (s.charAt(j) == c) {
                        if (j + 1 < s.length() && s.charAt(j + 1) == c) j += 2;
                        else break;
                    } else j++;
                }
                i = j + 1;
            } else if (isWordChar(c)) {
                int j = i;
                while (j < s.length() && isWordChar(s.charAt(j))) j++;
                out.add(new Token(s.substring(i, j), i, j));
                i = j;
            } else if (Character.isWhitespace(c)) {
                i++;
            } else {
                int j = i + 1;
                if (j < s.length() && "<>!=".indexOf(c) >= 0 && "<>=".indexOf(s.charAt(j)) >= 0) j++;
                out.add(new Token(s.substring(i, j), i, j));
                i = j;
            }
        }
        return out;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Carries one mutant's id into PIT, which builds ids from a factory's getGloballyUniqueId. */
    private record Id(String id) implements MethodMutatorFactory {
        @Override
        public String getGloballyUniqueId() {
            return id;
        }

        @Override
        public String getName() {
            return "SQL";
        }
    }
}
