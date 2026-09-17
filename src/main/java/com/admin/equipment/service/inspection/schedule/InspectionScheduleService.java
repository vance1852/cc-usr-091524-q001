package com.admin.equipment.service.inspection.schedule;

import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionScheduleLedger;
import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.repo.inspection.InspectionPlanRepository;
import com.admin.equipment.repo.inspection.InspectionScheduleLedgerRepository;
import com.admin.equipment.service.inspection.schedule.CycleScheduleCalculator.PeriodInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 巡检自动调度核心。
 *
 * 身份与去重：每个 (计划, 调度版本, 周期键) 在 inspection_schedule_ledger 占一行唯一身份；
 * 任务与台账在同一事务内落库。正常轮询、多实例并发抢占、重启补偿都只会产生一个任务：
 *  - 抢占由数据库唯一索引裁决，输家直接复用已有身份；
 *  - 抢占与生成分两个事务：PENDING 行先提交，任务生成整体原子；
 *    进程崩溃只会留下 PENDING 行，任务绝不可能半提交，重试天然幂等。
 */
@Service
public class InspectionScheduleService {

    private static final Logger log = LoggerFactory.getLogger(InspectionScheduleService.class);

    /** PENDING 超过该时长视为上次生成崩溃，可安全重试（任务与台账同事务，不存在半任务）。 */
    private static final long STALE_PENDING_MINUTES = 2;

    private final InspectionPlanRepository planRepo;
    private final InspectionScheduleLedgerRepository ledgerRepo;
    private final ScheduledTaskGenerator taskGenerator;
    private final CycleScheduleCalculator calculator;
    private final ScheduleClock clock;
    private final TransactionTemplate txTemplate;

    @Value("${app.schedule.backfill-max:200}")
    private int backfillMax;

    public InspectionScheduleService(InspectionPlanRepository planRepo,
                                      InspectionScheduleLedgerRepository ledgerRepo,
                                      ScheduledTaskGenerator taskGenerator,
                                      CycleScheduleCalculator calculator,
                                      ScheduleClock clock,
                                      TransactionTemplate txTemplate) {
        this.planRepo = planRepo;
        this.ledgerRepo = ledgerRepo;
        this.taskGenerator = taskGenerator;
        this.calculator = calculator;
        this.clock = clock;
        this.txTemplate = txTemplate;
    }

    /** 一次扫描的汇总结果，供接口展示与日志记录。 */
    public record SweepResult(int plansScanned, int generated, int skipped, int recovered,
                               List<String> details) {}

    /** 全量扫描：正常轮询与启动补偿共用，差异仅在触发来源标记。 */
    public SweepResult sweep(String source) {
        LocalDateTime now = clock.now();
        int generated = 0, skipped = 0, recovered = 0;
        List<String> details = new ArrayList<>();
        List<InspectionPlan> plans = planRepo.findByEnabledTrueOrderByCodeAsc();
        for (InspectionPlan plan : plans) {
            try {
                int[] counters = sweepPlan(plan, source, now);
                generated += counters[0];
                skipped += counters[1];
                recovered += counters[2];
                if (counters[0] + counters[1] > 0) {
                    details.add(plan.getCode() + ": 生成 " + counters[0] + "，跳过 " + counters[1]);
                }
            } catch (Exception e) {
                log.error("计划 {} 调度扫描失败", plan.getCode(), e);
                details.add(plan.getCode() + ": 扫描异常 " + e.getMessage());
            }
        }
        return new SweepResult(plans.size(), generated, skipped, recovered, details);
    }

