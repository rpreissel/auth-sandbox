package dev.authsandbox.authservice.repository;

import dev.authsandbox.authservice.entity.CmsPage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CmsPageRepository extends JpaRepository<CmsPage, UUID> {
    Optional<CmsPage> findByKey(String key);
}
