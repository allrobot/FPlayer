package io.github.fplayer.core.index.saf;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.util.LinkedHashSet;
import java.util.Set;

/** Owns persisted, read-only SAF tree grants used by local index sources. */
public final class SafPermissionStore {
    private final ContentResolver resolver;

    public SafPermissionStore(Context context) {
        resolver = context.getApplicationContext().getContentResolver();
    }

    public void persistReadOnly(@NonNull Uri treeUri) {
        resolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
        );
    }

    public boolean hasReadPermission(@NonNull Uri treeUri) {
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (permission.isReadPermission() && treeUri.equals(permission.getUri())) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    public Set<Uri> grantedTrees() {
        LinkedHashSet<Uri> result = new LinkedHashSet<>();
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (permission.isReadPermission()) {
                result.add(permission.getUri());
            }
        }
        return result;
    }

    public void release(@NonNull Uri treeUri) {
        resolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
        );
    }
}
