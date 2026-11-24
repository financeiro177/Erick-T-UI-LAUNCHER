package ohi.andre.consolelauncher.managers.reminder;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.AlarmManagerCompat;

import java.util.List;

import ohi.andre.consolelauncher.tuils.Tuils;

/** One alarm per reminder (the reminder id is the request code). */
public class ReminderScheduler {

    private static PendingIntent pending(Context c, int id) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.setAction(ReminderReceiver.ACTION_FIRE);
        i.putExtra(ReminderReceiver.EXTRA_ID, id);
        return PendingIntent.getBroadcast(c, id, i, Tuils.pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT));
    }

    public static boolean canBeExact(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            return am != null && am.canScheduleExactAlarms();
        } catch (Exception e) {
            return false;
        }
    }

    public static void schedule(Context c, ReminderStore.Reminder r) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            // a time already gone (for example after a reboot) fires in a couple of seconds
            long when = Math.max(r.when, System.currentTimeMillis() + 2000);
            PendingIntent pi = pending(c, r.id);

            try {
                if (canBeExact(c)) AlarmManagerCompat.setExactAndAllowWhileIdle(am, AlarmManager.RTC_WAKEUP, when, pi);
                else AlarmManagerCompat.setAndAllowWhileIdle(am, AlarmManager.RTC_WAKEUP, when, pi);
            } catch (SecurityException e) {
                AlarmManagerCompat.setAndAllowWhileIdle(am, AlarmManager.RTC_WAKEUP, when, pi);
            }
        } catch (Exception ignored) {}
    }

    public static void cancel(Context c, int id) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pending(c, id));
        } catch (Exception ignored) {}
    }

    /**
     * Sets the alarms again (reboot, update, or the app opening, since "force stop" deletes alarms).
     * Reminders already overdue are only included at boot; with the app open the card shows them.
     */
    public static void rescheduleAll(Context c, boolean includeOverdue) {
        try {
            long now = System.currentTimeMillis();
            List<ReminderStore.Reminder> list = ReminderStore.all(c);
            for (ReminderStore.Reminder r : list) {
                if (includeOverdue || r.when > now) schedule(c, r);
            }
        } catch (Exception ignored) {}
    }
}
