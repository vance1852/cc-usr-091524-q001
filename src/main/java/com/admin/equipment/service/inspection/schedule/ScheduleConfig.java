package com.admin.equipment.service.inspection.schedule;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;

/**
 * 调度组件装配。厂区时区由 app.schedule.zone 显式指定（默认 Asia/Shanghai），
 * 避免依赖部署机器的默认时区。
 */
@Configuration
@EnableScheduling
public class ScheduleConfig {

    @Bean
    public ScheduleClock scheduleClock(@Value("${app.schedule.zone:Asia/Shanghai}") String zone) {
        return new ScheduleClock(ZoneId.of(zone));
    }

    @Bean
    public CycleScheduleCalculator cycleScheduleCalculator(ScheduleClock clock) {
        return new CycleScheduleCalculator(clock.getZone());
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}
