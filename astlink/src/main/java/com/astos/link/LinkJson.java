package com.astos.link;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LinkJson — minimal JSON reader/writer shared by both AstOS apps and
 * mirrored by the desktop companion. Dependency-free on purpose: the same
 * source compiles into AstShell, AstSetup, and the Node protocol codec
 * stays byte-compatible with it.
 */
public final class LinkJson {

    public static final Object NULL = new Object() {
        @Override public String toString() { return "null"; }
    };

    public static final class Obj {
        public final Map<String, Object> map = new LinkedHashMap<String, Object>();

        public Obj put(String k, Object v) { map.put(k, v); return this; }
        public boolean has(String k)       { return map.containsKey(k); }
        public Object get(String k)        { return map.get(k); }
        public Obj remove(String k)        { map.remove(k); return this; }

        public String str(String k, String dflt) {
            Object v = map.get(k);
            return v instanceof String ? (String) v : dflt;
        }
        public int num(String k, int dflt) {
            Object v = map.get(k);
            if (v instanceof Number) return ((Number) v).intValue();
            if (v instanceof String) { try { return Integer.parseInt((String) v); } catch (Exception ignored) {} }
            return dflt;
        }
        public long lng(String k, long dflt) {
            Object v = map.get(k);
            if (v instanceof Number) return ((Number) v).longValue();
            if (v instanceof String) { try { return Long.parseLong((String) v); } catch (Exception ignored) {} }
            return dflt;
        }
        public double dbl(String k, double dflt) {
            Object v = map.get(k);
            if (v instanceof Number) return ((Number) v).doubleValue();
            if (v instanceof String) { try { return Double.parseDouble((String) v); } catch (Exception ignored) {} }
            return dflt;
        }
        public boolean bool(String k, boolean dflt) {
            Object v = map.get(k);
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof String) return "1".equals(v) || Boolean.parseBoolean((String) v);
            return dflt;
        }
        public Obj obj(String k) { Object v = map.get(k); return v instanceof Obj ? (Obj) v : null; }
        public Arr arr(String k) { Object v = map.get(k); return v instanceof Arr ? (Arr) v : null; }
        @Override public String toString() { return write(this); }
    }

    public static final class Arr {
        public final List<Object> list = new ArrayList<Object>();
        public Arr add(Object v) { list.add(v); return this; }
        public int size()        { return list.size(); }
        public Object at(int i)  { return list.get(i); }
        public String str(int i, String d) { Object v = at(i); return v instanceof String ? (String) v : d; }
        @Override public String toString() { return write(this); }
    }

    public static Obj obj() { return new Obj(); }
    public static Arr arr() { return new Arr(); }

    // ------------------------------------------------------------- writer

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, v);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null || v == NULL) { sb.append("null"); return; }
        if (v instanceof String)    { writeString(sb, (String) v); return; }
        if (v instanceof Boolean)   { sb.append(v.toString()); return; }
        if (v instanceof Obj) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Obj) v).map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof Arr) {
            sb.append('[');
            boolean first = true;
            for (Object o : ((Arr) v).list) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, o);
            }
            sb.append(']');
            return;
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else if (d == Math.floor(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
            return;
        }
        if (v instanceof Number) { sb.append(v.toString()); return; }
        writeString(sb, String.valueOf(v));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------- reader

    public static Object parse(String s) {
        P p = new P(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (!p.eof()) throw new IllegalArgumentException("trailing data at " + p.i);
        return v;
    }

    public static Obj parseObj(String s) {
        Object v = parse(s);
        if (!(v instanceof Obj)) throw new IllegalArgumentException("not an object");
        return (Obj) v;
    }

    private static final class P {
        final String s;
        int i;
        P(String s) { this.s = s; }
        boolean eof() { return i >= s.length(); }
        char c() { return s.charAt(i); }
        void ws() { while (!eof()) { char ch = c(); if (ch==' '||ch=='\t'||ch=='\n'||ch=='\r') i++; else break; } }

        Object value() {
            if (eof()) throw new IllegalArgumentException("eof");
            char ch = c();
            switch (ch) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true");  return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null");  return NULL;
                default:  return number();
            }
        }

        Obj object() {
            Obj o = new Obj();
            i++; ws();
            if (!eof() && c() == '}') { i++; return o; }
            while (true) {
                ws();
                if (eof() || c() != '"') throw new IllegalArgumentException("expected key at " + i);
                String k = string();
                ws();
                if (eof() || c() != ':') throw new IllegalArgumentException("expected ':' at " + i);
                i++; ws();
                o.put(k, value());
                ws();
                if (eof()) throw new IllegalArgumentException("eof in object");
                if (c() == ',') { i++; continue; }
                if (c() == '}') { i++; return o; }
                throw new IllegalArgumentException("bad object at " + i);
            }
        }

        Arr array() {
            Arr a = new Arr();
            i++; ws();
            if (!eof() && c() == ']') { i++; return a; }
            while (true) {
                ws();
                a.add(value());
                ws();
                if (eof()) throw new IllegalArgumentException("eof in array");
                if (c() == ',') { i++; continue; }
                if (c() == ']') { i++; return a; }
                throw new IllegalArgumentException("bad array at " + i);
            }
        }

        String string() {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw new IllegalArgumentException("eof in string");
                char ch = s.charAt(i++);
                if (ch == '"') return sb.toString();
                if (ch == '\\') {
                    if (eof()) throw new IllegalArgumentException("eof in escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw new IllegalArgumentException("bad \\u");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw new IllegalArgumentException("bad escape \\" + e);
                    }
                } else {
                    sb.append(ch);
                }
            }
        }

        Object number() {
            int start = i;
            if (!eof() && (c() == '-' || c() == '+')) i++;
            boolean fp = false;
            while (!eof()) {
                char ch = c();
                if (ch >= '0' && ch <= '9') i++;
                else if (ch=='.' || ch=='e' || ch=='E' || ch=='+' || ch=='-') { fp = fp || ch=='.' || ch=='e' || ch=='E'; i++; }
                else break;
            }
            String t = s.substring(start, i);
            if (t.isEmpty()) throw new IllegalArgumentException("bad number at " + start);
            try {
                if (fp) return Double.parseDouble(t);
                long l = Long.parseLong(t);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
                return l;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad number '" + t + "'");
            }
        }

        void expect(String word) {
            if (!s.startsWith(word, i)) throw new IllegalArgumentException("expected " + word);
            i += word.length();
        }
    }

    private LinkJson() {}
}
