package tn.loukious.facebookappadsremover;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class Module implements IXposedHookLoadPackage {

    private static final String TAG = "FacebookAppAdsRemover";
    private static final long[] EARLY_GUARD_DELAYS_MS = {100L, 250L, 750L, 1_500L, 2_500L};
    private static final long[] FAST_COMPONENT_DELAYS_MS = {3_500L, 5_000L, 7_500L};
    private static final long[] INSTALL_DELAYS_MS = {3_000L, 10_000L, 25_000L};
    private static volatile boolean sDexKitLoaded = false;
    private static final AtomicBoolean sAttachHookInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean sDexReadyHookInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean sComponentGuardInstallInProgress = new AtomicBoolean(false);
    private static final AtomicBoolean sFeedComponentGuardInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean sMarketplaceNetGuardInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean sMarketplaceNetGuardInProgress = new AtomicBoolean(false);
    private static final AtomicBoolean sInstallInProgress = new AtomicBoolean(false);
    private static final AtomicBoolean sHooksInstalled = new AtomicBoolean(false);
    private static volatile XC_MethodHook.Unhook sClassLoadNotifierUnhook;
    private static volatile Application sApplication;
    private static volatile String sHostVersionName;

    private static void debugLogInfo(String message) {
        if (BuildConfig.DEBUG) {
            Log.i(TAG, message);
        }
    }

    private static void debugLogError(String message, Throwable throwable) {
        if (BuildConfig.DEBUG) {
            Log.e(TAG, message, throwable);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.facebook.katana".equals(lpparam.packageName)) {
            return;
        }

        debugLogInfo("Loading hooks for package=" + lpparam.packageName + " process=" + lpparam.processName);
        installFacebookDexReadyHook(lpparam.classLoader);
        ensureDexKitLoaded();
        if (!sAttachHookInstalled.compareAndSet(false, true)) {
            return;
        }

        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        attach.setAccessible(true);
        XposedBridge.hookMethod(attach, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Application application = (Application) param.thisObject;
                sApplication = application;
                try {
                    PatchesKt.installFacebook576VideoInterruptionFastPaths(application.getClassLoader());
                } catch (Throwable throwable) {
                    Log.e(TAG, "Failed early Facebook 576 video-ad hooks at Application.attach", throwable);
                }
                loadCachedFeedGuardCandidates(application);
                loadCachedReelsGuard(application);
                loadCachedMarketplaceNetGuard(application);
                // The game webview can register its Javascript bridge before the
                // DexKit scan installs the main hooks; watch for it immediately.
                try {
                    PatchesKt.installGameAdJavascriptInterfaceBridgeHook();
                } catch (Throwable throwable) {
                    debugLogError("Failed to install game bridge watcher", throwable);
                }
                // The framework-only view-level safety net (marker-based ad
                // hiding) must be active before the first feed/reels/marketplace
                // content mounts, which happens well before the DexKit scan.
                try {
                    PatchesKt.installGlobalAdSurfaceFallbacksEarly();
                } catch (Throwable throwable) {
                    debugLogError("Failed to install early ad surface fallbacks", throwable);
                }
                scheduleHookInstallAttempts(application.getClassLoader());
            }
        });
        debugLogInfo("Waiting for Facebook Application.attach before scanning secondary dex");
    }

    // The cached initial News Feed renders before the DexKit scan completes, so
    // the guard pair discovered on a previous launch of the same Facebook build
    // is re-registered here (~100ms after attach) to hook before that render.
    private static void loadCachedFeedGuardCandidates(Application application) {
        try {
            int registered = PatchesKt.loadCachedFeedGuardCandidates(
                    application,
                    application.getClassLoader(),
                    resolveHostVersionName(application)
            );
            if (registered > 0) {
                debugLogInfo("Registered " + registered + " cached feed guard candidate(s)");
            }
        } catch (Throwable throwable) {
            debugLogError("Failed to load cached feed guard candidates", throwable);
        }
    }

    private static String resolveHostVersionName(Application application) {
        String versionName = sHostVersionName;
        if (versionName != null) {
            return versionName;
        }
        try {
            versionName = application.getPackageManager()
                    .getPackageInfo(application.getPackageName(), 0).versionName;
        } catch (Throwable throwable) {
            debugLogError("Failed to resolve host version name", throwable);
            versionName = "";
        }
        sHostVersionName = versionName;
        return versionName;
    }

    private static void saveFeedGuardCandidateCache() {
        Application application = sApplication;
        if (application == null) {
            return;
        }
        try {
            PatchesKt.saveFeedGuardCandidateCache(application, resolveHostVersionName(application));
        } catch (Throwable throwable) {
            debugLogError("Failed to save feed guard candidates", throwable);
        }
    }

    // Same cold-start race as the feed guard: the reels ad render block flashes
    // an ad until the full DexKit pass installs seconds after launch, so the
    // resolved reels targets are re-installed from cache right after attach.
    private static void loadCachedReelsGuard(Application application) {
        try {
            PatchesKt.installReelsGuardFromCache(
                    application,
                    application.getClassLoader(),
                    resolveHostVersionName(application)
            );
        } catch (Throwable throwable) {
            debugLogError("Failed to load cached reels guard", throwable);
        }
    }

    private static void saveReelsGuardCache() {
        Application application = sApplication;
        if (application == null) {
            return;
        }
        try {
            PatchesKt.saveReelsGuardCache(application, resolveHostVersionName(application));
        } catch (Throwable throwable) {
            debugLogError("Failed to save reels guard cache", throwable);
        }
    }

    // The marketplace feed's GraphQL query goes out within seconds of launch,
    // before the DexKit scan installs the Networking-module hooks. The resolved
    // module class name from a previous launch is re-hooked here (same pattern
    // as the feed guard cache), with timed retries while the secondary dex
    // finishes configuring.
    private static void loadCachedMarketplaceNetGuard(Application application) {
        try {
            if (PatchesKt.installMarketplaceNetGuardFromCache(
                    application,
                    application.getClassLoader(),
                    resolveHostVersionName(application)
            )) {
                sMarketplaceNetGuardInstalled.set(true);
                debugLogInfo("Marketplace net guard installed from cache at attach");
            }
        } catch (Throwable throwable) {
            debugLogError("Failed to load cached marketplace net guard", throwable);
        }
    }

    private static void retryCachedMarketplaceNetGuard(ClassLoader classLoader) {
        if (sMarketplaceNetGuardInstalled.get()) {
            return;
        }
        if (!sMarketplaceNetGuardInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            Application application = sApplication;
            if (application != null && PatchesKt.installMarketplaceNetGuardFromCache(
                    application,
                    classLoader,
                    resolveHostVersionName(application)
            )) {
                sMarketplaceNetGuardInstalled.set(true);
                debugLogInfo("Marketplace net guard installed from cache on retry");
            }
        } catch (Throwable throwable) {
            debugLogError("Failed to retry cached marketplace net guard", throwable);
        } finally {
            sMarketplaceNetGuardInProgress.set(false);
        }
    }

    private static void saveMarketplaceNetGuardCache() {
        Application application = sApplication;
        if (application == null) {
            return;
        }
        try {
            PatchesKt.saveMarketplaceNetGuardCache(
                    application,
                    resolveHostVersionName(application)
            );
        } catch (Throwable throwable) {
            debugLogError("Failed to save marketplace net guard cache", throwable);
        }
    }

    private static void installFacebookDexReadyHook(ClassLoader classLoader) {
        if (!sDexReadyHookInstalled.compareAndSet(false, true)) {
            return;
        }
        try {
            installFacebookClassLoadNotifierHook(classLoader);
            Class<?> multiDexClassLoader = Class.forName(
                    "com.facebook.common.dextricks.MultiDexClassLoaderJava",
                    false,
                    classLoader
            );
            Method configure = null;
            for (Method method : multiDexClassLoader.getDeclaredMethods()) {
                if ("configure".equals(method.getName()) && method.getParameterCount() == 1) {
                    configure = method;
                    break;
                }
            }
            if (configure == null) {
                debugLogInfo("Facebook MultiDex configure method not found; using timed feed hook fallback");
                return;
            }
            configure.setAccessible(true);
            XposedBridge.hookMethod(configure, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ClassLoader configuredLoader = param.thisObject instanceof ClassLoader
                            ? (ClassLoader) param.thisObject
                            : classLoader;
                    tryInstallFastFeedHooksAtDexReady(configuredLoader, "MultiDex configure");
                }
            });
            int fallbackHooks = 0;
            Class<?> multiDexBase = Class.forName(
                    "com.facebook.common.dextricks.MultiDexClassLoader",
                    false,
                    classLoader
            );
            for (Method method : multiDexBase.getDeclaredMethods()) {
                if (
                        "maybeFallbackLoadDexes".equals(method.getName()) &&
                        method.getParameterCount() >= 1 &&
                        method.getParameterTypes()[0] == String.class
                ) {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(param.getResult())) {
                                tryInstallFastFeedHooksAtDexReady(classLoader, "long-tail dex load");
                            }
                        }
                    });
                    fallbackHooks++;
                }
            }
            debugLogInfo(
                    "Waiting for Facebook MultiDex configure/long-tail load before installing decoded response hooks; " +
                            "fallbackHooks=" + fallbackHooks
            );
        } catch (Throwable throwable) {
            debugLogError("Failed to hook Facebook MultiDex readiness; using timed fallback", throwable);
        }
    }

    private static void installFacebookClassLoadNotifierHook(ClassLoader classLoader) throws Exception {
        Class<?> notifierClass = Class.forName(
                "com.facebook.common.dextricks.ClassLoadsNotifier",
                false,
                classLoader
        );
        Method notifyClassLoaded = notifierClass.getDeclaredMethod("notifyClassLoaded", Class.class);
        notifyClassLoaded.setAccessible(true);
        sClassLoadNotifierUnhook = XposedBridge.hookMethod(notifyClassLoaded, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Class<?> loadedClass = param.args[0] instanceof Class ? (Class<?>) param.args[0] : null;
                if (loadedClass == null) {
                    return;
                }
                String componentName = PatchesKt.lithoComponentNameOf(loadedClass);
                if (componentName == null || !PatchesKt.registerFeedGuardCandidate(loadedClass, componentName)) {
                    return;
                }

                ClassLoader targetLoader = loadedClass.getClassLoader();
                if (targetLoader == null) {
                    targetLoader = classLoader;
                }
                debugLogInfo(
                        "Observed feed component class load=" + loadedClass.getName() +
                                " component=" + componentName +
                                " loader=" + targetLoader.getClass().getName()
                );
                tryInstallFastFeedHooksAtDexReady(targetLoader, "class-load notification");
            }
        });
        debugLogInfo("Waiting for feed component class load before installing the component guard");
    }

    private static void tryInstallFastFeedHooksAtDexReady(
            ClassLoader classLoader,
            String readinessSource
    ) {
        try {
            PatchesKt.installFacebook576VideoInterruptionFastPaths(classLoader);
        } catch (Throwable throwable) {
            Log.e(TAG, "Failed Facebook 576 video-ad hooks at " + readinessSource, throwable);
        }
        tryInstallFeedComponentGuard(classLoader, readinessSource);
        removeClassLoadNotifierHook();
    }

    private static void removeClassLoadNotifierHook() {
        if (!sFeedComponentGuardInstalled.get()) {
            return;
        }
        XC_MethodHook.Unhook unhook = sClassLoadNotifierUnhook;
        if (unhook == null) {
            return;
        }
        sClassLoadNotifierUnhook = null;
        unhook.unhook();
        debugLogInfo("Removed class-load notifier after the component guard became active");
    }

    private static void scheduleHookInstallAttempts(ClassLoader classLoader) {
        Handler handler = new Handler(Looper.getMainLooper());
        tryInstallFeedComponentGuard(classLoader, "Application.attach");
        for (int attempt = 0; attempt < EARLY_GUARD_DELAYS_MS.length; attempt++) {
            final int attemptNumber = attempt + 1;
            handler.postDelayed(
                    () -> new Thread(
                            () -> tryInstallFeedComponentGuard(
                                    classLoader,
                                    "component attempt=" + attemptNumber
                            ),
                            "FacebookFeedComponentInit-" + attemptNumber
                    ).start(),
                    EARLY_GUARD_DELAYS_MS[attempt]
            );
            handler.postDelayed(
                    () -> new Thread(
                            () -> retryCachedMarketplaceNetGuard(classLoader),
                            "FacebookMarketplaceNetInit-" + attemptNumber
                    ).start(),
                    EARLY_GUARD_DELAYS_MS[attempt]
            );
        }
        for (int attempt = 0; attempt < FAST_COMPONENT_DELAYS_MS.length; attempt++) {
            final int attemptNumber = attempt + 1;
            handler.postDelayed(
                    () -> new Thread(
                            () -> {
                                tryInstallFeedComponentGuard(
                                        classLoader,
                                        "late component attempt=" + attemptNumber
                                );
                                retryCachedMarketplaceNetGuard(classLoader);
                            },
                            "FacebookFeedComponentLate-" + attemptNumber
                    ).start(),
                    FAST_COMPONENT_DELAYS_MS[attempt]
            );
        }
        for (int attempt = 0; attempt < INSTALL_DELAYS_MS.length; attempt++) {
            final int attemptNumber = attempt + 1;
            handler.postDelayed(
                    () -> new Thread(
                            () -> tryInstallHooks(classLoader, attemptNumber),
                            "FacebookAdsHookInit-" + attemptNumber
                    ).start(),
                    INSTALL_DELAYS_MS[attempt]
            );
        }
    }

    private static void tryInstallFeedComponentGuard(
            ClassLoader classLoader,
            String readinessSource
    ) {
        if (
                sFeedComponentGuardInstalled.get() ||
                !sComponentGuardInstallInProgress.compareAndSet(false, true)
        ) {
            return;
        }
        try {
            if (PatchesKt.installFacebookFeedComponentGuard(classLoader)) {
                sFeedComponentGuardInstalled.set(true);
                debugLogInfo(
                        "Sponsored feed component guard installed at " + readinessSource
                );
            }
        } catch (Throwable throwable) {
            debugLogError(
                    "Failed sponsored feed component guard at " + readinessSource,
                    throwable
            );
        } finally {
            sComponentGuardInstallInProgress.set(false);
        }
    }

    private static void tryInstallHooks(ClassLoader classLoader, int attemptNumber) {
        if (sHooksInstalled.get() || !sInstallInProgress.compareAndSet(false, true)) {
            return;
        }

        try (DexKitBridge bridge = DexKitBridge.create(classLoader, true)) {
            debugLogInfo("Scanning Facebook secondary dex, attempt=" + attemptNumber);
            if (PatchesKt.installFacebookAdRemover(classLoader, bridge)) {
                sHooksInstalled.set(true);
                saveFeedGuardCandidateCache();
                saveReelsGuardCache();
                saveMarketplaceNetGuardCache();
                tryInstallFeedComponentGuard(classLoader, "full DexKit readiness");
                removeClassLoadNotifierHook();
                debugLogInfo("Facebook ad remover hooks installed on attempt=" + attemptNumber);
            }
        } catch (Throwable throwable) {
            debugLogError("Failed to install Facebook ad remover on attempt=" + attemptNumber, throwable);
        } finally {
            sInstallInProgress.set(false);
        }
    }

    private static void ensureDexKitLoaded() {
        if (sDexKitLoaded) {
            return;
        }
        synchronized (Module.class) {
            if (!sDexKitLoaded) {
                System.loadLibrary("dexkit");
                sDexKitLoaded = true;
            }
        }
    }
}
