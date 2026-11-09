package com.mario.assistant.infrastructure.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

/** 试运行前台服务的通知渠道与状态通知。 */
public final class StatusNotifier {
    public static final int NOTIFICATION_ID = 42;
    private static final String CHANNEL_STATUS = "run_status";

    private final Context context;

    public StatusNotifier(Context context) {
        this.context = context;
    }

    public void ensureChannel() {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_STATUS) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_STATUS, "试运行状态", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("任务试运行期间的实时状态");
            nm.createNotificationChannel(channel);
        }
    }

    public Notification build(String text) {
        return new Notification.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Mario 任务助手")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    public void update(String text) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, build(text));
    }
}
