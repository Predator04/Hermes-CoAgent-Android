package com.hermescoagent.phone;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;

public class MainActivity extends Activity {

    private TextView statusView;
    private TextView tokenView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        Button grantAccessibility = new Button(this);
        grantAccessibility.setText("1. Enable Accessibility (required)");
        grantAccessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(grantAccessibility);

        Button startServer = new Button(this);
        startServer.setText("2. Start Remote Control");
        startServer.setOnClickListener(v -> {
            Intent i = new Intent(this, RemoteControlService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            updateStatus();
        });
        root.addView(startServer);

        TextView note = new TextView(this);
        note.setText("Commands are sent to http://<this-ip>:8765/cmd as JSON with the\n" +
                "X-Hermes-Token header. Examples:\n" +
                "{\"action\":\"tap\",\"x\":100,\"y\":200}\n" +
                "{\"action\":\"swipe\",\"x1\":100,\"y1\":500,\"x2\":100,\"y2\":200}\n" +
                "{\"action\":\"type\",\"text\":\"hello\"}\n" +
                "{\"action\":\"key\",\"code\":\"back\"}\n" +
                "{\"action\":\"launch\",\"package\":\"com.android.settings\"}\n" +
                "{\"action\":\"screen_size\"}  /  {\"action\":\"list_apps\"}\n" +
                "{\"action\":\"battery\"}  /  {\"action\":\"info\"}  /  {\"action\":\"ping\"}");
        note.setTextSize(12);
        note.setTextColor(Color.parseColor("#8B949E"));
        note.setPadding(0, 20, 0, 0);
        root.addView(note);

        scroll.addView(root);
        setContentView(scroll);
        updateStatus();
    }

    private void updateStatus() {
        String ip = getLocalIp();
        boolean acc = HermesAccessibilityService.instance != null;
        statusView.setText("IP: " + ip + "\nPort: " + RemoteControlService.PORT +
                "\nAccessibility: " + (acc ? "ENABLED" : "NOT ENABLED"));
        tokenView.setText(RemoteControlService.ensureToken(this));
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
