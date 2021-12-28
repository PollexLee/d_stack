// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.android;

import static android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
import static android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW;
import static io.flutter.embedding.android.FlutterActivityLaunchConfigs.DEFAULT_INITIAL_ROUTE;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver.OnPreDrawListener;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.Lifecycle;

import io.flutter.FlutterInjector;
import io.flutter.Log;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.FlutterShellArgs;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.renderer.FlutterUiDisplayListener;
import io.flutter.plugin.platform.PlatformPlugin;
import tal.com.d_stack.node.DNodeManager;
import tal.com.d_stack.observer.DStackActivityManager;

import java.util.Arrays;


public class DFlutterPageDelegate implements ExclusiveAppComponent<Activity> {
    private static final String TAG = "FlutterActivityAndFragmentDelegate";
    private static final String FRAMEWORK_RESTORATION_BUNDLE_KEY = "framework";
    private static final String PLUGINS_RESTORATION_BUNDLE_KEY = "plugins";
    private static final int FLUTTER_SPLASH_VIEW_FALLBACK_ID = 486947586;

    @NonNull
    private Host host;
    @Nullable
    private FlutterEngine flutterEngine;
    @Nullable
    private FlutterView flutterView;
    @Nullable
    private PlatformPlugin platformPlugin;
    @VisibleForTesting
    @Nullable
    OnPreDrawListener activePreDrawListener;
    private boolean isFlutterEngineFromHost;
    private boolean isFlutterUiDisplayed;
    private boolean isFirstFrameRendered;
    private boolean isAttached;
    private boolean isActive = false;
    private long destroyDelayTime = 200;

    @NonNull
    private final FlutterUiDisplayListener flutterUiDisplayListener =
            new FlutterUiDisplayListener() {
                @Override
                public void onFlutterUiDisplayed() {
                    host.onFlutterUiDisplayed();
                    isFlutterUiDisplayed = true;
                    isFirstFrameRendered = true;
                }

                @Override
                public void onFlutterUiNoLongerDisplayed() {
                    host.onFlutterUiNoLongerDisplayed();
                    isFlutterUiDisplayed = false;
                }
            };

    DFlutterPageDelegate(@NonNull Host host) {
        this.host = host;
        this.isFirstFrameRendered = false;
    }

    @Nullable
        /* package */ FlutterEngine getFlutterEngine() {
        return flutterEngine;
    }

    /* package */ boolean isFlutterEngineFromHost() {
        return isFlutterEngineFromHost;
    }

    /* package */ boolean isAttached() {
        return isAttached;
    }

    void onCreate(@NonNull Context context) {
        ensureAlive();
        DStackActivityManager.getInstance().addHost(host);
        if (flutterEngine == null) {
            setupFlutterEngine();
        }
        // 解除上一个Activity的绑定
        if (DStackActivityManager.getInstance().isNeedReAttachEngine()) {
            Host lastHost = DStackActivityManager.getInstance().getLastHost();
            if (lastHost != null) {
                lastHost.detachFromFlutterEngine();
            }
        }

        // 绑定当前Activity
        onAttach(context);

        // 保存引擎已被attach的状态
        DStackActivityManager.getInstance().setNeedReAttachEngine(true);
    }

    /**
     * 绑定引擎，重建platformPlugin
     *
     * @param context
     */
    void onAttach(@NonNull Context context) {
        ensureAlive();

        if (host.shouldAttachEngineToActivity() && !isActive) {
            Log.v(TAG, "Attaching FlutterEngine to the Activity that owns this delegate.");
            flutterEngine.getActivityControlSurface().attachToActivity(this, host.getLifecycle());
        }
        platformPlugin = host.providePlatformPlugin(host.getActivity(), flutterEngine);
        if (flutterView != null) {
            flutterView.attachToFlutterEngine(flutterEngine);
        }
        host.configureFlutterEngine(flutterEngine);
        isAttached = true;
        isActive = true;
    }

    @Override
    public @NonNull
    Activity getAppComponent() {
        final Activity activity = host.getActivity();
        if (activity == null) {
            throw new AssertionError(
                    "FlutterActivityAndFragmentDelegate's getAppComponent should only "
                            + "be queried after onAttach, when the host's activity should always be non-null");
        }
        return activity;
    }

