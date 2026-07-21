package com.axis.digest.store;

import com.axis.digest.config.DigestProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Reads previously-written daily digest markdown files.
 * Pairs with {@link DigestInboxWriter} — both point at the same inbox directory.
 */
@Component
public class DigestFileReader {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Path inboxDir;

    public DigestFileReader(DigestProperties props) {
        this.inboxDir = Paths.get(props.inboxDir());
    }

    /** Returns today's markdown if the file exists. */
    public Optional<String> readDailyFile(LocalDate date) {
        Path file = inboxDir.resolve("daily-" + DATE_FMT.format(date) + ".md");
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(Files.readString(file));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Lists the most recent N daily digests available on disk, newest first.
     * Days with no file are simply skipped — the caller decides how to render gaps.
     */
    public List<DailyDigestEntry> listRecent(int maxDays) {
        if (!Files.exists(inboxDir) || maxDays <= 0) return List.of();

        List<DailyDigestEntry> entries = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < maxDays; i++) {
            LocalDate d = today.minusDays(i);
            Optional<String> content = readDailyFile(d);
            content.ifPresent(c -> entries.add(new DailyDigestEntry(d, c)));
        }
        return Collections.unmodifiableList(entries);
    }

    public record DailyDigestEntry(LocalDate date, String content) {}
}