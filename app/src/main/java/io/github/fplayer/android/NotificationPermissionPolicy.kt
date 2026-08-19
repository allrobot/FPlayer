package io.github.fplayer.android

internal object NotificationPermissionPolicy {
    fun requiresRuntimeRequest(sdkInt: Int, permissionGranted: Boolean): Boolean =
        sdkInt >= 33 && !permissionGranted
}
