package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "inspection_plans")
public class InspectionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(length = 16)
    private String cycleType = "daily";

    @Column(name = "cycle_value")
    private Integer cycleValue = 1;

    @Column(name = "shift_type", length = 16)
    private String shiftType = "day";

    @Column(name = "start_time", length = 8)
    private String startTime = "08:00";

    @Column(name = "end_time", length = 8)
    private String endTime = "18:00";

    @Column(name = "time_window_minutes")
    private Integer timeWindowMinutes = 120;

    @Column(name = "team_name", length = 64)
    private String teamName = "";

    @Column(name = "assignee_ids", length = 512)
    private String assigneeIds = "";

    @Column(length = 512)
    private String remark = "";

    @Column
    private Boolean enabled = true;

    @Column(name = "last_generated_at")
    private LocalDateTime lastGeneratedAt;

    /** 调度参数版本：周期类型/周期值/起始时刻变更后递增，旧版本台账保留用于追溯。 */
    @Column(name = "schedule_version", nullable = false, columnDefinition = "int default 1")
    private Integer scheduleVersion = 1;

    /** 周期序列锚点（首个周期的日期/时刻据此推算），新建计划时写入。 */
    @Column(name = "schedule_anchor")
    private LocalDateTime scheduleAnchor;

    /** 最近一次被禁用的时刻，重新启用后保留，用于界定禁用区间。 */
    @Column(name = "disabled_at")
    private LocalDateTime disabledAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getCycleType() { return cycleType; }
    public void setCycleType(String cycleType) { this.cycleType = cycleType; }
    public Integer getCycleValue() { return cycleValue; }
    public void setCycleValue(Integer cycleValue) { this.cycleValue = cycleValue; }
    public String getShiftType() { return shiftType; }
    public void setShiftType(String shiftType) { this.shiftType = shiftType; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
    public Integer getTimeWindowMinutes() { return timeWindowMinutes; }
    public void setTimeWindowMinutes(Integer timeWindowMinutes) { this.timeWindowMinutes = timeWindowMinutes; }
    public String getTeamName() { return teamName; }
    public void setTeamName(String teamName) { this.teamName = teamName; }
    public String getAssigneeIds() { return assigneeIds; }
    public void setAssigneeIds(String assigneeIds) { this.assigneeIds = assigneeIds; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getLastGeneratedAt() { return lastGeneratedAt; }
    public void setLastGeneratedAt(LocalDateTime lastGeneratedAt) { this.lastGeneratedAt = lastGeneratedAt; }
    public Integer getScheduleVersion() { return scheduleVersion; }
    public void setScheduleVersion(Integer scheduleVersion) { this.scheduleVersion = scheduleVersion; }
    public LocalDateTime getScheduleAnchor() { return scheduleAnchor; }
    public void setScheduleAnchor(LocalDateTime scheduleAnchor) { this.scheduleAnchor = scheduleAnchor; }
    public LocalDateTime getDisabledAt() { return disabledAt; }
    public void setDisabledAt(LocalDateTime disabledAt) { this.disabledAt = disabledAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
