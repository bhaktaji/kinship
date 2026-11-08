package com.kinship.event;

import com.kinship.common.api.ApiException;
import com.kinship.common.security.UserPrincipal;
import com.kinship.event.dto.EventDtos.CreateSeriesRequest;
import com.kinship.event.dto.EventDtos.CreateSeriesResponse;
import com.kinship.event.dto.EventDtos.EventList;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/groups/{groupId}/events")
@Validated
public class GroupEventController {

    private final EventService eventService;

    public GroupEventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    public EventList list(@AuthenticationPrincipal UserPrincipal principal,
                          @PathVariable UUID groupId,
                          @RequestParam(required = false)
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                          @RequestParam(required = false)
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                          @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return new EventList(eventService.listEvents(require(principal).id(), groupId, from, to, limit));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateSeriesResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                       @PathVariable UUID groupId,
                                       @Valid @RequestBody CreateSeriesRequest request) {
        return eventService.createSeries(require(principal).id(), groupId, request);
    }

    private UserPrincipal require(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("UNAUTHENTICATED", "Authentication required");
        }
        return principal;
    }
}
