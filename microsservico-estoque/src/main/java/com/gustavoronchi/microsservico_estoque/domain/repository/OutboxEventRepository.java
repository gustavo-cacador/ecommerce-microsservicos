package com.gustavoronchi.microsservico_estoque.domain.repository;

import com.gustavoronchi.microsservico_estoque.domain.entities.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Transactional(readOnly = true)
    Optional<OutboxEvent> findBySourceEventId(UUID sourceEventId);
}
