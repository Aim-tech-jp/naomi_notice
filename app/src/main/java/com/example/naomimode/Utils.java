package com.example.naomimode;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.provider.Settings;

public class Utils {
    public static String getAndroidId(Context ctx) {
        return Settings.Secure.getString(
                ctx.getContentResolver(),
                Settings.Secure.ANDROID_ID
        );
    }
    public static String getPref(Context ctx, String key) {
        return ctx.getSharedPreferences("app_prefs", MODE_PRIVATE)
                .getString(key, "");
    }
}
