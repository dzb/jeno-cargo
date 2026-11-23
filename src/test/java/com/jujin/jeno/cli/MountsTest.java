package com.jujin.jeno.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mount guard's parser, over a constructed {@code mountinfo}. A bind mount
 * of the same filesystem, or a mount one level below the target, is invisible
 * to a file-store comparison — and walking into it deletes the mounted files.
 */
class MountsTest {

    private static final String MOUNTINFO = """
        22 1 8:1 / / rw,relatime shared:1 - ext4 /dev/sda1 rw
        36 22 0:32 / /tmp/project/target rw,nosuid - tmpfs none rw
        37 36 0:33 / /tmp/project/target/cache rw - tmpfs none rw
        38 22 0:34 / /tmp/project/target-old rw - tmpfs none rw
        39 22 0:35 / /tmp/project/dist/with\\040space rw - tmpfs none rw
        """;

    @Test
    void theTargetItselfBeingAMountPointIsFound() {
        List<Path> mounts = Mounts.inside(MOUNTINFO, Path.of("/tmp/project/target"));

        assertEquals(List.of(Path.of("/tmp/project/target"),
                        Path.of("/tmp/project/target/cache")),
                mounts, "the tree's own mount and the one nested in it");
    }

    @Test
    void aMountPointNestedInsideTheTreeIsFound() {
        assertEquals(List.of(Path.of("/tmp/project/target/cache")),
                Mounts.inside(MOUNTINFO, Path.of("/tmp/project/target/cache")));
    }

    @Test
    void aSimilarPrefixIsNotAMatch() {
        assertTrue(Mounts.inside(MOUNTINFO, Path.of("/tmp/project/targe")).isEmpty(),
                "/tmp/project/target is not inside /tmp/project/targe");
        assertTrue(Mounts.inside(MOUNTINFO, Path.of("/tmp/project/dist/wi")).isEmpty(),
                "and not inside a name that is a prefix of its own");
    }

    @Test
    void octalEscapesInPathsAreDecoded() {
        List<Path> mounts = Mounts.inside(MOUNTINFO, Path.of("/tmp/project/dist"));

        assertEquals(List.of(Path.of("/tmp/project/dist/with space")), mounts,
                "mountinfo writes a space as \\040");
    }
}
