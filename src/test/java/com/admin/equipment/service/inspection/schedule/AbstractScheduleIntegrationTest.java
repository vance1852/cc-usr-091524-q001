package com.admin.equipment.service.inspection.schedule;

import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionPlanPoint;
import com.admin.equipment.model.inspection.InspectionPoint;
import com.admin.equipment.model.inspection.InspectionTemplate;
import com.admin.equipment.repo.inspection.InspectionPlanPointRepository;
import com.admin.equipment.repo.inspection.InspectionPlanRepository;
import com.admin.equipment.repo.inspection.InspectionPointRepository;
import com.admin.equipment.repo.inspection.InspectionScheduleLedgerRepository;
import com.admin.equipment.repo.inspection.InspectionTaskRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateItemRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@SpringBootTest
@ActiveProfiles("test")
abstract class AbstractScheduleIntegrationTest {

    @Autowired protected ScheduleClock clock;
    @Autowired protected CycleScheduleCalculator calculator;
    @Autowired protected InspectionScheduleService scheduleService;
    @Autowired protected InspectionPlanRepository planRepo;
    @Autowired protected InspectionPlanPointRepository planPointRepo;
    @Autowired protected InspectionPointRepository pointRepo;
    @Autowired protected InspectionTemplateRepository templateRepo;
    @Autowired protected InspectionTemplateItemRepository templateItemRepo;
    @Autowired protected InspectionTaskRepository taskRepo;
    @Autowired protected InspectionScheduleLedgerRepository ledgerRepo;
    @Autowired protected TransactionTemplate tx;

    protected void fixClock(LocalDateTime t) {
        clock.setFixed(t);
    }

    protected InspectionPlan newPlan(String code, String cycle, Integer cycleValue,
                                     String startTime, LocalDateTime anchor) {
        InspectionTemplate tpl = new InspectionTemplate();
        tpl.setCode("TPL-" + code);
        tpl.setName("模板-" + code);
        tpl = templateRepo.save(tpl);

        InspectionPoint p = new InspectionPoint();
        p.setCode("IP-" + code);
        p.setName("巡检点-" + code);
        p.setLocation("测试现场");
        p.setCoordX(1.0);
        p.setCoordY(2.0);
        p = pointRepo.save(p);

        InspectionPlan plan = new InspectionPlan();
        plan.setCode(code);
        plan.setName("计划-" + code);
        plan.setTemplateId(tpl.getId());
        plan.setCycleType(cycle);
        plan.setCycleValue(cycleValue == null ? 1 : cycleValue);
        plan.setStartTime(startTime);
        plan.setEndTime("23:00");
        plan.setTimeWindowMinutes(120);
        plan.setTeamName("测试班组");
        plan.setEnabled(true);
        plan.setScheduleVersion(1);
        plan.setScheduleAnchor(anchor);
        plan = planRepo.save(plan);

        InspectionPlanPoint pp = new InspectionPlanPoint();
        pp.setPlanId(plan.getId());
        pp.setPointId(p.getId());
        pp.setSequenceNo(1);
        planPointRepo.save(pp);
        return plan;
    }
}
