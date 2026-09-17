package com.admin.equipment.service.inspection.schedule;

import com.admin.equipment.model.inspection.InspectionPlan;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 巡检周期计算器：把计划的周期配置解释为确定性的周期实例序列。
 *
 * 周期规则（厂区本地墙钟时间，时区由 {@link ScheduleClock} 统一提供）：
 * <ul>
 *   <li>hourly： 从锚点起每 cycleValue 小时一个实例，按时间轴精确步进，
 *       夏令时切换当天不会多发/少发；周期键带 UTC 偏移，避免回拨时刻撞键。</li>
 *   <li>daily：  每 cycleValue 天一次，时刻为 startTime。</li>
 *   <li>weekly： 每 cycleValue 周一次，固定在 ISO 周一，时刻为 startTime。</li>
 *   <li>monthly：每 cycleValue 月一次，日期取锚点日；月末规则：目标月天数不足时
 *       （如锚点 31 日遇 2 月）落在该月最后一天。</li>
 *   <li>shift：  每个自然日在班次起始时刻一次；跨午夜窗口（窗结束或 endTime 越过 00:00）
 *       归属起始日期的周期，不另建周期。</li>
 * </ul>
 *
 * 夏令时规则（对 daily/weekly/monthly/shift）：用 {@link ZonedDateTime} 解析名义本地时刻——
 * 春令向前跳时产生“不存在的本地时刻”时顺延到实际存在的时刻；秋令回拨产生两个相同时刻时
 * 取偏移较大的第一次。hourly 按精确时长步进，天然不受影响。
 */
public final class CycleScheduleCalculator {

    public static final String DAILY = "daily";
    public static final String WEEKLY = "weekly";
    public static final String MONTHLY = "monthly";
    public static final String SHIFT = "shift";
    public static final String HOURLY = "hourly";

    private static final DateTimeFormatter DATE_KEY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter HOUR_KEY = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm");
    private static final int ENUMERATE_SAFETY_LIMIT = 100_000;

    private final ZoneId zone;

    public CycleScheduleCalculator(ZoneId zone) {
        this.zone = zone;
    }

    /**
     * 周期实例：index 为计划周期序列中的序号（从 0 开始），key 为唯一生成身份的周期部分，
     * scheduledAt 为应用夏令时规则后的实际计划触发时刻（本地墙钟时间）。
     */
    public record PeriodInstance(long index, String key, LocalDateTime scheduledAt, String cycleType) {}

    /** 计算第 k 个周期实例。 */
    public PeriodInstance instance(InspectionPlan plan, long k) {
        return instance(plan, k, zone);
    }

    public PeriodInstance instance(InspectionPlan plan, long k, ZoneId atZone) {
        if (k < 0) throw new IllegalArgumentException("周期序号不能为负: " + k);
        LocalDateTime anchor = resolveAnchor(plan);
        LocalTime start = parseTime(plan.getStartTime());
        int n = plan.getCycleValue() == null || plan.getCycleValue() < 1 ? 1 : plan.getCycleValue();
        String cycle = normCycle(plan.getCycleType());

        // hourly 在时间轴上按精确时长步进，结果时刻一定真实存在，春令跳时不会产生撞键。
        ZonedDateTime hourlyZdt = null;
        LocalDateTime nominal = switch (cycle) {
            case HOURLY -> {
                hourlyZdt = ZonedDateTime.of(anchor, atZone).plusHours((long) k * n);
                yield hourlyZdt.toLocalDateTime();
            }
            case DAILY -> anchor.toLocalDate().plusDays((long) k * n).atTime(start);
            case WEEKLY -> mondayFloor(anchor.toLocalDate()).plusWeeks((long) k * n).atTime(start);
            case MONTHLY -> {
                YearMonth ym = YearMonth.from(anchor).plusMonths((long) k * n);
                int day = Math.min(anchor.getDayOfMonth(), ym.lengthOfMonth());
                yield ym.atDay(day).atTime(start);
            }
            case SHIFT -> anchor.toLocalDate().plusDays(k).atTime(start);
            default -> anchor.toLocalDate().plusDays((long) k * n).atTime(start);
        };

        ZonedDateTime zdt = hourlyZdt != null ? hourlyZdt : ZonedDateTime.of(nominal, atZone);
        LocalDateTime scheduled = zdt.toLocalDateTime();
        String key = switch (cycle) {
            case HOURLY -> "H" + HOUR_KEY.format(scheduled) + formatOffset(zdt.getOffset());
            case DAILY -> "D" + DATE_KEY.format(nominal.toLocalDate());
            case WEEKLY -> "W" + DATE_KEY.format(nominal.toLocalDate());
            case MONTHLY -> "M" + MONTH_KEY.format(nominal);
            case SHIFT -> "S" + DATE_KEY.format(nominal.toLocalDate())
                    + "-" + (plan.getShiftType() == null ? "day" : plan.getShiftType());
            default -> "D" + DATE_KEY.format(nominal.toLocalDate());
        };
        return new PeriodInstance(k, key, scheduled, cycle);
    }

    /**
     * 返回 t 时刻所属周期的序号（floor 语义：周期在其 scheduledAt 时刻即归属于它）。
     * 用于快速定位，不逐条回推序列。
     */
    public long indexAt(InspectionPlan plan, LocalDateTime t) {
        return indexAt(plan, t, zone);
    }

