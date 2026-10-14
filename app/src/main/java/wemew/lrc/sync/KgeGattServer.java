package wemew.lrc.sync;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The KGe K62 GATT server, ported from nbox's BlueZ implementation (nbox@52f74ea). The K-song
 * app discovers this device through the SPP SDP record below (its DeviceChecker matches the
 * UUID, not the name), connects over BLE, authenticates, and then pushes lyric lines. This
 * server is fully passive: it answers auth requests and relays nothing but lyric events.
 */
final class KgeGattServer {
    private static final String TAG = "KgeGatt";
    static final UUID SERVICE_UUID = UUID.fromString("fa920001-5bc8-4f49-99b8-568ea3b52b99");
    static final UUID CHAR_DEVICE_TOKEN = UUID.fromString(
            "fa920002-5bc8-4f49-99b8-568ea3b52b99");
    static final UUID CHAR_APP_TO_DEVICE = UUID.fromString(
            "fa920003-5bc8-4f49-99b8-568ea3b52b99");
    static final UUID CHAR_DEVICE_TO_APP = UUID.fromString(
            "fa920004-5bc8-4f49-99b8-568ea3b52b99");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    /**
     * The BR/EDR artifact that actually identifies us as a K62: the app's DeviceChecker
     * requires this UUID among the device's SDP records. Registered with a dummy listening
     * socket whose accepted connections are closed immediately.
     */
    static final UUID SPP_UUID = UUID.fromString("d6ba9f8b-a941-4f73-8058-9fb94feae68d");

    /** Lyric pushes and lifecycle notices for the UI/diagnostics. */
    interface Listener {
        void onLyricEvent(String text, long startMs);

        void onNotice(String what);
    }

    private final Context context;
    private final Listener listener;
    private final KgeFrameCodec.Assembler assembler = new KgeFrameCodec.Assembler();
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile BluetoothGattServer server;
    private volatile BluetoothServerSocket sppSocket;
    private volatile Thread sppAcceptThread;
    private volatile boolean running;
    private volatile boolean notifyEnabled;
    private volatile boolean connected;
    private volatile int mtu = 23;
    /** Lyric pair assembly: 0xB5 text and 0xB4 start may arrive in either order. */
    private String pendingText = "";
    private long pendingStartMs = -1L;

    KgeGattServer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    boolean isConnected() {
        return connected;
    }

