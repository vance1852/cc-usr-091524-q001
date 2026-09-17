package com.admin.equipment.service.inspection.schedule;

import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.service.inspection.schedule.ScheduleEngine.PlannedOccurrence;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 周期时刻纯函数测试：跨午夜班次、月末日期、夏令时缺口/重叠必须有确定结论。
 */
class ScheduleEngineTest {

    private final ScheduleEngine engine = new ScheduleEngine("Asia/Shanghai");
    private final ScheduleEngine nyEngine = new ScheduleEngine("America/New_York");
    private final ZoneId cn = ZoneId.of("Asia/Shanghai");

    private InspectionPlan plan(String cycle, Integer step, String shift, String start, Integer windowMin,
                                 Integer dayOfWeek, Integer dayOfMonth) {
        InspectionPlan p = new InspectionPlan();
        p.setId(1L);
        p.setCode("P");
        p.setCycleType(cycle);
        p.setCycleValue(step == null ? 1 : step);
        p.setShiftType(shift == null ? "day" : shift);
        p.setStartTime(start);
        p.setEndTime(start);
        p.setTimeWindowMinutes(windowMin);
        p.setDayOfWeek(dayOfWeek == null ? 1 : dayOfWeek);
        p.setDayOfMonth(dayOfMonth == null ? 1 : dayOfMonth);
        return p;
    }

    private Instant at(String zone, String local) {
        return LocalDateTime.parse(local).atZone(ZoneId.of(zone)).toInstant();
    }

    @Test
    void dailyWindowAndPeriodKey() {
        InspectionPlan p = plan("daily", 1, "day", "08:00", 120, null, null);
        PlannedOccurrence o = engine.currentOccurrence(p, at("Asia/Shanghai", "2026-09-17T09:00"));
        assertEquals("20260917", o.periodKey());
        assertEquals(LocalDateTime.of(2026, 9, 17, 8, 0), o.scheduledStart());
        assertEquals(LocalDateTime.of(2026, 9, 17, 10, 0), o.scheduledEnd());

        // 08:00 之前属于前一天周期
        PlannedOccurrence early = engine.currentOccurrence(p, at("Asia/Shanghai", "2026-09-17T07:59"));
        assertEquals("20260916", early.periodKey());
    }

    @Test
    void dailyMultiStepAlignmentAnchorsOnConfiguredGrid() {
        // 每 2 天：9/16、9/18 为周期日；在 9/19 看到的是 9/18
        InspectionPlan p = plan("daily", 2, "day", "08:00", 60, null, null);
        assertEquals("20260916", engine.currentOccurrence(p, at("Asia/Shanghai", "2026-09-16T09:00")).periodKey());
        assertEquals("20260916", engine.currentOccurrence(p, at("Asia/Shanghai", "2026-09-17T09:00")).periodKey());
        assertEquals("20260918", engine.currentOccurrence(p, at("Asia/Shanghai", "2026-09-18T09:00")).periodKey());
        assertEquals("20260918", engine.currentOccurrence(p, at("Asia/Shanghai", "2026-09-19T20:00")).periodKey());
    }

    @Test
    void shiftSpansMidnightAndPeriodBelongsToStartDate() {
        InspectionPlan p = plan("shift", 1, "night", "22:00", 180, null, null);
        // 9/17 凌晨 00:30 仍属于 9/16 开始的夜班，窗口结束于 9/17 01:00
        PlannedOccurrence o = engine.currentOccurrence(p, at("Asia/Shanghai", "2026-09-17T00:30"));
        assertEquals("20260916-NIGHT", o.periodKey());
        assertEquals(LocalDateTime.of(2026, 9, 16, 22, 0), o.scheduledStart());
        assertEquals(LocalDateTime.of(2026, 9, 17, 1, 0), o.scheduledEnd());

        // 21:59 还属于前一晚的班次
        assertEquals("20260915-NIGHT", engine.currentOccurrence(p, at("Asia/Shanghai", "2026-09-16T21:59")).periodKey());
        assertEquals("20260916-NIGHT", engine.currentOccurrence(p, at("Asia/Shanghai", "2026-09-16T22:00")).periodKey());
    }

