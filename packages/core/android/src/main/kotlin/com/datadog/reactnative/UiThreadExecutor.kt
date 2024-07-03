package com.datadog.reactnative

import com.facebook.react.bridge.UiThreadUtil

interface UiThreadExecutor {
    fun runOnUiThread(runnable: Runnable)

    object Provider {
        val uiThreadExecutor: UiThreadExecutor by lazy {
            if (isRunningInTest()) {
                TestUiThreadExecutor()
            } else {
                RealUiThreadExecutor()
            }
        }

        private fun isRunningInTest(): Boolean {
            return System.getProperty("IS_UNIT_TEST") == "true"
        }
    }
}

class RealUiThreadExecutor : UiThreadExecutor {
    override fun runOnUiThread(runnable: Runnable) {
        UiThreadUtil.runOnUiThread(runnable)
    }
}

class TestUiThreadExecutor : UiThreadExecutor {
    override fun runOnUiThread(runnable: Runnable) {
        // Run immediately in the same thread for tests
        runnable.run()
    }
}
