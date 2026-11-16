package ohi.andre.consolelauncher.managers.mock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Asks a public OSRM server for the driving route between two points, so "loc -go" can follow the roads.
 * Blocking: never call it on the main thread. The start and destination coordinates are sent to the server.
 */
public class RouteFetcher {

    private static final String URL = "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson";

    // longer routes are thinned out to keep the saved path small
    private static final int MAX_POINTS = 5000;

    public static class Route {
        public final double[] lat, lon;
        public final double distanceMeters, durationSeconds;

        Route(double[] lat, double[] lon, double distanceMeters, double durationSeconds) {
            this.lat = lat;
            this.lon = lon;
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
        }

        /** "lat,lon;lat,lon;..." as stored by MockLocationStore. */
        public String encode() {
            StringBuilder b = new StringBuilder(lat.length * 22);
            for (int i = 0; i < lat.length; i++) {
                if (i > 0) b.append(';');
                b.append(String.format(Locale.US, "%.6f,%.6f", lat[i], lon[i]));
            }
            return b.toString();
        }
    }

    /** @return null when there is no connection, no route, or the server did not answer in time */
    public static Route fetch(double fromLat, double fromLon, double toLat, double toLon) {
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(6, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .callTimeout(15, TimeUnit.SECONDS)
                    .build();

            // OSRM takes longitude first
            Request request = new Request.Builder()
                    .url(String.format(Locale.US, URL, fromLon, fromLat, toLon, toLat))
                    .header("User-Agent", "tui-launcher-loc")
                    .build();

            String body;
            try (Response response = client.newCall(request).execute()) {
                ResponseBody rb = response.body();
                if (!response.isSuccessful() || rb == null) return null;
                body = rb.string();
            }

            JSONObject json = new JSONObject(body);
            if (!"Ok".equals(json.optString("code"))) return null;

            JSONObject route = json.getJSONArray("routes").getJSONObject(0);
            JSONArray coords = route.getJSONObject("geometry").getJSONArray("coordinates");
            int n = coords.length();
            if (n < 2) return null;

            int step = Math.max(1, (int) Math.ceil(n / (double) MAX_POINTS));
            int kept = (n + step - 1) / step;

            // the route starts and ends on the nearest road: the exact start and destination are added around it
            double[] lat = new double[kept + 2], lon = new double[kept + 2];
            lat[0] = fromLat;
            lon[0] = fromLon;
            int k = 1;
            for (int i = 0; i < n; i += step) {
                JSONArray p = coords.getJSONArray(i);
                lon[k] = p.getDouble(0);
                lat[k] = p.getDouble(1);
                k++;
            }
            lat[k] = toLat;
            lon[k] = toLon;

            double duration = route.optDouble("duration", 0), distance = route.optDouble("distance", 0);
            if (duration <= 0 || distance <= 0) return null;

            return new Route(lat, lon, distance, duration);
        } catch (Throwable e) {
            return null;
        }
    }
}
