package com.opencodebuddy.util;

import java.util.regex.Pattern;

/**
 * JavaScript utility class.
 * Provides helper methods for JavaScript string escaping and function invocation.
 */
public class JsUtils {

    private static final Pattern SAFE_JS_NAME = Pattern.compile("^[a-zA-Z_$][a-zA-Z0-9_$.]*$");

    /**
     * Escape a string for safe embedding in JavaScript code.
     * Handles special characters including line separators, paragraph separators, etc.
     */
    public static String escapeJs(String str) {
        if (str == null) {
            return "";
        }
        return str
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("`", "\\`")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")          // Tab
            .replace("\b", "\\b")          // Backspace
            .replace("\f", "\\f")          // Form feed
            .replace("\u0085", "\\u0085")  // Next Line (NEL)
            .replace("\u2028", "\\u2028")  // Line separator
            .replace("\u2029", "\\u2029")  // Paragraph separator
            .replace("\0", "\\0")          // Null character
            .replace("</", "<\\/");        // Prevent </script> breakout in HTML context
    }

    /**
     * Reverse {@link #escapeJs}: recover the string a JS literal would produce.
     * Only used when a value escaped for a JS literal is sent as plain data instead.
     */
    public static String unescapeJs(String str) {
        if (str == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c != '\\' || i + 1 >= str.length()) {
                out.append(c);
                continue;
            }
            char next = str.charAt(++i);
            switch (next) {
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case '0':
                    out.append('\0');
                    break;
                case 'u':
                    if (i + 4 < str.length()) {
                        try {
                            out.append((char) Integer.parseInt(str.substring(i + 1, i + 5), 16));
                            i += 4;
                            break;
                        } catch (NumberFormatException ignored) {
                            // Not a unicode escape; keep it verbatim.
                        }
                    }
                    out.append('\\').append(next);
                    break;
                default: out.append(next);
            }
        }
        return out.toString();
    }

    /**
     * Build a JavaScript function call with an existence check.
     * @param functionName the function name
     * @param args pre-escaped string arguments
     * @return the JavaScript code
     */
    public static String buildJsCall(String functionName, String... args) {
        if (functionName == null || !SAFE_JS_NAME.matcher(functionName).matches()) {
            throw new IllegalArgumentException("Invalid JavaScript function name: " + functionName);
        }
        StringBuilder js = new StringBuilder();
        js.append("if (typeof ").append(functionName).append(" === 'function') { ");
        js.append(functionName).append("(");

        for (int i = 0; i < args.length; i++) {
            if (i > 0) { js.append(", "); }
            js.append("'").append(args[i]).append("'");
        }

        js.append("); }");
        return js.toString();
    }

    /**
     * Build a safe JavaScript call with a truthiness check on the object path.
     * @param objectPath the object path (e.g. "window.myFunction")
     * @param args pre-escaped string arguments
     * @return the JavaScript code
     */
    public static String buildSafeJsCall(String objectPath, String... args) {
        if (objectPath == null || !SAFE_JS_NAME.matcher(objectPath).matches()) {
            throw new IllegalArgumentException("Invalid JavaScript object path: " + objectPath);
        }
        StringBuilder js = new StringBuilder();
        js.append("if (").append(objectPath).append(") { ");
        js.append(objectPath).append("(");

        for (int i = 0; i < args.length; i++) {
            if (i > 0) { js.append(", "); }
            js.append("'").append(args[i]).append("'");
        }

        js.append("); }");
        return js.toString();
    }
}
