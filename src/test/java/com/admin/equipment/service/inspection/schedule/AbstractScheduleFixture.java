package com.admin.equipment.service.inspection.schedule;

import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionPlanPoint;
import com.admin.equipment.model.inspection.InspectionPoint;
import com.admin.equipment.model.inspection.InspectionScheduleLedger;
import com.admin.equipment.model.inspection.InspectionTemplate;
import com.admin.equipment.repo.inspection.InspectionPlanPointRepository;
import com.admin.equipment.repo.inspection.InspectionPlanRepository;
import com.admin.equipment.repo.inspection.InspectionPointRepository;
import com.admin.equipment.repo.inspection.InspectionScheduleLedgerRepository;
import com.admin.equipment.repo.inspection.InspectionTaskPointRepository;
import com.admin.equipment.repo.inspection.InspectionTaskRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateItemRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/** 测试夹具：在不同测试类间复用建点/建模板/建计划逻辑，并提供统一清理。 */
@Component
public class AbstractScheduleFixture {

    private final ScheduleClock clock;
    private final InspectionPlanRepository planRepo;
    private final InspectionPlanPointRepository planPointRepo;
    private final InspectionPointRepository pointRepo;
    private final InspectionTemplateRepository templateRepo;
    private final InspectionTemplateItemRepository templateItemRepo;
    private final InspectionTaskRepository taskRepo;
    private final InspectionTaskPointRepository taskPointRepo;
    private final InspectionScheduleLedgerRepository ledgerRepo;
    private final TransactionTemplate tx;

    public AbstractScheduleFixture(ScheduleClock clock, InspectionPlanRepository planRepo,
                                    InspectionPlanPointRepository planPointRepo,
                                    InspectionPointRepository pointRepo,
                                    InspectionTemplateRepository templateRepo,
                                    InspectionTemplateItemRepository templateItemRepo,
                                    InspectionTaskRepository taskRepo,
                                    InspectionTaskPointRepository taskPointRepo,
                                    InspectionScheduleLedgerRepository ledgerRepo,
                                    TransactionTemplate tx) {
        this.clock = clock;
        this.planRepo = planRepo;
        this.planPointRepo = planPointRepo;
        this.pointRepo = pointRepo;
        this.templateRepo = templateRepo;
        this.templateItemRepo = templateItemRepo;
        this.taskRepo = taskRepo;
        this.taskPointRepo = taskPointRepo;
        this.ledgerRepo = ledgerRepo;
        this.tx = tx;
    }

    public void fixClock(LocalDateTime t) {
        clock.setFixed(t);
    }

    public void reset() {
        taskPointRepo.deleteAllInBatch();
        ledgerRepo.deleteAllInBatch();
        taskRepo.deleteAllInBatch();
        planPointRepo.deleteAllInBatch();
        planRepo.deleteAllInBatch();
        pointRepo.deleteAllInBatch();
        templateItemRepo.deleteAllInBatch();
        templateRepo.deleteAllInBatch();
    }

    public InspectionPlan newPlan(String code, String cycle, Integer cycleValue,
                                   String startTime, LocalDateTime anchor) {
        return tx.execute(status -> {
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
        });
    }

    /** 供需要直接构造台账的测试使用。 */
    public InspectionScheduleLedger saveLedger(InspectionScheduleLedger l) {
        return tx.execute(status -> ledgerRepo.saveAndFlush(l));
    }
}
