package vn.tys.printbridge;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/** Nhật ký trong bộ nhớ, hiện trên màn hình app. */
final class AppLog {
    private static final int MAX = 300;
    private static final LinkedList<String> LINES = new LinkedList<>();
    private static volatile long version = 0;

    private AppLog() {}

    static synchronized void add(String msg) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        LINES.addFirst(t + "  " + msg);
        while (LINES.size() > MAX) LINES.removeLast();
        version++;
        Log.i("TysPrint", msg);
    }

    static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : LINES) sb.append(s).append('\n');
        return sb.toString();
    }

    static synchronized void clear() {
        LINES.clear();
        version++;
    }

    static long version() { return version; }
}
