package vn.tys.printbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private TextView tvServer, tvAddress, tvPrinter, tvCounts, tvError, tvLog;
    private EditText etPort, etChunk;
    private CheckBox cbKeepOpen, cbAutostart, cbCut;
    private RadioButton rb384, rb576;
    private UsbPrinter usb;
    private long logVersion = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        usb = new UsbPrinter(this);

        tvServer = findViewById(R.id.tvServer);
        tvAddress = findViewById(R.id.tvAddress);
        tvPrinter = findViewById(R.id.tvPrinter);
        tvCounts = findViewById(R.id.tvCounts);
        tvError = findViewById(R.id.tvError);
        tvLog = findViewById(R.id.tvLog);
        etPort = findViewById(R.id.etPort);
        etChunk = findViewById(R.id.etChunk);
        cbKeepOpen = findViewById(R.id.cbKeepOpen);
        cbAutostart = findViewById(R.id.cbAutostart);
        cbCut = findViewById(R.id.cbCut);
        rb384 = findViewById(R.id.rb384);
        rb576 = findViewById(R.id.rb576);
        loadSettingsToUi();

        findViewById(R.id.btnStart).setOnClickListener(v -> {
            Prefs.setEnabled(this, true);
            send(PrintService.ACTION_START, null);
            toast("Đã bật dịch vụ in");
        });
        findViewById(R.id.btnStop).setOnClickListener(v -> {
            Prefs.setEnabled(this, false);
            if (PrintService.running) send(PrintService.ACTION_STOP, null);
        });
        findViewById(R.id.btnChoose).setOnClickListener(v -> choosePrinter());
        findViewById(R.id.btnPermission).setOnClickListener(v -> askPermission());
        findViewById(R.id.btnTestRaw).setOnClickListener(v -> test("raw"));
        findViewById(R.id.btnTestText).setOnClickListener(v -> test("text"));
        findViewById(R.id.btnTestGsv0).setOnClickListener(v -> test("gsv0"));
        findViewById(R.id.btnTestEscStar).setOnClickListener(v -> test("escstar"));
        findViewById(R.id.btnSave).setOnClickListener(v -> saveSettings());
        findViewById(R.id.btnBattery).setOnClickListener(v -> openBatterySettings());
        findViewById(R.id.btnClearLog).setOnClickListener(v -> AppLog.clear());

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Prefs.enabled(this) && !PrintService.running) send(PrintService.ACTION_START, null);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    // ---------- Điều khiển dịch vụ ----------
    private void send(String action, String type) {
        Intent i = new Intent(this, PrintService.class).setAction(action);
        if (type != null) i.putExtra(PrintService.EXTRA_TYPE, type);
        try {
            startService(i);
        } catch (Exception e) {
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            } catch (Exception e2) {
                toast("Không bật được dịch vụ in: " + e2.getMessage());
            }
        }
    }

    private void test(String type) {
        if (!PrintService.running) {
            Prefs.setEnabled(this, true);
            send(PrintService.ACTION_START, null);
        }
        send(PrintService.ACTION_TEST, type);
        toast("Đã gửi in thử: " + type);
    }

    // ---------- Máy in ----------
    private void choosePrinter() {
        final List<UsbDevice> list = usb.candidates();
        if (list.isEmpty()) {
            toast("Không thấy thiết bị USB nào. Kiểm tra máy in đã bật và cắm dây chưa.");
            return;
        }
        String[] names = new String[list.size()];
        for (int k = 0; k < list.size(); k++) names[k] = UsbPrinter.describe(list.get(k));
        new AlertDialog.Builder(this)
                .setTitle("Chọn máy in")
                .setItems(names, (dialog, which) -> {
                    UsbDevice d = list.get(which);
                    Prefs.setDevice(this, d.getVendorId(), d.getProductId());
                    if (!usb.hasPermission(d)) usb.requestPermission(d);
                    send(PrintService.ACTION_RESELECT, null);
                    toast("Đã chọn: " + UsbPrinter.describe(d));
                })
                .setNegativeButton("Huỷ", null)
                .show();
    }

    private void askPermission() {
        UsbDevice d = usb.pickDevice();
        if (d == null) { toast("Chưa thấy máy in. Bấm \"Chọn máy in\"."); return; }
        if (usb.hasPermission(d)) { toast("Đã có quyền dùng máy in"); return; }
        usb.requestPermission(d);
    }

    // ---------- Cài đặt ----------
    private void loadSettingsToUi() {
        etPort.setText(String.valueOf(Prefs.port(this)));
        etChunk.setText(String.valueOf(Prefs.chunk(this)));
        cbKeepOpen.setChecked(Prefs.keepOpen(this));
        cbAutostart.setChecked(Prefs.autostart(this));
        cbCut.setChecked(Prefs.cut(this));
        if (Prefs.testWidth(this) == 576) rb576.setChecked(true); else rb384.setChecked(true);
    }

    private void saveSettings() {
        int port, chunk;
        try { port = Integer.parseInt(etPort.getText().toString().trim()); } catch (Exception e) { port = -1; }
        try { chunk = Integer.parseInt(etChunk.getText().toString().trim()); } catch (Exception e) { chunk = -1; }
        if (port < 1024 || port > 65535) { toast("Cổng phải từ 1024 đến 65535"); return; }
        if (chunk < 64 || chunk > 16384) { toast("Khúc dữ liệu phải từ 64 đến 16384"); return; }
        boolean portChanged = port != Prefs.port(this);
        Prefs.save(this, port, chunk, cbKeepOpen.isChecked(), cbAutostart.isChecked(), cbCut.isChecked(),
                rb576.isChecked() ? 576 : 384);
        if (portChanged && PrintService.running) send(PrintService.ACTION_RESTART, null);
        if (!cbKeepOpen.isChecked() && PrintService.running) send(PrintService.ACTION_RESELECT, null);
        toast("Đã lưu cài đặt");
    }

    private void openBatterySettings() {
        try {
            if (Build.VERSION.SDK_INT >= 23) startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            else startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    // ---------- Hiển thị ----------
    private void refresh() {
        boolean on = PrintService.running && Status.serverRunning;
        if (on) {
            tvServer.setText("● Đang nhận lệnh in");
            tvServer.setTextColor(0xFF3F7A2E);
        } else if (PrintService.running) {
            tvServer.setText("● Đang mở cổng nhận lệnh in...");
            tvServer.setTextColor(0xFFA9793A);
        } else {
            tvServer.setText("● Dịch vụ in đang TẮT");
            tvServer.setTextColor(0xFFB23A22);
        }

        int port = Prefs.port(this);
        List<String> ips = localIps();
        if (ips.isEmpty()) {
            tvAddress.setText("Máy POS chưa kết nối wifi / mạng");
        } else {
            StringBuilder sb = new StringBuilder("Laptop gửi lệnh in tới:");
            for (String ip : ips) sb.append("\n").append(ip).append(":").append(port);
            tvAddress.setText(sb.toString());
        }

        UsbDevice d = usb.pickDevice();
        if (d == null) {
            tvPrinter.setText("● Chưa thấy máy in USB — bấm \"Chọn máy in\"");
            tvPrinter.setTextColor(0xFFB23A22);
        } else if (!usb.hasPermission(d)) {
            tvPrinter.setText("● " + UsbPrinter.describe(d) + "\n   Chưa có quyền — bấm \"Cấp quyền USB\"");
            tvPrinter.setTextColor(0xFFA9793A);
        } else {
            tvPrinter.setText("● Máy in: " + UsbPrinter.describe(d));
            tvPrinter.setTextColor(0xFF3F7A2E);
        }

        tvCounts.setText("Đã in: " + Status.printed + " · Lỗi: " + Status.failed + " · Đang chờ: " + Status.queued.get());
        if (Status.lastError == null || Status.lastError.isEmpty()) {
            tvError.setVisibility(View.GONE);
        } else {
            tvError.setVisibility(View.VISIBLE);
            tvError.setText("Lỗi gần nhất: " + Status.lastError);
        }

        long v = AppLog.version();
        if (v != logVersion) {
            logVersion = v;
            tvLog.setText(AppLog.dump());
        }
    }

    private static List<String> localIps() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try { if (!ni.isUp() || ni.isLoopback()) continue; } catch (Exception e) { continue; }
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) out.add(a.getHostAddress());
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
