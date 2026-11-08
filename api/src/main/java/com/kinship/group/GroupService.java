package com.kinship.group;

import com.kinship.common.api.ApiException;
import com.kinship.event.EventRepository;
import com.kinship.group.dto.GroupDtos.CreateGroupRequest;
import com.kinship.group.dto.GroupDtos.GroupCard;
import com.kinship.group.dto.GroupDtos.GroupDetailResponse;
import com.kinship.group.dto.GroupDtos.GroupPage;
import com.kinship.group.dto.GroupDtos.GroupResponse;
import com.kinship.group.dto.GroupDtos.JoinRequestResponse;
import com.kinship.group.dto.GroupDtos.JoinRequestView;
import com.kinship.group.dto.GroupDtos.JoinResponse;
import com.kinship.group.dto.GroupDtos.MembershipUpdateRequest;
import com.kinship.group.dto.GroupDtos.UpdateGroupRequest;
import com.kinship.notification.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class GroupService {

    private final GroupRepository groups;
    private final JoinRequestRepository joinRequests;
    private final EventRepository events;
    private final GroupPageCursor cursor;
    private final NotificationService notifications;

    public GroupService(GroupRepository groups, JoinRequestRepository joinRequests, EventRepository events,
                        GroupPageCursor cursor, NotificationService notifications) {
        this.groups = groups;
        this.joinRequests = joinRequests;
        this.events = events;
        this.cursor = cursor;
        this.notifications = notifications;
    }

    @Transactional
    public GroupDetailResponse create(UUID userId, CreateGroupRequest request) {
        double lat;
        double lng;
        if (request.location() != null) {
            lat = request.location().lat();
            lng = request.location().lng();
        } else if (request.activityId() != null) {
            var location = groups.activityLocation(request.activityId())
                    .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Activity not found"));
            lat = location.lat();
            lng = location.lng();
        } else {
            throw ApiException.badRequest("LOCATION_REQUIRED", "Location is required without an activity");
        }
        UUID groupId = UUID.randomUUID();
        int maxMembers = request.maxMembers() == null ? 6 : request.maxMembers();
        String visibility = request.visibility() == null ? "public" : request.visibility();
        String joinPolicy = request.joinPolicy() == null ? "OPEN" : request.joinPolicy();
        groups.insertGroup(groupId, request.name(), request.description(), request.category(),
                request.activityId(), lat, lng, maxMembers, visibility, joinPolicy, userId);
        groups.upsertMembership(groupId, userId, "OWNER", "ACTIVE");
        return detail(userId, groupId);
    }

    @Transactional(readOnly = true)
    public GroupDetailResponse detail(UUID userId, UUID groupId) {
        GroupRepository.GroupRow group = requireVisible(userId, groupId);
        GroupRepository.Membership membership = groups.membership(groupId, userId).orElse(null);
        boolean owner = membership != null && "OWNER".equals(membership.role())
                && "ACTIVE".equals(membership.status());
        var members = groups.members(groupId, owner);
        var nextEvents = events.listEvents(groupId, Instant.now(), Instant.now().plusSeconds(365L * 24 * 3600),
                5, userId);
        return new GroupDetailResponse(
                groups.toResponse(group, membership == null ? null : membership.role(),
                        membership == null ? null : membership.status()),
                members, nextEvents);
    }

    @Transactional
    public GroupResponse update(UUID userId, UUID groupId, UpdateGroupRequest request) {
        GroupRepository.GroupRow group = requireVisible(userId, groupId);
        requireOwner(userId, groupId);
        if (request.maxMembers() != null && request.maxMembers() < groups.activeMemberCount(groupId)) {
            throw ApiException.conflict("GROUP_SIZE_BELOW_MEMBERS", "max_members is below the active member count");
        }
        groups.updateGroup(groupId, request.name(), request.description(), request.maxMembers(),
                request.visibility(), request.joinPolicy(), request.bannerAssetId());
        GroupRepository.GroupRow updated = groups.findGroup(groupId).orElse(group);
        return groups.toResponse(updated, "OWNER", "ACTIVE");
    }

    @Transactional
    public JoinResponse join(UUID userId, UUID groupId) {
        GroupRepository.GroupLock group = groups.lockGroup(groupId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Group not found"));
        if (!"ACTIVE".equals(group.status())) {
            throw ApiException.conflict("GROUP_ARCHIVED", "Group is archived");
        }
        var existing = groups.membership(groupId, userId);
        if (existing.isPresent()) {
            if ("ACTIVE".equals(existing.get().status()) || "PENDING".equals(existing.get().status())) {
                return new JoinResponse(groupId, existing.get().status());
            }
        }
        if ("APPROVAL".equals(group.joinPolicy())) {
            throw ApiException.badRequest("APPROVAL_REQUIRED", "Use the join request endpoint for this group");
        }
        if ("private".equals(group.visibility())) {
            groups.upsertMembership(groupId, userId, "MEMBER", "PENDING");
            return new JoinResponse(groupId, "PENDING");
        }
        if (groups.activeMemberCount(groupId) >= group.maxMembers()) {
            throw ApiException.conflict("GROUP_FULL", "Group is full");
        }
        groups.upsertMembership(groupId, userId, "MEMBER", "ACTIVE");
        UUID ownerId = groups.findGroup(groupId).map(GroupRepository.GroupRow::createdBy).orElse(null);
        if (ownerId != null && !ownerId.equals(userId)) {
            notifications.notify(ownerId, "NEW_MEMBER", "New member", "A user joined your group", "GROUP", groupId);
        }
        return new JoinResponse(groupId, "ACTIVE");
    }

    @Transactional
    public JoinRequestResponse requestJoin(UUID userId, UUID groupId, String reason, boolean shareWorkplace) {
        GroupRepository.GroupLock group = groups.lockGroup(groupId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Group not found"));
        if (!"ACTIVE".equals(group.status())) {
            throw ApiException.conflict("GROUP_ARCHIVED", "Group is archived");
        }
        if (!"APPROVAL".equals(group.joinPolicy())) {
            throw ApiException.badRequest("APPROVAL_NOT_REQUIRED", "This group does not require approval");
        }
        var existing = groups.membership(groupId, userId);
        if (existing.isPresent() && ("ACTIVE".equals(existing.get().status()) || "PENDING".equals(existing.get().status()))) {
            throw ApiException.conflict("ALREADY_MEMBER", "Already a member or pending");
        }
        var pending = joinRequests.findPending(groupId, userId);
        if (pending.isPresent()) {
            throw ApiException.conflict("REQUEST_PENDING", "A join request is already pending");
        }
        Instant lastRejected = joinRequests.lastRejectedAt(groupId, userId).orElse(null);
        if (lastRejected != null && lastRejected.plusSeconds(7L * 24 * 3600).isAfter(Instant.now())) {
            throw ApiException.conflict("REAPPLICATION_COOLDOWN", "Please wait before reapplying");
        }
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw ApiException.badRequest("VALIDATION_ERROR", "reason must be 1..500 characters");
        }
        UUID requestId = joinRequests.insert(groupId, userId, reason.trim(), shareWorkplace);
        groups.upsertMembership(groupId, userId, "MEMBER", "PENDING");
        UUID ownerId = groups.findGroup(groupId).map(GroupRepository.GroupRow::createdBy).orElse(null);
        notifications.notify(ownerId, "JOIN_REQUEST_RECEIVED",
                "New join request", "A user requested to join your group", "GROUP", groupId);
        return new JoinRequestResponse(requestId, groupId, "PENDING");
    }

    @Transactional(readOnly = true)
    public List<JoinRequestView> listJoinRequests(UUID userId, UUID groupId) {
        requireOwner(userId, groupId);
        return joinRequests.listPending(groupId).stream().map(this::toJoinRequestView).toList();
    }

    @Transactional
    public void approveJoinRequest(UUID ownerId, UUID requestId) {
        JoinRequestRepository.JoinRequestRecord request = joinRequests.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Join request not found"));
        GroupRepository.GroupLock group = groups.lockGroup(request.groupId())
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Group not found"));
        requireOwner(ownerId, request.groupId());
        if (!"PENDING".equals(request.status())) {
            throw ApiException.conflict("REQUEST_DECIDED", "Request is not pending");
        }
        if (groups.activeMemberCount(request.groupId()) >= group.maxMembers()) {
            throw ApiException.conflict("GROUP_FULL", "Group is full");
        }
        joinRequests.decide(requestId, "APPROVED", ownerId, null);
        groups.upsertMembership(request.groupId(), request.userId(), "MEMBER", "ACTIVE");
        notifications.notify(request.userId(), "JOIN_REQUEST_APPROVED", "Join request approved",
                "Your request to join the group was approved", "GROUP", request.groupId());
    }

    @Transactional
    public void rejectJoinRequest(UUID ownerId, UUID requestId, String reason) {
        JoinRequestRepository.JoinRequestRecord request = joinRequests.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Join request not found"));
        requireOwner(ownerId, request.groupId());
        if (!"PENDING".equals(request.status())) {
            throw ApiException.conflict("REQUEST_DECIDED", "Request is not pending");
        }
        joinRequests.decide(requestId, "REJECTED", ownerId, reason);
        groups.upsertMembership(request.groupId(), request.userId(), "MEMBER", "LEFT");
        notifications.notify(request.userId(), "JOIN_REQUEST_REJECTED", "Join request rejected",
                "Your request to join the group was rejected", "GROUP", request.groupId());
    }

    private JoinRequestView toJoinRequestView(JoinRequestRepository.JoinRequestRecord r) {
        return new JoinRequestView(r.id(), r.groupId(), r.userId(), r.reason(), r.shareWorkplace(),
                r.displayName(), r.bio(), r.hobbiesJson(), r.profession(),
                r.shareWorkplace() && r.workplaceShared() ? r.workplace() : null,
                r.profilePhotoAssetId(), r.availabilityJson(), r.createdAt());
    }

    @Transactional
    public void leave(UUID userId, UUID groupId) {
        GroupRepository.GroupLock group = groups.lockGroup(groupId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Group not found"));
        GroupRepository.Membership membership = groups.membership(groupId, userId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Membership not found"));
        boolean owner = "OWNER".equals(membership.role()) && "ACTIVE".equals(membership.status());
        if ("PENDING".equals(membership.status())) {
            groups.upsertMembership(groupId, userId, "MEMBER", "LEFT");
            return;
        }
        cancelFutureRsvps(groupId, userId);
        groups.upsertMembership(groupId, userId, owner ? "OWNER" : "MEMBER", "LEFT");
        if (owner) {
            var successor = groups.successorOwner(groupId, userId);
            if (successor.isPresent()) {
                groups.upsertMembership(groupId, successor.get(), "OWNER", "ACTIVE");
            } else {
                groups.archiveGroup(groupId);
            }
        }
    }

    @Transactional
    public void updateMember(UUID ownerId, UUID groupId, UUID memberId, MembershipUpdateRequest request) {
        requireOwner(ownerId, groupId);
        GroupRepository.GroupLock group = groups.lockGroup(groupId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Group not found"));
        GroupRepository.Membership membership = groups.membership(groupId, memberId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Member not found"));
        if (memberId.equals(ownerId) || ("OWNER".equals(membership.role()) && "ACTIVE".equals(membership.status()))) {
            throw ApiException.forbidden("FORBIDDEN", "The owner cannot be removed");
        }
        if ("ACTIVE".equals(request.status())) {
            if (!"PENDING".equals(membership.status())) {
                return;
            }
            if (groups.activeMemberCount(groupId) >= group.maxMembers()) {
                throw ApiException.conflict("GROUP_FULL", "Group is full");
            }
            groups.upsertMembership(groupId, memberId, "MEMBER", "ACTIVE");
        } else {
            cancelFutureRsvps(groupId, memberId);
            groups.upsertMembership(groupId, memberId, "MEMBER", "REMOVED");
        }
    }

    @Transactional(readOnly = true)
    public GroupPage myGroups(UUID userId, String pageToken, int limit) {
        GroupPageCursor.Cursor decoded = pageToken == null ? null : cursor.decode(pageToken);
        List<GroupRepository.MyGroupRow> rows = groups.myGroups(userId,
                decoded == null ? null : decoded.joinedAt(),
                decoded == null ? null : decoded.groupId(), limit + 1);
        boolean more = rows.size() > limit;
        List<GroupRepository.MyGroupRow> selected = more ? rows.subList(0, limit) : rows;
        List<GroupCard> items = selected.stream().map(GroupRepository.MyGroupRow::card).toList();
        String next = more && !selected.isEmpty()
                ? cursor.encode(selected.get(selected.size() - 1).joinedAt(),
                        selected.get(selected.size() - 1).card().groupId())
                : null;
        return new GroupPage(items, next);
    }

    private void cancelFutureRsvps(UUID groupId, UUID userId) {
        for (UUID eventId : events.futureGoingEventIdsForUpdate(groupId, userId)) {
            events.declineRsvp(eventId, userId);
            events.decrementConfirmed(eventId);
        }
    }

    public GroupRepository.GroupRow requireVisible(UUID userId, UUID groupId) {
        GroupRepository.GroupRow group = groups.findGroup(groupId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Group not found"));
        if ("private".equals(group.visibility())) {
            var membership = groups.membership(groupId, userId);
            if (membership.isEmpty()
                    || (!"ACTIVE".equals(membership.get().status()) && !"PENDING".equals(membership.get().status()))) {
                throw ApiException.notFound("NOT_FOUND", "Group not found");
            }
        }
        return group;
    }

    public GroupRepository.Membership requireMember(UUID userId, UUID groupId) {
        var membership = groups.membership(groupId, userId)
                .orElseThrow(() -> ApiException.forbidden("FORBIDDEN", "Active membership required"));
        if (!"ACTIVE".equals(membership.status())) {
            throw ApiException.forbidden("FORBIDDEN", "Active membership required");
        }
        return membership;
    }

    public void requireOwner(UUID userId, UUID groupId) {
        var membership = requireMember(userId, groupId);
        if (!"OWNER".equals(membership.role())) {
            throw ApiException.forbidden("FORBIDDEN", "Group owner required");
        }
    }
}
