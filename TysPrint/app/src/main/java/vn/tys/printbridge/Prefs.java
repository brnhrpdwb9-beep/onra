package vn.tys.printbridge;

import android.content.Context;
import android.content.SharedPreferences;

/** Cài đặt lưu trên máy. */
final class Prefs {
    private Prefs() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("tys_print", Context.MODE_PRIVATE);
    }

    static int port(Context c)          { return p(c).getInt("port", 9100); }
    static int chunk(Context c)         { return p(c).getInt("chunk", 4096); }
    static boolean keepOpen(Context c)  { return p(c).getBoolean("keepOpen", false); }
    static boolean autostart(Context c) { return p(c).getBoolean("autostart", true); }
    static boolean enabled(Context c)   { return p(c).getBoolean("enabled", true); }
    static boolean cut(Context c)       { return p(c).getBoolean("cut", true); }
    static int testWidth(Context c)     { return p(c).getInt("testWidth", 384); }
    static int vid(Context c)           { return p(c).getInt("vid", -1); }
    static int pid(Context c)           { return p(c).getInt("pid", -1); }

    static void setEnabled(Context c, boolean v) { p(c).edit().putBoolean("enabled", v).apply(); }

    static void setDevice(Context c, int vid, int pid) {
        p(c).edit().putInt("vid", vid).putInt("pid", pid).apply();
    }

    static void save(Context c, int port, int chunk, boolean keepOpen, boolean autostart, boolean cut, int testWidth) {
        p(c).edit()
                .putInt("port", port)
                .putInt("chunk", chunk)
                .putBoolean("keepOpen", keepOpen)
                .putBoolean("autostart", autostart)
                .putBoolean("cut", cut)
                .putInt("testWidth", testWidth)
                .apply();
    }
}
