package ohi.andre.consolelauncher.managers.mock;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.Random;

import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.managers.TerminalManager;
import ohi.andre.consolelauncher.tuils.Tuils;

/**
 * Foreground service that feeds the GPS and NETWORK test providers every few seconds.
 * It owns no state: on every tick it reads MockLocationStore, so the commands only have to
 * write the store and make sure the service is started.
 */
public class MockLocationService extends Service {

    public static final int CYAN = Color.CYAN;
    public static final String MSG_SELECT_APP = "Selecione este app como localização fictícia nas Opções do desenvolvedor";

    private static final String CHANNEL_ID = "mock_location";
    private static final int NOTIFICATION_ID = 7341;

    private static final long TICK_MS = 2500;
    private static final double JITTER_METERS = 2;
    private static final double METERS_PER_DEGREE = 111320;

    private static final String[] PROVIDERS = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};

    public static volatile boolean running = false;

    private HandlerThread thread;
    private Handler handler;
    private LocationManager locationManager;
    private final Random random = new Random();

    private boolean providersReady = false;
    private float accuracy = 8f;
    private float lastBearing = 0f;

    public static void start(Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, MockLocationService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, MockLocationService.class));
    }

    /** True when this app is the one chosen in Developer options > Select mock location app. */
    public static boolean isMockAppSelected(Context context) {
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
            return ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.getPackageName())
                    == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Prints the "select this app" message; tapping the line opens Developer options. */
    public static void sendSelectAppMessage(Context context) {
        try {
            Intent settings = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(context, 0, settings, Tuils.pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT));
            Tuils.sendOutput(CYAN, context, MSG_SELECT_APP + " (toque aqui)", TerminalManager.CATEGORY_OUTPUT, pi);
        } catch (Exception e) {
            Tuils.sendOutput(CYAN, context, MSG_SELECT_APP);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Exception e) {
            say("loc: não foi possível iniciar o serviço");
            stopSelf();
            return;
        }

        thread = new HandlerThread("mock-location");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // sticky: if the system kills the process, it is recreated and reads the state from the store
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;

        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler.post(new Runnable() {
                @Override
                public void run() {
                    removeProviders();
                }
            });
        }
        if (thread != null) thread.quitSafely();

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                if (!emit()) {
                    stopSelf();
                    return;
                }
            } catch (SecurityException e) {
                // the launcher is not the selected mock location app
                MockLocationStore.clear(MockLocationService.this);
                sendSelectAppMessage(MockLocationService.this);
                stopSelf();
                return;
            } catch (Exception e) {
                // provider vanished or similar: register again on the next tick
                providersReady = false;
            }

            if (running && handler != null) handler.postDelayed(this, TICK_MS + random.nextInt(500));
        }
    };

    /** @return false when there is nothing left to mock and the service should stop */
    private boolean emit() {
        if (locationManager == null) return false;

        MockLocationStore.State s = MockLocationStore.state(this);
        if (!s.isOn()) return false;

        double lat = s.lat, lon = s.lon, alt = s.alt;
        float speed = 0f;

        if (s.goTarget != null) {
            MockLocationStore.Place target = MockLocationStore.place(this, s.goTarget);
            if (target == null) {
                MockLocationStore.clear(this);
                say("loc: destino removido, mock desligado");
                return false;
            }

            double f = (System.currentTimeMillis() - s.goStart) / (double) s.goDuration;
            if (f >= 1 || f < 0) {
                // arrived (f < 0 means the clock changed: do not travel forever)
                MockLocationStore.activate(this, target);
                lat = target.lat;
                lon = target.lon;
                alt = target.alt;
                say("loc: chegou em " + target.name);
            } else {
                lat = s.goStartLat + (target.lat - s.goStartLat) * f;
                lon = s.goStartLon + (target.lon - s.goStartLon) * f;
                alt = target.alt;

                float[] r = new float[2];
                Location.distanceBetween(s.goStartLat, s.goStartLon, target.lat, target.lon, r);
                speed = (float) (r[0] / (s.goDuration / 1000d));
                lastBearing = (r[1] + 360f) % 360f;

                MockLocationStore.updatePosition(this, lat, lon);
            }
        }

        if (!providersReady) {
            addProviders();
            providersReady = true;
        }

        // small random walk of the reported accuracy inside 5..15 m
        accuracy += (random.nextFloat() - 0.5f) * 2f;
        accuracy = Math.max(5f, Math.min(15f, accuracy));

        // +-2 m of jitter around the exact point
        double jLat = lat + ((random.nextDouble() * 2 - 1) * JITTER_METERS) / METERS_PER_DEGREE;
        double cos = Math.max(0.01, Math.cos(Math.toRadians(lat)));
        double jLon = lon + ((random.nextDouble() * 2 - 1) * JITTER_METERS) / (METERS_PER_DEGREE * cos);
        double jAlt = alt + (random.nextDouble() * 2 - 1);

        for (String provider : PROVIDERS) {
            Location l = new Location(provider);
            l.setLatitude(jLat);
            l.setLongitude(jLon);
            l.setAltitude(jAlt);
            l.setAccuracy(accuracy);
            l.setSpeed(speed);
            l.setBearing(lastBearing);
            l.setTime(System.currentTimeMillis());
            l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                l.setVerticalAccuracyMeters(accuracy * 1.5f);
                l.setSpeedAccuracyMetersPerSecond(0.5f);
                l.setBearingAccuracyDegrees(10f);
            }
            locationManager.setTestProviderLocation(provider, l);
        }

        return true;
    }

    private void addProviders() {
        for (String provider : PROVIDERS) {
            // a leftover provider from a killed process would make addTestProvider fail
            try {
                locationManager.removeTestProvider(provider);
            } catch (SecurityException e) {
                throw e;
            } catch (Exception ignored) {}

            boolean gps = LocationManager.GPS_PROVIDER.equals(provider);
            // requiresNetwork, requiresSatellite, requiresCell, hasMonetaryCost,
            // supportsAltitude, supportsSpeed, supportsBearing, powerRequirement (1 = low), accuracy (1 = fine)
            locationManager.addTestProvider(provider, !gps, gps, false, false, true, true, true, 1, 1);
            locationManager.setTestProviderEnabled(provider, true);
        }
    }

    private void removeProviders() {
        if (locationManager == null) return;
        for (String provider : PROVIDERS) {
            try {
                locationManager.setTestProviderEnabled(provider, false);
            } catch (Exception ignored) {}
            try {
                locationManager.removeTestProvider(provider);
            } catch (Exception ignored) {}
        }
        providersReady = false;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "loc", NotificationManager.IMPORTANCE_MIN);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("loc")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private void say(String text) {
        try {
            Tuils.sendOutput(CYAN, this, text);
        } catch (Exception ignored) {}
    }
}
