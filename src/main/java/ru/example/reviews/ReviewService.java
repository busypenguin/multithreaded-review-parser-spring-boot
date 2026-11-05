package ru.example.reviews;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ReviewService {
    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    public record Job(String id, String url, String status, int count, String error) {}
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private final ExecutorService executor;
    private final ReviewParser parser;
    private final ReviewRepository repository;

    public ReviewService(ExecutorService executor, ReviewParser parser, ReviewRepository repository) {
        this.executor = executor;
        this.parser = parser;
        this.repository = repository;
    }

    public Job start(String url) {
        validate(url);
        if (active.incrementAndGet() > 8) {
            active.decrementAndGet();
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Уже выполняется восемь заданий");
        }
        if (jobs.size() >= 100) {
            jobs.entrySet().removeIf(entry -> !entry.getValue().status().equals("RUNNING"));
        }
        String id = UUID.randomUUID().toString();
        Job job = new Job(id, url, "RUNNING", 0, "");
        jobs.put(id, job);
        log.info("Запуск задания {} для {}", id, url);
        try {
            executor.execute(() -> collect(job));
        } catch (RejectedExecutionException e) {
            finish(job, 0, "Очередь заполнена");
        }
        return job;
    }

    private void collect(Job job) {
        AtomicInteger count = new AtomicInteger();
        try {
            Document first = parser.load(job.url());
            save(first, job, count);
            List<CompletableFuture<Void>> tasks = new ArrayList<>();
            for (String page : parser.pages(first, job.url())) {
                tasks.add(CompletableFuture.runAsync(() -> save(parser.load(page), job, count), executor));
            }
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new))
                    .whenComplete((result, error) -> finish(job, count.get(),
                            error == null ? "" : "Не удалось загрузить одну из страниц. Доступные отзывы сохранены"));
        } catch (org.springframework.web.client.RestClientResponseException e) {
            log.warn("Сайт {} вернул HTTP {}", job.url(), e.getStatusCode());
            String error = e.getResponseBodyAsString().contains("captcha")
                    ? "Сайт требует капчу. Автоматический сбор недоступен"
                    : "Сайт вернул HTTP " + e.getStatusCode().value();
            finish(job, count.get(), error);
        } catch (Exception e) {
            log.warn("Ошибка сбора для {}", job.url(), e);
            finish(job, count.get(), "Не удалось прочитать страницу. Проверьте ссылку и доступность сайта");
        }
    }

    private void save(Document document, Job job, AtomicInteger count) {
        for (Review review : parser.parse(document, job.url())) {
            repository.save(review);
            int current = count.incrementAndGet();
            jobs.computeIfPresent(job.id(), (id, old) -> new Job(id, job.url(), "RUNNING", Math.max(current, old.count()), ""));
        }
    }

    private void finish(Job job, int count, String error) {
        if (error.isEmpty() && count == 0) {
            error = "Отзывы не найдены. Страница может требовать JavaScript или иметь другую разметку";
        }
        jobs.put(job.id(), new Job(job.id(), job.url(), error.isEmpty() ? "DONE" : "FAILED", count, error));
        log.info("Задание {} завершено. Обработано {}. {}", job.id(), count, error);
        active.decrementAndGet();
    }

    public Job job(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Задание не найдено");
        }
        return job;
    }

    public List<Review> answers(String source, String sort, String direction) {
        Comparator<Review> comparator = switch (sort) {
            case "rating" -> Comparator.comparingDouble(Review::rating);
            case "author" -> Comparator.comparing(Review::author);
            case "date" -> Comparator.comparing(Review::date);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Сортировка rating, author или date");
        };
        if (!direction.equals("asc") && !direction.equals("desc")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Направление asc или desc");
        }
        if (direction.equals("desc")) comparator = comparator.reversed();
        return repository.findAll().parallelStream()
                .filter(review -> source == null || review.source().equals(source))
                .sorted(comparator.thenComparing(Review::id)).toList();
    }

    private void validate(String url) {
        try {
            URI uri = URI.create(url);
            if (url.length() > 2048 || uri.getHost() == null || uri.getUserInfo() != null
                    || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Нужна полная ссылка HTTP или HTTPS");
        }
    }

    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public void refresh() {
        // Повторяем сбор только после завершения предыдущего задания по ссылке.
        Set<String> sources = new HashSet<>();
        jobs.values().stream().filter(job -> job.status().equals("DONE"))
                .forEach(job -> sources.add(job.url()));
        for (String source : sources) {
            if (active.get() < 8 && jobs.values().stream().noneMatch(job ->
                    job.url().equals(source) && job.status().equals("RUNNING"))) {
                start(source);
            }
        }
    }
}
