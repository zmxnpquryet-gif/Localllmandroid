// SDengine — TEST BUILD. On-device JNI contract: the .so loads and computes.
package com.localllm.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SDEngineNativeTest {

    @Test
    fun nativeLibraryLoadsAndComputes() {
        assertTrue("libsdengine.so failed to load", SDEngineNative.available)
        val version = SDEngineNative.version()
        assertTrue(version.startsWith("sdengine-native"))
        val a = floatArrayOf(1f, 2f, 3f, 4f)
        val b = floatArrayOf(5f, 6f, 7f, 8f)
        assertEquals(70.0, SDEngineNative.dot(a, b), 1e-9)
        assertEquals(SDEngineNative.dotJvm(a, b), SDEngineNative.dot(a, b), 1e-9)
    }
}
