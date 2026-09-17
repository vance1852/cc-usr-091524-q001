package com.admin.equipment.service.inspection.schedule;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 巡检调度轮询器。{@link #fixedDelayString()} 用固定延迟而非固定频率，
 * 保证上一轮（含重启补偿扫描）结束后才开始下一轮，避免同实例重叠。
 */
@Component
public class InspectionSchedulePoller {

    private static final Logger log = LoggerFactory.getLogger(InspectionSchedulePoller.class);

    private final InspectionScheduleService service;
    private final ScheduleProperties props;

    public InspectionSchedulePoller(InspectionScheduleService service, ScheduleProperties props) {
        this.service = service;
        this.props = props;
    }

    @PostConstruct
    public void startupRecovery() {
        if (props.isEnabled() && props.isRunOnStartup()) {
            log.info("巡检调度启动恢复扫描开始");
            try {
                service.tick();
            } catch (Exception e) {
                log.error("启动恢复扫描失败，将在下次轮询重试: {}", e.getMessage(), e);
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.schedule.poll-interval-ms:60000}")
    public void poll() {
        if (!props.isEnabled()) return;
        service.tick();
    }
}
