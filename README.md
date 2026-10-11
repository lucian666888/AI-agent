# AI Agent

基于 Spring AI 的 AI 智能体实战项目。围绕一个对话应用和一个自主规划智能体，完整覆盖 Spring AI 核心能力：多轮对话、对话记忆、RAG 知识库、Tool Calling、MCP 协议、ReAct 智能体、SSE 流式输出。

## 项目介绍

项目以一个「AI 情感顾问」对话应用和一个「自主规划智能体」为载体，把 Spring AI 的核心能力串成一条完整链路：

- **对话应用（LoveApp）**：扮演恋爱心理专家，支持多轮对话、对话记忆持久化、结构化输出（自动生成恋爱报告）、RAG 知识库问答、工具调用、MCP 服务调用。
- **自主规划智能体（Manus）**：基于 ReAct 模式实现，接收用户目标后自主推理、选择工具、执行、观察结果，循环迭代直到任务完成或主动终止。
- **工具集**：联网搜索、网页抓取、文件读写、终端命令、资源下载、PDF 生成、终止控制。
- **MCP 服务**：独立的图片搜索 MCP Server，主应用通过 stdio / SSE 协议接入。

## 项目结构

```
AI-agent/
├── src/main/java/com/lucian/aiagent/
│   ├── advisor/        # 自定义 Advisor（MyLoggerAdvisor 日志、ReReadingAdvisor 推理增强）
│   ├── agent/          # 智能体继承链：BaseAgent → ReActAgent → ToolCallAgent → Manus
│   ├── app/            # 业务应用 LoveApp（对话入口）
│   ├── chatmemory/     # 对话记忆（内存 MessageWindow / 文件 Kryo 序列化）
│   ├── config/         # CorsConfig 等配置
│   ├── constant/       # FileConstant 文件保存目录
│   ├── controller/     # AiController REST 接口（SSE 流式）
│   ├── demo/           # Spring AI / LangChain4j / Ollama 调用示例
│   ├── rag/            # 查询重写、向量存储、自定义 RAG Advisor、上下文增强
│   └── tools/          # 工具注册与 7 个具体工具实现
├── src/main/resources/
│   ├── application.yml
│   ├── application-prod.yml
│   ├── mcp-servers.json    # MCP 客户端配置（高德地图 + 图片搜索）
│   └── document/           # RAG 知识库语料（恋爱 FAQ）
├── frontend/           # Vue 3 + Vite 前端（聊天界面）
├── mcp-server/        # 图片搜索 MCP Server（独立 Spring Boot 应用）
└── pom.xml
```

## 技术栈

- **Java 21 + Spring Boot 3.4**
- **Spring AI 1.0** + Spring AI Alibaba（DashScope / 通义千问）
- **Ollama** 本地大模型接入
- **LangChain4j**
- **PgVector** 向量数据库
- **MCP**（Model Context Protocol）客户端与服务端
- **Kryo** 序列化、**Jsoup** 网页解析、**iText** PDF 生成
- **Knife4j** 接口文档
- **Vue 3 + Vite** 前端

## 核心流程

### 1. 普通对话流程（LoveApp）

```
用户消息
  ↓
ChatClient.prompt().user(message)
  ↓
[Advisor 链]
  ├─ MessageChatMemoryAdvisor  → 从 ChatMemory 取历史消息拼入上下文
  └─ MyLoggerAdvisor           → 记录请求/响应日志
  ↓
DashScope ChatModel（通义千问）
  ↓
SSE 流式返回（Flux<String>）
```

对话记忆默认使用 `MessageWindowChatMemory`（窗口大小 20 条），基于 `InMemoryChatMemoryRepository`；需要持久化时可切换到 `FileBasedChatMemory`（Kryo 序列化到本地文件）。

### 2. 结构化输出流程

```
用户消息
  ↓
ChatClient.prompt().system("每次对话后生成恋爱报告...")
  ↓
.call().entity(LoveReport.class)   // jsonschema-generator 自动生成 JSON Schema
  ↓
返回 LoveReport(title, suggestions)
```

### 3. RAG 知识库流程

