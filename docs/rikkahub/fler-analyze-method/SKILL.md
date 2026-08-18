---
name: fler-analyze-method
description: 分析目标 App 的单个 Dart 方法（逻辑、调用关系、数据池引用、可改写的指令），使用 fler 的 MCP 服务器。当用户给出方法名/类名、或被问到某方法的加密/校验/网络逻辑时使用。
---

# FLER 分析单个 Dart 方法

目标：用 fler 内嵌 MCP（已在 RikkaHub 启用）获得一个 Dart 方法的反汇编、调用关系与实际引用的数据，并给出结论或补丁建议。

## 前置

- MCP server 名：`fler-mcp`（连接串如 `http://<手机IP>:8765/mcp`；SSE 端点 `<手机IP>:8765/sse`）。
- `analysisId` 表示一次已完成的 Blutter 分析；不确定时先 `list_analyses`。

## 步骤

1. **确定 analysisId**
   - `list_analyses`，若有用过的记录先 `use_analysis(analysisId)` 设定当前会话，之后无需再传。
2. **定位方法**
   - 知道类名：`get_class(className=类名)` 或 `list_methods(name=方法名子串)`。
   - 不知道类：`list_methods(name=关键词)` 按子串过滤。
   - 拿到的 `functionOffset` 是 vaddr；改文件需 `fileOffset`（已换算返回）。
   - 混淆包的匿名闭包先 `closure_map(vaddr=…)` 或 `closure_map(query=业务词)` 定位归属类；`resolve_entry(vaddr=functionOffset)` 把对象池槽 vaddr 还原成真实代码入口。
3. **一站式读取（双轨反汇编）**
   - `analyze_method(methodName=类.方法)`：一次拿 src_code（截断）+ **asmCode/asmUrl/asmSize（asm_blocks 标准 DumpCode）** + callers + callees + PP 引用。
   - `src_code` 是 fler 兼容格式（`// 0x…: IL语义` + 裸指令）；`asmCode` 是引擎直写 `asm_blocks` 表的标准 DumpCode（`    // ` 缩进 + `; [pp+…]` 解引用），两者互补。
   - src_code 空壳但想读反汇编：`get_asm_code(methodId=… 或 name=…)` 拿 asm_blocks 完整存档（默认截断，`includeBody=true` 看全文）。
   - 若 src 截断且想看完：`get_method(methodId=…, includeSrc=true)`（谨慎，字段很大）。
   - 空壳方法（`functionSize=0`）：两轨都空，改走机器码链路（`string_xrefs`/`scan_pool_refs`/`method_cfg`）。
4. **深挖依赖**
   - 关心的调用方：`get_method_callers(methodName=…)`（图未建好时 `dart_call_graph_status` 看进度；需强制重建用 `dart_rebuild_call_graph`）。
   - 关心 PP 数据：`get_pp_entry(ppOffset=…)` 查对象池条目描述。
   - 关心字符串：`search_strings(query=关键词)`。
   - 若方法名/类名被混淆（`<unknown>`/`<anonymous closure>`），改用结构扫描反混淆：
     - 确认池基址寄存器：`calibrate_pool_sig(soPath=…, vaddr=functionOffset, size=256)`（本目标为 x27）。
     - 看类引用了哪些字符串：`infer_class_fields(className=…)`。
     - 按字符串反查方法：`string_xrefs(query=…)` 或 `scan_pool_refs(ppOffsets=0x…)`（无字符串槽时直传已知槽偏移，来自 calibrate 的 poolLoads）。
     - 找布尔 getter 候选：`find_bool_getters(query=…)`；候选落补丁位用 `getter_return_shape(methodId=…)`。
     - 混淆方法控制流：`method_cfg(methodId=…)` 划基本块找所有返回点；`blr_call_sites(methodId=…)` 看间接跳转形态（isolate 属正常，静态不可解析）。
   - 字符串可能走 fallback：`list_strings` 返回非 0（甚至几万条）即 fallback 生效（未建 strings 表时从 pp_entries 挑引号字符串），别误判无字符串。
5. **反汇编原始字节/确认指令**：`disassemble_range(soPath=…, offset=fileOffset, size=…, compact=true)`；或用引擎会话 `engine_open` → `engine_disassemble`/`engine_read_bytes`（Dart 库函数定位优先 `engine_find_function_at`）。
6. **改补丁**（用户明确要求才做）：`read_so_bytes` → `assemble_instruction` 预览 → 向用户确认 → `patch_bytes`/`patch_instruction` → `export_patched_so`，并给出 `http://<host>:<port>/export/<文件名>` 下载地址。

## 输出格式

- 结论先行：方法做什么 + 关键调用链。
- 关键引用逐条列出：`instruction 地址(vaddr)@fileOffset | 原字节 | 含义`。
- 若建议补丁：写明「替换为什么指令」及风险，等待用户确认再落盘。

## 注意

- 不要编造地址/字节/函数名——只引用工具回来的值。
- 调用图未建完（`graphBuilt=false`）时提示稍后再查，不要断言「无调用者」。
- 补丁默认关闭且破坏性；未经确认不做 `patch_*`/`engine_write_bytes`/`frida_patch_code`/`emu_write_*`。
