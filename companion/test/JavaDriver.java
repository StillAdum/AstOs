/**
 * JavaDriver.java — runs the REAL tablet-side JSON codec (LinkJson.java)
 * standalone and exchanges golden frames with the Node codec, proving both
 * sides of AstLink speak identical bytes.
 *
 * Build+run: see test/interop.sh
 */
import com.astos.link.LinkJson;

import java.io.*;

public class JavaDriver {

    static String writeFramesFile(String path) throws IOException {
        LinkJson.Obj hello = new LinkJson.Obj()
                .put("v", 1).put("id", "c1").put("cmd", "hello")
                .put("args", new LinkJson.Obj()
                        .put("token", "aabbcc")
                        .put("proto", 1));
        LinkJson.Obj reply = new LinkJson.Obj()
                .put("v", 1).put("id", "c2").put("ok", true)
                .put("result", new LinkJson.Obj()
                        .put("name", "AstTab")
                        .put("ver", "3.1")
                        .put("entities", new LinkJson.Arr()
                                .add("light.desk").add("switch.kettle").add(42))
                        .put("note", "quote\" back\\slash \n newline é")
                        .put("pct", 87));
        LinkJson.Obj err = new LinkJson.Obj()
                .put("v", 1).put("id", "c3").put("ok", false)
                .put("error", "need-code");
        String out = LinkJson.write(hello) + "\n" + LinkJson.write(reply) + "\n"
                + LinkJson.write(err) + "\n";
        Writer w = new OutputStreamWriter(new FileOutputStream(path), "UTF-8");
        w.write(out);
        w.close();
        return out;
    }

    static int verifyFramesFile(String path) throws Exception {
        BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(path), "UTF-8"));
        int n = 0;
        String line;
        while ((line = r.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            LinkJson.Obj o = LinkJson.parseObj(line);
            // deep-verify a known frame
            if (o.str("id", "").equals("j1")) {
                if (!o.str("cmd", "").equals("wifi.join")) throw new AssertionError("cmd lost");
                LinkJson.Obj args = o.obj("args");
                if (!args.str("ssid", "").equals("Café \"5G\""))
                    throw new AssertionError("ssid escape lost");
                if (!args.str("pass", "").equals("p@ss w0rd \\"))
                    throw new AssertionError("pass escape lost");
                if (args.lng("seq", -1) != 7) throw new AssertionError("num lost");
            }
            if (o.str("id", "").equals("j2") && o.has("b64")) {
                // round-trip the chunk payload
                LinkJson.Obj args = o.obj("args");
                byte[] expect = "chunk-payload-raw".getBytes("UTF-8");
                byte[] got = Base64Compat.decode(args.str("b64", ""));
                if (!java.util.Arrays.equals(expect, got))
                    throw new AssertionError("b64 mismatch");
            }
            n++;
        }
        r.close();
        return n;
    }

    /** Minimal base64 (avoids android.util.Base64 in a JVM harness). */
    static final class Base64Compat {
        private static final String T =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        static byte[] decode(String s) {
            s = s.replace("\n", "").replace("\r", "");
            StringBuilder bin = new StringBuilder();
            for (char c : s.toCharArray()) {
                if (c == '=') break;
                int v = T.indexOf(c);
                if (v < 0) throw new IllegalArgumentException("bad b64 " + c);
                for (int i = 5; i >= 0; i--) bin.append((v >> i & 1) == 1 ? '1' : '0');
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (int i = 0; i + 8 <= bin.length(); i += 8) {
                out.write(Integer.parseInt(bin.substring(i, i + 8), 2));
            }
            return out.toByteArray();
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        String path = args[1];
        if (mode.equals("write")) {
            writeFramesFile(path);
            System.out.println("WROTEOK");
        } else {
            int n = verifyFramesFile(path);
            System.out.println("VERIFIED " + n);
        }
    }
}
