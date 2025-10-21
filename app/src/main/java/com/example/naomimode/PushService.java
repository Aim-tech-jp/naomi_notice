package com.example.naomimode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.naomimode.MqttManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class PushService extends Service {
    private static final String TAG = "PushService(Paho)";
    private static final String TAG_MQTT = "MQTT_RX";
    private static final String PREFS = "app_prefs";
    private static final String KEY_ROBOTID = "qr_robot_id";

    private static final String CHANNEL_ID = "push_service_channel";
    private static final int ONGOING_ID = 1001;

    private MqttManager mqtt;
    private String topicFull;

    //缓存json用
    private final Map<String, JSONObject> lastJsonByKey = new HashMap<>();
    //最新create_time
    private final Map<String, Long> lastTsByKey = new HashMap<>();
    //create_time 前后5s
    private final Map<String, Long> lastTsByRoom = new HashMap<>();
    //5 秒抑制
    private static final long SUPPRESS_WINDOW_MS = 5_000L;

    static class MqttConf {
        String url, username, password, topicPrefix;
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(ONGOING_ID, buildNotification("接続中…"));

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String robotId = sp.getString(KEY_ROBOTID, "");
        if (robotId == null || robotId.trim().isEmpty()) {
            Toast.makeText(this, "robotId:null", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }

        MqttConf conf = loadConf();
        if (conf == null || conf.url == null || conf.url.trim().isEmpty()) {
            Toast.makeText(this, "mqtt_config.json存在しません", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        topicFull = (conf.topicPrefix == null ? "" : conf.topicPrefix) + robotId;

        // 生成 clientId
        String clientId = Utils.getAndroidId(this) + "_" + robotId;

        // 构造并连接（Paho 会根据 url 的 scheme 处理 tcp/ssl/ws/wss）
        mqtt = new MqttManager(getApplicationContext(), conf.url.trim(), clientId, topicFull);
        mqtt.connect(conf.username, conf.password, new MqttManager.MsgListener() {
            @Override public void onMessage(String topic, String payload) {
                handleMqttJson(payload);
            }
            @Override public void onError(Throwable t) {
                Log.e(TAG, "MQTT error: " + (t==null?"null":t.getMessage()), t);
            }
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        super.onDestroy();
        if (mqtt != null) mqtt.close();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void handleMqttJson(String text) {
        try {
            Log.i(TAG_MQTT, "---- RAW JSON ----");
            logLong(TAG_MQTT, text);

            JSONObject msg = new JSONObject(text);

            Log.i(TAG_MQTT, "---- PRETTY JSON ----");
            logLong(TAG_MQTT, prettyJson(msg));
            Log.i(TAG_MQTT, "---------END---------");
            String robotId   = msg.optString("robot_id");
            String eventType = msg.optString("event_type");
            JSONObject payload = msg.optJSONObject("payload");

            //到达房间时
            String event = payload != null ? payload.optString("event", "") : "";
            String roomId = payload != null ? payload.optString("room_id", "") : "";
             String floorId = payload != null ? payload.optString("floor_id", "") : "";
            Log.i(TAG_MQTT, "---- PRETTY JSON roomId=----"+roomId);
            long tsMs = parseTimestampMs(msg);

            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            String myRobot = sp.getString(KEY_ROBOTID, "");
            if (!myRobot.equals(robotId)) {
                Log.i(TAG_MQTT, "忽略其他 robot_id: " + robotId);
                return;
            }

            String key = makeKey(event, roomId, floorId);

            Long lastTsForKey = lastTsByKey.get(key);
            if (lastTsForKey != null && tsMs <= lastTsForKey) {
                Log.i(TAG_MQTT, "IGNORE: 旧消息 key=" + key + " ts=" + tsMs + " <= last=" + lastTsForKey);
                return;
            }

            if (roomId != null && !roomId.isEmpty()) {
                Long lastRoomTs = lastTsByRoom.get(roomId);
                if (lastRoomTs != null && (tsMs - lastRoomTs) < SUPPRESS_WINDOW_MS) {
                    Log.i(TAG_MQTT, "IGNORE: 同房间抑制 room=" + roomId + " Δ=" + (tsMs - lastRoomTs) + "ms < 5000ms");
                    return;
                }
            }

            JSONObject lastJson = lastJsonByKey.get(key);
            Log.i(TAG_MQTT, "---- DIFF @key=" + key + " ----");
            if (lastJson == null) {
                Log.i(TAG_MQTT, "(first message for this key)");
            } else {
                String diff = diffJson(lastJson, msg);
                logLong(TAG_MQTT, diff);
            }

            lastJsonByKey.put(key, msg);
            lastTsByKey.put(key, tsMs);
            if (roomId != null && !roomId.isEmpty()) {
                lastTsByRoom.put(roomId, tsMs);
            }

             Intent b = new Intent("com.example.naomimode.MQTT_EVENT");
            b.putExtra("event", event);
            b.putExtra("room_id", roomId);
            b.putExtra("floor_id", floorId);
            b.putExtra("timestamp_ms", tsMs);
            LocalBroadcastManager.getInstance(this).sendBroadcast(b);

        } catch (Exception e) {
            Log.e(TAG_MQTT, "JSON 解析失败", e);
        }
    }

    private long parseTimestampMs(JSONObject msg) {
        double ts = msg.optDouble("timestamp", Double.NaN);
        if (!Double.isNaN(ts)) return (long) (ts * 1000L);
        String ct = msg.optString("create_time", "");
        if (!ct.isEmpty()) return Utils.parseIsoToMillis(ct);
        return System.currentTimeMillis();
    }

    private MqttConf loadConf() {
        try (InputStream is = getAssets().open("mqtt_config.json");
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            String s = bos.toString(StandardCharsets.UTF_8.name());
            JSONObject root = new JSONObject(s).optJSONObject("mqtt");
            if (root == null) return null;
            MqttConf c = new MqttConf();
            c.url = root.optString("url", null);
            c.username = root.optString("username", "");
            c.password = root.optString("password", "");
            c.topicPrefix = root.optString("topicPrefix", "");
            return c;
        } catch (Exception e) {
            Log.e(TAG, "读取 mqtt_config.json 失败: " + e.getMessage(), e);
            return null;
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Push Service", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Service")
                .setContentText(text)
                .setSmallIcon(R.drawable.aimlogo)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private String makeKey(String event, String roomId, String floorId) {
        if (roomId != null && !roomId.isEmpty()) return event + "#room#" + roomId;
        if (floorId != null && !floorId.isEmpty()) return event + "#floor#" + floorId;
        return event;
    }

    private String prettyJson(JSONObject obj) {
        try { return obj.toString(2); } catch (Exception e) { return String.valueOf(obj); }
    }

    private void logLong(String tag, String text) {
        if (text == null) return;
        int max = 3500;
        for (int i = 0; i < text.length(); i += max) {
            int end = Math.min(text.length(), i + max);
            Log.i(tag, text.substring(i, end));
        }
    }

    private String diffJson(JSONObject oldJ, JSONObject newJ) {
        StringBuilder sb = new StringBuilder();
        diffJsonRecursive("", oldJ, newJ, sb);
        if (sb.length() == 0) return "(no changes)";
        return sb.toString();
    }

    private void diffJsonRecursive(String path, Object oldV, Object newV, StringBuilder sb) {
        if (oldV == null && newV == null) return;
        if (oldV == null) {
            sb.append("ADDED ").append(path).append(" = ").append(stringify(newV)).append("\n");
            return;
        }
        if (newV == null) {
            sb.append("REMOVED ").append(path).append(" (was ").append(stringify(oldV)).append(")\n");
            return;
        }
        if (oldV instanceof JSONObject && newV instanceof JSONObject) {
            JSONObject o = (JSONObject) oldV, n = (JSONObject) newV;
            Set<String> keys = new HashSet<>();
            for (var it = o.keys(); it.hasNext();) keys.add(it.next());
            for (var it = n.keys(); it.hasNext();) keys.add(it.next());
            for (String k : keys) {
                Object ov = o.opt(k);
                Object nv = n.opt(k);
                String p = path.isEmpty() ? k : path + "." + k;
                diffJsonRecursive(p, ov, nv, sb);
            }
        } else if (oldV instanceof JSONArray && newV instanceof JSONArray) {
            JSONArray oa = (JSONArray) oldV, na = (JSONArray) newV;
            if (oa.length() != na.length()) {
                sb.append("CHANGED ").append(path).append(" length: ")
                        .append(oa.length()).append(" -> ").append(na.length()).append("\n");
            }
            int len = Math.min(oa.length(), na.length());
            for (int i = 0; i < len; i++) {
                Object ov = oa.opt(i);
                Object nv = na.opt(i);
                diffJsonRecursive(path + "[" + i + "]", ov, nv, sb);
            }
        } else {
            if (!Objects.equals(String.valueOf(oldV), String.valueOf(newV))) {
                sb.append("CHANGED ").append(path).append(": ")
                        .append(stringify(oldV)).append(" -> ").append(stringify(newV)).append("\n");
            }
        }
    }

    private String stringify(Object v) {
        if (v == null) return "null";
        if (v instanceof JSONObject) return ((JSONObject) v).toString();
        if (v instanceof JSONArray)  return ((JSONArray) v).toString();
        return String.valueOf(v);
    }
}
