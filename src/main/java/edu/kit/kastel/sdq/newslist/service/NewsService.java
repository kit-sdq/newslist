package edu.kit.kastel.sdq.newslist.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Service class parsing the CSV files and fetching news for a SAML2 user.
 */
@Service
public class NewsService implements InitializingBean {

    @Value("${news.csv-path}")
    private String csvPath;

    @Value("${news.csv-format}")
    private String csvFormat;

    private volatile Map<String, List<NewsItem>> news = Map.of();
    private final Log logger = LogFactory.getLog(this.getClass());

    private record ParseCounts(int records, int skipped) {
    }

    private Path root() {
        return Path.of(csvPath).toAbsolutePath().normalize();
    }

    private List<Path> findCsvFiles() throws IOException {
        Path root = root();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .sorted()
                    .toList();
        } catch (UncheckedIOException e) {
            // Files.walk reports errors during iteration (e.g. an unreadable subdirectory) unchecked
            throw e.getCause();
        }
    }

    private static String titleOf(String fileName) {
        return fileName.substring(0, fileName.length() - 4).replace("_", "");
    }

    /**
     * Parses all records of the reader into the map.
     */
    private ParseCounts parse(Reader reader, String title, Map<String, List<NewsItem>> target) throws IOException {
        CSVFormat format = CSVFormat.valueOf(csvFormat);
        int records = 0;
        int skipped = 0;
        try (reader) {
            for (CSVRecord csvRecord : format.parse(reader)) {
                if (csvRecord.size() != 2) {
                    logger.warn("Expected exactly two columns, but record " + csvRecord + " had " + csvRecord.size() + ". Skipping!");
                    skipped++;
                    continue;
                }
                target.computeIfAbsent(csvRecord.get(0), k -> new LinkedList<>())
                        .add(new NewsItem(title, csvRecord.get(1)));
                records++;
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return new ParseCounts(records, skipped);
    }

    private ParseCounts parseFile(Path file, Map<String, List<NewsItem>> target) throws IOException {
        return parse(Files.newBufferedReader(file, StandardCharsets.UTF_8), titleOf(file.getFileName().toString()), target);
    }

    /**
     * Parses a file into the map, logging (instead of throwing) read errors.
     *
     * @return the number of valid records, or 0 if the file could not be read
     */
    private int parseFileOrWarn(Path file, Map<String, List<NewsItem>> target) {
        try {
            return parseFile(file, target).records();
        } catch (IOException e) {
            logger.warn("File " + file + " could not be read.", e);
            return 0;
        }
    }

    /**
     * Re-parses all CSV files and atomically swaps the in-memory index.
     *
     * @return {@code true} if the index was rebuilt, {@code false} if the CSV directory could not be read
     * (the previous index is kept in that case)
     */
    public synchronized boolean reload() {
        logger.debug("Parsing news items from CSVs");
        logger.debug("CSV path is " + csvPath + " , CSV format is " + csvFormat);
        Map<String, List<NewsItem>> fresh = new HashMap<>();
        try {
            for (Path file : findCsvFiles()) {
                logger.debug("Parsing " + file);
                parseFileOrWarn(file, fresh);
            }
        } catch (IOException e) {
            logger.warn("CSVs could not be parsed.", e);
            return false;
        }
        this.news = fresh;
        return true;
    }

    /**
     * Lists all CSV files below the csv path.
     */
    public List<CsvFileInfo> listFiles() {
        List<CsvFileInfo> result = new ArrayList<>();
        Path root = root();
        try {
            for (Path file : findCsvFiles()) {
                int records = parseFileOrWarn(file, new HashMap<>());
                result.add(new CsvFileInfo(root.relativize(file).toString().replace('\\', '/'),
                        titleOf(file.getFileName().toString()), Files.size(file),
                        Files.getLastModifiedTime(file).toInstant(), records));
            }
        } catch (IOException e) {
            logger.warn("CSVs could not be listed.", e);
        }
        return result;
    }

    /**
     * Validates and stores an uploaded CSV file in the root of the csv path (replacing a file of the same name).
     *
     * @throws IllegalArgumentException if the upload is invalid
     */
    public synchronized UploadResult store(String originalFilename, byte[] content) throws IOException {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("No file name given.");
        }
        // Normalise Windows separators too, then reduce to the base name
        String name;
        try {
            Path fileName = Path.of(originalFilename.replace('\\', '/')).getFileName();
            name = fileName == null ? "" : fileName.toString();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid file name.");
        }
        if (name.isBlank() || name.startsWith(".") || !name.endsWith(".csv") || name.length() <= 4) {
            throw new IllegalArgumentException("Only .csv files are accepted.");
        }

        ParseCounts counts;
        try {
            // Strict decoder: reject invalid UTF-8 like the reload does, instead of silently replacing it
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            counts = parse(new InputStreamReader(new java.io.ByteArrayInputStream(content), decoder),
                    titleOf(name), new HashMap<>());
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("The file is not valid UTF-8. Please save it with UTF-8 encoding.");
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("The file is not a valid CSV: " + e.getMessage());
        }

        Path root = root();
        Files.createDirectories(root);
        Path target = root.resolve(name).normalize();
        if (!target.getParent().equals(root)) {
            throw new IllegalArgumentException("Invalid file name.");
        }
        Path temp = Files.createTempFile(root, ".upload-", ".tmp");
        try {
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        reload();
        return new UploadResult(name, counts.records(), counts.skipped());
    }

    /**
     * Deletes a CSV file below the csv path.
     *
     * @param relativePath path relative to the csv root
     * @throws IllegalArgumentException if the path is not a CSV file below the root
     */
    public synchronized void delete(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("No file given.");
        }
        Path root = root();
        Path target;
        try {
            target = root.resolve(relativePath).normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid path.");
        }
        if (!target.startsWith(root) || target.equals(root)
                || !target.getFileName().toString().endsWith(".csv")
                || !Files.isRegularFile(target)
                // The lexical check above does not see symlinked directories: the real parent must be inside the
                // real root. (A final symlink is fine, Files.delete removes the link itself.)
                || !target.getParent().toRealPath().startsWith(root.toRealPath())) {
            throw new IllegalArgumentException("Not a CSV file: " + relativePath);
        }
        Files.delete(target);
        reload();
    }

    /**
     * Returns a sorted list of news items for this key.
     * @param key the key
     * @return a sorted list of news items for this key.
     */
    public List<NewsItem> getNewsForKey(final String key) {
        return news.getOrDefault(key, new ArrayList<>());
    }

    @Override
    public void afterPropertiesSet() {
        this.reload();
    }

}
