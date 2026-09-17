package com.admin.equipment.service.inspection.schedule;

import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionPlanPoint;
import com.admin.equipment.model.inspection.InspectionPoint;
import com.admin.equipment.model.inspection.InspectionScheduleOccurrence;
import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.model.inspection.InspectionTemplate;
import com.admin.equipment.repo.inspection.InspectionPlanPointRepository;
import com.admin.equipment.repo.inspection.InspectionPlanRepository;
import com.admin.equipment.repo.inspection.InspectionPointRepository;
import com.admin.equipment.repo.inspection.InspectionScheduleOccurrenceRepository;
import com.admin.equipment.repo.inspection.InspectionTaskRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateRepository;
import com.admin.equipment.service.inspection.InspectionPlanService;
import com.admin.equipment.service.inspection.schedule.ScheduleEngine.PlannedOccurrence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 时钟可控的端到端集成测试：正常轮询、重启/宕机补偿、禁用与重新启用、
 * 窗口过期、补齐、并发认领与任务反查。
 */
@SpringBootTest
@ActiveProfiles("test")
class InspectionScheduleIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired InspectionScheduleService scheduler;
    @Autowired InspectionPlanService planService;
    @Autowired ScheduleClock clock;
    @Autowired ScheduleProperties props;
    @Autowired InspectionPlanRepository planRepo;
    @Autowired InspectionPlanPointRepository planPointRepo;
    @Autowired InspectionPointRepository pointRepo;
    @Autowired InspectionTemplateRepository templateRepo;
    @Autowired InspectionTaskRepository taskRepo;
    @Autowired InspectionScheduleOccurrenceRepository occRepo;

    private int savedLateMinutes;

    private static Instant cn(String local) {
        return LocalDateTime.parse(local).atZone(ZONE).toInstant();
    }

    @BeforeEach
    void fixClock() {
        savedLateMinutes = props.getLateGenerateMinutes();
        clock.setFixed(cn("2026-09-17T09:30:00"));
    }

    @AfterEach
    void restore() {
        props.setLateGenerateMinutes(savedLateMinutes);
        clock.reset();
    }

    private InspectionPlan newDailyPlan(String code, String start, int windowMin) {
        InspectionTemplate tpl = new InspectionTemplate();
        tpl.setCode("TPL-" + code);
        tpl.setName("模板" + code);
        templateRepo.save(tpl);
        InspectionPoint point = new InspectionPoint();
        point.setCode("IP-" + code);
        point.setName("巡检点" + code);
        point.setCoordX(1.0);
        point.setCoordY(2.0);
        pointRepo.save(point);

        InspectionPlan plan = planService.create(new InspectionPlanService.PlanSpec(
                code, "计划" + code, tpl.getId(), "daily", 1, "day", 1, 1,
                start, start, windowMin, "班组", "", "测试", List.of(point.getId())));
        return plan;
    }

    @Test
    void pollGeneratesDueOccurrenceExactlyOnceAcrossRepeatedTicks() {
        InspectionPlan plan = newDailyPlan("PL-IT-1", "08:00", 120);

        InspectionScheduleService.TickResult r1 = tick();
        InspectionScheduleService.TickResult r2 = tick(); // 正常轮询重叠/重复

        List<InspectionTask> tasks = taskRepo.findByPlanIdOrderByCreatedAtDesc(plan.getId());
        assertEquals(1, tasks.size(), "重复轮询不得生成第二条任务");
        assertEquals(1, r1.generated());
        assertEquals(0, r2.generated());

        InspectionTask t = tasks.get(0);
        assertEquals("20260917", t.getPeriodKey());
        assertEquals("scheduler", t.getTriggerSource());
        assertEquals("TK-" + plan.getCode() + "-20260917", t.getCode());
        assertNotNull(t.getOccurrenceId());

        InspectionScheduleOccurrence occ = occRepo.findById(t.getOccurrenceId()).orElseThrow();
        assertEquals(InspectionScheduleOccurrence.GENERATED, occ.getStatus());
        assertEquals(t.getId(), occ.getTaskId());
    }

    @Test
    void expiredWindowIsSkippedWithReasonThenExplicitBackfillGenerates() {
        props.setLateGenerateMinutes(60);
        InspectionPlan plan = newDailyPlan("PL-IT-2", "08:00", 60); // 窗口 08:00-09:00
        clock.setFixed(cn("2026-09-17T11:30:00")); // 超出 60 分钟宽限

        tick();
        InspectionScheduleOccurrence occ = occRepo.findByOccurrenceKey(
                InspectionScheduleService.occurrenceKey(plan.getId(), "20260917")).orElseThrow();
        assertEquals(InspectionScheduleOccurrence.SKIPPED, occ.getStatus());
        assertEquals(InspectionScheduleOccurrence.REASON_WINDOW_EXPIRED, occ.getSkipReason());
        assertEquals(0, taskRepo.findByPlanIdOrderByCreatedAtDesc(plan.getId()).size());

        // 管理员显式补齐
        List<InspectionScheduleService.OccurrenceView> res = scheduler.backfill(
                plan.getId(), cn("2026-09-17T00:00"), cn("2026-09-18T00:00"), 10);
        assertEquals(1, res.size());
        assertEquals(InspectionScheduleOccurrence.GENERATED, res.get(0).status());
        assertEquals("backfill", res.get(0).triggerSource());
        assertEquals(1, taskRepo.findByPlanIdOrderByCreatedAtDesc(plan.getId()).size());
    }

    @Test
    void crashAfterClaimIsRecoveredOnNextTickWithoutDuplicate() {
        InspectionPlan plan = newDailyPlan("PL-IT-3", "08:00", 120);
        LocalDateTime nowLdt = LocalDateTime.ofInstant(clock.now(), ZONE);

        // 登记周期并模拟“已认领、进程崩溃”：processing 且认领时刻早于 stale 阈值
        InspectionScheduleOccurrence occ = new InspectionScheduleOccurrence();
        occ.setPlanId(plan.getId());
        occ.setPeriodKey("20260917");
        occ.setOccurrenceKey(InspectionScheduleService.occurrenceKey(plan.getId(), "20260917"));
        occ.setCycleType("daily");
        occ.setScheduledStart(LocalDateTime.parse("2026-09-17T08:00"));
        occ.setScheduledEnd(LocalDateTime.parse("2026-09-17T10:00"));
        occ.setStatus(InspectionScheduleOccurrence.PROCESSING);
        occ.setClaimedBy("dead-instance");
        occ.setClaimedAt(nowLdt.minusMinutes(10));
        occ.setAttemptCount(1);
        occ.setCreatedAt(nowLdt);
        occ.setUpdatedAt(nowLdt.minusMinutes(10));
        occRepo.saveAndFlush(occ);

        InspectionScheduleService.TickResult r = tick();
        assertEquals(1, r.generated());
        assertEquals(1, taskRepo.findByPlanIdOrderByCreatedAtDesc(plan.getId()).size());

        InspectionScheduleOccurrence reloaded = occRepo.findById(occ.getId()).orElseThrow();
        assertEquals(InspectionScheduleOccurrence.GENERATED, reloaded.getStatus());
        assertEquals(scheduler.getInstanceId(), reloaded.getClaimedBy());

        tick(); // 再扫一次也不重复
        assertEquals(1, taskRepo.findByPlanIdOrderByCreatedAtDesc(plan.getId()).size());
    }

    @Test
    void briefRestartGapWithinGraceStillGenerates() {
        InspectionPlan plan = newDailyPlan("PL-IT-4", "08:00", 240); // 窗口到 12:00
        // 服务停机：09:30 周期已到点但从未轮询过；09:35 重启后立即 tick
        clock.setFixed(cn("2026-09-17T09:35:00"));
        InspectionScheduleService.TickResult r = tick();
        assertEquals(1, r.generated());
        assertEquals(1, taskRepo.findByPlanIdOrderByCreatedAtDesc(plan.getId()).size());
    }

    @Test
    void disabledPeriodsAreNotBackfilledOnContinueAndCurrentResumes() {
        InspectionPlan plan = newDailyPlan("PL-IT-5", "08:00", 120);
        tick(); // 9/17 正常生成

        // 9/17 18:00 禁用
        clock.setFixed(cn("2026-09-17T18:00:00"));
        scheduler.disablePlan(plan.getId());
        assertFalse(planRepo.findById(plan.getId()).orElseThrow().getEnabled());

        // 跨过整个周末，9/21 09:00 重新启用，选择“从当前周期继续”
        clock.setFixed(cn("2026-09-21T09:00:00"));
        scheduler.reactivatePlan(plan.getId(), "continue", null);
        tick();

        List<InspectionTask> tasks = taskRepo.findByPlanIdOrderByCreatedAtDesc(plan.getId());
        assertEquals(2, tasks.size(), "只应有 9/17 与 9/21 两条任务");
        assertEquals("20260921", tasks.get(0).getPeriodKey());

        for (String key : new String[]{"20260918", "20260919", "20260920"}) {
            InspectionScheduleOccurrence o = occRepo.findByOccurrenceKey(
                    InspectionScheduleService.occurrenceKey(plan.getId(), key)).orElseThrow();
            assertEquals(InspectionScheduleOccurrence.SKIPPED, o.getStatus());
            assertEquals(InspectionScheduleOccurrence.REASON_DISABLED_NO_BACKFILL, o.getSkipReason(),
                    "禁用期周期必须留下可反查的跳过原因: " + key);
        }
    }

    @Test
    void reactivateWithBackfillFillsOnlyRequestedRecentPeriods() {
        InspectionPlan plan = newDailyPlan("PL-IT-6", "08:00", 60);
        clock.setFixed(cn("2026-09-17T18:00:00"));
        scheduler.disablePlan(plan.getId());

        clock.setFixed(cn("2026-09-21T09:30:00"));
        scheduler.reactivatePlan(plan.getId(), "backfill", 2); // 只补最近 2 个周期

        List<InspectionTask> tasks = taskRepo.findByPlanIdOrderByCreatedAtDesc(plan.getId());
        List<String> generatedKeys = tasks.stream().map(InspectionTask::getPeriodKey).sorted().toList();
        assertEquals(List.of("20260919", "20260920"), generatedKeys,
                "只补齐最近 2 个禁用期周期");

        InspectionScheduleOccurrence older = occRepo.findByOccurrenceKey(
                InspectionScheduleService.occurrenceKey(plan.getId(), "20260918")).orElseThrow();
        assertEquals(InspectionScheduleOccurrence.SKIPPED, older.getStatus());
        assertEquals(InspectionScheduleOccurrence.REASON_BACKFILL_LIMIT, older.getSkipReason());
    }

    @Test
    void previewShowsComputedPeriodsAndRegistrationState() {
        InspectionPlan plan = newDailyPlan("PL-IT-7", "08:00", 120);
        List<InspectionScheduleService.PreviewItem> before = scheduler.preview(plan.getId(),
                cn("2026-09-17T00:00"), cn("2026-09-19T00:00"));
        assertEquals(List.of("20260917", "20260918"),
                before.stream().map(InspectionScheduleService.PreviewItem::periodKey).toList());
        assertFalse(before.get(0).registered());

        tick();
        List<InspectionScheduleService.PreviewItem> after = scheduler.preview(plan.getId(),
                cn("2026-09-17T00:00"), cn("2026-09-19T00:00"));
        assertTrue(after.get(0).registered());
        assertEquals("generated", after.get(0).status());
        assertFalse(after.get(1).registered());
    }

    @Test
    void triggerNowRejectsDuplicateAndTraceLinksTaskToOccurrence() {
        InspectionPlan plan = newDailyPlan("PL-IT-8", "08:00", 120);
        tick();
        InspectionTask existing = taskRepo.findByPlanIdOrderByCreatedAtDesc(plan.getId()).get(0);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> scheduler.triggerNow(plan.getId(), null));
        assertTrue(ex.getMessage().contains("禁止重复生成"));

        InspectionScheduleService.TaskTrace trace = scheduler.traceByTask(existing.getId());
        assertNotNull(trace.occurrence());
        assertEquals("20260917", trace.occurrence().periodKey());
        assertEquals("generated", trace.occurrence().status());
        assertEquals(plan.getCode(), trace.occurrence().planCode());
    }

    @Test
    void triggerNowGeneratesCurrentPeriodManuallyBeforePoll() {
        InspectionPlan plan = newDailyPlan("PL-IT-9", "08:00", 120);
        InspectionTask t = scheduler.triggerNow(plan.getId(), null);
        assertEquals("manual", t.getTriggerSource());
        assertEquals(1, taskRepo.findByPlanIdOrderByCreatedAtDesc(plan.getId()).size());
        // 调度轮询后也不会重复
        tick();
        assertEquals(1, taskRepo.findByPlanIdOrderByCreatedAtDesc(plan.getId()).size());
    }

    @Test
    void claimIsExclusiveAndStaleClaimCanBeTakenOver() {
        InspectionPlan plan = newDailyPlan("PL-IT-10", "08:00", 120);
        PlannedOccurrence po = new PlannedOccurrence("20260917", "daily", "day",
                LocalDateTime.parse("2026-09-17T08:00"), LocalDateTime.parse("2026-09-17T10:00"));
        InspectionScheduleOccurrence occ = scheduler.registerOne(plan, po);

        LocalDateTime nowLdt = LocalDateTime.ofInstant(clock.now(), ZONE);
        assertTrue(scheduler.claimOccurrence(occ.getId(), nowLdt));
        assertFalse(scheduler.claimOccurrence(occ.getId(), nowLdt), "活跃认领不得被第二个实例抢走");

        // 超过 stale 阈值后可被抢占
        assertTrue(scheduler.claimOccurrence(occ.getId(), nowLdt.plusMinutes(10)));
        InspectionScheduleOccurrence reloaded = occRepo.findById(occ.getId()).orElseThrow();
        assertEquals(2, reloaded.getAttemptCount(), "每次认领都累加尝试次数");
    }

    private InspectionScheduleService.TickResult tick() {
        return scheduler.tick();
    }
}
