package edu.kit.kastel.sdq.newslist.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Information about a CSV file below the configured csv path.
 *
 * @param path         path relative to the csv root (with '/' separators)
 * @param title        the title shown for the news items of this file
 * @param size         file size in bytes
 * @param lastModified last modification time
 * @param records      number of valid records
 */
public record CsvFileInfo(String path, String title, long size, Instant lastModified, int records) {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

    /** Human-readable size (B, KB, MB). */
    public String sizeDisplay() {
        return formatBytes(size);
    }

    /** Formats a byte count as B, KB or MB (e.g. {@code 10 MB}, {@code 1.5 KB}). */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        boolean kb = bytes < 1024 * 1024;
        double value = kb ? bytes / 1024.0 : bytes / (1024.0 * 1024.0);
        String number = String.format(Locale.ROOT, "%.1f", value);
        if (number.endsWith(".0")) {
            number = number.substring(0, number.length() - 2);
        }
        return number + (kb ? " KB" : " MB");
    }

    /** Human-readable modification time. */
    public String lastModifiedDisplay() {
        return lastModified == null ? "" : DATE_FORMAT.format(lastModified);
    }
}
