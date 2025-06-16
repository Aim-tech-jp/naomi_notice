package com.example.naomimode;

import android.app.ActivityManager;
import android.graphics.Color;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.view.Window;
import android.view.WindowManager;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import androidx.core.view.GravityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.util.Collections;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;

import com.bumptech.glide.Glide;

import org.json.JSONException;
import org.json.JSONObject;
import io.github.g00fy2.quickie.QRResult;
import io.github.g00fy2.quickie.ScanCustomCode;
import io.github.g00fy2.quickie.config.BarcodeFormat;
import io.github.g00fy2.quickie.config.ScannerConfig;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS    = "app_prefs";
    private static final String KEY_ROBOTID   = "qr_robot_id";
    private static final String KEY_PWD  = "qr_auth_key";
    private static final String KEY_ROOM = "qr_room_id";
    private DrawerLayout drawer;
    private View         navMenu;
    private EditText     etCode, tvRoom, tvPwd;
    private ImageView    ivMenuToggle;
    private Dialog arrivalDialog;
    private CameraManager cameraManager;
    private String cameraIdWithFlash;
    private boolean isTorchOn = false;
    private Handler dialogFlashHandler;
    private Runnable dialogFlashRunnable;
    private Handler homeFlashHandler;
    private Runnable homeFlashRunnable;
    private Handler torchHandler;
    private Runnable torchRunnable;
    private static final String ARRIVE ="arrive";
    private static final String EMERGENCY ="emergency";
    private static final String HOME ="home";
    private static final String POINTINFO = "pointinfo";
    private static final String AUTH_KEY = "auth_key";
    private static final String ROBOT_ID = "robot_id";
    private static final String FROM_PUSH = "from_push";
    private MediaPlayer playerHome;
    private Handler homeHandler;
    private Runnable homeRunnable;
    private int homeCount;
    private MediaPlayer playerKinkyu;
    private Handler kinkyuHandler;
    private Runnable kinkyuRunnable;
    private MediaPlayer playerRoom;
    private Handler roomHandler;
    private Runnable roomRunnable;
    enum Scene { ROOM, HOME, KINKYU }
    private Scene currentScene;
    private String currentEventType;
    private String myRoomNo;

    private final ActivityResultLauncher<ScannerConfig> scanCustomCode =
            registerForActivityResult(
                    new ScanCustomCode(),
                    result -> {
                        if (result instanceof QRResult.QRSuccess) {
                            String raw = ((QRResult.QRSuccess) result)
                                    .getContent().getRawValue().trim();
                            try {
                                // JSON
                                JSONObject jo = new JSONObject(raw);
                                String robot_id = jo.getString(ROBOT_ID);
                                String auth_key = jo.getString(AUTH_KEY);
                                String pointinfo = jo.getString(POINTINFO);

                                // SharedPreferences
                                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                        .putString(KEY_ROBOTID, robot_id)
                                        .putString(KEY_PWD, auth_key)
                                        .putString(KEY_ROOM, pointinfo)
                                        .apply();

                                // Service
                                Intent svc = new Intent(this, PushService.class);
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    startForegroundService(svc);
                                } else {
                                    startService(svc);
                                }
                                // UI
                                initUI();
                            } catch (JSONException e) {
                                Toast.makeText(this,
                                        "QRコードの内容が期待された JSON 形式ではありません",
                                        Toast.LENGTH_SHORT).show();
                            }

                        } else if (result instanceof QRResult.QRError) {
                            Exception error = ((QRResult.QRError) result).getException();
                            Log.e("Quickie", "スキャン中にエラーが発生しました", error);
                            Toast.makeText(this,
                                    "スキャン中にエラーが発生しました",
                                    Toast.LENGTH_SHORT).show();

                        } else if (result instanceof QRResult.QRUserCanceled) {
                            Toast.makeText(this,
                                    "スキャンがキャンセルされました",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
            );


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            startLockTask();
        }
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
        } catch (CameraAccessException e) {
            e.printStackTrace();
            cameraIdWithFlash = null;
        }

        // SharedPreferences
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        myRoomNo = sp.getString(KEY_ROOM, "");
        if (!sp.contains(KEY_ROBOTID) || !sp.contains(KEY_PWD) || !sp.contains(KEY_ROOM)) {
            ScannerConfig config = new ScannerConfig.Builder()
                    .setBarcodeFormats(Collections.singletonList(BarcodeFormat.FORMAT_QR_CODE))
                    .setOverlayStringRes(R.string.scan_qr_code)
                    .setShowTorchToggle(true)
                    .setUseFrontCamera(false)
                    .setKeepScreenOn(true)
                    .build();
            scanCustomCode.launch(config);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        1001
                );
            }
        }

        // Service
        Intent svc = new Intent(this, PushService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        initUI();
        handlePushIntent(getIntent());

        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        // 已有的到达弹框广播
        lbm.registerReceiver(arrivalReceiver,
                new IntentFilter("com.example.naomimode.SHOW_ARRIVAL_DIALOG"));
        // 新增：关闭弹框广播
        lbm.registerReceiver(dismissReceiver,
                new IntentFilter("com.example.naomimode.DISMISS_ARRIVAL_DIALOG"));
    }

    private final BroadcastReceiver arrivalReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String roomNo = intent.getStringExtra("roomNo");
            String evtType = intent.getStringExtra("eventType");
            currentEventType = evtType;
            if (roomNo != null && roomNo.equals(myRoomNo)) {
                showArrivalDialog(roomNo);
            }
        }
    };

    private void initUI() {
        drawer = findViewById(R.id.drawer_layout);
        navMenu = findViewById(R.id.navMenu);
        ivMenuToggle = findViewById(R.id.ivMenuToggle);

        ivMenuToggle.setOnClickListener(v -> {
            if (drawer.isDrawerOpen(GravityCompat.END)) drawer.closeDrawer(GravityCompat.END);
            else drawer.openDrawer(GravityCompat.END);
        });
        // EditText
        etCode = navMenu.findViewById(R.id.etCode);
        tvRoom = navMenu.findViewById(R.id.tv_room);
        tvPwd  = navMenu.findViewById(R.id.tv_pwd);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        etCode.setText(sp.getString(KEY_ROBOTID, ""));
        tvRoom.setText(sp.getString(KEY_ROOM, ""));
        tvPwd.setText(sp.getString(KEY_PWD, ""));
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

    private void handlePushIntent(Intent intent) {
        if (intent == null) return;
        boolean fromPush = intent.getBooleanExtra(FROM_PUSH, false);
        if (fromPush) {
            Log.i("NaoMiMode", "fromPush:" + fromPush);
            String roomNo = intent.getStringExtra("roomNo");
            if (roomNo != null) {
                Log.i("NaoMiMode", "roomNo:" + roomNo);
                showArrivalDialog(roomNo);
            } else {
                Log.w("NaoMiMode", "roomNo 为 null");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.unregisterReceiver(arrivalReceiver);
        lbm.unregisterReceiver(dismissReceiver);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            stopLockTask();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!isAppLocked()) {
            startLockTask();
        }
        hideSystemUI();
    }

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

    @Override
    protected void onPause() {
        super.onPause();
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

    private void showSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
    }

    private void showArrivalDialog(String roomNo) {
        Log.i("NaoMiMode", "roomNo:"+roomNo);
        arrivalDialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        arrivalDialog.setContentView(R.layout.dialog_arrival);

        arrivalDialog.setCancelable(false);
        arrivalDialog.setCanceledOnTouchOutside(false);
        arrivalDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        arrivalDialog.show();

        TextView tvInfo = arrivalDialog.findViewById(R.id.tv_roomNo);
        ImageView gifImage = arrivalDialog.findViewById(R.id.gifImage);
        ImageView pngImage = arrivalDialog.findViewById(R.id.pngImage);

        Window dlgWindow = arrivalDialog.getWindow();
        if (dlgWindow != null) {
            dlgWindow.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        }

        startFlashAll();

        if (EMERGENCY.equals(currentEventType)) {
            startKinkyuLoop();
            currentScene = Scene.KINKYU;
            tvInfo.setText("緊急ボタンが押されました");
            pngImage.setVisibility(View.VISIBLE);
        } else if (ARRIVE.equals(currentEventType) && HOME.equals(roomNo)) {
            // 1/2/5：arrive home 的场合
            startHomeLoop();
            currentScene = Scene.HOME;
            tvInfo.setText("ホームに戻りました");
            pngImage.setVisibility(View.VISIBLE);
        } else if (ARRIVE.equals(currentEventType) && myRoomNo.equals(roomNo)) {
            // 5/6：其它房间 arrive/leave
            startRoomLoop();
            currentScene = Scene.ROOM;
            tvInfo.setText("部屋 " + roomNo + " に到着しました");
            Glide.with(arrivalDialog.getContext())
                    .asGif()
                    .load(R.drawable.naomi_anim3)
                    .into(gifImage);
            gifImage.setVisibility(View.VISIBLE);
        }

        Button btnClose = arrivalDialog.findViewById(R.id.btn_close);
        btnClose.setOnClickListener(v -> {
            switch (currentScene) {
                case ROOM:   stopRoomLoop();    break;
                case HOME:   stopHomeLoop();    break;
                case KINKYU: stopKinkyuLoop();  break;
            }
            stopFlashAll();
            arrivalDialog.dismiss();
        });

    }

private void startHomeLoop() {
    // 先把旧的停掉
    stopHomeLoop();
    stopKinkyuLoop();

    playerHome = MediaPlayer.create(this, R.raw.karaoke_home);
    homeCount = 0;

    // 用专门的 Handler 来 postDelayed
    homeHandler = new Handler(Looper.getMainLooper());
    homeRunnable = new Runnable() {
        @Override
        public void run() {
            if (playerHome != null) {
                try {
                    playerHome.seekTo(0);
                    playerHome.start();
                } catch (IllegalStateException ignore) {
                    // 播放器已不可用，就不再重播
                }
            }
        }
    };

    playerHome.setOnCompletionListener(mp -> {
        homeCount++;
        if (homeCount < 3) {
            // 完成后延迟 2 秒再重播
            homeHandler.postDelayed(homeRunnable, 2000);
        } else {
            // 第 3 次播完就清理并关闭对话框
            stopHomeLoop();
            if (arrivalDialog != null && arrivalDialog.isShowing()) {
                arrivalDialog.dismiss();
            }
        }
    });

    // 首次启动
    playerHome.start();
}

    private void stopHomeLoop() {
        // 1. 先移除所有还没执行的重播任务
        if (homeHandler != null && homeRunnable != null) {
            homeHandler.removeCallbacks(homeRunnable);
            homeRunnable = null;
        }

        // 2. 再释放 MediaPlayer
        if (playerHome != null) {
            playerHome.setOnCompletionListener(null);
            if (playerHome.isPlaying()) {
                playerHome.stop();
            }
            playerHome.release();
            playerHome = null;
        }
    }
private void startKinkyuLoop() {
    // 先把旧的都停掉
    stopHomeLoop();
    stopKinkyuLoop();

    playerKinkyu = MediaPlayer.create(this, R.raw.kinkyu);
    kinkyuHandler = new Handler(Looper.getMainLooper());
    // 定义重播的 Runnable
    kinkyuRunnable = new Runnable() {
        @Override
        public void run() {
            if (playerKinkyu != null) {
                try {
                    playerKinkyu.seekTo(0);
                    playerKinkyu.start();
                } catch (IllegalStateException ignore) {
                    // 播放器已经不可用，就不再重播
                }
            }
        }
    };
    // 当一次播放完成后，延迟 2 秒执行上面的 Runnable
    playerKinkyu.setOnCompletionListener(mp -> {
        kinkyuHandler.postDelayed(kinkyuRunnable, 2000);
    });
    // 首次播放
    playerKinkyu.start();
}

    private void stopKinkyuLoop() {
        // 先移除所有还没执行的重播任务
        if (kinkyuHandler != null && kinkyuRunnable != null) {
            kinkyuHandler.removeCallbacks(kinkyuRunnable);
            kinkyuRunnable = null;
        }
        // 再释放播放器
        if (playerKinkyu != null) {
            playerKinkyu.setOnCompletionListener(null);
            if (playerKinkyu.isPlaying()) {
                playerKinkyu.stop();
            }
            playerKinkyu.release();
            playerKinkyu = null;
        }
    }
    private void startRoomLoop() {
        stopRoomLoop();

        playerRoom = MediaPlayer.create(this, R.raw.karaoke);
        roomHandler = new Handler(Looper.getMainLooper());

        roomRunnable = new Runnable() {
            @Override
            public void run() {
                if (playerRoom != null) {
                    try {
                        playerRoom.seekTo(0);
                        playerRoom.start();
                    } catch (IllegalStateException ignore) {
                        // 播放器不可用，不再重播
                    }
                }
            }
        };

        playerRoom.setOnCompletionListener(mp -> {
            // 每次播放完成后延迟 10 秒
            roomHandler.postDelayed(roomRunnable, 10_000);
        });

        // 首次启动
        playerRoom.start();
    }

    private void stopRoomLoop() {
        // 1. 先移除所有还未执行的回调
        if (roomHandler != null && roomRunnable != null) {
            roomHandler.removeCallbacks(roomRunnable);
            roomRunnable = null;
        }
        // 2. 再释放 MediaPlayer
        if (playerRoom != null) {
            playerRoom.setOnCompletionListener(null);
            if (playerRoom.isPlaying()) {
                playerRoom.stop();
            }
            playerRoom.release();
            playerRoom = null;
        }
    }

    private void startFlashAll() {
        // ————————————— A. Home ——————————————
        View homeOverlay = findViewById(R.id.overlay_home_flash);
        if (homeOverlay != null) {
            final int[] homeCount = {0};
            homeFlashHandler = new Handler(Looper.getMainLooper());
            homeFlashRunnable = new Runnable() {
                boolean visible = true;
                @Override
                public void run() {
                    if (homeCount[0] >= 6 || !isDialogShowing()) {
                        homeOverlay.setVisibility(View.GONE);
                        return;
                    }
                    homeOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
                    visible = !visible;
                    homeCount[0]++;
                    homeFlashHandler.postDelayed(this, 500);
                }
            };
            homeFlashHandler.post(homeFlashRunnable);
        }

        // ————————————— B. Dialog ——————————————
        View overlayDialogFlash = arrivalDialog.findViewById(R.id.overlay_dialog_flash);
        if (overlayDialogFlash != null) {
            final int[] dialogCount = {0};
            dialogFlashHandler = new Handler(Looper.getMainLooper());
            dialogFlashRunnable = new Runnable() {
                boolean visible = true;
                @Override
                public void run() {
                    if (dialogCount[0] >= 6 || !isDialogShowing()) {
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

        // ————————————— C. camera ——————————————
        if (cameraIdWithFlash != null) {
            final int[] torchCount = {0};
            torchHandler = new Handler(Looper.getMainLooper());
            torchRunnable = new Runnable() {
                @Override
                public void run() {
                    if (torchCount[0] >= 6 || !isDialogShowing()) {
                        stopFlashingTorch();
                        return;
                    }
                    boolean newState = !isTorchOn;
                    try {
                        cameraManager.setTorchMode(cameraIdWithFlash, newState);
                        isTorchOn = newState;
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                    torchCount[0]++;
                    torchHandler.postDelayed(this, 500);
                }
            };
            torchHandler.post(torchRunnable);
        }
    }

    private void stopFlashAll() {
        // ————————————— A. 停止 Home 界面闪烁 ——————————————
        if (homeFlashHandler != null && homeFlashRunnable != null) {
            homeFlashHandler.removeCallbacks(homeFlashRunnable);
            View homeOverlay = findViewById(R.id.overlay_home_flash);
            if (homeOverlay != null) {
                homeOverlay.setVisibility(View.GONE);
            }
        }

        // ————————————— B. 停止对话框内部闪烁 ——————————————
        if (dialogFlashHandler != null && dialogFlashRunnable != null) {
            dialogFlashHandler.removeCallbacks(dialogFlashRunnable);
            View overlayDialogFlash = arrivalDialog.findViewById(R.id.overlay_dialog_flash);
            if (overlayDialogFlash != null) {
                overlayDialogFlash.setVisibility(View.GONE);
            }
        }

        // ————————————— C. 停止摄像头闪光灯闪烁 ——————————————
        if (torchHandler != null && torchRunnable != null) {
            torchHandler.removeCallbacks(torchRunnable);
        }
        stopFlashingTorch();
    }

    private boolean isDialogShowing() {
        return arrivalDialog != null && arrivalDialog.isShowing();
    }

    private void stopFlashingTorch() {
        if (cameraIdWithFlash != null && isTorchOn) {
            try {
                cameraManager.setTorchMode(cameraIdWithFlash, false);
                isTorchOn = false;
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    private final BroadcastReceiver dismissReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String roomNo = intent.getStringExtra("roomNo");
            if (roomNo != null && roomNo.equals(myRoomNo)
                    && arrivalDialog != null
                    && arrivalDialog.isShowing()) {
                Log.i("NaoMiMode", "dismissReceiver1:"+roomNo);
                Log.i("NaoMiMode", "dismissReceiver1.1:"+myRoomNo);
                if (currentScene != null) {
                    Log.i("NaoMiMode", "dismissReceiver2:"+currentScene);
                    switch (currentScene) {
                        case ROOM:   stopRoomLoop();    break;
                        case HOME:   stopHomeLoop();    break;
                        case KINKYU: stopKinkyuLoop();  break;
                    }
                }
                stopFlashAll();
                // 再关 dialog
                if (arrivalDialog != null && arrivalDialog.isShowing()) {
                    arrivalDialog.dismiss();
                }
            }
        }
    };


}

