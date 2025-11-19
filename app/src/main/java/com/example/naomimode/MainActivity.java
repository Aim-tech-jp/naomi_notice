package com.example.naomimode;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.bumptech.glide.Glide;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;

import io.github.g00fy2.quickie.QRResult;
import io.github.g00fy2.quickie.ScanCustomCode;
import io.github.g00fy2.quickie.config.BarcodeFormat;
import io.github.g00fy2.quickie.config.ScannerConfig;
import com.example.naomimode.SlackLogger;
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private static final String PREFS       = "app_prefs";
    private static final String KEY_ROBOTID = "qr_robot_id";
    private static final String KEY_FLASH_ENABLED = "flash_enabled";
    private static final String KEY_DIALOG_MODE   = "dialog_mode";

    private static final String FROM_PUSH = "from_push";
    private static final int REQ_POST_NOTI = 1001;

    private DrawerLayout drawer;
    private View navMenu;
    private ImageView ivMenuToggle;

    // 主界面
    private TextView tvStatus, tvSubInfo, tvTime;
    private Button   btnStopVoice;

    // 侧栏：TextView 开关
    private EditText robotIdEt;
    private TextView swFlash, swDialog;

    private Dialog arrivalDialog;

    // 手电筒/闪烁
    private CameraManager cameraManager;
    private String  cameraIdWithFlash;
    private boolean isTorchOn = false;
    private Handler dialogFlashHandler;
    private Runnable dialogFlashRunnable;
    private Handler homeFlashHandler;
    private Runnable homeFlashRunnable;
    private Handler torchHandler;
    private Runnable torchRunnable;
    private MediaPlayer playerArrive;
    private Handler arriveHandler;
    private Runnable arriveRunnable;
    private int arriveCount;
    private boolean flashEnabled;
    private boolean dialogMode;
    private boolean receiversRegistered = false;

    //扫码
    private final ActivityResultLauncher<ScannerConfig> scanCustomCode =
            registerForActivityResult(
                    new ScanCustomCode(),
                    result -> {
                        if (result instanceof QRResult.QRSuccess) {
                            String raw = ((QRResult.QRSuccess) result).getContent().getRawValue().trim();
                            try {
                                JSONObject jo = new JSONObject(raw);
                                String robot_id = jo.optString("robot_id", "");
                                if (robot_id.isEmpty()) {
                                    Toast.makeText(this, "QRに robot_id がありません", Toast.LENGTH_SHORT).show();
                                    enforceRobotIdSafely();
                                    return;
                                }
//                                getSharedPreferences(PREFS, MODE_PRIVATE)
//                                        .edit()
//                                        .putString(KEY_ROBOTID, robot_id)
//                                        .apply();
                                SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                                sp.edit()
                                        .putString(KEY_ROBOTID, robot_id)
                                        .apply();
                                String savedCode   = sp.getString(KEY_ROBOTID, "");
                                robotIdEt.setText(savedCode);
                                recreate();
                            } catch (JSONException e) {
                                Toast.makeText(this, "QRコードの内容が期待された JSON 形式ではありません", Toast.LENGTH_SHORT).show();
                                enforceRobotIdSafely();
                            }
                        } else if (result instanceof QRResult.QRError) {
                            Exception error = ((QRResult.QRError) result).getException();
                            Log.e("Quickie", "スキャン中にエラーが発生しました", error);
                            Toast.makeText(this, "スキャン中にエラーが発生しました", Toast.LENGTH_SHORT).show();
                            enforceRobotIdSafely();
                        } else if (result instanceof QRResult.QRUserCanceled) {
                            Toast.makeText(this, "スキャンコードはキャンセルされており、バインド後にのみ使用できます", Toast.LENGTH_SHORT).show();
                            enforceRobotIdSafely();
                        }
                    }
            );

    private boolean safeStartLockTask() {
        try {
            if (!isAppLocked()) startLockTask();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "startLockTask not permitted: " + t.getMessage());
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        hideSystemUI();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            );
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }

        initCameraFlashAvailability();

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        flashEnabled = sp.getBoolean(KEY_FLASH_ENABLED, false);
        dialogMode   = sp.getBoolean(KEY_DIALOG_MODE,   false);

        initUI();
        registerReceivers();
        requestPostNotificationIfNeeded();
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        new Handler(Looper.getMainLooper()).post(() -> {
            if (hasRobotId()) {
                startPushServiceIfReady();
                handlePushIntent(getIntent());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    safeStartLockTask();
                }
            } else {
                enforceRobotIdSafely();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiversIfNeeded();
        if (isAppLocked() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try { stopLockTask(); } catch (Exception ignored) {}
        }
        stopArriveVoice();
        stopFlashAll();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            );
        }
        handlePushIntent(intent);
    }

    // 画面UI
    private void initUI() {
        drawer       = findViewById(R.id.drawer_layout);
        navMenu      = findViewById(R.id.navMenu);
        ivMenuToggle = findViewById(R.id.ivMenuToggle);
        if (ivMenuToggle != null) {
            ivMenuToggle.setOnClickListener(v -> {
                if (drawer != null) {
                    if (drawer.isDrawerOpen(GravityCompat.END)) drawer.closeDrawer(GravityCompat.END);
                    else drawer.openDrawer(GravityCompat.END);
                }
            });
        }

        // 主画面控件
        tvStatus     = findViewById(R.id.tv_subinfo);
        tvSubInfo    = findViewById(R.id.tv_subinfo);
        tvTime       = findViewById(R.id.tv_time);
        btnStopVoice = findViewById(R.id.btn_stop_voice);
        if (btnStopVoice != null) btnStopVoice.setOnClickListener(v -> stopArriveVoice());

        // 侧栏控件（TextView 伪开关）
        if (navMenu != null) {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);

            robotIdEt = navMenu.findViewById(R.id.etCode);
            if (robotIdEt != null) {
                robotIdEt.setText(sp.getString(KEY_ROBOTID, ""));
                // 点击重新扫码换绑
                robotIdEt.setOnClickListener(v -> {
                    Toast.makeText(this, "コードをスキャンしてください", Toast.LENGTH_SHORT).show();
                    launchQrScanner();
                });
            }

            swFlash  = navMenu.findViewById(R.id.sw_flash);
            swDialog = navMenu.findViewById(R.id.sw_dialog);

            applyFlashUI();
            applyDialogUI();

            if (swFlash != null) {
                swFlash.setOnClickListener(v -> {
                    boolean newVal = !flashEnabled;
                    flashEnabled = newVal;
                    sp.edit().putBoolean(KEY_FLASH_ENABLED, newVal).apply();
                    applyFlashUI();
                    Toast.makeText(this, newVal ? "フラッシュ：open" : "フラッシュ：off", Toast.LENGTH_SHORT).show();
                    if (!newVal) stopFlashAll();
                });
            }
            if (swDialog != null) {
                swDialog.setOnClickListener(v -> {
                    boolean newVal = !dialogMode;
                    dialogMode = newVal;
                    sp.edit().putBoolean(KEY_DIALOG_MODE, newVal).apply();
                    applyDialogUI();
                    Toast.makeText(this, newVal ? "Mode：ポップアップ" : "Mode：ホーム", Toast.LENGTH_SHORT).show();
                });
            }
        } else {
            Log.w(TAG, "navMenu is null, side drawer UI not available in this layout.");
        }

        // 若尚未绑定，给主屏一个提示
        if (!hasRobotId() && tvStatus != null) {
            tvStatus.setText("まずコードをスキャンしてください");
        }
    }

    private void applyFlashUI() {
        if (swFlash == null) return;
        swFlash.setText(flashEnabled ? "フラッシュ：open" : "フラッシュ：off");
        swFlash.setActivated(flashEnabled);
    }

    private void applyDialogUI() {
        if (swDialog == null) return;
        swDialog.setText(dialogMode ? "ポップアップ：open" : "ポップアップ：off");
        swDialog.setActivated(dialogMode);
    }

    // 获取通知权限
    private void requestPostNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTI);
            }
        }
    }

    // robotId
    private boolean hasRobotId() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String robotId = sp.getString(KEY_ROBOTID, null);
        return robotId != null && !robotId.trim().isEmpty();
    }

    //未扫码时
    private void enforceRobotIdSafely() {
        if (hasRobotId()) return;

        if (tvStatus != null) tvStatus.setText("まずコードをスキャンしてください");
        if (tvSubInfo != null) tvSubInfo.setText("");
        if (tvTime   != null) tvTime.setText("");

        stopArriveVoice();
        stopFlashAll();

        // 透明拦截层
        View homeOverlay = findViewById(R.id.overlay_home_flash);
        if (homeOverlay != null) {
            homeOverlay.setVisibility(View.VISIBLE);
            homeOverlay.setOnClickListener(v -> {});
            homeOverlay.setAlpha(0f);
        }

        new Handler(Looper.getMainLooper()).postDelayed(this::launchQrScanner, 150);
    }

    private void launchQrScanner() {
        ScannerConfig config = new ScannerConfig.Builder()
                .setBarcodeFormats(Collections.singletonList(BarcodeFormat.FORMAT_QR_CODE))
                .setOverlayStringRes(R.string.scan_qr_code)
                .setShowTorchToggle(true)
                .setUseFrontCamera(false)
                .setKeepScreenOn(true)
                .build();
        scanCustomCode.launch(config);
    }

    //Service
    private void startPushServiceIfReady() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String robotId = sp.getString(KEY_ROBOTID, "");
        if (robotId == null || robotId.isEmpty()) {
            Log.w(TAG, "startPushServiceIfReady: robot_id not ready, skip start service.");
            return;
        }
        Intent svc = new Intent(this, PushService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
        else startService(svc);
    }

    private void handlePushIntent(Intent intent) {
        if (intent == null) return;
        boolean fromPush = intent.getBooleanExtra(FROM_PUSH, false);
        if (fromPush) {
        }
    }

    //  System UI
    private boolean isAppLocked() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int mode = am.getLockTaskModeState();
            return mode == ActivityManager.LOCK_TASK_MODE_LOCKED
                    || mode == ActivityManager.LOCK_TASK_MODE_PINNED;
        } else {
            return am.isInLockTaskMode();
        }
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
    }

    // 呼吸灯闪光是否可用
    private void initCameraFlashAvailability() {
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics cc = cameraManager.getCameraCharacteristics(id);
                Boolean hasFlash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer lensFacing = cc.get(CameraCharacteristics.LENS_FACING);
                if (hasFlash != null && hasFlash
                        && lensFacing != null
                        && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraIdWithFlash = id;
                    break;
                }
            }
        } catch (Exception e) {
            cameraIdWithFlash = null;
        }
    }

    //  MQTT 推送接收：画面更新 /弹框 /闪烁/语音
    private final BroadcastReceiver eventReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!"com.example.naomimode.MQTT_EVENT".equals(intent.getAction())) return;

            String event    = intent.getStringExtra("event");
            String roomId   = intent.getStringExtra("room_id");
            String floorId  = intent.getStringExtra("floor_id");
            String state    = intent.getStringExtra("state");
            String error    = intent.getStringExtra("error_code");
            long   ts       = intent.getLongExtra("timestamp_ms", System.currentTimeMillis());
            if ("0".equals(floorId) || "0.0".equals(floorId) || "null".equalsIgnoreCase(floorId)) {
                floorId = "";
            }
            Log.i("mainActivity", "---mainActivity- roomId=----"+roomId);
            Log.i("mainActivity", "---mainActivity- floorId=----"+floorId);
            if (!hasRobotId()) {
                enforceRobotIdSafely();
                return;
            }

            String EventText = EventTextMapper.Text(event, roomId, floorId, state, error);
            if (tvStatus != null) tvStatus.setText(EventText);
            if (tvTime   != null) tvTime.setText(Utils.formatLocalTime(ts));

            if ("arrive".equals(event) && roomId != null && !roomId.isEmpty()) {
                startArriveVoice();
            }

            if (flashEnabled) {
                startFlashAllOnMain();
            } else {
                stopFlashAll();
            }

            if (dialogMode) {
                showMirrorDialog(EventText);
            }
        }
    };

    private final BroadcastReceiver stopVoiceReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if ("com.example.naomimode.STOP_VOICE".equals(intent.getAction())) {
                stopArriveVoice();
            }
        }
    };

    private void registerReceivers() {
        if (receiversRegistered) return;
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(eventReceiver,  new IntentFilter("com.example.naomimode.MQTT_EVENT"));
        lbm.registerReceiver(stopVoiceReceiver, new IntentFilter("com.example.naomimode.STOP_VOICE"));
        receiversRegistered = true;
    }

    private void unregisterReceiversIfNeeded() {
        if (!receiversRegistered) return;
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        try { lbm.unregisterReceiver(eventReceiver); } catch (Exception ignored) {}
        try { lbm.unregisterReceiver(stopVoiceReceiver); } catch (Exception ignored) {}
        receiversRegistered = false;
    }

    // ———————————— 到房间语音：播放3次 ————————————
    private void startArriveVoice() {
        stopArriveVoice();
        playerArrive = MediaPlayer.create(this, R.raw.arrival_aoi);
        arriveCount = 0;
        arriveHandler = new Handler(Looper.getMainLooper());
        arriveRunnable = () -> {
            if (playerArrive != null) {
                try {
                    playerArrive.seekTo(0);
                    playerArrive.start();
                } catch (IllegalStateException ignored) {}
            }
        };
        if (playerArrive != null) {
            playerArrive.setOnCompletionListener(mp -> {
                arriveCount++;
                if (arriveCount < 3 && arriveHandler != null) {
                    arriveHandler.postDelayed(arriveRunnable, 2000);
                } else {
                    stopArriveVoice();
                }
            });
            if (btnStopVoice != null) btnStopVoice.setVisibility(View.VISIBLE);
            try { playerArrive.start(); } catch (Exception ignored) {}
        }
    }

    private void stopArriveVoice() {
        if (arriveHandler != null && arriveRunnable != null) {
            arriveHandler.removeCallbacks(arriveRunnable);
            arriveRunnable = null;
        }
        if (playerArrive != null) {
            playerArrive.setOnCompletionListener(null);
            try { if (playerArrive.isPlaying()) playerArrive.stop(); } catch (Exception ignored) {}
            try { playerArrive.release(); } catch (Exception ignored) {}
            playerArrive = null;
        }
        if (btnStopVoice != null) btnStopVoice.setVisibility(View.GONE);
    }

    // ———————————— 主屏闪烁 & 呼吸灯闪烁 ————————————
    private void startFlashAllOnMain() {
        // A. 主屏遮罩闪烁（overlay_home_flash）
        View homeOverlay = findViewById(R.id.overlay_home_flash);
        if (homeOverlay != null) {
            final int[] homeCount = {0};
            homeFlashHandler = new Handler(Looper.getMainLooper());
            homeFlashRunnable = new Runnable() {
                boolean visible = true;
                @Override public void run() {
                    if (homeCount[0] >= 6) { homeOverlay.setVisibility(View.GONE); return; }
                    homeOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
                    visible = !visible;
                    homeCount[0]++;
                    homeFlashHandler.postDelayed(this, 500);
                }
            };
            homeFlashHandler.post(homeFlashRunnable);
        }

        // B. 呼吸灯闪烁
        if (cameraIdWithFlash != null) {
            final int[] torchCount = {0};
            torchHandler = new Handler(Looper.getMainLooper());
            torchRunnable = new Runnable() {
                @Override public void run() {
                    if (torchCount[0] >= 6) { stopFlashingTorch(); return; }
                    boolean newState = !isTorchOn;
                    try {
                        cameraManager.setTorchMode(cameraIdWithFlash, newState);
                        isTorchOn = newState;
                    } catch (SecurityException se) {
                        Log.w(TAG, "No CAMERA permission for torch: " + se.getMessage());
                    } catch (CameraAccessException ignored) {}
                    torchCount[0]++;
                    torchHandler.postDelayed(this, 500);
                }
            };
            torchHandler.post(torchRunnable);
        }
    }

    private void stopFlashAll() {
        if (homeFlashHandler != null && homeFlashRunnable != null) {
            try { homeFlashHandler.removeCallbacks(homeFlashRunnable); } catch (Exception ignored) {}
            View homeOverlay = findViewById(R.id.overlay_home_flash);
            if (homeOverlay != null) homeOverlay.setVisibility(View.GONE);
        }
        if (torchHandler != null && torchRunnable != null) {
            try { torchHandler.removeCallbacks(torchRunnable); } catch (Exception ignored) {}
        }
        stopFlashingTorch();
    }

    private void stopFlashingTorch() {
        if (cameraIdWithFlash != null && isTorchOn) {
            try {
                cameraManager.setTorchMode(cameraIdWithFlash, false);
                isTorchOn = false;
            } catch (SecurityException se) {
                Log.w(TAG, "No CAMERA permission for torch: " + se.getMessage());
            } catch (CameraAccessException ignored) {}
        }
    }

    // ———————————— popup ————————————
    private void showMirrorDialog(String EventText) {
        if (arrivalDialog != null && arrivalDialog.isShowing()) {
            updateDialogUI(EventText);
            return;
        }
        arrivalDialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        arrivalDialog.setContentView(R.layout.dialog_arrival);
        arrivalDialog.setCancelable(false);
        arrivalDialog.setCanceledOnTouchOutside(false);
        arrivalDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        arrivalDialog.show();
        updateDialogUI(EventText);

        Button btnClose = arrivalDialog.findViewById(R.id.btn_close);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                stopFlashAll();
                arrivalDialog.dismiss();
            });
        }

        if (flashEnabled) {
            View overlayDialogFlash = arrivalDialog.findViewById(R.id.overlay_dialog_flash);
            if (overlayDialogFlash != null) {
                final int[] dialogCount = {0};
                dialogFlashHandler = new Handler(Looper.getMainLooper());
                dialogFlashRunnable = new Runnable() {
                    boolean visible = true;
                    @Override public void run() {
                        if (dialogCount[0] >= 6 || arrivalDialog == null || !arrivalDialog.isShowing()) {
                            overlayDialogFlash.setVisibility(View.GONE);
                            return;
                        }
                        overlayDialogFlash.setVisibility(visible ? View.VISIBLE : View.GONE);
                        visible = !visible;
                        dialogCount[0]++;
                        dialogFlashHandler.postDelayed(this, 500);
                    }
                };
                dialogFlashHandler.post(dialogFlashRunnable);
            }
        }
    }

    private void updateDialogUI(String EventText) {
        if (arrivalDialog == null) return;
        TextView tvInfo   = arrivalDialog.findViewById(R.id.tv_roomNo);
        ImageView gifImage= arrivalDialog.findViewById(R.id.gifImage);
        ImageView pngImage= arrivalDialog.findViewById(R.id.pngImage);

        if (tvInfo != null) {
            tvInfo.setText(EventText);
        }
        if (gifImage != null) {
            Glide.with(arrivalDialog.getContext()).asGif().load(R.drawable.naomi_anim3).into(gifImage);
            gifImage.setVisibility(View.VISIBLE);
        }
        if (pngImage != null) {
            pngImage.setVisibility(View.VISIBLE);
        }
    }
}
