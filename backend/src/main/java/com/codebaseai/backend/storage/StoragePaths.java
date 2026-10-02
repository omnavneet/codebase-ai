package com.codebaseai.backend.storage;

import java.nio.file.Path;

/**
 * Shared validation for project-relative paths before they become a filesystem
 * path (local storage) or an object key (S3). Keeping the traversal guard in one
 * place means it cannot drift between the two backends or the controllers.
 */
public final class StoragePaths {

    private StoragePaths() {
    }

    /**
     * Normalises a caller-supplied project-relative path to forward slashes and
     * rejects anything that could escape the project root.
     *
     * @throws IllegalArgumentException if the path is blank, absolute, carries a
     *         drive/URI scheme, or contains a {@code ..} segment
     */
    public static String requireRelative(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Path is required");
        }
        String normalized = relativePath.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":")) {
            throw new IllegalArgumentException("Path must be project-relative: " + relativePath);
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("Path traversal is not allowed: " + relativePath);
            }
        }
        return normalized;
    }

    /**
     * Resolves {@code relativePath} under {@code root} and verifies the result is
     * still inside it. Defence in depth: {@link #requireRelative} already blocks
     * traversal syntax, but a symlink or a platform quirk must not escape either.
     */
    public static Path resolveWithin(Path root, String relativePath) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(requireRelative(relativePath)).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Path escapes the project root: " + relativePath);
        }
        return resolved;
    }
}