    @VisibleForTesting
        /* package */ void setupFlutterEngine() {
        Log.v(TAG, "Setting up FlutterEngine.");

        // First, check if the host wants to use a cached FlutterEngine.
        String cachedEngineId = host.getCachedEngineId();
        if (cachedEngineId != null) {
            flutterEngine = FlutterEngineCache.getInstance().get(cachedEngineId);
            isFlutterEngineFromHost = true;
            if (flutterEngine == null) {
                throw new IllegalStateException(
                        "The requested cached FlutterEngine did not exist in the FlutterEngineCache: '"
                                + cachedEngineId
                                + "'");
            }
            return;
        }

        // Second, defer to subclasses for a custom FlutterEngine.
        flutterEngine = host.provideFlutterEngine(host.getContext());
        if (flutterEngine != null) {
            isFlutterEngineFromHost = true;
            return;
        }

        // Our host did not provide a custom FlutterEngine. Create a FlutterEngine to back our
        // FlutterView.
        Log.v(
                TAG,
                "No preferred FlutterEngine was provided. Creating a new FlutterEngine for"
                        + " this FlutterFragment.");
        flutterEngine =
                new FlutterEngine(
                        host.getContext(),
                        host.getFlutterShellArgs().toArray(),
                        /*automaticallyRegisterPlugins=*/ false,
                        /*willProvideRestorationData=*/ host.shouldRestoreAndSaveState());
        isFlutterEngineFromHost = false;
    }

    @NonNull
    View onCreateView(
            LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState,
            int flutterViewId,
            boolean shouldDelayFirstAndroidViewDraw) {
        Log.v(TAG, "Creating FlutterView.");
        ensureAlive();

        if (host.getRenderMode() == RenderMode.surface) {
            FlutterSurfaceView flutterSurfaceView =
                    new FlutterSurfaceView(
                            host.getContext(), host.getTransparencyMode() == TransparencyMode.transparent);

            // Allow our host to customize FlutterSurfaceView, if desired.
            host.onFlutterSurfaceViewCreated(flutterSurfaceView);

            // Create the FlutterView that owns the FlutterSurfaceView.
            flutterView = new FlutterView(host.getContext(), flutterSurfaceView);
        } else {
            FlutterTextureView flutterTextureView = new FlutterTextureView(host.getContext());

            flutterTextureView.setOpaque(host.getTransparencyMode() == TransparencyMode.opaque);

            // Allow our host to customize FlutterSurfaceView, if desired.
            host.onFlutterTextureViewCreated(flutterTextureView);

            // Create the FlutterView that owns the FlutterTextureView.
            flutterView = new FlutterView(host.getContext(), flutterTextureView);
        }

        // Add listener to be notified when Flutter renders its first frame.
        flutterView.addOnFirstFrameRenderedListener(flutterUiDisplayListener);

        Log.v(TAG, "Attaching FlutterEngine to FlutterView.");
        flutterView.attachToFlutterEngine(flutterEngine);
        flutterView.setId(flutterViewId);

        SplashScreen splashScreen = host.provideSplashScreen();

        if (splashScreen != null) {
            Log.w(
                    TAG,
                    "A splash screen was provided to Flutter, but this is deprecated. See"
                            + " flutter.dev/go/android-splash-migration for migration steps.");
            FlutterSplashView flutterSplashView = new FlutterSplashView(host.getContext());
            flutterSplashView.setId(DViewUtils.generateViewId(FLUTTER_SPLASH_VIEW_FALLBACK_ID));
            flutterSplashView.displayFlutterViewWithSplash(flutterView, splashScreen);

            return flutterSplashView;
        }

        if (shouldDelayFirstAndroidViewDraw) {
            delayFirstAndroidViewDraw(flutterView);
        }
        return flutterView;
    }

    void onRestoreInstanceState(@Nullable Bundle bundle) {
        Log.v(
                TAG,
                "onRestoreInstanceState. Giving framework and plugins an opportunity to restore state.");
        ensureAlive();

        Bundle pluginState = null;
        byte[] frameworkState = null;
        if (bundle != null) {
            pluginState = bundle.getBundle(PLUGINS_RESTORATION_BUNDLE_KEY);
            frameworkState = bundle.getByteArray(FRAMEWORK_RESTORATION_BUNDLE_KEY);
        }

        if (host.shouldRestoreAndSaveState()) {
            flutterEngine.getRestorationChannel().setRestorationData(frameworkState);
        }

        if (host.shouldAttachEngineToActivity()) {
            flutterEngine.getActivityControlSurface().onRestoreInstanceState(pluginState);
        }
    }

