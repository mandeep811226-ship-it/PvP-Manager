package com.pvpmanager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * Foreground service that keeps the app process alive when in background.
 * Displays a persistent notification with a Stop action.
 */
public class PvpService extends Service {

    private static final String CHANNEL_ID = "pvp_manager_channel";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_STOP = "com.pvpmanager.ACTION_STOP";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            // Also tell the bot to stop
            if (MainActivity.gameWebView != null) {
                MainActivity.gameWebView.post(() ->
                    MainActivity.gameWebView.evaluateJavascript(
                        "if(window.__pvpmSetRunning) window.__pvpmSetRunning(false);", null));
            }
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        return START_STICKY;
    }

    private Notification buildNotification() {
        // Stop action
        Intent stopIntent = new Intent(this, PvpService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Tap to open app
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("PvP Manager")
                .setContentText("Running — tap to open")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "PvP Manager",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps PvP Manager running in background");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
