package io.github.ssiloh.onetouchsettings;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private static final String NAME = "prefs";
    private static final String AUTO_CONNECT = "auto_connect";

    private Prefs() {}

    private static SharedPreferences sp(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static boolean isAutoConnect(Context context) {
        return sp(context).getBoolean(AUTO_CONNECT, false);
    }

    static void setAutoConnect(Context context, boolean value) {
        sp(context).edit().putBoolean(AUTO_CONNECT, value).apply();
    }
}
