package com.admin.equipment.web.inspection;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.repo.AppUserRepository;
import com.admin.equipment.security.PasswordUtil;
import com.admin.equipment.service.inspection.schedule.AbstractScheduleFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 管理员调度接口的端到端 HTTP 测试（含鉴权）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InspectionScheduleControllerTest {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private AppUserRepository userRepo;
    @Autowired private AbstractScheduleFixture fixture;

    @BeforeEach
    void setup() {
        fixture.reset();
        fixture.fixClock(LocalDateTime.of(2026, 9, 17, 9, 0));
        if (userRepo.findByUsername("admin").isEmpty()) {
            AppUser u = new AppUser();
            u.setUsername("admin");
            u.setPasswordHash(PasswordUtil.hash("admin123"));
            u.setDisplayName("管理员");
            userRepo.save(u);
        }
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private String token() {
        ResponseEntity<Map> resp = rest.postForEntity(base() + "/api/auth/login",
                Map.of("username", "admin", "password", "admin123"), Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        return (String) resp.getBody().get("access_token");
    }

    private HttpEntity<Void> authed(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return new HttpEntity<>(h);
    }

    @SuppressWarnings("unchecked")
    @Test
    void endpointsRequireAuthAndAdminFlowWorks() {
        // 未登录 401
        ResponseEntity<Map> denied = rest.getForEntity(
                base() + "/api/inspection/schedule/records", Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, denied.getStatusCode());

        String token = token();
        InspectionPlan plan = fixture.newPlan("API1", "daily", 1, "08:00",
                LocalDateTime.of(2026, 9, 17, 8, 0));

        // 调度预览
        ResponseEntity<Map> preview = rest.exchange(
                base() + "/api/inspection/plans/" + plan.getId()
                        + "/schedule/preview?from=2026-09-17T00:00:00&to=2026-09-18T23:59:59",
                HttpMethod.GET, authed(token), Map.class);
        assertEquals(HttpStatus.OK, preview.getStatusCode());
        List<Map<String, Object>> entries = (List<Map<String, Object>>) preview.getBody().get("entries");
        assertEquals(2, entries.size());
        assertEquals("DUE", entries.get(0).get("status"));

        // 立即执行
        HttpHeaders json = new HttpHeaders();
        json.setBearerAuth(token);
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<InspectionTask> trig = rest.exchange(
                base() + "/api/inspection/plans/" + plan.getId() + "/schedule/trigger",
                HttpMethod.POST, new HttpEntity<>("{}", json), InspectionTask.class);
        assertEquals(HttpStatus.CREATED, trig.getStatusCode());
        assertEquals("MANUAL", trig.getBody().getTriggerSource());
        Long taskId = trig.getBody().getId();

        // 再次立即执行：幂等，同一任务
        ResponseEntity<InspectionTask> trig2 = rest.exchange(
                base() + "/api/inspection/plans/" + plan.getId() + "/schedule/trigger",
                HttpMethod.POST, new HttpEntity<>("{}", json), InspectionTask.class);
        assertEquals(taskId, trig2.getBody().getId());

        // 反查
        ResponseEntity<Map> trace = rest.exchange(
                base() + "/api/inspection/tasks/" + taskId + "/schedule-trace",
                HttpMethod.GET, authed(token), Map.class);
        assertEquals(HttpStatus.OK, trace.getStatusCode());
        assertEquals("D20260917", trace.getBody().get("periodKey"));
        assertEquals("GENERATED", trace.getBody().get("status"));

        // 记录列表
        ResponseEntity<List> records = rest.exchange(
                base() + "/api/inspection/schedule/records?planId=" + plan.getId(),
                HttpMethod.GET, authed(token), List.class);
        assertEquals(HttpStatus.OK, records.getStatusCode());
        assertEquals(1, records.getBody().size());

        // resume 非法 mode
        ResponseEntity<Map> bad = rest.exchange(
                base() + "/api/inspection/plans/" + plan.getId() + "/schedule/resume",
                HttpMethod.POST, new HttpEntity<>("{\"mode\":\"WHATEVER\"}", json), Map.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, bad.getStatusCode());
    }
}
