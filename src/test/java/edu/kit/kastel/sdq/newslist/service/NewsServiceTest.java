package edu.kit.kastel.sdq.newslist.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class NewsServiceTest {

    private NewsService service(Path dir) {
        NewsService s = new NewsService();
        ReflectionTestUtils.setField(s, "csvPath", dir.toString());
        ReflectionTestUtils.setField(s, "csvFormat", "Default");
        s.reload();
        return s;
    }

    @Test
    void deleteRejectsPathTraversal(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectory(tmp.resolve("root"));
        Path outside = Files.writeString(tmp.resolve("x.csv"), "1,a\n");
        NewsService s = service(root);
        assertThatThrownBy(() -> s.delete("../x.csv")).isInstanceOf(IllegalArgumentException.class);
        assertThat(outside).exists();
    }

    @Test
    void storeSanitisesNameAndRejectsNonCsv(@TempDir Path tmp) throws Exception {
        NewsService s = service(tmp);
        assertThatThrownBy(() -> s.store("a.txt", "1,a".getBytes())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.store(".csv", "1,a".getBytes())).isInstanceOf(IllegalArgumentException.class);
        UploadResult r = s.store("../../evil.csv", "1,a\n1,b,c\n".getBytes());
        assertThat(r.filename()).isEqualTo("evil.csv");
        assertThat(r.records()).isEqualTo(1);
        assertThat(r.skippedRows()).isEqualTo(1);
        assertThat(tmp.resolve("evil.csv")).exists();
        assertThat(s.getNewsForKey("1")).hasSize(1);
    }

    @Test
    void deleteRejectsFilesBehindSymlinkedDirectory(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectory(tmp.resolve("root"));
        Path outsideDir = Files.createDirectory(tmp.resolve("outside"));
        Path outside = Files.writeString(outsideDir.resolve("x.csv"), "1,a\n");
        Files.createSymbolicLink(root.resolve("linked"), outsideDir);
        NewsService s = service(root);
        assertThatThrownBy(() -> s.delete("linked/x.csv")).isInstanceOf(IllegalArgumentException.class);
        assertThat(outside).exists();
    }

    @Test
    void storeRejectsInvalidUtf8AndKeepsExistingFile(@TempDir Path tmp) throws Exception {
        Path existing = Files.writeString(tmp.resolve("news.csv"), "1,café\n");
        NewsService s = service(tmp);
        byte[] windows1252 = "1,caf\u00e9\n".getBytes(java.nio.charset.Charset.forName("windows-1252"));
        assertThatThrownBy(() -> s.store("news.csv", windows1252))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTF-8");
        assertThat(Files.readString(existing)).isEqualTo("1,café\n");
        assertThat(s.getNewsForKey("1")).hasSize(1);
    }

    @Test
    void reloadReportsFailureAndKeepsPreviousNews(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("news.csv"), "1,a\n");
        Path unreadable = Files.createDirectory(tmp.resolve("unreadable"));
        NewsService s = service(tmp);
        assertThat(s.getNewsForKey("1")).hasSize(1);

        Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(unreadable), "permissions not enforced (e.g. running as root)");
            Files.writeString(tmp.resolve("other.csv"), "1,b\n");
            assertThat(s.reload()).isFalse();
            assertThat(s.getNewsForKey("1")).hasSize(1);
            // Upload and delete still change the files, but report that the index was not refreshed
            assertThat(s.store("third.csv", "1,c\n".getBytes()).reloaded()).isFalse();
            assertThat(s.delete("third.csv")).isFalse();
            assertThat(s.getNewsForKey("1")).hasSize(1);
        } finally {
            Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rwx------"));
        }
        assertThat(s.reload()).isTrue();
        assertThat(s.getNewsForKey("1")).hasSize(2);
    }

    @Test
    void reloadFailsAndKeepsNewsWhenRootIsMissing(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectory(tmp.resolve("root"));
        Files.writeString(root.resolve("news.csv"), "1,a\n");
        NewsService s = service(root);
        assertThat(s.getNewsForKey("1")).hasSize(1);

        Files.move(root, tmp.resolve("moved"));
        assertThat(s.reload()).isFalse();
        assertThat(s.getNewsForKey("1")).hasSize(1);
        assertThat(s.listFiles()).isEmpty();

        // An existing but empty directory intentionally clears the news
        Files.createDirectory(root);
        assertThat(s.reload()).isTrue();
        assertThat(s.getNewsForKey("1")).isEmpty();
    }
}
