package ohi.andre.consolelauncher.managers.reminder;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import ohi.andre.consolelauncher.LauncherActivity;
import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.tuils.Tuils;

/**
 * The alarm of a reminder went off. With the T-Lau on screen the card appears right away; otherwise a
 * notification is posted, and tapping it opens the T-Lau with the card.
 */
public class ReminderReceiver extends BroadcastReceiver {

    public static final String ACTION_FIRE = "ohi.andre.consolelauncher.REMINDER_FIRE";
    public static final String EXTRA_ID = "id";

    private static final String CHANNEL = "tui_lembretes";
    private static final int NOTIFICATION_BASE = 41000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_FIRE.equals(intent.getAction())) return;

        try {
            int id = intent.getIntExtra(EXTRA_ID, -1);
            ReminderStore.Reminder r = ReminderStore.get(context, id);
            if (r == null) return;

            if (!ReminderCard.onDue(context)) notifyDue(context, r);
        } catch (Exception ignored) {}
    }

    public static void notifyDue(Context c, ReminderStore.Reminder r) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Lembretes", NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
            }

            Intent open = new Intent(c, LauncherActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, r.id, open, Tuils.pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT));

            NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Lembrete " + ReminderStore.hour(r.when))
                    .setContentText(r.title)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(r.body.length() > 0 ? r.title + "\n" + r.body : r.title))
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .setContentIntent(pi);

            nm.notify(NOTIFICATION_BASE + r.id, b.build());
        } catch (Exception ignored) {}
    }

    public static void cancelNotification(Context c, int id) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_BASE + id);
        } catch (Exception ignored) {}
    }
}