```
用户问题
  ↓
QueryRewriter.doQueryRewrite()     // 查询重写：把口语化问题改写成检索友好的查询
  ↓
QuestionAnswerAdvisor
  ├─ VectorStoreDocumentRetriever  // 从 PgVector 检索 topK=3 相似文档（相似度阈值 0.5）
  └─ ContextualQueryAugmenter       // 上下文查询增强
  ↓
检索到的文档片段拼入 Prompt
  ↓
DashScope 生成回答
```

自定义 RAG 入口在 `LoveAppRagCustomAdvisorFactory`，支持按 `status` 字段（单身/恋爱/已婚）过滤文档。

### 4. ReAct 自主规划智能体流程（Manus）

```
用户目标
  ↓
BaseAgent.runStream(userPrompt)
  ↓ 循环（最多 maxSteps=20 步）
ReActAgent.step()
  ├─ think()：调大模型，传入当前消息列表 + 可用工具
  │    ├─ 模型返回 toolCalls 列表 → 返回 true
  │    └─ 模型直接给出回答（无需工具）→ 返回 false，结束
  └─ act()：
       ├─ ToolCallingManager.executeToolCalls() 执行工具
       ├─ 结果回灌 messageList
       └─ 如果调用了 TerminateTool → 状态置为 FINISHED
  ↓
SSE 逐步推送每一步的思考和工具执行结果
```

继承链：
- `BaseAgent`：状态机（IDLE → RUNNING → FINISHED / ERROR）+ 执行循环 + SseEmitter 异步推送
- `ReActAgent`：定义 think / act 抽象，step() = think + act
- `ToolCallAgent`：具体实现 think（调模型拿工具调用）和 act（执行工具）
- `Manus`：注入系统提示词（"You are Manus..."）和下一步提示词

### 5. Tool Calling 流程

工具在 `ToolRegistration` 中集中注册为 `ToolCallback[]` Bean：

| 工具 | 作用 |
|---|---|
| `FileOperationTool` | 文件读写 |
| `WebSearchTool` | 联网搜索（SearchAPI） |
| `WebScrapingTool` | 网页内容抓取（Jsoup） |
| `ResourceDownloadTool` | 资源下载 |
| `TerminalOperationTool` | 终端命令执行 |
| `PDFGenerationTool` | iText 生成 PDF |
| `TerminateTool` | 主动终止智能体循环 |

调用时通过 `DashScopeChatOptions.withInternalToolExecutionEnabled(false)` 关闭 Spring AI 内置工具执行，由 `ToolCallingManager` 手动管理，便于在 act() 中观察每一步结果。

### 6. MCP 接入流程

主应用作为 MCP Client，配置在 `src/main/resources/mcp-servers.json`：

```json
{
  "mcpServers": {
    "amap-maps": { "command": "npx.cmd", "args": ["-y", "@amap/amap-maps-mcp-server"] },
    "image-search-mcp-server": { "command": "java", "args": ["-jar", "image-search-mcp-server/target/...jar"] }
  }
}
```

主应用启动后，Spring AI MCP Client 自动通过 stdio 拉起这些服务，把它们的工具注册成 `ToolCallbackProvider`，LoveApp 可以直接调用。

## 快速开始

### 后端

```bash
# 1. 配置 API Key（application-local.yml 不提交 git）
cp src/main/resources/application.yml application-local.yml
# 编辑 application-local.yml，填入 DashScope API Key、SearchAPI Key

# 2. 启动
./mvnw spring-boot:run
```

服务默认监听 `http://localhost:8123/api`，接口文档：`http://localhost:8123/api/swagger-ui.html`

### 前端

```bash
cd frontend
npm install
npm run dev
```

### MCP Server（可选）

```bash
cd mcp-server
./mvnw spring-boot:run
```

## 配置项

`application-local.yml`（不提交到 git）中覆盖以下配置：

| 配置 | 说明 |
|---|---|
| `spring.ai.dashscope.api-key` | 阿里云百炼 API Key |
| `search-api.api-key` | 联网搜索 API Key |
| `spring.ai.ollama.base-url` | 本地 Ollama 地址 |
| `spring.ai.vectorstore.pgvector.*` | PgVector 数据库连接 |

## 接口示例

```bash
# SSE 流式对话
curl -N "http://localhost:8123/api/ai/love_app/chat/sse?message=你好&chatId=test1"

# 自主规划智能体
curl -N "http://localhost:8123/api/ai/manus/chat?message=帮我查一下今天北京天气并生成一份出行建议"
```
