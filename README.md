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
- 巡检完成率、设备历史与执行轨迹查询（`/api/inspection/stats`）
- 仪表盘统计（`/api/dashboard/stats`）
- 健康检查（`/api/health`）

除 `login` 与 `health` 外，接口均需 `Authorization: Bearer <token>`。

## 巡检任务自动调度（可恢复、可审计）

计划支持 `daily / weekly / monthly / shift / hourly` 五种周期（`cycleValue` 为步长，
weekly 用 `dayOfWeek`、monthly 用 `dayOfMonth`）。调度以 `inspection_schedule_occurrences`
为“周期生成实例”表，身份键为 `planId:periodKey`（数据库唯一约束）：

- 周期键格式：daily/shift=`yyyyMMdd`（夜班带后缀如 `20260917-NIGHT`）、weekly=`yyyyWww`、
  monthly=`yyyyMM`、hourly=`yyyyMMddHHmm`；
- 正常轮询、多实例并发、重启补偿都以行级原子认领（`pending/processing → claim → generated`）
  保证同一周期最多一条任务；宕机残留的 `processing` 超过 `claim-stale-minutes` 自动接管；
- 服务启动即扫描 `recovery-hours`（默认 72h）内缺口；窗口结束超过 `late-generate-minutes`
  （默认 180 分钟宽限）后不再自动生成，登记为 `window_expired`，须管理员显式补齐；
- 生成失败按 `max-attempts` 自动重试，超限保留 `failed` 等人工处理，全过程记录可反查。

确定规则（时区由 `app.schedule.zone` 配置，默认 `Asia/Shanghai`）：

- **跨午夜班次**：窗口按 `timeWindowMinutes` 物理时长顺延到次日，周期归属于班次开始日；
- **月末日期**：`dayOfMonth` 超过当月天数（如 31 日遇 2 月）落到当月最后一天；
- **夏令时**：缺口时间不存在时按 `ZoneRules` 向后顺延，重叠小时取较早偏移，
  窗口一律按物理分钟数计算，跨 DST 切换长度确定。

禁用与重新启用：

- 禁用期间的周期标记 `plan_disabled`，**绝不自动补发**；
- 重新启用必须二选一：`continue`（从当前周期继续，禁用期留痕 `disabled_no_backfill`）
  或 `backfill`（仅补齐最近 N 个周期，更早的留痕 `backfill_limit`，上限 500）。

管理员接口（均需 Bearer Token）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/inspection/schedules/plans/{id}/preview?from=..&to=..` | 调度预览（应触发周期与当前状态/跳过原因） |
| POST | `/api/inspection/schedules/plans/{id}/trigger` | 立即执行当前（或指定 `periodKey`）周期，重复执行被拒绝 |
| POST | `/api/inspection/schedules/plans/{id}/backfill` | 区间内有限补齐 `{from,to,maxPeriods}` |
| POST | `/api/inspection/schedules/plans/{id}/disable` | 禁用计划（禁用期不补发） |
| POST | `/api/inspection/schedules/plans/{id}/reactivate` | `{mode:"continue"|"backfill", maxPeriods}` |
| POST | `/api/inspection/schedules/tick` | 手工触发一轮轮询（含重启补偿） |
| GET | `/api/inspection/schedules/occurrences?planId=&status=` | 周期生成实例与跳过原因列表 |
| GET | `/api/inspection/schedules/tasks/{taskId}/trace` | 从任一任务反查触发周期、生成实例、来源 |

时间参数支持 `yyyy-MM-ddTHH:mm`（按调度时区解释）或带 `Z` 的 UTC 时间。
任务编码为 `TK-<计划编号>-<周期键>`，任务行同时保存 `occurrenceId / periodKey / triggerSource`。

配置项（环境变量可覆盖）：`SCHEDULE_ENABLED`、`SCHEDULE_ZONE`、`SCHEDULE_POLL_MS`，
以及 `app.schedule.recovery-hours / late-generate-minutes / claim-stale-minutes /
max-attempts / batch-size / backfill-limit-default / backfill-limit-max`。

测试使用 H2 与可控时钟（`ScheduleClock`）：`mvn test`，覆盖五种周期时刻计算、
跨午夜班次、月末日期、夏令时缺口/重叠，以及轮询幂等、宕机认领恢复、禁用继续/补齐、
过期跳过与任务反查等集成场景。

## 编码说明

数据库使用 utf8mb4，JDBC 连接显式指定 characterEncoding=utf8；Spring Boot 的 JSON 响应默认 UTF-8，中文不乱码。