    @Test
    void weeklyUsesConfiguredWeekdayAndIsoKey() {
        InspectionPlan p = plan("weekly", 1, "day", "09:00", 240, 5, null); // 周五
        // 2026-09-18 是周五
        PlannedOccurrence o = engine.currentOccurrence(p, at("Asia/Shanghai", "2026-09-18T10:00"));
        assertEquals(LocalDate.of(2026, 9, 18), o.scheduledStart().toLocalDate());
        assertEquals("2026W38", o.periodKey());

        // 周四看到上周五 9/11（ISO W37）
        PlannedOccurrence before = engine.currentOccurrence(p, at("Asia/Shanghai", "2026-09-17T23:00"));
        assertEquals("2026W37", before.periodKey());
        assertEquals(DayOfWeek.FRIDAY, before.scheduledStart().getDayOfWeek());
    }

    @Test
    void weeklyMultiStepKeepsWeekday() {
        InspectionPlan p = plan("weekly", 2, "day", "09:00", 60, 3, null); // 每两周周三
        PlannedOccurrence o1 = engine.currentOccurrence(p, at("Asia/Shanghai", "2026-09-16T10:00"));
        assertEquals(DayOfWeek.WEDNESDAY, o1.scheduledStart().getDayOfWeek());
        PlannedOccurrence o2 = engine.currentOccurrence(p, at("Asia/Shanghai", "2026-09-30T10:00"));
        assertEquals(DayOfWeek.WEDNESDAY, o2.scheduledStart().getDayOfWeek());
        assertEquals(14, ChronoUnit.DAYS.between(o1.scheduledStart().toLocalDate(), o2.scheduledStart().toLocalDate()));
    }

    @Test
    void monthlyDay31ClampsToLastDayOfShortMonth() {
        InspectionPlan p = plan("monthly", 1, "day", "09:00", 60, null, 31);
        // 2026-02 只有 28 天 → 落到 2/28
        PlannedOccurrence feb = engine.currentOccurrence(p, at("Asia/Shanghai", "2026-02-28T12:00"));
        assertEquals("202602", feb.periodKey());
        assertEquals(LocalDate.of(2026, 2, 28), feb.scheduledStart().toLocalDate());

        // 3 月仍在 31 日；2026-04-31 不存在 → 4/30
        PlannedOccurrence apr = engine.currentOccurrence(p, at("Asia/Shanghai", "2026-04-30T12:00"));
        assertEquals("202604", apr.periodKey());
        assertEquals(LocalDate.of(2026, 4, 30), apr.scheduledStart().toLocalDate());
    }

    @Test
    void monthlyBeforeTriggerBelongsToPreviousMonth() {
        InspectionPlan p = plan("monthly", 1, "day", "09:00", 60, null, 15);
        PlannedOccurrence o = engine.currentOccurrence(p, at("Asia/Shanghai", "2026-09-10T08:00"));
        assertEquals("202608", o.periodKey());
        assertEquals(LocalDate.of(2026, 8, 15), o.scheduledStart().toLocalDate());
    }

    @Test
    void hourlyGridStartsAtConfiguredMinuteAndAdvancesByStep() {
        InspectionPlan p = plan("hourly", 2, "day", "08:30", 60, null, null); // 每 2 小时
        PlannedOccurrence o = engine.currentOccurrence(p, at("Asia/Shanghai", "2026-09-17T09:15"));
        assertEquals("202609170830", o.periodKey());
        assertEquals(LocalDateTime.of(2026, 9, 17, 8, 30), o.scheduledStart());

        // 分钟未到 → 退回上一个偶数小时
        assertEquals("202609170830", engine.currentOccurrence(p, at("Asia/Shanghai", "2026-09-17T10:29")).periodKey());
        assertEquals("202609171030", engine.currentOccurrence(p, at("Asia/Shanghai", "2026-09-17T10:30")).periodKey());

        List<PlannedOccurrence> day = engine.occurrencesBetween(p,
                at("Asia/Shanghai", "2026-09-17T00:00"), at("Asia/Shanghai", "2026-09-18T00:00"));
        // 每 2 小时一个刻度，分钟取 startTime 的 30 分，全天 12 个
        assertEquals(List.of("202609170030", "202609170230", "202609170430", "202609170630",
                "202609170830", "202609171030", "202609171230", "202609171430", "202609171630",
                "202609171830", "202609172030", "202609172230"),
                day.stream().map(PlannedOccurrence::periodKey).toList());
    }

