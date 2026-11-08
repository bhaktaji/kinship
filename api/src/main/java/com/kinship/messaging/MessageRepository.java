package com.kinship.messaging;

import com.kinship.messaging.dto.MessageDtos.MessageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MessageRepository {

    private final JdbcClient jdbc;

    public MessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<GroupSequence> lockGroup(UUID groupId) {
        return jdbc.sql("""
                        SELECT id, status, last_message_seq FROM groups WHERE id = :id FOR UPDATE
                        """)
                .param("id", groupId)
                .query((rs, rowNum) -> new GroupSequence(rs.getObject("id", UUID.class),
                        rs.getString("status"), rs.getLong("last_message_seq")))
                .optional();
    }

    public long nextSequence(UUID groupId) {
        Long sequence = jdbc.sql("""
                        UPDATE groups SET last_message_seq = last_message_seq + 1
                         WHERE id = :id
                        RETURNING last_message_seq
                        """)
                .param("id", groupId).query(Long.class).single();
        return sequence == null ? 0 : sequence;
    }

    public Optional<MessageRow> findByClientId(UUID groupId, UUID clientMsgId) {
        return jdbc.sql("""
                        SELECT id, group_id, sender_id, body, sequence_id, client_msg_id, created_at
                          FROM messages
                         WHERE group_id = :groupId AND client_msg_id = :clientMsgId
                        """)
                .param("groupId", groupId).param("clientMsgId", clientMsgId)
                .query(this::map).optional();
    }

    public void insert(UUID id, UUID groupId, UUID senderId, String body, long sequenceId, UUID clientMsgId) {
        jdbc.sql("""
                        INSERT INTO messages (id, group_id, sender_id, body, sequence_id, client_msg_id, created_at)
                        VALUES (:id, :groupId, :senderId, :body, :sequenceId, :clientMsgId, now())
                        """)
                .param("id", id).param("groupId", groupId).param("senderId", senderId)
                .param("body", body).param("sequenceId", sequenceId).param("clientMsgId", clientMsgId).update();
    }

    public List<MessageRow> history(UUID groupId, Long beforeSeq, int limit) {
        return jdbc.sql("""
                        SELECT id, group_id, sender_id, body, sequence_id, client_msg_id, created_at
                          FROM messages
                         WHERE group_id = :groupId
                           AND (CAST(:beforeSeq AS bigint) IS NULL OR sequence_id < CAST(:beforeSeq AS bigint))
                         ORDER BY sequence_id DESC
                         LIMIT :limit
                        """)
                .param("groupId", groupId).param("beforeSeq", beforeSeq).param("limit", limit)
                .query(this::map).list();
    }

    public long latestSequence(UUID groupId) {
        Long value = jdbc.sql("SELECT COALESCE(max(sequence_id), 0) FROM messages WHERE group_id = :groupId")
                .param("groupId", groupId).query(Long.class).single();
        return value == null ? 0 : value;
    }

    private MessageRow map(ResultSet rs, int rowNum) throws SQLException {
        return new MessageRow(rs.getObject("id", UUID.class), rs.getObject("group_id", UUID.class),
                rs.getObject("sender_id", UUID.class), rs.getString("body"), rs.getLong("sequence_id"),
                rs.getObject("client_msg_id", UUID.class), rs.getTimestamp("created_at").toInstant());
    }

    public record GroupSequence(UUID id, String status, long lastMessageSeq) {
    }

    public record MessageRow(UUID id, UUID groupId, UUID senderId, String body, long sequenceId,
                             UUID clientMsgId, Instant createdAt) {

        public MessageResponse toResponse() {
            return new MessageResponse(id, sequenceId, senderId, body, createdAt);
        }
    }
}
