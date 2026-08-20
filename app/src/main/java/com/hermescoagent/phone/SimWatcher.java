package com.hermescoagent.phone;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reads current SIM info and records SIM-state change events (serial +
 * operator + carrier) into a capped SharedPreferences list.
 */
public final class SimWatcher {

    private SimWatcher() {}

    private static final String PREFS = "hermes_sim";
    private static final String KEY_EVENTS = "events";
    private static final String KEY_LAST_SERIAL = "last_serial";
    private static final int MAX_EVENTS = 20;

    /** Manifest-registered receiver for android.intent.action.SIM_STATE_CHANGED. */
    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (!"android.intent.action.SIM_STATE_CHANGED".equals(action)) return;
            recordEvent(ctx.getApplicationContext(), intent);
        }
    }

    public static JSONObject currentInfo(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) {
                o.put("ok", false);
                o.put("error", "no telephony service");
                return o;
            }
            String serial = readSerial(ctx, tm);
            String simOp = safe(tm.getSimOperator());
            String simOpName = safe(tm.getSimOperatorName());
            String carrier = safe(tm.getNetworkOperatorName());
            String country = safe(tm.getSimCountryIso());
            int simState = tm.getSimState();
            o.put("ok", true);
            o.put("sim_state", simStateName(simState));
            o.put("sim_serial", serial);
            o.put("operator", simOp);
            o.put("operator_name", simOpName);
            o.put("carrier_name", carrier);
            o.put("country", country);
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", String.valueOf(e)); } catch (Exception ignored) {}
        }
        return o;
    }

    public static JSONArray events(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray raw = new JSONArray(sp.getString(KEY_EVENTS, "[]"));
            JSONArray out = new JSONArray();
            for (int i = raw.length() - 1; i >= 0; i--) out.put(raw.get(i));
            return out;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void recordEvent(Context ctx, Intent intent) {
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            String serial = tm == null ? "" : readSerial(ctx, tm);
            String simOp = tm == null ? "" : safe(tm.getSimOperator());
            String simOpName = tm == null ? "" : safe(tm.getSimOperatorName());
            String state = intent.getStringExtra("ss");
            if (state == null && tm != null) state = simStateName(tm.getSimState());

            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String lastSerial = sp.getString(KEY_LAST_SERIAL, null);
            boolean serialChanged = serial != null && !serial.isEmpty()
                    && lastSerial != null && !lastSerial.equals(serial);

            JSONObject e = new JSONObject();
            e.put("timestamp", System.currentTimeMillis());
            e.put("state", state == null ? "" : state);
            e.put("sim_serial", serial == null ? "" : serial);
            e.put("operator", simOp);
            e.put("operator_name", simOpName);
            if (serialChanged) e.put("serial_changed_from", lastSerial);

            JSONArray arr;
            try { arr = new JSONArray(sp.getString(KEY_EVENTS, "[]")); }
            catch (Exception ex) { arr = new JSONArray(); }
            arr.put(e);
            while (arr.length() > MAX_EVENTS) {
                JSONArray trimmed = new JSONArray();
                for (int i = arr.length() - MAX_EVENTS; i < arr.length(); i++) trimmed.put(arr.get(i));
                arr = trimmed;
            }

            SharedPreferences.Editor ed = sp.edit().putString(KEY_EVENTS, arr.toString());
            if (serial != null && !serial.isEmpty()) ed.putString(KEY_LAST_SERIAL, serial);
            ed.apply();
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings({"deprecation", "MissingPermission"})
    private static String readSerial(Context ctx, TelephonyManager tm) {
        // getSimSerialNumber requires READ_PHONE_STATE and returns null on Q+
        // without READ_PRIVILEGED_PHONE_STATE for most callers. We attempt it
        // guarded by the runtime permission check; return "" on failure.
        try {
            if (!PermissionPrefs.wants(ctx, PermissionPrefs.PHONE)) {
                return "";
            }
            if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                return "";
            }
            String s = tm.getSimSerialNumber();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String simStateName(int state) {
        switch (state) {
            case TelephonyManager.SIM_STATE_ABSENT: return "absent";
            case TelephonyManager.SIM_STATE_PIN_REQUIRED: return "pin_required";
            case TelephonyManager.SIM_STATE_PUK_REQUIRED: return "puk_required";
            case TelephonyManager.SIM_STATE_NETWORK_LOCKED: return "network_locked";
            case TelephonyManager.SIM_STATE_READY: return "ready";
            case TelephonyManager.SIM_STATE_NOT_READY: return "not_ready";
            case TelephonyManager.SIM_STATE_PERM_DISABLED: return "perm_disabled";
            case TelephonyManager.SIM_STATE_CARD_IO_ERROR: return "card_io_error";
            case TelephonyManager.SIM_STATE_CARD_RESTRICTED: return "card_restricted";
            case TelephonyManager.SIM_STATE_UNKNOWN: return "unknown";
            default: return "state_" + state;
        }
    }
}
