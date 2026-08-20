package com.hermescoagent.phone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private TextView statusView;
    private TextView tokenView;
    private TextView deviceIdView;
    private TextView remoteStatusView;
    private EditText relayUrlField;
    private Switch remoteToggle;
    private Button grantAccessibility;
    private Button startServer;
    private Button grantPerms;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If the previous process was killed mid-ring, DND + audio volumes may
        // be stuck in the "ringing" state. Fix that as soon as the user opens
        // the app (in addition to the service-startup cleanup).
        CommandExecutor.restoreCrashedRingState(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0D1117"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 72, 48, 48);

        TextView title = new TextView(this);
        title.setText("Hermes CoAgent");
        title.setTextSize(26);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Lets your Hermes assistant control this phone like a computer.");
        sub.setTextSize(14);
        sub.setTextColor(Color.parseColor("#8B949E"));
        sub.setPadding(0, 8, 0, 24);
        root.addView(sub);

        statusView = new TextView(this);
        statusView.setTextSize(15);
        statusView.setTextColor(Color.parseColor("#58A6FF"));
        statusView.setTypeface(Typeface.MONOSPACE);
        statusView.setPadding(0, 0, 0, 16);
        root.addView(statusView);

        TextView tokenLabel = new TextView(this);
        tokenLabel.setText("Auth token (send as X-Hermes-Token header):");
        tokenLabel.setTextSize(12);
        tokenLabel.setTextColor(Color.parseColor("#8B949E"));
        tokenLabel.setPadding(0, 8, 0, 4);
        root.addView(tokenLabel);

        tokenView = new TextView(this);
        tokenView.setTextSize(12);
        tokenView.setTextColor(Color.parseColor("#7EE787"));
        tokenView.setTypeface(Typeface.MONOSPACE);
        tokenView.setPadding(16, 12, 16, 12);
        tokenView.setBackgroundColor(Color.parseColor("#161B22"));
        tokenView.setGravity(Gravity.START);
        tokenView.setTextIsSelectable(true);
        root.addView(tokenView);

        Button copyToken = new Button(this);
        copyToken.setText("Copy token to clipboard");
        copyToken.setOnClickListener(v -> {
            String t = RemoteControlService.ensureToken(MainActivity.this);
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Hermes token", t));
                Toast.makeText(MainActivity.this, "Token copied", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(copyToken);

        TextView deviceIdLabel = new TextView(this);
        deviceIdLabel.setText("Relay device_id (required by relay /command):");
        deviceIdLabel.setTextSize(12);
        deviceIdLabel.setTextColor(Color.parseColor("#8B949E"));
        deviceIdLabel.setPadding(0, 16, 0, 4);
        root.addView(deviceIdLabel);

        deviceIdView = new TextView(this);
        deviceIdView.setTextSize(12);
        deviceIdView.setTextColor(Color.parseColor("#7EE787"));
        deviceIdView.setTypeface(Typeface.MONOSPACE);
        deviceIdView.setPadding(16, 12, 16, 12);
        deviceIdView.setBackgroundColor(Color.parseColor("#161B22"));
        deviceIdView.setGravity(Gravity.START);
        deviceIdView.setTextIsSelectable(true);
        deviceIdView.setText(RemoteRelayClient.ensureDeviceId(this));
        root.addView(deviceIdView);

        Button copyDeviceId = new Button(this);
        copyDeviceId.setText("Copy device_id to clipboard");
        copyDeviceId.setOnClickListener(v -> {
            String id = RemoteRelayClient.ensureDeviceId(MainActivity.this);
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Hermes device_id", id));
                Toast.makeText(MainActivity.this, "device_id copied", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(copyDeviceId);

        grantAccessibility = new Button(this);
        grantAccessibility.setText("1. Enable Accessibility (required)");
        grantAccessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(grantAccessibility);

        startServer = new Button(this);
        startServer.setText("2. Start Remote Control");
        startServer.setOnClickListener(v -> {
            Intent i = new Intent(this, RemoteControlService.class);
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(i);
                } else {
                    startService(i);
                }
                RemoteControlService.isRunning = true; // immediate UI feedback (startForegroundService is async)
                Toast.makeText(this, "Remote control started on port " + RemoteControlService.PORT,
                        Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Failed to start: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            updateStatus();
        });
        root.addView(startServer);

        // ─── Remote Mode section ─────────────────────────────────────────
        TextView remoteHeader = new TextView(this);
        remoteHeader.setText("Remote Mode (works over cellular)");
        remoteHeader.setTextSize(16);
        remoteHeader.setTextColor(Color.WHITE);
        remoteHeader.setTypeface(null, Typeface.BOLD);
        remoteHeader.setPadding(0, 32, 0, 8);
        root.addView(remoteHeader);

        TextView remoteHelp = new TextView(this);
        remoteHelp.setText("Dial out to a relay server so the controller can reach this phone " +
                "even when carrier NAT blocks incoming connections. Uses the same auth token.");
        remoteHelp.setTextSize(12);
        remoteHelp.setTextColor(Color.parseColor("#8B949E"));
        remoteHelp.setPadding(0, 0, 0, 8);
        root.addView(remoteHelp);

        TextView relayLabel = new TextView(this);
        relayLabel.setText("Relay base URL (e.g. https://your-relay.example.com):");
        relayLabel.setTextSize(12);
        relayLabel.setTextColor(Color.parseColor("#8B949E"));
        relayLabel.setPadding(0, 8, 0, 4);
        root.addView(relayLabel);

        relayUrlField = new EditText(this);
        relayUrlField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        relayUrlField.setSingleLine(true);
        relayUrlField.setTextSize(12);
        relayUrlField.setEllipsize(android.text.TextUtils.TruncateAt.END);
        relayUrlField.setTextColor(Color.WHITE);
        relayUrlField.setHintTextColor(Color.parseColor("#6E7681"));
        relayUrlField.setHint("https://…");
        relayUrlField.setText(RemoteRelayClient.getRelayUrl(this));
        root.addView(relayUrlField);

        remoteToggle = new Switch(this);
        remoteToggle.setText("  Enable Remote Mode");
        remoteToggle.setTextColor(Color.WHITE);
        remoteToggle.setChecked(RemoteRelayClient.isEnabled(this));
        remoteToggle.setPadding(0, 16, 0, 8);
        root.addView(remoteToggle);

        remoteStatusView = new TextView(this);
        remoteStatusView.setTextSize(13);
        remoteStatusView.setTypeface(Typeface.MONOSPACE);
        remoteStatusView.setTextColor(Color.parseColor("#8B949E"));
        remoteStatusView.setPadding(0, 0, 0, 8);
        root.addView(remoteStatusView);

        remoteToggle.setOnCheckedChangeListener((btn, checked) -> {
            String url = relayUrlField.getText().toString().trim();
            RemoteRelayClient.setRelayUrl(this, url);
            RemoteRelayClient.setEnabled(this, checked);
            RemoteRelayClient client = RemoteRelayClient.get(this);
            if (checked) {
                if (url.isEmpty()) {
                    Toast.makeText(this, "Enter a relay URL first", Toast.LENGTH_SHORT).show();
                    remoteToggle.setChecked(false);
                    RemoteRelayClient.setEnabled(this, false);
                    return;
                }
                client.setStatusListener(this::onRemoteStatusChanged);
                client.start();
            } else {
                client.stop();
            }
        });

        // If Remote Mode was previously enabled, auto-resume.
        if (RemoteRelayClient.isEnabled(this) && !RemoteRelayClient.getRelayUrl(this).isEmpty()) {
            RemoteRelayClient client = RemoteRelayClient.get(this);
            client.setStatusListener(this::onRemoteStatusChanged);
            client.start();
        } else {
            renderRemoteStatus(RemoteRelayClient.Status.OFF, "");
        }

        grantPerms = new Button(this);
        grantPerms.setText("Grant permissions (location / DND / camera / battery)");
        grantPerms.setOnClickListener(v -> showPermissionsDialog());
        root.addView(grantPerms);

        Button checkUpdates = new Button(this);
        checkUpdates.setText("Check for updates");
        checkUpdates.setOnClickListener(v -> {
            Toast.makeText(MainActivity.this, "Checking for updates…", Toast.LENGTH_SHORT).show();
            UpdateChecker.checkForUpdate(MainActivity.this, true, (available, code, name1, apkUrl) -> {
                if (available) {
                    showUpdateDialog(code, name1, apkUrl);
                } else {
                    Toast.makeText(MainActivity.this, "You're on the latest version.", Toast.LENGTH_SHORT).show();
                }
            });
        });
        root.addView(checkUpdates);

        TextView note = new TextView(this);
        note.setText("Commands are sent to http://<this-ip>:8765/cmd as JSON with the\n" +
                "X-Hermes-Token header. Examples:\n" +
                "{\"action\":\"tap\",\"x\":100,\"y\":200}\n" +
                "{\"action\":\"swipe\",\"x1\":100,\"y1\":500,\"x2\":100,\"y2\":200}\n" +
                "{\"action\":\"type\",\"text\":\"hello\"}\n" +
                "{\"action\":\"key\",\"code\":\"back\"}\n" +
                "{\"action\":\"launch\",\"package\":\"com.android.settings\"}\n" +
                "{\"action\":\"screen_size\"}  /  {\"action\":\"list_apps\"}\n" +
                "{\"action\":\"battery\"}  /  {\"action\":\"info\"}  /  {\"action\":\"ping\"}\n" +
                "Find-my-phone: {\"action\":\"ring\"}, {\"action\":\"stop_ring\"},\n" +
                "  {\"action\":\"location\"}, {\"action\":\"flashlight\",\"on\":true},\n" +
                "  {\"action\":\"speak\",\"text\":\"hi\"}, {\"action\":\"vibrate\"},\n" +
                "  {\"action\":\"wifi\"}, {\"action\":\"charging\"}, {\"action\":\"find_phone\"}\n" +
                "Agent-control: {\"action\":\"wait\",\"for\":\"OK\",\"until\":\"appear\",\"timeout_ms\":5000},\n" +
                "  {\"action\":\"scroll\",\"direction\":\"down\"},\n" +
                "  {\"action\":\"clipboard_get\"}, {\"action\":\"clipboard_set\",\"text\":\"…\"},\n" +
                "  {\"action\":\"snapshot\"}, {\"action\":\"wake\"}, {\"action\":\"log\"}");
        note.setTextSize(12);
        note.setTextColor(Color.parseColor("#8B949E"));
        note.setPadding(0, 20, 0, 0);
        root.addView(note);

        scroll.addView(root);
        setContentView(scroll);
        updateStatus();

        requestRuntimePermissionsOnFirstUse();

        UpdateChecker.checkForUpdate(this, (available, code, name1, apkUrl) -> {
            if (available) showUpdateDialog(code, name1, apkUrl);
        });
    }

    private static final int REQ_RUNTIME_PERMS = 4711;

    private void requestRuntimePermissionsOnFirstUse() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_RUNTIME_PERMS);
        }
    }

    private boolean allPermissionsGranted() {
        boolean loc = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean cam = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean notif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        boolean dnd = nm != null && nm.isNotificationPolicyAccessGranted();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean batt = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()));
        return loc && cam && notif && dnd && batt;
    }

    private void showPermissionsDialog() {
        boolean loc = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean cam = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean notif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean dnd = false;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) dnd = nm.isNotificationPolicyAccessGranted();
        boolean battOpt = false;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            battOpt = pm.isIgnoringBatteryOptimizations(getPackageName());
        }

        String msg = "• Location (GPS): " + mark(loc) + "\n" +
                "• Camera (flashlight): " + mark(cam) + "\n" +
                "• Notifications (Android 13+): " + mark(notif) + "\n" +
                "• Notification Policy (DND bypass for ring): " + mark(dnd) + "\n" +
                "• Battery optimization exempt (keep alive): " + mark(battOpt);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Permissions")
                .setMessage(msg)
                .setNegativeButton("Close", (d, w) -> d.dismiss());
        if (!loc || !cam || !notif) {
            b.setPositiveButton("Request runtime", (d, w) -> requestRuntimePermissionsOnFirstUse());
        }
        if (!dnd) {
            b.setNeutralButton("DND access", (d, w) -> {
                try {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(this, "Cannot open DND settings", Toast.LENGTH_SHORT).show();
                }
            });
        } else if (!battOpt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            b.setNeutralButton("Battery exempt", (d, w) -> requestBatteryExempt());
        }
        b.show();
    }

    private void requestBatteryExempt() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open battery settings", Toast.LENGTH_SHORT).show();
        }
    }

    private static String mark(boolean ok) { return ok ? "granted" : "NOT granted"; }

    private void showUpdateDialog(int latestCode, String releaseName, String apkUrl) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Update Available")
                .setMessage("Version " + releaseName + " is available.")
                .setPositiveButton("Update Now", (d, w) -> {
                    UpdateChecker.downloadAndInstall(MainActivity.this, apkUrl);
                    d.dismiss();
                })
                .setNeutralButton("Skip This Version", (d, w) -> {
                    UpdateChecker.skipVersion(MainActivity.this, latestCode);
                    d.dismiss();
                })
                .setNegativeButton("Later", (d, w) -> d.dismiss())
                .show();
    }

    private void onRemoteStatusChanged(RemoteRelayClient.Status s, String detail) {
        runOnUiThread(() -> renderRemoteStatus(s, detail));
    }

    private void renderRemoteStatus(RemoteRelayClient.Status s, String detail) {
        if (remoteStatusView == null) return;
        String text;
        int color;
        switch (s) {
            case CONNECTED:
                text = "remote: connected" + (detail == null || detail.isEmpty() ? "" : " → " + detail);
                color = Color.parseColor("#7EE787");
                break;
            case CONNECTING:
                text = "remote: connecting" + (detail == null || detail.isEmpty() ? "" : " → " + detail);
                color = Color.parseColor("#F0B72F");
                break;
            case ERROR:
                text = "remote: error" + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")");
                color = Color.parseColor("#F85149");
                break;
            case OFF:
            default:
                text = "remote: off";
                color = Color.parseColor("#8B949E");
                break;
        }
        remoteStatusView.setText(text);
        remoteStatusView.setTextColor(color);
    }

    private void updateStatus() {
        String ip = getLocalIp();
        boolean acc = HermesAccessibilityService.instance != null;
        statusView.setText("IP: " + ip + "\nPort: " + RemoteControlService.PORT +
                "\nAccessibility: " + (acc ? "ENABLED" : "NOT ENABLED"));
        tokenView.setText(RemoteControlService.ensureToken(this));
        if (grantAccessibility != null) {
            grantAccessibility.setVisibility(acc ? View.GONE : View.VISIBLE);
        }
        if (startServer != null) {
            boolean running = RemoteControlService.isRunning;
            startServer.setText(running
                    ? "Running: remote control on :" + RemoteControlService.PORT
                    : "2. Start Remote Control");
            startServer.setEnabled(!running);
        }
        if (grantPerms != null) {
            grantPerms.setVisibility(allPermissionsGranted() ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * Try WifiManager first (still fine when we own ACCESS_WIFI_STATE), then fall
     * back to walking the active network's link addresses — that works on newer
     * Android where WifiInfo can be 0.0.0.0.
     */
    private String getLocalIp() {
        String ip = ipFromWifiManager();
        if (ip != null) return ip;
        ip = ipFromConnectivity();
        return ip == null ? "unknown" : ip;
    }

    private String ipFromWifiManager() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null) return null;
            int raw = wm.getConnectionInfo().getIpAddress();
            if (raw == 0) return null;
            return String.format("%d.%d.%d.%d",
                    (raw & 0xff), (raw >> 8 & 0xff), (raw >> 16 & 0xff), (raw >> 24 & 0xff));
        } catch (Exception e) {
            return null;
        }
    }

    private String ipFromConnectivity() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            Network active = cm.getActiveNetwork();
            if (active == null) return null;
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null;
            LinkProperties lp = cm.getLinkProperties(active);
            if (lp == null) return null;
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress addr = la.getAddress();
                if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                    return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null) updateStatus();
    }
}
