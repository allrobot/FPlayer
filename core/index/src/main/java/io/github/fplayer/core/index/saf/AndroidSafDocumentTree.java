package io.github.fplayer.core.index.saf;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** ContentResolver-backed SAF tree reader. It never opens file contents. */
public final class AndroidSafDocumentTree implements SafDocumentTree {
    private static final String[] PROJECTION = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };

    private final ContentResolver resolver;
    private final Uri treeUri;

    public AndroidSafDocumentTree(ContentResolver resolver, Uri treeUri) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.treeUri = Objects.requireNonNull(treeUri, "treeUri");
    }

    @NonNull
    @Override
    public Entry root() throws IOException {
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
        return querySingle(documentUri, rootId);
    }

    @NonNull
    @Override
    public List<Entry> children(@NonNull Entry directory) throws IOException {
        if (!directory.directory) {
            throw new IllegalArgumentException("SAF_ENTRY_NOT_DIRECTORY");
        }
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                directory.documentId
        );
        ArrayList<Entry> result = new ArrayList<>();
        try (Cursor cursor = resolver.query(childrenUri, PROJECTION, null, null, null)) {
            if (cursor == null) {
                throw new IOException("SAF_QUERY_RETURNED_NULL");
            }
            while (cursor.moveToNext()) {
                result.add(readEntry(cursor));
            }
        } catch (SecurityException exception) {
            throw new SafAccessException("SAF_PERMISSION_LOST", exception);
        }
        return result;
    }

    private Entry querySingle(Uri documentUri, String expectedDocumentId) throws IOException {
        try (Cursor cursor = resolver.query(documentUri, PROJECTION, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new FileNotFoundException("SAF_ROOT_NOT_FOUND");
            }
            Entry entry = readEntry(cursor);
            if (!expectedDocumentId.equals(entry.documentId)) {
                throw new IOException("SAF_ROOT_ID_MISMATCH");
            }
            return entry;
        } catch (SecurityException exception) {
            throw new SafAccessException("SAF_PERMISSION_LOST", exception);
        }
    }

    private Entry readEntry(Cursor cursor) {
        String documentId = required(cursor, DocumentsContract.Document.COLUMN_DOCUMENT_ID);
        String displayName = required(cursor, DocumentsContract.Document.COLUMN_DISPLAY_NAME);
        String mimeType = required(cursor, DocumentsContract.Document.COLUMN_MIME_TYPE);
        Uri locator = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        return new Entry(
                documentId,
                locator.toString(),
                displayName,
                mimeType,
                DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType),
                nullableLong(cursor, DocumentsContract.Document.COLUMN_SIZE),
                nullableLong(cursor, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        );
    }

    private static String required(Cursor cursor, String column) {
        String value = cursor.getString(cursor.getColumnIndexOrThrow(column));
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("SAF_REQUIRED_COLUMN_MISSING");
        }
        return value;
    }

    private static Long nullableLong(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? null : cursor.getLong(index);
    }

    public static final class SafAccessException extends IOException {
        public SafAccessException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
