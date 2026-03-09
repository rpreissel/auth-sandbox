package dev.authsandbox.authservice.repository;

import dev.authsandbox.authservice.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceRepository extends JpaRepository<Device, UUID> {

    Optional<Device> findByPublicKeyHash(String publicKeyHash);

    boolean existsByUserIdAndDeviceName(String userId, String deviceName);
}
