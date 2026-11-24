package ohi.andre.consolelauncher.managers.ai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The alarm of a time trigger of Jarvis went off. */
public class JarvisReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !JarvisTriggers.ACTION_FIRE.equals(intent.getAction())) return;
        try {
            JarvisTriggers.fired(context.getApplicationContext(), intent.getIntExtra(JarvisTriggers.EXTRA_ID, -1));
        } catch (Exception ignored) {}
    }
}
