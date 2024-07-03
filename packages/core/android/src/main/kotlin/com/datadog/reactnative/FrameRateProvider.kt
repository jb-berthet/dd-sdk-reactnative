package com.datadog.reactnative

import com.facebook.infer.annotation.Assertions
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.modules.core.ChoreographerCompat
import com.facebook.react.modules.debug.DidJSUpdateUiDuringFrameDetector
import com.facebook.react.modules.debug.FpsDebugFrameCallback
import com.facebook.react.uimanager.UIManagerModule
import com.facebook.react.uimanager.debug.NotThreadSafeViewHierarchyUpdateDebugListener
import java.lang.ClassCastException
import java.util.TreeMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

internal class FrameRateProvider(
    reactFrameRateCallback: ((Double) -> Unit)?,
    reactContext: ReactContext
) {
    private val frameCallback: FpsFrameCallback =
        FpsFrameCallback(reactContext)

    private var backgroundExecutor: ScheduledExecutorService? = null

    private val monitor = Monitor(frameCallback, reactFrameRateCallback)

    fun start() {
        // Create an executor that executes tasks in a background thread.
        val executor = Executors.newSingleThreadScheduledExecutor()
        backgroundExecutor = executor
        frameCallback.reset()
        frameCallback.start()
        monitor.start(executor)
    }

    fun stop() {
        backgroundExecutor?.shutdown()
        backgroundExecutor = null
        frameCallback.stop()
        monitor.stop()
    }

    internal class Monitor(
        private val frameCallback: FpsFrameCallback,
        private val reactFrameRateCallback: ((Double) -> Unit)?
    ): Runnable {

        private var mShouldStop = false

        override fun run() {
            if (mShouldStop) {
                return
            }

            // Send JS FPS info
            reactFrameRateCallback?.let { it(frameCallback.jSFPS) }
        }

        fun start(backgroundExecutor: ScheduledExecutorService) {
            mShouldStop = false
            backgroundExecutor.execute(this)
        }

        fun stop() {
            mShouldStop = true
        }
    }
}

internal class FpsFrameCallback(private val mReactContext: ReactContext) :
    ChoreographerCompat.FrameCallback() {
    private var mChoreographer: ChoreographerCompat? = null

    private val mUIManagerModule: UIManagerModule? get() {
        var uiManagerModule: UIManagerModule? = null
        try {
            uiManagerModule = mReactContext.getNativeModule(
                UIManagerModule::class.java
            )
        } catch (_: ClassCastException) {}
        return uiManagerModule
    }

    private val mDidJSUpdateUiDuringFrameDetector: DidJSUpdateUiDuringFrameDetector =
        DidJSUpdateUiDuringFrameDetector()
    private var mFirstFrameTime = -1L
    private var mLastFrameTime = -1L
    private var mNumFrameCallbacks = 0
    private var mExpectedNumFramesPrev = 0
    private var m4PlusFrameStutters = 0
    private var mNumFrameCallbacksWithBatchDispatches = 0
    private var mIsRecordingFpsInfoAtEachFrame = false
    private var mTimeToFps: TreeMap<Long?, FpsDebugFrameCallback.FpsInfo?>? = null

    val fPS: Double
        get() = if (mLastFrameTime == mFirstFrameTime) 0.0 else numFrames.toDouble() * 1.0E9 / (mLastFrameTime - mFirstFrameTime).toDouble()
    val jSFPS: Double
        get() = if (mLastFrameTime == mFirstFrameTime) 0.0 else numJSFrames.toDouble() * 1.0E9 / (mLastFrameTime - mFirstFrameTime).toDouble()
    val numFrames: Int
        get() = mNumFrameCallbacks - 1
    val numJSFrames: Int
        get() = mNumFrameCallbacksWithBatchDispatches - 1
    val expectedNumFrames: Int
        get() {
            val totalTimeMS = totalTimeMS.toDouble()
            return (totalTimeMS / 16.9 + 1.0).toInt()
        }
    val totalTimeMS: Int
        get() = (mLastFrameTime.toDouble() - mFirstFrameTime.toDouble()).toInt() / 1000000


    override fun doFrame(l: Long) {
        if (mFirstFrameTime == -1L) {
            mFirstFrameTime = l
        }
        val lastFrameStartTime = mLastFrameTime
        mLastFrameTime = l
        if (mDidJSUpdateUiDuringFrameDetector.getDidJSHitFrameAndCleanup(lastFrameStartTime, l)) {
            ++mNumFrameCallbacksWithBatchDispatches
        }
        ++mNumFrameCallbacks
        val expectedNumFrames = expectedNumFrames
        val framesDropped = expectedNumFrames - mExpectedNumFramesPrev - 1
        if (framesDropped >= 4) {
            ++m4PlusFrameStutters
        }

        mExpectedNumFramesPrev = expectedNumFrames
        if (mChoreographer != null) {
            mChoreographer!!.postFrameCallback(this)
        }
    }

    fun start() {
        mReactContext.catalystInstance.addBridgeIdleDebugListener(mDidJSUpdateUiDuringFrameDetector)
        mUIManagerModule?.setViewHierarchyUpdateDebugListener(mDidJSUpdateUiDuringFrameDetector)
        UiThreadExecutor.Provider.uiThreadExecutor.runOnUiThread {
            mChoreographer = ChoreographerCompat.getInstance()
            mChoreographer?.postFrameCallback(this@FpsFrameCallback)
        }
    }

    fun stop() {
        mReactContext.catalystInstance.removeBridgeIdleDebugListener(
            mDidJSUpdateUiDuringFrameDetector
        )
        mUIManagerModule?.setViewHierarchyUpdateDebugListener(null as NotThreadSafeViewHierarchyUpdateDebugListener?)
        UiThreadExecutor.Provider.uiThreadExecutor.runOnUiThread {
            mChoreographer = ChoreographerCompat.getInstance()
            mChoreographer?.removeFrameCallback(this@FpsFrameCallback)
        }
    }

    fun reset() {
        mFirstFrameTime = -1L
        mLastFrameTime = -1L
        mNumFrameCallbacks = 0
        m4PlusFrameStutters = 0
        mNumFrameCallbacksWithBatchDispatches = 0
        mIsRecordingFpsInfoAtEachFrame = false
        mTimeToFps = null
    }
}



