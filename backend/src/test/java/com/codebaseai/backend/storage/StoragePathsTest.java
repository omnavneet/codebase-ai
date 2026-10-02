package com.codebaseai.backend.storage;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

/**
 * The traversal guard is the one piece of the storage layer both backends share,
 * so it is tested on its own: everything else in {@link StorageService} is a thin
 * wrapper around it.
 */
class StoragePathsTest {

    @Test
    void normalisesBackslashesToForwardSlashes() {
        assertEquals("src/main/Foo.java", StoragePaths.requireRelative("src\\main\\Foo.java"));
    }

    @Test
    void keepsAlreadyNormalisedPathsUnchanged() {
        assertEquals("src/main/Foo.java", StoragePaths.requireRelative("src/main/Foo.java"));
    }

    @Test
    void rejectsBlankPaths() {
        assertThrows(IllegalArgumentException.class, () -> StoragePaths.requireRelative(null));
        assertThrows(IllegalArgumentException.class, () -> StoragePaths.requireRelative("   "));
    }

    @Test
    void rejectsAbsoluteAndSchemeQualifiedPaths() {
        assertThrows(IllegalArgumentException.class, () -> StoragePaths.requireRelative("/etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> StoragePaths.requireRelative("C:/Windows/system32"));
        assertThrows(IllegalArgumentException.class, () -> StoragePaths.requireRelative("s3://bucket/key"));
    }

    @Test
    void rejectsTraversalSegmentsInEitherSeparatorStyle() {
        assertThrows(IllegalArgumentException.class, () -> StoragePaths.requireRelative("../secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> StoragePaths.requireRelative("src/../../secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> StoragePaths.requireRelative("..\\secret.txt"));
    }

    @Test
    void resolveWithinKeepsOrdinaryPathsInsideTheRoot() {
        Path root = Path.of("uploads").toAbsolutePath();

        assertEquals(root.resolve("src/main/Foo.java").normalize(),
                StoragePaths.resolveWithin(root, "src/main/Foo.java"));
    }

    @Test
    void resolveWithinRejectsEscapes() {
        Path root = Path.of("uploads");

        assertThrows(IllegalArgumentException.class,
                () -> StoragePaths.resolveWithin(root, "../outside.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> StoragePaths.resolveWithin(root, "src/../../outside.txt"));
    }
}