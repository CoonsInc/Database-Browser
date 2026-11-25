package org.databasebrowser.sqlite;

import static org.databasebrowser.core.domain.Errors.error;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.databasebrowser.core.domain.Errors.Code;

/** Проверяет границу команды. Синтаксис SQL разбирает SQLite. */
final class SqliteSql {
    private final String sql;
    private int offset;

    private SqliteSql(String sql) {
        this.sql = sql;
    }

    /** Проверяет одну команду и определяет, сообщает ли она число изменённых строк. */
    static boolean validateSingleStatement(String sql) {
        if (sql == null || sql.indexOf('\0') >= 0) {
            throw error(Code.INVALID_INPUT, "SQL text is missing or contains a NUL character");
        }

        List<Token> tokens = new SqliteSql(sql).readTokens();
        int start = 0;
        while (is(tokens, start, ";")) {
            start++;
        }
        if (start == tokens.size()) {
            throw error(Code.INVALID_INPUT, "SQL statement is required");
        }

        boolean trigger = isTrigger(tokens, start);
        int end = tokens.size();
        for (int i = start; i < tokens.size(); i++) {
            // Внутренние команды CREATE TRIGGER завершаются до финального END.
            if (is(tokens, i, ";") && (!trigger
                || is(tokens, i - 2, ";") && is(tokens, i - 1, "END"))) {
                end = i;
                break;
            }
        }
        for (int i = end; i < tokens.size(); i++) {
            if (!is(tokens, i, ";")) {
                throw error(Code.INVALID_INPUT, "SQLite accepts one SQL statement at a time");
            }
        }

        Token first = tokens.get(start);
        if (first.is("BEGIN") || first.is("COMMIT") || first.is("END") || first.is("ROLLBACK")
            || first.is("SAVEPOINT") || first.is("RELEASE")) {
            throw error(Code.UNSUPPORTED, "SQLite manual transactions are not supported");
        }
        return changesRows(tokens, start, end);
    }

    /** Извлекает текст после ключевого слова вне кавычек и скобок. */
    static Optional<String> afterKeyword(String sql, String keyword) {
        int depth = 0;
        for (Token token : new SqliteSql(sql).readTokens()) {
            if (token.is("(")) {
                depth++;
            } else if (token.is(")")) {
                depth--;
            } else if (depth == 0 && token.is(keyword)) {
                return Optional.of(sql.substring(token.end()).strip());
            }
        }
        return Optional.empty();
    }

    static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** SQLite сравнивает имена без учёта регистра только для ASCII. */
    static boolean sameIdentifier(String left, String right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.length() != right.length()) {
            return false;
        }

        for (int i = 0; i < left.length(); i++) {
            if (asciiLower(left.charAt(i)) != asciiLower(right.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static char asciiLower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + 'a' - 'A') : c;
    }

    private static boolean isTrigger(List<Token> tokens, int start) {
        int command = start;
        if (is(tokens, command, "EXPLAIN")) {
            command++;
            if (is(tokens, command, "QUERY") && is(tokens, command + 1, "PLAN")) {
                command += 2;
            }
        }
        if (!is(tokens, command, "CREATE")) {
            return false;
        }

        int kind = command + 1;
        if (is(tokens, kind, "TEMP") || is(tokens, kind, "TEMPORARY")) {
            kind++;
        }
        return is(tokens, kind, "TRIGGER");
    }

    private static boolean changesRows(List<Token> tokens, int start, int end) {
        Token first = tokens.get(start);
        if (!first.is("WITH")) {
            return isWrite(first);
        }

        int depth = 0;
        for (int i = start + 1; i < end - 1; i++) {
            Token token = tokens.get(i);
            if (token.is("(")) {
                depth++;
            } else if (token.is(")") && --depth == 0) {
                Token next = tokens.get(i + 1);
                if (isWrite(next) || next.is("SELECT") || next.is("VALUES")) {
                    return isWrite(next);
                }
            }
        }
        return false;
    }

    private static boolean isWrite(Token token) {
        return token.is("INSERT") || token.is("UPDATE") || token.is("DELETE") || token.is("REPLACE");
    }

    private static boolean is(List<Token> tokens, int index, String text) {
        return index >= 0 && index < tokens.size() && tokens.get(index).is(text);
    }

    private List<Token> readTokens() {
        List<Token> tokens = new ArrayList<>();
        skipTrivia();
        while (offset < sql.length()) {
            tokens.add(readToken());
            skipTrivia();
        }
        return tokens;
    }

    private Token readToken() {
        int start = offset;
        char first = sql.charAt(offset++);
        if (first == '\'' || first == '"' || first == '`' || first == '[') {
            readQuoted(first);
            return new Token("", offset);
        }
        if (first == '$' || first == ':' || first == '@' || first == '#') {
            readNamedParameter();
            return new Token("", offset);
        }
        if (isIdentifierChar(first)) {
            while (offset < sql.length() && isIdentifierChar(sql.charAt(offset))) {
                offset++;
            }
        }
        return new Token(sql.substring(start, offset), offset);
    }

    private void skipTrivia() {
        while (offset < sql.length()) {
            char c = sql.charAt(offset);
            if (isSpace(c) || c == '\ufeff') {
                offset++;
            } else if (sql.startsWith("--", offset)) {
                int newline = sql.indexOf('\n', offset + 2);
                offset = newline < 0 ? sql.length() : newline;
            } else if (offset + 2 < sql.length() && sql.startsWith("/*", offset)) {
                int end = sql.indexOf("*/", offset + 2);
                offset = end < 0 ? sql.length() : end + 2;
            } else {
                return;
            }
        }
    }

    private void readQuoted(char first) {
        char end = first == '[' ? ']' : first;
        while (offset < sql.length()) {
            if (sql.charAt(offset++) == end) {
                if (first != '[' && offset < sql.length() && sql.charAt(offset) == end) {
                    offset++;
                } else {
                    return;
                }
            }
        }
        throw error(Code.SQL, "SQLite SQL contains an unclosed quoted value");
    }

    private void readNamedParameter() {
        boolean hasName = false;
        while (offset < sql.length()) {
            char c = sql.charAt(offset);
            if (isIdentifierChar(c)) {
                hasName = true;
                offset++;
            } else if (sql.startsWith("::", offset)) {
                offset += 2;
            } else if (hasName && c == '(') {
                offset++;
                while (offset < sql.length() && !isSpace(sql.charAt(offset))
                    && sql.charAt(offset) != ')') {
                    offset++;
                }
                if (offset == sql.length() || sql.charAt(offset) != ')') {
                    throw error(Code.SQL, "SQLite SQL contains an invalid parameter");
                }
                offset++;
                return;
            } else {
                return;
            }
        }
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    private static boolean isIdentifierChar(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
            || c == '_' || c == '$' || c >= 0x80;
    }

    private record Token(String text, int end) {
        boolean is(String expected) {
            return sameIdentifier(text, expected);
        }
    }
}
