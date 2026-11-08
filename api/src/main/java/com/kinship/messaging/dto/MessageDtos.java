package com.kinship.messaging.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MessageDtos {

    private MessageDtos() {
    }

    public record SendRequest(
            @NotBlank @Size(min = 1, max = 2000) String body,
            @NotNull(message = "client_msg_id is required") UUID clientMsgId) {
    }

    public record SendResponse(UUID messageId, long sequenceId, Instant createdAt) {
    }

    public record SendResult(SendResponse response, boolean created) {
    }

    public record MessageResponse(UUID messageId, long sequenceId, UUID senderId, String body, Instant createdAt) {
    }

    public record MessagePage(List<MessageResponse> items, Long nextBeforeSeq) {
    }

    public record TicketRequest(@NotNull(message = "group_id is required") UUID groupId) {
    }

    public record TicketResponse(String ticket, long expiresIn) {
    }
}
