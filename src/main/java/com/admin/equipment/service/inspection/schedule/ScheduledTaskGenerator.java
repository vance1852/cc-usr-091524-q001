package com.admin.equipment.service.inspection.schedule;

import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.service.inspection.schedule.CycleScheduleCalculator.PeriodInstance;

/**
 * 调度器与任务生成之间的端口：由 {@code InspectionTaskService} 实现，
 * 使调度核心不依赖具体的任务组装/路线规划逻辑。
 */
public interface ScheduledTaskGenerator {

    /** 按指定周期实例生成任务（调用方已完成周期身份抢占与计划启用校验）。 */
    InspectionTask generateForPeriod(InspectionPlan plan, PeriodInstance period,
                                      Long ledgerId, String triggerSource);

    /** 按主键取任务，不存在则抛异常。 */
    InspectionTask getTask(Long taskId);
}