    @Test
    void occurrencesBetweenIsBoundedAndHalfOpen() {
        InspectionPlan p = plan("daily", 1, "day", "08:00", 60, null, null);
        List<PlannedOccurrence> week = engine.occurrencesBetween(p,
                at("Asia/Shanghai", "2026-09-14T00:00"), at("Asia/Shanghai", "2026-09-17T00:00"));
        assertEquals(List.of("20260914", "20260915", "20260916"),
                week.stream().map(PlannedOccurrence::periodKey).toList());
    }

    @Test
    void dstSpringForwardShiftsNonExistentLocalTimeForward() {
        // 纽约 2026-03-08 02:00 跳成 03:00（缺口 1 小时），02:30 不存在 → 顺延 03:30
        InspectionPlan p = nyPlan();
        PlannedOccurrence o = nyEngine.currentOccurrence(p,
                at("America/New_York", "2026-03-08T06:00"));
        assertEquals(LocalDateTime.of(2026, 3, 8, 3, 30), o.scheduledStart(),
                "缺口内的本地时刻必须顺延，而不是静默改到其它星期");
        // 窗口按物理时长 120 分钟：03:30-04:00(EDT) + 120min = 05:30
        assertEquals(LocalDateTime.of(2026, 3, 8, 5, 30), o.scheduledEnd());
        // 物理偏移确实是 -04:00（夏令时）
        ZoneOffset offset = o.scheduledStart().atZone(ZoneId.of("America/New_York")).getOffset();
        assertEquals(ZoneOffset.ofHours(-4), offset);
    }

    @Test
    void dstFallBackTakesEarlierOffsetAndPhysicalWindow() {
        // 纽约 2026-11-01 02:00 回退到 01:00；01:30 出现两次，取较早偏移 -04:00(EDT)
        InspectionPlan p = nyOverlapPlan();
        PlannedOccurrence o = nyEngine.currentOccurrence(p,
                at("America/New_York", "2026-11-01T04:00"));
        assertEquals(LocalDateTime.of(2026, 11, 1, 1, 30), o.scheduledStart());
        ZonedDateTime zStart = o.scheduledStart().atZone(ZoneId.of("America/New_York"));
        // withEarlierOffsetAtOverlap 语义
        assertEquals(ZoneOffset.ofHours(-4), zStart.getOffset());
        // 物理 120 分钟窗口：01:30(-04) + 120min = 07:30Z = 02:30(-05)，
        // 即跨过重复小时后墙钟只前进 60 分钟
        assertEquals(LocalDateTime.of(2026, 11, 1, 2, 30), o.scheduledEnd());
        assertEquals(120, Duration.between(
                o.scheduledStart().atZone(ZoneId.of("America/New_York")),
                o.scheduledEnd().atZone(ZoneId.of("America/New_York"))).toMinutes());
    }

    private InspectionPlan nyPlan() {
        InspectionPlan p = plan("weekly", 1, "day", "02:30", 120, 7, null); // 周日
        return p;
    }

    @Test
    void enumeratedWindowsAcrossDstTransitionsKeepPhysicalLength() {
        InspectionPlan p = nyOverlapPlan(); // 每周日 01:30，窗口 120 物理分钟
        ZoneId ny = ZoneId.of("America/New_York");
        List<PlannedOccurrence> occs = nyEngine.occurrencesBetween(p,
                at("America/New_York", "2026-10-18T12:00"),
                at("America/New_York", "2026-11-09T12:00"));
        assertEquals(List.of("2026W43", "2026W44", "2026W45"),
                occs.stream().map(PlannedOccurrence::periodKey).toList());
        for (PlannedOccurrence o : occs) {
            long physicalMinutes = Duration.between(
                    o.scheduledStart().atZone(ny), o.scheduledEnd().atZone(ny)).toMinutes();
            assertEquals(120, physicalMinutes,
                    "周期 " + o.periodKey() + " 的窗口物理时长必须恒定");
        }
        // 跨 DST 那周（11/1，ISO W44）的结束本地时刻按规则为 02:30，而非平周的 03:30
        PlannedOccurrence fallBackWeek = occs.stream()
                .filter(o -> o.periodKey().equals("2026W44")).findFirst().orElseThrow();
        assertEquals(LocalDateTime.of(2026, 11, 1, 2, 30), fallBackWeek.scheduledEnd());
    }

    private InspectionPlan nyOverlapPlan() {
        return plan("weekly", 1, "day", "01:30", 120, 7, null); // 周日，落入重叠小时
    }
}
