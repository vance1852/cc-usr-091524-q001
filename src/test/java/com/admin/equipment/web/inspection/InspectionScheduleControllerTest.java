package com.admin.equipment.web.inspection;

import com.admin.equipment.model.inspection.InspectionPoint;
import com.admin.equipment.model.inspection.InspectionTemplate;
import com.admin.equipment.repo.inspection.InspectionPointRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateRepository;
import com.admin.equipment.service.inspection.schedule.ScheduleClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.time.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 调度管理接口的 HTTP 集成测试（含鉴权）：预览、立即执行、轮询、反查、禁用/重新启用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InspectionScheduleControllerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired ScheduleClock clock;
    @Autowired InspectionTemplateRepository templateRepo;
    @Autowired InspectionPointRepository pointRepo;

    private String token;
    private String base;

    private static Instant cn(String local) {
        return LocalDateTime.parse(local).atZone(ZONE).toInstant();
    }

    @BeforeEach
    void setUp() {
        clock.setFixed(cn("2026-09-17T09:30:00"));
        base = "http://localhost:" + port;
        ResponseEntity<String> login = rest.postForEntity(base + "/api/auth/login",
                Map.of("username", "admin", "password", "admin123"), String.class);
        assertEquals(HttpStatus.OK, login.getStatusCode(), login.getBody());
        try {
            token = mapper.readTree(login.getBody()).get("access_token").asText();
        } catch (Exception e) {
            fail(e);
        }
    }

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private Long createPlan(String code, String cycle) throws Exception {
        InspectionTemplate tpl = new InspectionTemplate();
        tpl.setCode("TPL-WEB-" + code);
        tpl.setName("模板");
        templateRepo.save(tpl);
        InspectionPoint p = new InspectionPoint();
        p.setCode("IP-WEB-" + code);
        p.setName("点");
        p.setCoordX(0.0);
        p.setCoordY(0.0);
        pointRepo.save(p);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("code", code);
        body.put("name", "计划" + code);
        body.put("templateId", tpl.getId());
        body.put("cycleType", cycle);
        body.put("cycleValue", 1);
        body.put("shiftType", "day");
        body.put("dayOfWeek", 1);
        body.put("dayOfMonth", 31);
        body.put("startTime", "08:00");
        body.put("endTime", "08:00");
        body.put("timeWindowMinutes", 120);
        body.put("teamName", "班组");
        body.put("assigneeIds", "");
        body.put("remark", "");
        body.put("pointIds", List.of(p.getId()));
        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, auth());
        ResponseEntity<String> resp = rest.postForEntity(base + "/api/inspection/plans", req, String.class);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(), resp.getBody());
        return mapper.readTree(resp.getBody()).get("id").asLong();
    }

    @Test
    void previewTriggerTickAndTraceOverHttp() throws Exception {
        Long planId = createPlan("PLAN-WEB-1", "daily");

        // 预览
        ResponseEntity<String> preview = rest.exchange(
                base + "/api/inspection/schedules/plans/" + planId + "/preview"
                        + "?from=2026-09-17T00:00&to=2026-09-19T00:00",
                HttpMethod.GET, new HttpEntity<>(auth()), String.class);
        assertEquals(HttpStatus.OK, preview.getStatusCode(), preview.getBody());
        JsonNode pnode = mapper.readTree(preview.getBody());
        assertEquals(2, pnode.get("occurrences").size());
        assertEquals("20260917", pnode.get("occurrences").get(0).get("periodKey").asText());

        // 立即执行
        HttpEntity<Map<String, Object>> triggerReq = new HttpEntity<>(Map.of(), auth());
        ResponseEntity<String> trigger = rest.postForEntity(
                base + "/api/inspection/schedules/plans/" + planId + "/trigger", triggerReq, String.class);
        assertEquals(HttpStatus.CREATED, trigger.getStatusCode(), trigger.getBody());
        JsonNode tnode = mapper.readTree(trigger.getBody());
        long taskId = tnode.get("id").asLong();
        assertEquals("manual", tnode.get("triggerSource").asText());

        // 再次立即执行 → 422 拒绝重复
        ResponseEntity<String> dup = rest.postForEntity(
                base + "/api/inspection/schedules/plans/" + planId + "/trigger", triggerReq, String.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, dup.getStatusCode());
        assertTrue(dup.getBody().contains("禁止重复生成"));

        // 手工轮询
        ResponseEntity<String> tick = rest.postForEntity(
                base + "/api/inspection/schedules/tick", new HttpEntity<>(auth()), String.class);
        assertEquals(HttpStatus.OK, tick.getStatusCode());

        // 该计划仍只有这一条任务（轮询不重复生成；种子计划的生成与本计划无关）
        ResponseEntity<String> planTasks = rest.exchange(
                base + "/api/inspection/tasks?planId=" + planId,
                HttpMethod.GET, new HttpEntity<>(auth()), String.class);
        assertEquals(1, mapper.readTree(planTasks.getBody()).size());

        // 任务反查
        ResponseEntity<String> trace = rest.exchange(
                base + "/api/inspection/schedules/tasks/" + taskId + "/trace",
                HttpMethod.GET, new HttpEntity<>(auth()), String.class);
        assertEquals(HttpStatus.OK, trace.getStatusCode(), trace.getBody());
        JsonNode trnode = mapper.readTree(trace.getBody());
        assertEquals("20260917", trnode.get("occurrence").get("periodKey").asText());
        assertEquals("generated", trnode.get("occurrence").get("status").asText());
        assertEquals(taskId, trnode.get("occurrence").get("taskId").asLong());

        // 无凭证 → 401
        ResponseEntity<String> unauth = rest.getForEntity(
                base + "/api/inspection/schedules/occurrences", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, unauth.getStatusCode());
    }

    @Test
    void enableEndpointDirectsToReactivateAndContinueLeavesTraces() throws Exception {
        Long planId = createPlan("PLAN-WEB-2", "daily");
        rest.postForEntity(base + "/api/inspection/schedules/tick", new HttpEntity<>(auth()), String.class);

        // 禁用
        HttpEntity<Map<String, Boolean>> disableReq =
                new HttpEntity<>(Map.of("enabled", false), auth());
        ResponseEntity<String> disabled = rest.exchange(
                base + "/api/inspection/plans/" + planId + "/enabled",
                HttpMethod.PATCH, disableReq, String.class);
        assertEquals(HttpStatus.OK, disabled.getStatusCode(), disabled.getBody());

        // 跨过周末
        clock.setFixed(cn("2026-09-21T09:00:00"));

        // 直接 PATCH enabled=true → 409 并给出 reactivate 入口
        HttpEntity<Map<String, Boolean>> enableReq =
                new HttpEntity<>(Map.of("enabled", true), auth());
        ResponseEntity<String> conflict = rest.exchange(
                base + "/api/inspection/plans/" + planId + "/enabled",
                HttpMethod.PATCH, enableReq, String.class);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertTrue(conflict.getBody().contains("reactivateEndpoint"));

        // 显式 continue
        HttpEntity<Map<String, Object>> reactivateReq =
                new HttpEntity<>(Map.of("mode", "continue"), auth());
        ResponseEntity<String> reactivated = rest.postForEntity(
                base + "/api/inspection/schedules/plans/" + planId + "/reactivate",
                reactivateReq, String.class);
        assertEquals(HttpStatus.OK, reactivated.getStatusCode(), reactivated.getBody());

        // occurrences 列表能查到禁用期跳过原因
        ResponseEntity<String> occs = rest.exchange(
                base + "/api/inspection/schedules/occurrences?planId=" + planId + "&status=skipped&limit=10",
                HttpMethod.GET, new HttpEntity<>(auth()), String.class);
        JsonNode arr = mapper.readTree(occs.getBody());
        assertTrue(arr.isArray());
        List<String> disabledKeys = new java.util.ArrayList<>();
        for (JsonNode o : arr) {
            if ("disabled_no_backfill".equals(o.get("skipReason").asText())) {
                disabledKeys.add(o.get("periodKey").asText());
                assertNotNull(o.get("skipReasonText").asText());
            }
        }
        assertEquals(List.of("20260920", "20260919", "20260918"), disabledKeys,
                "9/18、9/19、9/20 三个禁用期周期必须留痕");
    }
}
