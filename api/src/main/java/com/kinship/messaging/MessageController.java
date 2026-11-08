package com.kinship.messaging;

import com.kinship.common.api.ApiException;
import com.kinship.common.ratelimit.RateLimitService;
import com.kinship.common.security.UserPrincipal;
import com.kinship.messaging.dto.MessageDtos.MessagePage;
import com.kinship.messaging.dto.MessageDtos.SendRequest;
import com.kinship.messaging.dto.MessageDtos.SendResponse;
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
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.UUID;

@RestController
@Validated
public class MessageController {

    private final MessageService messageService;
    private final RateLimitService rateLimit;

    public MessageController(MessageService messageService, RateLimitService rateLimit) {
        this.messageService = messageService;
        this.rateLimit = rateLimit;
    }

    @PostMapping("/v1/groups/{groupId}/messages")
    public ResponseEntity<SendResponse> send(@AuthenticationPrincipal UserPrincipal principal,
                                             @PathVariable UUID groupId,
                                             @Valid @RequestBody SendRequest request) {
        UserPrincipal user = require(principal);
        rateLimit.check("message:" + user.id(), 30, Duration.ofMinutes(1));
        var result = messageService.send(user.id(), groupId, request.body(), request.clientMsgId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.response());
    }

    @GetMapping("/v1/groups/{groupId}/messages")
    public MessagePage history(@AuthenticationPrincipal UserPrincipal principal,
                               @PathVariable UUID groupId,
                               @RequestParam(name = "before_seq", required = false) Long beforeSeq,
                               @RequestParam(defaultValue = "50") @Min(1) @Max(50) int limit) {
        return messageService.history(require(principal).id(), groupId, beforeSeq, limit);
    }

    private UserPrincipal require(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("UNAUTHENTICATED", "Authentication required");
        }
        return principal;
    }
}
