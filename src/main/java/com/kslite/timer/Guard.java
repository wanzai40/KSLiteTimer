package com.kslite.timer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import io.github.libxposed.api.XposedModule;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 极简测试版守卫：全部运行在快手主进程内，时长存在快手私有目录。
 * 打开时弹出调时长的对话框（1–120 分钟）；快到点弹窗可延长同样时长或忽略；到点自动关闭。
 */
final class Guard implements Application.ActivityLifecycleCallbacks {

    private static final String TAG = "KSLiteTimer";
    private static final String STATE_PREFS = "kslite_guard_state";
    private static final String K_DAY = "day";
    private static final String K_USED = "used_ms";
    private static final String K_EXTRA = "extra_ms";
    private static final String K_LIMIT = "limit_min";
    private static final String K_WARNED = "warned_quota";

    private static final long BACKGROUND_GRACE_MS = 600L;
    private static final long WARN_LEAD_MS = 60_000L;

    private final XposedModule module;
    private final Application app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<WeakReference<Activity>> activities = new ArrayList<>();

    private SharedPreferences state;

    private long limitMs = Config.DEF_LIMIT_MIN * 60000L;
    private long usedBase = 0L;
    private long extraMs = 0L;
    private long warnedQuota = -1L;

    private boolean foreground = false;
    private long fgStart = 0L;
    private int resumedCount = 0;

    private WeakReference<Activity> current;
    private AlertDialog dialog;
    private boolean closing = false;
    private boolean limitDialogShown = false;

    private final Runnable tickRunnable = this::schedule;
    private final Runnable backgroundRunnable = () -> {
        if (resumedCount == 0) {
            endForeground();
        }
    };

    Guard(XposedModule module, Application app) {
        this.module = module;
        this.app = app;
    }

    void attach() {
        try {
            state = app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
            resetIfNewDay();
            // 进程启动即视为新会话，清掉上次耗尽遗留的已用值，避免一进来就被判超额
            usedBase = 0L;
            extraMs = 0L;
            warnedQuota = -1L;
            limitMs = Math.max(1, (int) lng(K_LIMIT, Config.DEF_LIMIT_MIN)) * 60000L;
            state.edit()
                    .putLong(K_USED, 0L)
                    .putLong(K_EXTRA, 0L)
                    .putLong(K_WARNED, -1L)
                    .commit();
            app.registerActivityLifecycleCallbacks(this);
            log("attached: fresh session, quota=" + (quota() / 60000L) + "min");
        } catch (Throwable t) {
            log("attach failed: " + t);
        }
    }

    // ------------------------------------------------ 状态

    private static String todayKey() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    private void resetIfNewDay() {
        String today = todayKey();
        String saved = state.getString(K_DAY, "");
        if (today.equals(saved)) {
            return;
        }
        usedBase = 0L;
        extraMs = 0L;
        warnedQuota = -1L;
        state.edit()
                .putString(K_DAY, today)
                .putLong(K_USED, 0L)
                .putLong(K_EXTRA, 0L)
                .putLong(K_WARNED, -1L)
                .apply();
        log("new day, counters reset");
    }

    private long quota() {
        return limitMs + extraMs;
    }

    /** 兼容历史上混存的 Integer/Long，读 Long 失败就按 Integer 读。 */
    private long lng(String key, long def) {
        try {
            return state.getLong(key, def);
        } catch (Throwable t) {
            try {
                return state.getInt(key, (int) def);
            } catch (Throwable ignored) {
                return def;
            }
        }
    }

    private long usedNow() {
        return usedBase + (foreground ? SystemClock.elapsedRealtime() - fgStart : 0L);
    }

    private void persist() {
        if (foreground) {
            usedBase = usedNow();
            fgStart = SystemClock.elapsedRealtime();
        }
        state.edit()
                .putString(K_DAY, todayKey())
                .putLong(K_USED, Math.max(0L, usedBase))
                .putLong(K_EXTRA, Math.max(0L, extraMs))
                .putLong(K_LIMIT, (int) (limitMs / 60000L))
                .putLong(K_WARNED, warnedQuota)
                .commit();
    }

    private void stopCounting() {
        usedBase = usedNow();
        foreground = false;
        fgStart = 0L;
        handler.removeCallbacks(tickRunnable);
    }

    // ------------------------------------------------ 生命周期

