package ohi.andre.consolelauncher.managers.mock;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

                    // every source at once (Google fused, satellites, Wi-Fi/cell): the first real fix wins.
                    // GPS alone can take minutes indoors, or right after the mock is turned off
                    final List<String> sources = new ArrayList<>();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                        sources.add(LocationManager.FUSED_PROVIDER);
                    }
                    if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) sources.add(LocationManager.GPS_PROVIDER);
                    if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) sources.add(LocationManager.NETWORK_PROVIDER);

                    if (sources.isEmpty()) {
                        callback.onResult(last);
                        return;
                    }

                    final AtomicBoolean done = new AtomicBoolean(false);
                    final AtomicInteger pending = new AtomicInteger(sources.size());

                    for (String provider : sources) {
                        try {
                            // the system gives up by itself after ~30 s and delivers null
                            lm.getCurrentLocation(provider, null, executor, new java.util.function.Consumer<Location>() {
                                @Override
                                public void accept(Location location) {
                                    boolean real = location != null && !isMock(location);
                                    int left = pending.decrementAndGet();

                                    if (real) {
                                        if (done.compareAndSet(false, true)) callback.onResult(location);
                                    } else if (left == 0 && done.compareAndSet(false, true)) {
                                        callback.onResult(last);
                                    }
                                }
                            });
                        } catch (Exception e) {
                            if (pending.decrementAndGet() == 0 && done.compareAndSet(false, true)) callback.onResult(last);
                        }
                    }
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
