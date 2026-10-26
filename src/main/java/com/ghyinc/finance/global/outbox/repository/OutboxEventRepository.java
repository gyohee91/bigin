package com.ghyinc.finance.global.outbox.repository;

import com.ghyinc.finance.global.outbox.entity.OutboxEvent;
import com.ghyinc.finance.global.outbox.entity.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * 발행 성공 처리 - 엔티티를 SELECT 하지 않고 id 기준 UPDATE 1건으로 끝낸다.
     * (save()는 detached 엔티티 merge를 위해 SELECT 후 UPDATE 를 수행한다)
     *
     * @return 갱신된 행 수
     */
    @Transactional
    @Modifying
    @Query("UPDATE OutboxEvent e " +
            "SET e.status = com.ghyinc.finance.global.outbox.entity.OutboxStatus.PUBLISHED, " +
            "    e.publishedAt = :now, " +
            "    e.updatedAt = :now " +
            "WHERE e.id = :id")
    int markAsPublished(@Param("id") Long id, @Param("now") LocalDateTime now);
}
