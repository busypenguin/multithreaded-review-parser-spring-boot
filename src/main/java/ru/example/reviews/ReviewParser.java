package ru.example.reviews;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class ReviewParser {
    private final RestTemplate http;
    private final ObjectMapper mapper;

    public ReviewParser(RestTemplate http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    public Document load(String url) {
        return Jsoup.parse(Objects.requireNonNull(http.getForObject(url, String.class)), url);
    }

    public List<String> pages(Document document, String source) {
        URI origin = URI.create(source);
        if (origin.getHost().equals("irecommend.ru") && document.selectFirst(".reviewBlock") != null) {
            return List.of();
        }
        return document.select("a[href]").stream()
                .filter(a -> a.hasAttr("data-review-page") || a.hasClass("reviewTextSnippet") || a.parents().stream()
                        .anyMatch(p -> p.hasClass("pagination") || p.hasClass("pager")))
                .map(a -> a.absUrl("href"))
                .filter(url -> {
                    try {
                        URI page = URI.create(url);
                        return Objects.equals(origin.getScheme(), page.getScheme())
                                && Objects.equals(origin.getHost(), page.getHost())
                                && origin.getPort() == page.getPort()
                                && (Objects.equals(origin.getPath(), page.getPath())
                                    || (origin.getHost().equals("irecommend.ru") && page.getPath().startsWith("/content/"))
                                    || (origin.getHost().equals("otzovik.com")
                                        && page.getPath().matches(java.util.regex.Pattern.quote(origin.getPath()) + "[0-9]+/")))
                                && !source.equals(url);
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                }).distinct().limit(9).toList();
    }

    public List<Review> parse(Document document, String source) {
        Map<String, Review> reviews = new LinkedHashMap<>();
        for (Element script : document.select("script[type=application/ld+json]")) {
            try {
                collect(mapper.readTree(script.data()), source, reviews);
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // Поврежденный блок не мешает разбирать остальные отзывы.
            }
        }
        if (reviews.isEmpty()) {
            for (Element item : document.select(".review-item, [itemprop=review]")) {
                String text = item.select("[itemprop=reviewBody], .review-body, .review-teaser").text();
                String author = item.select(".user-login").text();
                if (author.isBlank()) author = item.select("[itemprop=author] [itemprop=name]").text();
                String date = item.select("[itemprop=datePublished]").attr("content");
                if (date.length() >= 10) date = date.substring(0, 10);
                if (date.isBlank()) date = item.select(".review-postdate").text();
                String value = item.select("[itemprop=ratingValue]").attr("content");
                double rating = item.select(".rating-score .star-1").size();
                if (!value.isBlank()) {
                    try {
                        rating = Double.parseDouble(value);
                    } catch (NumberFormatException ignored) {
                        // Неподходящий рейтинг будет отсеян при добавлении.
                    }
                }
                Element link = item.selectFirst(".review-read-link[href]");
                if (link != null && URI.create(source).getHost().equals("otzovik.com")) {
                    String detailUrl = link.absUrl("href");
                    if (Objects.equals(URI.create(source).getHost(), URI.create(detailUrl).getHost())) {
                        try {
                            String fullText = load(detailUrl).select(".review-body.description").text();
                            if (!fullText.isBlank()) text = fullText;
                        } catch (org.springframework.web.client.RestClientException e) {
                            // Если полный отзыв недоступен, сохраняем текст из карточки.
                            org.slf4j.LoggerFactory.getLogger(ReviewParser.class)
                                    .warn("Полный отзыв недоступен для {}. Сохраняем текст карточки", detailUrl);
                        }
                    }
                }
                add(source, author, text, rating, date, reviews);
            }
        }
        if (URI.create(source).getHost().equals("www.litres.ru")) {
            for (Element item : document.select("[data-testid=review__wrapper]")) {
                String author = item.attr("aria-label").replaceFirst("^Отзыв от пользователя\\s*", "");
                String text = item.select("[data-testid=review__text]").text();
                String date = item.select("time[datetime]").attr("datetime");
                if (date.length() >= 10) date = date.substring(0, 10);
                int rating = item.select("[data-testid^=bookRating__stars--] path[class*=svgPathActive]").size();
                add(source, author, text, rating, date, reviews);
            }
        }
        return new ArrayList<>(reviews.values());
    }

    private void collect(JsonNode node, String source, Map<String, Review> reviews) {
        if (node.isObject()) {
            if (node.has("reviewBody")) {
                JsonNode author = node.path("author");
                add(source, author.isTextual() ? author.asText() : author.path("name").asText(),
                        node.path("reviewBody").asText(),
                        node.path("reviewRating").path("ratingValue").asDouble(),
                        node.path("datePublished").asText(), reviews);
            }
            node.elements().forEachRemaining(child -> collect(child, source, reviews));
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, source, reviews));
        }
    }

    private void add(String source, String author, String text, double rating, String date,
                     Map<String, Review> reviews) {
        if (text.isBlank() || author.isBlank() || date.isBlank() || rating < 1 || rating > 5) {
            return;
        }
        try {
            String key = source + "\n" + author + "\n" + date + "\n" + text;
            String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
            reviews.put(id, new Review(id, source, author, text, rating, date));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
