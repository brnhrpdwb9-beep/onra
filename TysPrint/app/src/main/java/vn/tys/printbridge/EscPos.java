package vn.tys.printbridge;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Dữ liệu in thử. Bill thật do laptop tạo sẵn, app chỉ chuyển nguyên. */
final class EscPos {
    private EscPos() {}

    private static void w(ByteArrayOutputStream o, int... b) { for (int x : b) o.write(x); }

    private static void s(ByteArrayOutputStream o, String t) {
        byte[] b = t.getBytes(StandardCharsets.US_ASCII);
        o.write(b, 0, b.length);
    }

    private static void tail(ByteArrayOutputStream o, boolean cut) {
        w(o, 0x1B, 0x64, 4);                         // đẩy giấy 4 dòng
        if (cut) w(o, 0x1D, 0x56, 0x42, 0x00);       // cắt giấy
    }

    /** Chữ trần, không có lệnh nào. */
    static byte[] rawHello() {
        return "HELLO 123\r\nTYS PRINT - chu tran\r\n\r\n\r\n\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    }

    /** Chữ có lệnh ESC/POS: căn giữa, chữ to. */
    static byte[] textTest(boolean cut) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        w(o, 0x1B, 0x40, 0x1B, 0x61, 1, 0x1B, 0x21, 0x38);
        s(o, "TEST IN THU 123\n");
        w(o, 0x1B, 0x21, 0x00);
        s(o, "TYS COFFEE - App in USB\n");
        w(o, 0x1B, 0x61, 0);
        s(o, "Chu khong dau: OK\n1234567890 ABCDEFGHIJ\n");
        tail(o, cut);
        return o.toByteArray();
    }

    /** Ảnh có chữ tiếng Việt có dấu, in bằng GS v 0 hoặc ESC *. */
    static byte[] imageTest(int width, boolean escStar, boolean cut) {
        int h = 170;
        Bitmap bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        c.drawRect(0, 0, width, 24, p);
        p.setFakeBoldText(true);
        p.setTextSize(width >= 576 ? 32 : 24);
        c.drawText("ẢNH OK · Tiếng Việt có dấu", 8, 64, p);
        p.setFakeBoldText(false);
        p.setTextSize(20);
        c.drawText("Lệnh: " + (escStar ? "ESC *" : "GS v 0") + " · rộng " + width + " điểm", 8, 98, p);
        p.setAntiAlias(false);
        for (int y = 112; y < h; y += 14)
            for (int x = 0; x < width; x += 14)
                if ((((x + y) / 14) & 1) == 0) c.drawRect(x, y, x + 14, Math.min(y + 14, h), p);

        byte[] bits = toBits(bmp);
        bmp.recycle();
        int bw = (width + 7) / 8;
        return escStar ? escStar(bw, h, bits, cut) : gsv0(bw, h, bits, cut);
    }

    static byte[] toBits(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), bw = (w + 7) / 8;
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        byte[] out = new byte[bw * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, bl = c & 0xFF;
                if (0.299 * r + 0.587 * g + 0.114 * bl < 150) out[y * bw + (x >> 3)] |= (byte) (0x80 >> (x & 7));
            }
        }
        return out;
    }

    static byte[] gsv0(int bw, int h, byte[] d, boolean cut) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        w(o, 0x1B, 0x40);
        for (int y = 0; y < h; y += 24) {
            int n = Math.min(24, h - y);
            w(o, 0x1D, 0x76, 0x30, 0x00, bw & 0xFF, (bw >> 8) & 0xFF, n & 0xFF, (n >> 8) & 0xFF);
            o.write(d, y * bw, n * bw);
        }
        tail(o, cut);
        return o.toByteArray();
    }

    static byte[] escStar(int bw, int h, byte[] d, boolean cut) {
        int width = bw * 8;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        w(o, 0x1B, 0x40, 0x1B, 0x33, 24);
        for (int y0 = 0; y0 < h; y0 += 24) {
            byte[] band = new byte[width * 3];
            for (int x = 0; x < width; x++) {
                for (int k = 0; k < 24; k++) {
                    int y = y0 + k;
                    if (y >= h) break;
                    if ((d[y * bw + (x >> 3)] & (0x80 >> (x & 7))) != 0) band[x * 3 + (k >> 3)] |= (byte) (0x80 >> (k & 7));
                }
            }
            w(o, 0x1B, 0x2A, 33, width & 0xFF, (width >> 8) & 0xFF);
            o.write(band, 0, band.length);
            w(o, 0x0A);
        }
        w(o, 0x1B, 0x32);
        tail(o, cut);
        return o.toByteArray();
    }
}
