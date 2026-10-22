package vn.tys.printbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dịch vụ chạy nền: mở cổng TCP (mặc định 9100), nhận dữ liệu và chuyển nguyên xuống máy in USB. */
public class PrintService extends Service {
    public static final String ACTION_START = "vn.tys.printbridge.START";
    public static final String ACTION_STOP = "vn.tys.printbridge.STOP";
    public static final String ACTION_TEST = "vn.tys.printbridge.TEST";
    public static final String ACTION_RESTART = "vn.tys.printbridge.RESTART";
    public static final String ACTION_RESELECT = "vn.tys.printbridge.RESELECT";
    public static final String EXTRA_TYPE = "type";

    static volatile boolean running = false;

    private static final String CHANNEL = "tys_print";
    private static final int NOTIF_ID = 1;
    private static final int MAX_JOB_BYTES = 32 * 1024 * 1024;

    private UsbPrinter printer;
    private ExecutorService printExec;
    private ExecutorService connExec;
    private volatile ServerSocket server;
    private AtomicBoolean serverAlive;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private long lastPermAsk = 0;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (UsbPrinter.ACTION_USB_PERMISSION.equals(a)) {
                boolean ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                AppLog.add(ok ? "Đã được cấp quyền dùng máy in" : "Quyền dùng máy in bị từ chối");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                AppLog.add("Một thiết bị USB vừa bị rút");
                runOnPrinter(() -> printer.close());
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                AppLog.add("Một thiết bị USB vừa được cắm");
                runOnPrinter(() -> checkPrinter(true));
            }
            updateNotification();
        }
    };

    // ================== VÒNG ĐỜI ==================
    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        printer = new UsbPrinter(this);
        printExec = Executors.newSingleThreadExecutor();
        connExec = Executors.newCachedThreadPool();
        createChannel();
        goForeground();
        acquireLocks();

        IntentFilter f = new IntentFilter();
        f.addAction(UsbPrinter.ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(usbReceiver, f);

        startServer();
        runOnPrinter(() -> checkPrinter(true));
        AppLog.add("Dịch vụ in đã bật");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        String a = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(a)) {
            AppLog.add("Đã tắt dịch vụ in");
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForegroundLegacy();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(a)) {
            AppLog.add("Mở lại cổng nhận lệnh in");
            startServer();
        } else if (ACTION_RESELECT.equals(a)) {
            runOnPrinter(() -> { printer.close(); checkPrinter(true); });
        } else if (ACTION_TEST.equals(a)) {
            String t = intent.getStringExtra(EXTRA_TYPE);
            byte[] data = testBytes(t);
            if (data != null) enqueue(data, "In thử " + t);
        }
        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundLegacy() { stopForeground(true); }

    @Override
    public void onDestroy() {
        running = false;
        stopServer();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        connExec.shutdownNow();
        printExec.shutdownNow();
        printer.close();
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
        Status.serverRunning = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ================== CỔNG TCP ==================
    private synchronized void startServer() {
        stopServer();
        final AtomicBoolean alive = new AtomicBoolean(true);
        serverAlive = alive;
        final int port = Prefs.port(this);

        Thread t = new Thread(() -> {
            while (alive.get()) {
                ServerSocket ss = null;
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(port));
                    server = ss;
                    if (!alive.get()) break;
                    Status.serverRunning = true;
                    Status.port = port;
                    AppLog.add("Đang nhận lệnh in ở cổng " + port);
                    updateNotification();
                    while (alive.get()) {
                        final Socket s = ss.accept();
                        try { connExec.execute(() -> handleClient(s)); }
                        catch (RejectedExecutionException e) { closeQuietly(s); }
                    }
                } catch (IOException e) {
                    if (alive.get()) AppLog.add("Lỗi cổng " + port + ": " + e.getMessage() + " — thử lại sau 3 giây");
                } finally {
                    closeQuietly(ss);
                    if (server == ss) {
                        server = null;
                        Status.serverRunning = false;
                    }
                }
                if (alive.get()) sleep(3000);
            }
        }, "tys-accept");
        t.start();
    }

    private synchronized void stopServer() {
        if (serverAlive != null) serverAlive.set(false);
        closeQuietly(server);
        server = null;
        Status.serverRunning = false;
    }

    /** Đọc toàn bộ dữ liệu một lần gửi (đến khi laptop đóng kết nối hoặc ngừng gửi 3 giây). */
    private void handleClient(Socket s) {
        String who = s.getInetAddress() != null ? s.getInetAddress().getHostAddress() : "?";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            s.setSoTimeout(3000);
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            byte[] b = new byte[16384];
            int idle = 0;
            while (true) {
                int n;
                try {
                    n = in.read(b);
                } catch (SocketTimeoutException te) {
                    if (buf.size() > 0) break;
                    if (++idle >= 10) break;     // 30 giây không có gì thì đóng
                    continue;
                }
                if (n < 0) break;
                buf.write(b, 0, n);
                if (buf.size() > MAX_JOB_BYTES) {
                    AppLog.add("Dữ liệu từ " + who + " quá lớn, bỏ qua");
                    buf.reset();
                    break;
                }
            }
        } catch (IOException e) {
            AppLog.add("Lỗi nhận dữ liệu từ " + who + ": " + e.getMessage());
        } finally {
            closeQuietly(s);
        }
        if (buf.size() > 0) enqueue(buf.toByteArray(), "Lệnh in từ " + who);
    }

    // ================== IN ==================
    private void runOnPrinter(Runnable r) {
        try { printExec.execute(r); } catch (RejectedExecutionException ignored) {}
    }

    private void enqueue(final byte[] data, final String label) {
        Status.queued.incrementAndGet();
        try {
            printExec.execute(() -> {
                Status.queued.decrementAndGet();
                doPrint(data, label);
            });
        } catch (RejectedExecutionException e) {
            Status.queued.decrementAndGet();
        }
    }

    private void doPrint(byte[] data, String label) {
        String err = "Không rõ lỗi";
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                printer.print(data, Prefs.chunk(this), Prefs.keepOpen(this));
                Status.printed++;
                Status.printerOk = true;
                Status.lastError = "";
                Status.lastJobAt = System.currentTimeMillis();
                AppLog.add("Đã in: " + label + " (" + data.length + " byte)");
                updateNotification();
                return;
            } catch (UsbPrinter.NoPermissionException e) {
                err = e.getMessage();
                askPermission(e.device);
                if (!waitPermission(25000)) break;
            } catch (IOException e) {
                err = e.getMessage();
                AppLog.add("Lỗi in lần " + attempt + ": " + err);
                sleep(1500);
            }
        }
        Status.failed++;
        Status.printerOk = false;
        Status.lastError = err;
        AppLog.add("KHÔNG in được: " + label + " — " + err);
        updateNotification();
    }

    private boolean checkPrinter(boolean ask) {
        UsbDevice d = printer.pickDevice();
        if (d == null) {
            Status.printerName = "";
            Status.printerOk = false;
            return false;
        }
        Status.printerName = UsbPrinter.describe(d);
        if (printer.hasPermission(d)) {
            Status.printerOk = true;
            return true;
        }
        Status.printerOk = false;
        if (ask) askPermission(d);
        return false;
    }

    private void askPermission(UsbDevice d) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastPermAsk < 15000) return;
        lastPermAsk = now;
        AppLog.add("Xin quyền dùng máy in — bấm \"Cho phép\" trên màn hình máy POS");
        printer.requestPermission(d);
    }

    private boolean waitPermission(long ms) {
        long end = SystemClock.elapsedRealtime() + ms;
        while (SystemClock.elapsedRealtime() < end) {
            UsbDevice d = printer.pickDevice();
            if (d != null && printer.hasPermission(d)) return true;
            sleep(500);
        }
        return false;
    }

    private byte[] testBytes(String t) {
        int w = Prefs.testWidth(this);
        boolean cut = Prefs.cut(this);
        if ("raw".equals(t)) return EscPos.rawHello();
        if ("text".equals(t)) return EscPos.textTest(cut);
        if ("gsv0".equals(t)) return EscPos.imageTest(w, false, cut);
        if ("escstar".equals(t)) return EscPos.imageTest(w, true, cut);
        return null;
    }

    // ================== THÔNG BÁO & CHẠY NỀN ==================
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Dịch vụ in", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        String text = (Status.serverRunning ? "Cổng " + Status.port : "Đang mở cổng...")
                + " · Đã in " + Status.printed
                + (Status.failed > 0 ? " · Lỗi " + Status.failed : "");
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pf = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) pf |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, pf);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("TYS Print đang chạy")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }

    private void goForeground() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIF_ID, n);
        } catch (Exception e) {
            AppLog.add("Không chạy nền được: " + e.getMessage());
        }
    }

    private void updateNotification() {
        if (!running) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TysPrint:service");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception ignored) {}
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TysPrint:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception ignored) {}
    }

    // ================== TIỆN ÍCH ==================
    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static void closeQuietly(ServerSocket s) {
        try { if (s != null) s.close(); } catch (Exception ignored) {}
    }

    private static void closeQuietly(Socket s) {
        try { if (s != null) s.close(); } catch (Exception ignored) {}
    }
}
