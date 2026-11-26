package io.folderaudio.player;

import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class Library {
    private static final String[] COLUMNS = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE};

    static final class Track {
        final Uri uri;
        final String name;
        final String folder;
        final String format;

        Track(Uri uri, String name, String folder, String format) {
            this.uri = uri;
            this.name = name;
            this.folder = folder;
            this.format = format;
        }
    }

    static final class Scan {
        final List<Track> tracks = new ArrayList<>();
        final List<String> inaccessible = new ArrayList<>();
    }

    private static final class Folder {
        final String id;
        final String path;

        Folder(String id, String path) {
            this.id = id;
            this.path = path;
        }
    }

    static List<Uri> roots(Context context) {
        List<Uri> result = new ArrayList<>();
        for (UriPermission grant : context.getContentResolver().getPersistedUriPermissions()) {
            if (grant.isReadPermission() && DocumentsContract.isTreeUri(grant.getUri())) {
                result.add(grant.getUri());
            }
        }
        result.sort(Comparator.comparing(Uri::toString));
        return result;
    }

    static String label(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        int separator = Math.max(id.lastIndexOf('/'), id.lastIndexOf(':'));
        return separator + 1 < id.length() ? id.substring(separator + 1) : id;
    }

    static Scan scan(ContentResolver resolver, List<Uri> roots) {
        Scan result = new Scan();
        Set<String> listed = new HashSet<>();
        for (Uri root : roots) {
            Set<String> visited = new HashSet<>();
            ArrayDeque<Folder> pending = new ArrayDeque<>();
            pending.add(new Folder(DocumentsContract.getTreeDocumentId(root), rootLabel(resolver, root)));
            boolean failed = false;
            while (!pending.isEmpty() && !Thread.currentThread().isInterrupted()) {
                Folder folder = pending.removeFirst();
                if (!visited.add(folder.id)) continue;
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(root, folder.id);
                try (Cursor cursor = resolver.query(children, COLUMNS, null, null, null)) {
                    if (cursor == null) {
                        failed = true;
                        continue;
                    }
                    while (!Thread.currentThread().isInterrupted() && cursor.moveToNext()) {
                        String id = cursor.getString(0);
                        String name = cursor.getString(1);
                        String mime = cursor.getString(2);
                        if (id == null || name == null) continue;
                        if (Document.MIME_TYPE_DIR.equals(mime)) {
                            pending.addLast(new Folder(id, folder.path + " / " + name));
                            continue;
                        }
                        String format = format(name, mime);
                        if (format != null && listed.add(root.getAuthority() + "\n" + id)) {
                            Uri file = DocumentsContract.buildDocumentUriUsingTree(root, id);
                            result.tracks.add(new Track(file, name, folder.path, format));
                        }
                    }
                } catch (RuntimeException unavailable) {
                    failed = true;
                }
            }
            if (failed) result.inaccessible.add(label(root));
            if (Thread.currentThread().isInterrupted()) break;
        }
        result.tracks.sort(Comparator.comparing((Track t) -> t.folder, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(t -> t.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private static String rootLabel(ContentResolver resolver, Uri tree) {
        Uri document = DocumentsContract.buildDocumentUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cursor = resolver.query(document,
                new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) {
                return cursor.getString(0);
            }
        } catch (RuntimeException unavailable) {
            return label(tree);
        }
        return label(tree);
    }

    private static String format(String name, String mime) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".flac") || "audio/flac".equals(mime) || "audio/x-flac".equals(mime)) return "FLAC";
        if (lower.endsWith(".alac") || "audio/alac".equals(mime)) return "ALAC";
        if (lower.endsWith(".mp3") || "audio/mpeg".equals(mime)) return "MP3";
        if (lower.endsWith(".wav") || lower.endsWith(".wave") || "audio/wav".equals(mime)
                || "audio/x-wav".equals(mime)) return "WAV";
        if (lower.endsWith(".caf")) return "CAF";
        if (lower.endsWith(".m4a") || lower.endsWith(".mp4") || lower.endsWith(".m4b")
                || "audio/mp4".equals(mime)) return "MPEG-4 audio";
        return null;
    }

    private Library() {}
}
