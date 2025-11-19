package com.example.naomimode;

import android.util.Log;
import org.json.JSONObject;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** 简单日志上报工具：将日志发送到 Slack */
public class SlackLogger {
    private static final String TAG = "SlackLogger";
    private static final OkHttpClient client = new OkHttpClient();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    // ✅ 替换成你的 Slack Webhook URL
    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/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";

    /** 异步发送日志 */
    public static void log(String rootId, String message) {
        new Thread(() -> {
            try {
                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(new Date());
                JSONObject json = new JSONObject();
                json.put("text", String.format("🧭 *RootID:* `%s`\n🕒 *Time:* %s\n📄 *Message:* %s",
                        rootId, time, message));

                RequestBody body = RequestBody.create(json.toString(), JSON);
                Request req = new Request.Builder().url(WEBHOOK_URL).post(body).build();
                try (Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful()) {
                        Log.e(TAG, "Slack上报失败: HTTP " + resp.code());
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Slack发送错误：" + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Slack格式错误：" + e.getMessage());
            }
        }).start();
    }
}
