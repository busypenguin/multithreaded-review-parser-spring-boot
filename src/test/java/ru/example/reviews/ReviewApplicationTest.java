package ru.example.reviews;

import java.time.Duration;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
        "reviews.files-directory=./target/test-data",
        "reviews.refresh-ms=200"
})
class ReviewApplicationTest {
    @Autowired TestRestTemplate http;
    @LocalServerPort int port;

    @Test
    void collectsPagesWithoutDuplicatesAndSorts() {
        String url = "http://localhost:" + port + "/demo/product?page=1";
        collect(url);
        collect(url);
        Review[] reviews = http.getForObject("/answer?source={source}&sort=rating&direction=desc", Review[].class, url);
        assertThat(reviews).hasSize(3);
        assertThat(List.of(reviews).stream().map(Review::rating).toList()).containsExactly(5.0, 4.0, 3.0);
        assertThat(reviews[0].author()).isEqualTo("Анна");
        assertThat(reviews[0].text()).isEqualTo("Товар пришел вовремя. Все работает.");
        assertThat(reviews[0].date()).isEqualTo("2026-10-01");
        assertThat(http.getForObject("/answer?source=missing", Review[].class)).isEmpty();
        Review[] byAuthor = http.getForObject("/answer?source={source}&sort=author&direction=asc", Review[].class, url);
        assertThat(List.of(byAuthor).stream().map(Review::author).toList()).containsExactly("Анна", "Борис", "Вера");
    }

    @Test
    void scheduledRefreshRunsAutomaticallyAndWritesFiles() {
        String url = "http://localhost:" + port + "/demo/product?page=3";
        collect(url);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<String> journal = Files.readAllLines(Path.of("target/test-data/jobs.log"));
            long completed = journal.stream().filter(line -> line.contains(". DONE.") && line.contains(url)).count();
            assertThat(completed).isGreaterThanOrEqualTo(2);
            assertThat(String.join("\n", journal)).contains("daemon=true", "daemon=false");
            assertThat(Files.readString(Path.of("target/test-data/reviews.jsonl"))).contains("Анна", "Товар пришел вовремя");
        });
    }

    private void collect(String url) {
        var response = http.postForEntity("/parse", Map.of("url", url), ReviewService.Job.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String id = response.getBody().id();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var job = http.getForObject("/jobs/" + id, ReviewService.Job.class);
            assertThat(job.status()).isEqualTo("DONE");
            assertThat(job.count()).isEqualTo(3);
        });
    }

    @Test
    void rejectsInvalidRequests() {
        assertThat(http.postForEntity("/parse", Map.of("url", "wrong"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/parse", Map.of("url", ""), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/answer?sort=wrong", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/answer?direction=wrong", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/jobs/missing", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reportsPagesWithoutReviews() {
        var response = http.postForEntity("/parse", Map.of("url", "http://localhost:" + port + "/"), ReviewService.Job.class);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var job = http.getForObject("/jobs/" + response.getBody().id(), ReviewService.Job.class);
            assertThat(job.status()).isEqualTo("FAILED");
            assertThat(job.error()).isNotBlank();
        });
    }
}
