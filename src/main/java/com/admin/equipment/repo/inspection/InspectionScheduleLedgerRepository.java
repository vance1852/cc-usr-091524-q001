package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.InspectionScheduleLedger;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InspectionScheduleLedgerRepository extends JpaRepository<InspectionScheduleLedger, Long> {

    Optional<InspectionScheduleLedger> findByPlanIdAndScheduleVersionAndPeriodKey(
            Long planId, Integer scheduleVersion, String periodKey);

    List<InspectionScheduleLedger> findByPlanIdOrderByScheduledAtDesc(Long planId, Pageable pageable);

    List<InspectionScheduleLedger> findByPlanIdAndScheduleVersionOrderByScheduledAtDesc(
            Long planId, Integer scheduleVersion, Pageable pageable);

    List<InspectionScheduleLedger> findByPlanIdAndStatusAndCreatedAtBefore(
            Long planId, String status, LocalDateTime before);

    List<InspectionScheduleLedger> findAllByOrderByScheduledAtDesc(Pageable pageable);

    Optional<InspectionScheduleLedger> findByTaskId(Long taskId);
}
