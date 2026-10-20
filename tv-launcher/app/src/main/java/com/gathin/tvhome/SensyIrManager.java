package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native USB driver for Sensara Sensy USB-OTG IR Blaster (VID 0x2717, PID 0x003d).
 * Directly communicates with TV USB port to blast NEC IR commands to STBs.
 */
public class SensyIrManager {

    private static final String TAG = "SensyIrManager";
    public static final int VENDOR_ID = 0x2717;
    public static final int PRODUCT_ID = 0x003d;
    public static final String ACTION_USB_PERMISSION = "com.gathin.tvhome.USB_PERMISSION";

    private static final int OP_GET_CONFIG = 1;
    private static final int OP_SET_CONFIG = 2;
    private static final int OP_START_BUFFER = 3;
    private static final int OP_END_BUFFER = 4;
    private static final int OP_RESET = 120;

    private static SensyIrManager sInstance;

    private final Context context;
    private final UsbManager usbManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private UsbDevice usbDevice;
    private UsbDeviceConnection connection;
    private UsbInterface usbInterface;
    private UsbEndpoint epCmd;
    private UsbEndpoint epResp;
    private UsbEndpoint epData;

    public static synchronized SensyIrManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new SensyIrManager(context.getApplicationContext());
        }
        return sInstance;
    }

    private SensyIrManager(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public UsbDevice findDongle() {
        if (usbManager == null) return null;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (device.getVendorId() == VENDOR_ID && device.getProductId() == PRODUCT_ID) {
                return device;
            }
        }
        return null;
    }

    public boolean isConnected() {
        return findDongle() != null;
    }

    public boolean hasPermission() {
        UsbDevice d = findDongle();
        return d != null && usbManager.hasPermission(d);
    }

    public void requestPermission(Runnable onGranted) {
        UsbDevice device = findDongle();
        if (device == null) {
            Log.w(TAG, "Cannot request permission: Dongle not attached");
            return;
        }

        if (usbManager.hasPermission(device)) {
            openDongle();
            if (onGranted != null) onGranted.run();
            return;
        }

        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ? (PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)
                : PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), flags);

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    try {
                        context.unregisterReceiver(this);
                    } catch (Exception ignored) {}
                    if (granted) {
                        openDongle();
                        if (onGranted != null) mainHandler.post(onGranted);
                    }
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, new IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, new IntentFilter(ACTION_USB_PERMISSION));
        }

        usbManager.requestPermission(device, pi);
    }

    public synchronized boolean openDongle() {
        UsbDevice device = findDongle();
        if (device == null || !usbManager.hasPermission(device)) {
            return false;
        }

        if (connection != null && epCmd != null && epResp != null && epData != null) {
            return true;
        }

        UsbDeviceConnection conn = usbManager.openDevice(device);
        if (conn == null) {
            Log.e(TAG, "Failed to open USB device connection");
            return false;
        }

        UsbInterface intf = device.getInterface(0);
        if (!conn.claimInterface(intf, true)) {
            conn.close();
            Log.e(TAG, "Failed to claim USB interface 0");
            return false;
        }

        this.usbDevice = device;
        this.connection = conn;
        this.usbInterface = intf;

        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            switch (ep.getEndpointNumber()) {
                case 2:
                    if (ep.getDirection() == UsbConstants.USB_DIR_OUT) epCmd = ep;
                    break;
                case 1:
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) epResp = ep;
                    break;
                case 4:
                    if (ep.getDirection() == UsbConstants.USB_DIR_OUT) epData = ep;
                    break;
            }
        }

        boolean ok = epCmd != null && epResp != null && epData != null;
        Log.i(TAG, "openDongle result: " + ok);
        return ok;
    }

    private byte[] sendCommand(byte[] cmd) throws Exception {
        if (connection == null || epCmd == null || epResp == null) {
            throw new IllegalStateException("Dongle not opened");
        }
        connection.bulkTransfer(epCmd, cmd, cmd.length, 1000);
        byte[] resp = new byte[64];
        connection.bulkTransfer(epResp, resp, resp.length, 1000);
        return resp;
    }

    private int getState() {
        try {
            byte[] resp = sendCommand(new byte[]{(byte) OP_GET_CONFIG});
            return resp[3] & 0xFF;
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean waitForState(int target, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (getState() == target) return true;
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {}
        }
        return false;
    }

    private synchronized boolean transmitRaw(int frequency, int[] pattern) {
        if (!openDongle()) {
            Log.e(TAG, "transmitRaw: dongle not open");
            return false;
        }

        int byteLength = pattern.length * 4;

        try {
            // 1. Reset
            sendCommand(new byte[]{(byte) OP_RESET});
            waitForState(0, 1000);

            // 2. Set Config (carrier frequency, payload byte size)
            ByteBuffer cfg = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
            cfg.put((byte) OP_SET_CONFIG);
            cfg.putShort((short) frequency);
            cfg.putShort((short) byteLength);
            sendCommand(cfg.array());
            waitForState(1, 1000);

            // 3. Start Buffering
            sendCommand(new byte[]{(byte) OP_START_BUFFER});
            waitForState(2, 1000);

            // 4. Stream data to bulk endpoint 4 in 64-byte chunks
            ByteBuffer dataBuf = ByteBuffer.allocate(byteLength).order(ByteOrder.LITTLE_ENDIAN);
            for (int interval : pattern) {
                dataBuf.putInt(interval);
            }
            byte[] rawBytes = dataBuf.array();

            int offset = 0;
            while (offset < rawBytes.length) {
                int chunkLen = Math.min(64, rawBytes.length - offset);
                connection.bulkTransfer(epData, rawBytes, offset, chunkLen, 1000);
                offset += chunkLen;
            }

            // 5. End Buffering & Emit
            sendCommand(new byte[]{(byte) OP_END_BUFFER});

            // 6. Wait for return to Idle
            return waitForState(0, 10000);
        } catch (Exception e) {
            Log.e(TAG, "Error in transmitRaw", e);
            return false;
        }
    }

    private static int[] encodeNec(String codeStr, int bitLength) {
        String clean = codeStr.trim();
        long numericVal;
        if (clean.toLowerCase().startsWith("0x")) {
            numericVal = Long.parseUnsignedLong(clean.substring(2), 16);
        } else {
            numericVal = Long.parseUnsignedLong(clean, 10);
        }

        List<Integer> list = new ArrayList<>();
        list.add(9000);
        list.add(4500);

        for (int bit = bitLength - 1; bit >= 0; bit--) {
            list.add(560);
            boolean isSet = (numericVal & (1L << bit)) != 0;
            list.add(isSet ? 1690 : 560);
        }

        list.add(560);
        list.add(40000);

        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    public void transmitNecAsync(String hexCode, int bitLength, Runnable onComplete) {
        executor.execute(() -> {
            try {
                int[] pattern = encodeNec(hexCode, bitLength);
                boolean success = transmitRaw(38000, pattern);
                Log.i(TAG, "Transmitted NEC [" + hexCode + "] success=" + success);
            } catch (Exception e) {
                Log.e(TAG, "transmitNecAsync error", e);
            }
            if (onComplete != null) {
                mainHandler.post(onComplete);
            }
        });
    }

    /**
     * Sequentially transmits the IR digits of an LCN channel (e.g. "455" -> 4, 5, 5) with 120ms delays.
     */
    public void tuneChannelAsync(String lcn, Map<String, String> keyCodes, int bitLength, Runnable onComplete) {
        executor.execute(() -> {
            try {
                for (int i = 0; i < lcn.length(); i++) {
                    char digit = lcn.charAt(i);
                    String hex = keyCodes.get(String.valueOf(digit));
                    if (hex != null && !hex.isEmpty()) {
                        int[] pattern = encodeNec(hex, bitLength);
                        transmitRaw(38000, pattern);
                        Thread.sleep(120);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "tuneChannelAsync error", e);
            }
            if (onComplete != null) {
                mainHandler.post(onComplete);
            }
        });
    }
}
