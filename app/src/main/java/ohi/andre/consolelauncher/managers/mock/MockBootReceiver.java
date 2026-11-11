package ohi.andre.consolelauncher.managers.mock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Resumes the mock after a reboot, if one was active.
 * A trip ("-go") interrupted by the reboot is fixed directly on its destination.
 */
public class MockBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        try {
            MockLocationStore.State s = MockLocationStore.state(context);
            if (!s.isOn()) return;

            if (s.goTarget != null) {
                MockLocationStore.Place target = MockLocationStore.place(context, s.goTarget);
                if (target == null) {
                    MockLocationStore.clear(context);
                    return;
                }
                MockLocationStore.activate(context, target);
            }

            MockLocationService.start(context);
        } catch (Exception e) {
            // nothing to show at boot; "loc -status" will tell the service is not running
        }
    }
}
