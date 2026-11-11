package ohi.andre.consolelauncher.managers.mock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Resumes the mock after a reboot or after the app is updated, if one was active.
 * A trip ("-go") interrupted by a reboot is fixed directly on its destination; after an update it
 * simply continues.
 */
public class MockBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction());
        if (!boot && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;

        try {
            MockLocationStore.State s = MockLocationStore.state(context);
            if (!s.isOn()) return;

            if (boot && s.goTarget != null) {
                MockLocationStore.Place target = MockLocationStore.place(context, s.goTarget);
                if (target == null) {
                    MockLocationStore.clear(context);
                    return;
                }
                MockLocationStore.activate(context, target);
            }

            MockLocationService.ensureRunning(context);
        } catch (Exception e) {
            // nothing to show at boot; "loc -status" will tell the service is not running
        }
    }
}