    void start() {
        stop();
        running = true;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(
                Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            listener.onNotice("蓝牙不可用，K 歌服务未启动");
            return;
        }
        registerSppRecord(adapter);
        server = manager.openGattServer(context, callback);
        if (server == null) {
            listener.onNotice("GATT 服务打开失败");
            return;
        }
        BluetoothGattCharacteristic token = new BluetoothGattCharacteristic(CHAR_DEVICE_TOKEN,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        BluetoothGattCharacteristic dataIn = new BluetoothGattCharacteristic(CHAR_APP_TO_DEVICE,
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        BluetoothGattCharacteristic dataOut = new BluetoothGattCharacteristic(CHAR_DEVICE_TO_APP,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        // Some stacks want the CCCD present even though the framework can add it implicitly.
        dataOut.addDescriptor(new BluetoothGattDescriptor(CCCD,
                BluetoothGattDescriptor.PERMISSION_WRITE));
        android.bluetooth.BluetoothGattService service =
                new android.bluetooth.BluetoothGattService(SERVICE_UUID,
                        android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(token);
        service.addCharacteristic(dataIn);
        service.addCharacteristic(dataOut);
        server.addService(service);
        listener.onNotice("K62 GATT 服务已就绪");
    }

    void stop() {
        running = false;
        connected = false;
        notifyEnabled = false;
        if (server != null) {
            try {
                server.close();
            } catch (Throwable ignored) { }
            server = null;
        }
        if (sppSocket != null) {
            try {
                sppSocket.close();
            } catch (IOException ignored) { }
            sppSocket = null;
        }
        sppAcceptThread = null;
    }

    private void registerSppRecord(BluetoothAdapter adapter) {
        try {
            sppSocket = adapter.listenUsingRfcommWithServiceRecord("KGe-SPP", SPP_UUID);
            sppAcceptThread = new Thread(() -> {
                while (running) {
                    try {
                        BluetoothSocket socket = sppSocket.accept();
                        // Presence in the SDP database is all that matters; drop links.
                        try {
                            socket.close();
                        } catch (IOException ignored) { }
                    } catch (IOException error) {
                        if (running) {
                            listener.onNotice("SPP 监听结束: " + error.getMessage());
                        }
                        return;
                    }
                }
            }, "KgeSpp");
            sppAcceptThread.start();
        } catch (Throwable error) {
            listener.onNotice("SPP 记录注册失败: " + error.getMessage());
        }
    }

    private final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {
        @Override public void onConnectionStateChange(BluetoothDevice device, int status,
                                                      int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connected = true;
                mtu = 23;
                assembler.reset();
                notifyEnabled = false;
                pendingText = "";
                pendingStartMs = -1L;
                listener.onNotice("K 歌客户端已连接: " + safeName(device));
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                connected = false;
                notifyEnabled = false;
                assembler.reset();
                listener.onNotice("K 歌客户端已断开");
            }
        }

        @Override public void onMtuChanged(BluetoothDevice device, int newMtu) {
            mtu = Math.max(23, newMtu);
        }

        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                                                          int offset,
                                                          BluetoothGattCharacteristic characteristic) {
            BluetoothGattServer current = server;
            if (current == null) return;
            byte[] value = KgeFrameCodec.DEVICE_TOKEN;
            int from = Math.max(0, Math.min(offset, value.length));
            current.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                    Arrays.copyOfRange(value, from, value.length));
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                           BluetoothGattCharacteristic characteristic,
                                                           boolean preparedWrite, boolean responseNeeded,
                                                           int offset, byte[] value) {
            BluetoothGattServer current = server;
            if (current != null && responseNeeded) {
                current.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        value == null ? new byte[0] : value);
            }
            if (!CHAR_APP_TO_DEVICE.equals(characteristic.getUuid())) return;
            KgeFrameCodec.Frame frame = KgeFrameCodec.parseFrame(value);
            if (frame == null) return;
            KgeFrameCodec.Message message = assembler.feed(frame);
            if (message == null) return;
            handleMessage(device, message);
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                                       BluetoothGattDescriptor descriptor,
                                                       boolean preparedWrite, boolean responseNeeded,
                                                       int offset, byte[] value) {
            BluetoothGattServer current = server;
            if (current == null) return;
            if (value != null && value.length >= 2 && (value[0] & 0xFF) == 0x01) {
                notifyEnabled = true;
            } else if (value != null && value.length >= 2 && (value[0] & 0xFF) == 0x00) {
                notifyEnabled = false;
            }
            if (responseNeeded) {
                current.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        value);
            }
        }
    };

    private void handleMessage(BluetoothDevice device, KgeFrameCodec.Message message) {
        if (message.commandType == KgeFrameCodec.TYPE_REQUEST) {
            if (message.commandId == KgeFrameCodec.CMD_AUTH) {
                respond(device, KgeFrameCodec.CMD_AUTH,
                        KgeFrameCodec.isDeviceInfoRequest(message.payload)
                                ? KgeFrameCodec.DEVICE_INFO_PAYLOAD
                                : KgeFrameCodec.AUTH_INFO_PAYLOAD);
                listener.onNotice(KgeFrameCodec.isDeviceInfoRequest(message.payload)
                        ? "鉴权: DeviceInfo 已应答" : "鉴权: 协议版本已应答");
            } else if (message.commandId == KgeFrameCodec.CMD_WRITE_TOKEN) {
                respond(device, KgeFrameCodec.CMD_WRITE_TOKEN, new byte[0]);
                listener.onNotice("鉴权: 0xC0 已应答");
            } else if (message.commandId == KgeFrameCodec.CMD_TOKEN_UPLOAD) {
                respond(device, KgeFrameCodec.CMD_TOKEN_UPLOAD, KgeFrameCodec.ACK_SUCCESS);
                listener.onNotice("鉴权: token 上传已确认（腾讯侧是否放行待观察）");
            } else if (message.commandId == KgeFrameCodec.CMD_STATE_SYNC) {
                respond(device, KgeFrameCodec.CMD_STATE_SYNC, KgeFrameCodec.ACK_SUCCESS);
            } else {
                Log.d(TAG, "unanswered request cmd=0x" + Integer.toHexString(message.commandId));
            }
            return;
        }
        if (message.commandType == KgeFrameCodec.TYPE_NOTIFY) {
            if (message.commandId == KgeFrameCodec.CMD_LYRIC_LINE_TITLE) {
                pendingText = KgeFrameCodec.decodeLyricText(message.payload);
                maybeEmitLyric();
            } else if (message.commandId == KgeFrameCodec.CMD_LYRIC_LINE_STATE) {
                long start = KgeFrameCodec.decodeLyricStartMs(message.payload);
                if (start >= 0L) {
                    pendingStartMs = start;
                    maybeEmitLyric();
                }
            }
        }
    }

    private void maybeEmitLyric() {
        if (pendingText.isEmpty() || pendingStartMs < 0L) return;
        listener.onLyricEvent(pendingText, pendingStartMs);
        // The pair repeats per line; reset so the next line needs both fresh halves.
        pendingText = "";
        pendingStartMs = -1L;
    }

    private void respond(BluetoothDevice device, int commandId, byte[] payload) {
        BluetoothGattServer current = server;
        if (current == null || device == null) return;
        if (!notifyEnabled) {
            listener.onNotice("响应 0x" + Integer.toHexString(commandId)
                    + " 被丢弃：notify 未启用");
            return;
        }
        for (byte[] frame : KgeFrameCodec.encode(commandId, KgeFrameCodec.TYPE_RESPONSE,
                payload, sequence.getAndIncrement())) {
            try {
                notifyFrame(current, device, frame);
            } catch (Throwable error) {
                Log.w(TAG, "respond failed", error);
            }
        }
    }

    private void notifyFrame(BluetoothGattServer server, BluetoothDevice device, byte[] frame) {
        BluetoothGattCharacteristic out = findOutCharacteristic(server);
        if (out == null) return;
        out.setValue(frame);
        boolean sent;
        if (Build.VERSION.SDK_INT >= 33) {
            sent = server.notifyCharacteristicChanged(device, out, false, frame)
                    == BluetoothStatusCodes.SUCCESS;
        } else {
            sent = server.notifyCharacteristicChanged(device, out, false);
        }
        if (!sent) {
            Log.w(TAG, "notify failed (mtu=" + mtu + " len=" + frame.length + ")");
        }
    }

    private BluetoothGattCharacteristic findOutCharacteristic(BluetoothGattServer server) {
        for (android.bluetooth.BluetoothGattService service : server.getServices()) {
            if (!SERVICE_UUID.equals(service.getUuid())) continue;
            return service.getCharacteristic(CHAR_DEVICE_TO_APP);
        }
        return null;
    }

    private String safeName(BluetoothDevice device) {
        try {
            return device.getName() == null ? device.getAddress() : device.getName();
        } catch (Throwable security) {
            return device.getAddress();
        }
    }
}
