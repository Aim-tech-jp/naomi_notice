package com.example.naomimode;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

public class EventDedupManager {
    private static final String TAG = "EventDedupManager";
    private static final String PREF_NAME = "dedup_prefs";
    private static final String KEY_EMERGENCY_TIME = "emergency_home_time_";
    private static final String KEY_ROOM_TIME = "room_time_";

    private static EventDedupManager instance;
    private final SharedPreferences sharedPreferences;

    private final Map<String, Long> emergencyLastTimeMap = new HashMap<>();

     private final Map<String, Long> arriveLeaveLastTimeMap = new HashMap<>();

    private static final long DUPLICATE_INTERVAL_MS = 2000L;

    private EventDedupManager(Context context) {
        Log.d(TAG, "EventDedupManager initialized");
        this.sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized EventDedupManager getInstance(Context context) {
        if (instance == null) {
            instance = new EventDedupManager(context.getApplicationContext());
        }
        return instance;
    }

    public synchronized boolean isDuplicate(String eventType, String pointinfo, long createTime) {
        long lastTime;
        long now = System.currentTimeMillis();
        if (EMERGENCY.equals(eventType)) {
            lastTime = emergencyLastTimeMap.getOrDefault("home", sharedPreferences.getLong(KEY_EMERGENCY_TIME + "home", 0L));
            if (createTime <= lastTime) {
                Log.w(TAG, "EMERGENCY 重复跳过: time=" + createTime + " <= " + lastTime);
                return true;
            }
            emergencyLastTimeMap.put("home", createTime);
            sharedPreferences.edit().putLong(KEY_EMERGENCY_TIME + "home", createTime).apply();
            Log.d(TAG, "EMERGENCY 新记录: home time=" + createTime);
            return false;
        }

        if (ARRIVE.equals(eventType) || LEAVE.equals(eventType)) {
            lastTime = arriveLeaveLastTimeMap.getOrDefault(pointinfo, sharedPreferences.getLong(KEY_ROOM_TIME + pointinfo, 0L));
            if (createTime <= lastTime || createTime - lastTime < DUPLICATE_INTERVAL_MS) {
                Log.w(TAG, eventType + " 重复跳过: pointinfo=" + pointinfo + ", time=" + createTime + " <= " + lastTime);
                return true;
            }
            arriveLeaveLastTimeMap.put(pointinfo, createTime);
            sharedPreferences.edit().putLong(KEY_ROOM_TIME + pointinfo, createTime).apply();
            Log.d(TAG, eventType + " 新记录: pointinfo=" + pointinfo + ", time=" + createTime);
            return false;
        }

        Log.w(TAG, "未知类型，默认重复: " + eventType);
        return true;
    }

    private static final String EMERGENCY = "emergency";
    private static final String ARRIVE = "arrive";
    private static final String LEAVE = "leave";
    private static final String KEY_LAST_ARRIVE_ROOM = "LAST_ARRIVE_POINTINFO";

    public synchronized String getLastArriveRoom() {
        return sharedPreferences.getString(KEY_LAST_ARRIVE_ROOM, null);
    }

    public synchronized void updateLastArriveRoom(String pointinfo) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        if (pointinfo == null) {
            editor.remove(KEY_LAST_ARRIVE_ROOM);
            Log.d(TAG, "清除 LAST_ARRIVE_POINTINFO");
        } else {
            editor.putString(KEY_LAST_ARRIVE_ROOM, pointinfo);
            Log.d(TAG, "更新 LAST_ARRIVE_POINTINFO = " + pointinfo);
        }
        editor.apply();
    }
}
