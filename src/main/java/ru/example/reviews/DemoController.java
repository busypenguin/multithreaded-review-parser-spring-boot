package ru.example.reviews;

import org.springframework.web.bind.annotation.*;

@RestController
public class DemoController {
    @GetMapping(value = "/demo/product", produces = "text/html;charset=UTF-8")
    public String product(@RequestParam(defaultValue = "1") int page) {
        if (page < 1 || page > 3) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND);
        }
        String[] authors = {"Анна", "Борис", "Вера"};
        String[] texts = {"Товар пришел вовремя. Все работает.", "Хороший товар, но упаковка помята.", "Пользуюсь неделю. Покупкой довольна."};
        return """
                <!doctype html><html lang="ru"><meta charset="UTF-8"><title>Демонстрационный товар</title>
                <h1>Демонстрационные отзывы</h1>
                <p>Это локальные учебные данные для проверки парсера.</p>
                <script type="application/ld+json">
                {"@context":"https://schema.org","@type":"Product","name":"Учебный товар",
                "review":{"@type":"Review","author":{"name":"%s"},"reviewBody":"%s",
                "reviewRating":{"ratingValue":%d},"datePublished":"2026-10-0%d"}}
                </script>
                <a data-review-page href="/demo/product?page=1">Первая</a>
                <a data-review-page href="/demo/product?page=2">Вторая</a>
                <a data-review-page href="/demo/product?page=3">Третья</a></html>
                """.formatted(authors[page - 1], texts[page - 1], 6 - page, page);
    }
}
