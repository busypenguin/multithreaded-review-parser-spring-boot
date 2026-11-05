package ru.example.reviews;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewParserTest {
    @Test
    void readsLitresRatingAndSkipsUnratedComments() {
        var document = Jsoup.parse("""
                <div data-testid="review__wrapper" aria-label="Отзыв от пользователя Анна">
                    <time datetime="2026-10-07T14:53:57"></time>
                    <div data-testid="review__text">Текст отзыва</div>
                    <div data-testid="bookRating__stars--1"><svg><path class="uik-svgPathActive-example"></path></svg></div>
                    <div data-testid="bookRating__stars--2"><svg><path class="uik-svgPathActive-example"></path></svg></div>
                    <div data-testid="bookRating__stars--3"><svg><path class="uik-svgPath-example"></path></svg></div>
                </div>
                <div data-testid="review__wrapper" aria-label="Отзыв от пользователя Борис">
                    <time datetime="2026-10-08"></time>
                    <div data-testid="review__text">Без оценки</div>
                </div>
                """);
        var parser = new ReviewParser(new RestTemplate(), new ObjectMapper());
        var reviews = parser.parse(document, "https://www.litres.ru/book/example/otzivi/");
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).author()).isEqualTo("Анна");
        assertThat(reviews.get(0).rating()).isEqualTo(2);
        assertThat(reviews.get(0).date()).isEqualTo("2026-10-07");
    }

    @Test
    void readsIRecommendReviewAndProductLinks() {
        String source = "https://irecommend.ru/content/product";
        var document = Jsoup.parse("""
                <div itemprop="review">
                    <div itemprop="author"><span itemprop="name">Автор</span></div>
                    <meta itemprop="ratingValue" content="5">
                    <meta itemprop="datePublished" content="2026-10-07T14:53:57+03:00">
                    <div itemprop="reviewBody">Полный текст отзыва</div>
                </div>
                <a class="reviewTextSnippet" href="/content/review-one">Отзыв</a>
                <a class="reviewTextSnippet" href="https://example.org/content/other">Чужой сайт</a>
                """, source);
        var parser = new ReviewParser(new RestTemplate(), new ObjectMapper());
        var reviews = parser.parse(document, source);
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).text()).isEqualTo("Полный текст отзыва");
        assertThat(reviews.get(0).author()).isEqualTo("Автор");
        assertThat(parser.pages(document, source)).containsExactly("https://irecommend.ru/content/review-one");
    }

    @Test
    void readsOtzovikMicrodataAndPagination() {
        String source = "https://otzovik.com/reviews/example/";
        var document = Jsoup.parse("""
                <div class="item status4" itemprop="review">
                    <a class="user-login">Автор</a>
                    <meta itemprop="ratingValue" content="4">
                    <div itemprop="datePublished" content="2026-10-07T14:53:57+03:00">7 окт 2026</div>
                    <div class="review-teaser">Текст отзыва</div>
                </div>
                <div class="pager">
                    <a href="/reviews/example/2/">2</a>
                    <a href="/reviews/example/2/">Далее</a>
                    <a href="/reviews/another/">Другой товар</a>
                </div>
                """, source);
        var parser = new ReviewParser(new RestTemplate(), new ObjectMapper());
        var reviews = parser.parse(document, source);
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).author()).isEqualTo("Автор");
        assertThat(reviews.get(0).rating()).isEqualTo(4);
        assertThat(reviews.get(0).date()).isEqualTo("2026-10-07");
        assertThat(parser.pages(document, source)).containsExactly(source + "2/");
    }
}