    @Override
    public void onActivityCreated(Activity activity, android.os.Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityResumed(Activity activity) {
        try {
            if (closing) {
                return;
            }
            current = new WeakReference<>(activity);
            remember(activity);
            resumedCount++;
            handler.removeCallbacks(backgroundRunnable);

            if (!foreground) {
                resetIfNewDay();
                usedBase = state != null ? lng(K_USED, 0L) : 0L;
                extraMs = state != null ? lng(K_EXTRA, 0L) : 0L;
                warnedQuota = state != null ? lng(K_WARNED, -1L) : -1L;
                limitMs = Math.max(1, (int) (state != null ? lng(K_LIMIT, Config.DEF_LIMIT_MIN) : Config.DEF_LIMIT_MIN)) * 60000L;
                foreground = true;
                fgStart = SystemClock.elapsedRealtime();
            }

            // 每次冷启动先弹调时长框（这就是调整入口），保存后才开始计时
            if (!limitDialogShown) {
                showLimitDialog(activity);
                return;
            }
            if (usedNow() >= quota()) {
                stopCounting();
                showExhaustedDialog(activity);
                return;
            }
            schedule();
        } catch (Throwable t) {
            log("onResume failed: " + t);
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
        resumedCount = Math.max(0, resumedCount - 1);
        if (resumedCount == 0) {
            handler.removeCallbacks(backgroundRunnable);
            handler.postDelayed(backgroundRunnable, BACKGROUND_GRACE_MS);
        }
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, android.os.Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        forget(activity);
        WeakReference<Activity> ref = current;
        if (ref != null && ref.get() == activity) {
            current = null;
        }
    }

    private void endForeground() {
        if (!foreground) {
            return;
        }
        usedBase = usedNow();
        foreground = false;
        fgStart = 0L;
        handler.removeCallbacks(tickRunnable);
        persist();
        log("background, used=" + (usedBase / 1000L) + "s");
    }

    // ------------------------------------------------ 调时长对话框

    private void showLimitDialog(final Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        limitDialogShown = true;

        final TextView label = new TextView(activity);
        label.setPadding(60, 40, 60, 10);
        final int[] minutes = {(int) (limitMs / 60000L)};
        label.setText("限制时长：" + minutes[0] + " 分钟");

        SeekBar bar = new SeekBar(activity);
        bar.setMax(119);
        bar.setProgress(minutes[0] - 1);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                minutes[0] = progress + 1;
                label.setText("限制时长：" + minutes[0] + " 分钟");
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });

        android.widget.LinearLayout box = new android.widget.LinearLayout(activity);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.addView(label);
        box.addView(bar);

        AlertDialog d = new AlertDialog.Builder(activity)
                .setTitle("快手极速版时间管家 · 设置时长")
                .setView(box)
                .setPositiveButton("保存并开始", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d0, int which) {
                        limitMs = minutes[0] * 60000L;
                        // 重新设置时长即从 0 开始，避免上次耗尽后一进来又被判超额
                        usedBase = 0L;
                        extraMs = 0L;
                        warnedQuota = -1L;
                        fgStart = SystemClock.elapsedRealtime();
                        persist();
                        log("limit set to " + minutes[0] + "min, counter reset");
                        schedule();
                    }
                })
                .setNegativeButton("不调整", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d0, int which) {
                        schedule();
                    }
                })
                .create();
        try {
            d.show();
        } catch (Throwable t) {
            log("limit dialog failed: " + t);
        }
    }

    /** 计时过程中从提醒弹窗再次打开调时长框。 */
    private void reopenLimitDialog() {
        limitDialogShown = false;
        Activity activity = currentActivity();
        if (activity != null && !activity.isFinishing()) {
            showLimitDialog(activity);
        }
    }

    // ------------------------------------------------ 音量键呼出菜单

    private boolean volUpDown;
    private boolean volDownDown;
    private boolean menuShownForCombo;

    /**
     * 音量上 + 下同时按下呼出调整菜单。
     * @return true 表示本次按键已消费（菜单已呼出），框架不再向下派发。
     */
    boolean onKeyEvent(android.view.KeyEvent ev) {
        if (ev == null || closing) {
            return false;
        }
        int code = ev.getKeyCode();
        if (code != android.view.KeyEvent.KEYCODE_VOLUME_UP
                && code != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }
        if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                volUpDown = true;
            } else {
                volDownDown = true;
            }
            if (volUpDown && volDownDown && !menuShownForCombo) {
                menuShownForCombo = true;
                reopenLimitDialog();
                return true;
            }
        } else {
            if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                volUpDown = false;
            } else {
                volDownDown = false;
            }
            if (!volUpDown && !volDownDown) {
                menuShownForCombo = false;
            }
        }
        return false;
    }

    // ------------------------------------------------ 计时 / 提醒 / 关闭

    private void schedule() {
        handler.removeCallbacks(tickRunnable);
        if (!foreground || closing) {
            return;
        }
        long used = usedNow();
        long quota = quota();
        if (used >= quota) {
            onExhausted();
            return;
        }
        long warnAt = quota - WARN_LEAD_MS;
        boolean needWarn = warnedQuota != quota;
        if (needWarn && used >= warnAt) {
            onWarn();
            return;
        }
        long target = needWarn ? warnAt : quota;
        long delay = Math.max(50L, target - used);
        handler.postDelayed(tickRunnable, delay);
    }

    private void onWarn() {
        long quota = quota();
        warnedQuota = quota;
        persist();

        Activity activity = currentActivity();
        if (activity == null) {
            handler.postDelayed(tickRunnable, 300L);
            return;
        }
        long remainMs = Math.max(0L, quota - usedNow());
        int extendMin = (int) (limitMs / 60000L);
        AlertDialog d = new AlertDialog.Builder(activity)
                .setTitle("使用时长提醒")
                .setMessage(String.format(Locale.CHINA,
                        "还剩 %d 秒，到点将自动关闭快手极速版。",
                        Math.max(1L, remainMs / 1000L)))
                .setCancelable(false)
                .setPositiveButton("延长 " + extendMin + " 分钟", (d0, w) -> extend())
                .setNeutralButton("调整时长", (d0, w) -> reopenLimitDialog())
                .setNegativeButton("忽略", (d0, w) -> {
                    persist();
                    schedule();
                })
                .create();
        dialog = d;
        try {
            d.show();
            log("warn dialog shown, remain=" + (remainMs / 1000L) + "s");
        } catch (Throwable t) {
            log("warn dialog failed: " + t);
            schedule();
        }
    }

    private void onExhausted() {
        usedBase = usedNow();
        stopCounting();
        persist();
        log("quota reached, used=" + (usedBase / 60000L) + "min");
        closeApp("quota reached");
    }

    private void extend() {
        usedBase = usedNow();
        fgStart = SystemClock.elapsedRealtime();
        extraMs += limitMs;
        warnedQuota = -1L;
        handler.removeCallbacks(backgroundRunnable);
        persist();
        log("extended, quota=" + (quota() / 60000L) + "min");
        schedule();
    }

    private void showExhaustedDialog(Activity activity) {
        int extendMin = (int) (limitMs / 60000L);
        AlertDialog d = new AlertDialog.Builder(activity)
                .setTitle("今日时长已用完")
                .setMessage("今日已累计到 " + (quota() / 60000L) + " 分钟配额，可以延长继续使用，或退出。")
                .setCancelable(false)
                .setPositiveButton("延长 " + extendMin + " 分钟", (d0, w) -> extend())
                .setNegativeButton("立即退出", (d0, w) -> closeApp("user chose exit"))
                .create();
        dialog = d;
        try {
            d.show();
        } catch (Throwable t) {
            closeApp("dialog failed");
        }
    }

    private void closeApp(String reason) {
        if (closing) {
            return;
        }
        closing = true;
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(backgroundRunnable);
        log("closing app: " + reason);

        Activity activity = currentActivity();
        if (activity != null) {
            try {
                Toast.makeText(activity, "使用时长已到，已退出快手极速版", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
        for (WeakReference<Activity> ref : new ArrayList<>(activities)) {
            Activity act = ref.get();
            if (act == null) {
                continue;
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    act.finishAndRemoveTask();
                } else {
                    act.finish();
                }
            } catch (Throwable ignored) {
            }
        }
        activities.clear();
        handler.postDelayed(() -> {
            try {
                Process.killProcess(Process.myPid());
            } catch (Throwable ignored) {
            }
        }, 400L);
    }

    // ------------------------------------------------ 工具

    private Activity currentActivity() {
        WeakReference<Activity> ref = current;
        if (ref != null) {
            Activity a = ref.get();
            if (a != null && !a.isFinishing()) {
                return a;
            }
        }
        for (WeakReference<Activity> r : activities) {
            Activity a = r.get();
            if (a != null && !a.isFinishing()) {
                return a;
            }
        }
        return null;
    }

    private void remember(Activity activity) {
        for (WeakReference<Activity> ref : activities) {
            if (ref.get() == activity) {
                return;
            }
        }
        activities.add(new WeakReference<>(activity));
    }

    private void forget(Activity activity) {
        for (int i = activities.size() - 1; i >= 0; i--) {
            Activity a = activities.get(i).get();
            if (a == null || a == activity) {
                activities.remove(i);
            }
        }
    }

    private void log(String msg) {
        try {
            module.log(Log.INFO, TAG, msg);
        } catch (Throwable ignored) {
        }
    }
}
