package com.admin.equipment.service.inspection.schedule;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 巡检自动调度参数（前缀 app.schedule）。
 */
@Component
@ConfigurationProperties(prefix = "app.schedule")
public class ScheduleProperties {

    /** 总开关：false 时不注册定时轮询（仍可手工调用管理接口）。 */
    private boolean enabled = true;
    /** 轮询间隔（毫秒）。 */
    private long pollIntervalMs = 60_000;
    /** 启动后是否立即执行一次补偿扫描。 */
    private boolean runOnStartup = true;
    /** 重启后自动补扫的历史上限（小时），超过的历史不自动登记，需管理员显式补齐。 */
    private int recoveryHours = 72;
    /** 窗口结束后仍允许自动生成的宽限分钟数（应对短暂停机/重启）；再晚则登记为 window_expired。 */
    private int lateGenerateMinutes = 180;
    /** processing 认领超过该分钟数视为宕机残留，可被其他实例抢占。 */
    private int claimStaleMinutes = 5;
    /** 单个周期生成失败后的最大尝试次数，达到后保留 failed 等待人工处理。 */
    private int maxAttempts = 5;
    /** 单次轮询最多处理的待办行数，防止长期停机后洪峰。 */
    private int batchSize = 200;
    /** 重新启用时补齐周期数的默认/硬上限。 */
    private int backfillLimitDefault = 50;
    private int backfillLimitMax = 500;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    public boolean isRunOnStartup() { return runOnStartup; }
    public void setRunOnStartup(boolean runOnStartup) { this.runOnStartup = runOnStartup; }
    public int getRecoveryHours() { return recoveryHours; }
    public void setRecoveryHours(int recoveryHours) { this.recoveryHours = recoveryHours; }
    public int getLateGenerateMinutes() { return lateGenerateMinutes; }
    public void setLateGenerateMinutes(int lateGenerateMinutes) { this.lateGenerateMinutes = lateGenerateMinutes; }
    public int getClaimStaleMinutes() { return claimStaleMinutes; }
    public void setClaimStaleMinutes(int claimStaleMinutes) { this.claimStaleMinutes = claimStaleMinutes; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getBackfillLimitDefault() { return backfillLimitDefault; }
    public void setBackfillLimitDefault(int backfillLimitDefault) { this.backfillLimitDefault = backfillLimitDefault; }
    public int getBackfillLimitMax() { return backfillLimitMax; }
    public void setBackfillLimitMax(int backfillLimitMax) { this.backfillLimitMax = backfillLimitMax; }
}
