package com.hermescoagent.phone;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
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
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
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

    // GitHub-dark palette
    private static final int COLOR_BG          = 0xFF0D1117;
    private static final int COLOR_CARD        = 0xFF161B22;
    private static final int COLOR_BORDER      = 0xFF30363D;
    private static final int COLOR_TEXT        = 0xFFE6EDF3;
    private static final int COLOR_MUTED       = 0xFF8B949E;
    private static final int COLOR_ACCENT      = 0xFF58A6FF;
    private static final int COLOR_GREEN       = 0xFF3FB950;
    private static final int COLOR_GREEN_SOFT  = 0xFF7EE787;
    private static final int COLOR_AMBER       = 0xFFD29922;
    private static final int COLOR_AMBER_SOFT  = 0xFFF0B72F;
    private static final int COLOR_RED         = 0xFFF85149;
    private static final int COLOR_HINT        = 0xFF6E7681;

    private enum BtnStyle { PRIMARY, SECONDARY, DANGER, GHOST }

    // ─── Command reference data (human-friendly) ────────────────────────
    private static final class Cmd {
        final String name, desc, action;
        Cmd(String name, String desc, String action) { this.name = name; this.desc = desc; this.action = action; }
    }
    private static final class CmdGroup {
        final String title;
        final Cmd[] commands;
        CmdGroup(String title, Cmd[] commands) { this.title = title; this.commands = commands; }
    }
    private static final CmdGroup[] COMMAND_GROUPS = {
            new CmdGroup("Screen control", new Cmd[]{
                    new Cmd("Tap the screen", "Tap at an (x, y) coordinate", "tap"),
                    new Cmd("Swipe", "Drag between two points", "swipe"),
                    new Cmd("Type text", "Type into the focused field", "type"),
                    new Cmd("Press a key", "Back, home, recents, power", "key"),
                    new Cmd("Open an app", "Launch by package name", "launch"),
                    new Cmd("Scroll", "Scroll the focused list", "scroll"),
                    new Cmd("Wait", "Wait for text to appear", "wait"),
                    new Cmd("Wake screen", "Turn the screen on", "wake"),
            }),
            new CmdGroup("See the screen", new Cmd[]{
                    new Cmd("Read the screen", "Dump the UI tree as text", "dump"),
                    new Cmd("Find", "Search the UI for text", "find"),
                    new Cmd("Find & tap", "Find a UI element, tap it", "find_tap"),
                    new Cmd("Screenshot", "Capture the screen as an image", "screenshot"),
                    new Cmd("Live view", "Stream frames to the relay", "watch"),
                    new Cmd("Screen size", "Width, height, density", "screen_size"),
            }),
            new CmdGroup("Device", new Cmd[]{
                    new Cmd("Battery", "Level and charging state", "battery"),
                    new Cmd("Device info", "Model, Android version", "info"),
                    new Cmd("Installed apps", "List launchable apps", "list_apps"),
                    new Cmd("Location", "GPS position", "location"),
                    new Cmd("Wi-Fi", "SSID and signal", "wifi"),
                    new Cmd("Charging", "Charging state", "charging"),
                    new Cmd("Read clipboard", "Copy the clipboard", "clipboard_get"),
                    new Cmd("Write clipboard", "Set clipboard text", "clipboard_set"),
            }),
            new CmdGroup("Find my phone", new Cmd[]{
                    new Cmd("Ring", "Loud ring, bypasses silent", "ring"),
                    new Cmd("Stop ringing", "Stop the ring", "stop_ring"),
                    new Cmd("Find phone", "Location + ring + battery", "find_phone"),
                    new Cmd("Flashlight", "Toggle the light", "flashlight"),
                    new Cmd("Speak", "Text-to-speech out loud", "speak"),
                    new Cmd("Vibrate", "Pulse the vibration motor", "vibrate"),
            }),
            new CmdGroup("Privacy & notifications", new Cmd[]{
                    new Cmd("Notifications", "List active notifications", "notifications"),
                    new Cmd("Dismiss", "Clear a notification", "dismiss_notification"),
                    new Cmd("Privacy mode", "Block screen / dump reads", "privacy"),
                    new Cmd("Command log", "Recent command history", "log"),
            }),
            new CmdGroup("Anti-theft", new Cmd[]{
                    new Cmd("Stolen response", "Screenshot + photos + GPS + lock", "stolen"),
                    new Cmd("Take a photo", "Silent front / rear capture", "photo"),
                    new Cmd("Record audio", "Capture ambient sound", "mic"),
                    new Cmd("Lock screen", "Lock the phone", "lock"),
                    new Cmd("Location tracking", "GPS breadcrumbs on a timer", "tracking"),
                    new Cmd("Location history", "Logged breadcrumbs", "location_history"),
                    new Cmd("SIM identity", "Current SIM info", "sim"),
                    new Cmd("SIM events", "Detect a SIM swap", "sim_events"),
            }),
            new CmdGroup("Files & apps", new Cmd[]{
                    new Cmd("List files", "List a folder's contents", "file_list"),
                    new Cmd("File info", "Size, type, timestamps", "file_info"),
                    new Cmd("Read a file", "Download a file's contents", "file_get"),
                    new Cmd("Write a file", "Upload and save a file", "file_put"),
                    new Cmd("Delete a file", "Delete a file or empty folder", "file_delete"),
                    new Cmd("Install an app", "Download + install an APK", "install_apk"),
                    new Cmd("Uninstall an app", "Remove a package", "uninstall"),
                    new Cmd("App details", "Version, target SDK, dates", "app_info"),
                    new Cmd("Kill background app", "Stop a background process", "kill_background"),
            }),
            new CmdGroup("Service", new Cmd[]{
                    new Cmd("Health check", "Is the server alive?", "ping"),
                    new Cmd("Stop service", "Turn off remote control", "shutdown"),
            }),
    };

    private TextView statusView;      // "● Running — port 8765" / "○ Stopped"
    private TextView statusIpView;
    private TextView statusAccView;
    private TextView tokenView;
    private TextView deviceIdView;
    private TextView remoteStatusView;
    private EditText relayUrlField;
    private Switch remoteToggle;
    private TextView batteryStatusView;
    private Button batteryExemptBtn;
    private Button grantAccessibility;
    private Button startServer;
    private Button grantPermsButton;
    private TextView permsSummaryView;
    private TextView permsHeaderStatusView;

    private final List<PermSpec> permSpecs = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If the previous process was killed mid-ring, DND + audio volumes may
        // be stuck in the "ringing" state. Fix that as soon as the user opens
        // the app (in addition to the service-startup cleanup).
        CommandExecutor.restoreCrashedRingState(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundResource(R.drawable.bg_scroll_gradient);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        root.setClipChildren(false);
        root.setClipToPadding(false);

        // ─── Title ────────────────────────────────────────────────────────
        TextView title = new TextView(this);
        title.setText("Hermes CoAgent");
        title.setTextSize(24);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Lets your Hermes assistant control this phone like a computer.");
        sub.setTextSize(13);
        sub.setTextColor(COLOR_MUTED);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dp(4), 0, dp(20));
        sub.setLayoutParams(subLp);
        root.addView(sub);

        // ─── Master status card ───────────────────────────────────────────
        LinearLayout statusCard = newCard(dp(16));

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setTypeface(null, Typeface.BOLD);
        statusCard.addView(statusView);

        statusIpView = new TextView(this);
        statusIpView.setTextSize(12);
        statusIpView.setTextColor(COLOR_MUTED);
        LinearLayout.LayoutParams ipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ipLp.setMargins(0, dp(6), 0, 0);
        statusIpView.setLayoutParams(ipLp);
        statusCard.addView(statusIpView);

        statusAccView = new TextView(this);
        statusAccView.setTextSize(12);
        LinearLayout.LayoutParams accLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        accLp.setMargins(0, dp(2), 0, 0);
        statusAccView.setLayoutParams(accLp);
        statusCard.addView(statusAccView);

        root.addView(statusCard);

        // ─── Primary actions (Enable Accessibility / Start Remote) ────────
        grantAccessibility = new Button(this);
        grantAccessibility.setText("1. Enable Accessibility (required)");
        grantAccessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        styleButton(grantAccessibility, BtnStyle.PRIMARY);
        root.addView(grantAccessibility);

        startServer = new Button(this);
        startServer.setText("2. Start Remote Control");
        startServer.setOnClickListener(v -> {
            if (RemoteControlService.isRunning) {
                stopRemoteControl();
            } else {
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
            }
            updateStatus();
        });
        styleButton(startServer, BtnStyle.PRIMARY);
        root.addView(startServer);

        // ─── Settings (collapsed) ──────────────────────────────────────────
        LinearLayout settingsContent = addCollapsibleSection(root, "Settings", false);

        Switch autoStartToggle = new Switch(this);
        autoStartToggle.setText("  Auto-start on boot");
        autoStartToggle.setTextColor(Color.WHITE);
        autoStartToggle.setChecked(BootReceiver.isAutoStartEnabled(this));
        LinearLayout.LayoutParams autoStartLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        autoStartLp.setMargins(0, dp(4), 0, 0);
        autoStartToggle.setLayoutParams(autoStartLp);
        settingsContent.addView(autoStartToggle);

        TextView autoStartHint = new TextView(this);
        autoStartHint.setText("Start remote control automatically after the phone reboots.");
        autoStartHint.setTextSize(11);
        autoStartHint.setTextColor(COLOR_MUTED);
        autoStartHint.setPadding(0, 0, 0, dp(8));
        settingsContent.addView(autoStartHint);

        autoStartToggle.setOnCheckedChangeListener((btn, checked) ->
                BootReceiver.setAutoStartEnabled(this, checked));

        TextView batteryLabel = new TextView(this);
        batteryLabel.setText("Battery optimization");
        batteryLabel.setTextSize(13);
        batteryLabel.setTextColor(COLOR_TEXT);
        batteryLabel.setTypeface(null, Typeface.BOLD);
        batteryLabel.setPadding(0, dp(8), 0, dp(2));
        settingsContent.addView(batteryLabel);

        batteryStatusView = new TextView(this);
        batteryStatusView.setTextSize(12);
        batteryStatusView.setTextColor(COLOR_MUTED);
        settingsContent.addView(batteryStatusView);

        batteryExemptBtn = new Button(this);
        batteryExemptBtn.setText("Allow background activity");
        batteryExemptBtn.setOnClickListener(v -> requestBatteryExemption());
        styleButton(batteryExemptBtn, BtnStyle.SECONDARY);
        LinearLayout.LayoutParams battBtnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        battBtnLp.setMargins(0, dp(8), 0, dp(4));
        batteryExemptBtn.setLayoutParams(battBtnLp);
        settingsContent.addView(batteryExemptBtn);

        TextView batteryNote = new TextView(this);
        batteryNote.setText("Android may kill this app in the background to save battery. "
                + "Allowing background activity keeps Remote Mode and auto-start reliable.");
        batteryNote.setTextSize(11);
        batteryNote.setTextColor(COLOR_MUTED);
        batteryNote.setPadding(0, 0, 0, dp(8));
        settingsContent.addView(batteryNote);

        // ─── Connection details (collapsed) ───────────────────────────────
        LinearLayout connDetails = addCollapsibleSection(root, "Connection details", false);

        TextView tokenLabel = new TextView(this);
        tokenLabel.setText("Auth token (send as X-Hermes-Token header):");
        tokenLabel.setTextSize(12);
        tokenLabel.setTextColor(COLOR_MUTED);
        tokenLabel.setPadding(0, 0, 0, dp(4));
        connDetails.addView(tokenLabel);

        tokenView = new TextView(this);
        tokenView.setTextSize(12);
        tokenView.setTextColor(COLOR_GREEN_SOFT);
        tokenView.setTypeface(Typeface.MONOSPACE);
        tokenView.setPadding(dp(12), dp(10), dp(12), dp(10));
        tokenView.setBackgroundResource(R.drawable.bg_code_block);
        tokenView.setGravity(Gravity.START);
        tokenView.setTextIsSelectable(true);
        connDetails.addView(tokenView);

        Button copyToken = new Button(this);
        copyToken.setText("Copy token");
        copyToken.setOnClickListener(v -> {
            String t = RemoteControlService.ensureToken(MainActivity.this);
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Hermes token", t));
                Toast.makeText(MainActivity.this, "Token copied", Toast.LENGTH_SHORT).show();
            }
        });
        styleButton(copyToken, BtnStyle.SECONDARY);
        connDetails.addView(copyToken);

        TextView deviceIdLabel = new TextView(this);
        deviceIdLabel.setText("Relay device_id (required by relay /command):");
        deviceIdLabel.setTextSize(12);
        deviceIdLabel.setTextColor(COLOR_MUTED);
        deviceIdLabel.setPadding(0, dp(16), 0, dp(4));
        connDetails.addView(deviceIdLabel);

        deviceIdView = new TextView(this);
        deviceIdView.setTextSize(12);
        deviceIdView.setTextColor(COLOR_GREEN_SOFT);
        deviceIdView.setTypeface(Typeface.MONOSPACE);
        deviceIdView.setPadding(dp(12), dp(10), dp(12), dp(10));
        deviceIdView.setBackgroundResource(R.drawable.bg_code_block);
        deviceIdView.setGravity(Gravity.START);
        deviceIdView.setTextIsSelectable(true);
        deviceIdView.setText(RemoteRelayClient.ensureDeviceId(this));
        connDetails.addView(deviceIdView);

        Button copyDeviceId = new Button(this);
        copyDeviceId.setText("Copy device_id");
        copyDeviceId.setOnClickListener(v -> {
            String id = RemoteRelayClient.ensureDeviceId(MainActivity.this);
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Hermes device_id", id));
                Toast.makeText(MainActivity.this, "device_id copied", Toast.LENGTH_SHORT).show();
            }
        });
        styleButton(copyDeviceId, BtnStyle.SECONDARY);
        connDetails.addView(copyDeviceId);

        // ─── Remote Mode ──────────────────────────────────────────────────
        addSectionHeader(root, "Remote Mode");

        TextView remoteHelp = new TextView(this);
        remoteHelp.setText("Dial out to a relay server so the controller can reach this phone " +
                "even when carrier NAT blocks incoming connections. Uses the same auth token.");
        remoteHelp.setTextSize(12);
        remoteHelp.setTextColor(COLOR_MUTED);
        LinearLayout.LayoutParams remoteHelpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        remoteHelpLp.setMargins(0, 0, 0, dp(8));
        remoteHelp.setLayoutParams(remoteHelpLp);
        root.addView(remoteHelp);

        // How to set up Remote Mode (collapsed step-by-step guide)
        LinearLayout rmGuide = addCollapsibleSection(root, "How to set up Remote Mode", false);
        for (String s : new String[]{
                "1. Run the relay server on a machine with internet access:\n     python3 relay.py 8787",
                "2. Expose it publicly (e.g. ngrok http 8787) to get a public URL.",
                "3. Paste that URL into the \"Relay base URL\" field below.",
                "4. Turn on \"Enable Remote Mode\" — the phone dials out and waits for commands.",
                "5. Control the phone via the relay using the device_id + token under \"Connection details\"."}) {
            TextView st = new TextView(this);
            st.setText(s);
            st.setTextSize(12);
            st.setTextColor(COLOR_MUTED);
            st.setPadding(0, dp(4), 0, dp(4));
            rmGuide.addView(st);
        }

        TextView relayLabel = new TextView(this);
        relayLabel.setText("Relay base URL (e.g. https://your-relay.example.com):");
        relayLabel.setTextSize(12);
        relayLabel.setTextColor(COLOR_MUTED);
        relayLabel.setPadding(0, dp(4), 0, dp(4));
        root.addView(relayLabel);

        relayUrlField = new EditText(this);
        relayUrlField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        relayUrlField.setSingleLine(true);
        relayUrlField.setTextSize(13);
        relayUrlField.setEllipsize(android.text.TextUtils.TruncateAt.END);
        relayUrlField.setTextColor(COLOR_TEXT);
        relayUrlField.setHintTextColor(COLOR_HINT);
        relayUrlField.setHint("https://…");
        relayUrlField.setBackgroundResource(R.drawable.bg_edit_text);
        relayUrlField.setPadding(dp(12), dp(10), dp(12), dp(10));
        relayUrlField.setText(RemoteRelayClient.getRelayUrl(this));
        root.addView(relayUrlField);

        remoteToggle = new Switch(this);
        remoteToggle.setText("  Enable Remote Mode");
        remoteToggle.setTextColor(Color.WHITE);
        remoteToggle.setChecked(RemoteRelayClient.isEnabled(this));
        LinearLayout.LayoutParams remoteToggleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        remoteToggleLp.setMargins(0, dp(12), 0, dp(4));
        remoteToggle.setLayoutParams(remoteToggleLp);
        root.addView(remoteToggle);

        remoteStatusView = new TextView(this);
        remoteStatusView.setTextSize(12);
        remoteStatusView.setTypeface(Typeface.MONOSPACE);
        remoteStatusView.setTextColor(COLOR_MUTED);
        remoteStatusView.setPadding(0, 0, 0, dp(8));
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

        // ─── Permissions ──────────────────────────────────────────────────
        initPermissionsSection(root);

        // ─── Privacy toggle ───────────────────────────────────────────────
        Switch privacyToggle = new Switch(this);
        privacyToggle.setText("  Privacy mode (block screenshot/dump/snapshot/notifications)");
        privacyToggle.setTextColor(Color.WHITE);
        privacyToggle.setChecked(Redaction.isPrivacyOn(this));
        LinearLayout.LayoutParams privacyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        privacyLp.setMargins(0, dp(20), 0, dp(4));
        privacyToggle.setLayoutParams(privacyLp);
        privacyToggle.setOnCheckedChangeListener((btn, checked) -> {
            Redaction.setPrivacyOn(this, checked);
            Toast.makeText(this, checked ? "Privacy mode ON" : "Privacy mode OFF",
                    Toast.LENGTH_SHORT).show();
        });
        root.addView(privacyToggle);

        // ─── Check for updates ────────────────────────────────────────────
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
        styleButton(checkUpdates, BtnStyle.SECONDARY);
        root.addView(checkUpdates);

        // ─── Command reference (collapsed) ────────────────────────────────
        LinearLayout cmdRef = addCollapsibleSection(root, "Command reference", false);

        TextView note = new TextView(this);
        note.setText("What the assistant can do on this phone. Each row shows the command name on the right.");
        note.setTextSize(12);
        note.setTextColor(COLOR_MUTED);
        note.setPadding(0, 0, 0, dp(4));
        cmdRef.addView(note);

        for (CmdGroup g : COMMAND_GROUPS) {
            addCommandGroupHeader(cmdRef, g.title);
            for (Cmd c : g.commands) {
                addCommandRow(cmdRef, c.name, c.desc, c.action);
            }
        }

        scroll.addView(root);
        setContentView(scroll);
        updateStatus();

        UpdateChecker.checkForUpdate(this, (available, code, name1, apkUrl) -> {
            if (available) showUpdateDialog(code, name1, apkUrl);
        });
    }

    // ─────────────────────────── UI helpers ─────────────────────────────

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout newCard(int bottomMargin) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, bottomMargin);
        card.setLayoutParams(lp);
        applyGlassElevation(card, 20, 6);
        return card;
    }

    // Rounded outline + soft elevation so drop-shadows follow the card's corners.
    private void applyGlassElevation(View v, int cornerDp, int elevationDp) {
        final int r = dp(cornerDp);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setElevation(dp(elevationDp));
    }

    private TextView addSectionHeader(LinearLayout parent, String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextSize(16);
        h.setTextColor(COLOR_TEXT);
        h.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(24), 0, dp(8));
        h.setLayoutParams(lp);
        parent.addView(h);
        return h;
    }

    private LinearLayout addCollapsibleSection(LinearLayout parent, String title, boolean initiallyExpanded) {
        LinearLayout sectionCard = new LinearLayout(this);
        sectionCard.setOrientation(LinearLayout.VERTICAL);
        sectionCard.setBackgroundResource(R.drawable.bg_card);
        sectionCard.setClipChildren(false);
        sectionCard.setClipToPadding(false);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sectionLp.setMargins(0, dp(12), 0, 0);
        sectionCard.setLayoutParams(sectionLp);
        applyGlassElevation(sectionCard, 20, 6);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(12), dp(14), dp(12));
        header.setClickable(true);
        header.setFocusable(true);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTextSize(14);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(titleView);

        TextView chevron = new TextView(this);
        chevron.setTextColor(COLOR_MUTED);
        chevron.setTextSize(14);
        chevron.setText(initiallyExpanded ? "▾" : "▸");
        header.addView(chevron);

        sectionCard.addView(header);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), 0, dp(14), dp(14));
        content.setVisibility(initiallyExpanded ? View.VISIBLE : View.GONE);
        sectionCard.addView(content);

        header.setOnClickListener(v -> {
            boolean expanded = content.getVisibility() == View.VISIBLE;
            TransitionManager.beginDelayedTransition(sectionCard,
                    new AutoTransition().setDuration(180));
            content.setVisibility(expanded ? View.GONE : View.VISIBLE);
            chevron.setText(expanded ? "▸" : "▾");
        });

        parent.addView(sectionCard);
        return content;
    }

    private void addCommandGroupHeader(LinearLayout parent, String title) {
        TextView h = new TextView(this);
        h.setText(title.toUpperCase());
        h.setTextSize(11);
        h.setTextColor(COLOR_AMBER_SOFT);
        h.setTypeface(null, Typeface.BOLD);
        h.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(12), 0, dp(2));
        h.setLayoutParams(lp);
        parent.addView(h);
    }

    private void addCommandRow(LinearLayout parent, String name, String desc, String action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        parent.addView(row);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(left);

        TextView nameV = new TextView(this);
        nameV.setText(name);
        nameV.setTextSize(13);
        nameV.setTextColor(COLOR_TEXT);
        nameV.setTypeface(null, Typeface.BOLD);
        left.addView(nameV);

        TextView descV = new TextView(this);
        descV.setText(desc);
        descV.setTextSize(11);
        descV.setTextColor(COLOR_MUTED);
        left.addView(descV);

        TextView actionV = new TextView(this);
        actionV.setText(action);
        actionV.setTextSize(12);
        actionV.setTextColor(COLOR_ACCENT);
        actionV.setTypeface(Typeface.MONOSPACE);
        actionV.setPadding(dp(10), dp(3), dp(10), dp(3));
        actionV.setBackgroundResource(R.drawable.bg_code_block);
        row.addView(actionV);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void styleButton(Button b, BtnStyle style) {
        int bg;
        int textColor;
        int corner;
        int elevation;
        switch (style) {
            case PRIMARY:
                bg = R.drawable.bg_button_primary;
                textColor = Color.WHITE;
                corner = 14;
                elevation = 8;
                break;
            case DANGER:
                bg = R.drawable.bg_button_danger;
                textColor = Color.WHITE;
                corner = 14;
                elevation = 6;
                break;
            case GHOST:
                bg = R.drawable.bg_button_ghost;
                textColor = COLOR_MUTED;
                corner = 12;
                elevation = 0;
                break;
            case SECONDARY:
            default:
                bg = R.drawable.bg_button_secondary;
                textColor = COLOR_TEXT;
                corner = 14;
                elevation = 3;
                break;
        }
        b.setBackgroundResource(bg);
        b.setTextColor(textColor);
        b.setAllCaps(false);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextSize(14);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setStateListAnimator(null);
        if (elevation > 0) {
            applyGlassElevation(b, corner, elevation);
        } else {
            b.setElevation(0);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, 0);
        b.setLayoutParams(lp);

        // Subtle press-scale so the buttons feel "physical".
        b.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(120).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                    break;
            }
            return false;
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
                statusText.setTextColor(COLOR_MUTED);
                if (openSettings != null) openSettings.setVisibility(View.GONE);
                return;
            }
            boolean granted = PermissionPrefs.isGranted(MainActivity.this, key);
            boolean wants = PermissionPrefs.wants(MainActivity.this, key);
            checkbox.setEnabled(true);
            checkbox.setChecked(wants);
            if (granted) {
                statusText.setText("Granted");
                statusText.setTextColor(COLOR_GREEN_SOFT);
            } else if (wants) {
                statusText.setText(isRuntime() && !isComplex()
                        ? "Not granted — tap Grant permissions"
                        : "Not granted — tap Open Settings");
                statusText.setTextColor(COLOR_AMBER_SOFT);
            } else {
                statusText.setText("Off (feature disabled)");
                statusText.setTextColor(COLOR_MUTED);
            }
            if (openSettings != null) openSettings.setVisibility(View.VISIBLE);
        }
    }

    private void initPermissionsSection(LinearLayout root) {
        // Collapsible "Permissions" card whose header shows a live one-line status.
        final LinearLayout sectionCard = new LinearLayout(this);
        sectionCard.setOrientation(LinearLayout.VERTICAL);
        sectionCard.setBackgroundResource(R.drawable.bg_card);
        sectionCard.setClipChildren(false);
        sectionCard.setClipToPadding(false);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sectionLp.setMargins(0, dp(24), 0, 0);
        sectionCard.setLayoutParams(sectionLp);
        applyGlassElevation(sectionCard, 20, 6);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(14), dp(14), dp(14));
        header.setClickable(true);
        header.setFocusable(true);

        LinearLayout headerText = new LinearLayout(this);
        headerText.setOrientation(LinearLayout.VERTICAL);
        headerText.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(this);
        titleView.setText("Permissions");
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTextSize(16);
        titleView.setTypeface(null, Typeface.BOLD);
        headerText.addView(titleView);

        permsHeaderStatusView = new TextView(this);
        permsHeaderStatusView.setTextSize(12);
        permsHeaderStatusView.setTextColor(COLOR_MUTED);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, dp(2), 0, 0);
        permsHeaderStatusView.setLayoutParams(statusLp);
        headerText.addView(permsHeaderStatusView);

        header.addView(headerText);

        final TextView chevron = new TextView(this);
        chevron.setTextColor(COLOR_MUTED);
        chevron.setTextSize(14);
        chevron.setText("▸");
        header.addView(chevron);

        sectionCard.addView(header);

        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), 0, dp(14), dp(14));
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setVisibility(View.GONE);
        sectionCard.addView(content);

        header.setOnClickListener(v -> {
            boolean expanded = content.getVisibility() == View.VISIBLE;
            TransitionManager.beginDelayedTransition(sectionCard,
                    new AutoTransition().setDuration(180));
            content.setVisibility(expanded ? View.GONE : View.VISIBLE);
            chevron.setText(expanded ? "▸" : "▾");
        });

        root.addView(sectionCard);

        TextView subtitle = new TextView(this);
        subtitle.setText("Check the features you want. Tap ? for the reason. "
                + "Permissions Android grants through Settings show an Open Settings button.");
        subtitle.setTextSize(12);
        subtitle.setTextColor(COLOR_MUTED);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dp(4), 0, dp(10));
        subtitle.setLayoutParams(subLp);
        content.addView(subtitle);

        permsSummaryView = new TextView(this);
        permsSummaryView.setTextSize(13);
        permsSummaryView.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        summaryLp.setMargins(0, 0, 0, dp(4));
        permsSummaryView.setLayoutParams(summaryLp);
        content.addView(permsSummaryView);

        grantPermsButton = new Button(this);
        grantPermsButton.setText("Grant permissions");
        grantPermsButton.setOnClickListener(v -> onGrantAllClicked());
        styleButton(grantPermsButton, BtnStyle.PRIMARY);
        content.addView(grantPermsButton);

        addPermRow(content, new PermSpec(
                PermissionPrefs.LOC,
                "Location (GPS)",
                "To get the phone's position for the `location` command and the `stolen` anti-theft response.",
                Build.VERSION_CODES.M,
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                },
                null, null));

        addPermRow(content, new PermSpec(
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

        addPermRow(content, new PermSpec(
                PermissionPrefs.CAM,
                "Camera",
                "To silently take front/back photos via `photo` and `stolen` so you can see who has the phone.",
                Build.VERSION_CODES.M,
                new String[]{Manifest.permission.CAMERA},
                null, null));

        addPermRow(content, new PermSpec(
                PermissionPrefs.MIC,
                "Microphone",
                "To record ambient audio via `mic` so you can hear what's happening around the phone.",
                Build.VERSION_CODES.M,
                new String[]{Manifest.permission.RECORD_AUDIO},
                null, null));

        addPermRow(content, new PermSpec(
                PermissionPrefs.PHONE,
                "Phone state / SIM",
                "To read the SIM identity via `sim`/`sim_events` and detect if a thief swaps the SIM card.",
                Build.VERSION_CODES.M,
                new String[]{Manifest.permission.READ_PHONE_STATE},
                null, null));

        addPermRow(content, new PermSpec(
                PermissionPrefs.POST_NOTIF,
                "Notifications",
                "Required by Android to show the persistent 'remote control running' status-bar notification while the foreground service is active.",
                Build.VERSION_CODES.TIRAMISU,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                null, null));

        addPermRow(content, new PermSpec(
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

        addPermRow(content, new PermSpec(
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

        addPermRow(content, new PermSpec(
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

        addPermRow(content, new PermSpec(
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
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, dp(10), 0, 0);
        card.setLayoutParams(cardLp);
        card.setBackgroundResource(R.drawable.bg_card);
        applyGlassElevation(card, 20, 4);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        spec.checkbox = new CheckBox(this);
        spec.checkbox.setText(spec.label);
        spec.checkbox.setTextColor(Color.WHITE);
        spec.checkbox.setTextSize(14);
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
        whyBtn.setBackgroundResource(R.drawable.bg_button_ghost);
        whyBtn.setTextColor(COLOR_MUTED);
        whyBtn.setTypeface(null, Typeface.BOLD);
        whyBtn.setTextSize(14);
        whyBtn.setAllCaps(false);
        whyBtn.setMinWidth(0);
        whyBtn.setMinimumWidth(0);
        whyBtn.setPadding(dp(14), dp(4), dp(14), dp(4));
        whyBtn.setStateListAnimator(null);
        whyBtn.setElevation(0);
        LinearLayout.LayoutParams whyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        whyLp.setMargins(dp(8), 0, 0, 0);
        whyBtn.setLayoutParams(whyLp);
        whyBtn.setOnClickListener(v -> showWhyDialog(spec));
        headerRow.addView(whyBtn);

        card.addView(headerRow);

        spec.statusText = new TextView(this);
        spec.statusText.setTextSize(12);
        spec.statusText.setPadding(0, dp(4), 0, dp(4));
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
            steps.setPadding(0, dp(4), 0, dp(4));
            card.addView(steps);
        }

        if (spec.settingsIntent != null) {
            spec.openSettings = new Button(this);
            spec.openSettings.setText("Open Settings");
            spec.openSettings.setOnClickListener(v -> openSettingsFor(spec));
            styleButton(spec.openSettings, BtnStyle.SECONDARY);
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
                permsSummaryView.setTextColor(COLOR_MUTED);
            } else if (missingLabels.isEmpty()) {
                permsSummaryView.setText("✅ All permissions granted");
                permsSummaryView.setTextColor(COLOR_GREEN);
            } else {
                int n = missingLabels.size();
                StringBuilder sb = new StringBuilder("⚠️ ");
                sb.append(n).append(n == 1 ? " permission not enabled: " : " permissions not enabled: ");
                for (int i = 0; i < missingLabels.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(missingLabels.get(i));
                }
                permsSummaryView.setText(sb.toString());
                permsSummaryView.setTextColor(COLOR_AMBER);
            }
        }
        if (permsHeaderStatusView != null) {
            if (applicable == 0) {
                permsHeaderStatusView.setText("Off");
                permsHeaderStatusView.setTextColor(COLOR_MUTED);
            } else if (missingLabels.isEmpty()) {
                permsHeaderStatusView.setText("✅ All permissions set");
                permsHeaderStatusView.setTextColor(COLOR_GREEN);
            } else {
                int n = missingLabels.size();
                permsHeaderStatusView.setText("⚠️ " + n + " to review");
                permsHeaderStatusView.setTextColor(COLOR_AMBER);
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
                color = COLOR_GREEN_SOFT;
                break;
            case CONNECTING:
                text = "remote: connecting" + (detail == null || detail.isEmpty() ? "" : " → " + detail);
                color = COLOR_AMBER_SOFT;
                break;
            case ERROR:
                text = "remote: error" + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")");
                color = COLOR_RED;
                break;
            case OFF:
            default:
                text = "remote: off";
                color = COLOR_MUTED;
                break;
        }
        remoteStatusView.setText(text);
        remoteStatusView.setTextColor(color);
    }

    /** Stop the remote-control service from the main-screen toggle. */
    private void stopRemoteControl() {
        try {
            RemoteRelayClient.setEnabled(this, false);
            try { RemoteRelayClient.get(this).stop(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        try {
            stopService(new Intent(this, RemoteControlService.class));
        } catch (Throwable ignored) {}
        RemoteControlService.isRunning = false; // immediate UI feedback (onDestroy is async)
        Toast.makeText(this, "Remote control stopped", Toast.LENGTH_SHORT).show();
    }

    private void updateStatus() {
        String ip = getLocalIp();
        boolean acc = HermesAccessibilityService.instance != null;
        boolean running = RemoteControlService.isRunning;

        if (statusView != null) {
            if (running) {
                statusView.setText("● Running — port " + RemoteControlService.PORT);
                statusView.setTextColor(COLOR_GREEN);
            } else {
                statusView.setText("○ Stopped");
                statusView.setTextColor(COLOR_MUTED);
            }
        }
        if (statusIpView != null) {
            statusIpView.setText("IP: " + ip);
        }
        if (statusAccView != null) {
            if (acc) {
                statusAccView.setText("Accessibility: Enabled");
                statusAccView.setTextColor(COLOR_GREEN_SOFT);
            } else {
                statusAccView.setText("Accessibility: Not enabled");
                statusAccView.setTextColor(COLOR_MUTED);
            }
        }

        if (tokenView != null) {
            tokenView.setText(RemoteControlService.ensureToken(this));
        }
        if (grantAccessibility != null) {
            grantAccessibility.setVisibility(acc ? View.GONE : View.VISIBLE);
        }
        if (startServer != null) {
            startServer.setText(running
                    ? "■ Stop Remote Control"
                    : "2. Start Remote Control");
            startServer.setEnabled(true);
            styleButton(startServer, running ? BtnStyle.DANGER : BtnStyle.PRIMARY);
        }
        refreshPermissions();
        renderBatteryStatus();
    }

    /** True if the OS will not restrict this app's background activity. */
    private boolean isBatteryExempt() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    /** Ask the user to exempt this app from battery optimization (Doze). */
    private void requestBatteryExemption() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "Could not open battery settings", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void renderBatteryStatus() {
        if (batteryStatusView == null) return;
        boolean exempt = isBatteryExempt();
        batteryStatusView.setText(exempt ? "Background activity: Allowed" : "Background activity: Restricted");
        batteryStatusView.setTextColor(exempt ? COLOR_GREEN_SOFT : COLOR_AMBER_SOFT);
        if (batteryExemptBtn != null) {
            batteryExemptBtn.setVisibility(exempt ? View.GONE : View.VISIBLE);
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
