package com.gustavoronchi.microsservico_pedido.domain.repository;

import com.gustavoronchi.microsservico_pedido.domain.entities.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Transactional(readOnly = true)
    List<OutboxEvent> findByPublishedAtIsNullOrderByOccurredAtAsc(Pageable pageable);

    @Transactional
    @Modifying
    @Query("update OutboxEvent e set e.publishedAt = :publishedAt where e.eventId = :eventId and e.publishedAt is null")
    int markPublished(@Param("eventId") UUID eventId, @Param("publishedAt") Instant publishedAt);
}
