package com.admin.equipment.service.inspection.schedule;

import com.admin.equipment.model.inspection.InspectionScheduleLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 调度触发器：
 *  - 启动后执行一次补偿扫描（STARTUP），补发停机期间漏发、恢复残留 PENDING；
 *  - 之后按固定间隔轮询（POLL）。
 *
 * 多实例部署时各节点可同时轮询：周期身份由数据库唯一索引裁决，
 * 抢占失败者直接跳过，因此并发不会产生重复任务，仅存在极少量无效抢占。
 */
@Component
public class InspectionSchedulePoller {

    private static final Logger log = LoggerFactory.getLogger(InspectionSchedulePoller.class);

    private final InspectionScheduleService scheduleService;

    @Value("${app.schedule.enabled:true}")
    private boolean scheduleEnabled;

    public InspectionSchedulePoller(InspectionScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!scheduleEnabled) {
            log.info("自动调度已关闭(app.schedule.enabled=false)，跳过启动补偿");
            return;
        }
        runSafely(InspectionScheduleLedger.SOURCE_STARTUP, "启动补偿");
    }

    @Scheduled(fixedDelayString = "${app.schedule.poll-interval-ms:60000}",
            initialDelayString = "${app.schedule.poll-initial-delay-ms:15000}")
    public void poll() {
        if (!scheduleEnabled) return;
        runSafely(InspectionScheduleLedger.SOURCE_POLL, "轮询");
    }

    private void runSafely(String source, String label) {
        try {
            InspectionScheduleService.SweepResult r = scheduleService.sweep(source);
            log.info("调度{}完成：扫描{}个计划，生成{}，跳过{}，恢复{}",
                    label, r.plansScanned(), r.generated(), r.skipped(), r.recovered());
        } catch (Exception e) {
            log.error("调度{}异常", label, e);
        }
    }
}
