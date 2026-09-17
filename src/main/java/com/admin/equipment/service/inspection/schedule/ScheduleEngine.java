package com.admin.equipment.service.inspection.schedule;

import com.admin.equipment.model.inspection.InspectionPlan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.temporal.IsoFields;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 周期时刻计算引擎。
 *
 * <p>所有计算都在 {@code app.schedule.zone}（默认 Asia/Shanghai）时区内进行，存储/比较口径：
 * <ul>
 *   <li><b>夏令时缺口（spring-forward）</b>：计划本地时刻不存在时，按 {@link ZoneRules} 默认规则
 *       向后顺延缺口长度（如 02:00 → 03:00）；</li>
 *   <li><b>夏令时重叠（fall-back）</b>：同一本地时刻出现两次时，取<b>较早的偏移量</b>；</li>
 *   <li><b>窗口长度</b>一律按“物理时长” {@code timeWindowMinutes} 追加，因此跨越 DST 切换的窗口
 *       长度是确定的小时数，不受墙钟跳变影响；</li>
 *   <li><b>跨午夜班次</b>：结束时刻 ≤ 开始时刻（或按窗口分钟数算出已跨日）时，结束日期顺延一天，
 *       periodKey 以班次的“开始日”归属；</li>
 *   <li><b>月末日期</b>：dayOfMonth 超过当月天数（如 31 日遇到 2 月）时，落到当月最后一天。</li>
 * </ul>
 *
 * <p>周期键（periodKey）按周期类型固定格式，是唯一生成身份的业务部分：
 * daily/shift=yyyyMMdd，weekly=yyyyWww（ISO 周年-周数），monthly=yyyyMM，hourly=yyyyMMddHHmm。
 */
@Component
public class ScheduleEngine {

    /** 计算网格的固定锚点，保证周期划分不依赖计划创建时间，重启后结论一致。 */
    private static final LocalDate EPOCH_DATE = LocalDate.of(1970, 1, 1);
    private static final LocalDate EPOCH_MONDAY = LocalDate.of(1970, 1, 5);

    private final ZoneId zone;

    public ScheduleEngine(@Value("${app.schedule.zone:Asia/Shanghai}") String zoneId) {
        this.zone = ZoneId.of(zoneId);
    }

    public ZoneId getZone() {
        return zone;
    }

    /** 一个计划周期的计划窗口。 */
    public record PlannedOccurrence(String periodKey,
                                    String cycleType,
                                    String shiftLabel,
                                    LocalDateTime scheduledStart,
                                    LocalDateTime scheduledEnd) {
        public Instant startInstant(ZoneId z) { return scheduledStart.atZone(z).toInstant(); }
        public Instant endInstant(ZoneId z) { return scheduledEnd.atZone(z).toInstant(); }
    }

    /**
     * 返回 {@code now} 时刻所属的“当前周期”：开始时刻 ≤ now 的最近一个周期
     * （即使窗口刚结束、下一个周期还没开始，也算当前周期，用于“立即执行/从当前周期继续”）。
     */
    public PlannedOccurrence currentOccurrence(InspectionPlan plan, Instant now) {
        return occurrenceAtOrBefore(plan, now);
    }

    /** 与 {@link #currentOccurrence} 相同，但要求 now 必须严格 ≥ 该周期的开始时刻。 */
    public PlannedOccurrence occurrenceAtOrBefore(InspectionPlan plan, Instant instant) {
        ZonedDateTime zdt = instant.atZone(zone);
        String cycle = normalizeCycle(plan.getCycleType());
        int step = Math.max(1, plan.getCycleValue() == null ? 1 : plan.getCycleValue());
        return switch (cycle) {
            case "hourly" -> hourlyAtOrBefore(plan, zdt, step);
            case "weekly" -> weeklyAtOrBefore(plan, zdt, step);
            case "monthly" -> monthlyAtOrBefore(plan, zdt, step);
            case "shift" -> shiftAtOrBefore(plan, zdt);
            default -> dailyAtOrBefore(plan, zdt, step);
        };
    }

