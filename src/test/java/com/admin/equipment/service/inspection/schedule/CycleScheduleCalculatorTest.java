package com.admin.equipment.service.inspection.schedule;

import com.admin.equipment.model.inspection.InspectionPlan;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 周期计算纯单元测试：跨午夜、月末、夏令时规则必须确定。 */
class CycleScheduleCalculatorTest {

    private final ZoneId shanghai = ZoneId.of("Asia/Shanghai");
    private final CycleScheduleCalculator calc = new CycleScheduleCalculator(shanghai);

    private InspectionPlan plan(String cycle, int n, String start, LocalDateTime anchor) {
        InspectionPlan p = new InspectionPlan();
        p.setCode("P");
        p.setCycleType(cycle);
        p.setCycleValue(n);
        p.setStartTime(start);
        p.setEndTime("18:00");
        p.setTimeWindowMinutes(120);
        p.setScheduleAnchor(anchor);
        p.setShiftType("night");
        return p;
    }

    @Test
    void dailySequenceAndKeys() {
        InspectionPlan p = plan("daily", 1, "08:00", LocalDateTime.of(2026, 9, 17, 8, 0));
        assertEquals("D20260917", calc.instance(p, 0).key());
        assertEquals(LocalDateTime.of(2026, 9, 18, 8, 0), calc.instance(p, 1).scheduledAt());
        assertEquals(1L, calc.indexAt(p, LocalDateTime.of(2026, 9, 18, 7, 59)));
        assertEquals(2L, calc.indexAt(p, LocalDateTime.of(2026, 9, 19, 8, 0)));
    }

    @Test
    void dailyWithCycleValueTwo() {
        InspectionPlan p = plan("daily", 2, "08:00", LocalDateTime.of(2026, 9, 1, 8, 0));
        assertEquals(LocalDateTime.of(2026, 9, 1, 8, 0), calc.instance(p, 0).scheduledAt());
        assertEquals(LocalDateTime.of(2026, 9, 3, 8, 0), calc.instance(p, 1).scheduledAt());
        assertEquals(0L, calc.indexAt(p, LocalDateTime.of(2026, 9, 2, 23, 0)));
        assertEquals(1L, calc.indexAt(p, LocalDateTime.of(2026, 9, 3, 0, 0)));
    }

    @Test
    void weeklyFloorsToMonday() {
        // 2026-09-17 是周四，序列仍从周一 09-14 开始
        InspectionPlan p = plan("weekly", 1, "09:00", LocalDateTime.of(2026, 9, 17, 9, 0));
        assertEquals(LocalDateTime.of(2026, 9, 14, 9, 0), calc.instance(p, 0).scheduledAt());
        assertEquals("W20260914", calc.instance(p, 0).key());
        assertEquals(LocalDateTime.of(2026, 9, 21, 9, 0), calc.instance(p, 1).scheduledAt());
    }

    @Test
    void monthlyClampsToLastDayOfMonth() {
        InspectionPlan p = plan("monthly", 1, "08:00", LocalDateTime.of(2026, 1, 31, 8, 0));
        assertEquals(LocalDateTime.of(2026, 1, 31, 8, 0), calc.instance(p, 0).scheduledAt());
        assertEquals(LocalDateTime.of(2026, 2, 28, 8, 0), calc.instance(p, 1).scheduledAt(),
                "锚点31日遇2月应落在月末28日");
        assertEquals(LocalDateTime.of(2026, 3, 31, 8, 0), calc.instance(p, 2).scheduledAt());
        assertEquals(LocalDateTime.of(2026, 4, 30, 8, 0), calc.instance(p, 3).scheduledAt(),
                "4月只有30天");
        assertEquals("M202602", calc.instance(p, 1).key());
    }

    @Test
    void hourlySequenceKeysAndIndex() {
        InspectionPlan p = plan("hourly", 2, "08:00", LocalDateTime.of(2026, 9, 17, 8, 0));
        assertEquals("H20260917T0800+0800", calc.instance(p, 0).key());
        assertEquals(LocalDateTime.of(2026, 9, 17, 10, 0), calc.instance(p, 1).scheduledAt());
        assertEquals(LocalDateTime.of(2026, 9, 17, 12, 0), calc.instance(p, 2).scheduledAt());
        assertEquals(0L, calc.indexAt(p, LocalDateTime.of(2026, 9, 17, 9, 59)),
                "10:00 前仍属于第 0 个周期");
        assertEquals(1L, calc.indexAt(p, LocalDateTime.of(2026, 9, 17, 10, 0)));
        assertEquals(2L, calc.indexAt(p, LocalDateTime.of(2026, 9, 17, 12, 0)));
    }

    @Test
    void shiftOneInstancePerNaturalDayWindowBelongsToStartDate() {
        InspectionPlan p = plan("shift", 1, "22:00", LocalDateTime.of(2026, 9, 17, 22, 0));
        assertEquals("S20260917-night", calc.instance(p, 0).key());
        assertEquals(LocalDateTime.of(2026, 9, 18, 22, 0), calc.instance(p, 1).scheduledAt());
        List<CycleScheduleCalculator.PeriodInstance> list = calc.enumerate(
                p, LocalDateTime.of(2026, 9, 17, 20, 0), LocalDateTime.of(2026, 9, 19, 23, 0));
        assertEquals(3, list.size());
    }

