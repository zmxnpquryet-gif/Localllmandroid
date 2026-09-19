// SDengine — TEST BUILD. EXPERIMENTAL. See SDEngine.ADVISORIES.
package com.localllm.engine

/**
 * JNI bridge with a JVM fallback behind it: native accelerates when present,
 * the JVM implementation guarantees the result everywhere else (including
 * desktop unit tests, where Android .so files cannot load by design).
 */
object SDEngineNative {
    @Volatile
    var available: Boolean = false
        private set

    init {
        available = try {
            System.loadLibrary("sdengine")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    external fun version(): String

    external fun dot(a: FloatArray, b: FloatArray): Double

    fun dotJvm(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size && a.isNotEmpty())
        var acc = 0.0
        for (i in a.indices) acc += a[i] * b[i]
        return acc
    }

    /** Native when loaded, JVM otherwise. Never throws for missing lib. */
    fun dotAuto(a: FloatArray, b: FloatArray): Double {
        if (available) {
            try {
                return dot(a, b)
            } catch (_: UnsatisfiedLinkError) {
                available = false
            }
        }
        return dotJvm(a, b)
    }
}
