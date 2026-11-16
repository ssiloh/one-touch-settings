package io.github.ssiloh.onetouchsettings;

import android.Manifest;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.muntashirakon.adb.AdbPairingRequiredException;

/**
 * "Just connect to Wi-Fi" mode. Whenever a Wi-Fi network becomes available it
 * <ol>
 *   <li>turns Wireless debugging on, if this app was granted WRITE_SECURE_SETTINGS (see README), and</li>
 *   <li>finds the on-device debugging port over mDNS and connects.</li>
 * </ol>
 */
public class AutoConnectService extends Service {
    /** Settings.Global.ADB_WIFI_ENABLED is hidden API, but the setting key itself is stable since Android 11. */
    private static final String ADB_WIFI_ENABLED = "adb_wifi_enabled";
    private static final int NOTIFICATION_ID = 2001;
    private static final long DEBOUNCE_MS = 1500;
    private static final int ATTEMPTS = 4;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ConnectivityManager connectivity;
    private AdbController adb;
    private ConnectivityManager.NetworkCallback callback;
    private int wifiCount;
    private volatile int generation;

    public static void start(Context context) {
        context.startForegroundService(new Intent(context, AutoConnectService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, AutoConnectService.class));
    }

    public static boolean canToggleWirelessDebugging(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        adb = AdbController.get(this);
        connectivity = getSystemService(ConnectivityManager.class);
        Notifier.ensureChannels(this);
        startForeground(NOTIFICATION_ID, status("Wi-Fi 연결을 기다리는 중", "Wi-Fi에 연결되면 자동으로 이 폰에 연결해요."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);

        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                main.post(() -> {
                    wifiCount++;
                    scheduleAttempt();
                });
            }

            @Override
            public void onLost(Network network) {
                main.post(() -> {
                    wifiCount = Math.max(0, wifiCount - 1);
                    if (wifiCount == 0) {
                        generation++;
                        worker.execute(adb::disconnectQuietly);
                        update(status("Wi-Fi 연결을 기다리는 중", "Wi-Fi가 끊겼어요."));
                    }
                });
            }
        };
        connectivity.registerNetworkCallback(
                new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void scheduleAttempt() {
        main.removeCallbacksAndMessages(null);
        int gen = ++generation;
        main.postDelayed(() -> worker.execute(() -> attempt(gen)), DEBOUNCE_MS);
    }

    private void attempt(int gen) {
        if (gen != generation) return;
        boolean toggled = false;
        if (canToggleWirelessDebugging(this)) {
            try {
                if (Settings.Global.getInt(getContentResolver(), ADB_WIFI_ENABLED, 0) != 1) {
                    Settings.Global.putInt(getContentResolver(), ADB_WIFI_ENABLED, 1);
                    toggled = true;
                }
            } catch (SecurityException ignored) {
                // permission revoked
            }
        }
        update(status("연결하는 중…", toggled ? "무선 디버깅을 켰어요." : "무선 디버깅 서비스를 찾는 중"));

        String last = "";
        for (int i = 0; i < ATTEMPTS && gen == generation; i++) {
            try {
                adb.connectBlocking(toggled && i == 0 ? 20_000 : 10_000);
                update(status("연결됨", "이 폰의 무선 디버깅에 연결했어요."));
                return;
            } catch (AdbPairingRequiredException e) {
                update(Notifier.builder(this, Notifier.CHANNEL_STATUS, "페어링이 필요해요",
                                "앱을 열어 처음 한 번만 페어링해 주세요.")
                        .setOngoing(true).build());
                return;
            } catch (Exception e) {
                last = AdbController.describe(e);
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
        if (gen != generation) return;
        boolean canToggle = canToggleWirelessDebugging(this);
        Notification.Builder b = Notifier.builder(this, Notifier.CHANNEL_STATUS, "연결하지 못했어요",
                canToggle ? last : "무선 디버깅을 켜 주세요 (탭하면 설정이 열려요).\n" + last).setOngoing(true);
        if (!canToggle) b.setContentIntent(Notifier.openApp(this, Notifier.ACTION_OPEN_WIRELESS_SETTINGS));
        update(b.build());
    }

    private Notification status(String title, String text) {
        return Notifier.builder(this, Notifier.CHANNEL_STATUS, title, text).setOngoing(true).build();
    }

    private void update(Notification notification) {
        Notifier.post(this, NOTIFICATION_ID, notification);
    }

    @Override
    public void onDestroy() {
        generation++;
        main.removeCallbacksAndMessages(null);
        if (callback != null) connectivity.unregisterNetworkCallback(callback);
        worker.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
