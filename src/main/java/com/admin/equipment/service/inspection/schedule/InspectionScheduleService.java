package com.admin.equipment.service.inspection.schedule;

import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionScheduleOccurrence;
import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.repo.inspection.InspectionPlanRepository;
import com.admin.equipment.repo.inspection.InspectionScheduleOccurrenceRepository;
import com.admin.equipment.service.inspection.InspectionTaskService;
import com.admin.equipment.service.inspection.schedule.ScheduleEngine.PlannedOccurrence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.management.ManagementFactory;
import java.time.*;
import java.util.*;

/**
 * 可恢复的巡检任务自动调度。
 *
 * <p>身份模型：{@code occurrenceKey = planId + ":" + periodKey} 落库并带唯一约束，
 * 周期由 {@link ScheduleEngine} 纯函数计算。注册走“先查后插 + 唯一约束兜底”，
 * 生成走行级原子认领（{@code claimIfDue}），每一步都是独立事务（REQUIRES_NEW），因此：
 * <ul>
 *   <li>正常轮询重复执行 → 已存在/已认领的周期直接跳过；</li>
 *   <li>多实例并发 → 只有一个实例 UPDATE 认领成功（affected=1）；</li>
 *   <li>重启补偿 → 启动即扫描 recoveryHours 内缺口，宕机残留的 processing 超时后可被抢占；
 *       崩溃发生在“认领后/任务提交前”任一点，结果都是可重放且不重复的。</li>
 * </ul>
 */
@Service
public class InspectionScheduleService {

    private static final Logger log = LoggerFactory.getLogger(InspectionScheduleService.class);

    public static final String SOURCE_SCHEDULER = "scheduler";
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_BACKFILL = "backfill";

    private static final String GENERATED = InspectionScheduleOccurrence.GENERATED;
    private static final String SKIPPED = InspectionScheduleOccurrence.SKIPPED;
    private static final String REASON_WINDOW_EXPIRED = InspectionScheduleOccurrence.REASON_WINDOW_EXPIRED;
    private static final String REASON_PLAN_DISABLED = InspectionScheduleOccurrence.REASON_PLAN_DISABLED;

    private final ScheduleEngine engine;
    private final ScheduleClock clock;
    private final ScheduleProperties props;
    private final InspectionPlanRepository planRepo;
    private final InspectionScheduleOccurrenceRepository occRepo;
    private final InspectionTaskService taskService;
    private final InspectionScheduleService self;

    private final String instanceId = ManagementFactory.getRuntimeMXBean().getName()
            + "-" + UUID.randomUUID().toString().substring(0, 8);

    public InspectionScheduleService(ScheduleEngine engine, ScheduleClock clock, ScheduleProperties props,
                                      InspectionPlanRepository planRepo,
                                      InspectionScheduleOccurrenceRepository occRepo,
                                      InspectionTaskService taskService,
                                      @Lazy InspectionScheduleService self) {
        this.engine = engine;
        this.clock = clock;
        this.props = props;
        this.planRepo = planRepo;
        this.occRepo = occRepo;
        this.taskService = taskService;
        this.self = self;
    }

    public String getInstanceId() {
        return instanceId;
    }

    // ==================================================================
    // DTO
    // ==================================================================
    public record OccurrenceView(Long id, Long planId, String planCode, String planName,
                                  String periodKey, String occurrenceKey, String cycleType,
                                  LocalDateTime scheduledStart, LocalDateTime scheduledEnd,
                                  String status, Long taskId, String taskCode, String triggerSource,
                                  String skipReason, String skipReasonText, Integer attemptCount,
                                  String claimedBy, LocalDateTime claimedAt, LocalDateTime generatedAt,
                                  String errorMessage) {
        static OccurrenceView of(InspectionScheduleOccurrence o, InspectionPlan p) {
            return new OccurrenceView(o.getId(), o.getPlanId(),
                    p != null ? p.getCode() : null, p != null ? p.getName() : null,
                    o.getPeriodKey(), o.getOccurrenceKey(), o.getCycleType(),
                    o.getScheduledStart(), o.getScheduledEnd(), o.getStatus(),
                    o.getTaskId(), o.getTaskCode(), o.getTriggerSource(),
                    o.getSkipReason(), describeSkipReason(o.getSkipReason()),
                    o.getAttemptCount(), o.getClaimedBy(), o.getClaimedAt(),
                    o.getGeneratedAt(), o.getErrorMessage());
        }
    }