    /**
     * 扫描单个启用计划：找出已到期但台账缺失的周期实例，按补偿上限生成或留痕跳过。
     * 返回 [generated, skipped, recovered]。
     */
    private int[] sweepPlan(InspectionPlan plan, String source, LocalDateTime now) {
        int generated = 0, skipped = 0, recovered = 0;

        // 先恢复本计划残留的 PENDING 身份（上次生成进程崩溃）。
        for (InspectionScheduleLedger stale : ledgerRepo
                .findByPlanIdAndStatusAndCreatedAtBefore(plan.getId(),
                        InspectionScheduleLedger.STATUS_PENDING,
                        now.minusMinutes(STALE_PENDING_MINUTES))) {
            if (fireExisting(plan, stale, source)) {
                generated++;
                recovered++;
            }
        }

        LocalDateTime from = watermark(plan);
        List<PeriodInstance> due = new ArrayList<>();
        for (PeriodInstance pi : calculator.enumerate(plan, from, now)) {
            InspectionScheduleLedger existing = ledgerRepo
                    .findByPlanIdAndScheduleVersionAndPeriodKey(plan.getId(), version(plan), pi.key())
                    .orElse(null);
            if (existing == null) {
                due.add(pi);
            } else if (InspectionScheduleLedger.STATUS_FAILED.equals(existing.getStatus())) {
                if (fireExisting(plan, existing, source)) { generated++; recovered++; }
            }
        }

        // 有限历史补偿：最早的超出上限部分仅留痕，最近的周期照常生成。
        int overflow = Math.max(0, due.size() - Math.max(1, backfillMax));
        for (int i = 0; i < due.size(); i++) {
            PeriodInstance pi = due.get(i);
            if (i < overflow) {
                markSkipped(plan, pi, InspectionScheduleLedger.REASON_BACKFILL_LIMIT,
                        "漏发周期早于补偿上限(" + backfillMax + ")，仅记录不补发", source);
                skipped++;
            } else if (acquireAndFire(plan, pi, source)) {
                generated++;
            }
        }
        return new int[]{generated, skipped, recovered};
    }

    /** 本版本台账中最晚的计划时刻；无记录时从锚点开始枚举。 */
    private LocalDateTime watermark(InspectionPlan plan) {
        List<InspectionScheduleLedger> rows = ledgerRepo
                .findByPlanIdAndScheduleVersionOrderByScheduledAtDesc(
                        plan.getId(), version(plan), PageRequest.of(0, 1));
        LocalDateTime anchor = calculator.resolveAnchor(plan);
        if (rows.isEmpty()) return anchor;
        LocalDateTime wm = rows.get(0).getScheduledAt();
        // 回退一个周期起点之前即可，enumerate 自身会按台账去重；这里直接从水位线枚举（含该实例）。
        return wm.isBefore(anchor) ? anchor : wm;
    }

    /**
     * 抢占周期身份并生成任务。抢占失败（并发其他实例已占位）返回 false，且不做任何补救——
     * 赢家负责生成；若赢家崩溃，残留 PENDING 由后续扫描恢复。
     */
    private boolean acquireAndFire(InspectionPlan plan, PeriodInstance pi, String source) {
        InspectionScheduleLedger acquired;
        try {
            acquired = txTemplate.execute(status -> {
                InspectionScheduleLedger l = newLedger(plan, pi, source);
                return ledgerRepo.saveAndFlush(l);
            });
        } catch (DataIntegrityViolationException e) {
            log.info("计划 {} 周期 {} 已被其他实例占用", plan.getCode(), pi.key());
            return false;
        }
        if (acquired == null) return false;
        return fireExisting(plan, acquired, source);
    }

