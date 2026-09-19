package com.orange.echocards

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test. debug 变体带 .debug 后缀，这里只校验前缀。
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(appContext.packageName.startsWith("com.orange.echocards"))
    }
}