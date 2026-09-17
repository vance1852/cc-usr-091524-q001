package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.InspectionScheduleOccurrence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InspectionScheduleOccurrenceRepository extends JpaRepository<InspectionScheduleOccurrence, Long> {

    Optional<InspectionScheduleOccurrence> findByOccurrenceKey(String occurrenceKey);

    boolean existsByOccurrenceKey(String occurrenceKey);

    List<InspectionScheduleOccurrence> findByPlanIdOrderByScheduledStartDesc(Long planId);

    List<InspectionScheduleOccurrence> findByTaskId(Long taskId);

    /**
     * 原子认领：只有待处理 / 失败可重试 / 认领超时的行会被更新（affected rows = 1）。
     * 依赖行锁，保证多实例并发、轮询重叠时只有一个调度实例拿到生成权。
     */
    @Modifying
    @Query("update InspectionScheduleOccurrence o set o.status = 'processing', "
            + "o.claimedBy = :instanceId, o.claimedAt = :now, o.updatedAt = :now, "
            + "o.attemptCount = o.attemptCount + 1 "
            + "where o.id = :id and (o.status = 'pending' "
            + "or (o.status = 'failed' and o.attemptCount < :maxAttempts) "
            + "or (o.status = 'processing' and o.claimedAt < :staleBefore))")
    int claimIfDue(@Param("id") Long id,
                   @Param("instanceId") String instanceId,
                   @Param("now") LocalDateTime now,
                   @Param("staleBefore") LocalDateTime staleBefore,
                   @Param("maxAttempts") int maxAttempts);

    @Query("select o from InspectionScheduleOccurrence o where o.planId = :planId "
            + "and o.scheduledStart >= :from and o.scheduledStart < :to order by o.scheduledStart asc")
    List<InspectionScheduleOccurrence> findWindow(@Param("planId") Long planId,
                                                  @Param("from") LocalDateTime from,
                                                  @Param("to") LocalDateTime to);

    /**
     * 重启恢复：列出可认领的待办、失败但未达最大尝试次数的行，以及宕机残留的 processing 行。
     * 限制条数避免长时间停机后一次性加载过多。
     */
    @Query("select o from InspectionScheduleOccurrence o where o.status = 'pending' "
            + "or (o.status = 'failed' and o.attemptCount < :maxAttempts) "
            + "or (o.status = 'processing' and o.claimedAt < :staleBefore) "
            + "order by o.scheduledStart asc")
    List<InspectionScheduleOccurrence> findRecoverable(@Param("staleBefore") LocalDateTime staleBefore,
                                                       @Param("maxAttempts") int maxAttempts,
                                                       org.springframework.data.domain.Pageable pageable);
}