    /** 对已存在的 PENDING/FAILED 身份执行（或重试）任务生成，任务与台账状态在同一事务提交。 */
    private boolean fireExisting(InspectionPlan plan, InspectionScheduleLedger ledger, String source) {
        try {
            PeriodInstance pi = new PeriodInstance(
                    ledger.getPeriodIndex() == null ? 0 : ledger.getPeriodIndex(),
                    ledger.getPeriodKey(), ledger.getScheduledAt(), ledger.getCycleType());
            Long taskId = txTemplate.execute(status -> {
                InspectionTask task = taskGenerator.generateForPeriod(plan, pi, ledger.getId(), source);
                ledger.setStatus(InspectionScheduleLedger.STATUS_GENERATED);
                ledger.setTaskId(task.getId());
                ledger.setFiredAt(clock.now());
                ledger.setTriggerSource(source);
                ledger.setMessage(null);
                ledger.setUpdatedAt(clock.now());
                ledgerRepo.save(ledger);
                return task.getId();
            });
            log.info("计划 {} 周期 {} 已生成任务 id={}", plan.getCode(), pi.key(), taskId);
            return true;
        } catch (Exception e) {
            log.error("计划 {} 周期 {} 任务生成失败", plan.getCode(), ledger.getPeriodKey(), e);
            try {
                txTemplate.execute(status -> {
                    InspectionScheduleLedger l = ledgerRepo.findById(ledger.getId()).orElse(null);
                    // 并发恢复竞争中败者回滚后，赢家可能已置为 GENERATED：不得覆盖其结果。
                    if (l == null || InspectionScheduleLedger.STATUS_GENERATED.equals(l.getStatus())
                            || InspectionScheduleLedger.STATUS_SKIPPED.equals(l.getStatus())) {
                        return null;
                    }
                    l.setStatus(InspectionScheduleLedger.STATUS_FAILED);
                    l.setMessage(truncate(e.getMessage(), 480));
                    l.setUpdatedAt(clock.now());
                    return ledgerRepo.save(l);
                });
            } catch (Exception ignore) { /* 下轮扫描重试 */ }
            return false;
        }
    }

    private void markSkipped(InspectionPlan plan, PeriodInstance pi, String reason,
                              String message, String source) {
        try {
            txTemplate.execute(status -> {
                InspectionScheduleLedger l = newLedger(plan, pi, source);
                l.setStatus(InspectionScheduleLedger.STATUS_SKIPPED);
                l.setSkipReason(reason);
                l.setMessage(message);
                l.setFiredAt(clock.now());
                return ledgerRepo.saveAndFlush(l);
            });
        } catch (DataIntegrityViolationException ignored) {
            // 并发下身份已存在，以已有行为准。
        }
    }

    private InspectionScheduleLedger newLedger(InspectionPlan plan, PeriodInstance pi, String source) {
        LocalDateTime now = clock.now();
        InspectionScheduleLedger l = new InspectionScheduleLedger();
        l.setPlanId(plan.getId());
        l.setScheduleVersion(version(plan));
        l.setPeriodKey(pi.key());
        l.setCycleType(pi.cycleType());
        l.setPeriodIndex(pi.index());
        l.setScheduledAt(pi.scheduledAt());
        l.setStatus(InspectionScheduleLedger.STATUS_PENDING);
        l.setTriggerSource(source);
        l.setCreatedAt(now);
        l.setUpdatedAt(now);
        return l;
    }

    // ===== 管理员接口 =====

    /** 调度预览：枚举时间窗内的周期实例并附上当前生成状态。 */
    public List<SchedulePreviewEntry> preview(Long planId, LocalDateTime from, LocalDateTime to) {
        InspectionPlan plan = requirePlan(planId);
        LocalDateTime end = to != null ? to : clock.now().plusDays(14);
        LocalDateTime start = from != null ? from : clock.now().minusDays(1);
        List<SchedulePreviewEntry> out = new ArrayList<>();
        for (PeriodInstance pi : calculator.enumerate(plan, start, end)) {
            InspectionScheduleLedger l = ledgerRepo
                    .findByPlanIdAndScheduleVersionAndPeriodKey(planId, version(plan), pi.key())
                    .orElse(null);
            out.add(SchedulePreviewEntry.of(pi.index(), pi, l));
        }
        return out;
    }