    public record PreviewItem(String periodKey, String cycleType,
                               LocalDateTime scheduledStart, LocalDateTime scheduledEnd,
                               boolean registered, String status, Long taskId,
                               String skipReason, String skipReasonText) {}

    public record TickResult(int scannedPlans, int registered, int generated, int skippedExpired,
                              int recovered, int failed, List<Long> taskIds) {
        static Builder builder() { return new Builder(); }
        static class Builder {
            int scannedPlans, registered, generated, skippedExpired, recovered, failed;
            final List<Long> taskIds = new ArrayList<>();
            TickResult build() {
                return new TickResult(scannedPlans, registered, generated, skippedExpired,
                        recovered, failed, List.copyOf(taskIds));
            }
        }
    }

    public record TaskTrace(InspectionTask task, OccurrenceView occurrence, String note) {}

    // ==================================================================
    // 轮询入口（定时任务 / 启动恢复 / 手工触发共用）
    // ==================================================================

    /** 编排方法，自身不开事务；每个写动作都是独立提交的事务，崩在任何一步都可重放。 */
    public TickResult tick() {
        TickResult.Builder b = TickResult.builder();
        Instant now = clock.now();
        LocalDateTime nowLdt = LocalDateTime.ofInstant(now, engine.getZone());
        log.debug("调度轮询开始 instance={} now={}", instanceId, nowLdt);

        int recoveryHours = Math.max(1, props.getRecoveryHours());
        Instant horizonFrom = now.minus(Duration.ofHours(recoveryHours));
        Instant horizonTo = now.plusMillis(Math.max(props.getPollIntervalMs() * 2, 120_000));

        List<InspectionPlan> plans = planRepo.findByEnabledTrueOrderByCodeAsc();
        b.scannedPlans = plans.size();
        for (InspectionPlan plan : plans) {
            try {
                scanPlan(plan, horizonFrom, horizonTo, nowLdt, b);
            } catch (Exception e) {
                log.error("计划 {} 调度扫描失败: {}", plan.getCode(), e.getMessage(), e);
                b.failed++;
            }
        }
        recoverStaleClaims(nowLdt, b);
        TickResult r = b.build();
        log.info("调度轮询完成: 计划{} 新登记{} 生成{} 过期跳过{} 恢复认领{} 失败{} 任务{}",
                r.scannedPlans(), r.registered(), r.generated(), r.skippedExpired(),
                r.recovered(), r.failed(), r.taskIds());
        return r;
    }

    private void scanPlan(InspectionPlan plan, Instant horizonFrom, Instant horizonTo,
                           LocalDateTime nowLdt, TickResult.Builder b) {
        List<PlannedOccurrence> due = engine.occurrencesBetween(plan, horizonFrom, horizonTo);
        if (due.isEmpty()) return;

        // 单次区间查询取回已登记周期，避免高频周期（hourly）逐周期 SELECT
        LocalDateTime fromLdt = LocalDateTime.ofInstant(horizonFrom, engine.getZone());
        LocalDateTime toLdt = LocalDateTime.ofInstant(horizonTo, engine.getZone());
        Map<String, InspectionScheduleOccurrence> existing = new HashMap<>();
        for (InspectionScheduleOccurrence o : occRepo.findWindow(plan.getId(), fromLdt, toLdt)) {
            existing.put(o.getPeriodKey(), o);
        }

        LocalDateTime expireLine = nowLdt.minusMinutes(Math.max(0, props.getLateGenerateMinutes()));

        for (PlannedOccurrence po : due) {
            InspectionScheduleOccurrence occ = existing.get(po.periodKey());
            if (occ == null) {
                occ = self.registerOne(plan, po);
                if (occ == null) {
                    occ = occRepo.findByOccurrenceKey(occurrenceKey(plan.getId(), po.periodKey())).orElse(null);
                }
                if (occ != null) b.registered++;
            }
            if (occ == null) continue;

            if (po.scheduledStart().isAfter(nowLdt)) continue; // 未到点，保持 pending
            String st = occ.getStatus();
            if (GENERATED.equals(st) || SKIPPED.equals(st)) continue;
            if (isTerminalFailure(occ)) continue; // 达到最大尝试次数，保留 failed 等待人工处理

            if (po.scheduledEnd().isBefore(expireLine)) {
                if (self.markSkipped(occ.getId(), REASON_WINDOW_EXPIRED, nowLdt)) b.skippedExpired++;
                continue;
            }
            if (claimAndGenerate(plan, occ, SOURCE_SCHEDULER, nowLdt, b)) b.generated++;
        }
    }

