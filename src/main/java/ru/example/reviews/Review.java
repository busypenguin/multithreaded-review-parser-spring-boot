package ru.example.reviews;

public record Review(String id, String source, String author, String text,
                     double rating, String date) {
}
