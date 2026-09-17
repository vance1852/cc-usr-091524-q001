package com.admin.equipment.service.inspection.schedule;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 调度时钟：所有周期计算与任务生成时间均从此处获取，便于在集成测试中冻结/拨快时间。
 * 默认使用系统时钟与 {@link ZoneId#systemDefault()}，可通过配置 {@code app.schedule.zone}
 * 固定厂区所在时区（建议生产显式配置，例如 Asia/Shanghai）。
 */
public class ScheduleClock {

    private final ZoneId zone;
    private volatile Clock clock;

    public ScheduleClock(ZoneId zone) {
        this.zone = zone;
        this.clock = Clock.system(zone);
    }

    public ZoneId getZone() {
        return zone;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 供测试或运维工具使用：把时钟固定/拨到指定时刻。 */
    public void setFixed(LocalDateTime time) {
        this.clock = Clock.fixed(time.atZone(zone).toInstant(), zone);
    }

    /** 恢复为系统时钟。 */
    public void resetSystem() {
        this.clock = Clock.system(zone);
    }
}
