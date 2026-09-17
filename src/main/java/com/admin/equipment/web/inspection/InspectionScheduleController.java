package com.admin.equipment.web.inspection;

import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.service.inspection.schedule.InspectionScheduleService;
import com.admin.equipment.service.inspection.schedule.InspectionScheduleService.OccurrenceView;
import com.admin.equipment.service.inspection.schedule.InspectionScheduleService.PreviewItem;
import com.admin.equipment.service.inspection.schedule.InspectionScheduleService.TaskTrace;
import com.admin.equipment.service.inspection.schedule.InspectionScheduleService.TickResult;
import com.admin.equipment.service.inspection.schedule.ScheduleEngine;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 巡检自动调度管理接口（管理员）：调度预览、立即执行、历史补齐、
 * 计划禁用/重新启用（继续或有限补齐）、周期记录与任务反查。
 */
@RestController
@RequestMapping("/api/inspection/schedules")
public class InspectionScheduleController {

    private final InspectionScheduleService service;
    private final ScheduleEngine engine;

    public InspectionScheduleController(InspectionScheduleService service, ScheduleEngine engine) {
        this.service = service;
        this.engine = engine;
    }

    public record RangeRequest(String from, String to) {}
    public record TriggerRequest(String periodKey) {}
    public record BackfillRequest(String from, String to, Integer maxPeriods) {}
    public record ReactivateRequest(String mode, Integer maxPeriods) {}

    @GetMapping("/plans/{planId}/preview")
    public ResponseEntity<?> preview(@PathVariable Long planId,
                                      @RequestParam String from,
                                      @RequestParam String to) {
        try {
            List<PreviewItem> items = service.preview(planId, parseInstant(from), parseInstant(to));
            return ResponseEntity.ok(Map.of("planId", planId, "zone", engine.getZone().getId(),
                    "occurrences", items));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/plans/{planId}/trigger")
    public ResponseEntity<?> trigger(@PathVariable Long planId,
                                      @RequestBody(required = false) TriggerRequest req) {
        try {
            String periodKey = req == null ? null : req.periodKey();
            InspectionTask t = service.triggerNow(planId, periodKey);
            return ResponseEntity.status(HttpStatus.CREATED).body(t);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/plans/{planId}/backfill")
    public ResponseEntity<?> backfill(@PathVariable Long planId, @RequestBody BackfillRequest req) {
        try {
            if (req == null || req.from() == null || req.to() == null) {
                return ResponseEntity.unprocessableEntity().body(Map.of("detail", "from/to 必填"));
            }
            List<OccurrenceView> result = service.backfill(planId,
                    parseInstant(req.from()), parseInstant(req.to()), req.maxPeriods());
            int generated = 0;
            int skipped = 0;
            for (OccurrenceView v : result) {
                if ("generated".equals(v.status())) generated++;
                if ("skipped".equals(v.status())) skipped++;
            }
            return ResponseEntity.ok(Map.of("planId", planId, "total", result.size(),
                    "generated", generated, "skipped", skipped, "occurrences", result));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/plans/{planId}/disable")
    public ResponseEntity<?> disable(@PathVariable Long planId) {
        try {
            return ResponseEntity.ok(service.disablePlan(planId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/plans/{planId}/reactivate")
    public ResponseEntity<?> reactivate(@PathVariable Long planId, @RequestBody ReactivateRequest req) {
        try {
            if (req == null || req.mode() == null
                    || (!"continue".equals(req.mode()) && !"backfill".equals(req.mode()))) {
                return ResponseEntity.unprocessableEntity()
                        .body(Map.of("detail", "mode 必填，取值 continue（从当前周期继续）或 backfill（补齐有限历史）"));
            }
            return ResponseEntity.ok(service.reactivatePlan(planId, req.mode(), req.maxPeriods()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    /** 手工触发一次调度轮询（含重启补偿扫描）。 */
    @PostMapping("/tick")
    public TickResult tick() {
        return service.tick();
    }

    /** 周期生成实例列表（支持按计划/状态过滤），用于“被跳过原因”核查。 */
    @GetMapping("/occurrences")
    public List<OccurrenceView> occurrences(@RequestParam(required = false) Long planId,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "200") int limit) {
        return service.listOccurrences(planId, status, limit);
    }

    /** 从任一任务反查触发周期、生成实例、触发来源与跳过原因。 */
    @GetMapping("/tasks/{taskId}/trace")
    public ResponseEntity<?> trace(@PathVariable Long taskId) {
        try {
            TaskTrace t = service.traceByTask(taskId);
            return ResponseEntity.ok(Map.of(
                    "task", t.task(),
                    "occurrence", t.occurrence() == null ? Map.of() : t.occurrence(),
                    "note", t.note() == null ? "" : t.note()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", e.getMessage()));
        }
    }

    private Instant parseInstant(String text) {
        String s = text.trim().replace(' ', 'T');
        if (s.endsWith("Z")) return Instant.parse(s);
        try {
            return Instant.parse(s);
        } catch (Exception ignored) {
            // 按调度时区解释本地时间
            LocalDateTime ldt = LocalDateTime.parse(s);
            return ldt.atZone(engine.getZone()).toInstant();
        }
    }
}
