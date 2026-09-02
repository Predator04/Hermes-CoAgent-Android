package com.hermescoagent.phone;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * On-device self-learning store for element finding.
 *
 * Records per-package find/find_tap outcomes (success, tree size, match field,
 * duration) so the controller can learn which apps are accessibility-opaque
 * (games, WebViews, custom-rendered UIs) and drive them via screenshot + vision
 * instead of wasting a tree-dump round-trip.
 *
 * Deliberately dependency-free (android.database.sqlite) and fire-and-forget:
 * telemetry logging must never break the action it is observing.
 */
public class TelemetryStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "coagent_telemetry.db";
    private static final int DB_VERSION = 1;

    private static volatile TelemetryStore instance;

    public static TelemetryStore get(Context ctx) {
        if (instance == null) {
            synchronized (TelemetryStore.class) {
                if (instance == null) {
                    instance = new TelemetryStore(ctx.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private TelemetryStore(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS find_events ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "ts INTEGER NOT NULL,"
                + "package TEXT NOT NULL,"
                + "query TEXT,"
                + "success INTEGER NOT NULL,"
                + "tree_nodes INTEGER NOT NULL,"
                + "match_field TEXT,"
                + "duration_ms INTEGER NOT NULL"
                + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_find_pkg ON find_events(package)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS find_events");
        onCreate(db);
    }

    /** Record a find/find_tap outcome. Never throws into the caller. */
    public void logFind(String pkg, String query, boolean success, int treeNodes,
                        String matchField, long durationMs) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("ts", System.currentTimeMillis());
            cv.put("package", pkg == null ? "" : pkg);
            cv.put("query", query == null ? "" : query);
            cv.put("success", success ? 1 : 0);
            cv.put("tree_nodes", treeNodes);
            cv.put("match_field", matchField == null ? "" : matchField);
            cv.put("duration_ms", durationMs);
            db.insert("find_events", null, cv);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Per-package learnings: attempts, success rate, avg tree size, avg duration,
     * top match field, and an `opaque` flag (tree yields ~nothing for this app).
     * Empty/null package = aggregate over all packages.
     */
    public JSONObject bestStrategy(String pkg) throws Exception {
        JSONObject out = new JSONObject();
        SQLiteDatabase db = getReadableDatabase();
        String where = "";
        String[] args = null;
        if (pkg != null && !pkg.isEmpty()) {
            where = "WHERE package = ?";
            args = new String[]{pkg};
        }
        Cursor c = db.rawQuery(
                "SELECT package, COUNT(*) AS n, COALESCE(SUM(success),0) AS ok, "
                        + "AVG(tree_nodes) AS avg_nodes, AVG(duration_ms) AS avg_ms "
                        + "FROM find_events " + where + " GROUP BY package ORDER BY n DESC",
                args);
        JSONArray per = new JSONArray();
        while (c.moveToNext()) {
            String p = c.getString(0);
            int n = c.getInt(1);
            int ok = c.getInt(2);
            double rate = n > 0 ? (double) ok / n : 0.0;
            double avgNodes = c.getDouble(3);
            JSONObject o = new JSONObject();
            o.put("package", p);
            o.put("find_attempts", n);
            o.put("find_success_rate", Math.round(rate * 1000.0) / 1000.0);
            o.put("avg_tree_nodes", Math.round(avgNodes));
            o.put("avg_duration_ms", Math.round(c.getDouble(4)));
            o.put("opaque", n >= 2 && avgNodes < 2.0 && rate < 0.5);
            per.put(o);
        }
        c.close();

        // top match field (text vs desc) per package
        Cursor f = db.rawQuery(
                "SELECT package, match_field, COUNT(*) AS n FROM find_events "
                        + "WHERE match_field != '' " + where + " GROUP BY package, match_field",
                args);
        Map<String, String> topField = new HashMap<>();
        Map<String, Integer> topN = new HashMap<>();
        while (f.moveToNext()) {
            String p = f.getString(0);
            String mf = f.getString(1);
            int n = f.getInt(2);
            if (n > (topN.containsKey(p) ? topN.get(p) : 0)) {
                topN.put(p, n);
                topField.put(p, mf);
            }
        }
        f.close();
        for (int i = 0; i < per.length(); i++) {
            String p = per.getJSONObject(i).optString("package", "");
            if (topField.containsKey(p)) {
                per.getJSONObject(i).put("top_match_field", topField.get(p));
            }
        }

        out.put("packages", per);
        out.put("package_count", per.length());
        return out;
    }

    public void clear() {
        try {
            getWritableDatabase().execSQL("DELETE FROM find_events");
        } catch (Throwable ignored) {
        }
    }
}
