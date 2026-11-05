package ru.example.reviews;

import java.net.URI;
import java.io.IOException;
import jakarta.annotation.PreDestroy;
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
    private record Running(Job job, Thread thread) {}
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final Set<String> sources = ConcurrentHashMap.newKeySet();
    private final Map<String, Running> activeJobs = new HashMap<>();
    private final ExecutorService executor;
    private final ReviewParser parser;
    private final ReviewRepository repository;
    private final FileStorage files;
    private boolean stopping;

    public ReviewService(ExecutorService executor, ReviewParser parser, ReviewRepository repository,
                         FileStorage files) {
        this.executor = executor;
        this.parser = parser;
        this.repository = repository;
        this.files = files;
    }

    // Проверка и регистрация задания выполняются под одной блокировкой.
    public synchronized Job start(String url) {
        validate(url);
        if (stopping) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Приложение останавливается");
        }
        Running existing = activeJobs.get(url);
        if (existing != null) return jobs.get(existing.job().id());
        if (activeJobs.size() >= 8) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Уже выполняется восемь заданий");
        }
        if (jobs.size() >= 100) {
            jobs.entrySet().removeIf(entry -> !entry.getValue().status().equals("RUNNING"));
        }
        String id = UUID.randomUUID().toString();
        Job job = new Job(id, url, "RUNNING", 0, "");
        Runnable task = () -> collect(job);
        Thread thread = new Thread(task, "review-job-" + id);
        thread.setDaemon(false);
        activeJobs.put(url, new Running(job, thread));
        jobs.put(id, job);
        log.info("Запуск задания {} для {}", id, url);
        thread.start();
        return job;
    }

    private void collect(Job job) {
        AtomicInteger count = new AtomicInteger();
        String error = "";
        boolean firstLoaded = false;
        files.log("Начало задания " + job.id() + ". Поток " + Thread.currentThread().getName()
                + ". daemon=" + Thread.currentThread().isDaemon() + ". " + job.url());
        try {
            Document first = executor.submit(() -> {
                Document document = parser.load(job.url());
                save(document, job, count);
                return document;
            }).get();
            firstLoaded = true;
            List<Callable<Void>> tasks = new ArrayList<>();
            for (String page : parser.pages(first, job.url())) {
                tasks.add(() -> {
                    save(parser.load(page), job, count);
                    return null;
                });
            }
            // Обычный поток ожидает пул. Сами потоки пула друг друга не ожидают.
            for (Future<Void> result : executor.invokeAll(tasks)) result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Сбор остановлен";
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            log.warn("Ошибка сбора для {}. {}", job.url(), cause.getClass().getSimpleName());
            if (firstLoaded) {
                error = "Не удалось загрузить одну из страниц. Доступные отзывы сохранены";
            } else if (cause instanceof org.springframework.web.client.RestClientResponseException response) {
                error = response.getResponseBodyAsString().contains("captcha")
                        ? "Сайт требует капчу. Автоматический сбор недоступен"
                        : "Сайт вернул HTTP " + response.getStatusCode().value();
            } else {
                error = "Не удалось прочитать страницу. Проверьте ссылку и доступность сайта";
            }
        } finally {
            finish(job, count.get(), error);
        }
    }

    private void save(Document document, Job job, AtomicInteger count) throws IOException {
        for (Review review : parser.parse(document, job.url())) {
            repository.save(review);
            files.save(review);
            int current = count.incrementAndGet();
            jobs.computeIfPresent(job.id(), (id, old) -> new Job(id, job.url(), "RUNNING", Math.max(current, old.count()), ""));
        }
    }

    private synchronized void finish(Job job, int count, String error) {
        if (error.isEmpty() && count == 0) {
            error = "Отзывы не найдены. Страница может требовать JavaScript или иметь другую разметку";
        }
        jobs.put(job.id(), new Job(job.id(), job.url(), error.isEmpty() ? "DONE" : "FAILED", count, error));
        if (error.isEmpty()) sources.add(job.url());
        files.log("Задание " + job.id() + ". " + jobs.get(job.id()).status()
                + ". Обработано " + count + ". " + job.url() + ". " + error);
        log.info("Задание {} завершено. Обработано {}. {}", job.id(), count, error);
        activeJobs.remove(job.url());
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

    @Scheduled(initialDelayString = "${reviews.refresh-ms:60000}", fixedDelayString = "${reviews.refresh-ms:60000}")
    public void refresh() {
        for (String source : sources) {
            try {
                start(source);
            } catch (ResponseStatusException e) {
                if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS
                        || e.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) return;
                throw e;
            }
        }
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        List<Thread> threads;
        synchronized (this) {
            stopping = true;
            threads = activeJobs.values().stream().map(Running::thread).toList();
        }
        // Сначала завершаем задания, затем Spring закрывает пул, файлы и базу.
        for (Thread thread : threads) thread.join();
    }
}
