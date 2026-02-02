package com.example.naomimode;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import info.mqtt.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;

import java.nio.charset.StandardCharsets;

public class MqttManager {

    public interface MsgListener {
        // ✨ 增加 retained/duplicate 两个标志，供上层去重
        void onMessage(String topic, String payload, boolean isRetained, boolean isDuplicate);
        void onError(Throwable t);
    }

    private static final String TAG = "MqttManager";

    private final Context appCtx;
    private final MqttAndroidClient client;
    private final String topic;
    private MsgListener listener;

    public MqttManager(Context ctx, String serverUri, String clientId, String topic) {
        this.appCtx = ctx.getApplicationContext();
        this.client = new MqttAndroidClient(appCtx, serverUri, clientId);
        this.topic = topic;
    }

    public void connect(String user, String pass, MsgListener lsn) {
        this.listener = lsn;

        Log.i(TAG, "========== [CONNECT] 开始 MQTT 连接 ==========");
        Log.i(TAG, "Server URI = " + client.getServerURI());
        Log.i(TAG, "Client ID  = " + client.getClientId());
        Log.i(TAG, "Topic      = " + topic);

        MqttConnectOptions opts = new MqttConnectOptions();
        // ✨ 保持会话，配合 B 方案由上层做去重
        opts.setCleanSession(false);
        opts.setAutomaticReconnect(true);
//        opts.setKeepAliveInterval(20);
//        opts.setConnectionTimeout(10);
        opts.setKeepAliveInterval(30);
        opts.setAutomaticReconnect(true);

        if (user != null) {
            opts.setUserName(user);
            Log.i(TAG, "Username   = " + user);
        }
        if (pass != null) {
            opts.setPassword(pass.toCharArray());
            Log.i(TAG, "Password   = ******");
        }

        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                Log.i(TAG, "[CALLBACK] connectComplete, reconnect=" + reconnect + ", serverURI=" + serverURI);
                enableBufferingSafely();

                LocalBroadcastManager.getInstance(appCtx)
                        .sendBroadcast(new Intent("com.example.naomimode.MQTT_CONNECTED"));

                trySubscribe(reconnect);
            }

            @Override
            public void connectionLost(Throwable cause) {
                Log.w(TAG, "[CALLBACK] connectionLost: " + (cause == null ? "null" : cause.getMessage()), cause);
            }

            @Override
            public void messageArrived(String t, MqttMessage m) {
                String payload = new String(m.getPayload(), StandardCharsets.UTF_8).trim();
                boolean retained = m.isRetained();
                boolean duplicate = m.isDuplicate();
                Log.i(TAG, "[CALLBACK] topic=" + t + ", qos=" + m.getQos() + ", retained=" + retained + ", dup=" + duplicate);
                Log.v(TAG, "[PAYLOAD] " + payload);
                SlackLogger.log("niigata", "[PAYLOAD] " + payload);
                if (listener != null) {
                    listener.onMessage(t, payload, retained, duplicate);
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                Log.i(TAG, "[CALLBACK] deliveryComplete: " + token);
            }
        });

        try {
            Log.i(TAG, "调用 client.connect() …");
            client.connect(opts, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    SlackLogger.log("niigata", "[ACTION] 连接成功！");
                    Log.i(TAG, "[ACTION] 连接成功！");
                    enableBufferingSafely();

                    LocalBroadcastManager.getInstance(appCtx)
                            .sendBroadcast(new Intent("com.example.naomimode.MQTT_CONNECTED"));

                    trySubscribe(false);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    SlackLogger.log("niigata", "[ACTION] 连接失败！");
                    Log.e(TAG, "[ACTION] 连接失败: " + exception, exception);
                    if (listener != null) listener.onError(exception);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "[EXCEPTION] 调用 connect 抛出异常: " + e, e);
            if (listener != null) listener.onError(e);
        }
    }

    private void enableBufferingSafely() {
        try {
            if (!client.isConnected()) return;
            DisconnectedBufferOptions buf = new DisconnectedBufferOptions();
            buf.setBufferEnabled(true);
            buf.setBufferSize(100);
            buf.setPersistBuffer(false);
            buf.setDeleteOldestMessages(true);
            client.setBufferOpts(buf);
        } catch (Throwable t) {
            Log.w(TAG, "setBufferOpts failed (service not ready yet?): " + t.getMessage());
        }
    }

    private void trySubscribe() {
        trySubscribe(false);
    }

     private void trySubscribe(boolean reconnect) {
        try {
            if (!client.isConnected()) {
                Log.w(TAG, "订阅前检测：client 未连接！");
                return;
            }
            if (reconnect) {
                try {
                    Log.i(TAG, "reconnect=true → 先 unsubscribe(" + topic + ")");
                    client.unsubscribe(topic);
                } catch (Exception ex) {
                    Log.w(TAG, "unsubscribe ignored: " + ex.getMessage());
                }
            }
            Log.i(TAG, "调用 subscribe(topic=" + topic + ")");
            client.subscribe(topic, 1, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    SlackLogger.log("niigata", "[ACTION] 订阅成功: " + topic);
                    Log.i(TAG, "[ACTION] 订阅成功: " + topic);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e(TAG, "[ACTION] 订阅失败: " + exception, exception);
                    if (listener != null) listener.onError(exception);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "[EXCEPTION] 订阅时异常: " + e, e);
            if (listener != null) listener.onError(e);
        }
    }

    public void close() {
        try { if (client.isConnected()) client.disconnect(); } catch (Exception ignored) {}
        try { client.unregisterResources(); client.close(); } catch (Exception ignored) {}
    }
}
