package io.github.fplayer.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionPolicyTest {
    @Test fun preApi33DoesNotRequestRuntimePermission() {
        assertFalse(NotificationPermissionPolicy.requiresRuntimeRequest(32, permissionGranted = false))
    }

    @Test fun api33RequestsOnlyWhenPermissionIsMissing() {
        assertTrue(NotificationPermissionPolicy.requiresRuntimeRequest(33, permissionGranted = false))
        assertFalse(NotificationPermissionPolicy.requiresRuntimeRequest(33, permissionGranted = true))
    }
}
