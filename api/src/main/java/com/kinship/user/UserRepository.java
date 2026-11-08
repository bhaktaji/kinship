package com.kinship.user;

import com.kinship.user.dto.InterestSelection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRecord> findById(UUID id) {
        return jdbc.sql("""
                        SELECT id, email, password_hash, display_name, timezone,
                               CASE WHEN location IS NULL THEN NULL ELSE ST_Y(location::geometry) END AS lat,
                               CASE WHEN location IS NULL THEN NULL ELSE ST_X(location::geometry) END AS lng,
                               availability::text AS availability,
                               status, created_at, bio, hobbies::text AS hobbies, profession, workplace,
                               workplace_shared, profile_photo_asset_id, onboarding_completed, onboarding_step
                          FROM users
                         WHERE id = :id
                        """)
                .param("id", id)
                .query(this::mapUser)
                .optional();
    }

    public Optional<UserRecord> findByEmail(String email) {
        return jdbc.sql("""
                        SELECT id, email, password_hash, display_name, timezone,
                               CASE WHEN location IS NULL THEN NULL ELSE ST_Y(location::geometry) END AS lat,
                               CASE WHEN location IS NULL THEN NULL ELSE ST_X(location::geometry) END AS lng,
                               availability::text AS availability,
                               status, created_at, bio, hobbies::text AS hobbies, profession, workplace,
                               workplace_shared, profile_photo_asset_id, onboarding_completed, onboarding_step
                          FROM users
                         WHERE lower(email) = lower(:email)
                        """)
                .param("email", email)
                .query(this::mapUser)
                .optional();
    }

    public boolean existsByEmail(String email) {
        Integer count = jdbc.sql("SELECT count(*) FROM users WHERE lower(email) = lower(:email)")
                .param("email", email)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public void insert(UUID id, String email, String passwordHash, String displayName, String timezone) {
        jdbc.sql("""
                        INSERT INTO users (id, email, password_hash, display_name, timezone)
                        VALUES (:id, :email, :passwordHash, :displayName, :timezone)
                        """)
                .param("id", id)
                .param("email", email)
                .param("passwordHash", passwordHash)
                .param("displayName", displayName)
                .param("timezone", timezone)
                .update();
    }

    public void updateBasics(UUID id, String displayName, String timezone) {
        jdbc.sql("""
                        UPDATE users
                           SET display_name = COALESCE(:displayName, display_name),
                               timezone = COALESCE(:timezone, timezone),
                               updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("displayName", displayName)
                .param("timezone", timezone)
                .update();
    }

    public void updateLocation(UUID id, double lat, double lng, String h3Cell) {
        jdbc.sql("""
                        UPDATE users
                           SET location = ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                               h3_cell_r7 = :h3,
                               updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("lat", lat)
                .param("lng", lng)
                .param("h3", h3Cell)
                .update();
    }

    public void updateExtended(UUID id, String bio, String hobbiesJson, String profession, String workplace,
                              Boolean workplaceShared, UUID profilePhotoAssetId, Boolean onboardingCompleted,
                              Integer onboardingStep) {
        jdbc.sql("""
                        UPDATE users
                           SET bio = COALESCE(:bio, bio),
                               hobbies = COALESCE(CAST(:hobbies AS jsonb), hobbies),
                               profession = COALESCE(:profession, profession),
                               workplace = COALESCE(:workplace, workplace),
                               workplace_shared = COALESCE(:workplaceShared, workplace_shared),
                               profile_photo_asset_id = COALESCE(:photo, profile_photo_asset_id),
                               onboarding_completed = COALESCE(:completed, onboarding_completed),
                               onboarding_step = COALESCE(:step, onboarding_step),
                               updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("bio", bio)
                .param("hobbies", hobbiesJson)
                .param("profession", profession)
                .param("workplace", workplace)
                .param("workplaceShared", workplaceShared)
                .param("photo", profilePhotoAssetId)
                .param("completed", onboardingCompleted)
                .param("step", onboardingStep)
                .update();
    }

    public void updateAvailability(UUID id, String availabilityJson) {
        jdbc.sql("""
                        UPDATE users
                           SET availability = CAST(:availability AS jsonb),
                               updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("availability", availabilityJson)
                .update();
    }

    public void replaceInterests(UUID userId, List<InterestSelection> interests) {
        jdbc.sql("DELETE FROM user_interests WHERE user_id = :userId")
                .param("userId", userId)
                .update();
        for (InterestSelection selection : interests) {
            jdbc.sql("""
                            INSERT INTO user_interests (user_id, interest_id, weight)
                            VALUES (:userId, :interestId, :weight)
                            ON CONFLICT (user_id, interest_id)
                            DO UPDATE SET weight = EXCLUDED.weight
                            """)
                    .param("userId", userId)
                    .param("interestId", selection.interestId())
                    .param("weight", selection.weight() == null ? new java.math.BigDecimal("1.0") : selection.weight())
                    .update();
        }
    }

    public List<UserInterestRecord> findUserInterests(UUID userId) {
        return jdbc.sql("""
                        SELECT i.id, i.slug, i.name, ui.weight
                          FROM user_interests ui
                          JOIN interests i ON i.id = ui.interest_id
                         WHERE ui.user_id = :userId
                         ORDER BY i.category, i.name
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> new UserInterestRecord(
                        rs.getInt("id"), rs.getString("slug"), rs.getString("name"), rs.getBigDecimal("weight")))
                .list();
    }

    public List<InterestRecord> findAllInterests() {
        return jdbc.sql("SELECT id, slug, name, category FROM interests ORDER BY category, name")
                .query((rs, rowNum) -> new InterestRecord(
                        rs.getInt("id"), rs.getString("slug"), rs.getString("name"), rs.getString("category")))
                .list();
    }

    private UserRecord mapUser(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new UserRecord(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                rs.getString("timezone"),
                rs.getObject("lat", Double.class),
                rs.getObject("lng", Double.class),
                rs.getString("availability"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("bio"),
                rs.getString("hobbies"),
                rs.getString("profession"),
                rs.getString("workplace"),
                rs.getBoolean("workplace_shared"),
                rs.getObject("profile_photo_asset_id", UUID.class),
                rs.getBoolean("onboarding_completed"),
                rs.getInt("onboarding_step"));
    }
}
