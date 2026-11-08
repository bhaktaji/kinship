package com.kinship.messaging;

import com.kinship.common.api.ApiException;
import com.kinship.common.security.UserPrincipal;
import com.kinship.messaging.dto.MessageDtos.TicketRequest;
import com.kinship.messaging.dto.MessageDtos.TicketResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RealtimeController {

    private final WsTicketService tickets;

    public RealtimeController(WsTicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping("/v1/realtime/tickets")
    public ResponseEntity<TicketResponse> ticket(@AuthenticationPrincipal UserPrincipal principal,
                                                 @Valid @RequestBody TicketRequest request) {
        if (principal == null) {
            throw ApiException.unauthorized("UNAUTHENTICATED", "Authentication required");
        }
        WsTicketService.Issued issued = tickets.issue(principal.id(), request.groupId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new TicketResponse(issued.ticket(), issued.expiresIn()));
    }
}
