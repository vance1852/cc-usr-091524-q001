package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 巡检计划调度台账：每一行代表“某个计划的某个周期实例”的唯一生成身份。
 * 通过 (plan_id, schedule_version, period_key) 唯一约束保证：
 * 正常轮询、多实例并发、重启补偿都只会产生一条记录、一个任务。
 *
 * status 取值：
 *  PENDING   已占用身份，任务生成中（崩溃后由启动扫描兜底重试）
 *  GENERATED 已生成任务，task_id 非空
 *  SKIPPED   确定性跳过，skip_reason 记录原因（DISABLED / BACKFILL_LIMIT）
 *  FAILED    生成失败，等待下一轮扫描重试
 */
@Entity
@Table(name = "inspection_schedule_ledger",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_schedule_identity",
                columnNames = {"plan_id", "schedule_version", "period_key"}))
public class InspectionScheduleLedger {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_GENERATED = "GENERATED";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_FAILED = "FAILED";

    /** 计划禁用期间到期，按规则不补发。 */
    public static final String REASON_DISABLED = "DISABLED";
    /** 漏发周期数超过补偿上限，更早的周期仅留痕。 */
    public static final String REASON_BACKFILL_LIMIT = "BACKFILL_LIMIT";

    public static final String SOURCE_STARTUP = "STARTUP";
    public static final String SOURCE_POLL = "POLL";
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_RESUME = "RESUME";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    /** 计划周期参数版本：cycleType/cycleValue/startTime 变更后递增，旧版本实例不再参与新序列。 */
    @Column(name = "schedule_version", nullable = false)
    private Integer scheduleVersion;

    /** 周期实例键，如 D20260917 / W20260914 / M202609 / S20260917T2000 / H20260917T0800。 */
    @Column(name = "period_key", nullable = false, length = 32)
    private String periodKey;

    @Column(name = "cycle_type", nullable = false, length = 16)
    private String cycleType;

    /** 周期在计划序列中的序号（k=0 为计划创建后的第一个周期）。 */
    @Column(name = "period_index")
    private Long periodIndex;

    /** 名义计划触发时刻（厂区本地墙钟时间）。 */
    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    /** 实际派发时刻。 */
    @Column(name = "fired_at")
    private LocalDateTime firedAt;

    @Column(nullable = false, length = 24)
    private String status = STATUS_PENDING;

    @Column(name = "skip_reason", length = 32)
    private String skipReason;

    @Column(name = "trigger_source", length = 16)
    private String triggerSource;

    @Column(name = "task_id")
    private Long taskId;

    @Column(length = 512)
    private String message;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 乐观锁：两个实例同时恢复同一条 PENDING/FAILED 时，败者事务（含任务插入）整体回滚。 */
    @Version
    private Long version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }
    public Integer getScheduleVersion() { return scheduleVersion; }
    public void setScheduleVersion(Integer scheduleVersion) { this.scheduleVersion = scheduleVersion; }
    public String getPeriodKey() { return periodKey; }
    public void setPeriodKey(String periodKey) { this.periodKey = periodKey; }
    public String getCycleType() { return cycleType; }
    public void setCycleType(String cycleType) { this.cycleType = cycleType; }
    public Long getPeriodIndex() { return periodIndex; }
    public void setPeriodIndex(Long periodIndex) { this.periodIndex = periodIndex; }
    public LocalDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(LocalDateTime scheduledAt) { this.scheduledAt = scheduledAt; }
    public LocalDateTime getFiredAt() { return firedAt; }
    public void setFiredAt(LocalDateTime firedAt) { this.firedAt = firedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSkipReason() { return skipReason; }
    public void setSkipReason(String skipReason) { this.skipReason = skipReason; }
    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
