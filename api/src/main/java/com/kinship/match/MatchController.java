package com.kinship.match;

import com.kinship.common.api.ApiException;
import com.kinship.common.ratelimit.RateLimitService;
import com.kinship.common.security.UserPrincipal;
import com.kinship.match.dto.MatchDtos.AcceptResponse;
import com.kinship.match.dto.MatchDtos.DeclineResponse;
import com.kinship.match.dto.MatchDtos.InvitationList;
import com.kinship.match.dto.MatchDtos.MatchJobCreated;
import com.kinship.match.dto.MatchDtos.MatchJobResponse;
import com.kinship.match.dto.MatchDtos.MatchRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

@RestController
public class MatchController {

    private final MatchService matchService;
    private final RateLimitService rateLimit;

    public MatchController(MatchService matchService, RateLimitService rateLimit) {
        this.matchService = matchService;
        this.rateLimit = rateLimit;
    }

    @PostMapping("/v1/match-requests")
    public ResponseEntity<MatchJobCreated> request(@AuthenticationPrincipal UserPrincipal principal,
                                                   @Valid @RequestBody(required = false) MatchRequest request) {
        UserPrincipal user = require(principal);
        rateLimit.check("match:" + user.id(), 3, Duration.ofDays(1));
        MatchRequest effective = request == null ? new MatchRequest(null, null) : request;
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(matchService.request(user.id(), effective));
    }

    @GetMapping("/v1/match-requests/{jobId}")
    public MatchJobResponse job(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID jobId) {
        return matchService.job(require(principal).id(), jobId);
    }

    @GetMapping("/v1/match-invitations")
    public InvitationList invitations(@AuthenticationPrincipal UserPrincipal principal,
                                      @RequestParam(defaultValue = "INVITED") String status) {
        return matchService.invitations(require(principal).id(), status);
    }

    @PostMapping("/v1/match-invitations/{id}/accept")
    public AcceptResponse accept(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        return matchService.accept(require(principal).id(), id);
    }

    @PostMapping("/v1/match-invitations/{id}/decline")
    public DeclineResponse decline(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        return matchService.decline(require(principal).id(), id);
    }

    private UserPrincipal require(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("UNAUTHENTICATED", "Authentication required");
        }
        return principal;
    }
}
