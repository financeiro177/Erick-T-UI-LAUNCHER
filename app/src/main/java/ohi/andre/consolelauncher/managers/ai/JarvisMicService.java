package ohi.andre.consolelauncher.managers.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.tuils.Tuils;

/**
 * Android only lets an app hear the microphone away from its own screen while it shows a permanent notification.
 * This service is that notification: it runs while the mini Jarvis may be used over other apps, and it does
 * nothing else. The microphone itself is still only on after a tap on the bubble.
 */
public class JarvisMicService extends Service {

    private static final String CHANNEL = "jarvis_mic";
    private static final int ID = 43001;

    public static volatile boolean running;

    public static void start(Context c) {
        try {
            ContextCompat.startForegroundService(c, new Intent(c, JarvisMicService.class));
        } catch (Exception ignored) {}
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, JarvisMicService.class));
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Jarvis ligado", NotificationManager.IMPORTANCE_LOW));
            }

            Intent open = new Intent(this, JarvisActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, Tuils.pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT));

            Notification n = new NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Jarvis ligado")
                    .setContentText("O microfone só liga quando você toca na bolinha. Toque aqui para abrir a tela dele.")
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentIntent(pi)
                    .build();

            if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(ID, n);
            running = true;
        } catch (Exception e) {
            running = false;
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
