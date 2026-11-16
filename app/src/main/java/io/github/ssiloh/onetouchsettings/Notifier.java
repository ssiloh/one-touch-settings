package io.github.ssiloh.onetouchsettings;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

final class Notifier {
    static final String CHANNEL_PAIRING = "pairing";
    static final String CHANNEL_STATUS = "status";
    static final String ACTION_OPEN_WIRELESS_SETTINGS = "io.github.ssiloh.onetouchsettings.OPEN_WIRELESS_SETTINGS";

    private Notifier() {}

    static void ensureChannels(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        NotificationChannel pairing = new NotificationChannel(CHANNEL_PAIRING, "페어링 코드 입력",
                NotificationManager.IMPORTANCE_HIGH);
        pairing.setDescription("무선 디버깅 페어링 코드를 알림에서 바로 입력해요");
        NotificationChannel status = new NotificationChannel(CHANNEL_STATUS, "연결 상태",
                NotificationManager.IMPORTANCE_LOW);
        status.setDescription("Wi-Fi 자동 연결 상태");
        nm.createNotificationChannel(pairing);
        nm.createNotificationChannel(status);
    }

    static PendingIntent openApp(Context context, String action) {
        Intent intent = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (action != null) intent.setAction(action);
        return PendingIntent.getActivity(context, action == null ? 0 : 1, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static Notification.Builder builder(Context context, String channel, String title, String text) {
        return new Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp(context, null));
    }

    static void post(Context context, int id, Notification notification) {
        context.getSystemService(NotificationManager.class).notify(id, notification);
    }
}
