package com.example.naomimode;

import android.content.Context;
import android.provider.Settings;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class Utils {
    public static String getAndroidId(Context ctx) {
        try {
            return Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception e) {
            return "androidid";
        }
    }
    public static long parseIsoToMillis(String iso) {
        String t = iso;
        int dot = t.indexOf('.');
        if (dot > 0) {
            int end = Math.min(dot + 4, t.length());
            t = t.substring(0, end);
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            Date d = sdf.parse(t);
            return d != null ? d.getTime() : System.currentTimeMillis();
        } catch (ParseException e) {
            return System.currentTimeMillis();
        }
    }

    public static String formatLocalTime(long tsMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN);
        sdf.setTimeZone(TimeZone.getDefault());
        return sdf.format(new Date(tsMs));
    }
}
