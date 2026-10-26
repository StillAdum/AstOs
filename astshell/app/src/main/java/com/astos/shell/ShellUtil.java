package com.astos.shell;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Shell/prop helpers used by the plugin `system` grant. */
public final class ShellUtil {

    private ShellUtil() {}

    /** Run a command, return "exit N\n<output>" — one bounded string. */
    public static String run(android.content.Context c, String cmd, int timeoutMs) {
        if (cmd == null || cmd.trim().isEmpty()) return "empty command";
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            final Process fp = p;
            Thread killer = new Thread(new Runnable() {
                @Override public void run() {
                    try { Thread.sleep(timeoutMs); } catch (Exception ignored) {}
                    try { fp.destroy(); } catch (Exception ignored) {}
                }
            });
            killer.setDaemon(true);
            killer.start();
            BufferedReader rd = new BufferedReader(new InputStreamReader(fp.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            int max = 32 * 1024;
            while ((line = rd.readLine()) != null && sb.length() < max) sb.append(line).append('\n');
            rd.close();
            int code = fp.waitFor();
            return "exit " + code + "\n" + sb.toString().trim();
        } catch (Exception e) {
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
            return "error: " + e;
        }
    }
}
