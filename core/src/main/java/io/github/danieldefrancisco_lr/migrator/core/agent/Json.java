package io.github.danieldefrancisco_lr.migrator.core.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader and string escaper for the AI provider clients. The core has no dependencies (it is embedded in
 * the IDE plugins), and the providers' responses only need objects, arrays, strings, numbers, booleans and null.
 * Values are Map, List, String, Double, Boolean or null.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String json) {
        Json p = new Json(json);
        p.skip();
        Object value = p.value();
        p.skip();
        if (p.i != p.s.length()) {
            throw new IllegalArgumentException("Unexpected content at " + p.i + " in JSON");
        }
        return value;
    }

    /** The value at a path of object keys and array indexes, e.g. path(json, "choices", 0, "message", "content"); null if absent. */
    public static Object path(Object value, Object... keys) {
        Object current = value;
        for (Object key : keys) {
            if (key instanceof Integer && current instanceof List) {
                List<?> list = (List<?>) current;
                int index = (Integer) key;
                current = index < list.size() ? list.get(index) : null;
            } else if (key instanceof String && current instanceof Map) {
                current = ((Map<?, ?>) current).get(key);
            } else {
                return null;
            }
        }
        return current;
    }

    /** A JSON string literal, with quotes. */
    public static String quote(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    private Object value() {
        if (i >= s.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        i++;
        skip();
        if (peek() == '}') {
            i++;
            return map;
        }
        while (true) {
            skip();
            String key = string();
            skip();
            expect(":");
            skip();
            map.put(key, value());
            skip();
            if (peek() == ',') {
                i++;
            } else {
                expect("}");
                return map;
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        i++;
        skip();
        if (peek() == ']') {
            i++;
            return list;
        }
        while (true) {
            skip();
            list.add(value());
            skip();
            if (peek() == ',') {
                i++;
            } else {
                expect("]");
                return list;
            }
        }
    }

    private String string() {
        expect("\"");
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw new IllegalArgumentException("Unterminated JSON string");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: sb.append(e);
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (start == i) {
            throw new IllegalArgumentException("Unexpected character '" + s.charAt(i) + "' at " + i + " in JSON");
        }
        return Double.parseDouble(s.substring(start, i));
    }

    private void expect(String token) {
        if (!s.startsWith(token, i)) {
            throw new IllegalArgumentException("Expected '" + token + "' at " + i + " in JSON");
        }
        i += token.length();
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private void skip() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }
}
