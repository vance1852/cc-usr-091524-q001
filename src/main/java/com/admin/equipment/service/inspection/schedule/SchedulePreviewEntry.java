package com.admin.equipment.service.inspection.schedule;

import com.admin.equipment.model.inspection.InspectionScheduleLedger;

import java.time.LocalDateTime;

/** 调度预览行：描述某个周期实例的计划时刻与当前生成状态。 */
public record SchedulePreviewEntry(
        Long ledgerId,
        long periodIndex,
        String periodKey,
        String cycleType,
        LocalDateTime scheduledAt,
        String status,
        Long taskId,
        String triggerSource,
        String skipReason,
        String message) {

    public static SchedulePreviewEntry of(long index, CycleScheduleCalculator.PeriodInstance pi,
                                           InspectionScheduleLedger l) {
        if (l == null) {
            return new SchedulePreviewEntry(null, index, pi.key(), pi.cycleType(), pi.scheduledAt(),
                    "DUE", null, null, null, "尚未生成");
        }
        return new SchedulePreviewEntry(l.getId(), index, l.getPeriodKey(), l.getCycleType(),
                l.getScheduledAt(), l.getStatus(), l.getTaskId(), l.getTriggerSource(),
                l.getSkipReason(), l.getMessage());
    }
}
