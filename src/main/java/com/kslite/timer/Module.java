package com.kslite.timer;

import android.app.Activity;
import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.util.Log;
import android.view.KeyEvent;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * API 102 入口（极简测试版）：只 hook 主进程 Application#onCreate，挂上 Guard。
 * 不连服务、不读 RemotePreferences，时长存在快手自己的私有目录里。
 */
public final class Module extends XposedModule {

    private static final String TAG = "KSLiteTimer";
    private static final String HOOK_ID = "nebula-application-oncreate";

    private volatile Guard guard;
    private volatile boolean hooked;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        String process;
        try {
            process = param.getProcessName();
        } catch (Throwable t) {
            detach();
            return;
        }
        if (param.isSystemServer() || !Config.TARGET_PACKAGE.equals(process)) {
            detach();
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!Config.TARGET_PACKAGE.equals(param.getPackageName()) || hooked) {
            return;
        }
        if (hookApplicationOnCreate(param.getClassLoader(), param.getApplicationInfo())) {
            hooked = true;
            safeLog(Log.INFO, "hook installed in " + Config.TARGET_PACKAGE);
        }
    }

    private boolean hookApplicationOnCreate(ClassLoader loader, ApplicationInfo info) {
        String className = info != null ? info.className : null;
        if (className == null || className.isEmpty()) {
            className = "android.app.Application";
        }
        if (tryHook(loader, className)) {
            return true;
        }
        return !"android.app.Application".equals(className)
                && tryHook(loader, "android.app.Application");
    }

    private boolean tryHook(ClassLoader loader, String className) {
        try {
            Class<?> clazz = Class.forName(className, false, loader);
            hook(clazz.getDeclaredMethod("onCreate"))
                    .setId(HOOK_ID)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object self = chain.getThisObject();
                        if (self instanceof Application) {
                            installGuard((Application) self);
                        }
                        return chain.proceed();
                    });
            return true;
        } catch (Throwable t) {
            safeLog(Log.WARN, "cannot hook " + className + "#onCreate: " + t);
            return false;
        }
    }

    private void installGuard(Application app) {
        if (guard != null || !Config.TARGET_PACKAGE.equals(app.getPackageName())) {
            return;
        }
        try {
            Guard g = new Guard(this, app);
            guard = g;
            g.attach();
            hookKeyMenu();
        } catch (Throwable t) {
            safeLog(Log.ERROR, "install guard failed: " + t);
        }
    }

    /** 音量上 + 下同时按下呼出调整菜单；独立 try，失败不影响弹窗与计时。 */
    private void hookKeyMenu() {
        try {
            hook(Activity.class.getDeclaredMethod("dispatchKeyEvent", KeyEvent.class))
                    .setId("nebula-key-menu")
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        java.util.List<Object> args = chain.getArgs();
                        if (args != null && !args.isEmpty() && args.get(0) instanceof KeyEvent) {
                            Guard g = guard;
                            if (g != null && g.onKeyEvent((KeyEvent) args.get(0))) {
                                return true;
                            }
                        }
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            safeLog(Log.WARN, "key menu hook failed: " + t);
        }
    }

    private void safeLog(int level, String msg) {
        try {
            log(level, TAG, msg);
        } catch (Throwable ignored) {
        }
    }
}
