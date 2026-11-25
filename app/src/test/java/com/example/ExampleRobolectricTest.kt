package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `verify app name and zero-trust offline manifest invariants`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Kavach-UPI", appName)

        // Verify 100% edge autonomy: INTERNET permission must NOT be requested in Manifest
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val requestedPermissions = packageInfo.requestedPermissions?.toSet().orEmpty()
        assertFalse(
            "Kavach-UPI must remain 100% offline with zero INTERNET permission",
            requestedPermissions.contains(Manifest.permission.INTERNET)
        )
        assertTrue(requestedPermissions.contains(Manifest.permission.SYSTEM_ALERT_WINDOW))
        assertTrue(requestedPermissions.contains(Manifest.permission.PACKAGE_USAGE_STATS))
    }

    @Test
    fun `verify DFS depth limit and two-cycle hysteresis constants`() {
        assertEquals(15, KavachAccessibilityService.MAX_DFS_DEPTH)
        assertEquals(2, KavachAccessibilityService.HYSTERESIS_CLEAN_CYCLES_REQUIRED)
        assertTrue(
            KavachSecurityRegistry.PROTECTED_PACKAGE_MAP.containsKey(
                "com.google.android.apps.nbu.paisa.user"
            )
        )
        assertTrue(
            KavachSecurityRegistry.REMOTE_THREAT_MAP.containsKey(
                "com.anydesk.anydeskandroid"
            )
        )
    }
}
