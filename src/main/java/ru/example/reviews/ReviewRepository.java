package ru.example.reviews;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewRepository {
    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Review review) {
        jdbc.update("MERGE INTO reviews KEY(id) VALUES (?, ?, ?, ?, ?, ?)",
                review.id(), review.source(), review.author(), review.text(), review.rating(), review.date());
    }

    public List<Review> findAll() {
        return jdbc.query("SELECT * FROM reviews", (row, index) -> new Review(
                row.getString("id"), row.getString("source"), row.getString("author"),
                row.getString("review_text"), row.getDouble("rating"), row.getString("review_date")));
    }
}
