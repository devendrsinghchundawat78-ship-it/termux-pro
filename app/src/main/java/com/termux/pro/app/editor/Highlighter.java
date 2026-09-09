package com.termux.pro.app.editor;

import android.text.Spannable;
import android.text.style.ForegroundColorSpan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small, dependency-free syntax highlighter.
 *
 * A simple character scanner tags the text with COMMENT / STRING / KEYWORD / NUMBER
 * segments and those are turned into coloured spans. Language rules are detected from
 * the file extension (java, kotlin, python, js/ts, c/cpp, sh, xml/html, css, sql,
 * go, rust, json, yaml, ...). Multi-line block comments and triple-quoted strings
 * are supported, so it behaves well on real code.
 */
public final class Highlighter {

    private Highlighter() {}

    public static final int T_COMMENT = 1;
    public static final int T_STRING = 2;
    public static final int T_KEYWORD = 3;
    public static final int T_NUMBER = 4;

    public static final class Token {
        public final int start;
        public final int end;
        public final int type;

        Token(int start, int end, int type) {
            this.start = start;
            this.end = end;
            this.type = type;
        }
    }

    public static final class LanguageSpec {
        public final String[] keywords;
        public final String lineComment;   // e.g. "//" or null
        public final String blockStart;    // e.g. "/*" or null
        public final String blockEnd;      // e.g. "*/"
        public final boolean tripleQuotes; // python style ''' / """
        public final boolean backticks;    // js template literals

        LanguageSpec(String[] keywords, String lineComment, String blockStart, String blockEnd,
                     boolean tripleQuotes, boolean backticks) {
            this.keywords = keywords;
            this.lineComment = lineComment;
            this.blockStart = blockStart;
            this.blockEnd = blockEnd;
            this.tripleQuotes = tripleQuotes;
            this.backticks = backticks;
        }

        boolean hasKeywords() {
            return keywords != null && keywords.length > 0;
        }
    }

    // ----------------------------------------------------------------------------------
    // Keyword sets
    // ----------------------------------------------------------------------------------

    private static final String[] JAVA = {
        "public", "private", "protected", "class", "interface", "enum", "record", "extends", "implements",
        "new", "this", "super", "final", "abstract", "static", "void", "int", "boolean", "byte", "char",
        "double", "float", "long", "short", "var", "package", "import", "throws", "throw", "try", "catch",
        "finally", "return", "if", "else", "switch", "case", "default", "for", "while", "do", "break",
        "continue", "instanceof", "synchronized", "null", "true", "false", "assert", "native", "transient",
        "volatile", "strictfp", "yield", "sealed", "permits", "goto", "const"
    };

    private static final String[] C_LANG = {
        "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else", "enum",
        "extern", "float", "for", "goto", "if", "inline", "int", "long", "register", "return", "short",
        "signed", "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned", "void",
        "volatile", "while", "include", "define", "undef", "ifdef", "ifndef", "endif", "pragma", "error",
        "bool", "true", "false", "nullptr", "class", "namespace", "template", "typename", "using",
        "public", "private", "protected", "virtual", "override", "final", "constexpr", "const_cast",
        "static_cast", "reinterpret_cast", "dynamic_cast", "new", "delete", "try", "catch", "throw",
        "operator", "friend", "this", "auto", "decltype", "noexcept", "explicit", "mutable", "NULL"
    };

    private static final String[] KOTLIN = {
        "fun", "val", "var", "class", "object", "interface", "enum", "when", "if", "else", "for", "while",
        "do", "return", "package", "import", "data", "sealed", "override", "open", "private", "public",
        "internal", "protected", "companion", "init", "constructor", "this", "super", "by", "as", "is",
        "in", "out", "vararg", "null", "true", "false", "suspend", "inline", "tailrec", "operator",
        "infix", "reified", "const", "lateinit", "get", "set", "field", "delegate", "break", "continue"
    };

    private static final String[] JS = {
        "var", "let", "const", "function", "return", "if", "else", "for", "while", "do", "switch", "case",
        "break", "continue", "new", "class", "extends", "import", "export", "from", "default", "async",
        "await", "try", "catch", "finally", "throw", "typeof", "instanceof", "this", "super", "true",
        "false", "null", "undefined", "of", "in", "yield", "static", "get", "set", "interface", "type",
        "namespace", "public", "private", "readonly", "enum", "implements", "delete", "void", "debugger"
    };

    private static final String[] PYTHON = {
        "def", "class", "return", "if", "elif", "else", "for", "while", "import", "from", "as", "with",
        "try", "except", "finally", "raise", "lambda", "pass", "break", "continue", "global", "nonlocal",
        "yield", "del", "in", "is", "not", "and", "or", "None", "True", "False", "assert", "async",
        "await", "match", "case", "print", "len", "range", "type", "self", "super"
    };

    private static final String[] BASH = {
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac",
        "function", "in", "return", "break", "continue", "exit", "local", "export", "readonly", "set",
        "unset", "source", "select", "shift", "trap", "alias", "declare", "true", "false", "echo", "cd",
        "pwd", "test", "printf", "exec", "eval", "let"
    };

