package ohi.andre.consolelauncher.managers.mock;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.LocationSettingsStates;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;

import ohi.andre.consolelauncher.tuils.Tuils;

/**
 * Google Location Accuracy (Wi-Fi/cell positioning). Only the user may switch it, so the "loc" command takes
 * them to the right place: on when the real position must come back, off while a place is being mocked.
 */
public class LocationAccuracy {

    // any value different from the other request codes of LauncherActivity
    private static final int REQUEST_CODE = 7342;

    // the screen changed name across Play services versions; the first one that opens is used
    private static final String GMS = "com.google.android.gms";
    private static final String[] SCREENS = {
            "com.google.android.gms.location.settings.LocationAccuracyV31Activity",
            "com.google.android.gms.location.settings.LocationAccuracyNonwearableActivity",
            "com.google.android.gms.location.settings.LocationAccuracyActivity",
    };

    private static LocationSettingsRequest request() {
        return new LocationSettingsRequest.Builder()
                .addLocationRequest(new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build())
                .setAlwaysShow(true)
                .build();
    }

    /** "-reset": if the location or Google Location Accuracy is off, shows Google's own dialog to turn them on. */
    public static void askToTurnOn(final Context context) {
        try {
            LocationServices.getSettingsClient(context).checkLocationSettings(request())
                    .addOnSuccessListener(new OnSuccessListener<LocationSettingsResponse>() {
                        @Override
                        public void onSuccess(LocationSettingsResponse response) {
                            // everything is already on: still takes the user there to confirm
                            say(context, "loc: confira se a Precisão de local do Google está ligada para a posição real voltar");
                            openScreen(context);
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(Exception e) {
                            say(context, "loc: ative a Localização e a Precisão de local do Google para a posição real voltar");

                            if (e instanceof ResolvableApiException && context instanceof Activity) {
                                try {
                                    ((ResolvableApiException) e).startResolutionForResult((Activity) context, REQUEST_CODE);
                                    return;
                                } catch (Exception ignored) {}
                            }
                            openScreen(context);
                        }
                    });
        } catch (Throwable ignored) {}
    }

    /** "-use", "-at", "-go": always takes the user to Google Location Accuracy so it can be turned off. */
    public static void askToTurnOff(final Context context) {
        try {
            say(context, "loc: desative a Precisão de local do Google para a posição real não escapar");
            openScreen(context);
        } catch (Throwable ignored) {}
    }

    private static void openScreen(Context context) {
        // public action of Google Play services settings (works where the activity itself is not exported)
        try {
            Intent g = new Intent("com.google.android.gms.location.settings.GOOGLE_LOCATION_SETTINGS");
            g.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(g);
            return;
        } catch (Exception ignored) {}

        for (String screen : SCREENS) {
            try {
                Intent i = new Intent().setComponent(new ComponentName(GMS, screen));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
                return;
            } catch (Exception ignored) {}
        }

        // the system location screen: Location services > Google Location Accuracy
        try {
            Intent i = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception ignored) {}
    }

    private static void say(Context context, String text) {
        try {
            Tuils.sendOutput(MockLocationService.CYAN, context, text);
        } catch (Exception ignored) {}
    }
}
