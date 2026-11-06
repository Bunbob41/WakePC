package com.morgan.wakepc

import org.junit.Assert.fail
import org.junit.Test

/**
 * The setup QR scanner was the app's only closed-source dependency (Google
 * Play services), and it went in 0.14.0. These classes are what it pulled in;
 * if any comes back on the classpath, something has re-added it.
 */
class OpenSourceTest {
    @Test
    fun `no closed-source Play services code is on the classpath`() {
        listOf(
            "com.google.mlkit.vision.codescanner.GmsBarcodeScanning",
            "com.google.android.gms.tasks.Task",
            "com.google.android.gms.common.api.Api",
        ).forEach { name ->
            try {
                Class.forName(name)
                fail("$name is on the classpath, so Play services is back")
            } catch (expected: ClassNotFoundException) {
                // Absent, as it should be.
            }
        }
    }
}
