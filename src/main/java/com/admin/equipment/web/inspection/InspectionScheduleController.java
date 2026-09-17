package com.admin.equipment.web.inspection;

import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.service.inspection.InspectionPlanService;
import com.admin.equipment.service.inspection.schedule.InspectionScheduleService;
import com.admin.equipment.service.inspection.schedule.SchedulePreviewEntry;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理员调度接口：
 *  GET  /api/inspection/plans/{id}/schedule/preview   调度预览（周期时刻与生成状态）
 *  POST /api/inspection/plans/{id}/schedule/trigger   立即执行当前或指定周期（幂等）
 *  POST /api/inspection/plans/{id}/schedule/resume    重新启用：CURRENT 当前周期 / BACKFILL 补齐有限历史
 *  GET  /api/inspection/schedule/records              补偿/调度记录（可按计划、状态过滤）
 *  GET  /api/inspection/tasks/{taskId}/schedule-trace 从任务反查触发周期、生成实例与跳过原因
 *  POST /api/inspection/schedule/sweep                手动触发一次全量扫描
 */
@RestController
@RequestMapping("/api/inspection")
public class InspectionScheduleController {

    private final InspectionScheduleService scheduleService;
    private final InspectionPlanService planService;

    public InspectionScheduleController(InspectionScheduleService scheduleService,
                                         InspectionPlanService planService) {
        this.scheduleService = scheduleService;
        this.planService = planService;
    }

    public record TriggerRequest(String periodKey) {}

    public record ResumeRequest(String mode, Integer limit) {}

    @GetMapping("/plans/{id}/schedule/preview")
    public ResponseEntity<?> preview(@PathVariable Long id,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        try {
            List<SchedulePreviewEntry> entries = scheduleService.preview(id, from, to);
            InspectionPlan plan = planService.getById(id).orElseThrow();
            return ResponseEntity.ok(Map.of(
                    "planId", id,
                    "planCode", plan.getCode(),
                    "scheduleVersion", plan.getScheduleVersion() == null ? 1 : plan.getScheduleVersion(),
                    "entries", entries));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/plans/{id}/schedule/trigger")
    public ResponseEntity<?> trigger(@PathVariable Long id, @RequestBody(required = false) TriggerRequest req) {
        try {
            String key = req == null ? null : req.periodKey();
            InspectionTask task = scheduleService.triggerNow(id, key);
            return ResponseEntity.status(HttpStatus.CREATED).body(task);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/plans/{id}/schedule/resume")
    public ResponseEntity<?> resume(@PathVariable Long id, @RequestBody(required = false) ResumeRequest req) {
        try {
            String mode = req == null || req.mode() == null ? "CURRENT" : req.mode().trim().toUpperCase();
            if (!"CURRENT".equals(mode) && !"BACKFILL".equals(mode)) {
                return ResponseEntity.unprocessableEntity()
                        .body(Map.of("detail", "mode 必须为 CURRENT 或 BACKFILL"));
            }
            Integer limit = req == null ? null : req.limit();
            InspectionScheduleService.SweepResult r = scheduleService.resume(id, mode, limit);
            return ResponseEntity.ok(Map.of(
                    "result", r,
                    "plan", planService.getById(id).orElse(null)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @GetMapping("/schedule/records")
    public ResponseEntity<?> records(@RequestParam(required = false) Long planId,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(scheduleService.ledgerRecords(planId, status, limit));
    }

    @PostMapping("/schedule/sweep")
    public ResponseEntity<?> sweep() {
        InspectionScheduleService.SweepResult r = scheduleService.sweep("MANUAL");
        return ResponseEntity.ok(r);
    }

    @GetMapping("/tasks/{taskId}/schedule-trace")
    public ResponseEntity<?> trace(@PathVariable Long taskId) {
        return ResponseEntity.ok(scheduleService.traceByTask(taskId));
    }
}
