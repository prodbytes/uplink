package org.uplink.web;

/** A minimal JSON writer: enough for the view state handed to app.js. */
final class Json {

    private final StringBuilder sb = new StringBuilder();
    /** Whether the innermost open object or array still needs no comma. */
    private boolean first = true;

    Json object() {
        separate();
        sb.append('{');
        first = true;
        return this;
    }

    Json end() {
        sb.append('}');
        first = false;
        return this;
    }

    Json array(String key) {
        key(key);
        sb.append('[');
        first = true;
        return this;
    }

    Json endArray() {
        sb.append(']');
        first = false;
        return this;
    }

    Json object(String key) {
        key(key);
        sb.append('{');
        first = true;
        return this;
    }

    Json put(String key, String value) {
        key(key);
        if (value == null) {
            sb.append("null");
        } else {
            quote(value);
        }
        first = false;
        return this;
    }

    Json put(String key, long value) {
        key(key);
        sb.append(value);
        first = false;
        return this;
    }

    Json put(String key, double value) {
        key(key);
        sb.append(Double.isFinite(value) ? Double.toString(value) : "0");
        first = false;
        return this;
    }

    Json put(String key, boolean value) {
        key(key);
        sb.append(value);
        first = false;
        return this;
    }

    Json putNull(String key) {
        key(key);
        sb.append("null");
        first = false;
        return this;
    }

    private void key(String key) {
        separate();
        quote(key);
        sb.append(':');
        // The value that follows is not preceded by a comma.
        first = true;
    }

    private void separate() {
        if (!first) {
            sb.append(',');
        }
    }

    private void quote(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}
