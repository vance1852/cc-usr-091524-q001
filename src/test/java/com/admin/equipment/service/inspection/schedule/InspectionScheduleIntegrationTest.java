package com.admin.equipment.service.inspection.schedule;

import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionScheduleLedger;
import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.repo.inspection.InspectionPlanPointRepository;
import com.admin.equipment.repo.inspection.InspectionPlanRepository;
import com.admin.equipment.repo.inspection.InspectionPointRepository;
import com.admin.equipment.repo.inspection.InspectionScheduleLedgerRepository;
import com.admin.equipment.repo.inspection.InspectionTaskPointRepository;
import com.admin.equipment.repo.inspection.InspectionTaskRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateItemRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateRepository;
import com.admin.equipment.service.inspection.InspectionPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 时钟可控的端到端调度集成测试（H2 内存库）。 */
class InspectionScheduleIntegrationTest extends AbstractScheduleIntegrationTest {

    @Autowired private InspectionPlanService planService;

    @BeforeEach
    void clean() {
        ledgerRepo.deleteAll();
        taskRepo.deleteAll();
        planPointRepo.deleteAll();
        planRepo.deleteAll();
        pointRepo.deleteAll();
        templateItemRepo.deleteAll();
        templateRepo.deleteAll();
    }

    @Test
    void pollGeneratesDuePeriodAndRepeatedPollIsIdempotent() {
        fixClock(LocalDateTime.of(2026, 9, 17, 9, 0));
        InspectionPlan plan = newPlan("D1", "daily", 1, "08:00",
                LocalDateTime.of(2026, 9, 17, 8, 0));

        InspectionScheduleService.SweepResult r1 = scheduleService.sweep("POLL");
        assertEquals(1, r1.generated());

        List<InspectionTask> tasks = taskRepo.findByPlanIdOrderByCreatedAtDesc(plan.getId());
        assertEquals(1, tasks.size());
        assertEquals("D20260917", tasks.get(0).getPeriodKey());
        assertEquals("POLL", tasks.get(0).getTriggerSource());
        assertNotNull(tasks.get(0).getScheduleLedgerId());
        assertEquals(LocalDateTime.of(2026, 9, 17, 8, 0), tasks.get(0).getScheduledStart());
        assertEquals(LocalDateTime.of(2026, 9, 17, 10, 0), tasks.get(0).getScheduledEnd(),
                "120 分钟时间窗");

        // 再次轮询不得重复
        InspectionScheduleService.SweepResult r2 = scheduleService.sweep("POLL");
        assertEquals(0, r2.generated());
        assertEquals(1, taskRepo.findByPlanIdOrderByCreatedAtDesc(plan.getId()).size());
    }

    @Test
    void startupCatchUpAfterDowntimeGeneratesEveryMissedHourlyPeriodOnce() {
        // 模拟停机：锚点 08:00，当前已 11:40，08/10 两个周期漏发
        fixClock(LocalDateTime.of(2026, 9, 17, 11, 40));
        InspectionPlan plan = newPlan("H1", "hourly", 2, "08:00",
                LocalDateTime.of(2026, 9, 17, 8, 0));

        InspectionScheduleService.SweepResult r = scheduleService.sweep("STARTUP");
        assertEquals(2, r.generated());

        List<InspectionTask> tasks = taskRepo.findByPlanIdOrderByCreatedAtDesc(plan.getId());
        assertEquals(2, tasks.size());
        assertEquals(2, ledgerRepo.findAll().size());
        // 再扫一次，仍然只有两个任务
        assertEquals(0, scheduleService.sweep("STARTUP").generated());
        assertEquals(2, taskRepo.findByPlanIdOrderByCreatedAtDesc(plan.getId()).size());
    }

