package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 巡检调度的“周期生成实例”。
 *
 * <p>每个计划的每个应执行周期恰好对应一行：{@code occurrenceKey = planId + ":" + periodKey}，
 * 数据库唯一约束保证无论正常轮询、多实例并发还是重启补偿，都只会生成一条任务。
 *
 * <ul>
 *   <li>PENDING：周期已登记，尚未被认领；</li>
 *   <li>PROCESSING：已被某个调度实例认领（claim），宕机残留的过期认领可被再次抢占；</li>
 *   <li>GENERATED：任务已生成，{@code taskId} 指向任务；</li>
 *   <li>SKIPPED：确定不生成，{@code skipReason} 记录原因（窗口已过期、禁用期不补发、补齐上限等）；</li>
 *   <li>FAILED：生成失败（如下游异常），{@code attemptCount} 未达上限时后续轮询重试。</li>
 * </ul>
 */
@Entity
@Table(name = "inspection_schedule_occurrences",
        uniqueConstraints = @UniqueConstraint(name = "uk_schedule_occurrence_key",
                columnNames = "occurrence_key"),
        indexes = {
                @Index(name = "idx_occ_plan_status", columnList = "plan_id,status"),
                @Index(name = "idx_occ_period", columnList = "period_key")
        })
public class InspectionScheduleOccurrence {

    public static final String PENDING = "pending";
    public static final String PROCESSING = "processing";
    public static final String GENERATED = "generated";
    public static final String SKIPPED = "skipped";
    public static final String FAILED = "failed";

    /** 窗口结束时间已过，自动调度不再生成（可由管理员显式补齐）。 */
    public static final String REASON_WINDOW_EXPIRED = "window_expired";
    /** 计划禁用期间的周期，按规则不补发。 */
    public static final String REASON_PLAN_DISABLED = "plan_disabled";
    /** 重新启用时选择“从当前周期继续”，禁用期历史周期标记跳过。 */
    public static final String REASON_DISABLED_NO_BACKFILL = "disabled_no_backfill";
    /** 重新启用/补齐时超出管理员给定的补齐上限。 */
    public static final String REASON_BACKFILL_LIMIT = "backfill_limit";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    /** 周期业务键，如 20260917 / 2026W38 / 202609 / 202609171400 / 20260917-NIGHT。 */
    @Column(name = "period_key", nullable = false, length = 32)
    private String periodKey;

    /** 全局唯一生成身份：planId:periodKey。 */
    @Column(name = "occurrence_key", nullable = false, length = 64)
    private String occurrenceKey;

    @Column(name = "cycle_type", nullable = false, length = 16)
    private String cycleType;

    @Column(name = "scheduled_start", nullable = false)
    private LocalDateTime scheduledStart;

    @Column(name = "scheduled_end", nullable = false)
    private LocalDateTime scheduledEnd;

    @Column(nullable = false, length = 16)
    private String status = PENDING;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "task_code", length = 64)
    private String taskCode;

    /** scheduler（轮询/重启补偿）、manual（管理员立即执行）、backfill（显式补齐）。 */
    @Column(name = "trigger_source", length = 16)
    private String triggerSource;

    @Column(name = "skip_reason", length = 32)
    private String skipReason;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "claimed_by", length = 64)
    private String claimedBy;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }
    public String getPeriodKey() { return periodKey; }
    public void setPeriodKey(String periodKey) { this.periodKey = periodKey; }
    public String getOccurrenceKey() { return occurrenceKey; }
    public void setOccurrenceKey(String occurrenceKey) { this.occurrenceKey = occurrenceKey; }
    public String getCycleType() { return cycleType; }
    public void setCycleType(String cycleType) { this.cycleType = cycleType; }
    public LocalDateTime getScheduledStart() { return scheduledStart; }
    public void setScheduledStart(LocalDateTime scheduledStart) { this.scheduledStart = scheduledStart; }
    public LocalDateTime getScheduledEnd() { return scheduledEnd; }
    public void setScheduledEnd(LocalDateTime scheduledEnd) { this.scheduledEnd = scheduledEnd; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getTaskCode() { return taskCode; }
    public void setTaskCode(String taskCode) { this.taskCode = taskCode; }
    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }
    public String getSkipReason() { return skipReason; }
    public void setSkipReason(String skipReason) { this.skipReason = skipReason; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public String getClaimedBy() { return claimedBy; }
    public void setClaimedBy(String claimedBy) { this.claimedBy = claimedBy; }
    public LocalDateTime getClaimedAt() { return claimedAt; }
    public void setClaimedAt(LocalDateTime claimedAt) { this.claimedAt = claimedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