    /**
     * 列出开始时刻落在 [from, to) 的全部周期（两端均为物理时间 Instant）。
     * 调度器的查询窗口由调用方限定，本方法只做小步遍历。
     */
    public List<PlannedOccurrence> occurrencesBetween(InspectionPlan plan, Instant from, Instant to) {
        if (!to.isAfter(from)) return List.of();
        String cycle = normalizeCycle(plan.getCycleType());
        int step = Math.max(1, plan.getCycleValue() == null ? 1 : plan.getCycleValue());
        List<PlannedOccurrence> out = new ArrayList<>();

        switch (cycle) {
            case "hourly" -> {
                PlannedOccurrence cur = hourlyAtOrBefore(plan, from.atZone(zone), step);
                PlannedOccurrence first = cur.startInstant(zone).isBefore(from) ? nextHourly(plan, cur, step) : cur;
                PlannedOccurrence o = first;
                while (o.startInstant(zone).isBefore(to)) {
                    if (!o.startInstant(zone).isBefore(from)) out.add(o);
                    o = nextHourly(plan, o, step);
                }
            }
            case "daily" -> {
                PlannedOccurrence cur = dailyAtOrBefore(plan, from.atZone(zone), step);
                PlannedOccurrence o = cur.startInstant(zone).isBefore(from) ? nextDaily(plan, cur, step) : cur;
                while (o.startInstant(zone).isBefore(to)) {
                    if (!o.startInstant(zone).isBefore(from)) out.add(o);
                    o = nextDaily(plan, o, step);
                }
            }
            case "shift" -> {
                PlannedOccurrence cur = shiftAtOrBefore(plan, from.atZone(zone));
                PlannedOccurrence o = cur.startInstant(zone).isBefore(from) ? nextShift(plan, cur) : cur;
                while (o.startInstant(zone).isBefore(to)) {
                    if (!o.startInstant(zone).isBefore(from)) out.add(o);
                    o = nextShift(plan, o);
                }
            }
            case "weekly" -> {
                PlannedOccurrence cur = weeklyAtOrBefore(plan, from.atZone(zone), step);
                PlannedOccurrence o = cur.startInstant(zone).isBefore(from) ? nextWeekly(plan, cur, step) : cur;
                while (o.startInstant(zone).isBefore(to)) {
                    if (!o.startInstant(zone).isBefore(from)) out.add(o);
                    o = nextWeekly(plan, o, step);
                }
            }
            case "monthly" -> {
                PlannedOccurrence cur = monthlyAtOrBefore(plan, from.atZone(zone), step);
                PlannedOccurrence o = cur.startInstant(zone).isBefore(from) ? nextMonthly(plan, cur, step) : cur;
                while (o.startInstant(zone).isBefore(to)) {
                    if (!o.startInstant(zone).isBefore(from)) out.add(o);
                    o = nextMonthly(plan, o, step);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // hourly：每自然日从 startTime 的分钟数起，每 step 小时一次；step 须整除 24
    // ------------------------------------------------------------------
    private PlannedOccurrence hourlyAtOrBefore(InspectionPlan plan, ZonedDateTime zdt, int step) {
        int grid = (24 % step == 0) ? step : 1;
        int minute0 = parseTime(plan.getStartTime()).getMinute();
        int h = zdt.getHour();
        if (zdt.getMinute() < minute0) h -= 1;
        while (h >= 0 && h % grid != 0) h -= 1;
        if (h < 0) {
            return buildHourly(plan, zdt.toLocalDate().minusDays(1), 24 - grid, minute0, grid);
        }
        return buildHourly(plan, zdt.toLocalDate(), h, minute0, grid);
    }

    private PlannedOccurrence nextHourly(InspectionPlan plan, PlannedOccurrence cur, int step) {
        int grid = (24 % step == 0) ? step : 1;
        LocalDateTime ls = cur.scheduledStart();
        int nextHour = ls.getHour() + grid;
        LocalDate date = ls.toLocalDate();
        if (nextHour >= 24) {
            date = date.plusDays(1);
            nextHour -= 24;
        }
        // 直接按计划规则重建，保证窗口分钟数与 DST 解析口径始终一致
        return buildHourly(plan, date, nextHour, ls.getMinute(), grid);
    }

    private PlannedOccurrence buildHourly(InspectionPlan plan, LocalDate date, int hour, int minute, int grid) {
        LocalDateTime start = date.atTime(hour, minute);
        Integer win = plan.getTimeWindowMinutes();
        long windowMinutes;
        if (win != null && win > 0) {
            // 窗口不允许侵入下一个小时刻度，避免与下一周期重叠
            windowMinutes = Math.min(win, grid * 60L);
        } else {
            windowMinutes = grid * 60L;
        }
        LocalDateTime end = start.plusMinutes(windowMinutes);
        String key = start.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        return new PlannedOccurrence(key, "hourly", plan.getShiftType(), start, end);
    }

    // ------------------------------------------------------------------
    // daily：距 1970-01-01 的天数对 step 取模为 0 的日期
    // ------------------------------------------------------------------
    private PlannedOccurrence dailyAtOrBefore(InspectionPlan plan, ZonedDateTime zdt, int step) {
        LocalDate d = zdt.toLocalDate();
        if (zdt.toLocalTime().isBefore(parseTime(plan.getStartTime()))) d = d.minusDays(1);
        long days = ChronoUnit.DAYS.between(EPOCH_DATE, d);
        long rem = Math.floorMod(days, step);
        LocalDate anchor = d.minusDays(rem);
        return buildDayLike(plan, anchor, "daily", null);
    }

    private PlannedOccurrence nextDaily(InspectionPlan plan, PlannedOccurrence cur, int step) {
        return buildDayLike(plan, cur.scheduledStart().toLocalDate().plusDays(step), "daily", null);
    }

    // ------------------------------------------------------------------
    // shift：每天一个班次；periodKey 以开始日归属并带班次后缀
    // ------------------------------------------------------------------
    private PlannedOccurrence shiftAtOrBefore(InspectionPlan plan, ZonedDateTime zdt) {
        LocalDate d = zdt.toLocalDate();
        ZonedDateTime candidate = resolveStart(plan, d);
        if (zdt.toInstant().isBefore(candidate.toInstant())) d = d.minusDays(1);
        return buildDayLike(plan, d, "shift", shiftSuffix(plan.getShiftType()));
    }

    private PlannedOccurrence nextShift(InspectionPlan plan, PlannedOccurrence cur) {
        return buildDayLike(plan, cur.scheduledStart().toLocalDate().plusDays(1),
                "shift", shiftSuffix(plan.getShiftType()));
    }

    // ------------------------------------------------------------------
    // weekly：ISO 周几触发，按距 1970-01-05（周一）的周数对 step 取模
    // ------------------------------------------------------------------
    private PlannedOccurrence weeklyAtOrBefore(InspectionPlan plan, ZonedDateTime zdt, int step) {
        int dow = clamp(plan.getDayOfWeek(), 1, 7, 1);
        LocalDate d = zdt.toLocalDate();
        LocalDate thisWeekDay = d.with(java.time.DayOfWeek.of(dow));
        ZonedDateTime candidate = resolveStart(plan, thisWeekDay);
        if (zdt.toInstant().isBefore(candidate.toInstant())) thisWeekDay = thisWeekDay.minusWeeks(1);
        // 锚点必须本身就是配置的周几，多周步长对齐才不会漂移到别的星期
        LocalDate dowEpoch = EPOCH_MONDAY.plusDays(dow - 1L);
        long weeks = ChronoUnit.WEEKS.between(dowEpoch, thisWeekDay);
        long k = Math.floorDiv(weeks, step);
        LocalDate anchor = dowEpoch.plusWeeks(k * step);
        return buildDayLike(plan, anchor, "weekly", null);
    }

    private PlannedOccurrence nextWeekly(InspectionPlan plan, PlannedOccurrence cur, int step) {
        // cur 锚点本身即配置的周几，加 step 周后仍落在该周几；buildDayLike 重新做 DST 解析
        return buildDayLike(plan, cur.scheduledStart().toLocalDate().plusWeeks(step), "weekly", null);
    }

    // ------------------------------------------------------------------
    // monthly：距 1970-01 的月数对 step 取模；日号超过当月长度则落到月末
    // ------------------------------------------------------------------
    private PlannedOccurrence monthlyAtOrBefore(InspectionPlan plan, ZonedDateTime zdt, int step) {
        int dom = clamp(plan.getDayOfMonth(), 1, 31, 1);
        YearMonth ym = YearMonth.from(zdt.toLocalDate());
        LocalDate candidateDate = monthDate(ym, dom);
        ZonedDateTime candidate = resolveStart(plan, candidateDate);
        if (zdt.toInstant().isBefore(candidate.toInstant())) ym = ym.minusMonths(1);
        long months = (long) (ym.getYear() - 1970) * 12 + (ym.getMonthValue() - 1);
        long k = Math.floorDiv(months, step);
        YearMonth anchor = YearMonth.of(1970, 1).plusMonths(k * step);
        return buildMonthly(plan, anchor, dom);
    }

    private PlannedOccurrence nextMonthly(InspectionPlan plan, PlannedOccurrence cur, int step) {
        YearMonth next = YearMonth.from(cur.scheduledStart().toLocalDate()).plusMonths(step);
        return buildMonthly(plan, next, clamp(plan.getDayOfMonth(), 1, 31, 1));
    }

    private PlannedOccurrence buildMonthly(InspectionPlan plan, YearMonth ym, int dom) {
        LocalDate anchor = monthDate(ym, dom);
        return buildDayLike(plan, anchor, "monthly", null);
    }

    private LocalDate monthDate(YearMonth ym, int dom) {
        return LocalDate.of(ym.getYear(), ym.getMonth(), Math.min(dom, ym.lengthOfMonth()));
    }

    // ------------------------------------------------------------------
    // 公共构造
    // ------------------------------------------------------------------
    private PlannedOccurrence buildDayLike(InspectionPlan plan, LocalDate anchor, String cycle, String shiftSuffix) {
        LocalTime st = parseTime(plan.getStartTime());
        // 经 ZonedDateTime 解析：DST 缺口自动顺延（02:30→03:30），重叠取较早偏移
        ZonedDateTime zStart = ZonedDateTime.of(anchor, st, zone);
        LocalDateTime start = zStart.toLocalDateTime();
        LocalDateTime end = resolveEndFrom(zStart, plan);
        String key = periodKey(cycle, anchor, start, plan.getShiftType(), shiftSuffix);
        return new PlannedOccurrence(key, cycle, plan.getShiftType(), start, end);
    }

    /**
     * 窗口按物理分钟数从已解析的开始时刻追加，因此跨越 DST 切换时窗口长度确定；
     * 无窗口分钟数时用 endTime（≤startTime 视为跨午夜），同样经 ZonedDateTime 解析。
     */
    private LocalDateTime resolveEndFrom(ZonedDateTime zStart, InspectionPlan plan) {
        Integer win = plan.getTimeWindowMinutes();
        ZonedDateTime zEnd;
        if (win != null && win > 0) {
            zEnd = zStart.plusMinutes(win);
        } else {
            LocalTime st = zStart.toLocalTime();
            LocalTime et = parseTime(plan.getEndTime());
            LocalDate endDate = !et.isAfter(st) ? zStart.toLocalDate().plusDays(1) : zStart.toLocalDate();
            zEnd = ZonedDateTime.of(endDate, et, zone);
        }
        return zEnd.toLocalDateTime();
    }

    private ZonedDateTime resolveStart(InspectionPlan plan, LocalDate anchor) {
        return ZonedDateTime.of(anchor, parseTime(plan.getStartTime()), zone);
    }

    private String periodKey(String cycle, LocalDate anchor, LocalDateTime start,
                             String shiftType, String shiftSuffix) {
        return switch (cycle) {
            case "daily" -> anchor.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            case "shift" -> anchor.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                    + "-" + (shiftSuffix != null ? shiftSuffix : shiftSuffix(shiftType));
            case "weekly" -> {
                int wy = anchor.get(IsoFields.WEEK_BASED_YEAR);
                int wn = anchor.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                yield String.format("%04dW%02d", wy, wn);
            }
            case "monthly" -> String.format("%04d%02d", anchor.getYear(), anchor.getMonthValue());
            default -> start.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        };
    }

    private String shiftSuffix(String shiftType) {
        if ("night".equalsIgnoreCase(shiftType)) return "NIGHT";
        if ("middle".equalsIgnoreCase(shiftType)) return "MIDDLE";
        return "DAY";
    }

    private LocalTime parseTime(String s) {
        if (s == null || s.isBlank()) return LocalTime.of(8, 0);
        try {
            String[] parts = s.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return LocalTime.of(h % 24, m % 60);
        } catch (Exception e) {
            return LocalTime.of(8, 0);
        }
    }

    private String normalizeCycle(String c) {
        if (c == null) return "daily";
        return switch (c) {
            case "daily", "weekly", "monthly", "shift", "hourly" -> c;
            default -> "daily";
        };
    }

    private int clamp(Integer v, int lo, int hi, int dflt) {
        if (v == null) return dflt;
        return Math.max(lo, Math.min(hi, v));
    }
}
