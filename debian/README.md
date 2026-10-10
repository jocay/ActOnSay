# ActOnSay Debian 执行环境

本目录实现 Android 主控使用的本机桥接服务。模型请求和 AI 主循环留在 Android；Debian 运行命令、MCP、Skills 和固定脚本调度。服务固定监听 `127.0.0.1:18765`，所有 `/v1` 接口均要求 `Authorization: Bearer <配对令牌>`。容器不可用时客户端应报告错误，禁止回退到 Android Shell。

## 部署和升级

Android 的执行环境向导通过 Droidspaces CLI 将此目录打包到选定容器的 `/opt/actonsay-bridge/source`，再执行：

```bash
bash /opt/actonsay-bridge/source/install.sh --non-interactive
```

也可将本目录完整复制进 Debian 后手工执行同一脚本。脚本要求容器 Root、正在运行的 systemd 和可用软件源；会安装 Python venv/pip、Git、证书及 requirements 中的依赖，只管理 `actonsay-bridge.service`。不会安装 Node.js，stdio MCP 的额外依赖通过单独可见任务安装。

- 版本代码和独立 venv：`/opt/actonsay-bridge/releases/<版本目录>`，`current` 原子链接到已准备的新版本。
- 数据库、日志、产物、Skill 版本和快照：`/var/lib/actonsay-bridge`。
- 随机配对令牌：`/etc/actonsay-bridge/token`，权限 `0600`。安装输出不显示令牌，Android 获取后保存到 Keystore。
- 环境设置：`/etc/actonsay-bridge/environment`，权限 `0600`，默认 `Asia/Shanghai`。Android 创建计划时传手机的实际时区。
- 升级保留数据库、配对令牌、环境设置和旧代码版本；先准备依赖再切换，只重启本项目服务。
- Shell 脚本保存为 LF。不要把 `install.sh` 转换为 Windows CRLF。

升级会中断正在运行的执行，结果标记为 `result_unknown`，不能宣称成功或自动重放。排队但尚未启动的执行在新服务中继续。旧版本目录保留以保证固定脚本引用仍可访问，空间清理由用户决定。

## 接口约定

请求和响应均为 JSON，下载和 SSE 除外。`POST/PATCH` JSON 请求须提供 Content-Length；最大请求体 30 MiB。错误返回 `{error_type,message}`，校验错误不回显包含凭据的原请求。

| 接口 | 作用 |
|---|---|
| `GET /v1/health` | 状态、版本、身份、可识别的手机共享挂载、时区、私有 Python 路径和能力 |
| `POST /v1/executions` | 异步提交，返回 HTTP 202 和持久化执行记录 |
| `GET /v1/executions?task_id=&limit=200` | 执行列表，可同步离线完成的固定脚本 |
| `GET /v1/executions/{id}` | 查询原执行，不重复提交命令 |
| `POST /v1/executions/{id}/cancel` | 终止执行进程组 |
| `POST /v1/executions/{id}/rerun` | 用户显式重跑，提交新的 `{request_id,task_id}`；后端复用受保护原配置 |
| `POST /v1/executions/{id}/resolve` | 仅结果待确认，用户选择 `{status,note}` 人工核实，不伪造退出码 |
| `GET /v1/executions/{id}/log?offset=0&limit=65536` | 按字节读取日志：`{text,next_offset,truncated,status}` |
| `GET /v1/executions/{id}/artifact-text?path=` | UTF-8 文本产物预览，最多 256 KiB |
| `GET /v1/executions/{id}/artifacts/{path}` | 下载执行目录内的产物 |
| `GET /v1/tasks/{task_id}/artifacts/{path}` | 下载 MCP 保存的文件、图片或音频 |
| `GET /v1/events?after=0&wait_seconds=25` | 可续接事件：`{events,cursor}` |
| `GET /v1/events/stream?after=0` | Bearer 鉴权的 SSE 事件流 |
| `GET/PATCH /v1/automation` | 持久化全局暂停：`{paused:true}`；进行中的任务继续 |
| `GET/POST /v1/schedules`、`GET/PATCH/DELETE /v1/schedules/{id}` | 自动化计划 CRUD |
| `POST /v1/schedules/preview` | 按正式调度规则预览接下来几次触发时间（只读时间字段，不保存） |
| `GET /v1/triggers?schedule_id=` | 最近触发与跳过记录 |
| `GET /v1/ai-triggers` | AI 等待队列：`{triggers:[...]}` |
| `POST /v1/ai-triggers/{id}/claim` | `{owner_id}`，领取租约 120 秒 |
| `POST /v1/ai-triggers/{id}/ack` | `{owner_id,task_id}`，在 Android 持久化后确认 |
| `POST /v1/ai-triggers/{id}/complete` | `{task_id,status}`，结束绑定任务 |
| `POST /v1/ai-triggers/{id}/resolve` | 仅结果待确认，用户选择 `{task_id,status,note}` 人工核实 |
| `GET/POST /v1/mcp`、`PATCH/DELETE /v1/mcp/{id}` | MCP 服务配置和启停 |
| `POST /v1/mcp/{id}/test` | 初始化连接并列举工具 |
| `GET /v1/mcp/{id}/tools` | 完整工具名、命名空间、参数 schema |
| `POST /v1/mcp/{id}/call` | `{name,arguments,task_id,request_id}`，调用并持久化结果 |
| `GET /v1/mcp-calls/{id或request_id}` | 查询原 MCP 调用的结果 |
| `POST /v1/mcp-calls/{id或request_id}/cancel` | 取消连接 actor 与底层请求，副作用结果待确认 |
| `POST /v1/mcp-calls/{id或request_id}/resolve` | 仅结果待确认，用户 `{status,note}` 人工核实 |
| `GET /v1/mcp/{id}/resources`、`resource-templates`、`prompts` | 按 SDK 分页列出对应能力 |
| `POST /v1/mcp/{id}/resources/read` | `{uri,task_id}`，读资源并将二进制内容保存为产物 |
| `POST /v1/mcp/{id}/prompts/get` | `{name,arguments,task_id?}`，获取 prompt |
| `GET /v1/skills` | 只列出名称、说明、状态、来源、版本与依赖，不加载正文 |
| `POST /v1/skills/import` | 导入目录、ZIP 或 Git；不执行任何导入脚本 |
| `PATCH /v1/skills/{id}`、`DELETE /v1/skills/{id}` | `{enabled}` 或删除可选 Skill，保留任务快照 |
| `POST /v1/skills/{id}/update` | 从原来源显式更新 |
| `POST /v1/skills/{id}/snapshot` | `{task_id}`，建立不可被后续更新替换的任务快照 |
| `GET /v1/skills/{id}/read?path=SKILL.md&task_id=` | 读取对应任务快照的正文或引用，返回 `{text,path,version,cwd}` |
| `POST /v1/skills/{id}/run` | `{task_id,request_id,path,args,env?,timeout_seconds?}`，在任务快照目录运行 `scripts/*.py` 或 `scripts/*.sh` |

