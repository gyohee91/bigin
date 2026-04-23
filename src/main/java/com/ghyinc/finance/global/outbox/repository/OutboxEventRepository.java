package com.ghyinc.finance.global.outbox.repository;

import com.ghyinc.finance.global.outbox.entity.OutboxEvent;
import com.ghyinc.finance.global.outbox.entity.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 재시도 대상 조회 (5분 이상 PENDING)
     */
    @Query("SELECT t " +
            "FROM OutboxEvent t " +
            "WHERE t.status = :status " +
            "   AND t.createdAt < :threshold " +
            "ORDER BY t.createdAt ASC " +
            "LIMIT :limit")
    List<OutboxEvent> findRetryTargets(
            @Param("status") OutboxStatus status,
            @Param("threshold") LocalDateTime threshold,
            @Param("limit") int limit
    );
}