    private static final String[] SQL = {
        "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create",
        "table", "alter", "drop", "index", "join", "left", "right", "inner", "outer", "cross", "on", "as",
        "and", "or", "not", "null", "primary", "key", "foreign", "references", "order", "by", "group",
        "having", "limit", "offset", "distinct", "count", "sum", "avg", "min", "max", "exists", "like",
        "between", "case", "when", "then", "else", "end", "begin", "commit", "rollback", "transaction",
        "union", "all", "in", "is", "desc", "asc", "unique", "default", "check", "constraint", "view"
    };

    private static final String[] GO = {
        "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for",
        "func", "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select",
        "struct", "switch", "type", "var", "true", "false", "nil", "make", "len", "cap", "append"
    };

    private static final String[] RUST = {
        "fn", "let", "mut", "struct", "enum", "impl", "mod", "use", "pub", "crate", "self", "super",
        "match", "if", "else", "loop", "while", "for", "return", "break", "continue", "unsafe", "async",
        "await", "move", "ref", "dyn", "trait", "where", "type", "const", "static", "in", "true", "false",
        "as", "extern", "macro_rules"
    };

    private static final String[] JSON_LANG = {"true", "false", "null"};

    private static final String[] CSS = {
        "important", "inherit", "initial", "auto", "none", "block", "inline", "flex", "grid", "absolute",
        "relative", "fixed", "static", "sticky", "bold", "italic", "solid", "dashed", "hidden", "visible",
        "center", "left", "right", "top", "bottom", "nowrap", "wrap"
    };

    private static final String[] YAML = {"true", "false", "null", "yes", "no", "on", "off"};

    // ----------------------------------------------------------------------------------
    // Extension -> language rules
    // ----------------------------------------------------------------------------------

    private static final Map<String, LanguageSpec> LANGUAGES = new LinkedHashMap<>();

    private static void lang(String ext, String[] keywords, String lineComment, String blockStart,
                             String blockEnd, boolean triple, boolean backticks) {
        LANGUAGES.put(ext, new LanguageSpec(keywords, lineComment, blockStart, blockEnd, triple, backticks));
    }

    static {
        lang("java", JAVA, "//", "/*", "*/", false, false);
        lang("kt", KOTLIN, "//", "/*", "*/", false, false);
        lang("kts", KOTLIN, "//", "/*", "*/", false, false);
        lang("c", C_LANG, "//", "/*", "*/", false, false);
        lang("h", C_LANG, "//", "/*", "*/", false, false);
        lang("cpp", C_LANG, "//", "/*", "*/", false, false);
        lang("cc", C_LANG, "//", "/*", "*/", false, false);
        lang("cxx", C_LANG, "//", "/*", "*/", false, false);
        lang("hpp", C_LANG, "//", "/*", "*/", false, false);
        lang("js", JS, "//", "/*", "*/", false, true);
        lang("mjs", JS, "//", "/*", "*/", false, true);
        lang("cjs", JS, "//", "/*", "*/", false, true);
        lang("jsx", JS, "//", "/*", "*/", false, true);
        lang("ts", JS, "//", "/*", "*/", false, true);
        lang("tsx", JS, "//", "/*", "*/", false, true);
        lang("json", JSON_LANG, null, null, null, false, false);
        lang("py", PYTHON, "#", null, null, true, false);
        lang("sh", BASH, "#", null, null, false, false);
        lang("bash", BASH, "#", null, null, false, false);
        lang("zsh", BASH, "#", null, null, false, false);
        lang("sql", SQL, "--", "/*", "*/", false, false);
        lang("go", GO, "//", "/*", "*/", false, false);
        lang("rs", RUST, "//", "/*", "*/", false, false);
        lang("css", CSS, null, "/*", "*/", false, false);
        lang("scss", CSS, null, "/*", "*/", false, false);
        lang("yaml", YAML, "#", null, null, false, false);
        lang("yml", YAML, "#", null, null, false, false);
        lang("xml", null, null, "<!--", "-->", false, false);
        lang("html", null, null, "<!--", "-->", false, false);
        lang("htm", null, null, "<!--", "-->", false, false);
        lang("md", null, null, null, null, false, false);
    }