    @Test
    void concurrentSweepsProduceExactlyOneTask() throws Exception {
        fixClock(LocalDateTime.of(2026, 9, 17, 9, 0));
        newPlan("C1", "daily", 1, "08:00", LocalDateTime.of(2026, 9, 17, 8, 0));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    scheduleService.sweep("POLL");
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        if (!errors.isEmpty()) {
            fail("并发扫描出现异常: " + errors.peek());
        }
        assertEquals(1, taskRepo.count(), "并发实例不得产生重复任务");
        assertEquals(1, ledgerRepo.count());
    }

    @Test
    void concurrentRecoveryOfSameStalePendingProducesOneTask() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 9, 17, 9, 0);
        fixClock(now);
        InspectionPlan plan = newPlan("R2", "daily", 1, "08:00",
                LocalDateTime.of(2026, 9, 17, 8, 0));
        tx.executeWithoutResult(s -> {
            InspectionScheduleLedger l = new InspectionScheduleLedger();
            l.setPlanId(plan.getId());
            l.setScheduleVersion(1);
            l.setPeriodKey("D20260917");
            l.setCycleType("daily");
            l.setPeriodIndex(0L);
            l.setScheduledAt(LocalDateTime.of(2026, 9, 17, 8, 0));
            l.setStatus(InspectionScheduleLedger.STATUS_PENDING);
            l.setTriggerSource("POLL");
            l.setCreatedAt(now.minusMinutes(10));
            l.setUpdatedAt(now.minusMinutes(10));
            ledgerRepo.save(l);
        });

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    scheduleService.sweep("STARTUP");
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        if (!errors.isEmpty()) fail("并发恢复出现异常: " + errors.peek());
        assertEquals(1, taskRepo.count(), "乐观锁必须保证同一 PENDING 只恢复出一个任务");
    }

    @Test
    void crashedPendingLedgerIsRecoveredOnNextSweep() {        LocalDateTime now = LocalDateTime.of(2026, 9, 17, 9, 0);
        fixClock(now);
        InspectionPlan plan = newPlan("R1", "daily", 1, "08:00",
                LocalDateTime.of(2026, 9, 17, 8, 0));

        // 直接写一条 10 分钟前抢占、生成进程崩溃遗留的 PENDING
        tx.executeWithoutResult(s -> {
            InspectionScheduleLedger l = new InspectionScheduleLedger();
            l.setPlanId(plan.getId());
            l.setScheduleVersion(1);
            l.setPeriodKey("D20260917");
            l.setCycleType("daily");
            l.setPeriodIndex(0L);
            l.setScheduledAt(LocalDateTime.of(2026, 9, 17, 8, 0));
            l.setStatus(InspectionScheduleLedger.STATUS_PENDING);
            l.setTriggerSource("POLL");
            l.setCreatedAt(now.minusMinutes(10));
            l.setUpdatedAt(now.minusMinutes(10));
            ledgerRepo.save(l);
        });
        assertEquals(0, taskRepo.count());

        InspectionScheduleService.SweepResult r = scheduleService.sweep("STARTUP");
        assertEquals(1, r.generated());
        assertEquals(1, r.recovered());
        assertEquals(1, taskRepo.count());
        InspectionScheduleLedger l = ledgerRepo.findAll().get(0);
        assertEquals(InspectionScheduleLedger.STATUS_GENERATED, l.getStatus());
        assertNotNull(l.getTaskId());
    }

    @Test
    void disabledPeriodsAreNotGeneratedAndResumeCurrentOnlyServesCurrentPeriod() {
        // 9-15 08:00 创建的日计划，9-16 全天禁用，9-18 09:00 重新启用
        fixClock(LocalDateTime.of(2026, 9, 15, 10, 0));
        InspectionPlan plan = newPlan("E1", "daily", 1, "08:00",
                LocalDateTime.of(2026, 9, 15, 8, 0));
        scheduleService.sweep("POLL"); // 9-15 周期正常生成

        fixClock(LocalDateTime.of(2026, 9, 16, 9, 0));
        planService.setEnabled(plan.getId(), false);

        fixClock(LocalDateTime.of(2026, 9, 17, 9, 0));
        assertEquals(0, scheduleService.sweep("POLL").generated(), "禁用期间不补发");

        fixClock(LocalDateTime.of(2026, 9, 18, 9, 0));
        InspectionScheduleService.SweepResult r = scheduleService.resume(plan.getId(), "CURRENT", null);
        assertEquals(1, r.generated(), "CURRENT 只生成当前周期(9-18)");
        assertEquals(2, r.skipped(), "禁用窗口内 9-16、9-17 两个漏发周期留痕");

        // 留痕原因可查，且之后轮询不会再补发
        List<InspectionScheduleLedger> skipped = scheduleService.ledgerRecords(plan.getId(), "SKIPPED", 50);
        assertTrue(skipped.stream().allMatch(l -> InspectionScheduleLedger.REASON_DISABLED.equals(l.getSkipReason())));
        assertEquals(0, scheduleService.sweep("POLL").generated());
        assertEquals(2, taskRepo.findByPlanIdOrderByCreatedAtDesc(plan.getId()).size());
    }

    @Test
    void resumeBackfillFillsOnlyRequestedFiniteHistory() {
        fixClock(LocalDateTime.of(2026, 9, 10, 10, 0));
        InspectionPlan plan = newPlan("B1", "daily", 1, "08:00",
                LocalDateTime.of(2026, 9, 10, 8, 0));
        // 同一天禁用（9-10 周期还没生成）
        planService.setEnabled(plan.getId(), false);

        // 5 天后重新启用：9-10..9-15 共 6 个缺台账周期，管理员只补最近 2 个
        fixClock(LocalDateTime.of(2026, 9, 15, 10, 0));
        InspectionScheduleService.SweepResult r = scheduleService.resume(plan.getId(), "BACKFILL", 2);
        assertEquals(2, r.generated());
        assertEquals(4, r.skipped());

        List<InspectionScheduleLedger> recs = scheduleService.ledgerRecords(plan.getId(), null, 50);
        long backfillLimited = recs.stream()
                .filter(l -> InspectionScheduleLedger.REASON_BACKFILL_LIMIT.equals(l.getSkipReason())).count();
        assertEquals(4, backfillLimited);
        assertEquals(2, taskRepo.count());
        // 补发任务带来源标记 RESUME
        assertTrue(taskRepo.findAll().stream()
                .allMatch(t -> "RESUME".equals(t.getTriggerSource())));
    }

    @Test
    void directReenableDefaultsToCurrentPeriodAndMarksPastSkipped() {
        fixClock(LocalDateTime.of(2026, 9, 15, 10, 0));
        InspectionPlan plan = newPlan("E2", "daily", 1, "08:00",
                LocalDateTime.of(2026, 9, 15, 8, 0));
        fixClock(LocalDateTime.of(2026, 9, 16, 9, 0));
        planService.setEnabled(plan.getId(), false);
        fixClock(LocalDateTime.of(2026, 9, 18, 9, 0));
        planService.setEnabled(plan.getId(), true);
        scheduleService.sweep("POLL");

        List<InspectionTask> tasks = taskRepo.findByPlanIdOrderByCreatedAtDesc(plan.getId());
        assertEquals(1, tasks.size(), "直接启用后只有当前周期一个任务");
        assertEquals("D20260918", tasks.get(0).getPeriodKey());
        List<InspectionScheduleLedger> skipped = scheduleService.ledgerRecords(plan.getId(), "SKIPPED", 50);
        // 9-15（禁用前漏扫）、9-16、9-17 三个早于当前周期的实例全部确定性留痕
        assertEquals(3, skipped.size());
    }

    @Test
    void triggerNowIsIdempotentAndPreviewReflectsStatus() {
        fixClock(LocalDateTime.of(2026, 9, 17, 9, 0));
        InspectionPlan plan = newPlan("T1", "daily", 1, "08:00",
                LocalDateTime.of(2026, 9, 17, 8, 0));

        InspectionTask t1 = scheduleService.triggerNow(plan.getId(), null);
        InspectionTask t2 = scheduleService.triggerNow(plan.getId(), null);
        assertEquals(t1.getId(), t2.getId(), "立即执行必须幂等，返回同一任务");
        assertEquals("MANUAL", t1.getTriggerSource());

        List<SchedulePreviewEntry> preview = scheduleService.preview(
                plan.getId(), LocalDateTime.of(2026, 9, 17, 0, 0), LocalDateTime.of(2026, 9, 19, 23, 59));
        assertEquals(3, preview.size());
        SchedulePreviewEntry first = preview.get(0);
        assertEquals("GENERATED", first.status());
        assertEquals(t1.getId(), first.taskId());
        assertEquals("DUE", preview.get(1).status());
    }

    @Test
    void taskTracesBackToPeriodAndLedger() {
        fixClock(LocalDateTime.of(2026, 9, 17, 9, 0));
        InspectionPlan plan = newPlan("X1", "daily", 1, "08:00",
                LocalDateTime.of(2026, 9, 17, 8, 0));
        scheduleService.sweep("STARTUP");
        InspectionTask task = taskRepo.findAll().get(0);

        Map<String, Object> trace = scheduleService.traceByTask(task.getId());
        assertEquals(true, trace.get("scheduled"));
        assertEquals("D20260917", trace.get("periodKey"));
        assertEquals(0L, trace.get("periodIndex"));
        assertEquals("STARTUP", trace.get("triggerSource"));
        assertEquals("GENERATED", trace.get("status"));
        assertEquals(plan.getId(), trace.get("planId"));
    }

    @Test
    void changingScheduleParametersBumpsVersionAndKeepsOldLedger() {
        fixClock(LocalDateTime.of(2026, 9, 17, 9, 0));
        InspectionPlan plan = newPlan("V1", "daily", 1, "08:00",
                LocalDateTime.of(2026, 9, 17, 8, 0));
        scheduleService.sweep("POLL");
        assertEquals(1, ledgerRepo.count());

        // 改成每周一次：新版本、新锚点；旧台账保留
        InspectionPlanService.PlanSpec spec = new InspectionPlanService.PlanSpec(
                null, null, null, "weekly", 1, "day", "09:00", null, null,
                null, null, null, null);
        planService.update(plan.getId(), spec);
        InspectionPlan reloaded = planRepo.findById(plan.getId()).orElseThrow();
        assertEquals(2, reloaded.getScheduleVersion());
        assertEquals(1, ledgerRepo.count(), "旧版本台账保留用于追溯");

        fixClock(LocalDateTime.of(2026, 9, 21, 10, 0));
        scheduleService.sweep("POLL");
        List<InspectionScheduleLedger> all = new ArrayList<>(ledgerRepo.findAll());
        // 新版本 weekly 锚点归一到当周周一 09-14：k=0(09-14) 与 k=1(09-21) 两个周周期，加旧版本 1 条
        assertEquals(3, all.size());
        assertEquals(1, all.stream().filter(l -> l.getScheduleVersion() == 1).count(),
                "旧版本台账保留用于追溯");
        assertTrue(all.stream().anyMatch(l -> l.getScheduleVersion() == 2
                && l.getPeriodKey().startsWith("W")));
    }

    @Test
    void backfillOverflowInSweepIsRecordedAsSkipped() {
        // 锚点远在 300 天前的日计划：超过 backfill-max(200) 的最早周期只留痕
        fixClock(LocalDateTime.of(2027, 7, 14, 12, 0));
        InspectionPlan plan = newPlan("O1", "daily", 1, "08:00",
                LocalDateTime.of(2026, 9, 17, 8, 0));
        InspectionScheduleService.SweepResult r = scheduleService.sweep("STARTUP");
        assertEquals(200, r.generated());
        assertTrue(r.skipped() > 0);
        assertTrue(scheduleService.ledgerRecords(plan.getId(), "SKIPPED", 500).stream()
                .allMatch(l -> InspectionScheduleLedger.REASON_BACKFILL_LIMIT.equals(l.getSkipReason())));
    }
}
