package ru.example.reviews;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileStorage implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(FileStorage.class);
    private final ObjectMapper mapper;
    private final Path reviewsFile;
    private final Path logFile;
    private final BlockingQueue<String> messages = new ArrayBlockingQueue<>(256);
    private final Thread logger;
    private boolean closed;

    public FileStorage(ObjectMapper mapper,
                       @Value("${reviews.files-directory:./data}") String directory) throws IOException {
        this.mapper = mapper;
        Path folder = Path.of(directory);
        Files.createDirectories(folder);
        reviewsFile = folder.resolve("reviews.jsonl");
        logFile = folder.resolve("jobs.log");
        logger = new Thread(this, "review-logger");
        logger.setDaemon(true);
        logger.start();
    }

    // Несколько потоков записывают отзывы в один файл без перемешивания строк.
    public synchronized void save(Review review) throws IOException {
        Files.writeString(reviewsFile, mapper.writeValueAsString(review) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized void log(String message) {
        if (closed) return;
        try {
            messages.put(Instant.now() + " " + message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        writeLog("Поток журнала " + Thread.currentThread().getName() + ". daemon="
                + Thread.currentThread().isDaemon());
        try {
            while (true) {
                String message = messages.take();
                if (message.isEmpty()) return;
                writeLog(message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeLog(String message) {
        try {
            Files.writeString(logFile, message + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Не удалось записать журнал заданий", e);
        }
    }

    @PreDestroy
    public synchronized void close() throws InterruptedException {
        if (closed) return;
        closed = true;
        messages.put("");
        logger.join();
    }
}
