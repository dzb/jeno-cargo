package com.jujin.jeno.cli;

import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.zip.ZipEntry;

/**
 * The one place a jar entry gets its metadata, so a build's bytes depend only
 * on what went in, not on when it ran.
 *
 * <p>1980-02-01T00:00:00Z: right after the DOS epoch (1980-01-01), and inside
 * every zip writer's representable range. The exact instant is arbitrary; what
 * matters is that it is a constant and not "now".
 *
 * <p>Creation and last-access times are set too. Modern zip writers emit them
 * as extra fields when present (the JDK's does as soon as one is set), and an
 * extra field with a wall-clock value changes the bytes even when the entry's
 * DOS time is fixed.
 */
final class ZipEntries {

    private static final LocalDateTime DOS_TIME = LocalDateTime.of(1980, 2, 1, 0, 0, 0);
    private static final FileTime FILE_TIME =
            FileTime.fromMillis(DOS_TIME.toInstant(ZoneOffset.UTC).toEpochMilli());

    private ZipEntries() {}

    /** A deterministic entry: fixed local DOS time, no other timestamps. */
    static ZipEntry entry(String name) {
        ZipEntry entry = new ZipEntry(name);
        // setTimeLocal writes the DOS fields directly; setTime would go through
        // the default time zone and make the bytes depend on the machine's zone
        entry.setTimeLocal(DOS_TIME);
        entry.setCreationTime(FILE_TIME);
        entry.setLastAccessTime(FILE_TIME);
        return entry;
    }
}
