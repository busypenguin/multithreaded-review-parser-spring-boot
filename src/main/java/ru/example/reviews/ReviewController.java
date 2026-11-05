package ru.example.reviews;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReviewController {
    public record ParseRequest(@NotBlank String url) {}
    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @PostMapping("/parse")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReviewService.Job parse(@Valid @RequestBody ParseRequest request) {
        return service.start(request.url());
    }

    @GetMapping("/jobs/{id}")
    public ReviewService.Job job(@PathVariable String id) {
        return service.job(id);
    }

    @GetMapping("/answer")
    public List<Review> answer(@RequestParam(required = false) String source,
                              @RequestParam(defaultValue = "date") String sort,
                              @RequestParam(defaultValue = "desc") String direction) {
        return service.answers(source, sort, direction);
    }
}
