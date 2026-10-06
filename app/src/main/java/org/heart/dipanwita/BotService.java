package org.heart.dipanwita;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

/** Foreground service: keeps the Python bot + local server alive in background. */
public class BotService extends Service {
    private static boolean started = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("bot_fg", "Background", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "bot_fg")
                .setContentTitle(getString(R.string.fg_title))
                .setContentText(getString(R.string.fg_text))
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build();
        startForeground(1, n);

        synchronized (BotService.class) {
            if (!started) {
                started = true;
                Notifier.init(getApplicationContext());
                final String priv = getFilesDir().getAbsolutePath();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if (!Python.isStarted()) {
                            Python.start(new AndroidPlatform(getApplicationContext()));
                        }
                        Python.getInstance().getModule("service").callAttr("run", priv);
                    }
                }, "python-bot").start();
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