    /** Pick the language rules for a file name (case insensitive). Null = plain text. */
    public static LanguageSpec detect(String fileName) {
        if (fileName == null) return null;
        String name = fileName.toLowerCase(Locale.US);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            // extension-less special files
            if (name.equals("dockerfile") || name.equals("makefile") || name.equals("cmakelists.txt"))
                return new LanguageSpec(BASH, "#", null, null, false, false);
            if (name.equals("gradle"))
                return LANGUAGES.get("kts");
            return null;
        }
        String ext = name.substring(dot + 1);
        if (ext.equals("gitignore"))
            return LANGUAGES.get("sh");
        if (ext.equals("gradle"))
            return LANGUAGES.get("kts");
        return LANGUAGES.get(ext);
    }

    // ----------------------------------------------------------------------------------
    // Tokenizer
    // ----------------------------------------------------------------------------------

    /** Scan {@code text} and return coloured segments (sorted by start). */
    public static List<Token> tokenize(String text, LanguageSpec spec) {
        List<Token> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) return tokens;

        Map<String, Boolean> keywordSet = null;
        if (spec != null && spec.hasKeywords()) {
            keywordSet = new HashMap<>();
            for (String keyword : spec.keywords) keywordSet.put(keyword, Boolean.TRUE);
        }

        final int n = text.length();
        boolean blockComment = false;
        int i = 0;

        while (i < n) {
            char c = text.charAt(i);

            if (blockComment) {
                if (spec != null && spec.blockEnd != null && text.startsWith(spec.blockEnd, i)) {
                    i += spec.blockEnd.length();
                    blockComment = false;
                } else {
                    i++;
                }
                continue;
            }

            // comment starts
            if (spec != null && spec.lineComment != null && text.startsWith(spec.lineComment, i)) {
                int end = text.indexOf('\n', i);
                if (end == -1) end = n;
                tokens.add(new Token(i, end, T_COMMENT));
                i = end;
                continue;
            }
            if (spec != null && spec.blockStart != null && text.startsWith(spec.blockStart, i)) {
                blockComment = true;
                i += spec.blockStart.length();
                continue;
            }

            // strings
            boolean triple = spec != null && spec.tripleQuotes && i + 2 < n
                && c == text.charAt(i + 1) && c == text.charAt(i + 2)
                && (c == '"' || c == '\'');
            if (triple) {
                int end = findClosing(text, i + 3, c, spec, true);
                tokens.add(new Token(i, end == -1 ? n : end + 3, T_STRING));
                i = end == -1 ? n : end + 3;
                continue;
            }
            if ((c == '"' || c == '\'' || (spec != null && spec.backticks && c == '`'))) {
                int end = findClosing(text, i + 1, c, spec, false);
                if (end == -1) {
                    int nl = text.indexOf('\n', i);
                    tokens.add(new Token(i, nl == -1 ? n : nl, T_STRING));
                    i = nl == -1 ? n : nl;
                } else {
                    tokens.add(new Token(i, end + 1, T_STRING));
                    i = end + 1;
                }
                continue;
            }

            // identifiers / numbers
            if (Character.isLetterOrDigit(c) || c == '_') {
                int start = i;
                while (i < n) {
                    char ch = text.charAt(i);
                    if (!(Character.isLetterOrDigit(ch) || ch == '_')) break;
                    i++;
                }
                String word = text.substring(start, i);
                boolean startsWithDigit = Character.isDigit(text.charAt(start));
                if (!startsWithDigit && keywordSet != null && keywordSet.containsKey(word)) {
                    tokens.add(new Token(start, i, T_KEYWORD));
                } else if (startsWithDigit) {
                    tokens.add(new Token(start, i, T_NUMBER));
                }
                continue;
            }

            i++;
        }

        return tokens;
    }

    /** Find closing quote handling backslash escapes; returns index of quote char or -1. */
    private static int findClosing(String text, int from, char quote, LanguageSpec spec, boolean triple) {
        int i = from;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == '\\' && i + 1 < text.length()) {
                i += 2;
                continue;
            }
            if (ch == quote) {
                if (triple) {
                    if (i + 2 < text.length() && text.charAt(i + 1) == quote && text.charAt(i + 2) == quote) {
                        return i;
                    }
                } else {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    // ----------------------------------------------------------------------------------
    // Colours & application
    // ----------------------------------------------------------------------------------

    /** Dark theme colours, similar to a modern IDE. */
    public static final int COLOR_KEYWORD = 0xFFC586C0;
    public static final int COLOR_STRING = 0xFFCE9178;
    public static final int COLOR_COMMENT = 0xFF6A9955;
    public static final int COLOR_NUMBER = 0xFFB5CEA8;

    /** Remove all highlighting spans (used before re-highlighting). */
    public static void clear(Spannable spannable) {
        ForegroundColorSpan[] spans = spannable.getSpans(0, spannable.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan span : spans) {
            spannable.removeSpan(span);
        }
    }

    /** Highlight {@code spannable} with the given language (must run on the UI thread). */
    public static void apply(Spannable spannable, LanguageSpec spec) {
        clear(spannable);
        if (spec == null) return;

        int colorForType;
        List<Token> tokens = tokenize(spannable.toString(), spec);
        for (Token token : tokens) {
            switch (token.type) {
                case T_KEYWORD: colorForType = COLOR_KEYWORD; break;
                case T_STRING: colorForType = COLOR_STRING; break;
                case T_COMMENT: colorForType = COLOR_COMMENT; break;
                case T_NUMBER: colorForType = COLOR_NUMBER; break;
                default: continue;
            }
            int end = Math.min(token.end, spannable.length());
            if (token.start < end) {
                spannable.setSpan(new ForegroundColorSpan(colorForType), token.start, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }
}
