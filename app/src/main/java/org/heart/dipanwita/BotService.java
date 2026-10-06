package org.heart.dipanwita;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

/** Foreground service: keeps the Python bot + local server alive in background. */
public class BotService extends Service {
    public static final String ACTION_STOP = "org.heart.dipanwita.STOP";
    private static boolean started = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // "বন্ধ করো": সার্ভিস থামিয়ে পুরো প্রসেস (Python সহ) শেষ করি
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() { Process.killProcess(Process.myPid()); }
            }, 400);
            return START_NOT_STICKY;
        }

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("bot_fg", "Background", NotificationManager.IMPORTANCE_LOW));

        Intent stop = new Intent(this, BotService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action stopAct = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_launcher), getString(R.string.fg_stop), stopPi).build();

        Notification.Builder nb = new Notification.Builder(this, "bot_fg")
                .setContentTitle(getString(R.string.fg_title))
                .setContentText(getString(R.string.fg_text))
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .addAction(stopAct);
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            nb.setContentIntent(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE));
        }
        startForeground(1, nb.build());

        synchronized (BotService.class) {
            if (!started) {
                started = true;
                Notifier.init(getApplicationContext());
                final String priv = getFilesDir().getAbsolutePath();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!Python.isStarted()) {
                                Python.start(new AndroidPlatform(getApplicationContext()));
                            }
                            Python.getInstance().getModule("service").callAttr("run", priv);
                        } catch (Throwable t) {
                            t.printStackTrace();
                            synchronized (BotService.class) { started = false; }   // আবার চেষ্টা করা যাবে
                        }
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
