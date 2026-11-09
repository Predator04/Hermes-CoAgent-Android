package com.hermescoagent.phone;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Runs inside the Shizuku user-service process with shell (adb) or root
 *  identity, so plain Runtime.exec works for `settings`, `pm`, `dumpsys`, etc.
 *  Context APIs largely do NOT work here — stick to process + filesystem. */
public class ShellService extends Service {

    private final IShellService.Stub binder = new IShellService.Stub() {
        @Override
        public String exec(String command) {
            Process p = null;
            try {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c", command == null ? "" : command);
                pb.redirectErrorStream(true);
                p = pb.start();
                StringBuilder sb = new StringBuilder();
                try (java.io.InputStream is = p.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
                }
                p.waitFor();
                return sb.toString();
            } catch (Throwable t) {
                return "error: " + t;
            } finally {
                if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
