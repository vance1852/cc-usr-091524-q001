# 工业设备巡检与维保工单管理平台（纯后端）

工业设备台账、巡检与维保工单管理的纯后端 API 服务。

## 技术栈

- Java 17 + Spring Boot 3 + Spring Web
- Spring Data JPA + MySQL 8（字符集 utf8mb4）
- JWT 鉴权（jjwt，自定义过滤器）、PBKDF2 密码哈希（JDK 自带）

## 启动（Docker）

```bash
docker compose up --build
```

MySQL 就绪后，应用通过 JPA 自动建表（ddl-auto=update）并在启动时灌入种子数据，服务监听 `http://127.0.0.1:7654`。

## 内置账号

唯一管理员（本平台只有 admin 一个角色）：

- 用户名：`admin`
- 密码：`admin123`

## 已实现的基础功能

- 登录签发 JWT、获取当前用户（`/api/auth/login`、`/api/auth/me`）
- 设备台账增删改查（`/api/equipments`，编号唯一校验）
- 维保工单查询、创建、状态流转（`/api/work-orders`，完成时记录关闭时间）
- 巡检点、巡检模板与周期计划维护（`/api/inspection/points`、`/api/inspection/templates`、`/api/inspection/plans`）
- 巡检任务生成与执行、异常转工单、复检闭环和路线比较（`/api/inspection/tasks`）
- **可恢复的自动调度**：按 daily/weekly/monthly/shift/hourly 自动生成任务（见下）
- 巡检完成率、设备历史与执行轨迹查询（`/api/inspection/stats`）
- 仪表盘统计（`/api/dashboard/stats`）
- 健康检查（`/api/health`）

除 `login` 与 `health` 外，接口均需 `Authorization: Bearer <token>`。

## 巡检自动调度

调度器在应用启动时做一次补偿扫描，之后每 60 秒轮询一次（`app.schedule.poll-interval-ms`）。
每个“计划 × 周期”在 `inspection_schedule_ledger` 表中有一行**唯一生成身份**
（唯一键 `plan_id + schedule_version + period_key`），任务与台账在同一数据库事务内提交：

- 正常轮询、多实例并发、重启补偿都只会产生一个任务——抢占由唯一索引裁决，
  残留的 PENDING（生成进程崩溃）由下一轮扫描幂等恢复，并以乐观锁防止并发重复恢复；
- 计划禁用期间到期不补发；禁用区间的周期在重新启用时确定性留痕（SKIPPED/DISABLED）。
  重新启用必须由管理员明确选择：
  - `POST /api/inspection/plans/{id}/schedule/resume` `{"mode":"CURRENT"}`：从当前周期继续；
  - `POST /api/inspection/plans/{id}/schedule/resume` `{"mode":"BACKFILL","limit":N}`：
    只补发最近 N 个周期，更早的留痕 SKIPPED/BACKFILL_LIMIT；
  - 直接 `PATCH /api/inspection/plans/{id}/enabled` 重新启用等价于 CURRENT。
- 周期规则（厂区时区 `app.schedule.zone`，默认 Asia/Shanghai）：
  weekly 固定 ISO 周一；monthly 锚点日遇小月自动落到月末（如 31 日遇 2 月→28 日）；
  shift 跨午夜班次的窗口归属起始日期、结束顺延到下一自然日；
  夏令时春令跳时间隙顺延、秋令回拨取第一次，hourly 按精确时长步进，周期键带 UTC 偏移不撞键。
- 管理员接口：
  - `GET  /api/inspection/plans/{id}/schedule/preview?from=&to=` 调度预览（时刻与生成状态）
  - `POST /api/inspection/plans/{id}/schedule/trigger` 立即执行当前/指定周期（幂等）
  - `POST /api/inspection/plans/{id}/schedule/resume` 重新启用接续（CURRENT/BACKFILL）
  - `GET  /api/inspection/schedule/records?planId=&status=` 补偿/调度记录
  - `GET  /api/inspection/tasks/{id}/schedule-trace` 从任务反查触发周期、生成实例、跳过原因
  - `POST /api/inspection/schedule/sweep` 手动触发一次全量扫描
- 任务带 `periodKey`、`triggerSource`、`scheduleLedgerId` 字段，手工生成的任务无调度身份。

相关配置（均可由环境变量覆盖）：`SCHEDULE_ZONE`、`SCHEDULE_ENABLED`、
`SCHEDULE_POLL_INTERVAL_MS`、`SCHEDULE_BACKFILL_MAX`。

## 测试

```bash
mvn test
```

集成测试使用 H2 内存库与可冻结/拨快的 `ScheduleClock`，覆盖：周期规则（跨午夜、月末、夏令时）、
轮询幂等、停机补偿、8 实例并发不重复、崩溃 PENDING 恢复、禁用/重新启用接续、
管理员预览/立即执行/记录接口与任务反查。

## 编码说明

数据库使用 utf8mb4，JDBC 连接显式指定 characterEncoding=utf8；Spring Boot 的 JSON 响应默认 UTF-8，中文不乱码。
