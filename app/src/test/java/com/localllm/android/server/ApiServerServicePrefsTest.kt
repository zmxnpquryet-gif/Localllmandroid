package com.localllm.android.server

import android.content.Context
import com.localllm.android.TestCrypto
import com.localllm.android.model.ModelRuntimeType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The server key must survive process restarts (it used to regenerate per
 * ViewModel creation, breaking every client config) and serving settings must
 * mirror what the ViewModel persists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiServerServicePrefsTest {

    @Before
    fun setUp() {
        TestCrypto.install()
    }

    @After
    fun tearDown() {
        TestCrypto.uninstall()
    }

    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("test_server_prefs", Context.MODE_PRIVATE)
        .also { it.edit().clear().apply() }

    @Test
    fun `api key is generated once and stable`() {
        val prefs = prefs()
        val first = ApiServerService.loadOrCreateApiKey(prefs)
        assertTrue(first.startsWith("sk-local-"))
        assertEquals(first, ApiServerService.loadOrCreateApiKey(prefs))
    }

    @Test
    fun `api key is never stored as plaintext`() {
        val prefs = prefs()
        val key = ApiServerService.loadOrCreateApiKey(prefs)
        assertNull(prefs.getString("api_server_key", null))
        val stored = prefs.getString("api_server_key_enc", null)
        assertNotNull(stored)
        assertFalse(stored!!.contains(key))
    }

    @Test
    fun `existing plaintext key is adopted then migrated away, blank is replaced`() {
        val prefs = prefs()
        prefs.edit().putString("api_server_key", "sk-local-kept").apply()
        assertEquals("sk-local-kept", ApiServerService.loadOrCreateApiKey(prefs))
        // The plaintext copy is gone after migration, and the key stays stable.
        assertNull(prefs.getString("api_server_key", null))
        assertEquals("sk-local-kept", ApiServerService.loadOrCreateApiKey(prefs))

        prefs.edit().clear().putString("api_server_key", "  ").apply()
        val fresh = ApiServerService.loadOrCreateApiKey(prefs)
        assertTrue(fresh.startsWith("sk-local-"))
    }

    @Test
    fun `serving settings default to loopback llama cpp`() {
        val s = ApiServerService.readServingSettings(prefs())
        assertEquals(ModelRuntimeType.LLAMA_CPP, s.runtime)
        assertEquals("127.0.0.1", s.apiServerBindAddress)
        assertEquals(true, s.apiServerRequireAuth)
        assertEquals(4096, s.contextWindow)
    }

    @Test
    fun `serving settings mirror persisted viewmodel values`() {
        val prefs = prefs()
        prefs.edit()
            .putString("runtime", ModelRuntimeType.SD_ENGINE.name)
            .putString("api_server_bind_address", "0.0.0.0")
            .putBoolean("api_server_require_auth", false)
            .putInt("context_window", 2048)
            .putFloat("temperature", 0.5f)
            .apply()
        val s = ApiServerService.readServingSettings(prefs)
        assertEquals(ModelRuntimeType.SD_ENGINE, s.runtime)
        assertEquals("0.0.0.0", s.apiServerBindAddress)
        assertEquals(false, s.apiServerRequireAuth)
        assertEquals(2048, s.contextWindow)
        assertEquals(0.5f, s.temperature, 0f)
    }

    @Test
    fun `corrupt runtime falls back to llama cpp`() {
        val prefs = prefs()
        prefs.edit().putString("runtime", "NOT_A_RUNTIME").apply()
        assertEquals(ModelRuntimeType.LLAMA_CPP, ApiServerService.readServingSettings(prefs).runtime)
    }
}
