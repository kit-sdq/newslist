package edu.kit.kastel.sdq.newslist.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
