package com.example.voicenotes

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidManifestConfigurationTest {

    @Test
    fun manifestDisablesDefaultWorkManagerInitializerAndDeclaresInternetPermission() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.permission.INTERNET"))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"true\""))
        assertTrue(manifest.contains("androidx.startup.InitializationProvider"))
        assertTrue(manifest.contains("androidx.work.WorkManagerInitializer"))
        assertTrue(manifest.contains("tools:node=\"remove\""))
    }
}
