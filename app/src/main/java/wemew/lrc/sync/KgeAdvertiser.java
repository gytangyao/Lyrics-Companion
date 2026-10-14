package wemew.lrc.sync;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;

import androidx.annotation.RequiresApi;

import java.util.Locale;

/**
 * Connectable BLE advertisement announcing the K62 service, mirroring nbox's LEAdvertisement:
 * service UUID fa920001-… + tx-power, local name Wemew_Lrc_XXXX (XXXX = tail of the device
 * code). Falls back gracefully on chips without peripheral support; KitKat (API 19) has no
 * peripheral APIs at all.
 */
final class KgeAdvertiser {
    interface Listener {
        void onNotice(String what);
    }

    private final Listener listener;
    private volatile Impl impl;
    private volatile boolean advertising;

    KgeAdvertiser(Listener listener) {
        this.listener = listener;
    }

    boolean isAdvertising() {
        return advertising;
    }

    void start(Context context, String deviceId) {
        if (Build.VERSION.SDK_INT < 21) {
            listener.onNotice("Android 4.4 不支持 BLE 外设，K 歌通道不可用");
            return;
        }
        if (impl == null) impl = new Impl();
        impl.start(context, deviceId);
    }

    void stop() {
        Impl current = impl;
        if (current != null && Build.VERSION.SDK_INT >= 21) current.stop();
        advertising = false;
    }

    @RequiresApi(21)
    private class Impl {
        private BluetoothLeAdvertiser advertiser;

        void start(Context context, String deviceId) {
            stop();
            BluetoothManager manager = (BluetoothManager) context.getSystemService(
                    Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                listener.onNotice("蓝牙未开启，K 歌广播未启动");
                return;
            }
            if (!adapter.isMultipleAdvertisementSupported()) {
                listener.onNotice("此设备不支持 BLE 外设广播");
                return;
            }
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                listener.onNotice("此设备无 BLE 广播器");
                return;
            }
            String suffix = deviceId == null || deviceId.length() < 4
                    ? "0000" : deviceId.substring(deviceId.length() - 4);
            String name = String.format(Locale.US, "Wemew_Lrc_%s",
                    suffix.toUpperCase(Locale.US));

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true)
                    .setTimeout(0)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeTxPowerLevel(true)
                    .addServiceUuid(new ParcelUuid(KgeGattServer.SERVICE_UUID))
                    .build();
            // The full name needs the scan-response slot (legacy adv is 31 bytes).
            AdvertiseData scanResponse = new AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build();
            try {
                adapter.setName(name);
            } catch (Throwable ignored) {
                // API 30+ restricts renaming without system privileges; the UUID is what counts.
            }
            advertiser.startAdvertising(settings, data, scanResponse, callback);
        }

        void stop() {
            if (advertiser != null) {
                try {
                    advertiser.stopAdvertising(callback);
                } catch (Throwable ignored) { }
                advertiser = null;
            }
            advertising = false;
        }

        private final AdvertiseCallback callback = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                advertising = true;
                listener.onNotice("K 歌广播已开启（Wemew_Lrc_*）");
            }

            @Override public void onStartFailure(int errorCode) {
                advertising = false;
                listener.onNotice("K 歌广播开启失败: " + errorCode);
            }
        };
    }
}
