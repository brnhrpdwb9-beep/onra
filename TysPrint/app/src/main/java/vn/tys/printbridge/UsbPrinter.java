package vn.tys.printbridge;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Gửi nguyên dữ liệu xuống máy in USB (không biến đổi). */
final class UsbPrinter {
    static final String ACTION_USB_PERMISSION = "vn.tys.printbridge.USB_PERMISSION";

    static final class NoPermissionException extends IOException {
        final UsbDevice device;
        NoPermissionException(UsbDevice d) {
            super("Chưa được cấp quyền dùng máy in USB");
            device = d;
        }
    }

    private final Context ctx;
    private final UsbManager um;
    private UsbDevice dev;
    private UsbDeviceConnection conn;
    private UsbInterface itf;
    private UsbEndpoint ep;

    UsbPrinter(Context c) {
        ctx = c.getApplicationContext();
        um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
    }

    // ---------- Nhận diện thiết bị ----------
    static UsbEndpoint bulkOut(UsbInterface i) {
        for (int k = 0; k < i.getEndpointCount(); k++) {
            UsbEndpoint e = i.getEndpoint(k);
            if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && e.getDirection() == UsbConstants.USB_DIR_OUT) return e;
        }
        return null;
    }

    static boolean hasPrinterClass(UsbDevice d) {
        if (d.getDeviceClass() == UsbConstants.USB_CLASS_PRINTER) return true;
        for (int k = 0; k < d.getInterfaceCount(); k++)
            if (d.getInterface(k).getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) return true;
        return false;
    }

    static boolean hasBulkOut(UsbDevice d) {
        for (int k = 0; k < d.getInterfaceCount(); k++)
            if (bulkOut(d.getInterface(k)) != null) return true;
        return false;
    }

    static String describe(UsbDevice d) {
        String name = "";
        try {
            String m = d.getManufacturerName(), p = d.getProductName();
            name = ((m == null ? "" : m) + " " + (p == null ? "" : p)).trim();
        } catch (Exception ignored) {}
        if (name.isEmpty()) name = "Thiết bị USB";
        return name + String.format(Locale.US, " (VID %04X PID %04X)%s",
                d.getVendorId(), d.getProductId(), hasPrinterClass(d) ? " · máy in" : "");
    }

    List<UsbDevice> candidates() {
        List<UsbDevice> out = new ArrayList<>();
        if (um == null) return out;
        for (UsbDevice d : um.getDeviceList().values()) if (hasBulkOut(d)) out.add(d);
        return out;
    }

    /** Ưu tiên: máy in đã chọn -> thiết bị loại máy in -> thiết bị duy nhất. */
    UsbDevice pickDevice() {
        List<UsbDevice> list = candidates();
        int vid = Prefs.vid(ctx), pid = Prefs.pid(ctx);
        if (vid >= 0) for (UsbDevice d : list) if (d.getVendorId() == vid && d.getProductId() == pid) return d;
        for (UsbDevice d : list) if (hasPrinterClass(d)) return d;
        if (list.size() == 1) return list.get(0);
        return null;
    }

    boolean hasPermission(UsbDevice d) {
        return um != null && d != null && um.hasPermission(d);
    }

    void requestPermission(UsbDevice d) {
        if (um == null || d == null) return;
        Intent i = new Intent(ACTION_USB_PERMISSION);
        i.setPackage(ctx.getPackageName());
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) f |= PendingIntent.FLAG_MUTABLE;
        um.requestPermission(d, PendingIntent.getBroadcast(ctx, 0, i, f));
    }

    // ---------- In ----------
    synchronized void print(byte[] data, int chunk, boolean keepOpen) throws IOException {
        try {
            open();
            write(data, chunk);
        } catch (IOException e) {
            close();
            throw e;
        }
        if (!keepOpen) {
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            close();   // nhả máy in để FABi vẫn dùng được
        }
    }

    private void open() throws IOException {
        if (um == null) throw new IOException("Máy POS không hỗ trợ USB host");
        UsbDevice d = pickDevice();
        if (d == null) throw new IOException("Không tìm thấy máy in USB (máy in tắt / chưa cắm dây?)");
        if (!um.hasPermission(d)) throw new NoPermissionException(d);
        if (conn != null && dev != null && dev.getDeviceId() == d.getDeviceId()) return;
        close();

        UsbInterface best = null;
        UsbEndpoint bestEp = null;
        for (int k = 0; k < d.getInterfaceCount(); k++) {
            UsbInterface i = d.getInterface(k);
            UsbEndpoint e = bulkOut(i);
            if (e == null) continue;
            boolean isPrinter = i.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER;
            if (best == null || (isPrinter && best.getInterfaceClass() != UsbConstants.USB_CLASS_PRINTER)) {
                best = i;
                bestEp = e;
            }
        }
        if (best == null) throw new IOException("Thiết bị không có cổng gửi dữ liệu");

        UsbDeviceConnection c = um.openDevice(d);
        if (c == null) throw new IOException("Không mở được máy in");
        if (!c.claimInterface(best, true)) {
            c.close();
            throw new IOException("Không chiếm được cổng in — có ứng dụng khác (FABi?) đang giữ máy in");
        }
        dev = d; conn = c; itf = best; ep = bestEp;
    }

    private void write(byte[] data, int chunk) throws IOException {
        int size = Math.max(64, Math.min(16384, chunk));
        int off = 0, zero = 0;
        while (off < data.length) {
            int n = Math.min(size, data.length - off);
            byte[] part = (off == 0 && n == data.length) ? data : Arrays.copyOfRange(data, off, off + n);
            int w = conn.bulkTransfer(ep, part, n, 5000);
            if (w < 0) throw new IOException("Máy in không nhận dữ liệu (hết giấy, nắp mở hoặc máy in bận)");
            if (w == 0) {
                if (++zero > 5) throw new IOException("Máy in không nhận dữ liệu");
                continue;
            }
            zero = 0;
            off += w;
        }
    }

    synchronized void close() {
        if (conn != null) {
            try { if (itf != null) conn.releaseInterface(itf); } catch (Exception ignored) {}
            try { conn.close(); } catch (Exception ignored) {}
        }
        conn = null; itf = null; ep = null; dev = null;
    }
}