执行提交示例：

```json
{
  "request_id": "明确固定的请求UUID",
  "task_id": "明确固定的任务UUID",
  "command": "printf '报告内容\\n' > \"$ACTONSAY_ARTIFACT_DIR/report.md\"",
  "timeout_seconds": 900,
  "env": {}
}
```

ID 使用 1 至 128 个 ASCII 字母、数字、下划线、点或短横线，不包含路径分隔符。幂等 ID 的内容变化会被拒绝。真实命令由 Bash 解释；外部参数应通过 `env` 或安全引用传入，不能把不受信任的值拼入命令文本。

执行响应含 `id/request_id/task_id/status/cwd/artifact_dir/exit_code/error_type/log_size/log_truncated/artifacts` 和时间戳。未填写 cwd 时使用任务工作目录。每次执行环境注入 `ACTONSAY_TASK_ID`、`ACTONSAY_EXECUTION_ID`、`ACTONSAY_ARTIFACT_DIR`。需要展示的报告写到产物目录；一般工作目录和容器系统文件不会被直接暴露为下载接口。

产物对象为 `{name,path,size,mime_type,reference}`，每次执行列表最多列出 200 个文件。reference 已进行 URL 编码，客户端直接使用该相对接口；不能再次对整条 reference 编码。下载逐级以 NOFOLLOW 打开目录与文件，拒绝路径穿越、符号链接和超过 50 MiB 的文件。

## 执行与恢复

默认两个命令并发，每个任务独立工作目录、日志与产物；普通聊天不影响它们。日志边读边写，保存上限 5 MiB，超过后继续排空管道，防止进程因 stdout 堵塞。配对令牌及已注册的环境/Header 凭据会脱敏，跨输出分块保留足够尾部，避免凭据分段漏出。日志游标按字节计数。

命令以新 session/进程组启动，取消、超时和正常父进程退出后均清理该组子进程。服务由 systemd 的 `KillMode=control-group` 进一步回收进程。显式创建新 session 的恶意 Root 脚本能绕过进程组边界；本版本面向用户信任的专用设备高度自主模式，Root 并非隔离沙箱。

服务恢复时仅在 PID、Linux boot ID 和 `/proc` 启动时间均匹配时终止原组，避免向复用 PID 发信号；历史 `running` 记录标记 `result_unknown`。创建进程前先把状态持久化为 `starting`：若在启动与记录 PID 之间崩溃，恢复时按 `ACTONSAY_EXECUTION_ID` 环境变量清理残留进程组，并标记 `result_unknown`（`interrupted_while_starting`），不会作为排队任务再执行一次。执行中的日志大小约每 2 秒合并写库一次，结束时强制保存。Shell/MCP 已产生副作用的执行均不自动重试。Android 应查询原 ID，展示结果待确认并提供明确重跑入口。

## 调度与 AI 接收

计划定义：