    void onStart() {
        Log.v(TAG, "onStart()");
        ensureAlive();
        doInitialFlutterViewRun();
    }

    private void doInitialFlutterViewRun() {
        // Don't attempt to start a FlutterEngine if we're using a cached FlutterEngine.
        if (host.getCachedEngineId() != null) {
            return;
        }

        if (flutterEngine.getDartExecutor().isExecutingDart()) {
            // No warning is logged because this situation will happen on every config
            // change if the developer does not choose to retain the Fragment instance.
            // So this is expected behavior in many cases.
            return;
        }
        String initialRoute = host.getInitialRoute();
        if (initialRoute == null) {
            initialRoute = maybeGetInitialRouteFromIntent(host.getActivity().getIntent());
            if (initialRoute == null) {
                initialRoute = DEFAULT_INITIAL_ROUTE;
            }
        }
        Log.v(
                TAG,
                "Executing Dart entrypoint: "
                        + host.getDartEntrypointFunctionName()
                        + ", and sending initial route: "
                        + initialRoute);

        // The engine needs to receive the Flutter app's initial route before executing any
        // Dart code to ensure that the initial route arrives in time to be applied.
        flutterEngine.getNavigationChannel().setInitialRoute(initialRoute);

        String appBundlePathOverride = host.getAppBundlePath();
        if (appBundlePathOverride == null || appBundlePathOverride.isEmpty()) {
            appBundlePathOverride = FlutterInjector.instance().flutterLoader().findAppBundlePath();
        }

        // Configure the Dart entrypoint and execute it.
        DartExecutor.DartEntrypoint entrypoint =
                new DartExecutor.DartEntrypoint(
                        appBundlePathOverride, host.getDartEntrypointFunctionName());
        flutterEngine.getDartExecutor().executeDartEntrypoint(entrypoint);
    }

    private String maybeGetInitialRouteFromIntent(Intent intent) {
        if (host.shouldHandleDeeplinking()) {
            Uri data = intent.getData();
            if (data != null && !data.getPath().isEmpty()) {
                String fullRoute = data.getPath();
                if (data.getQuery() != null && !data.getQuery().isEmpty()) {
                    fullRoute += "?" + data.getQuery();
                }
                if (data.getFragment() != null && !data.getFragment().isEmpty()) {
                    fullRoute += "#" + data.getFragment();
                }
                return fullRoute;
            }
        }
        return null;
    }

