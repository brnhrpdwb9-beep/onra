package vn.tys.printbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** Tự bật dịch vụ in khi máy POS khởi động. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Prefs.autostart(c) || !Prefs.enabled(c)) return;
        Intent s = new Intent(c, PrintService.class).setAction(PrintService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
            else c.startService(s);
        } catch (Exception e) {
            Log.e("TysPrint", "Không tự bật được dịch vụ in", e);
        }
    }
}
