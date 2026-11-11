package ohi.andre.consolelauncher.managers.mock;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Persistence of saved places and of the active mock state.
 * Everything lives in one SharedPreferences file; places are a JSON object {name: {lat, lon, alt}}.
 */
public class MockLocationStore {

    private static final String PREFS = "mock_location";

    private static final String PLACES = "places";
    private static final String ACTIVE = "active";
    private static final String CUR_LAT = "cur_lat", CUR_LON = "cur_lon", CUR_ALT = "cur_alt";
    private static final String GO_TARGET = "go_target", GO_SLAT = "go_slat", GO_SLON = "go_slon",
            GO_START = "go_start", GO_DURATION = "go_duration";

    public static class Place {
        public final String name;
        public final double lat, lon, alt;

        public Place(String name, double lat, double lon, double alt) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
        }
    }

    /** Snapshot of what the service has to emit. */
    public static class State {
        public String active;       // place fixed right now (null while travelling or when off)
        public boolean hasPosition; // false = nothing was ever emitted
        public double lat, lon, alt;

        public String goTarget;     // not null while a "-go" is running
        public double goStartLat, goStartLon;
        public long goStart, goDuration;

        public boolean isOn() {
            return active != null || goTarget != null;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void putDouble(SharedPreferences.Editor e, String key, double v) {
        e.putLong(key, Double.doubleToRawLongBits(v));
    }

    private static double getDouble(SharedPreferences p, String key) {
        return Double.longBitsToDouble(p.getLong(key, Double.doubleToRawLongBits(0)));
    }

//    places

    private static JSONObject placesJson(Context c) {
        try {
            return new JSONObject(prefs(c).getString(PLACES, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static List<Place> places(Context c) {
        JSONObject all = placesJson(c);
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) names.add(it.next());
        Collections.sort(names);

        List<Place> out = new ArrayList<>();
        for (String n : names) {
            Place p = toPlace(n, all.optJSONObject(n));
            if (p != null) out.add(p);
        }
        return out;
    }

    public static Place place(Context c, String name) {
        if (name == null) return null;
        return toPlace(name, placesJson(c).optJSONObject(name));
    }

    private static Place toPlace(String name, JSONObject o) {
        if (o == null || !o.has("lat") || !o.has("lon")) return null;
        return new Place(name, o.optDouble("lat"), o.optDouble("lon"), o.optDouble("alt", 0));
    }

    public static boolean save(Context c, Place p) {
        try {
            JSONObject all = placesJson(c);
            JSONObject o = new JSONObject();
            o.put("lat", p.lat);
            o.put("lon", p.lon);
            o.put("alt", p.alt);
            all.put(p.name, o);
            prefs(c).edit().putString(PLACES, all.toString()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean remove(Context c, String name) {
        JSONObject all = placesJson(c);
        if (!all.has(name)) return false;
        all.remove(name);
        prefs(c).edit().putString(PLACES, all.toString()).apply();
        return true;
    }

//    state

    public static State state(Context c) {
        SharedPreferences p = prefs(c);
        State s = new State();
        s.active = p.getString(ACTIVE, null);
        s.hasPosition = p.contains(CUR_LAT) && p.contains(CUR_LON);
        s.lat = getDouble(p, CUR_LAT);
        s.lon = getDouble(p, CUR_LON);
        s.alt = getDouble(p, CUR_ALT);
        s.goTarget = p.getString(GO_TARGET, null);
        s.goStartLat = getDouble(p, GO_SLAT);
        s.goStartLon = getDouble(p, GO_SLON);
        s.goStart = p.getLong(GO_START, 0);
        s.goDuration = p.getLong(GO_DURATION, 0);
        return s;
    }

    /** Fix the mock on a saved place (cancels any trip). */
    public static void activate(Context c, Place p) {
        SharedPreferences.Editor e = prefs(c).edit();
        e.putString(ACTIVE, p.name);
        putDouble(e, CUR_LAT, p.lat);
        putDouble(e, CUR_LON, p.lon);
        putDouble(e, CUR_ALT, p.alt);
        clearGo(e);
        e.apply();
    }

    /** Start a gradual trip from (startLat, startLon) to the target place. */
    public static void startGo(Context c, Place target, double startLat, double startLon, double startAlt, long durationMs) {
        SharedPreferences.Editor e = prefs(c).edit();
        e.remove(ACTIVE);
        putDouble(e, CUR_LAT, startLat);
        putDouble(e, CUR_LON, startLon);
        putDouble(e, CUR_ALT, startAlt);
        e.putString(GO_TARGET, target.name);
        putDouble(e, GO_SLAT, startLat);
        putDouble(e, GO_SLON, startLon);
        e.putLong(GO_START, System.currentTimeMillis());
        e.putLong(GO_DURATION, Math.max(1000, durationMs));
        e.apply();
    }

    /** Called by the service on every tick so "-go" and reboots resume from the last emitted point. */
    public static void updatePosition(Context c, double lat, double lon) {
        SharedPreferences.Editor e = prefs(c).edit();
        putDouble(e, CUR_LAT, lat);
        putDouble(e, CUR_LON, lon);
        e.apply();
    }

    /** Mock off: forget active place, trip and last position. */
    public static void clear(Context c) {
        SharedPreferences.Editor e = prefs(c).edit();
        e.remove(ACTIVE).remove(CUR_LAT).remove(CUR_LON).remove(CUR_ALT);
        clearGo(e);
        e.apply();
    }

    private static void clearGo(SharedPreferences.Editor e) {
        e.remove(GO_TARGET).remove(GO_SLAT).remove(GO_SLON).remove(GO_START).remove(GO_DURATION);
    }
}
