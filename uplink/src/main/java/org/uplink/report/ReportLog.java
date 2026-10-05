package org.uplink.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Keeps the final report of the latest run in a file, replacing the previous one. */
public final class ReportLog {

    /** In the directory uplink runs from. */
    public static final Path DEFAULT = Path.of(".uplink.local.log.txt");

    private ReportLog() {
    }

    /**
     * Writes the report to a temporary file next to {@code file} and renames it over
     * {@code file}, so a symlink planted at that name is replaced, never written through.
     */
    public static void save(Path file, String report) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, ".uplink.", ".tmp");
        try {
            Files.writeString(tmp, report, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
