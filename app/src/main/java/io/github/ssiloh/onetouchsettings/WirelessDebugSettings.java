package io.github.ssiloh.onetouchsettings;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;

/**
 * Jumps straight to the "Wireless debugging" screen in system settings.
 * <p>
 * There is no public intent for that screen, so we try progressively more generic targets:
 * <ol>
 *   <li>the AOSP {@code Settings$WirelessDebuggingActivity} alias (Pixel/AOSP-like builds),</li>
 *   <li>Developer options with the {@code toggle_adb_wireless} preference key highlighted
 *       (the approach Shizuku uses; works on One UI),</li>
 *   <li>plain Developer options.</li>
 * </ol>
 */
public final class WirelessDebugSettings {
    private WirelessDebugSettings() {}

    public enum Result { OPENED_WIRELESS, OPENED_DEVELOPER_OPTIONS, DEVELOPER_OPTIONS_DISABLED, FAILED }

    public static boolean isDeveloperOptionsEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
    }

    public static boolean isWifiConnected(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return true;
            }
        }
        return false;
    }

    public static Result open(Activity activity) {
        if (!isDeveloperOptionsEnabled(activity)) {
            return Result.DEVELOPER_OPTIONS_DISABLED;
        }

        Intent direct = new Intent()
                .setClassName("com.android.settings", "com.android.settings.Settings$WirelessDebuggingActivity");
        if (start(activity, direct)) return Result.OPENED_WIRELESS;

        Intent highlighted = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless");
        if (start(activity, highlighted)) return Result.OPENED_DEVELOPER_OPTIONS;

        if (start(activity, new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))) {
            return Result.OPENED_DEVELOPER_OPTIONS;
        }
        return Result.FAILED;
    }

    /** About-phone screen, where tapping "Build number" 7 times turns Developer options on. */
    public static boolean openDeviceInfo(Activity activity) {
        return start(activity, new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
    }

    private static boolean start(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
