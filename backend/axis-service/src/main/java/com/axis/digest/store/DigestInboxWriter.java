package com.axis.digest.store;

import com.axis.digest.config.DigestProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;

/**
 * Writes a daily digest markdown file atomically to {@code <inboxDir>/daily-YYYY-MM-DD.md}.
 * Re-running for the same day overwrites the file safely (tmp + rename).
 */
@Slf4j
@Component
public class DigestInboxWriter {

    private final Path inboxDir;

    public DigestInboxWriter(DigestProperties props) {
        this.inboxDir = Paths.get(props.inboxDir());
    }

    /**
     * Atomically write the markdown body to {@code <inboxDir>/daily-{date}.md}.
     *
     * @return absolute path of the written file.
     */
    public Path writeDailyFile(LocalDate date, String markdown) throws IOException {
        Files.createDirectories(inboxDir);
        Path target = inboxDir.resolve("daily-" + date + ".md");
        Path tmp = Files.createTempFile(inboxDir, "digest-", ".tmp");
        try {
            Files.writeString(tmp, markdown,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            log.info("Wrote digest file: {}", target.toAbsolutePath());
            return target.toAbsolutePath();
        } catch (IOException e) {
            // Clean up the tmp file on failure so we don't litter the inbox dir.
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw e;
        }
    }
}