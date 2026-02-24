package dev.authsandbox.authservice.repository;

import dev.authsandbox.authservice.entity.TransferSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransferSessionRepository extends JpaRepository<TransferSession, UUID> {

    Optional<TransferSession> findBySessionId(String sessionId);

    @Modifying
    @Query("DELETE FROM TransferSession ts WHERE ts.expiresAt < :now")
    int deleteExpiredSessions(OffsetDateTime now);
}
