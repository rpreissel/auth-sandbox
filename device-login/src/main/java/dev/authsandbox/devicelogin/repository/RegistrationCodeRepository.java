package dev.authsandbox.devicelogin.repository;

import dev.authsandbox.devicelogin.entity.RegistrationCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RegistrationCodeRepository extends JpaRepository<RegistrationCode, UUID> {

    Optional<RegistrationCode> findByUserId(String userId);
}
