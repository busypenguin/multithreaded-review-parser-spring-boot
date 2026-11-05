package ru.example.reviews;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class ReviewsApplication {
    public static void main(String[] args) {
        System.setProperty("java.net.useSystemProxies", "true");
        SpringApplication.run(ReviewsApplication.class, args);
    }
}
