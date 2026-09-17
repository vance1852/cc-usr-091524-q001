package com.admin.equipment.service.inspection.schedule;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 系统统一时钟。生产环境读取真实 UTC 时钟；集成测试可通过 {@link #setFixed(Instant)} /
 * {@link #advanceMillis(long)} 控制时间，验证重启补偿、窗口过期等场景。
 */
@Component
public class ScheduleClock {

    private final ZoneId zone;
    private volatile Clock clock;

    public ScheduleClock(ScheduleEngine engine) {
        this.zone = engine.getZone();
        this.clock = Clock.system(zone);
    }

    public Instant now() {
        return Instant.now(clock);
    }

    public ZoneId getZone() {
        return zone;
    }

    /** 测试用：将时钟固定在某一时刻（按调度时区解释）。 */
    public void setFixed(Instant instant) {
        this.clock = Clock.fixed(instant, zone);
    }

    /** 测试用：相对当前固定时刻推进。 */
    public Instant advanceMillis(long millis) {
        Instant next = now().plusMillis(millis);
        this.clock = Clock.fixed(next, zone);
        return next;
    }

    /** 测试用：恢复系统时钟。 */
    public void reset() {
        this.clock = Clock.system(zone);
    }
}
