package dev.authsandbox.authservice.repository;

import dev.authsandbox.authservice.entity.Challenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChallengeRepository extends JpaRepository<Challenge, UUID> {

    Optional<Challenge> findByNonce(String nonce);

    @Modifying
    @Query("DELETE FROM Challenge c WHERE c.expiresAt < :now")
    int deleteExpiredChallenges(OffsetDateTime now);
}