    public long indexAt(InspectionPlan plan, LocalDateTime t, ZoneId atZone) {
        LocalDateTime anchor = resolveAnchor(plan);
        int n = plan.getCycleValue() == null || plan.getCycleValue() < 1 ? 1 : plan.getCycleValue();
        String cycle = normCycle(plan.getCycleType());
        return switch (cycle) {
            case HOURLY -> {
                long seconds = Duration.between(ZonedDateTime.of(anchor, atZone), ZonedDateTime.of(t, atZone))
                        .getSeconds();
                yield Math.floorDiv(seconds, (long) n * 3600);
            }
            case DAILY -> Math.floorDiv(
                    ChronoUnit.DAYS.between(anchor.toLocalDate(), t.toLocalDate()), n);
            case WEEKLY -> {
                long weeks = ChronoUnit.WEEKS.between(
                        mondayFloor(anchor.toLocalDate()), mondayFloor(t.toLocalDate()));
                yield Math.floorDiv(weeks, n);
            }
            case MONTHLY -> {
                long months = ChronoUnit.MONTHS.between(YearMonth.from(anchor), YearMonth.from(t));
                yield Math.floorDiv(months, n);
            }
            case SHIFT -> ChronoUnit.DAYS.between(anchor.toLocalDate(), t.toLocalDate());
            default -> ChronoUnit.DAYS.between(anchor.toLocalDate(), t.toLocalDate());
        };
    }

    /** 枚举计划触发时刻落在 [from, to] 内的周期实例（含两端）。 */
    public List<PeriodInstance> enumerate(InspectionPlan plan, LocalDateTime from, LocalDateTime to) {
        List<PeriodInstance> out = new ArrayList<>();
        if (to.isBefore(from)) return out;
        long kFirst = Math.max(0, indexAt(plan, from));
        long kLast = indexAt(plan, to);
        for (long k = kFirst; k <= kLast && out.size() < ENUMERATE_SAFETY_LIMIT; k++) {
            PeriodInstance pi = instance(plan, k);
            if (!pi.scheduledAt().isBefore(from) && !pi.scheduledAt().isAfter(to)) {
                out.add(pi);
            }
        }
        return out;
    }

    /**
     * 推算周期实例对应的任务时间窗。跨午夜规则：窗结束时刻早于/等于起始时刻（endTime 配置）
     * 或显式时间窗越过 00:00 时，结束时间顺延到下一自然日。
     */
    public Window windowOf(InspectionPlan plan, LocalDateTime scheduledStart) {
        LocalDateTime end;
        if (plan.getTimeWindowMinutes() != null && plan.getTimeWindowMinutes() > 0) {
            end = scheduledStart.plusMinutes(plan.getTimeWindowMinutes());
        } else {
            LocalTime endTime = parseTime(plan.getEndTime());
            LocalDate endDate = scheduledStart.toLocalDate();
            if (!endTime.isAfter(scheduledStart.toLocalTime())) {
                endDate = endDate.plusDays(1);
            }
            end = LocalDateTime.of(endDate, endTime);
        }
        return new Window(scheduledStart, end);
    }

    public record Window(LocalDateTime start, LocalDateTime end) {}

    /**
     * 规范化计划的周期锚点：weekly 归一到锚点日期所在周的周一；其余取锚点日期的 startTime。
     * 供新建计划时写入 schedule_anchor，保证后续序列可重放。
     */
    public LocalDateTime normalizeAnchor(LocalDateTime createdAt, String cycleType, String startTime) {
        LocalTime t = parseTime(startTime);
        LocalDate d = createdAt.toLocalDate();
        String cycle = normCycle(cycleType);
        return switch (cycle) {
            case WEEKLY -> mondayFloor(d).atTime(t);
            case MONTHLY -> d.atTime(t);
            default -> d.atTime(t);
        };
    }

    public LocalDateTime resolveAnchor(InspectionPlan plan) {
        if (plan.getScheduleAnchor() != null) return plan.getScheduleAnchor();
        LocalDateTime base = plan.getCreatedAt() != null ? plan.getCreatedAt() : LocalDateTime.now(zone);
        return normalizeAnchor(base, plan.getCycleType(), plan.getStartTime());
    }

    private static LocalDate mondayFloor(LocalDate d) {
        return d.with(DayOfWeek.MONDAY);
    }

    private static LocalTime parseTime(String s) {
        if (s == null || s.isBlank()) return LocalTime.of(8, 0);
        try {
            String[] parts = s.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return LocalTime.of(h, m);
        } catch (Exception e) {
            return LocalTime.of(8, 0);
        }
    }

    private static String normCycle(String c) {
        if (c == null) return DAILY;
        return switch (c) {
            case DAILY, WEEKLY, MONTHLY, SHIFT, HOURLY -> c;
            default -> DAILY;
        };
    }

    private static String formatOffset(ZoneOffset offset) {
        int total = offset.getTotalSeconds();
        char sign = total < 0 ? '-' : '+';
        int abs = Math.abs(total);
        return String.format("%c%02d%02d", sign, abs / 3600, (abs % 3600) / 60);
    }
}
