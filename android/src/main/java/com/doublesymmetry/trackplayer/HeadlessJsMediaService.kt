/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/*
 * Adapted from lovegaoshi/react-native-track-player, branch APM,
 * android/src/main/java/com/doublesymmetry/trackplayer/HeadlessJsMediaService.kt (Apache-2.0 —
 * see com/doublesymmetry/kotlinaudio/NOTICE.md). Changes from that version: the wake lock is
 * acquired again (APM commented it out), `reactContext` tolerates a ReactHost that does not exist
 * yet instead of `checkNotNull`-ing, and `ensureReactContext` is exposed so `MusicService` can
 * start the runtime for an Android Auto browse with no task to run.
 */

package com.doublesymmetry.trackplayer

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactInstanceEventListener
import com.facebook.react.ReactNativeHost
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.internal.featureflags.ReactNativeFeatureFlags
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import com.facebook.react.jstasks.HeadlessJsTaskContext
import com.facebook.react.jstasks.HeadlessJsTaskEventListener
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Base class for running JS without a UI. Generally, you only need to override [getTaskConfig],
 * which is called for every [onStartCommand]. The result, if not `null`, is used to run a JS task.
 *
 * If you need more fine-grained control over how tasks are run, you can override [onStartCommand]
 * and call [startTask] depending on your custom logic.
 *
 * If you're starting a `HeadlessJsTaskService` from a `BroadcastReceiver` (e.g. handling push
 * notifications), make sure to call [acquireWakeLockNow] before returning from
 * [BroadcastReceiver.onReceive], to make sure the device doesn't go to sleep before the service is
 * started.
 *
 * The base class is a media3 [MediaLibraryService] now rather than a `MediaBrowserServiceCompat`,
 * and the React runtime is started through [ReactHost] when the app is bridgeless — which RN 0.86
 * always is — rather than through the deprecated `ReactInstanceManager`.
 */
@UnstableApi
abstract class HeadlessJsMediaService : MediaLibraryService(), HeadlessJsTaskEventListener {
    private val activeTasks: MutableSet<Int> = CopyOnWriteArraySet()
    private var initialized = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val taskConfig = getTaskConfig(intent)
        return if (!initialized && taskConfig != null) {
            initialized = true
            startTask(taskConfig)
            START_REDELIVER_INTENT
        } else {
            START_NOT_STICKY
        }
    }

    /**
     * Called from [onStartCommand] to create a [HeadlessJsTaskConfig] for this intent.
     *
     * @return a [HeadlessJsTaskConfig] to be used with [startTask], or `null` to ignore this command.
     */
    protected open fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig? = null

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    /**
     * Start a task. This method handles starting a new React instance if required.
     *
     * Has to be called on the UI thread.
     */
    protected fun startTask(taskConfig: HeadlessJsTaskConfig) {
        UiThreadUtil.assertOnUiThread()
        acquireWakeLockNow(this)

        val context = reactContext
        if (context == null) {
            createReactContextAndScheduleTask(taskConfig)
        } else {
            invokeStartTask(context, taskConfig)
        }
    }

    private fun invokeStartTask(reactContext: ReactContext, taskConfig: HeadlessJsTaskConfig) {
        val headlessJsTaskContext = HeadlessJsTaskContext.getInstance(reactContext)
        headlessJsTaskContext.addTaskEventListener(this)
        UiThreadUtil.runOnUiThread {
            val taskId = headlessJsTaskContext.startTask(taskConfig)
            activeTasks.add(taskId)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        reactContext?.let { context ->
            HeadlessJsTaskContext.getInstance(context).removeTaskEventListener(this)
        }
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    override fun onHeadlessJsTaskStart(taskId: Int): Unit = Unit

    override fun onHeadlessJsTaskFinish(taskId: Int) {
        activeTasks.remove(taskId)
        if (activeTasks.isEmpty()) {
            stopSelf()
        }
    }

    protected val reactNativeHost: ReactNativeHost
        get() = (application as ReactApplication).reactNativeHost

    protected val reactHost: ReactHost?
        get() = (application as? ReactApplication)?.reactHost

    protected val reactContext: ReactContext?
        @SuppressLint("VisibleForTests")
        get() = try {
            if (ReactNativeFeatureFlags.enableBridgelessArchitecture()) {
                reactHost?.currentReactContext
            } else {
                reactNativeHost.reactInstanceManager.currentReactContext
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not read the current React context")
            null
        }

    /**
     * Bring the React runtime up with nothing to run in it.
     *
     * The Android Auto cold start needs this: the head unit asks for the browse tree of an app whose
     * JS has never run, and the tree is built in JS. There is no headless *task* to start — the
     * TurboModule binds to this service as soon as the runtime exists and pushes the tree in — so
     * this only starts the host and reports back when the context is there.
     */
    protected fun ensureReactContext(onReady: (ReactContext) -> Unit): Boolean {
        val existing = reactContext
        if (existing != null) {
            onReady(existing)
            return false
        }
        if (ReactNativeFeatureFlags.enableBridgelessArchitecture()) {
            val host = reactHost
            if (host == null) {
                Timber.tag(TAG).e("ReactHost is null; cannot start React Native headlessly")
                return false
            }
            host.addReactInstanceEventListener(
                object : ReactInstanceEventListener {
                    override fun onReactContextInitialized(context: ReactContext) {
                        host.removeReactInstanceEventListener(this)
                        onReady(context)
                    }
                }
            )
            host.start()
        } else {
            val manager = reactNativeHost.reactInstanceManager
            manager.addReactInstanceEventListener(
                object : ReactInstanceEventListener {
                    override fun onReactContextInitialized(context: ReactContext) {
                        manager.removeReactInstanceEventListener(this)
                        onReady(context)
                    }
                }
            )
            manager.createReactContextInBackground()
        }
        return true
    }

    private fun createReactContextAndScheduleTask(taskConfig: HeadlessJsTaskConfig) {
        ensureReactContext { context -> invokeStartTask(context, taskConfig) }
    }

    companion object {
        private const val TAG = "RNTP-Headless"

        @JvmStatic
        var wakeLock: WakeLock? = null

        /**
         * Acquire a wake lock to ensure the device doesn't go to sleep while processing background
         * tasks.
         */
        @JvmStatic
        @SuppressLint("WakelockTimeout")
        fun acquireWakeLockNow(context: Context) {
            if (wakeLock == null || wakeLock?.isHeld == false) {
                val powerManager = checkNotNull(context.getSystemService(POWER_SERVICE) as PowerManager)
                wakeLock = powerManager
                    .newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        HeadlessJsMediaService::class.java.canonicalName
                    )
                    .also { lock ->
                        lock.setReferenceCounted(false)
                        lock.acquire()
                    }
            }
        }
    }
}
