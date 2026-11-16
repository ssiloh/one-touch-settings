package io.github.ssiloh.onetouchsettings;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import io.github.muntashirakon.adb.android.AdbMdns;

/**
 * Pairing helper. Android only keeps the pairing code/port valid while the "Pair device with pairing code" dialog
 * is on screen, and leaving that dialog invalidates them. So this service watches for the pairing port over mDNS
 * and offers a notification with an inline text field: type the 6-digit code there while the dialog stays open.
 */
public class PairingService extends Service {
    static final String ACTION_START = "start";
    static final String ACTION_STOP = "stop";
    static final String ACTION_PAIR = "pair";
    private static final String KEY_CODE = "code";
    private static final String EXTRA_PORT = "port";
    private static final int NOTIFICATION_ID = 1001;
    private static final int RESULT_NOTIFICATION_ID = 1002;
    private static final long IDLE_TIMEOUT_MS = 10 * 60 * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private AdbController adb;
    private AdbMdns mdns;
    private volatile int port = -1;

    public static void start(Context context) {
        context.startForegroundService(new Intent(context, PairingService.class).setAction(ACTION_START));
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, PairingService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        adb = AdbController.get(this);
        Notifier.ensureChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, waitingNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        if (ACTION_PAIR.equals(action)) {
            android.os.Bundle results = RemoteInput.getResultsFromIntent(intent);
            CharSequence typed = results == null ? null : results.getCharSequence(KEY_CODE);
            String code = typed == null ? null : typed.toString();
            int targetPort = intent.getIntExtra(EXTRA_PORT, port);
            if (code == null || code.trim().length() != 6) {
                update(codeNotification("코드는 6자리 숫자예요. 다시 입력해 주세요."));
            } else {
                update(Notifier.builder(this, Notifier.CHANNEL_PAIRING, "페어링 중…", "잠시만 기다려 주세요")
                        .setOngoing(true).build());
                new Thread(() -> pair(targetPort, code.trim()), "pairing").start();
            }
        } else {
            startDiscovery();
        }
        main.removeCallbacksAndMessages(null);
        main.postDelayed(this::shutdown, IDLE_TIMEOUT_MS);
        return START_NOT_STICKY;
    }

    private void startDiscovery() {
        if (mdns != null) return;
        mdns = new AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING, (host, p) -> main.post(() -> {
            port = p;
            adb.setPairingPort(p);
            update(p > 0 ? codeNotification(null) : waitingNotification());
        }));
        mdns.start();
    }

    private void pair(int targetPort, String code) {
        try {
            if (targetPort <= 0) throw new IllegalStateException("페어링 포트를 아직 찾지 못했어요");
            adb.pairBlocking(targetPort, code);
        } catch (Exception e) {
            main.post(() -> update(codeNotification("페어링 실패: " + AdbController.describe(e)
                    + "\n코드가 맞는지 확인하고 다시 입력해 주세요.")));
            return;
        }
        try {
            adb.connectBlocking(15_000);
            result("페어링 완료", "연결까지 마쳤어요. 앱에서 명령어를 실행해 보세요.");
        } catch (Exception e) {
            result("페어링 완료", "연결은 아직이에요: " + AdbController.describe(e));
        }
        main.post(this::shutdown);
    }

    private void result(String title, String text) {
        Notifier.post(this, RESULT_NOTIFICATION_ID,
                Notifier.builder(this, Notifier.CHANNEL_PAIRING, title, text).setAutoCancel(true).build());
    }

    private void update(Notification notification) {
        Notifier.post(this, NOTIFICATION_ID, notification);
    }

    private Notification waitingNotification() {
        return Notifier.builder(this, Notifier.CHANNEL_PAIRING, "페어링 대기 중",
                        "무선 디버깅 화면에서 '페어링 코드로 기기 페어링'을 눌러 주세요. 창을 닫지 말고 이 알림으로 코드를 입력해요.")
                .setOngoing(true).build();
    }

    private Notification codeNotification(String error) {
        RemoteInput input = new RemoteInput.Builder(KEY_CODE).setLabel("페어링 코드 6자리").build();
        Intent reply = new Intent(this, PairingService.class).setAction(ACTION_PAIR).putExtra(EXTRA_PORT, port);
        PendingIntent pending = PendingIntent.getService(this, 2, reply,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action action = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat), "코드 입력", pending)
                .addRemoteInput(input)
                .build();
        String text = error != null ? error
                : "페어링 서비스를 찾았어요 (포트 " + port + "). 화면에 보이는 6자리 코드를 입력하세요.";
        return Notifier.builder(this, Notifier.CHANNEL_PAIRING, "페어링 코드를 입력하세요", text)
                .addAction(action).setOngoing(true).build();
    }

    private void shutdown() {
        main.removeCallbacksAndMessages(null);
        if (mdns != null) {
            mdns.stop();
            mdns = null;
        }
        adb.setPairingPort(-1);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (mdns != null) mdns.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
