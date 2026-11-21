package com.jujin.mj.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Copies a source-resources tree onto the compiled output: {@code
 * src/main/resources} lands in {@code target/classes}, {@code
 * src/test/resources} in {@code target/test-classes} — the Maven-shaped
 * contract every build tool honors. Without it, classpath resources
 * (properties, {@code META-INF/services/}) only exist when a previous
 * Maven build happened to leave them behind.
 */
final class SourceResources {

    private SourceResources() {
    }

    /** Copies every regular file under {@code source} to the same relative spot in {@code target}. */
    static void copy(Path source, Path target) {
        if (!Files.isDirectory(source)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                Path destination = target.resolve(source.relativize(file));
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "cannot copy resources from " + source + " to " + target, e);
        }
    }
}
