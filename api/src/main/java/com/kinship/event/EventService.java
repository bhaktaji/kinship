package com.kinship.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kinship.common.api.ApiException;
import com.kinship.common.ops.KinshipMetrics;
import com.kinship.event.dto.EventDtos;
import com.kinship.event.dto.EventDtos.CancelSeriesResponse;
import com.kinship.event.dto.EventDtos.CreateSeriesRequest;
import com.kinship.event.dto.EventDtos.CreateSeriesResponse;
import com.kinship.event.dto.EventDtos.EventDetailResponse;
import com.kinship.event.dto.EventDtos.EventSummary;
import com.kinship.event.dto.EventDtos.RsvpRequest;
import com.kinship.event.dto.EventDtos.RsvpResponse;
import com.kinship.event.dto.EventDtos.UpdateSeriesRequest;
import com.kinship.group.GroupRepository;
import com.kinship.group.GroupService;
import com.kinship.notification.NotificationService;
import com.kinship.user.UserRecord;
import com.kinship.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EventService {

    private final EventRepository events;
    private final OccurrenceGenerator generator;
    private final GroupRepository groups;
    private final GroupService groupService;
    private final UserRepository users;
    private final ObjectMapper objectMapper;
    private final KinshipMetrics metrics;
    private final NotificationService notifications;

    public EventService(EventRepository events, OccurrenceGenerator generator, GroupRepository groups,
                        GroupService groupService, UserRepository users, ObjectMapper objectMapper,
                        KinshipMetrics metrics, NotificationService notifications) {
        this.events = events;
        this.generator = generator;
        this.groups = groups;
        this.groupService = groupService;
        this.users = users;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.notifications = notifications;
    }

    @Transactional
    public CreateSeriesResponse createSeries(UUID userId, UUID groupId, CreateSeriesRequest request) {
        groupService.requireOwner(userId, groupId);
        GroupRepository.GroupRow group = groups.findGroup(groupId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Group not found"));
        String title = request.title() == null || request.title().isBlank() ? group.name() : request.title().trim();
        String timezone = request.timezone();
        if (timezone == null || timezone.isBlank()) {
            UserRecord user = users.findById(userId)
                    .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "User not found"));
            timezone = user.timezone();
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Invalid timezone");
        }
        var recurrence = request.recurrence();
        RecurrenceEngine.Freq freq;
        try {
            freq = RecurrenceEngine.Freq.valueOf(recurrence.freq().trim().toUpperCase());
        } catch (RuntimeException e) {
            throw ApiException.badRequest("UNSUPPORTED_RECURRENCE", "Unsupported frequency");
        }
        List<java.time.DayOfWeek> byDays = new ArrayList<>();
        if (recurrence.byday() != null) {
            recurrence.byday().forEach(day -> byDays.add(RecurrenceEngine.parseDay(day)));
        }
        RecurrenceEngine.Rule rule = new RecurrenceEngine.Rule(freq,
                recurrence.interval() == null ? 1 : recurrence.interval(), byDays,
                recurrence.startTime(), recurrence.startsOn(), recurrence.until());
        new RecurrenceEngine().validate(rule);
        LocalDate today = LocalDate.now(zone);
        if (recurrence.startsOn().isBefore(today) || recurrence.startsOn().isAfter(today.plusWeeks(8))) {
            throw ApiException.badRequest("VALIDATION_ERROR", "starts_on must be within the next 8 weeks");
        }
        String existingTitle = title;
        var existing = events.findSeriesByGroupTitleStart(groupId, existingTitle, recurrence.startsOn());
        if (existing.isPresent()) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "SERIES_EXISTS",
                    "A series with this title and start date already exists",
                    Map.of("existing_series_id", existing.get().id().toString()));
        }
        int duration = request.durationMin() == null ? 90 : request.durationMin();
        int capacity = request.capacity() == null ? group.maxMembers() : request.capacity();
        double lat = request.location() != null ? request.location().lat() : group.lat();
        double lng = request.location() != null ? request.location().lng() : group.lng();
        String ruleJson;
        try {
            Map<String, Object> ruleMap = new LinkedHashMap<>();
            ruleMap.put("freq", freq.name());
            ruleMap.put("interval", rule.interval());
            if (!byDays.isEmpty()) {
                ruleMap.put("byday", byDays.stream().map(Enum::name).toList());
            }
            ruleMap.put("start_time", recurrence.startTime().toString());
            ruleJson = objectMapper.writeValueAsString(ruleMap);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialise recurrence", e);
        }
        UUID seriesId = events.insertSeries(groupId, title, request.description(), duration, capacity,
                lat, lng, timezone, ruleJson, recurrence.startsOn(), recurrence.until(), userId);
        EventRepository.SeriesRow series = events.findSeries(seriesId)
                .orElseThrow(() -> new IllegalStateException("Inserted series not found"));
        generator.extendSeries(series, Instant.now());
        for (UUID memberId : groups.activeMemberIds(groupId)) {
            if (!memberId.equals(userId)) {
                notifications.notify(memberId, "NEW_EVENT", "New event", title, "GROUP", groupId);
            }
        }
        return new CreateSeriesResponse(seriesId, Instant.now().plus(OccurrenceGenerator.HORIZON),
                events.listOccurrences(seriesId));
    }

    @Transactional(readOnly = true)
    public List<EventSummary> listEvents(UUID userId, UUID groupId, Instant from, Instant to, int limit) {
        groupService.requireVisible(userId, groupId);
        var group = groups.findGroup(groupId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Group not found"));
        if ("private".equals(group.visibility())) {
            groupService.requireMember(userId, groupId);
        }
        Instant start = from == null ? Instant.now() : from;
        Instant end = to == null ? start.plus(Duration.ofDays(56)) : to;
        if (end.isBefore(start) || Duration.between(start, end).compareTo(Duration.ofDays(112)) > 0) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Invalid event time range");
        }
        return events.listEvents(groupId, start, end, Math.min(limit, 100), userId);
    }

    @Transactional(readOnly = true)
    public EventDetailResponse detail(UUID userId, UUID eventId) {
        EventDetailResponse event = events.findEvent(eventId, userId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Event not found"));
        if ("private".equals(event.group().visibility())) {
            groupService.requireMember(userId, event.group().groupId());
        }
        return event;
    }

    @Transactional
    public CancelSeriesResponse cancelSeries(UUID userId, UUID seriesId, UpdateSeriesRequest request) {
        EventRepository.SeriesOwner owner = events.seriesOwner(seriesId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Series not found"));
        groupService.requireOwner(userId, owner.groupId());
        if (!"ACTIVE".equals(owner.seriesStatus())) {
            return new CancelSeriesResponse(0);
        }
        int cancelled = events.cancelSeries(seriesId);
        for (UUID memberId : groups.activeMemberIds(owner.groupId())) {
            notifications.notify(memberId, "EVENT_CANCELLED", "Event cancelled", "A recurring event was cancelled",
                    "GROUP", owner.groupId());
        }
        return new CancelSeriesResponse(cancelled);
    }

    @Transactional
    public RsvpResponse rsvp(UUID userId, UUID eventId, RsvpRequest request) {
        EventRepository.EventLock event = events.lockEvent(eventId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Event not found"));
        groupService.requireMember(userId, event.groupId());
        var group = groups.findGroup(event.groupId()).orElseThrow(
                () -> ApiException.notFound("NOT_FOUND", "Group not found"));
        if (!"ACTIVE".equals(group.status())) {
            throw ApiException.conflict("GROUP_ARCHIVED", "Group is archived");
        }
        if (!"SCHEDULED".equals(event.status())) {
            throw ApiException.conflict("EVENT_CANCELLED", "Event is cancelled");
        }
        if (!event.startTime().isAfter(Instant.now())) {
            throw ApiException.conflict("EVENT_STARTED", "Event has already started");
        }
        String status = request.status().trim().toUpperCase();
        if (!List.of("GOING", "NOT_GOING", "MAYBE", "DECLINED").contains(status)) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Invalid RSVP status");
        }
        String old = events.rsvpStatus(eventId, userId).orElse(null);
        if (status.equals(old)) {
            return new RsvpResponse(eventId, status, event.confirmedCount(), event.capacity());
        }
        int delta = ("GOING".equals(status) ? 1 : 0) - ("GOING".equals(old) ? 1 : 0);
        if (delta > 0 && event.confirmedCount() + delta > event.capacity()) {
            metrics.rsvpConflict();
            throw ApiException.conflict("EVENT_FULL", "Event is full");
        }
        events.upsertRsvp(eventId, userId, status);
        events.updateConfirmedCount(eventId, delta);
        return new RsvpResponse(eventId, status, event.confirmedCount() + delta, event.capacity());
    }
}