    /** 扫描宕机/事务中途残留的 processing，以及失败待重试的周期。 */
    private void recoverStaleClaims(LocalDateTime nowLdt, TickResult.Builder b) {
        LocalDateTime staleBefore = nowLdt.minusMinutes(Math.max(1, props.getClaimStaleMinutes()));
        List<InspectionScheduleOccurrence> stale = occRepo.findRecoverable(
                staleBefore, Math.max(1, props.getMaxAttempts()),
                PageRequest.of(0, Math.max(1, props.getBatchSize())));
        for (InspectionScheduleOccurrence occ : stale) {
            Optional<InspectionPlan> planOpt = planRepo.findById(occ.getPlanId());
            if (planOpt.isEmpty() || Boolean.FALSE.equals(planOpt.get().getEnabled())) {
                self.markSkipped(occ.getId(), REASON_PLAN_DISABLED, nowLdt);
                continue;
            }
            if (occ.getScheduledEnd() != null && occ.getScheduledEnd().isBefore(
                    nowLdt.minusMinutes(Math.max(0, props.getLateGenerateMinutes())))) {
                if (self.markSkipped(occ.getId(), REASON_WINDOW_EXPIRED, nowLdt)) b.skippedExpired++;
                continue;
            }
            if (claimAndGenerate(planOpt.get(), occ, SOURCE_SCHEDULER, nowLdt, b)) {
                b.recovered++;
                b.generated++;
            }
        }
    }

    private boolean claimAndGenerate(InspectionPlan plan, InspectionScheduleOccurrence occ, String source,
                                      LocalDateTime nowLdt, TickResult.Builder b) {
        if (!self.claimOccurrence(occ.getId(), nowLdt)) return false; // 已被并发实例认领
        try {
            InspectionTask task = self.generateClaimed(occ.getId(), source, nowLdt);
            b.taskIds.add(task.getId());
            return true;
        } catch (Exception e) {
            log.error("周期 {} 生成任务失败: {}", occ.getOccurrenceKey(), e.getMessage(), e);
            self.markFailed(occ.getId(), e.getMessage(), nowLdt);
            b.failed++;
            return false;
        }
    }

    // ==================================================================
    // 单步事务原语（REQUIRES_NEW：各自独立提交，崩溃可重放）
    // ==================================================================

