package ohi.andre.consolelauncher.managers.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Sets the reminder alarms again after a reboot or after the app is updated. */
public class ReminderBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;

        ReminderScheduler.rescheduleAll(context, true);
    }
}
