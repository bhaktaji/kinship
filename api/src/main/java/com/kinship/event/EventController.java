package com.kinship.event;

import com.kinship.common.api.ApiException;
import com.kinship.common.ratelimit.RateLimitService;
import com.kinship.common.security.UserPrincipal;
import com.kinship.event.dto.EventDtos.CancelSeriesResponse;
import com.kinship.event.dto.EventDtos.EventDetailResponse;
import com.kinship.event.dto.EventDtos.RsvpRequest;
import com.kinship.event.dto.EventDtos.RsvpResponse;
import com.kinship.event.dto.EventDtos.UpdateSeriesRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

@RestController
public class EventController {

    private final EventService eventService;
    private final RateLimitService rateLimit;

    public EventController(EventService eventService, RateLimitService rateLimit) {
        this.eventService = eventService;
        this.rateLimit = rateLimit;
    }

    @GetMapping("/v1/events/{id}")
    public EventDetailResponse detail(@AuthenticationPrincipal UserPrincipal principal,
                                      @PathVariable UUID id) {
        return eventService.detail(require(principal).id(), id);
    }

    @PostMapping("/v1/events/{id}/rsvp")
    public RsvpResponse rsvp(@AuthenticationPrincipal UserPrincipal principal,
                             @PathVariable UUID id,
                             @Valid @RequestBody RsvpRequest request) {
        UserPrincipal user = require(principal);
        rateLimit.check("rsvp:" + user.id(), 20, Duration.ofMinutes(1));
        return eventService.rsvp(user.id(), id, request);
    }

    @PatchMapping("/v1/event-series/{id}")
    public CancelSeriesResponse cancel(@AuthenticationPrincipal UserPrincipal principal,
                                       @PathVariable UUID id,
                                       @Valid @RequestBody UpdateSeriesRequest request) {
        return eventService.cancelSeries(require(principal).id(), id, request);
    }

    private UserPrincipal require(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("UNAUTHENTICATED", "Authentication required");
        }
        return principal;
    }
}