    /**
     * Delays the first drawing of the {@code flutterView} until the Flutter first has been displayed.
     */
    private void delayFirstAndroidViewDraw(final FlutterView flutterView) {
        if (host.getRenderMode() != RenderMode.surface) {
            // Using a TextureView will cause a deadlock, where the underlying SurfaceTexture is never
            // available since it will wait for drawing to be completed first. At the same time, the
            // preDraw listener keeps returning false since the Flutter Engine waits for the
            // SurfaceTexture to be available.
            throw new IllegalArgumentException(
                    "Cannot delay the first Android view draw when the render mode is not set to"
                            + " `RenderMode.surface`.");
        }

        if (activePreDrawListener != null) {
            flutterView.getViewTreeObserver().removeOnPreDrawListener(activePreDrawListener);
        }

        activePreDrawListener =
                new OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        if (isFlutterUiDisplayed && activePreDrawListener != null) {
                            flutterView.getViewTreeObserver().removeOnPreDrawListener(this);
                            activePreDrawListener = null;
                        }
                        return isFlutterUiDisplayed;
                    }
                };
        flutterView.getViewTreeObserver().addOnPreDrawListener(activePreDrawListener);
    }

    void onResume() {
        Log.v(TAG, "onResume()");
        ensureAlive();

        // copy form mix_stack
//        try {
//            if (platformPlugin != null) {
//                Field fs = platformPlugin.getClass().getDeclaredField("currentTheme");
//                fs.setAccessible(true);
//                Object currentTheme = fs.get(platformPlugin);
//                platformPlugin = host.providePlatformPlugin(host.getActivity(), flutterEngine);
//                Method method = platformPlugin.getClass().getDeclaredMethod("setSystemChromeSystemUIOverlayStyle", PlatformChannel.SystemChromeStyle.class);
//                method.setAccessible(true);
//                method.invoke(platformPlugin, currentTheme);
//            } else {
//                platformPlugin = host.providePlatformPlugin(host.getActivity(), flutterEngine);
//            }
//        } catch (NoSuchFieldException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
//            e.printStackTrace();
//        }
//        Log.v(TAG, "Attaching FlutterEngine to the Activity that owns this delegate.");
//        flutterEngine.getActivityControlSurface().attachToActivity(this, host.getLifecycle());
////        }
//        flutterView.attachToFlutterEngine(flutterEngine);

        flutterEngine.getLifecycleChannel().appIsResumed();

//        isActive = true;
    }

    /**
     * Invoke this from {@code Activity#onPostResume()}.
     *
     * <p>A {@code Fragment} host must have its containing {@code Activity} forward this call so that
     * the {@code Fragment} can then invoke this method.
     *
     * <p>This method informs the {@link PlatformPlugin} that {@code onPostResume()} has run, which is
     * used to update system UI overlays.
     */
    // possible.
    void onPostResume() {
        Log.v(TAG, "onPostResume()");
        ensureAlive();
        if (flutterEngine != null) {
            updateSystemUiOverlays();
        } else {
            Log.w(TAG, "onPostResume() invoked before FlutterFragment was attached to an Activity.");
        }
    }

    /**
     * Refreshes Android's window system UI (AKA system chrome) to match Flutter's desired system
     * chrome style.
     */
    void updateSystemUiOverlays() {
        if (platformPlugin != null) {
            // TODO(mattcarroll): find a better way to handle the update of UI overlays than calling
            // through to platformPlugin. We're implicitly entangling the Window, Activity,
            // Fragment, and engine all with this one call.
            platformPlugin.updateSystemUiOverlays();
        }
    }

    void onPause() {
        Log.v(TAG, "onPause()");
        ensureAlive();
        assert flutterEngine != null;
        flutterEngine.getLifecycleChannel().appIsInactive();
    }

    void onStop() {
        Log.v(TAG, "onStop()");
        ensureAlive();
        assert flutterEngine != null;
        flutterEngine.getLifecycleChannel().appIsPaused();
    }

    void onDestroyView() {
        Log.v(TAG, "onDestroyView()");
        ensureAlive();

        if (activePreDrawListener != null) {
            flutterView.getViewTreeObserver().removeOnPreDrawListener(activePreDrawListener);
            activePreDrawListener = null;
        }
        flutterView.removeOnFirstFrameRenderedListener(flutterUiDisplayListener);
    }

    void onSaveInstanceState(@Nullable Bundle bundle) {
        Log.v(TAG, "onSaveInstanceState. Giving framework and plugins an opportunity to save state.");
        ensureAlive();

        if (host.shouldRestoreAndSaveState()) {
            bundle.putByteArray(
                    FRAMEWORK_RESTORATION_BUNDLE_KEY,
                    flutterEngine.getRestorationChannel().getRestorationData());
        }

        if (host.shouldAttachEngineToActivity()) {
            final Bundle plugins = new Bundle();
            flutterEngine.getActivityControlSurface().onSaveInstanceState(plugins);
            bundle.putBundle(PLUGINS_RESTORATION_BUNDLE_KEY, plugins);
        }
    }

    @Override
    public void detachFromFlutterEngine() {
        if (host.shouldDestroyEngineWithHost()) {
            // The host owns the engine and should never have its engine taken by another exclusive
            // activity.
            throw new AssertionError(
                    "The internal FlutterEngine created by "
                            + host
                            + " has been attached to by another activity. To persist a FlutterEngine beyond the "
                            + "ownership of this activity, explicitly create a FlutterEngine");
        }

        host.detachFromFlutterEngine();
    }


    public void onDetach() {
        Log.v(TAG, "onDetach()");
        ensureAlive();
        host.cleanUpFlutterEngine(flutterEngine);

        // 避免解绑是PlatformView实现中的FlutterImageView不被解绑的问题
//        assert flutterView != null;
//        if (flutterView.renderSurface instanceof FlutterImageView) {
//            flutterView.renderSurface.detachFromRenderer();
//        }
        flutterView.detachFromFlutterEngine();


        if (host.shouldAttachEngineToActivity()) {
            // Notify plugins that they are no longer attached to an Activity.
            Log.v(TAG, "Detaching FlutterEngine from the Activity that owns this Fragment.");
            if (host.getActivity().isChangingConfigurations()) {
                flutterEngine.getActivityControlSurface().detachFromActivityForConfigChanges();
            } else {
                flutterEngine.getActivityControlSurface().detachFromActivity();
            }
        }
        if (platformPlugin != null) {
            platformPlugin.destroy();
            platformPlugin = null;
        }

        flutterEngine.getLifecycleChannel().appIsDetached();

        // Destroy our FlutterEngine if we're not set to retain it.
        if (host.shouldDestroyEngineWithHost()) {
            flutterEngine.destroy();

            if (host.getCachedEngineId() != null) {
                FlutterEngineCache.getInstance().remove(host.getCachedEngineId());
            }
            flutterEngine = null;

        }
        isAttached = false;
        isActive = false;
    }

    boolean onBackPressed() {
        ensureAlive();
        if (DNodeManager.getInstance().getLastNode() == null || DNodeManager.getInstance().getLastNode().isFlutter()) {
            if (flutterEngine != null) {
                flutterEngine.getNavigationChannel().popRoute();
            }
            return true;
        } else {
            assert flutterView != null;
            flutterView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (flutterEngine != null) {
                        flutterEngine.getNavigationChannel().popRoute();
//                        flutterEngine.getLifecycleChannel().appIsInactive();

                    }
                }
            }, destroyDelayTime);
            return false;
        }
    }

    /**
     * Invoke this from {@link android.app.Activity#onRequestPermissionsResult(int, String[], int[])}
     * or {@code Fragment#onRequestPermissionsResult(int, String[], int[])}.
     *
     * <p>This method forwards to interested Flutter plugins.
     */
    void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        ensureAlive();
        if (flutterEngine != null) {
            Log.v(
                    TAG,
                    "Forwarding onRequestPermissionsResult() to FlutterEngine:\n"
                            + "requestCode: "
                            + requestCode
                            + "\n"
                            + "permissions: "
                            + Arrays.toString(permissions)
                            + "\n"
                            + "grantResults: "
                            + Arrays.toString(grantResults));
            flutterEngine
                    .getActivityControlSurface()
                    .onRequestPermissionsResult(requestCode, permissions, grantResults);
        } else {
            Log.w(
                    TAG,
                    "onRequestPermissionResult() invoked before FlutterFragment was attached to an Activity.");
        }
    }

    void onNewIntent(@NonNull Intent intent) {
        ensureAlive();
        if (flutterEngine != null) {
            Log.v(TAG, "Forwarding onNewIntent() to FlutterEngine and sending pushRoute message.");
            flutterEngine.getActivityControlSurface().onNewIntent(intent);
            String initialRoute = maybeGetInitialRouteFromIntent(intent);
            if (initialRoute != null && !initialRoute.isEmpty()) {
                flutterEngine.getNavigationChannel().pushRoute(initialRoute);
            }
        } else {
            Log.w(TAG, "onNewIntent() invoked before FlutterFragment was attached to an Activity.");
        }
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        ensureAlive();
        if (flutterEngine != null) {
            Log.v(
                    TAG,
                    "Forwarding onActivityResult() to FlutterEngine:\n"
                            + "requestCode: "
                            + requestCode
                            + "\n"
                            + "resultCode: "
                            + resultCode
                            + "\n"
                            + "data: "
                            + data);
            flutterEngine.getActivityControlSurface().onActivityResult(requestCode, resultCode, data);
        } else {
            Log.w(TAG, "onActivityResult() invoked before FlutterFragment was attached to an Activity.");
        }
    }

    void onUserLeaveHint() {
        ensureAlive();
        if (flutterEngine != null) {
            Log.v(TAG, "Forwarding onUserLeaveHint() to FlutterEngine.");
            flutterEngine.getActivityControlSurface().onUserLeaveHint();
        } else {
            Log.w(TAG, "onUserLeaveHint() invoked before FlutterFragment was attached to an Activity.");
        }
    }

    void onTrimMemory(int level) {
        ensureAlive();
        if (flutterEngine != null) {
            // Use a trim level delivered while the application is running so the
            // framework has a chance to react to the notification.
            // Avoid being too aggressive before the first frame is rendered. If it is
            // not at least running critical, we should avoid delaying the frame for
            // an overly aggressive GC.
            boolean trim =
                    isFirstFrameRendered
                            ? level >= TRIM_MEMORY_RUNNING_LOW
                            : level >= TRIM_MEMORY_RUNNING_CRITICAL;
            if (trim) {
                flutterEngine.getDartExecutor().notifyLowMemoryWarning();
                flutterEngine.getSystemChannel().sendMemoryPressureWarning();
            }
        }
    }

    void onLowMemory() {
        Log.v(TAG, "Forwarding onLowMemory() to FlutterEngine.");
        ensureAlive();
        flutterEngine.getDartExecutor().notifyLowMemoryWarning();
        flutterEngine.getSystemChannel().sendMemoryPressureWarning();
    }

    private void ensureAlive() {
        if (host == null) {
            throw new IllegalStateException(
                    "Cannot execute method on a destroyed FlutterActivityAndFragmentDelegate.");
        }
    }

    public interface Host
            extends SplashScreenProvider,
            FlutterEngineProvider,
            FlutterEngineConfigurator,
            PlatformPlugin.PlatformPluginDelegate {
        /**
         * Returns the {@link Context} that backs the host {@link android.app.Activity} or {@code
         * Fragment}.
         */
        @NonNull
        Context getContext();

        /**
         * Returns true if the delegate should retrieve the initial route from the {@link Intent}.
         */
        @Nullable
        boolean shouldHandleDeeplinking();

        /**
         * Returns the host {@link android.app.Activity} or the {@code Activity} that is currently
         * attached to the host {@code Fragment}.
         */
        @Nullable
        Activity getActivity();

        /**
         * Returns the {@link Lifecycle} that backs the host {@link android.app.Activity} or {@code
         * Fragment}.
         */
        @NonNull
        Lifecycle getLifecycle();

        /**
         * Returns the {@link FlutterShellArgs} that should be used when initializing Flutter.
         */
        @NonNull
        FlutterShellArgs getFlutterShellArgs();

        /**
         * Returns the ID of a statically cached {@link io.flutter.embedding.engine.FlutterEngine} to
         * use within this delegate's host, or {@code null} if this delegate's host does not want to use
         * a cached {@link FlutterEngine}.
         */
        @Nullable
        String getCachedEngineId();

        /**
         * Returns true if the {@link io.flutter.embedding.engine.FlutterEngine} used in this delegate
         * should be destroyed when the host/delegate are destroyed.
         *
         * <p>The default value is {@code true} in cases where {@code FlutterFragment} created its own
         * {@link io.flutter.embedding.engine.FlutterEngine}, and {@code false} in cases where a cached
         * {@link io.flutter.embedding.engine.FlutterEngine} was provided.
         */
        boolean shouldDestroyEngineWithHost();

        void attachToFlutterEngine();

        /**
         * Callback called when the {@link io.flutter.embedding.engine.FlutterEngine} has been attached
         * to by another activity before this activity was destroyed.
         *
         * <p>The expected behavior is for this activity to synchronously stop using the {@link
         * FlutterEngine} to avoid lifecycle crosstalk with the new activity.
         */
        void detachFromFlutterEngine();

        /**
         * Returns the Dart entrypoint that should run when a new {@link
         * io.flutter.embedding.engine.FlutterEngine} is created.
         */
        @NonNull
        String getDartEntrypointFunctionName();

        /**
         * Returns the path to the app bundle where the Dart code exists.
         */
        @NonNull
        String getAppBundlePath();

        /**
         * Returns the initial route that Flutter renders.
         */
        @Nullable
        String getInitialRoute();

        /**
         * Returns the {@link RenderMode} used by the {@link FlutterView} that displays the {@link
         * FlutterEngine}'s content.
         */
        @NonNull
        RenderMode getRenderMode();

        /**
         * Returns the {@link TransparencyMode} used by the {@link FlutterView} that displays the {@link
         * FlutterEngine}'s content.
         */
        @NonNull
        TransparencyMode getTransparencyMode();

        @Nullable
        SplashScreen provideSplashScreen();

        /**
         * Returns the {@link io.flutter.embedding.engine.FlutterEngine} that should be rendered to a
         * {@link FlutterView}.
         *
         * <p>If {@code null} is returned, a new {@link io.flutter.embedding.engine.FlutterEngine} will
         * be created automatically.
         */
        @Nullable
        FlutterEngine provideFlutterEngine(@NonNull Context context);

        /**
         * Hook for the host to create/provide a {@link PlatformPlugin} if the associated Flutter
         * experience should control system chrome.
         */
        @Nullable
        PlatformPlugin providePlatformPlugin(
                @Nullable Activity activity, @NonNull FlutterEngine flutterEngine);

        /**
         * Hook for the host to configure the {@link io.flutter.embedding.engine.FlutterEngine} as
         * desired.
         */
        void configureFlutterEngine(@NonNull FlutterEngine flutterEngine);

        /**
         * Hook for the host to cleanup references that were established in {@link
         * #configureFlutterEngine(FlutterEngine)} before the host is destroyed or detached.
         */
        void cleanUpFlutterEngine(@NonNull FlutterEngine flutterEngine);

        /**
         * Returns true if the {@link io.flutter.embedding.engine.FlutterEngine}'s plugin system should
         * be connected to the host {@link android.app.Activity}, allowing plugins to interact with it.
         */
        boolean shouldAttachEngineToActivity();

        /**
         * Invoked by this delegate when the {@link FlutterSurfaceView} that renders the Flutter UI is
         * initially instantiated.
         *
         * <p>This method is only invoked if the {@link
         * io.flutter.embedding.android.FlutterView.RenderMode} is set to {@link
         * io.flutter.embedding.android.FlutterView.RenderMode#surface}. Otherwise, {@link
         * #onFlutterTextureViewCreated(FlutterTextureView)} is invoked.
         *
         * <p>This method is invoked before the given {@link FlutterSurfaceView} is attached to the
         * {@code View} hierarchy. Implementers should not attempt to climb the {@code View} hierarchy
         * or make assumptions about relationships with other {@code View}s.
         */
        void onFlutterSurfaceViewCreated(@NonNull FlutterSurfaceView flutterSurfaceView);

        /**
         * Invoked by this delegate when the {@link FlutterTextureView} that renders the Flutter UI is
         * initially instantiated.
         *
         * <p>This method is only invoked if the {@link
         * io.flutter.embedding.android.FlutterView.RenderMode} is set to {@link
         * io.flutter.embedding.android.FlutterView.RenderMode#texture}. Otherwise, {@link
         * #onFlutterSurfaceViewCreated(FlutterSurfaceView)} is invoked.
         *
         * <p>This method is invoked before the given {@link FlutterTextureView} is attached to the
         * {@code View} hierarchy. Implementers should not attempt to climb the {@code View} hierarchy
         * or make assumptions about relationships with other {@code View}s.
         */
        void onFlutterTextureViewCreated(@NonNull FlutterTextureView flutterTextureView);

        /**
         * Invoked by this delegate when its {@link FlutterView} starts painting pixels.
         */
        void onFlutterUiDisplayed();

        /**
         * Invoked by this delegate when its {@link FlutterView} stops painting pixels.
         */
        void onFlutterUiNoLongerDisplayed();

        /**
         * Whether state restoration is enabled.
         *
         * <p>When this returns true, the instance state provided to {@code
         * onRestoreInstanceState(Bundle)} will be forwarded to the framework via the {@code
         * RestorationChannel} and during {@code onSaveInstanceState(Bundle)} the current framework
         * instance state obtained from {@code RestorationChannel} will be stored in the provided
         * bundle.
         *
         * <p>This defaults to true, unless a cached engine is used.
         */
        boolean shouldRestoreAndSaveState();

        /**
         * Refreshes Android's window system UI (AKA system chrome) to match Flutter's desired system
         * chrome style.
         *
         * <p>This is useful when using the splash screen API available in Android 12. {@code
         * SplashScreenView#remove} resets the system UI colors to the values set prior to the execution
         * of the Dart entrypoint. As a result, the values set from Dart are reverted by this API. To
         * workaround this issue, call this method after removing the splash screen with {@code
         * SplashScreenView#remove}.
         */
        void updateSystemUiOverlays();
    }
}
