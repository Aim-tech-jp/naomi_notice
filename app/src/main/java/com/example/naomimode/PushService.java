package com.example.naomimode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;

public class PushService extends Service {
    private static final String TAG = "PushService(Paho)";
    private static final String TAG_MQTT = "MQTT_RX";
    private static final String PREFS = "app_prefs";
    private static final String KEY_ROBOTID = "qr_robot_id";

    private static final String CHANNEL_ID = "push_service_channel";
    private static final int ONGOING_ID = 1001;

    private MqttManager mqtt;
    private String topicFull;

    private final Map<String, JSONObject> lastJsonByEventKey = new HashMap<>();

    private DedupeStore dedupeStore;

    private static final long EVENT_THROTTLE_MS = 5_000L;

    private static final long ROOM_SUPPRESS_MS  = 5_000L;
    private static final long FLOOR_SUPPRESS_MS = 5_000L;

    private static final long RECONNECT_WARMUP_MS = 2_000L;

    private long lastConnectCompleteAt = 0L;

    static class MqttConf {
        String url, username, password, topicPrefix;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(ONGOING_ID, buildNotification("接続中…"));

        dedupeStore = new DedupeStore(getSharedPreferences(PREFS, MODE_PRIVATE));
        dedupeStore.printCachedPayloads(TAG);
        LocalBroadcastManager.getInstance(this).registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(android.content.Context context, Intent intent) {
                lastConnectCompleteAt = System.currentTimeMillis();
            }
        }, new IntentFilter("com.example.naomimode.MQTT_CONNECTED"));

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

        String clientId = Utils.getAndroidId(this) + "_" + robotId;
        mqtt = new MqttManager(getApplicationContext(), conf.url.trim(), clientId, topicFull);
        mqtt.connect(conf.username, conf.password, new MqttManager.MsgListener() {
            @Override
            public void onMessage(String topic, String payload, boolean isRetained, boolean isDuplicate) {
                handleIncoming(topic, payload, isRetained, isDuplicate);
            }

            @Override
            public void onError(Throwable t) {
                Log.e(TAG, "MQTT error: " + (t == null ? "null" : t.getMessage()), t);
            }
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override public void onDestroy() {
        super.onDestroy();
        if (mqtt != null) mqtt.close();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void handleIncoming(String topic, String text, boolean isRetained, boolean isDuplicate) {
        try {
            Log.i(TAG_MQTT, "---- RAW JSON ----");
            logLong(TAG_MQTT, text);

            JSONObject msg = new JSONObject(text);

            String robotId = msg.optString("robot_id");
            JSONObject payload = msg.optJSONObject("payload");
            String event   = payload != null ? payload.optString("event",   "") : "";
            String roomId  = payload != null ? payload.optString("room_id", "") : "";
            String floorId = payload != null ? payload.optString("floor_id","") : "";
            String state = payload != null ? payload.optString("state","") : "";
            String error_code = payload != null ? payload.optString("error_code","") : "";

            Object tsObj = msg.opt("timestamp");
            if (tsObj == null) tsObj = msg.opt("create_time");
            String tsRaw = (tsObj == null) ? null : String.valueOf(tsObj).trim();
            @Nullable Long tsMs = parseTsMillisNullable(tsRaw); // 可能为 null

            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            String myRobot = sp.getString(KEY_ROBOTID, "");
            if (!myRobot.equals(robotId)) {
                Log.i(TAG_MQTT, "忽略其他 robot_id: " + robotId);
                return;
            }

            String timeToken = (tsRaw != null && !tsRaw.isEmpty()) ? tsRaw : md5(text);
            String exactKey = makeExactKey(topic, event, timeToken); // topic|event|<tsRaw或hash>
            String eventKey = makeEventKey(topic, event);            // topic|event
            String roomKey  = makeRoomKey(topic, event, roomId);     // topic|event|room|<room_id>
            String floorKey = makeFloorKey(topic, event, floorId);   // topic|event|floor|<floor_id>

            boolean inWarmup = (System.currentTimeMillis() - lastConnectCompleteAt) <= RECONNECT_WARMUP_MS;

            if (!dedupeStore.shouldDeliver(
                    exactKey, eventKey, roomKey, floorKey,
                    tsMs, isRetained || isDuplicate, inWarmup,
                    EVENT_THROTTLE_MS, ROOM_SUPPRESS_MS, FLOOR_SUPPRESS_MS)) {
                Log.i(TAG_MQTT, "DROP: dedupe/suppress (but cache latest)");
                dedupeStore.onSeen(exactKey, eventKey, roomKey, floorKey, tsMs, text);
                return;
            }

            JSONObject lastJson = lastJsonByEventKey.get(eventKey);
            Log.i(TAG_MQTT, "---- DIFF @eventKey=" + eventKey + " ----");
            if (lastJson == null) {
                Log.i(TAG_MQTT, "(first message for this key)");
            } else {
                logLong(TAG_MQTT, diffJson(lastJson, msg));
            }
            lastJsonByEventKey.put(eventKey, msg);

            dedupeStore.onDelivered(exactKey, eventKey, roomKey, floorKey, tsMs, text);

            Intent b = new Intent("com.example.naomimode.MQTT_EVENT");
            b.putExtra("event", event);
            b.putExtra("room_id", roomId);
            b.putExtra("floor_id", floorId);
            b.putExtra("state", state);
            b.putExtra("error_code", error_code);
            b.putExtra("timestamp_ms", tsMs != null ? tsMs : 0);
            LocalBroadcastManager.getInstance(this).sendBroadcast(b);

        } catch (Exception e) {
            Log.e(TAG_MQTT, "JSON 解析或处理失败", e);
        }
    }

    private String makeEventKey(String topic, String event) {
        return topic + "|" + (event == null ? "" : event);
    }
    private String makeExactKey(String topic, String event, String timeToken) {
        return topic + "|" + (event == null ? "" : event) + "|" + (timeToken == null ? "" : timeToken);
    }
    private String makeRoomKey(String topic, String event, String roomId) {
        return topic + "|" + (event == null ? "" : event) + "|room|" + (roomId == null ? "" : roomId);
    }
    private String makeFloorKey(String topic, String event, String floorId) {
        return topic + "|" + (event == null ? "" : event) + "|floor|" + (floorId == null ? "" : floorId);
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
            Set<String> keys = new HashSet<String>();
            for (Iterator<String> it = o.keys(); it.hasNext();) keys.add(it.next());
            for (Iterator<String> it = n.keys(); it.hasNext();) keys.add(it.next());
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

    @Nullable
    private static Long parseTsMillisNullable(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            // 数字：秒/毫秒/微秒/纳秒（含小数或科学计数）
            java.math.BigDecimal bd = new java.math.BigDecimal(raw);
            if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) {
                // 秒.小数 → 毫秒，向下取整
                return bd.multiply(java.math.BigDecimal.valueOf(1000L))
                        .setScale(0, java.math.RoundingMode.DOWN)
                        .longValue();
            }
            long v = bd.longValueExact(); // 纯整数
            int len = raw.startsWith("-") || raw.startsWith("+") ? raw.length() - 1 : raw.length();
            if (len <= 10)      return v * 1000L;       // 秒
            else if (len == 13) return v;               // 毫秒
            else if (len == 16) return v / 1000L;       // 微秒 → 毫秒
            else                return v / 1_000_000L;  // 纳秒/更长 → 毫秒
        } catch (Exception ignore) {
            // 可选：支持 ISO8601（简化版）
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                return sdf.parse(raw).getTime();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(s.getBytes(StandardCharsets.UTF_8));
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    // ====================== 持久化去重器（v5，最新12条，O(1)驱逐） ======================
    // exactKey = topic|event|<timestamp原文或payload哈希>
    // eventKey = topic|event                      （同 event 5s 节流，基于系统时钟）
    // roomKey  = topic|event|room|<room_id>      （同房间抑制，5s，基于业务时间 tsMs）
    // floorKey = topic|event|floor|<floor_id>    （同楼层抑制，5s，基于业务时间 tsMs）
    private static final class DedupeStore {
        private static final String SP_KEY_DEDUPE = "dedupe_json_v5";
        private final SharedPreferences sp;

        // 只保留最新 N 条 exact & payload（O(1) 插入、逐出）
        private static final int MAX_EXACT_AND_PAYLOAD = 12;

        private final Map<String, Long> exactTs      = new HashMap<>(); // exactKey → deliveredAt(now)
        private final Map<String, Long> eventTs      = new HashMap<>(); // eventKey → deliveredAt(now)
        private final Map<String, Long> roomEventTs  = new HashMap<>(); // roomKey  → lastMsgTsMs(业务时间)
        private final Map<String, Long> floorEventTs = new HashMap<>(); // floorKey → lastMsgTsMs(业务时间)

        private final Map<String, String> lastPayloadByExact = new HashMap<>();

        private final LinkedHashSet<String> exactOrder = new LinkedHashSet<>();

        DedupeStore(SharedPreferences sp) {
            this.sp = sp;
            load();
        }

        private void load() {
            String raw = sp.getString(SP_KEY_DEDUPE, "{}");
            try {
                JSONObject root = new JSONObject(raw);
                fromObjL(root.optJSONObject("exact"),      exactTs);
                fromObjL(root.optJSONObject("event"),      eventTs);
                fromObjL(root.optJSONObject("roomEvent"),  roomEventTs);
                fromObjL(root.optJSONObject("floorEvent"), floorEventTs);

                JSONObject lp = root.optJSONObject("lastPayloadByExact");
                if (lp != null) {
                    for (Iterator<String> it = lp.keys(); it.hasNext();) {
                        String k = it.next();
                        lastPayloadByExact.put(k, lp.optString(k, ""));
                    }
                }

                // 重建插入顺序：按 deliveredAt(now) 升序加入
                java.util.List<Map.Entry<String, Long>> list = new java.util.ArrayList<>(exactTs.entrySet());
                list.sort(Map.Entry.comparingByValue());
                for (Map.Entry<String, Long> e : list) {
                    exactOrder.add(e.getKey());
                }

                // 启动时做一次上限删除
                while (exactOrder.size() > MAX_EXACT_AND_PAYLOAD) {
                    String victim = exactOrder.iterator().next();
                    exactOrder.remove(victim);
                    exactTs.remove(victim);
                    lastPayloadByExact.remove(victim);
                }
                persistAsync();
            } catch (Exception ignore) {}
        }

        private void persistAsync() {
            try {
                JSONObject root = new JSONObject();
                root.put("exact",      toObjL(exactTs));
                root.put("event",      toObjL(eventTs));
                root.put("roomEvent",  toObjL(roomEventTs));
                root.put("floorEvent", toObjL(floorEventTs));

                JSONObject lp = new JSONObject();
                for (Map.Entry<String, String> e : lastPayloadByExact.entrySet()) {
                    lp.put("打印", e.getValue());
                }
                root.put("lastPayloadByExact", lp);

                sp.edit().putString(SP_KEY_DEDUPE, root.toString()).apply();
            } catch (Exception ignore) {}
        }

        private static void fromObjL(@Nullable JSONObject obj, Map<String, Long> out) {
            if (obj == null) return;
            for (Iterator<String> it = obj.keys(); it.hasNext();) {
                String k = it.next();
                out.put(k, obj.optLong(k, 0L));
            }
        }

        private static JSONObject toObjL(Map<String, Long> m) {
            JSONObject o = new JSONObject();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
            try {
                for (Map.Entry<String, Long> e : m.entrySet()) {
                    long v = e.getValue();
                    JSONObject info = new JSONObject();
                    info.put("millis", v);
                    info.put("readable", sdf.format(new Date(v)));
                    o.put(e.getKey(), info);
                }
            } catch (Exception ignore) {}
            return o;
        }

        boolean shouldDeliver(String exactKey,
                              String eventKey,
                              String roomKey,
                              String floorKey,
                              @Nullable Long tsMs,
                              boolean isRetainedOrDup,
                              boolean inWarmup,
                              long eventThrottleMs,
                              long roomSuppressMs,
                              long floorSuppressMs) {
            long now = System.currentTimeMillis();

            Long lastEventAt = eventTs.get(eventKey);
            if (lastEventAt != null && (now - lastEventAt) <= eventThrottleMs) return false;

            if (tsMs != null && roomKey != null && roomKey.length() > 0 && !roomKey.endsWith("|")) {
                Long lastRoomEventAt = roomEventTs.get(roomKey);
                if (lastRoomEventAt != null && (tsMs - lastRoomEventAt) < roomSuppressMs) return false;
            }

            if (tsMs != null && floorKey != null && floorKey.length() > 0 && !floorKey.endsWith("|")) {
                Long lastFloorEventAt = floorEventTs.get(floorKey);
                if (lastFloorEventAt != null && (tsMs - lastFloorEventAt) < floorSuppressMs) return false;
            }

            if (exactTs.containsKey(exactKey)) return false;

            if (isRetainedOrDup && inWarmup && lastEventAt != null && (now - lastEventAt) <= eventThrottleMs) return false;

            return true;
        }

        void onDelivered(String exactKey,
                         String eventKey,
                         String roomKey,
                         String floorKey,
                         @Nullable Long tsMs,
                         @Nullable String rawJson) {
            long now = System.currentTimeMillis();

            exactTs.put(exactKey, now);
            eventTs.put(eventKey, now);

            if (tsMs != null) {
                if (roomKey != null && roomKey.length() > 0 && !roomKey.endsWith("|")) {
                    roomEventTs.put(roomKey, tsMs);
                }
                if (floorKey != null && floorKey.length() > 0 && !floorKey.endsWith("|")) {
                    floorEventTs.put(floorKey, tsMs);
                }
            }
            if (rawJson != null) {
                lastPayloadByExact.put(exactKey, rawJson);
            }

            if (exactOrder.contains(exactKey)) exactOrder.remove(exactKey);
            exactOrder.add(exactKey);
            while (exactOrder.size() > MAX_EXACT_AND_PAYLOAD) {
                String victim = exactOrder.iterator().next(); // 最旧
                exactOrder.remove(victim);
                exactTs.remove(victim);
                lastPayloadByExact.remove(victim);
            }

            persistAsync();
        }

        void onSeen(String exactKey,
                    String eventKey,
                    String roomKey,
                    String floorKey,
                    @Nullable Long tsMs,
                    @Nullable String rawJson) {
            long now = System.currentTimeMillis();

            exactTs.put(exactKey, now);
            eventTs.put(eventKey, now);

            if (tsMs != null) {
                if (roomKey != null && roomKey.length() > 0 && !roomKey.endsWith("|")) {
                    roomEventTs.put(roomKey, tsMs);
                }
                if (floorKey != null && floorKey.length() > 0 && !floorKey.endsWith("|")) {
                    floorEventTs.put(floorKey, tsMs);
                }
            }
            if (rawJson != null) {
                lastPayloadByExact.put(exactKey, rawJson);
            }

            if (exactOrder.contains(exactKey)) exactOrder.remove(exactKey);
            exactOrder.add(exactKey);
            while (exactOrder.size() > MAX_EXACT_AND_PAYLOAD) {
                String victim = exactOrder.iterator().next();
                exactOrder.remove(victim);
                exactTs.remove(victim);
                lastPayloadByExact.remove(victim);
            }

            persistAsync();
        }

        void printCachedPayloads(String tag) {
            if (lastPayloadByExact.isEmpty()) {
                Log.i(tag, "[Cache] 当前无已缓存旧消息");
                return;
            }
            Log.i(tag, "=== 已缓存旧消息（lastPayloadByExact，最多 " + MAX_EXACT_AND_PAYLOAD + " 条） ===");
            for (Map.Entry<String, String> e : lastPayloadByExact.entrySet()) {
                String key = e.getKey();
                String json = e.getValue();
                Log.i(tag, key + "\n" + json);
            }
        }

        @Nullable String getLastPayload(String exactKey) {
            return lastPayloadByExact.get(exactKey);
        }
    }
}
