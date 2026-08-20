package com.hermescoagent.phone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
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
    private Button grantPermsButton;
    private TextView permsSummaryView;

    private final List<PermSpec> permSpecs = new ArrayList<>();

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

        initPermissionsSection(root);

        Switch privacyToggle = new Switch(this);
        privacyToggle.setText("  Privacy mode (block screenshot/dump/snapshot/notifications)");
        privacyToggle.setTextColor(Color.WHITE);
        privacyToggle.setChecked(Redaction.isPrivacyOn(this));
        privacyToggle.setPadding(0, 24, 0, 8);
        privacyToggle.setOnCheckedChangeListener((btn, checked) -> {
            Redaction.setPrivacyOn(this, checked);
            Toast.makeText(this, checked ? "Privacy mode ON" : "Privacy mode OFF",
                    Toast.LENGTH_SHORT).show();
        });
        root.addView(privacyToggle);

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
                "  {\"action\":\"snapshot\"}, {\"action\":\"wake\"}, {\"action\":\"log\"}\n" +
                "Notifications: {\"action\":\"notifications\"},\n" +
                "  {\"action\":\"dismiss_notification\",\"key\":\"…\"} or {\"package\":\"…\"}\n" +
                "Privacy: {\"action\":\"privacy\",\"on\":true|false} — locks screenshot/dump/snapshot/notifications\n" +
                "Anti-theft: {\"action\":\"mic\",\"seconds\":10},\n" +
                "  {\"action\":\"tracking\",\"on\":true|false}, {\"action\":\"location_history\"},\n" +
                "  {\"action\":\"sim\"}, {\"action\":\"sim_events\"}");
        note.setTextSize(12);
        note.setTextColor(Color.parseColor("#8B949E"));
        note.setPadding(0, 20, 0, 0);
        root.addView(note);

        scroll.addView(root);
        setContentView(scroll);
        updateStatus();

        UpdateChecker.checkForUpdate(this, (available, code, name1, apkUrl) -> {
            if (available) showUpdateDialog(code, name1, apkUrl);
        });
    }

    // ─────────────────────────── permissions UI ─────────────────────────

    private static final int REQ_RUNTIME_PERMS = 4711;

    private interface IntentFactory { Intent create(); }

    private final class PermSpec {
        final String key;
        final String label;
        final String why;
        final int minSdk;
        final String[] runtimePerms;         // null → not a runtime perm
        final String[] howToSteps;           // null → no numbered steps shown
        final IntentFactory settingsIntent;  // null → no Open Settings button

        CheckBox checkbox;
        TextView statusText;
        Button openSettings;

        PermSpec(String key, String label, String why, int minSdk,
                 String[] runtimePerms, String[] howToSteps, IntentFactory settingsIntent) {
            this.key = key;
            this.label = label;
            this.why = why;
            this.minSdk = minSdk;
            this.runtimePerms = runtimePerms;
            this.howToSteps = howToSteps;
            this.settingsIntent = settingsIntent;
        }

        boolean applicable() { return Build.VERSION.SDK_INT >= minSdk; }
        boolean isRuntime()  { return runtimePerms != null; }
        boolean isComplex()  { return settingsIntent != null; }

        void refresh() {
            if (!applicable()) {
                checkbox.setChecked(false);
                checkbox.setEnabled(false);
                statusText.setText("Not needed on this Android version.");
                statusText.setTextColor(Color.parseColor("#8B949E"));
                if (openSettings != null) openSettings.setVisibility(View.GONE);
                return;
            }
            boolean granted = PermissionPrefs.isGranted(MainActivity.this, key);
            boolean wants = PermissionPrefs.wants(MainActivity.this, key);
            checkbox.setEnabled(true);
            checkbox.setChecked(wants);
            if (granted) {
                statusText.setText("Granted");
                statusText.setTextColor(Color.parseColor("#7EE787"));
            } else if (wants) {
                statusText.setText(isRuntime() && !isComplex()
                        ? "Not granted — tap Grant permissions"
                        : "Not granted — tap Open Settings");
                statusText.setTextColor(Color.parseColor("#F0B72F"));
            } else {
                statusText.setText("Off (feature disabled)");
                statusText.setTextColor(Color.parseColor("#8B949E"));
            }
            if (openSettings != null) openSettings.setVisibility(View.VISIBLE);
        }
    }

    private void initPermissionsSection(LinearLayout root) {
        TextView header = new TextView(this);
        header.setText("Permissions");
        header.setTextSize(16);
        header.setTextColor(Color.WHITE);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, 32, 0, 4);
        root.addView(header);

        TextView subtitle = new TextView(this);
        subtitle.setText("Check the features you want. Tap ? for the reason. "
                + "Permissions Android grants through Settings show an Open Settings button.");
        subtitle.setTextSize(12);
        subtitle.setTextColor(Color.parseColor("#8B949E"));
        subtitle.setPadding(0, 0, 0, 12);
        root.addView(subtitle);

        permsSummaryView = new TextView(this);
        permsSummaryView.setTextSize(13);
        permsSummaryView.setPadding(0, 0, 0, 12);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        permsSummaryView.setLayoutParams(summaryLp);
        root.addView(permsSummaryView);

        grantPermsButton = new Button(this);
        grantPermsButton.setText("Grant permissions");
        grantPermsButton.setOnClickListener(v -> onGrantAllClicked());
        root.addView(grantPermsButton);

        addPermRow(root, new PermSpec(
                PermissionPrefs.LOC,
                "Location (GPS)",
                "To get the phone's position for the `location` command and the `stolen` anti-theft response.",
                Build.VERSION_CODES.M,
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                },
                null, null));

        addPermRow(root, new PermSpec(
                PermissionPrefs.BG_LOC,
                "Background location",
                "To log a route (GPS breadcrumbs) for `tracking`/`location_history`, even while the app is in the background.",
                Build.VERSION_CODES.Q,
                null,
                new String[]{
                        "1. Tap Open Settings",
                        "2. Tap Permissions",
                        "3. Tap Location",
                        "4. Select 'Allow all the time'"
                },
                () -> {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    return i;
                }));

        addPermRow(root, new PermSpec(
                PermissionPrefs.CAM,
                "Camera",
                "To silently take front/back photos via `photo` and `stolen` so you can see who has the phone.",
                Build.VERSION_CODES.M,
                new String[]{Manifest.permission.CAMERA},
                null, null));

        addPermRow(root, new PermSpec(
                PermissionPrefs.MIC,
                "Microphone",
                "To record ambient audio via `mic` so you can hear what's happening around the phone.",
                Build.VERSION_CODES.M,
                new String[]{Manifest.permission.RECORD_AUDIO},
                null, null));

        addPermRow(root, new PermSpec(
                PermissionPrefs.PHONE,
                "Phone state / SIM",
                "To read the SIM identity via `sim`/`sim_events` and detect if a thief swaps the SIM card.",
                Build.VERSION_CODES.M,
                new String[]{Manifest.permission.READ_PHONE_STATE},
                null, null));

        addPermRow(root, new PermSpec(
                PermissionPrefs.POST_NOTIF,
                "Notifications",
                "Required by Android to show the persistent 'remote control running' status-bar notification while the foreground service is active.",
                Build.VERSION_CODES.TIRAMISU,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                null, null));

        addPermRow(root, new PermSpec(
                PermissionPrefs.DND,
                "DND access",
                "So the `ring` command can play sound even when the phone is on silent or Do Not Disturb.",
                Build.VERSION_CODES.BASE,
                null,
                new String[]{
                        "1. Tap Open Settings",
                        "2. Find Hermes CoAgent in the list",
                        "3. Toggle it ON"
                },
                () -> new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));

        addPermRow(root, new PermSpec(
                PermissionPrefs.NOTIF_LISTENER,
                "Notification access",
                "So you can read (`notifications`) and dismiss (`dismiss_notification`) the phone's notifications remotely.",
                Build.VERSION_CODES.BASE,
                null,
                new String[]{
                        "1. Tap Open Settings",
                        "2. Find Hermes CoAgent",
                        "3. Toggle it ON",
                        "4. Tap Allow when prompted"
                },
                () -> new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        addPermRow(root, new PermSpec(
                PermissionPrefs.BATT,
                "Battery exemption",
                "So Android doesn't kill the background service and cut off remote control.",
                Build.VERSION_CODES.M,
                null,
                new String[]{
                        "1. Tap Open Settings",
                        "2. Select 'Allow' (or 'Don't optimize')"
                },
                this::batteryExemptionIntent));

        addPermRow(root, new PermSpec(
                PermissionPrefs.OVERLAY,
                "Display over other apps",
                "To show a small 'being controlled' pill at the top of the screen while a command is running.",
                Build.VERSION_CODES.M,
                null,
                new String[]{
                        "1. Tap Open Settings",
                        "2. Find Hermes CoAgent in the list",
                        "3. Toggle 'Allow display over other apps' ON"
                },
                () -> {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    return i;
                }));
    }

    private void addPermRow(LinearLayout root, PermSpec spec) {
        permSpecs.add(spec);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(16, 16, 16, 16);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 12, 0, 0);
        card.setLayoutParams(cardLp);
        card.setBackgroundColor(Color.parseColor("#161B22"));

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        spec.checkbox = new CheckBox(this);
        spec.checkbox.setText(spec.label);
        spec.checkbox.setTextColor(Color.WHITE);
        spec.checkbox.setTextSize(15);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        spec.checkbox.setLayoutParams(cbLp);
        spec.checkbox.setOnCheckedChangeListener((btn, checked) -> {
            if (!spec.applicable()) return;
            PermissionPrefs.setWants(MainActivity.this, spec.key, checked);
            refreshPermissions();
        });
        headerRow.addView(spec.checkbox);

        Button whyBtn = new Button(this);
        whyBtn.setText("?");
        whyBtn.setMinWidth(0);
        whyBtn.setMinimumWidth(0);
        whyBtn.setPadding(24, 0, 24, 0);
        whyBtn.setOnClickListener(v -> showWhyDialog(spec));
        headerRow.addView(whyBtn);

        card.addView(headerRow);

        spec.statusText = new TextView(this);
        spec.statusText.setTextSize(12);
        spec.statusText.setPadding(0, 4, 0, 4);
        card.addView(spec.statusText);

        if (spec.howToSteps != null) {
            TextView steps = new TextView(this);
            StringBuilder sb = new StringBuilder("How to enable:\n");
            for (int i = 0; i < spec.howToSteps.length; i++) {
                sb.append(spec.howToSteps[i]);
                if (i < spec.howToSteps.length - 1) sb.append('\n');
            }
            steps.setText(sb.toString());
            steps.setTextSize(12);
            steps.setTextColor(Color.parseColor("#C9D1D9"));
            steps.setPadding(0, 4, 0, 4);
            card.addView(steps);
        }

        if (spec.settingsIntent != null) {
            spec.openSettings = new Button(this);
            spec.openSettings.setText("Open Settings");
            spec.openSettings.setOnClickListener(v -> openSettingsFor(spec));
            card.addView(spec.openSettings);
        }

        root.addView(card);
    }

    private void showWhyDialog(PermSpec spec) {
        boolean granted = PermissionPrefs.isGranted(this, spec.key);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(spec.label)
                .setMessage(spec.why)
                .setPositiveButton("OK", (d, w) -> d.dismiss());
        // Only runtime perms can be revoked via the app-details Settings page.
        if (granted && spec.isRuntime() && !spec.isComplex()) {
            b.setNeutralButton("Revoke in Settings", (d, w) -> {
                try {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(this, "Cannot open app settings", Toast.LENGTH_SHORT).show();
                }
            });
        }
        b.show();
    }

    private void openSettingsFor(PermSpec spec) {
        if (spec.settingsIntent == null) return;
        try {
            startActivity(spec.settingsIntent.create());
        } catch (Exception e) {
            // Battery-optimization fallback: some OEMs reject the per-package
            // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS intent — retry via
            // the generic list settings.
            if (PermissionPrefs.BATT.equals(spec.key)) {
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    return;
                } catch (Exception ignored) {}
            }
            Toast.makeText(this, "Cannot open settings for " + spec.label,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private Intent batteryExemptionIntent() {
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        i.setData(Uri.parse("package:" + getPackageName()));
        return i;
    }

    private void onGrantAllClicked() {
        // 1) Batch runtime requests for anything the user wants and hasn't granted.
        //    Background location is never bundled here: Android 11+ silently
        //    drops it when combined with fine location.
        List<String> runtimeToRequest = new ArrayList<>();
        for (PermSpec s : permSpecs) {
            if (!s.applicable()) continue;
            if (!PermissionPrefs.wants(this, s.key)) continue;
            if (PermissionPrefs.isGranted(this, s.key)) continue;
            if (!s.isRuntime() || s.isComplex()) continue;
            for (String p : s.runtimePerms) {
                if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    runtimeToRequest.add(p);
                }
            }
        }
        if (!runtimeToRequest.isEmpty()) {
            requestPermissions(runtimeToRequest.toArray(new String[0]), REQ_RUNTIME_PERMS);
            return;
        }

        // 2) Nothing runtime pending — open the first pending Settings deep-link.
        for (PermSpec s : permSpecs) {
            if (!s.applicable()) continue;
            if (!PermissionPrefs.wants(this, s.key)) continue;
            if (PermissionPrefs.isGranted(this, s.key)) continue;
            if (s.settingsIntent != null) {
                openSettingsFor(s);
                return;
            }
        }
        // 3) Everything checked is granted (shouldn't happen — button would be hidden).
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RUNTIME_PERMS) {
            refreshPermissions();
        }
    }

    private void refreshPermissions() {
        boolean anyPending = false;
        int applicable = 0;
        List<String> missingLabels = new ArrayList<>();
        for (PermSpec s : permSpecs) {
            s.refresh();
            if (!s.applicable()) continue;
            applicable++;
            if (!PermissionPrefs.wants(this, s.key)) continue;
            if (PermissionPrefs.isGranted(this, s.key)) continue;
            anyPending = true;
            missingLabels.add(s.label);
        }
        if (grantPermsButton != null) {
            grantPermsButton.setVisibility(anyPending ? View.VISIBLE : View.GONE);
        }
        if (permsSummaryView != null) {
            if (applicable == 0) {
                permsSummaryView.setText("No permissions required");
                permsSummaryView.setTextColor(Color.parseColor("#8B949E"));
            } else if (missingLabels.isEmpty()) {
                permsSummaryView.setText("✅ All permissions granted");
                permsSummaryView.setTextColor(Color.parseColor("#3FB950"));
            } else {
                int n = missingLabels.size();
                StringBuilder sb = new StringBuilder("⚠️ ");
                sb.append(n).append(n == 1 ? " permission not enabled: " : " permissions not enabled: ");
                for (int i = 0; i < missingLabels.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(missingLabels.get(i));
                }
                permsSummaryView.setText(sb.toString());
                permsSummaryView.setTextColor(Color.parseColor("#D29922"));
            }
        }
    }

    // ─────────────────────────── remote status ──────────────────────────

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
        refreshPermissions();
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
