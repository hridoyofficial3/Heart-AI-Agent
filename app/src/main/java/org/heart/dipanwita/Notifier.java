package org.heart.dipanwita;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** Called from Python (engine.py) to show a message notification. */
public class Notifier {
    private static Context ctx;

    public static void init(Context c) {
        ctx = c.getApplicationContext();
    }

    public static void show(String title, String text) {
        if (ctx == null) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("msgs", "Messages", NotificationManager.IMPORTANCE_DEFAULT));
        Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, launch, PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, "msgs")
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .build();
        nm.notify((int) (System.currentTimeMillis() % 100000) + 10, n);
    }
}
