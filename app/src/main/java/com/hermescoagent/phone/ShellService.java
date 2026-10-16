package com.hermescoagent.phone;

/** Runs inside the Shizuku user-service process with shell (adb) or root
 *  identity, so plain Runtime.exec works for `settings`, `pm`, `dumpsys`, etc.
 *  Context APIs largely do NOT work here — stick to process + filesystem. */
public class ShellService extends IShellService.Stub {
    @Override
    public String exec(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command == null ? "" : command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.InputStream is = p.getInputStream();
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return "error: " + t;
        }
    }
}
