package ru.example.reviews;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewServiceTest {
    private final ReviewParser parser = mock(ReviewParser.class);
    private final ReviewRepository repository = mock(ReviewRepository.class);
    private final FileStorage files = mock(FileStorage.class);
    private final ExecutorService executor = new AppConfig().parserExecutor();
    private final ReviewService service = new ReviewService(executor, parser, repository, files);
    private final Document document = Jsoup.parse("");

    @BeforeEach
    void prepareParser() {
        when(parser.load(anyString())).thenReturn(document);
        when(parser.pages(any(), anyString())).thenReturn(List.of());
        when(parser.parse(any(), anyString())).thenReturn(List.of(
                new Review("id", "source", "Автор", "Текст", 5, "2026-01-01")));
    }

    @AfterEach
    void stop() throws InterruptedException {
        service.stop();
        executor.shutdownNow();
    }

    @Test
    void keepsScheduledSourcesAfterHistoryCleanup() {
        String first = "https://example.org/first";
        String second = "https://example.org/second";
        collect(first);
        for (int i = 0; i < 100; i++) collect(second);

        service.refresh();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> verify(parser, times(2)).load(first));
    }

    @Test
    void manualRequestsAndScheduleShareOneActiveJob() throws Exception {
        String url = "https://example.org/product";
        collect(url);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(parser.load(url)).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Истекло время ожидания");
            return document;
        });
        ExecutorService callers = Executors.newFixedThreadPool(12);
        CyclicBarrier barrier = new CyclicBarrier(12);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                boolean scheduled = i % 3 == 0;
                results.add(callers.submit(() -> {
                    barrier.await(3, TimeUnit.SECONDS);
                    if (scheduled) {
                        service.refresh();
                        return null;
                    }
                    return service.start(url).id();
                }));
            }
            Set<String> ids = new HashSet<>();
            for (Future<String> result : results) {
                String id = result.get(3, TimeUnit.SECONDS);
                if (id != null) ids.add(id);
            }
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(ids).hasSize(1);
            verify(parser, times(2)).load(url);
            release.countDown();
            awaitJob(ids.iterator().next(), "DONE");
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void limitsActiveJobsAndReleasesSlotsAfterFailure() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(parser.load(anyString())).thenAnswer(call -> {
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Истекло время ожидания");
            throw new IllegalStateException("Ошибка загрузки");
        });
        try {
            List<ReviewService.Job> jobs = new ArrayList<>();
            for (int i = 0; i < 8; i++) jobs.add(service.start("https://example.org/product/" + i));
            assertThat(service.start(jobs.get(0).url()).id()).isEqualTo(jobs.get(0).id());
            assertThatThrownBy(() -> service.start("https://example.org/ninth"))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
            release.countDown();
            for (ReviewService.Job job : jobs) awaitJob(job.id(), "FAILED");
            doReturn(document).when(parser).load(anyString());
            collect("https://example.org/after-failure");
        } finally {
            release.countDown();
        }
    }

    @Test
    void waitsForAllPagesBeforeReportingFailure() throws Exception {
        String source = "https://example.org/product";
        String good = source + "?page=2";
        String bad = source + "?page=3";
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(parser.pages(document, source)).thenReturn(List.of(bad, good));
        when(parser.load(bad)).thenThrow(new IllegalStateException("Ошибка страницы"));
        when(parser.load(good)).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Истекло время ожидания");
            return document;
        });
        try {
            ReviewService.Job job = service.start(source);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(service.job(job.id()).status()).isEqualTo("RUNNING");
            release.countDown();
            awaitJob(job.id(), "FAILED");
            assertThat(service.job(job.id()).count()).isEqualTo(2);
            verify(repository, times(2)).save(any());
            verify(files, times(2)).save(any());
        } finally {
            release.countDown();
        }
    }

    private void collect(String url) {
        awaitJob(service.start(url).id(), "DONE");
    }

    private void awaitJob(String id, String status) {
        await().pollInterval(Duration.ofMillis(5)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(service.job(id).status()).isEqualTo(status));
    }
}