    /** 先查后插；并发唯一约束冲突时返回 null（交由调用方重读）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InspectionScheduleOccurrence registerOne(InspectionPlan plan, PlannedOccurrence po) {
        String key = occurrenceKey(plan.getId(), po.periodKey());
        Optional<InspectionScheduleOccurrence> existing = occRepo.findByOccurrenceKey(key);
        if (existing.isPresent()) return existing.get();
        InspectionScheduleOccurrence o = new InspectionScheduleOccurrence();
        o.setPlanId(plan.getId());
        o.setPeriodKey(po.periodKey());
        o.setOccurrenceKey(key);
        o.setCycleType(po.cycleType());
        o.setScheduledStart(po.scheduledStart());
        o.setScheduledEnd(po.scheduledEnd());
        o.setStatus(InspectionScheduleOccurrence.PENDING);
        o.setAttemptCount(0);
        LocalDateTime nowLdt = LocalDateTime.ofInstant(clock.now(), engine.getZone());
        o.setCreatedAt(nowLdt);
        o.setUpdatedAt(nowLdt);
        try {
            return occRepo.saveAndFlush(o);
        } catch (DataIntegrityViolationException dup) {
            return null;
        }
    }

    /** 原子认领：行锁 + 状态条件，affected=1 才拿到生成权。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimOccurrence(Long occurrenceId, LocalDateTime nowLdt) {
        LocalDateTime staleBefore = nowLdt.minusMinutes(Math.max(1, props.getClaimStaleMinutes()));
        return occRepo.claimIfDue(occurrenceId, instanceId, nowLdt, staleBefore,
                Math.max(1, props.getMaxAttempts())) == 1;
    }

    private boolean isTerminalFailure(InspectionScheduleOccurrence occ) {
        return InspectionScheduleOccurrence.FAILED.equals(occ.getStatus())
                && (occ.getAttemptCount() == null ? 0 : occ.getAttemptCount())
                >= Math.max(1, props.getMaxAttempts());
    }

    /** 认领后生成任务并回写身份，单事务提交；使用计划默认指派。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InspectionTask generateClaimed(Long occurrenceId, String source, LocalDateTime nowLdt) {
        return generateClaimed(occurrenceId, source, nowLdt, null, "", true, null);
    }

    /** 认领后生成任务并回写身份，单事务提交；可指定指派人、路线方式与起点。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InspectionTask generateClaimed(Long occurrenceId, String source, LocalDateTime nowLdt,
                                            Long assigneeId, String assigneeName,
                                            boolean useOptimizedRoute, Long startPointId) {
        InspectionScheduleOccurrence occ = occRepo.findById(occurrenceId)
                .orElseThrow(() -> new IllegalStateException("调度周期不存在: " + occurrenceId));
        if (!InspectionScheduleOccurrence.PROCESSING.equals(occ.getStatus())) {
            throw new IllegalStateException("周期未处于认领状态: " + occ.getOccurrenceKey());
        }
        InspectionPlan plan = planRepo.findById(occ.getPlanId())
                .orElseThrow(() -> new IllegalStateException("计划不存在: " + occ.getPlanId()));
        if (Boolean.FALSE.equals(plan.getEnabled())) {
            throw new IllegalStateException("计划已禁用: " + plan.getCode());
        }
        Long resolvedAssignee = assigneeId != null ? assigneeId : firstAssignee(plan.getAssigneeIds());
        InspectionTask task = taskService.generateTaskForWindow(plan, resolvedAssignee,
                assigneeName == null ? "" : assigneeName,
                useOptimizedRoute, startPointId,
                occ.getScheduledStart(), occ.getScheduledEnd(), nowLdt,
                occ.getId(), occ.getPeriodKey(), source, null);

        occ.setStatus(InspectionScheduleOccurrence.GENERATED);
        occ.setTaskId(task.getId());
        occ.setTaskCode(task.getCode());
        occ.setTriggerSource(source);
        occ.setGeneratedAt(nowLdt);
        occ.setErrorMessage(null);
        occ.setUpdatedAt(nowLdt);
        occRepo.save(occ);
        return task;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSkipped(Long occurrenceId, String reason, LocalDateTime nowLdt) {
        return occRepo.findById(occurrenceId).map(o -> {
            if (InspectionScheduleOccurrence.GENERATED.equals(o.getStatus())) return false;
            o.setStatus(InspectionScheduleOccurrence.SKIPPED);
            o.setSkipReason(reason);
            o.setUpdatedAt(nowLdt);
            occRepo.save(o);
            return true;
        }).orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long occurrenceId, String error, LocalDateTime nowLdt) {
        occRepo.findById(occurrenceId).ifPresent(o -> {
            int attempts = (o.getAttemptCount() == null ? 0 : o.getAttemptCount());
            if (attempts >= Math.max(1, props.getMaxAttempts())) {
                o.setStatus(InspectionScheduleOccurrence.FAILED);
                o.setErrorMessage(truncate(error, 500));
            } else {
                // 回到 pending 等下轮重试；认领计数已在 claim 时累加
                o.setStatus(InspectionScheduleOccurrence.PENDING);
                o.setErrorMessage(truncate(error, 500));
            }
            o.setClaimedBy(null);
            o.setClaimedAt(null);
            o.setUpdatedAt(nowLdt);
            occRepo.save(o);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reopenForBackfill(Long occurrenceId, LocalDateTime nowLdt) {
        occRepo.findById(occurrenceId).ifPresent(o -> {
            if (InspectionScheduleOccurrence.GENERATED.equals(o.getStatus())) return;
            o.setStatus(InspectionScheduleOccurrence.PENDING);
            o.setSkipReason(null);
            o.setErrorMessage(null);
            o.setClaimedBy(null);
            o.setClaimedAt(null);
            o.setAttemptCount(0); // 管理员显式补发是新的决策，重置尝试计数
            o.setUpdatedAt(nowLdt);
            occRepo.save(o);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InspectionPlan setPlanEnabled(Long planId, boolean enabled, LocalDateTime disabledAt) {
        InspectionPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("计划不存在"));
        plan.setEnabled(enabled);
        plan.setDisabledAt(disabledAt);
        return planRepo.save(plan);
    }

    // ==================================================================
    // 计划启停：禁用不补发；重新启用必须显式选择继续或有限补齐
    // ==================================================================

    @Transactional
    public InspectionPlan disablePlan(Long planId) {
        InspectionPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("计划不存在"));
        LocalDateTime nowLdt = LocalDateTime.ofInstant(clock.now(), engine.getZone());
        if (!Boolean.FALSE.equals(plan.getEnabled())) {
            plan.setEnabled(false);
            plan.setDisabledAt(nowLdt);
            planRepo.save(plan);
        }
        // 禁用时点之后未生成的周期一律标记 plan_disabled，禁用期间绝不补发
        for (InspectionScheduleOccurrence o : occRepo.findByPlanIdOrderByScheduledStartDesc(planId)) {
            String st = o.getStatus();
            if ((InspectionScheduleOccurrence.PENDING.equals(st)
                    || InspectionScheduleOccurrence.FAILED.equals(st)
                    || InspectionScheduleOccurrence.PROCESSING.equals(st))
                    && !o.getScheduledStart().isBefore(nowLdt)) {
                o.setStatus(InspectionScheduleOccurrence.SKIPPED);
                o.setSkipReason(InspectionScheduleOccurrence.REASON_PLAN_DISABLED);
                o.setUpdatedAt(nowLdt);
                occRepo.save(o);
            }
        }
        return plan;
    }

    /**
     * 重新启用计划（编排方法）。
     *
     * @param mode       continue=从当前周期继续，禁用期周期记 disabled_no_backfill；
     *                   backfill=补齐禁用期内最近 maxPeriods 个周期，更早的记 backfill_limit
     * @param maxPeriods backfill 模式的周期数上限（受 app.schedule.backfill-limit-max 硬约束）
     */
    public InspectionPlan reactivatePlan(Long planId, String mode, Integer maxPeriods) {
        InspectionPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("计划不存在"));
        Instant now = clock.now();
        LocalDateTime nowLdt = LocalDateTime.ofInstant(now, engine.getZone());