    @Test
    void crossMidnightWindowRollsEndToNextDay() {
        InspectionPlan p = plan("shift", 1, "22:00", LocalDateTime.of(2026, 9, 17, 22, 0));
        p.setTimeWindowMinutes(null);
        p.setEndTime("06:00");
        CycleScheduleCalculator.Window w = calc.windowOf(p, LocalDateTime.of(2026, 9, 17, 22, 0));
        assertEquals(LocalDateTime.of(2026, 9, 17, 22, 0), w.start());
        assertEquals(LocalDateTime.of(2026, 9, 18, 6, 0), w.end(),
                "跨午夜班次窗结束必须顺延到下一自然日");
    }

    @Test
    void explicitTimeWindowCanAlsoCrossMidnight() {
        InspectionPlan p = plan("shift", 1, "23:00", LocalDateTime.of(2026, 9, 17, 23, 0));
        p.setTimeWindowMinutes(480);
        CycleScheduleCalculator.Window w = calc.windowOf(p, LocalDateTime.of(2026, 9, 17, 23, 0));
        assertEquals(LocalDateTime.of(2026, 9, 18, 7, 0), w.end());
    }

    @Test
    void daylightSavingGapIsAdjustedForwardDeterministically() {
        ZoneId ny = ZoneId.of("America/New_York");
        CycleScheduleCalculator nyCalc = new CycleScheduleCalculator(ny);
        InspectionPlan p = new InspectionPlan();
        p.setCode("NY");
        p.setCycleType("daily");
        p.setCycleValue(1);
        p.setStartTime("02:30");
        p.setTimeWindowMinutes(60);
        p.setScheduleAnchor(LocalDateTime.of(2026, 3, 8, 2, 30));
        // 2026-03-08 02:30 是春令跳时间隙（02→03），按 WITH_GAP 规则顺延到 03:30-04:00
        CycleScheduleCalculator.PeriodInstance pi = nyCalc.instance(p, 0, ny);
        assertEquals(LocalDateTime.of(2026, 3, 8, 3, 30), pi.scheduledAt());
    }

    @Test
    void daylightSavingOverlapTakesFirstOccurrence() {
        ZoneId ny = ZoneId.of("America/New_York");
        CycleScheduleCalculator nyCalc = new CycleScheduleCalculator(ny);
        InspectionPlan p = new InspectionPlan();
        p.setCode("NY");
        p.setCycleType("daily");
        p.setCycleValue(1);
        p.setStartTime("01:30");
        p.setTimeWindowMinutes(60);
        p.setScheduleAnchor(LocalDateTime.of(2026, 11, 1, 1, 30));
        // 秋令回拨 01:30 出现两次，取偏移较大（-0400 EDT）的第一次
        assertEquals("D20261101", nyCalc.instance(p, 0, ny).key());
        java.time.ZonedDateTime zdt = java.time.ZonedDateTime.of(
                nyCalc.instance(p, 0, ny).scheduledAt(), ny);
        assertEquals(java.time.ZoneOffset.ofHours(-4), zdt.getOffset());
    }

    @Test
    void hourlyAcrossDstGapSkipsNoPeriodAndKeysStayUnique() {
        ZoneId ny = ZoneId.of("America/New_York");
        CycleScheduleCalculator nyCalc = new CycleScheduleCalculator(ny);
        InspectionPlan p = new InspectionPlan();
        p.setCode("NY");
        p.setCycleType("hourly");
        p.setCycleValue(1);
        p.setStartTime("00:00");
        p.setTimeWindowMinutes(30);
        p.setScheduleAnchor(LocalDateTime.of(2026, 3, 8, 0, 0));
        List<String> keys = new java.util.ArrayList<>();
        for (long k = 0; k < 6; k++) {
            CycleScheduleCalculator.PeriodInstance pi = nyCalc.instance(p, k, ny);
            keys.add(pi.key());
            // 每个实例的本地时刻必须真实存在
            assertDoesNotThrow(() -> java.time.ZonedDateTime.of(pi.scheduledAt(), ny)
                    .toInstant().atZone(ny));
        }
        assertEquals(keys.size(), keys.stream().distinct().count(), "夏令时切换期周期键不得重复");
        // 00:00 EST、01:00 EST 后直接是 03:00 EDT，不存在 02:00 本地时刻
        assertFalse(keys.contains("H20260308T0200-0500"));
    }

    @Test
    void enumerateBoundsAreInclusiveAndOrdered() {
        InspectionPlan p = plan("daily", 1, "08:00", LocalDateTime.of(2026, 9, 17, 8, 0));
        List<CycleScheduleCalculator.PeriodInstance> all = calc.enumerate(
                p, LocalDateTime.of(2026, 9, 18, 8, 0), LocalDateTime.of(2026, 9, 20, 8, 0));
        assertEquals(3, all.size());
        assertEquals("D20260918", all.get(0).key());
        assertEquals("D20260920", all.get(2).key());
    }
}
