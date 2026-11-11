package ohi.andre.consolelauncher.managers.mock;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Reads the real (non mocked) position of the device, never on the main thread.
 */
public class RealLocation {

    public interface Callback {
        /** @param location null when no real position could be obtained */
        void onResult(Location location);
    }

    private static final long FRESH_MS = 2 * 60 * 1000;
    private static final Executor executor = Executors.newSingleThreadExecutor();

    public static boolean hasPermission(Context c) {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    public static void get(final Context context, final Callback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                    if (lm == null || !hasPermission(context)) {
                        callback.onResult(null);
                        return;
                    }

                    final Location last = lastKnown(lm);
                    boolean fresh = last != null && System.currentTimeMillis() - last.getTime() < FRESH_MS;

                    if (fresh || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        callback.onResult(last);
                        return;
                    }

                    String provider = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER
                            : lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : null;
                    if (provider == null) {
                        callback.onResult(last);
                        return;
                    }

                    // the system gives up by itself after ~30 s and delivers null
                    lm.getCurrentLocation(provider, null, executor, new java.util.function.Consumer<Location>() {
                        @Override
                        public void accept(Location location) {
                            callback.onResult(location != null && !isMock(location) ? location : last);
                        }
                    });
                } catch (Exception e) {
                    callback.onResult(null);
                }
            }
        });
    }

    @SuppressLint("MissingPermission")
    private static Location lastKnown(LocationManager lm) {
        Location best = null;
        List<String> providers = lm.getProviders(true);
        if (providers == null) return null;

        for (String p : providers) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l == null || isMock(l)) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            } catch (Exception ignored) {}
        }
        return best;
    }

    private static boolean isMock(Location l) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return l.isMock();
        return l.isFromMockProvider();
    }
}
