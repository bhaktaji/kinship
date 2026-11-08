package com.kinship.group;

import com.kinship.common.api.ApiException;
import com.kinship.common.security.UserPrincipal;
import com.kinship.group.dto.GroupDtos.CreateGroupRequest;
import com.kinship.group.dto.GroupDtos.GroupDetailResponse;
import com.kinship.group.dto.GroupDtos.GroupPage;
import com.kinship.group.dto.GroupDtos.GroupResponse;
import com.kinship.group.dto.GroupDtos.JoinRequestCreateRequest;
import com.kinship.group.dto.GroupDtos.JoinRequestResponse;
import com.kinship.group.dto.GroupDtos.RejectRequest;
import com.kinship.group.dto.GroupDtos.JoinRequestView;
import com.kinship.group.dto.GroupDtos.JoinResponse;
import com.kinship.group.dto.GroupDtos.MembershipUpdateRequest;
import com.kinship.group.dto.GroupDtos.UpdateGroupRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/groups")
@Validated
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping
    public GroupPage mine(@AuthenticationPrincipal UserPrincipal principal,
                          @RequestParam(defaultValue = "false") boolean mine,
                          @RequestParam(required = false) String pageToken,
                          @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        if (!mine) {
            throw ApiException.badRequest("VALIDATION_ERROR", "mine=true is required");
        }
        return groupService.myGroups(require(principal).id(), pageToken, limit);
    }

    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public GroupDetailResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                      @Valid @RequestBody CreateGroupRequest request) {
        return groupService.create(require(principal).id(), request);
    }

    @GetMapping("/{id}")
    public GroupDetailResponse detail(@AuthenticationPrincipal UserPrincipal principal,
                                      @PathVariable UUID id) {
        return groupService.detail(require(principal).id(), id);
    }

    @PatchMapping("/{id}")
    public GroupResponse update(@AuthenticationPrincipal UserPrincipal principal,
                                @PathVariable UUID id,
                                @Valid @RequestBody UpdateGroupRequest request) {
        return groupService.update(require(principal).id(), id, request);
    }

    @PostMapping("/{id}/join")
    public JoinResponse join(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        return groupService.join(require(principal).id(), id);
    }

    @PostMapping("/{id}/join-requests")
    public JoinRequestResponse requestJoin(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                           @Valid @RequestBody JoinRequestCreateRequest request) {
        return groupService.requestJoin(require(principal).id(), id, request.reason(), Boolean.TRUE.equals(request.shareWorkplace()));
    }

    @GetMapping("/{id}/join-requests")
    public java.util.List<JoinRequestView> listJoinRequests(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        return groupService.listJoinRequests(require(principal).id(), id);
    }

    @PostMapping("/join-requests/{requestId}/approve")
    public void approveJoinRequest(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID requestId) {
        groupService.approveJoinRequest(require(principal).id(), requestId);
    }

    @PostMapping("/join-requests/{requestId}/reject")
    public void rejectJoinRequest(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID requestId,
                                  @RequestBody(required = false) RejectRequest body) {
        groupService.rejectJoinRequest(require(principal).id(), requestId, body == null ? null : body.reason());
    }

    @DeleteMapping("/{id}/members/me")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        groupService.leave(require(principal).id(), id);
    }

    @PatchMapping("/{id}/members/{userId}")
    public void updateMember(@AuthenticationPrincipal UserPrincipal principal,
                             @PathVariable UUID id, @PathVariable UUID userId,
                             @Valid @RequestBody MembershipUpdateRequest request) {
        groupService.updateMember(require(principal).id(), id, userId, request);
    }

    private UserPrincipal require(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("UNAUTHENTICATED", "Authentication required");
        }
        return principal;
    }
}
