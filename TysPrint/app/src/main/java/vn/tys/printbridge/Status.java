package vn.tys.printbridge;

import java.util.concurrent.atomic.AtomicInteger;

/** Trạng thái dùng chung giữa dịch vụ in và màn hình. */
final class Status {
    private Status() {}

    static volatile boolean serverRunning = false;
    static volatile int port = 9100;
    static volatile String printerName = "";
    static volatile boolean printerOk = false;
    static volatile int printed = 0;
    static volatile int failed = 0;
    static final AtomicInteger queued = new AtomicInteger(0);
    static volatile String lastError = "";
    static volatile long lastJobAt = 0;
}
