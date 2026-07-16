package com.ghyinc.finance.global.kafka.dlq.repository;

import com.ghyinc.finance.global.kafka.dlq.entity.DlqEvent;
import com.ghyinc.finance.global.kafka.dlq.entity.DlqStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DlqEventRepository extends JpaRepository<DlqEvent, Long> {
    @Query("""
            SELECT t
            FROM DlqEvent t
            WHERE t.status IN :statuses
               AND t.nextRetryAt <= :now
            ORDER BY t.nextRetryAt
            LIMIT :limit
            """)
    List<DlqEvent> findRetryTarget(
            @Param("statuses") List<DlqStatus> statuses,
            @Param("now")LocalDateTime now,
            @Param("limit") int limit
    );
}
