package edu.skope.attachment;

import edu.skope.service.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** File storage only, in a JUnit temporary folder that is deleted after each test. */
class AttachmentStorageTest {
    @TempDir Path temp;

    AttachmentStorage storage() { return new AttachmentStorage(temp.resolve("attachments").toString()); }
    static List<String> names(Path dir) throws Exception {
        try (var files = Files.list(dir)) { return files.map(p -> p.getFileName().toString()).sorted().toList(); }
    }

    @Test void writtenFileIsNamedOnlyByTheServerKeyInsideTheRoot() throws Exception {
        var storage = storage();
        String key = storage.newKey();
        storage.write(key, new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, storage.read(key));
        assertEquals(List.of(key), names(storage.root()), "no extension, no original name, no leftover .part");
        assertTrue(storage.exists(key));
    }

    @Test void keysAreRandomUuids() {
        var storage = storage();
        String a = storage.newKey(), b = storage.newKey();
        assertNotEquals(a, b);
        assertDoesNotThrow(() -> storage.path(a));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../application.properties", "..\\..\\secrets.json", "C:\\Windows\\win.ini", "/etc/passwd",
        "photo.jpg", "", "3F2A9C1E-5B7D-4E8A-9C21-7D4E5F6A8B90", "3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90/../../x",
        "3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90.part"})
    void keysThatAreNotPlainUuidsNeverBecomePaths(String key) {
        var storage = storage();
        assertThrows(IllegalArgumentException.class, () -> storage.path(key));
        assertThrows(IllegalArgumentException.class, () -> storage.read(key));
        assertThrows(IllegalArgumentException.class, () -> storage.write(key, new byte[]{1}));
        assertEquals(AttachmentStorage.Removal.FAILED, storage.remove(key), "remove never throws, even for a hostile key");
    }

    @Test void existingFileIsNeverOverwrittenAndFailedWriteLeavesNoPartialFile() throws Exception {
        var storage = storage();
        String key = storage.newKey();
        storage.write(key, new byte[]{1});
        var e = assertThrows(ApiException.class, () -> storage.write(key, new byte[]{9, 9}));
        assertEquals(503, e.status);
        assertEquals("ATTACHMENT_STORAGE_FAILED", e.code);
        assertArrayEquals(new byte[]{1}, storage.read(key), "first photo untouched");
        assertEquals(List.of(key), names(storage.root()), "the failed attempt's .part file was removed");
    }

    @Test void unusableStorageFolderFailsCleanly() throws Exception {
        Path blocker = temp.resolve("not-a-folder");
        Files.writeString(blocker, "a file where the folder should be");
        var storage = new AttachmentStorage(blocker.toString());
        var e = assertThrows(ApiException.class, () -> storage.write(storage.newKey(), new byte[]{1}));
        assertEquals(503, e.status);
        assertEquals("a file where the folder should be", Files.readString(blocker));
    }

    @Test void removeTellsDeletedApartFromAlreadyGone() {
        var storage = storage();
        String key = storage.newKey();
        storage.write(key, new byte[]{1});
        assertEquals(AttachmentStorage.Removal.DELETED, storage.remove(key));
        assertFalse(storage.exists(key));
        assertEquals(AttachmentStorage.Removal.ALREADY_GONE, storage.remove(key), "second remove: nothing left, not an error");
    }

    /** A real failure, not a mock: on Windows a file held open by another reader cannot be deleted. */
    @Test void fileHeldOpenElsewhereIsReportedAsFailedAndKept() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"), "Windows file locking");
        var storage = storage();
        String key = storage.newKey();
        storage.write(key, new byte[]{1});
        try (var held = new java.io.FileInputStream(storage.path(key).toFile())) {
            assertEquals(AttachmentStorage.Removal.FAILED, storage.remove(key));
            assertTrue(storage.exists(key));
        }
        assertEquals(AttachmentStorage.Removal.DELETED, storage.remove(key), "works once the file is released");
    }

    @Test void missingFileIsReportedAsNotFound() {
        var storage = storage();
        var e = assertThrows(ApiException.class, () -> storage.read(storage.newKey()));
        assertEquals(404, e.status);
        assertEquals("ATTACHMENT_NOT_FOUND", e.code);
    }

    @Test void partialFilesLeftByAStoppedServerAreRemovedOnStartup() throws Exception {
        Path root = temp.resolve("attachments");
        Files.createDirectories(root);
        String kept = "3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90";
        Files.write(root.resolve(kept), new byte[]{1});
        Files.write(root.resolve("0a1b2c3d-0000-4000-8000-000000000000.part"), new byte[]{2});
        new AttachmentStorage(root.toString());
        assertEquals(List.of(kept), names(root));
    }
}
