package ru.example.reviews;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class FileStorageTest {
    @TempDir Path directory;

    @Test
    void savesConcurrentReviewsAndFlushesDaemonLogOnClose() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FileStorage files = new FileStorage(mapper, directory.toString());
        ExecutorService writers = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String id = Integer.toString(i);
                tasks.add(() -> {
                    files.save(new Review(id, "source", "Автор", "Текст\nотзыва", 5, "2026-01-01"));
                    files.log("Сохранен отзыв " + id);
                    return null;
                });
            }
            for (Future<Void> result : writers.invokeAll(tasks)) result.get();
        } finally {
            writers.shutdownNow();
            files.close();
        }
        List<String> lines = Files.readAllLines(directory.resolve("reviews.jsonl"));
        assertThat(lines).hasSize(40);
        Set<String> ids = new HashSet<>();
        for (String line : lines) {
            Review review = mapper.readValue(line, Review.class);
            assertThat(review.text()).isEqualTo("Текст\nотзыва");
            ids.add(review.id());
        }
        assertThat(ids).hasSize(40);
        String journal = Files.readString(directory.resolve("jobs.log"));
        assertThat(journal).contains("daemon=true");
        for (int i = 0; i < 40; i++) assertThat(journal).contains("Сохранен отзыв " + i + System.lineSeparator());
    }
}