        Instant disabledSince = plan.getDisabledAt() != null
                ? plan.getDisabledAt().atZone(engine.getZone()).toInstant()
                : now.minus(Duration.ofHours(Math.max(1, props.getRecoveryHours())));

        boolean backfill = "backfill".equalsIgnoreCase(mode);
        int limit = Math.min(Math.max(1, maxPeriods == null ? props.getBackfillLimitDefault() : maxPeriods),
                props.getBackfillLimitMax());

        // 先把计划打开，使后续生成不再被“计划禁用”拦截
        plan = self.setPlanEnabled(planId, true, null);

        // 禁用期历史 = [禁用时刻, 当前周期开始)；当前周期不算历史，恢复后正常调度
        Instant currentStart = engine.currentOccurrence(plan, now).startInstant(engine.getZone());
        List<PlannedOccurrence> missed = engine.occurrencesBetween(plan, disabledSince, currentStart);
        int fromIndex = Math.max(0, missed.size() - limit);
        for (int i = 0; i < missed.size(); i++) {
            PlannedOccurrence po = missed.get(i);
            InspectionScheduleOccurrence occ = self.registerOne(plan, po);
            if (occ == null) occ = occRepo.findByOccurrenceKey(
                    occurrenceKey(planId, po.periodKey())).orElseThrow();
            if (GENERATED.equals(occ.getStatus())) continue;
            if (backfill && i >= fromIndex) {
                self.reopenForBackfill(occ.getId(), nowLdt);
                if (self.claimOccurrence(occ.getId(), nowLdt)) {
                    try {
                        self.generateClaimed(occ.getId(), SOURCE_BACKFILL, nowLdt);
                    } catch (Exception e) {
                        self.markFailed(occ.getId(), e.getMessage(), nowLdt);
                    }
                }
            } else {
                String reason = backfill
                        ? InspectionScheduleOccurrence.REASON_BACKFILL_LIMIT
                        : InspectionScheduleOccurrence.REASON_DISABLED_NO_BACKFILL;
                self.markSkipped(occ.getId(), reason, nowLdt);
            }
        }

