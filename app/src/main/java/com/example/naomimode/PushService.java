package com.example.naomimode;

import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONException;
import org.json.JSONObject;

import android.Manifest;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;



public class PushService extends Service {
    private static final String CHANNEL_ID = "push_service_channel";
    private static final String PREFS = "app_prefs";
    private static final int ONGOING_ID = 1;
    private WebSocket ws;
    private static final String WS_URL = "ws://ec2-54-250-56-242.ap-northeast-1.compute.amazonaws.com:8001/v1/ws/robot/naomi";
    //private static final String WS_URL = "ws://192.168.100.44:8001/v1/ws/robot/naomi";
    private static final Handler handler = new Handler();
    private enum WsState { CONNECTING, OPEN, CLOSING, CLOSED, FAILED }
    private WsState wsState = WsState.CLOSED;
    private static final String KEY_ROBOTID = "qr_robot_id";
    private static final String KEY_PWD = "qr_auth_key";
    private static final String KEY_ROOM = "qr_room_id";
    private static final String ARRIVE ="arrive";
    private static final String LEAVE ="leave";
    private static final String EMERGENCY ="emergency";
    private static final String HOME ="home";
    private static final String OFF ="off";
    private static final String ON ="on";
    private static final String SERVICE = "Service";
    private static final String SETSUZOKU = "接続中…";
    private static final String ROBOT_ID = "robot_id";
    private static final String EVENT_TYPE = "event_type";
    private static final String FROM_PUSH = "from_push";
    private static final long RECONNECT_DELAY_MS = 5000;
    private static final long HEARTBEAT_INTERVAL = 15000;
    private String lastEventKey = "";
    private long lastEventTime = 0;
    private static final long DEDUP_INTERVAL_MS = 3_000;
    private long lastPongTime = 0;
    private boolean isSubscribed = false;
    private static final long HEARTBEAT_TIMEOUT = 10000L;
    private static final String FULLSCREEN_CHANNEL_ID = "push_fullscreen_channel";
    private static final int FULLSCREEN_NOTIFICATION_ID = 10010;
    private static final String MESSAGE_PS_001 = "ネットワークが利用できません。";
    private static OkHttpClient client;
    private static final long FORCE_RECONNECT_INTERVAL = 10 * 60 * 1000; // 10 分钟
    private static OkHttpClient getClientInstance() {
        if (client == null) {
            synchronized (PushService.class) {
                if (client == null) {
                    client = new OkHttpClient();
                }
            }
        }
        return client;
    }

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (ws != null && wsState == WsState.OPEN) {
                SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                String myRobotId   = sp.getString(KEY_ROBOTID, "");
                String clientId = Utils.getAndroidId(PushService.this) + "_" + sp.getString(KEY_ROOM, "");
                String authKey = Utils.getPref(PushService.this, KEY_PWD);
                try {
                    JSONObject ping = new JSONObject();
                    ping.put("type", "ping");
                    ping.put("client_id", clientId);
                    ping.put("auth_key", authKey);
                    ping.put(ROBOT_ID, myRobotId);
                    boolean sent = ws.send(ping.toString());
                    if (!sent) {
                        Log.w("NaoMiMode", "心跳发送失败，尝试断开重连");
                        forceReconnect();
                    } else {
                        Log.d("NaoMiMode", "ping sent");
                    }
                } catch (JSONException e) {
                    Log.e("NaoMiMode", "构造 ping 消息失败", e);
                }

                Log.w("NaoMiMode", "ping now:" +System.currentTimeMillis());
                Log.w("NaoMiMode", "ping lastPongTime:" +lastPongTime);
                Log.w("NaoMiMode", "ping HEARTBEAT_TIMEOUT:" +HEARTBEAT_TIMEOUT);
                if (System.currentTimeMillis() - lastPongTime > HEARTBEAT_TIMEOUT) {
                    Log.w("NaoMiMode", "ping 失败或 pong 超时，尝试重连");
                    forceReconnect();
                }
            }
            handler.postDelayed(this, RECONNECT_DELAY_MS);
        }
    };

    private void forceReconnect() {
        if (ws != null) {
            try {
                ws.cancel();
            } catch (Exception e) {
                Log.e("NaoMiMode", "ws.cancel() error", e);
            }
            ws = null;
        }
        wsState = WsState.FAILED;
        tryReconnectWebSocket();
    }

    private boolean isWebSocketIdle() {
        return ws == null || wsState == WsState.CLOSED || wsState == WsState.FAILED;
    }

    private int retryAttempt = 0;

    private void tryReconnectWebSocket() {
        if (isWebSocketIdle()) {
            long delay = Math.min(RECONNECT_DELAY_MS * (1 << retryAttempt), 60_000);
            Log.i("NaoMiMode", "尝试重新连接 WebSocket，延迟: " + delay + "ms");
            handler.postDelayed(this::connectWebSocket, delay);
            retryAttempt++;
        } else {
            retryAttempt = 0;
            Log.i("NaoMiMode", "WebSocket 正常，不需要重连");
        }
    }


    @Override
    public void onCreate() {
        super.onCreate();
        //client = new OkHttpClient();
        createChannel();
        tryReconnectWebSocket();
        handler.postDelayed(heartbeatRunnable, RECONNECT_DELAY_MS);
       // handler.postDelayed(forceReconnectRunnable, FORCE_RECONNECT_INTERVAL);
        startForeground(ONGOING_ID, buildNotification(SETSUZOKU));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    SERVICE,
                    NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class)
                    .createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(SERVICE)
                .setContentText(text)
                .setSmallIcon(R.drawable.aimlogo_white)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
    private void connectWebSocket() {
        if (ws != null) {
            try {
                ws.cancel();
            } catch (Exception ignored) {}
            ws = null;
        }
        if (!isNetworkAvailable()) {
            Log.w("NaoMiMode", "WebSocket 网络不可用");
            handler.post(() -> Toast.makeText(PushService.this,
                    MESSAGE_PS_001, Toast.LENGTH_SHORT).show());
            handler.postDelayed(this::tryReconnectWebSocket, RECONNECT_DELAY_MS);
            return;
        }
        if (wsState == WsState.CONNECTING || wsState == WsState.OPEN) {
            Log.w("NaoMiMode", "WebSocket 正在连接或已连接，忽略重复调用");
            return;
        }

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String myPointInfo = sp.getString(KEY_ROOM, "");
        String url = WS_URL
                + "?client_id=" + Utils.getAndroidId(PushService.this)+  "_" + myPointInfo
                + "&auth_key=" + Utils.getPref(PushService.this, KEY_PWD);
        Log.i("NaoMiMode", "connectWebSocket url" + url);
        Request req = new Request.Builder().url(url).build();
        wsState = WsState.CONNECTING;
        Log.i("NaoMiMode", "wsState:" + wsState);

        ws = getClientInstance().newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                wsState = WsState.OPEN;
                Log.i("NaoMiMode", "wsState:" + wsState);
                isSubscribed = false;
                lastPongTime = System.currentTimeMillis();

                Log.i("NaoMiMode", "wsState:" + wsState);
                Log.i("NaoMiMode", "WS onOpen:" + response.code());
                try {
                    JSONObject sub = new JSONObject();
                    sub.put("type", "subscribe");
                    sub.put(ROBOT_ID, Utils.getPref(PushService.this, KEY_ROBOTID));
                    webSocket.send(sub.toString());
                    Log.i("NaoMiMode", "WS onOpen　sub: " + sub);
                } catch (JSONException e) {
                    Log.e("NaoMiMode", "失敗しました。", e);
                }
                handler.postDelayed(() -> {
                    if (wsState != WsState.OPEN) return;
                    if (isSubscribed == false) {
                        Log.w("NaoMiMode", "虽然onOpen，订阅超时，强制重连");
                        forceReconnect();
                    }
                }, 10000);
            }
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                Log.i("NaoMiMode", "WS onMessage: " + text);
                SharedPreferences lastArrive = getSharedPreferences(PREFS, MODE_PRIVATE);
                EventDedupManager dedupManager = EventDedupManager.getInstance(PushService.this);

                try {
                    JSONObject msg = new JSONObject(text);
                    String type = msg.optString("type");
                    if ("pong".equals(type)) {
                        Log.d("NaoMiMode", "pong received");
                        lastPongTime = System.currentTimeMillis();
                        return;
                    } else if ("subscription_confirmed".equals(type)) {
                        Log.i("NaoMiMode", "服务端已确认订阅成功 robot_id=" + msg.optString("robot_id"));
                        Log.i("NaoMiMode", "订阅成功，连接有效");
                            isSubscribed = true;
                        return;
                    }
                    if (isSubscribed == false) {
                        Log.w("NaoMiMode", "收到消息但订阅失败");
                        return;
                    }

                    String robotId   = msg.optString(ROBOT_ID);
                    String eventType = msg.optString(EVENT_TYPE);
                    String createTime = msg.optString("timestamp");
                    JSONObject payload = msg.optJSONObject("payload");
                    String pointinfo = "";
                    String status = "";
                    if (payload != null && payload.has("param")) {
                        JSONObject param = payload.getJSONObject("param");
                        pointinfo = param.optString("pointinfo");
                        status = param.optString("status");
                    }
                    SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                    String myRobotId   = sp.getString(KEY_ROBOTID, "");
                    String myPointInfo = sp.getString(KEY_ROOM, "");

                    if (!robotId.equals(myRobotId)) return;
                    if (!(ARRIVE.equals(eventType) || LEAVE.equals(eventType) || EMERGENCY.equals(eventType))) return;

                    String dedupKey = eventType + "#" + pointinfo + "#" + status;
                    String eventType1="";
                    String pointinfo1="";

                    if (EMERGENCY.equals(eventType)) {
                        eventType1 = eventType;
                        pointinfo1 = "home";
                    } else if (ARRIVE.equals(eventType) || LEAVE.equals(eventType)) {
                        eventType1 = eventType;
                        pointinfo1 = pointinfo;
                    } else {
                        return;
                    }
                    long newTime = parseCreateTimeToMillis(createTime);
                    if (newTime < 0) {
                        return;
                    }
                    if (dedupManager.isDuplicate(eventType1,pointinfo1,newTime)) {
                        return;
                    }

                    if (EMERGENCY.equals(eventType)) {
                        dispatchDismiss(HOME);
                        if (ON.equals(status))   dispatchShow(HOME, eventType);
                        else if (OFF.equals(status)) dispatchDismiss(HOME);
                        return;
                    }
                    else if (ARRIVE.equals(eventType) && HOME.equals(pointinfo)) {
                        dispatchDismiss(HOME);
                        dispatchShow(HOME, eventType);
                        return;
                    }
                    else if (ARRIVE.equals(eventType)
                            && !HOME.equals(pointinfo)) {
                        String last = dedupManager.getLastArriveRoom();
                        if (last != null && !last.equals(pointinfo)) {
                            Log.i("NaoMiMode", "[调试] pointinfo 发生变化，关闭上一次=" + last);
                            dispatchDismiss(last);
                        }

                        if (pointinfo.equals(myPointInfo)) {
                            dispatchDismiss(pointinfo); // 冗余关闭
                            dispatchShow(pointinfo, eventType);
                        }

                        dedupManager.updateLastArriveRoom(pointinfo);
                        return;
                    }
                    else if (LEAVE.equals(eventType)
                            && pointinfo.equals(myPointInfo)
                            && !HOME.equals(pointinfo)) {
//                        String last = lastArrive.getString("LAST_ARRIVE_POINTINFO", null);
//                        if (pointinfo.equals(last)) {
//                            lastArrive.edit().remove("LAST_ARRIVE_POINTINFO").apply();
//                            Log.i("NaoMiMode", "WS LEAVE: 清除 lastArrivePointInfo=" + last);
//                        }

                        dispatchDismiss(pointinfo);
                        return;
                    }
                } catch (JSONException e) {
                    Log.e("NaoMiMode", "消息解析失败:", e);
                }
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                wsState = WsState.CLOSING;
                Log.i("NaoMiMode", "WS onClosing: code=" + code + "，原因=" + reason);
                webSocket.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                wsState = WsState.CLOSED;
                Log.i("NaoMiMode", "WS onClosed: code=" + code + "，原因=" + reason);
                ws = null;
                handler.postDelayed(() -> tryReconnectWebSocket(), RECONNECT_DELAY_MS);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                wsState = WsState.FAILED;
                Log.e("NaoMiMode", "WS onFailure: 接続に失敗するか、異常に切断されました", t);

                if (t instanceof java.net.ConnectException) {
                    Log.e("NaoMiMode", "连接被服务器拒绝，检查服务器是否在线、端口是否开放");
                }

                if (response != null) {
                    Log.e("NaoMiMode", "  HTTP code=" + response.code() + "，message=" + response.message());
                }

                ws = null;
                handler.postDelayed(() -> tryReconnectWebSocket(), RECONNECT_DELAY_MS);
            }

        });
    }
    private long parseCreateTimeToMillis(String createTime) {
        if (createTime == null || createTime.length() < 23) {
            Log.w("NaoMiMode", "create_time 格式不合法: " + createTime);
            return -1;
        }

        String trimmed = createTime.substring(0, 23);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

        try {
            Date date = sdf.parse(trimmed);
            if (date != null) {
                return date.getTime();
            }
        } catch (Exception e) {
            Log.e("NaoMiMode", "create_time 解析失败: " + createTime, e);
        }

        return -1;
    }

    private void dispatchShow(String pointinfo, String eventType) {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            PowerManager.WakeLock wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "NaoMiMode:WakeLock"
            );
            wakeLock.acquire(5_000L);
        }

        Intent broadcast = new Intent("com.example.naomimode.SHOW_ARRIVAL_DIALOG");
        broadcast.putExtra("roomNo", pointinfo);
        broadcast.putExtra("eventType", eventType);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }



    private void dispatchDismiss(String pointinfo) {
        Intent broadcast = new Intent("com.example.naomimode.DISMISS_ARRIVAL_DIALOG");
        broadcast.putExtra("roomNo", pointinfo);
        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(broadcast);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (ws == null) {
            connectWebSocket();
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i("NaoMiMode", "onTaskRemoved(): 用户划掉任务，尝试重启 Service");
        Intent restartIntent = new Intent(getApplicationContext(), PushService.class);
        restartIntent.setPackage(getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplicationContext().startForegroundService(restartIntent);
        } else {
            getApplicationContext().startService(restartIntent);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (ws != null) {
            ws.close(1000, null);
        }
        if (client != null) {
            client.dispatcher().executorService().shutdown();
        }
        handler.removeCallbacks(heartbeatRunnable);
        //handler.removeCallbacks(forceReconnectRunnable);
    }

    @SuppressLint("InvalidWakeLockTag")
    private void wakeScreenAndStartActivity(String roomNo,String eventType) {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            int wakeFlags = PowerManager.FULL_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP
                    | PowerManager.ON_AFTER_RELEASE;
            PowerManager.WakeLock wl = pm.newWakeLock(wakeFlags, "MyApp:WakeLockTag");
            wl.acquire(3000);
        }

        handler.postDelayed(() -> {
            Intent intent = new Intent(PushService.this, MainActivity.class);
            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
            );
            intent.putExtra(FROM_PUSH, true);
            intent.putExtra("roomNo", roomNo);
            intent.putExtra("eventType", eventType);
            startActivity(intent);
        }, 300);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return false;
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } else {
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
    }

}
