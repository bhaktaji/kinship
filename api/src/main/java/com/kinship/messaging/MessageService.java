package com.kinship.messaging;

import com.kinship.common.api.ApiException;
import com.kinship.common.ops.KinshipMetrics;
import com.kinship.group.GroupRepository;
import com.kinship.notification.NotificationService;
import com.kinship.messaging.dto.MessageDtos.MessagePage;
import com.kinship.messaging.dto.MessageDtos.MessageResponse;
import com.kinship.messaging.dto.MessageDtos.SendResponse;
import com.kinship.messaging.dto.MessageDtos.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

@Service
public class MessageService {

    private final MessageRepository messages;
    private final GroupRepository groups;
    private final RedisGroupPublisher publisher;
    private final KinshipMetrics metrics;
    private final NotificationService notifications;

    public MessageService(MessageRepository messages, GroupRepository groups, RedisGroupPublisher publisher,
                          KinshipMetrics metrics, NotificationService notifications) {
        this.messages = messages;
        this.groups = groups;
        this.publisher = publisher;
        this.metrics = metrics;
        this.notifications = notifications;
    }

    @Transactional
    public SendResult send(UUID userId, UUID groupId, String body, UUID clientMsgId) {
        if (body == null || body.isBlank() || body.length() > 2000) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Message body must be 1..2000 characters");
        }
        if (clientMsgId == null) {
            throw ApiException.badRequest("VALIDATION_ERROR", "client_msg_id is required");
        }
        requireActiveMember(userId, groupId);
        MessageRepository.GroupSequence group = messages.lockGroup(groupId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Group not found"));
        if (!"ACTIVE".equals(group.status())) {
            throw ApiException.conflict("GROUP_ARCHIVED", "Group is archived");
        }
        if (clientMsgId != null) {
            var existing = messages.findByClientId(groupId, clientMsgId);
            if (existing.isPresent()) {
                MessageRepository.MessageRow row = existing.get();
                return new SendResult(new SendResponse(row.id(), row.sequenceId(), row.createdAt()), false);
            }
        }
        long sequence = messages.nextSequence(groupId);
        UUID messageId = UUID.randomUUID();
        messages.insert(messageId, groupId, userId, body, sequence, clientMsgId);
        MessageRepository.MessageRow row = messages.findByClientId(groupId, clientMsgId)
                .orElseThrow(() -> new IllegalStateException("Inserted message not found"));
        metrics.messageSent();
        for (UUID memberId : groups.activeMemberIds(groupId)) {
            if (!memberId.equals(userId)) {
                notifications.notify(memberId, "NEW_MESSAGE", "New group message",
                        body.length() > 80 ? body.substring(0, 80) + "..." : body, "GROUP", groupId);
            }
        }
        publishAfterCommit(row);
        return new SendResult(new SendResponse(row.id(), row.sequenceId(), row.createdAt()), true);
    }

    @Transactional(readOnly = true)
    public MessagePage history(UUID userId, UUID groupId, Long beforeSeq, int limit) {
        requireActiveMember(userId, groupId);
        int effective = Math.max(1, Math.min(limit, 50));
        List<MessageRepository.MessageRow> rows = messages.history(groupId, beforeSeq, effective + 1);
        boolean more = rows.size() > effective;
        List<MessageRepository.MessageRow> page = more ? rows.subList(0, effective) : rows;
        Long next = more && !page.isEmpty() ? page.get(page.size() - 1).sequenceId() : null;
        List<MessageResponse> items = page.stream()
                .map(MessageRepository.MessageRow::toResponse).toList();
        return new MessagePage(items, next);
    }

    @Transactional(readOnly = true)
    public long latestSequence(UUID userId, UUID groupId) {
        requireActiveMember(userId, groupId);
        return messages.latestSequence(groupId);
    }

    private void requireActiveMember(UUID userId, UUID groupId) {
        var membership = groups.membership(groupId, userId)
                .orElseThrow(() -> ApiException.forbidden("FORBIDDEN", "Active membership required"));
        if (!"ACTIVE".equals(membership.status())) {
            throw ApiException.forbidden("FORBIDDEN", "Active membership required");
        }
    }

    private void publishAfterCommit(MessageRepository.MessageRow row) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.publish(row);
                }
            });
        } else {
            publisher.publish(row);
        }
    }
}