```json
{
  "name": "每天工作报告",
  "kind": "ai",
  "schedule_type": "cron",
  "cron": "0 9 * * *",
  "timezone": "Asia/Shanghai",
  "goal": "使用 scheduled-report 检查指定工作目录并生成报告，后台完成不朗读",
  "enabled": true,
  "misfire_policy": "latest",
  "catchup_window_seconds": 86400
}
```

`once` 使用带时区的 ISO `run_at`，`interval` 使用不小于 60 的 `interval_seconds`，`cron` 使用五字段表达式。APScheduler 3 的数字星期语义与 Unix 不同，本接口要求星期字段使用 `mon..sun`、英文范围/列表或 `*`，禁止数字星期。

SQLite 保存定义、next_run 和 `(schedule_id,scheduled_at)` 唯一触发记录，APScheduler 3.x 每 10 秒唤醒一次持久化处理。默认多次错过合并最近一次，补跑窗口 24 小时；`skip` 跳过超过一分钟的过期时刻。同一计划有未领取、已绑定、执行中或结果待确认任务时不会再重叠执行。手机休眠、关机或容器停止仍可能使执行延迟。

固定脚本 `kind=script` 使用 `command`、可选 `cwd/env/timeout_seconds`，不依赖 Android；完成后执行记录与事件立即落盘。AI 计划生成 durable trigger，Android 根据固定的 trigger ID 建立唯一 Room 任务：先 claim，再写本地任务，最后 ack。重复 ack 只允许相同 task_id。完成后调用 complete，才能释放该计划的重叠限制；`result_unknown` 保持阻塞，待明确确认。全局暂停阻止新触发和 AI 领取，保留运行中任务；恢复按原补跑规则处理。

## MCP 与 Skills

MCP 使用官方 Python SDK `1.12.4` 的 stdio 和 Streamable HTTP 客户端 API，并固定版本避免混用新 SDK 的接口。每个连接由一个 actor 管理生命周期，不跨异步任务进入/退出 SDK 的 AnyIO 上下文。stdio 配置分开填写 `command/args/env/cwd`，HTTP 配置使用 `url/headers`。列表不回显 env/Header 值，更新未提供这些字段时保留已有凭据。

工具名称包含服务 ID 命名空间，输入按 JSON Schema 校验，保留 `isError` 与 `structuredContent`。图片、音频、文件 blob 保存到任务产物，不把大块 base64 塞入模型上下文。OAuth、sampling 和扩展市场不在此版本范围。

Skills 通过 YAML 前置内容读取 `name/description`，正文按需加载。ZIP 可填写容器路径，也可上传 `data_base64`（原 ZIP 最大 20 MiB）；解压后最多 50 MiB/2000 项。Git 来源支持 HTTPS/HTTP/SSH URL，地址不允许密码，认证通过容器配置。拒绝路径穿越、链接、特殊文件、重复名称和递归子模块。导入不执行安装脚本或 Git hook。

每次任务固定内容快照，更新或删除不会改变已在用的脚本。`.py` 脚本使用桥接服务的私有 Python venv；缺失的 `requires.commands/python_packages/env` 会明确返回，不自动安装。需要额外依赖时创建可见任务并使用 health 返回的 Python 路径安装到该 venv。内置系统巡检、文件整理和定时报告三个 Skill；文件整理首次默认只生成计划，实际移动须用户开启对应授权。

## 人工验收

本次实现没有执行构建、lint、类型检查、测试、设备安装或持续监测。以下由用户在部署后验证：

1. health 显示真实身份、挂载和时区；错误令牌无法访问任何接口。
2. 提交写入产物目录的报告，Android 能预览并保存文件；包含中文、空格、`#`、`?` 的产物名可下载。
3. 相同 request_id 返回原执行，改变内容被拒绝；脚本期间继续聊天不取消执行。
4. 取消包含子进程的脚本、超时脚本和大量 stdout，确认任务终态、5 MiB 限额和进程清理。
5. 停止 Android 后固定脚本仍可调度；恢复 Android 后 AI trigger 只持久化一次，再 ack/complete。
6. 测试 stdio/HTTP MCP、断线、超时、isError、资源、prompt 与图片产物，断网后按原 request_id 查询。
7. 导入目录/ZIP/Git Skill，校验依赖提示、按 task_id 快照读取与脚本 cwd；更新不改变旧任务。
8. 暂停和恢复自动化，验证 latest/skip、时区、Cron、计划删除和不重叠；升级后正在执行的任务显示结果待确认。

参考实现文档：[FastAPI lifespan](https://fastapi.tiangolo.com/advanced/events/)、[MCP Python SDK v1.12.4](https://github.com/modelcontextprotocol/python-sdk/tree/v1.12.4)、[APScheduler 3.x 用户指南](https://apscheduler.readthedocs.io/en/3.x/userguide.html)、[CronTrigger 3.x](https://apscheduler.readthedocs.io/en/3.x/modules/triggers/cron.html)。已先通过 Context7 查询库文档；APScheduler 的 Context7 主索引偏向 4.x，版本差异使用官方 3.x 文档核对。