        // 当前周期（以及禁用时被标记的未来周期）从 plan_disabled 恢复为 pending，交给轮询生成
        self.registerOne(plan, engine.currentOccurrence(plan, now));
        self.reopenDisabledFuture(planId, currentStart, nowLdt);
        return plan;
    }

    /**
     * 把禁用时留痕为 plan_disabled、且开始时刻 ≥ since 的周期恢复为 pending。
     * 已被重新启用流程改写为 disabled_no_backfill / backfill_limit / window_expired 的不受影响。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reopenDisabledFuture(Long planId, Instant since, LocalDateTime nowLdt) {
        LocalDateTime sinceLdt = LocalDateTime.ofInstant(since, engine.getZone());
        for (InspectionScheduleOccurrence o : occRepo.findByPlanIdOrderByScheduledStartDesc(planId)) {
            if (InspectionScheduleOccurrence.SKIPPED.equals(o.getStatus())
                    && InspectionScheduleOccurrence.REASON_PLAN_DISABLED.equals(o.getSkipReason())
                    && !o.getScheduledStart().isBefore(sinceLdt)) {
                o.setStatus(InspectionScheduleOccurrence.PENDING);
                o.setSkipReason(null);
                o.setErrorMessage(null);
                o.setClaimedBy(null);
                o.setClaimedAt(null);
                o.setUpdatedAt(nowLdt);
                occRepo.save(o);
            }
        }
    }

    // ==================================================================
    // 管理员接口：预览 / 立即执行 / 显式补齐 / 反查
    // ==================================================================

    /** 预览某计划在给定时间区间内应触发的周期及当前落库状态。 */
    public List<PreviewItem> preview(Long planId, Instant from, Instant to) {
        InspectionPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("计划不存在"));
        List<PreviewItem> out = new ArrayList<>();
        for (PlannedOccurrence po : engine.occurrencesBetween(plan, from, to)) {
            Optional<InspectionScheduleOccurrence> saved =
                    occRepo.findByOccurrenceKey(occurrenceKey(planId, po.periodKey()));
            out.add(new PreviewItem(po.periodKey(), po.cycleType(),
                    po.scheduledStart(), po.scheduledEnd(),
                    saved.isPresent(), saved.map(InspectionScheduleOccurrence::getStatus).orElse(null),
                    saved.map(InspectionScheduleOccurrence::getTaskId).orElse(null),
                    saved.map(InspectionScheduleOccurrence::getSkipReason).orElse(null),
                    saved.map(o -> describeSkipReason(o.getSkipReason())).orElse(null)));
        }
        return out;
    }

    /** 立即执行：默认当前周期；已生成/已跳过的周期有确定拒绝语义，绝不重复生成。 */
    public InspectionTask triggerNow(Long planId, String periodKey) {
        return triggerNow(planId, periodKey, null, "", true, null);
    }

    /**
     * 立即执行（可携带指派人/路线参数，供原有手工生成入口复用）。
     * 与轮询共用同一套周期身份与原子认领，因此手工生成与自动调度绝不会对同一周期各发一条任务。
     */
    public InspectionTask triggerNow(Long planId, String periodKey,
                                      Long assigneeId, String assigneeName,
                                      boolean useOptimizedRoute, Long startPointId) {
        InspectionPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("计划不存在"));
        if (Boolean.FALSE.equals(plan.getEnabled())) {
            throw new IllegalArgumentException("计划已禁用，请先重新启用");
        }
        Instant now = clock.now();
        LocalDateTime nowLdt = LocalDateTime.ofInstant(now, engine.getZone());
        PlannedOccurrence po = (periodKey == null || periodKey.isBlank())
                ? engine.currentOccurrence(plan, now)
                : findByPeriodKey(plan, periodKey, now);

        InspectionScheduleOccurrence occ = self.registerOne(plan, po);
        if (occ == null) {
            occ = occRepo.findByOccurrenceKey(occurrenceKey(planId, po.periodKey())).orElseThrow();
        }
        if (GENERATED.equals(occ.getStatus())) {
            throw new IllegalStateException("周期 " + po.periodKey() + " 已生成任务 #" + occ.getTaskId()
                    + "（" + occ.getTaskCode() + "），禁止重复生成");
        }
        if (InspectionScheduleOccurrence.SKIPPED.equals(occ.getStatus())) {
            throw new IllegalStateException("周期 " + po.periodKey() + " 已标记跳过："
                    + describeSkipReason(occ.getSkipReason()) + "，如需补发请使用补齐接口");
        }
        if (!self.claimOccurrence(occ.getId(), nowLdt)) {
            throw new IllegalStateException("周期正由其他调度实例处理，请稍后查看结果");
        }
        try {
            return self.generateClaimed(occ.getId(), SOURCE_MANUAL, nowLdt,
                    assigneeId, assigneeName, useOptimizedRoute, startPointId);
        } catch (Exception e) {
            self.markFailed(occ.getId(), e.getMessage(), nowLdt);
            throw e;
        }
    }

    /**
     * 显式补齐历史周期（含禁用期或窗口过期），最多 maxPeriods 个，按开始时间“最近优先”选取；
     * 更早的周期登记 backfill_limit 留痕。已生成的周期不重复。
     */
    public List<OccurrenceView> backfill(Long planId, Instant from, Instant to, Integer maxPeriods) {
        InspectionPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("计划不存在"));
        if (!from.isBefore(to)) throw new IllegalArgumentException("补齐时间区间不合法");
        int limit = Math.min(Math.max(1, maxPeriods == null ? props.getBackfillLimitDefault() : maxPeriods),
                props.getBackfillLimitMax());
        LocalDateTime nowLdt = LocalDateTime.ofInstant(clock.now(), engine.getZone());

        List<PlannedOccurrence> all = engine.occurrencesBetween(plan, from, to);
        int fromIndex = Math.max(0, all.size() - limit);
        List<OccurrenceView> result = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            PlannedOccurrence po = all.get(i);
            InspectionScheduleOccurrence occ = self.registerOne(plan, po);
            if (occ == null) occ = occRepo.findByOccurrenceKey(
                    occurrenceKey(planId, po.periodKey())).orElseThrow();
            if (!GENERATED.equals(occ.getStatus())) {
                if (i < fromIndex) {
                    self.markSkipped(occ.getId(),
                            InspectionScheduleOccurrence.REASON_BACKFILL_LIMIT, nowLdt);
                } else {
                    self.reopenForBackfill(occ.getId(), nowLdt);
                    if (self.claimOccurrence(occ.getId(), nowLdt)) {
                        try {
                            self.generateClaimed(occ.getId(), SOURCE_BACKFILL, nowLdt);
                        } catch (Exception e) {
                            self.markFailed(occ.getId(), e.getMessage(), nowLdt);
                        }
                    }
                }
            }
            InspectionScheduleOccurrence latest = occRepo.findById(occ.getId()).orElseThrow();
            result.add(OccurrenceView.of(latest, plan));
        }
        return result;
    }

    /** 从任务反查触发周期、生成实例与跳过原因（含调度上线前老任务的兼容回退）。 */
    public TaskTrace traceByTask(Long taskId) {
        InspectionTask task = taskService.getById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        InspectionPlan plan = planRepo.findById(task.getPlanId()).orElse(null);
        InspectionScheduleOccurrence occ = null;
        String note = null;
        if (task.getOccurrenceId() != null) {
            occ = occRepo.findById(task.getOccurrenceId()).orElse(null);
            if (occ == null) note = "任务记录了生成实例ID，但调度实例记录已不存在";
        }
        if (occ == null) {
            List<InspectionScheduleOccurrence> list = occRepo.findByTaskId(taskId);
            if (!list.isEmpty()) occ = list.get(0);
        }
        if (occ == null && task.getPeriodKey() != null) {
            occ = occRepo.findByOccurrenceKey(occurrenceKey(task.getPlanId(), task.getPeriodKey())).orElse(null);
            if (occ == null) note = "调度身份登记前生成的历史任务，仅有周期键：" + task.getPeriodKey();
        }
        if (occ == null && note == null) note = "该任务未关联调度周期（可能为人工补录）";
        return new TaskTrace(task, occ == null ? null : OccurrenceView.of(occ, plan), note);
    }

    public List<OccurrenceView> listOccurrences(Long planId, String status, int limit) {
        int safeLimit = Math.max(1, limit);
        List<InspectionScheduleOccurrence> rows;
        Sort sort = Sort.by(Sort.Direction.DESC, "scheduledStart");
        if (planId != null) {
            rows = occRepo.findByPlanIdOrderByScheduledStartDesc(planId);
        } else {
            // 状态过滤在内存进行，给一个有限的超取上限避免全表加载
            int fetchSize = (status == null || status.isBlank()) ? safeLimit : Math.min(safeLimit * 10, 2000);
            rows = occRepo.findAll(PageRequest.of(0, fetchSize, sort)).getContent();
        }
        List<OccurrenceView> out = new ArrayList<>();
        int count = 0;
        for (InspectionScheduleOccurrence o : rows) {
            if (status != null && !status.isBlank() && !status.equals(o.getStatus())) continue;
            InspectionPlan p = planRepo.findById(o.getPlanId()).orElse(null);
            out.add(OccurrenceView.of(o, p));
            if (++count >= Math.max(1, limit)) break;
        }
        return out;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private PlannedOccurrence findByPeriodKey(InspectionPlan plan, String periodKey, Instant now) {
        int days = Math.max(2, props.getRecoveryHours() / 24 + 2);
        Instant from = now.minus(Duration.ofDays(days));
        Instant to = now.plus(Duration.ofDays(days));
        for (PlannedOccurrence po : engine.occurrencesBetween(plan, from, to)) {
            if (po.periodKey().equals(periodKey)) return po;
        }
        throw new IllegalArgumentException("周期 " + periodKey + " 不在可操作区间内，请使用预览接口确认");
    }

    private Long firstAssignee(String assigneeIds) {
        if (assigneeIds == null || assigneeIds.isBlank()) return null;
        for (String part : assigneeIds.split(",")) {
            try {
                long id = Long.parseLong(part.trim());
                if (id > 0) return id;
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    public static String occurrenceKey(Long planId, String periodKey) {
        return planId + ":" + periodKey;
    }

    public static String describeSkipReason(String reason) {
        if (reason == null) return null;
        return switch (reason) {
            case InspectionScheduleOccurrence.REASON_WINDOW_EXPIRED -> "执行窗口已过期";
            case InspectionScheduleOccurrence.REASON_PLAN_DISABLED -> "计划禁用期间不补发";
            case InspectionScheduleOccurrence.REASON_DISABLED_NO_BACKFILL -> "重新启用时选择从当前周期继续，禁用期不补发";
            case InspectionScheduleOccurrence.REASON_BACKFILL_LIMIT -> "超出补齐周期数上限";
            default -> reason;
        };
    }

    private String truncate(String s, int max) {
        if (s == null) return "生成异常";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
