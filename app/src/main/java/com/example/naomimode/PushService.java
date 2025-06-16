package com.example.naomimode;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.KeyguardManager;
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
import android.os.Looper;
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
import android.graphics.Color;
import android.Manifest;
import android.widget.Toast;


public class PushService extends Service {
    private static final String CHANNEL_ID = "push_service_channel";
    private static final String PREFS    = "app_prefs";
    private static final int    ONGOING_ID = 1;
    private OkHttpClient client;
    private WebSocket     ws;
    private static final String WS_URL = "ws://ec2-54-250-56-242.ap-northeast-1.compute.amazonaws.com:8001/v1/ws/robot/naomi";
    private static final Handler  handler = new Handler();
    private enum WsState { CONNECTING, OPEN, CLOSING, CLOSED, FAILED }
    private WsState wsState = WsState.CONNECTING;
    private static final String KEY_ROBOTID   = "qr_robot_id";
    private static final String KEY_PWD  = "qr_auth_key";
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
    // 用于去重
    private String lastEventKey = "";
    private long   lastEventTime = 0;
    // 重复阈值，3 秒
    private static final long DEDUP_INTERVAL_MS = 3_000;
    private static final String FULLSCREEN_CHANNEL_ID = "push_fullscreen_channel";
    private static final int FULLSCREEN_NOTIFICATION_ID = 10010;
    private static final String MESSAGE_PS_001 = "ネットワークが利用できません。";



    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        connectWebSocket();
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
        if (!isNetworkAvailable()) {
            handler.post(() -> Toast.makeText(PushService.this,
                    MESSAGE_PS_001, Toast.LENGTH_SHORT).show());
            handler.postDelayed(this::connectWebSocket, RECONNECT_DELAY_MS);
            return;
        }
        client = new OkHttpClient();
        String url = WS_URL
                + "?client_id="    + Utils.getAndroidId(PushService.this)
                + "&auth_key="    + Utils.getPref(PushService.this, KEY_PWD);
        Log.i("NaoMiMode", "url" + url);
        Request req = new Request.Builder()
                .url(url)
                .build();
        wsState = WsState.CONNECTING;
        Log.i("NaoMiMode", "wsState:" +wsState);

        ws = client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                wsState = WsState.OPEN;
                Log.i("NaoMiMode", "wsState:" +wsState);
                Log.i("NaoMiMode", "WS onOpen:" + response.code());
                try {
                    JSONObject sub = new JSONObject();
                    sub.put("type",   "subscribe");
                    sub.put(ROBOT_ID,   Utils.getPref(PushService.this, KEY_ROBOTID));
                    webSocket.send(sub.toString());
                    Log.i("NaoMiMode", "WS onOpen　sub: " + sub);
                } catch (JSONException e) {
                    Log.e("NaoMiMode", "失敗しました。", e);
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                Log.i("NaoMiMode", "WS onMessage: " + text);

                try {
                    JSONObject msg = new JSONObject(text);
                    String robotId   = msg.optString(ROBOT_ID);
                    String eventType = msg.optString(EVENT_TYPE);
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

                    // 只处理本设备
                    if (!robotId.equals(myRobotId)) return;
                    // —— 1. 紧急 ——
                    // 构造去重 key
                    String key = eventType + "#" + pointinfo + "#" + status;
                    long now = System.currentTimeMillis();
                    if (key.equals(lastEventKey) && now - lastEventTime < DEDUP_INTERVAL_MS) {
                        // 重复消息，忽略
                        return;
                    }
                    lastEventKey = key;
                    lastEventTime = now;

                    // 下面按原逻辑互斥分支
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
                            && pointinfo.equals(myPointInfo)
                            && !HOME.equals(pointinfo)) {
                        dispatchDismiss(pointinfo);
                        dispatchShow(pointinfo, eventType);
                        return;
                    }
                    else if (LEAVE.equals(eventType)
                            && pointinfo.equals(myPointInfo)
                            && !HOME.equals(pointinfo)) {
                        dispatchDismiss(pointinfo);
                        return;
                    }
                } catch (JSONException ignored) { }
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
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                wsState = WsState.FAILED;
                Log.e("NaoMiMode", "WS onFailure: 接続に失敗するか、異常に切断されました", t);
                if (response != null) {
                    Log.e("NaoMiMode",
                            "  HTTP code=" + response.code()
                                    + "，message=" + response.message());
                }
                ws = null;
                handler.postDelayed(() -> connectWebSocket(), 3000);
            }
        });
    }

    private void dispatchShow(String pointinfo, String eventType) {
        // —— 1. 先短暂唤醒屏幕 ——
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            // FULL_WAKE_LOCK 在 SDK ≤ 16 时可用；在更高版本与 ACQUIRE_CAUSES_WAKEUP 一起使用仍然有效
            PowerManager.WakeLock wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "NaoMiMode:WakeLock"
            );
            // 点亮屏幕 5 秒
            wakeLock.acquire(5_000L);
        }

        // —— 2. 统一走广播 ——
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
    }

    private void sendHeadsUpNotification(String roomNo,String eventType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    FULLSCREEN_CHANNEL_ID,
                    "通知",
                    NotificationManager.IMPORTANCE_HIGH  // HIGH 才能悬浮
            );
            channel.setDescription("ロボットが到着しました。通知をクリックしてください。");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        tapIntent.putExtra(FROM_PUSH, true);
        tapIntent.putExtra("roomNo", roomNo);
        tapIntent.putExtra("eventType", eventType);

        PendingIntent tapPending = PendingIntent.getActivity(
                this, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, FULLSCREEN_CHANNEL_ID)
                .setSmallIcon(R.drawable.aimlogo_white)
                .setContentTitle("ロボットが到着しました。")
                .setContentText("部屋 " + roomNo + "に到着しました。クリックして表示")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(tapPending);

        NotificationManagerCompat.from(this).notify(FULLSCREEN_NOTIFICATION_ID, builder.build());
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
