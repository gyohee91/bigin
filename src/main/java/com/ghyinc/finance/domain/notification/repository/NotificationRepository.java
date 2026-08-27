package com.ghyinc.finance.domain.notification.repository;

import com.ghyinc.finance.domain.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("UPDATE Notification t SET t.status = com.ghyinc.finance.domain.notification.enums.NotificationStatus.SENDING " +
            "WHERE t.id = :id AND t.status = com.ghyinc.finance.domain.notification.enums.NotificationStatus.PENDING")
     int claimForSending(@Param("id") Long id);
}