    /**
     * 立即执行：为指定周期（缺省为当前周期）生成任务。
     * 已生成则幂等返回既有任务；禁用计划拒绝执行。
     */
    public InspectionTask triggerNow(Long planId, String periodKey) {
        InspectionPlan plan = requirePlan(planId);
        if (Boolean.FALSE.equals(plan.getEnabled())) {
            throw new IllegalArgumentException("该计划已禁用，不能立即执行");
        }
        LocalDateTime now = clock.now();
        PeriodInstance pi;
        if (periodKey != null && !periodKey.isBlank()) {
            pi = findPeriodByKey(plan, periodKey, now.minusDays(31), now.plusDays(31));
        } else {
            pi = calculator.instance(plan, calculator.indexAt(plan, now));
        }
        InspectionScheduleLedger ledger = ledgerRepo
                .findByPlanIdAndScheduleVersionAndPeriodKey(planId, version(plan), pi.key()).orElse(null);
        if (ledger != null && InspectionScheduleLedger.STATUS_GENERATED.equals(ledger.getStatus())
                && ledger.getTaskId() != null) {
            return taskGenerator.getTask(ledger.getTaskId());
        }
        if (ledger == null) {
            acquireAndFire(plan, pi, InspectionScheduleLedger.SOURCE_MANUAL);
            ledger = waitForIdentity(planId, pi.key());
        } else {
            fireExisting(plan, ledger, InspectionScheduleLedger.SOURCE_MANUAL);
            ledger = ledgerRepo.findById(ledger.getId()).orElseThrow();
        }
        Long taskId = ledger.getTaskId();
        if (taskId == null) {
            throw new IllegalStateException("立即执行未生成任务，状态：" + ledger.getStatus()
                    + (ledger.getMessage() != null ? "，" + ledger.getMessage() : ""));
        }
        return taskGenerator.getTask(taskId);
    }

