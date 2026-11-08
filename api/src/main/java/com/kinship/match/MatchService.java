package com.kinship.match;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kinship.activity.H3Service;
import com.kinship.common.api.ApiException;
import com.kinship.common.ops.KinshipMetrics;
import com.kinship.group.GroupRepository;
import com.kinship.match.dto.MatchDtos;
import com.kinship.match.dto.MatchDtos.AcceptResponse;
import com.kinship.match.dto.MatchDtos.DeclineResponse;
import com.kinship.match.dto.MatchDtos.InvitationList;
import com.kinship.match.dto.MatchDtos.InvitationResponse;
import com.kinship.match.dto.MatchDtos.MatchJobCreated;
import com.kinship.match.dto.MatchDtos.MatchJobResponse;
import com.kinship.match.dto.MatchDtos.MatchResult;
import com.kinship.match.dto.MatchDtos.MemberPreview;
import com.kinship.match.dto.MatchDtos.Preferences;
import com.kinship.user.InterestRecord;
import com.kinship.user.UserRecord;
import com.kinship.user.UserRepository;
import com.kinship.user.dto.AvailabilitySlot;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MatchService {

    private static final Logger log = LoggerFactory.getLogger(MatchService.class);
    private static final int MIN_GROUP_SIZE = 4;

    private final MatchRepository matches;
    private final UserRepository users;
    private final GroupRepository groups;
    private final H3Service h3;
    private final ObjectMapper objectMapper;
    private final KinshipMetrics metrics;

    public MatchService(MatchRepository matches, UserRepository users, GroupRepository groups,
                        H3Service h3, ObjectMapper objectMapper, KinshipMetrics metrics) {
        this.matches = matches;
        this.users = users;
        this.groups = groups;
        this.h3 = h3;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Transactional
    public MatchJobCreated request(UUID userId, MatchDtos.MatchRequest request) {
        UserRecord user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "User not found"));
        if (user.lat() == null || user.lng() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PROFILE_INCOMPLETE",
                    "Location is required for matching");
        }
        if (users.findUserInterests(userId).size() < 2) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PROFILE_INCOMPLETE",
                    "At least two interests are required for matching");
        }
        if (parseAvailability(user.availabilityJson()).isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PROFILE_INCOMPLETE",
                    "At least one availability slot is required for matching");
        }
        var existing = matches.findActiveJob(userId);
        if (existing.isPresent()) {
            return new MatchJobCreated(existing.get().id(), existing.get().status());
        }
        Preferences preferences = new Preferences(
                request.maxDistanceKm() == null ? 10 : request.maxDistanceKm(),
                request.preferredGroupSize() == null ? 4 : request.preferredGroupSize());
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "max_distance_km", preferences.maxDistanceKm(),
                    "preferred_group_size", preferences.preferredGroupSize()));
            UUID jobId = matches.insertJob(userId, json);
            return new MatchJobCreated(jobId, "QUEUED");
        } catch (DataIntegrityViolationException e) {
            var job = matches.findActiveJob(userId).orElseThrow();
            return new MatchJobCreated(job.id(), job.status());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create match job", e);
        }
    }

    @Transactional(readOnly = true)
    public MatchJobResponse job(UUID userId, UUID jobId) {
        MatchRepository.JobRow job = matches.findJob(jobId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Match job not found"));
        if (!job.userId().equals(userId)) {
            throw ApiException.notFound("NOT_FOUND", "Match job not found");
        }
        List<MatchResult> results = matches.matchGroupsForJob(jobId).stream()
                .map(group -> new MatchResult(group.id(), group.status(), group.score(),
                        group.expiresAt(), matches.memberPreviews(group.id(), userId)))
                .toList();
        return new MatchJobResponse(job.id(), job.status(), job.errorCode(), results);
    }

    @Transactional(readOnly = true)
    public InvitationList invitations(UUID userId, String status) {
        List<InvitationResponse> items = matches.invitationsForUser(userId, status).stream()
                .map(invitation -> new InvitationResponse(invitation.id(), invitation.matchGroupId(),
                        invitation.expiresAt(), invitation.status(),
                        matches.memberPreviews(invitation.matchGroupId(), userId)))
                .toList();
        return new InvitationList(items);
    }

    @Transactional
    public AcceptResponse accept(UUID userId, UUID invitationId) {
        MatchRepository.InvitationRow invitation = requireInvitation(userId, invitationId);
        if ("ACCEPTED".equals(invitation.status())) {
            return new AcceptResponse("ACCEPTED", invitation.groupId());
        }
        if (!"INVITED".equals(invitation.status())) {
            throw ApiException.conflict("INVITATION_EXPIRED", "Invitation is no longer pending");
        }
        if (!invitation.expiresAt().isAfter(Instant.now())) {
            matches.expireMatchGroup(invitation.matchGroupId());
            throw ApiException.conflict("INVITATION_EXPIRED", "Invitation has expired");
        }
        matches.updateInvitation(invitation.id(), "ACCEPTED");
        if (invitation.groupId() != null) {
            return new AcceptResponse("ACCEPTED", invitation.groupId());
        }
        int accepted = matches.countInvitations(invitation.matchGroupId(), "ACCEPTED");
        if (accepted < MIN_GROUP_SIZE || !matches.isAccepted(invitation.matchGroupId(), invitation.requesterId())) {
            return new AcceptResponse("ACCEPTED", null);
        }
        UUID groupId = createConfirmedGroup(invitation);
        return new AcceptResponse("ACCEPTED", groupId);
    }

    @Transactional
    public DeclineResponse decline(UUID userId, UUID invitationId) {
        MatchRepository.InvitationRow invitation = requireInvitation(userId, invitationId);
        if ("DECLINED".equals(invitation.status())) {
            return new DeclineResponse("DECLINED");
        }
        if (!"INVITED".equals(invitation.status()) || invitation.groupId() != null) {
            throw ApiException.conflict("INVITATION_NOT_PENDING", "Invitation is no longer pending");
        }
        if (!invitation.expiresAt().isAfter(Instant.now())) {
            matches.expireMatchGroup(invitation.matchGroupId());
            throw ApiException.conflict("INVITATION_EXPIRED", "Invitation has expired");
        }
        matches.updateInvitation(invitation.id(), "DECLINED");
        int remaining = matches.countInvitations(invitation.matchGroupId(), "ACCEPTED")
                + matches.countInvitations(invitation.matchGroupId(), "INVITED");
        if (invitation.userId().equals(invitation.requesterId()) || remaining < MIN_GROUP_SIZE) {
            matches.expireMatchGroup(invitation.matchGroupId());
            requeueAccepted(invitation.matchGroupId());
        }
        return new DeclineResponse("DECLINED");
    }

    public boolean processNextJob() {
        var claimed = matches.claimNextJob();
        if (claimed.isEmpty()) {
            return false;
        }
        MatchRepository.JobRow job = claimed.get();
        Timer.Sample sample = metrics.startMatchJob();
        try {
            process(job);
            matches.markDone(job.id());
            metrics.finishMatchJob(sample, "done");
            return true;
        } catch (ApiException e) {
            matches.markFailed(job.id(), e.code());
            metrics.finishMatchJob(sample, "rejected");
            log.info("match_job_failed job_id={} code={}", job.id(), e.code());
            return true;
        } catch (RuntimeException e) {
            matches.markFailed(job.id(), "INTERNAL");
            metrics.finishMatchJob(sample, "error");
            log.error("match_job_internal_error job_id={} type={}", job.id(), e.getClass().getSimpleName());
            return true;
        }
    }

    public int expireDue() {
        return matches.expireDueGroups();
    }

    public int recoverStale() {
        return matches.recoverStale();
    }

    private void process(MatchRepository.JobRow job) {
        UserRecord requester = users.findById(job.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_CANDIDATES",
                        "Requester profile is missing"));
        List<AvailabilitySlot> requesterAvailability = parseAvailability(requester.availabilityJson());
        Set<Integer> requesterInterests = users.findUserInterests(requester.id()).stream()
                .map(i -> i.interestId()).collect(Collectors.toSet());
        if (requester.lat() == null || requester.lng() == null || requesterInterests.size() < 2
                || requesterAvailability.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_CANDIDATES", "Requester profile incomplete");
        }
        Preferences preferences = preferences(job.preferences());
        List<String> cells = h3.kRing(requester.lat(), requester.lng(), 2);
        List<MatchRepository.CandidateRow> rows = matches.candidates(requester.id(), cells,
                preferences.maxDistanceKm());
        List<UUID> rowIds = rows.stream().map(MatchRepository.CandidateRow::id).toList();
        Map<UUID, List<InterestRecord>> interests = matches.interestsFor(rowIds);
        Map<UUID, Integer> sharedWithRequester = matches.sharedGroupCounts(requester.id(), rowIds);
        List<Member> eligible = new ArrayList<>();
        for (MatchRepository.CandidateRow row : rows) {
            var candidateInterests = interests.getOrDefault(row.id(), List.of());
            var candidateAvailability = parseAvailability(row.availabilityJson());
            if (candidateInterests.size() < 2 || candidateAvailability.isEmpty()
                    || sharedWithRequester.getOrDefault(row.id(), 0) >= 2) {
                continue;
            }
            if (MatchScoring.overlapMinutes(requesterAvailability, candidateAvailability) < 30) {
                continue;
            }
            eligible.add(new Member(row.id(), row.displayName(), row.lat(), row.lng(),
                    candidateInterests.stream().map(InterestRecord::id).collect(Collectors.toSet()),
                    candidateAvailability, row.distanceM() / 1000.0));
        }
        if (eligible.size() < MIN_GROUP_SIZE - 1) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_CANDIDATES", "Not enough candidates");
        }
        List<UUID> allIds = new ArrayList<>();
        allIds.add(requester.id());
        eligible.forEach(member -> allIds.add(member.id()));
        Map<String, Integer> pairShared = matches.pairSharedCounts(allIds);

        Member requesterMember = new Member(requester.id(), requester.displayName(), requester.lat(), requester.lng(),
                requesterInterests, requesterAvailability, 0);
        List<Member> others = new ArrayList<>(eligible);
        Member seed = others.stream()
                .max(Comparator.comparingDouble(candidate -> others.stream()
                        .filter(other -> !other.id().equals(candidate.id()))
                        .mapToDouble(other -> score(candidate, other, preferences.maxDistanceKm(), pairShared))
                        .sum() + score(candidate, requesterMember, preferences.maxDistanceKm(), pairShared)))
                .orElseThrow();
        List<Member> group = new ArrayList<>(List.of(requesterMember, seed));
        others.remove(seed);
        while (group.size() < preferences.preferredGroupSize() && !others.isEmpty()) {
            Member best = null;
            double bestScore = -1;
            for (Member candidate : others) {
                boolean compatible = group.stream()
                        .allMatch(member -> pairShared.getOrDefault(pairKey(candidate.id(), member.id()), 0) < 2);
                if (!compatible || distanceToCentroid(requesterMember, group, candidate) > preferences.maxDistanceKm()) {
                    continue;
                }
                double score = group.stream()
                        .mapToDouble(member -> score(candidate, member, preferences.maxDistanceKm(), pairShared))
                        .average().orElse(0);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            if (best == null) {
                break;
            }
            group.add(best);
            others.remove(best);
        }
        if (group.size() < MIN_GROUP_SIZE) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_CANDIDATES", "Not enough compatible members");
        }
        double total = 0;
        int pairs = 0;
        for (int i = 0; i < group.size(); i++) {
            for (int j = i + 1; j < group.size(); j++) {
                total += score(group.get(i), group.get(j), preferences.maxDistanceKm(), pairShared);
                pairs++;
            }
        }
        double score = pairs == 0 ? 0 : Math.round(total / pairs * 1000.0) / 1000.0;
        List<UUID> memberIds = group.stream().map(Member::id).toList();
        matches.insertMatchGroup(job.id(), memberIds, score, Instant.now().plusSeconds(48 * 3600));
    }

    private double distanceToCentroid(Member requester, List<Member> group, Member candidate) {
        double lat = 0;
        double lng = 0;
        for (Member member : group) {
            lat += member.lat();
            lng += member.lng();
        }
        lat = (lat + candidate.lat()) / (group.size() + 1);
        lng = (lng + candidate.lng()) / (group.size() + 1);
        return MatchScoring.haversineKm(requester.lat(), requester.lng(), lat, lng);
    }

    private double score(Member a, Member b, int maxDistanceKm, Map<String, Integer> pairShared) {
        double distance = MatchScoring.haversineKm(a.lat(), a.lng(), b.lat(), b.lng());
        return MatchScoring.score(a.interests(), b.interests(), a.availability(), b.availability(),
                distance, maxDistanceKm, pairShared.getOrDefault(pairKey(a.id(), b.id()), 0));
    }

    private String pairKey(UUID left, UUID right) {
        return left.toString().compareTo(right.toString()) < 0
                ? left + "|" + right : right + "|" + left;
    }

    private UUID createConfirmedGroup(MatchRepository.InvitationRow invitation) {
        List<UUID> memberIds = matches.acceptedUserIds(invitation.matchGroupId());
        if (!memberIds.contains(invitation.requesterId())) {
            return null;
        }
        Map<UUID, List<InterestRecord>> interests = matches.interestsFor(memberIds);
        String name = "Kinship group";
        String category = "social";
        if (!memberIds.isEmpty()) {
            Map<Integer, InterestRecord> common = null;
            for (UUID id : memberIds) {
                Map<Integer, InterestRecord> current = interests.getOrDefault(id, List.of()).stream()
                        .collect(Collectors.toMap(InterestRecord::id, value -> value, (a, b) -> a));
                if (common == null) {
                    common = current;
                } else {
                    common.keySet().retainAll(current.keySet());
                }
            }
            if (common != null && !common.isEmpty()) {
                InterestRecord first = common.values().iterator().next();
                name = "Kinship " + first.name();
                category = first.category();
            }
        }
        double lat = 0;
        double lng = 0;
        int count = 0;
        for (UUID id : memberIds) {
            UserRecord user = users.findById(id).orElse(null);
            if (user != null && user.lat() != null && user.lng() != null) {
                lat += user.lat();
                lng += user.lng();
                count++;
            }
        }
        if (count == 0) {
            UserRecord requester = users.findById(invitation.requesterId()).orElseThrow();
            lat = requester.lat() == null ? 0 : requester.lat();
            lng = requester.lng() == null ? 0 : requester.lng();
            count = 1;
        }
        UUID groupId = UUID.randomUUID();
        groups.insertGroup(groupId, name, "Formed by Kinship matching", category, null,
                lat / count, lng / count, memberIds.size(), "private", "OPEN", invitation.requesterId());
        for (UUID memberId : memberIds) {
            groups.upsertMembership(groupId, memberId,
                    memberId.equals(invitation.requesterId()) ? "OWNER" : "MEMBER", "ACTIVE");
        }
        matches.confirmMatchGroup(invitation.matchGroupId(), groupId);
        return groupId;
    }

    private void requeueAccepted(UUID matchGroupId) {
        String preferences = matches.jobPreferencesForMatchGroup(matchGroupId);
        for (UUID userId : matches.acceptedUserIds(matchGroupId)) {
            if (matches.findActiveJob(userId).isEmpty()) {
                try {
                    matches.insertJob(userId, preferences);
                } catch (DataIntegrityViolationException ignored) {
                    // Another job was inserted concurrently.
                }
            }
        }
    }

    private MatchRepository.InvitationRow requireInvitation(UUID userId, UUID invitationId) {
        MatchRepository.InvitationRow invitation = matches.findInvitationForUpdate(invitationId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Invitation not found"));
        if (!invitation.userId().equals(userId)) {
            throw ApiException.notFound("NOT_FOUND", "Invitation not found");
        }
        return invitation;
    }

    private Preferences preferences(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return new Preferences(node.path("max_distance_km").asInt(10),
                    node.path("preferred_group_size").asInt(4));
        } catch (Exception e) {
            return new Preferences(10, 4);
        }
    }

    private List<AvailabilitySlot> parseAvailability(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AvailabilitySlot>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private record Member(UUID id, String displayName, double lat, double lng, Set<Integer> interests,
                          List<AvailabilitySlot> availability, double distanceKm) {
    }
}
