package com.hermescoagent.phone;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import rikka.shizuku.Shizuku;

/** Runs shell commands with Shizuku (adb/root) identity via a UserService.
 *  Requires the Shizuku app installed + started + permission granted. */
public final class ShizukuShell {

    private static final int REQUEST_CODE = 10001;

    public static boolean available() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    public static boolean granted() {
        try { return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable t) { return false; }
    }

    public static void requestPermission() {
        try { Shizuku.requestPermission(REQUEST_CODE); } catch (Throwable ignored) {}
    }

    /** Blocking exec (call on a background thread). Returns stdout+stderr, or
     *  "error: ..." on failure/timeout. */
    public static String exec(String command) {
        if (!available()) return "error: Shizuku not available (install + start the Shizuku app)";
        if (!granted()) return "error: Shizuku permission not granted";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>("error: timeout");

        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName("com.hermescoagent.phone", ShellService.class.getName()))
                .daemon(false)
                .processNameSuffix("shell")
                .version(1);

        try {
            Shizuku.bindUserService(args, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    try {
                        IShellService s = IShellService.Stub.asInterface(binder);
                        result.set(s.exec(command));
                    } catch (Throwable t) {
                        result.set("error: " + t);
                    }
                    latch.countDown();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    result.set("error: service disconnected");
                    latch.countDown();
                }
            });
            if (!latch.await(15, TimeUnit.SECONDS)) {
                return "error: timeout";
            }
        } catch (Throwable t) {
            return "error: " + t;
        }
        return result.get();
    }
}
