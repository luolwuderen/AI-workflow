package com.acme.workflow.infra.execution.agent;

/**
 * 安全的算术表达式求值器：仅支持 + - * / %、括号、一元负号与小数。
 * 不使用任何脚本引擎，避免注入风险。除零与非法表达式抛出 {@link IllegalArgumentException}。
 */
final class ArithmeticExpression {

    private final String input;
    private int pos;

    private ArithmeticExpression(String input) {
        this.input = input;
    }

    static double evaluate(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("表达式不能为空。");
        ArithmeticExpression parser = new ArithmeticExpression(input);
        parser.skipWhitespace();
        double value = parser.parseExpression();
        parser.skipWhitespace();
        if (parser.pos < parser.input.length()) {
            throw new IllegalArgumentException("表达式存在无法解析的尾部字符：'" + parser.input.charAt(parser.pos) + "'。");
        }
        return value;
    }

    private double parseExpression() {
        double value = parseTerm();
        while (true) {
            skipWhitespace();
            if (peek() == '+') { pos++; value += parseTerm(); }
            else if (peek() == '-') { pos++; value -= parseTerm(); }
            else break;
        }
        return value;
    }

    private double parseTerm() {
        double value = parseFactor();
        while (true) {
            skipWhitespace();
            char c = peek();
            if (c == '*') { pos++; value *= parseFactor(); }
            else if (c == '/') {
                pos++;
                double divisor = parseFactor();
                if (divisor == 0.0) throw new IllegalArgumentException("除数不能为零。");
                value /= divisor;
            } else if (c == '%') {
                pos++;
                double divisor = parseFactor();
                if (divisor == 0.0) throw new IllegalArgumentException("取模的除数不能为零。");
                value %= divisor;
            } else break;
        }
        return value;
    }

    private double parseFactor() {
        skipWhitespace();
        char c = peek();
        if (c == '+') { pos++; return parseFactor(); }
        if (c == '-') { pos++; return -parseFactor(); }
        return parsePrimary();
    }

    private double parsePrimary() {
        skipWhitespace();
        char c = peek();
        if (c == '(') {
            pos++;
            double value = parseExpression();
            skipWhitespace();
            if (peek() != ')') throw new IllegalArgumentException("缺少右括号。");
            pos++;
            return value;
        }
        return parseNumber();
    }

    private double parseNumber() {
        skipWhitespace();
        int start = pos;
        while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
            pos++;
        }
        if (start == pos) {
            throw new IllegalArgumentException(pos < input.length()
                    ? "意外字符：'" + input.charAt(pos) + "'。"
                    : "表达式不完整，缺少数字。");
        }
        return Double.parseDouble(input.substring(start, pos));
    }

    private char peek() {
        return pos < input.length() ? input.charAt(pos) : '\0';
    }

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
    }
}
