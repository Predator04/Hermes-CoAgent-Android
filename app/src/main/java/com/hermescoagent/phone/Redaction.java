package com.hermescoagent.phone;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Privacy helpers: masks credentials/OTP/card/SSN in outgoing text, and
 * identifies "sensitive" apps whose screen contents must never leave the
 * device even in redacted form.
 */
public final class Redaction {

    private Redaction() {}

    public static final String MASK = "•••"; // •••

    // Order matters only weakly (all replace with MASK). Keep more specific
    // patterns earlier so bug-hunting a mis-match is easier.
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    // 13-19 digit runs, optionally separated by spaces or hyphens (credit cards).
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");
    // Any bare 10+ digit run.
    private static final Pattern LONG_DIGITS = Pattern.compile("\\b\\d{10,}\\b");
    // Standalone 4-8 digit codes (OTP). Conservative — must be surrounded by
    // word boundaries, so "abc1234def" is not touched.
    private static final Pattern OTP = Pattern.compile("\\b\\d{4,8}\\b");

    // Default sensitive-app package prefixes: banking, finance, password managers,
    // wallet apps. Users can override via SharedPreferences.
    private static final String[] DEFAULT_SENSITIVE_PREFIXES = new String[] {
            "com.chase",
            "com.wellsfargo",
            "com.wf.",
            "com.bankofamerica",
            "com.citi",
            "com.usaa",
            "com.capitalone",
            "com.discover",
            "com.americanexpress",
            "com.paypal",
            "com.venmo",
            "com.squareup.cash",
            "com.robinhood",
            "com.coinbase",
            "com.binance",
            "com.blockchain",
            "com.lastpass",
            "com.dashlane",
            "com.agilebits.onepassword",
            "com.onepassword",
            "com.x.bitwarden",
            "com.bitwarden",
            "com.keepersecurity",
            "com.google.android.apps.walletnfcrel",
            "com.samsung.android.spay",
    };

    private static final String PREFS = "hermes_prefs";
    private static final String KEY_SENSITIVE_EXTRA = "sensitive_pkg_extra"; // comma-separated

    /** Redact credentials/OTP/card/SSN in a piece of text. Null → "". */
    public static String redactText(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String s = text;
        s = SSN.matcher(s).replaceAll(MASK);
        s = CARD.matcher(s).replaceAll(MASK);
        s = LONG_DIGITS.matcher(s).replaceAll(MASK);
        s = OTP.matcher(s).replaceAll(MASK);
        return s;
    }

    /**
     * True if the package name matches any built-in or user-configured
     * sensitive prefix. Never returns true for an empty/null package.
     */
    public static boolean isSensitivePackage(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        for (String prefix : DEFAULT_SENSITIVE_PREFIXES) {
            if (pkg.startsWith(prefix)) return true;
        }
        for (String extra : extraSensitivePrefixes(ctx)) {
            if (!extra.isEmpty() && pkg.startsWith(extra)) return true;
        }
        return false;
    }

    private static Set<String> extraSensitivePrefixes(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String csv = sp.getString(KEY_SENSITIVE_EXTRA, "");
            if (csv == null || csv.isEmpty()) return Collections.emptySet();
            LinkedHashSet<String> set = new LinkedHashSet<>();
            for (String p : csv.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) set.add(t);
            }
            return set;
        } catch (Throwable ignored) {
            return Collections.emptySet();
        }
    }

    // ─── Privacy master toggle ────────────────────────────────────────────
    private static final String KEY_PRIVACY = "privacy_locked";

    public static boolean isPrivacyOn(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_PRIVACY, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setPrivacyOn(Context ctx, boolean on) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_PRIVACY, on).apply();
        } catch (Throwable ignored) {}
    }
}