    /** 并发抢占失败后等待赢家完成生成（约10秒），使并发“立即执行”返回同一个任务。 */
    private InspectionScheduleLedger waitForIdentity(Long planId, String periodKey) {
        for (int i = 0; i < 50; i++) {
            InspectionScheduleLedger l = ledgerRepo
                    .findByPlanIdAndScheduleVersionAndPeriodKey(planId, versionOf(planId), periodKey)
                    .orElse(null);
            if (l != null && l.getTaskId() != null) return l;
            if (l != null && InspectionScheduleLedger.STATUS_SKIPPED.equals(l.getStatus())) return l;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ledgerRepo.findByPlanIdAndScheduleVersionAndPeriodKey(planId, versionOf(planId), periodKey)
                .orElseThrow(() -> new IllegalStateException("并发生成结果等待超时：" + periodKey));
    }

    private int versionOf(Long planId) {
        return planRepo.findById(planId).map(this::version).orElse(1);
    }

    private PeriodInstance findPeriodByKey(InspectionPlan plan, String key,
                                            LocalDateTime from, LocalDateTime to) {
        for (PeriodInstance pi : calculator.enumerate(plan, from, to)) {
            if (pi.key().equals(key)) return pi;
        }
        throw new IllegalArgumentException("周期 " + key + " 不在可执行范围（前后31天）内");
    }

    /**
     * 重新启用计划时的调度接续策略。
     * @param mode CURRENT=从当前周期继续，禁用期间到期周期留痕跳过；
     *             BACKFILL=补齐有限历史（上限内最近若干周期），管理员明确选择。
     */
    public SweepResult resume(Long planId, String mode, Integer limit) {
        InspectionPlan plan = requirePlan(planId);
        if (Boolean.TRUE.equals(plan.getEnabled())) {
            throw new IllegalArgumentException("计划处于启用状态，无需接续");
        }
        LocalDateTime now = clock.now();
        // 从本版本水位线枚举：禁用前已到期但从未扫描过的周期也要给出确定归属，
        // 否则重新启用后的第一次轮询会把它们当作普通漏发整段补发。
        LocalDateTime enumerateFrom = watermark(plan);

        // 收集水位线之后缺台账的周期，并确保“当前周期”在列（其计划时刻必不晚于 now）。
        long currentIndex = calculator.indexAt(plan, now);
        LinkedHashSet<PeriodInstance> missedSet = new LinkedHashSet<>();
        for (PeriodInstance pi : calculator.enumerate(plan, enumerateFrom, now)) {
            if (ledgerRepo.findByPlanIdAndScheduleVersionAndPeriodKey(
                    plan.getId(), version(plan), pi.key()).isEmpty()) {
                missedSet.add(pi);
            }
        }
        PeriodInstance current = calculator.instance(plan, currentIndex);
        if (ledgerRepo.findByPlanIdAndScheduleVersionAndPeriodKey(
                plan.getId(), version(plan), current.key()).isEmpty()) {
            missedSet.add(current);
        }
        List<PeriodInstance> missed = new ArrayList<>(missedSet);

        int maxBackfill = limit != null ? Math.max(0, Math.min(limit, 1000)) : Math.max(1, backfillMax);
        int generated = 0, skipped = 0;
        List<String> details = new ArrayList<>();
        // CURRENT：仅补发当前周期，更早的留痕 DISABLED；
        // BACKFILL：补发最近 maxBackfill 个（含当前周期），更早的留痕 BACKFILL_LIMIT。
        int toGenerate = "BACKFILL".equalsIgnoreCase(mode)
                ? Math.min(missed.size(), maxBackfill)
                : (missed.isEmpty() ? 0 : 1);
        int cutoff = missed.size() - toGenerate;
        for (int i = 0; i < missed.size(); i++) {
            PeriodInstance pi = missed.get(i);
            if (i < cutoff) {
                String reason = "BACKFILL".equalsIgnoreCase(mode)
                        ? InspectionScheduleLedger.REASON_BACKFILL_LIMIT
                        : InspectionScheduleLedger.REASON_DISABLED;
                String msg = "BACKFILL".equalsIgnoreCase(mode)
                        ? "超出管理员指定补齐数量(" + maxBackfill + ")"
                        : "计划禁用期间到期，管理员选择从当前周期继续，不补发";
                markSkipped(plan, pi, reason, msg, InspectionScheduleLedger.SOURCE_RESUME);
                skipped++;
            } else {
                if (acquireAndFire(plan, pi, InspectionScheduleLedger.SOURCE_RESUME)) generated++;
            }
        }

        // 最后原子地启用计划。
        txTemplate.execute(status -> {
            InspectionPlan p = planRepo.findById(planId).orElseThrow();
            p.setEnabled(true);
            p.setDisabledAt(null);
            return planRepo.save(p);
        });
        details.add(plan.getCode() + " 重新启用(" + mode + ")：补发 " + generated + "，留痕跳过 " + skipped);
        return new SweepResult(1, generated, skipped, 0, details);
    }

    /** 从任务反查触发周期、生成实例与跳过信息。 */
    public Map<String, Object> traceByTask(Long taskId) {
        InspectionScheduleLedger ledger = ledgerRepo.findByTaskId(taskId).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        if (ledger == null) {
            out.put("scheduled", false);
            out.put("message", "该任务为手工触发生成，无周期调度身份");
            return out;
        }
        out.put("scheduled", true);
        out.put("ledgerId", ledger.getId());
        out.put("planId", ledger.getPlanId());
        out.put("scheduleVersion", ledger.getScheduleVersion());
        out.put("periodIndex", ledger.getPeriodIndex());
        out.put("periodKey", ledger.getPeriodKey());
        out.put("cycleType", ledger.getCycleType());
        out.put("scheduledAt", ledger.getScheduledAt());
        out.put("firedAt", ledger.getFiredAt());
        out.put("status", ledger.getStatus());
        out.put("triggerSource", ledger.getTriggerSource());
        out.put("skipReason", ledger.getSkipReason());
        out.put("message", ledger.getMessage());
        return out;
    }

    /** 补偿/调度记录查询。 */
    public List<InspectionScheduleLedger> ledgerRecords(Long planId, String status, int limit) {
        int size = Math.max(1, Math.min(limit, 500));
        if (planId != null && status != null) {
            List<InspectionScheduleLedger> all = ledgerRepo
                    .findByPlanIdOrderByScheduledAtDesc(planId, PageRequest.of(0, size));
            return all.stream().filter(l -> status.equals(l.getStatus())).toList();
        }
        if (planId != null) {
            return ledgerRepo.findByPlanIdOrderByScheduledAtDesc(planId, PageRequest.of(0, size));
        }
        List<InspectionScheduleLedger> all = ledgerRepo.findAllByOrderByScheduledAtDesc(PageRequest.of(0, size));
        if (status != null) {
            return all.stream().filter(l -> status.equals(l.getStatus())).toList();
        }
        return all;
    }

    private InspectionPlan requirePlan(Long planId) {
        return planRepo.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("巡检计划不存在"));
    }

    private int version(InspectionPlan plan) {
        return plan.getScheduleVersion() == null ? 1 : plan.getScheduleVersion();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
