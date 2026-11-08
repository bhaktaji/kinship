package com.kinship.review;

import com.kinship.common.api.ApiException;
import com.kinship.common.security.UserPrincipal;
import com.kinship.review.ReviewService.ReviewRequest;
import com.kinship.review.ReviewService.ReviewSummaryView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class ReviewController {
    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @PostMapping("/events/{occurrenceId}/reviews")
    public ReviewService.ReviewView submit(@AuthenticationPrincipal UserPrincipal principal,
                                           @PathVariable UUID occurrenceId,
                                           @RequestBody ReviewRequest request) {
        return service.submit(require(principal).id(), occurrenceId, request);
    }

    @GetMapping("/groups/{groupId}/reviews")
    public ReviewSummaryView summary(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID groupId) {
        require(principal);
        return service.summary(groupId);
    }

    @PostMapping("/reviews/{reviewId}/report")
    public void report(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID reviewId,
                       @RequestBody Map<String, String> body) {
        service.report(require(principal).id(), reviewId, body.getOrDefault("reason", "inappropriate"));
    }

    private UserPrincipal require(UserPrincipal principal) {
        if (principal == null) throw ApiException.unauthorized("UNAUTHENTICATED", "Authentication required");
        return principal;
    }
}