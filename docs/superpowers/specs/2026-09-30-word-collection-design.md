# 生词本 / 好句本 收录功能 — 设计文档

- **日期**：2026-09-30
- **项目**：bud-wordlist（Spring Boot 4.1.1 / Java 17 / Maven）
- **状态**：待评审

---

## 1. 背景与目标

英语学习 App 的用户在阅读任意一篇考题（KET / PET / 中考 / 雅思的阅读、完形等）时，可以随手点选单词或句子，收录进自己的生词本 / 好句本。

**成功标准**

1. 用户点"收录"后接口在 100ms 内返回，不因外部 AI 服务延迟而卡住或失败。
2. 同一个单词（或句子）在全平台只存一份内容，不同用户通过关联表挂到自己名下；重复收录幂等。
3. 新词的内容自动生成，无需人工录入；生成失败可补偿，用户数据不丢。
4. 换 AI 供应商不需要改代码，只需改配置。

## 2. 范围

**做**

- 4 个级别的单词表、句子表及配套的用户关联表
- 单词 / 句子的收录、取消收录、列表查询、详情查询
- 跨级别内容拷贝（可开关、拷贝链可配置）
- AI 生成单词卡片 / 句子分析（provider 可切换）
- 生成失败的异步补偿

**不做（明确排除）**

- 用户注册登录、鉴权（`userId` 由上游服务直接传入）
- 考题 / 阅读文章本身的管理（只存收录来源的 id 和标题快照，不存正文）
- 复习计划、记忆曲线、测验等学习功能
- 词库的人工编辑后台（后续单独做）
- 句子翻译（只做"句子分析"）

## 3. 名词与约定

| 名词 | 含义 |
|---|---|
| **级别 (level)** | `ket` / `pet` / `zhongkao` / `ielts` 四个之一，值域封闭 |
| **词库** | 某个级别对应的单词表，如 `ket_word` |
| **句库** | 某个级别对应的句子表，如 `ket_sentence` |
| **收录** | 用户把某个单词/句子加入自己的生词本/好句本，即向关联表插入一行 |
| **规范化单词** | `word.trim().toLowerCase()`，用于查重与存储 |
| **规范化句子** | 原句 `trim` → 连续空白折叠为单个空格 → `toLowerCase()`，仅用于算 hash |

**关键约定：查重与存储一律使用规范化后的值，展示也用规范化后的单词**（英语学习场景下首字母大写无意义）。句子则相反——**原句原样保存**，只有算 hash 时才规范化，因此 "Hello world." 与 "hello world." 去重为一条，但展示的是用户第一次收录时的原文。

## 4. 数据模型

所有表由 Flyway 管理（`src/main/resources/db/migration/V1__init.sql`），应用启动自动建表。

### 4.1 单词表（4 张同构）

`ket_word` / `pet_word` / `zhongkao_word` / `ielts_word`，以 KET 为例：

```sql
CREATE TABLE ket_word (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '单词唯一id',
  word        VARCHAR(64)  NOT NULL COLLATE utf8mb4_bin COMMENT '英文单词（规范化后：trim+小写）',
  meaning     TEXT         NULL COMMENT '中文意思',
  phonetic    VARCHAR(128) NULL COMMENT '音标',
  pos         VARCHAR(64)  NULL COMMENT '词性',
  mnemonic    TEXT         NULL COMMENT '助记',
  example     TEXT         NULL COMMENT '例句',
  phrase      TEXT         NULL COMMENT '词组',
  status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=生成中 1=已完成 2=生成失败',
  source      TINYINT      NOT NULL DEFAULT 1 COMMENT '1=AI生成 2=跨级别拷贝 3=人工录入',
  retry_count TINYINT      NOT NULL DEFAULT 0 COMMENT 'AI 生成已尝试次数（含首次）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_word (word),
  KEY idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='KET 单词表';
```

需求给出的 8 个字段全部保留。额外的 4 列及其存在理由：

- `status` — 承载"生成中"中间态，同时是异步补全任务的乐观锁
- `source` — 区分内容来源，便于统计 AI 成本与人工质量对比
- `retry_count` — 补偿任务判断是否放弃重试的依据
- `created_at` / `updated_at` — 运维与补偿任务扫描用

**`word` 列显式使用 `utf8mb4_bin` 排序规则**：默认的 `utf8mb4_0900_ai_ci` 是重音不敏感的，会把 `cafe` 和 `café` 判为同一个词而误去重。二进制排序规则配合应用层的统一小写，得到精确匹配语义。

### 4.2 句子表（4 张同构）

```sql
CREATE TABLE ket_sentence (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '句子唯一id',
  hash        CHAR(64)     NOT NULL COLLATE utf8mb4_bin COMMENT '规范化原句的 SHA-256（小写 hex）',
  sentence    TEXT         NOT NULL COMMENT '句子原句（原样保存）',
  analysis    TEXT         NULL COMMENT '句子分析',
  status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=生成中 1=已完成 2=生成失败',
  source      TINYINT      NOT NULL DEFAULT 1 COMMENT '1=AI生成 2=跨级别拷贝 3=人工录入',
  retry_count TINYINT      NOT NULL DEFAULT 0 COMMENT 'AI 生成已尝试次数（含首次）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_hash (hash),
  KEY idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='KET 句子表';
```

### 4.3 用户关联表（2 张）

```sql
CREATE TABLE user_word (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id      BIGINT UNSIGNED NOT NULL COMMENT '用户id（上游传入，不建外键）',
  level        VARCHAR(16)     NOT NULL COLLATE utf8mb4_bin COMMENT 'ket/pet/zhongkao/ielts',
  word_id      BIGINT UNSIGNED NOT NULL COMMENT '对应级别词表的主键',
  source_type  TINYINT         NULL COMMENT '来源类型：1=阅读 2=完形 3=听力 4=写作 5=其他',
  source_id    BIGINT UNSIGNED NULL COMMENT '来源考题id',
  source_title VARCHAR(255)    NULL COMMENT '来源考题标题快照（冗余，列表直接展示，避免跨库查询）',
  created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_word (user_id, level, word_id),
  KEY idx_user_time (user_id, level, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户单词收录关联';

CREATE TABLE user_sentence (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id      BIGINT UNSIGNED NOT NULL COMMENT '用户id（上游传入，不建外键）',
  level        VARCHAR(16)     NOT NULL COLLATE utf8mb4_bin COMMENT 'ket/pet/zhongkao/ielts',
  sentence_id  BIGINT UNSIGNED NOT NULL COMMENT '对应级别句表的主键',
  source_type  TINYINT         NULL COMMENT '来源类型：1=阅读 2=完形 3=听力 4=写作 5=其他',
  source_id    BIGINT UNSIGNED NULL COMMENT '来源考题id',
  source_title VARCHAR(255)    NULL COMMENT '来源考题标题快照',
  created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_sentence (user_id, level, sentence_id),
  KEY idx_user_time (user_id, level, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户句子收录关联';
```

**设计说明**

- 唯一键 `(user_id, level, word_id)` 让重复收录天然幂等，应用层无需"先查后插"。
- `word_id` / `sentence_id` 指向 4 张同构表中的某一张（多态外键），**因此无法建立数据库外键约束**。层级一致性由应用层保证：`level` 值与 `id` 所在的表必须匹配，写入时由同一段代码同时决定，不存在单独修改其中一个的路径。
- `source_title` 存的是收录那一刻的标题快照，不跟随考题改名。这是有意的——生词本要还原的是"我当时读的那篇"。

## 5. 收录流程

### 5.1 收录单词

```
POST /api/collect/word
入参：userId, level, word, sourceType?, sourceId?, sourceTitle?

1. 校验 level 属于枚举白名单，word 非空且长度 <= 64
2. normalized = word.trim().toLowerCase()
3. 查 <level>_word WHERE word = normalized
   ├─ 命中（status 任意）→ wordId = 命中的 id
   └─ 未命中 ↓
       a. 跨级别拷贝（见 §6）
          ├─ 命中 → 整行复制 6 个内容字段到 <level>_word
          │        设置 source=2(拷贝), status=1(已完成)
          │        → wordId = 新 id【不调用 AI】
          └─ 未命中 → 走 b
       b. INSERT INTO <level>_word (word, status, retry_count)
          VALUES (normalized, 0, 1)          -- retry_count=1：本次即第 1 次投递
          → wordId = 新 id
          → 投递生成任务到线程池
          （若抛 DuplicateKeyException，说明并发下已被别的请求建好，
            重新 SELECT 取 id，不重复投递任务）
4. INSERT INTO user_word (user_id, level, word_id, source_*)
   若抛 DuplicateKeyException → 判定"之前已收录过"，视为成功
5. 立即返回（不等待 AI）
```

**返回时 `status=0` 的项，6 个内容字段为 `null`**，客户端渲染骨架屏；下次拉取列表或详情即为完整卡片。

### 5.2 收录句子

同 5.1，差异仅有两点：

- 查重键从 `word` 换成 `hash`，`hash = SHA-256(规范化句子)` 的小写 hex
- 入库时**同时写 `sentence` 原文和 `hash`**

### 5.3 并发正确性

两个用户同时收录同一个新词，会双双走到步骤 3b。`word` 上的唯一索引保证只有一次 INSERT 成功，另一次抛 `DuplicateKeyException` 后重新 SELECT 取回 id。最终 `<level>_word` 只有一行，`user_word` 有两行（各自关联），生成任务只投递一次。

> 不使用 MySQL 的 `INSERT IGNORE`：它除了忽略唯一键冲突，还会静默吞掉数据截断、类型不匹配等其他错误。应用层捕获 `DuplicateKeyException` 更精确。

## 6. 跨级别拷贝

新词落库前，先按配置的"拷贝链"到其他级别的词库找一圈。命中就整行复制内容，省下一次 AI 调用，且人工校对过的内容质量通常高于生成内容。

### 6.1 配置

```yaml
wordlist:
  copy:
    enabled: true      # 总开关，false 则所有新词一律走 AI
    chain:             # 目标级别: 来源级别（可配多条，按顺序尝试）
      ket: pet
      pet: zhongkao
      # ielts: zhongkao      # 需要时取消注释
      # zhongkao: pet        # 中考配了来源才会拷贝
```

- 默认链：`ket ← pet ← zhongkao`。**`zhongkao` 与 `ielts` 默认没有来源，全部走 AI 生成**。
- 链上可以配多个来源（如 `ket: [pet, zhongkao]`），按顺序尝试，先命中先赢。
- 总开关关闭时，整个拷贝步骤跳过。

### 6.2 拷贝的内容范围

只复制 6 个业务内容字段（`meaning` / `phonetic` / `pos` / `mnemonic` / `example` / `phrase`）与 `status=1`，**不复制 `id`、`created_at`**。新记录标记 `source=2`，以便日后统计有多少词是靠拷贝省下的。

**已知取舍**：拷贝是快照，来源词后来被修订不会同步到已拷贝的记录。这是有意接受的——拷贝发生在收录那一刻，语义是"把当时能找到的最好内容拿过来"。若日后需要同步，另做数据订正任务。

### 6.3 安全约束

**表名永远来自 `Level` 枚举的白名单映射，绝不由用户传入的字符串拼接而成。** `level` 参数在反序列化阶段就校验，非法值直接返回 400，不进入 Service 层。

## 7. AI 生成层

### 7.1 接口

```java
/** 生成单词卡片，失败抛 GenerationException */
public interface WordCardGenerator {
    WordCard generate(String word, String level);
}

/** 生成句子分析，失败抛 GenerationException */
public interface SentenceAnalyzer {
    String analyze(String sentence, String level);
}

public record WordCard(
    String meaning,   // 中文意思
    String phonetic,  // 音标
    String pos,       // 词性
    String mnemonic,  // 助记
    String example,   // 例句
    String phrase     // 词组
) {}
```

两个接口而非一个，是因为二者的调用形态与失败处理可以独立演进（句子分析未来可能改为流式）。

### 7.2 实现（共 3 个）

共 3 组、每组 2 个类（分别实现 `WordCardGenerator` 与 `SentenceAnalyzer`）：

| provider 值 | 单词实现 | 句子实现 | 说明 |
|---|---|---|---|
| `openai-compatible` | `OpenAiCompatibleWordCardGenerator` | `OpenAiCompatibleSentenceAnalyzer` | DeepSeek、通义千问、智谱 GLM、Kimi 等国内主流厂商均提供 OpenAI 兼容的 `/chat/completions`。**一个实现覆盖多家，切换供应商只改配置。** 结构化输出走 `response_format: {"type": "json_object"}` + prompt 内给出字段说明 |
| `claude` | `ClaudeWordCardGenerator` | `ClaudeSentenceAnalyzer` | 走 Anthropic 官方 Java SDK（`com.anthropic:anthropic-java`），结构化输出用 `outputConfig(WordCard.class)`，由 API 保证 schema 合规 |
| `none` | `NoopWordCardGenerator` | `NoopSentenceAnalyzer` | 降级态：不做任何生成，`status` 停在 0。用于关闭 AI 成本或未配置密钥时 |

两组 `openai-compatible` / `claude` 实现共享一个内部的 HTTP 调用与容错解析工具类，避免同一套 JSON 容错逻辑写两遍。

选用哪个实现由 `@ConditionalOnProperty(name = "wordlist.llm.provider")` 决定，Spring 启动时装配。

> **`provider: none` 时补偿任务必须一并关闭**（见 §10.2），否则补偿任务会把 `status=0` 的记录反复重新投递，而 Noop 实现永远不会推进状态，形成空转。

### 7.3 结构化输出的容错

OpenAI 兼容实现**必须做返回解析容错**，实测中模型经常：

- 用 ` ```json ` 围栏包裹整个 JSON → 解析前剥离围栏
- 在 JSON 前后附带解释性文字 → 取第一个 `{` 到最后一个 `}` 的子串
- 缺失个别字段 → 缺失字段留 `null`，不抛异常（`meaning` 缺失视为生成失败）
- 返回非法 JSON → 计为一次失败，进入重试

Claude 实现无需这些容错（schema 由 API 强制），但仍需处理 `stop_reason: "refusal"` —— 此时内容为空，直接判定失败。

### 7.4 重试与超时

- **单次调用超时 30s**（HTTP 客户端层，含连接与读取）
- **最多尝试 3 次**（`wordlist.llm.max-attempts`，这是总次数，含首次，不是重试次数），退避间隔 1s / 4s，各次叠加 ±20% 随机抖动，避免重试风暴同步
- **只重试可重试错误**：超时、连接失败、HTTP 429、HTTP 5xx、JSON 解析失败
- **不重试**：HTTP 4xx（除 429）——请求本身有问题，重试只是浪费配额
- 3 次耗尽 → `status=2`，记 ERROR 日志

### 7.4.1 `retry_count` 的归属

`retry_count` 的语义是**这条记录被投递生成的次数（含首次）**，由**投递方**在投递时递增，**生成器自己不维护它**。这样职责单一，避免生成器与补偿任务同时改同一个计数器。

- 首次收录入库时：`status=0, retry_count=1`
- 生成器只做两件事：成功 → `UPDATE ... SET <6字段>, status=1 WHERE id=? AND status=0`；失败 → `UPDATE ... SET status=2 WHERE id=? AND status=0`
- 补偿任务的投递与计数见 §10.2

`retry_count` 与 `max-attempts` 可以直接比较，不存在 ±1 的换算。

### 7.5 写回

```
UPDATE <level>_word
   SET meaning=?, phonetic=?, pos=?, mnemonic=?, example=?, phrase=?,
       status=1, updated_at=NOW()
 WHERE id=? AND status=0
```

`AND status=0` 是关键：它使写回操作幂等。补偿任务与首次任务若同时完成，只有一个能更新成功，不会用两次不同的生成结果互相覆盖。若 `affected_rows=0`，说明记录已被处理过，直接丢弃本次结果。

## 8. 接口定义

所有接口统一响应包装：`{"code": 0, "msg": "ok", "data": {...}}`，业务失败 `code` 非 0。`userId` 均为必传。

### 8.1 `POST /api/collect/word` — 收录单词

```jsonc
// 请求
{
  "userId": 1001,
  "level": "ket",
  "word": "Hello",                       // 大小写不敏感，服务端规范化
  "sourceType": 1,                       // 可选
  "sourceId": 8001,                      // 可选
  "sourceTitle": "2024年KET真题 Reading Part 1"   // 可选
}

// 响应
{
  "code": 0,
  "data": {
    "wordId": 123,
    "word": "hello",
    "level": "ket",
    "status": 0,              // 0=生成中 1=已完成 2=生成失败
    "newlyCollected": true,   // false 表示之前已收录过，本次为幂等重复
    "meaning": null,          // status != 1 时，以下 6 个字段均为 null
    "phonetic": null,
    "pos": null,
    "mnemonic": null,
    "example": null,
    "phrase": null
  }
}
```

### 8.2 `POST /api/collect/sentence` — 收录句子

```jsonc
// 请求
{
  "userId": 1001,
  "level": "ket",
  "sentence": "He has been living here since 2019.",
  "sourceType": 1,
  "sourceId": 8001,
  "sourceTitle": "2024年KET真题 Reading Part 1"
}

// 响应
{
  "code": 0,
  "data": {
    "sentenceId": 456,
    "hash": "a3f1...",
    "sentence": "He has been living here since 2019.",
    "level": "ket",
    "status": 0,
    "newlyCollected": true,
    "analysis": null
  }
}
```

### 8.3 `DELETE /api/collect/word` — 取消收录

```jsonc
// 请求
{ "userId": 1001, "level": "ket", "wordId": 123 }
// 响应
{ "code": 0, "data": { "removed": true } }   // removed=false 表示本来就没收录
```

只删 `user_word` 的关联行，**不删词库记录**（其他用户可能还在用）。

### 8.4 `DELETE /api/collect/sentence` — 取消收录

请求体 `{ userId, level, sentenceId }`，语义同上。

### 8.5 `GET /api/collect/words` — 生词本列表

```
GET /api/collect/words?userId=1001&level=ket&page=1&size=20
```

**`level` 必传**。生词本按级别分页展示，这样列表查询是 `user_word ⋈ <level>_word` 的单次两表 join，简单且能走索引。混合级别的列表需要 4 张表 UNION 或 4 次查询归并，代价高、收益低，暂不做。

```jsonc
{
  "code": 0,
  "data": {
    "total": 128,
    "page": 1,
    "size": 20,
    "list": [
      {
        "wordId": 123,
        "word": "hello",
        "status": 1,
        "meaning": "int. 你好；喂",
        "phonetic": "/həˈləʊ/",
        "pos": "int.",
        "mnemonic": "和中文'哈喽'谐音",
        "example": "Hello, how are you?",
        "phrase": "say hello to sb.",
        "sourceTitle": "2024年KET真题 Reading Part 1",
        "collectedAt": "2026-09-30T15:41:00"
      }
    ]
  }
}
```

按 `user_word.created_at DESC` 排序（最近收录的在前）。`size` 上限 100。

### 8.6 `GET /api/collect/sentences` — 好句本列表

参数与结构同上，`level` 必传。

### 8.7 `GET /api/word/detail` — 单词详情

```
GET /api/word/detail?level=ket&wordId=123
GET /api/word/detail?level=ket&word=hello     // 二选一，wordId 优先
```

用于客户端在收录后轮询生成状态。返回结构与 8.1 的 `data` 相同（`newlyCollected` 字段省略）。

**句子详情接口暂不提供**——句子内容较长，列表接口一次性返回不会造成明显负担；单词列表则可能因内容长而需要详情页单独拉取。

## 9. 配置项汇总

```yaml
wordlist:
  copy:
    enabled: true
    chain:
      ket: pet
      pet: zhongkao

  llm:
    provider: openai-compatible     # openai-compatible | claude | none
    model: deepseek-chat
    base-url: https://api.deepseek.com
    api-key: ${LLM_API_KEY}         # 走环境变量，不进 git
    timeout-seconds: 30
    max-attempts: 3

  async:
    core-pool-size: 4
    max-pool-size: 8
    queue-capacity: 500
    compensate-enabled: true         # provider=none 时应设为 false
    compensate-cron: "0 */5 * * * ?"
    stale-threshold-seconds: 120     # status=0 超过该时长视为任务丢失
```

补偿任务的放弃阈值复用 `wordlist.llm.max-attempts`，不单独配置——两个数字表达同一件事，分开配迟早会不一致。

Spring Boot 侧还需配置数据源（`spring.datasource.*`）与 Flyway。

## 10. 异常处理与补偿

### 10.1 线程池与背压

生成任务投递到 `ThreadPoolTaskExecutor`（核心 4 / 最大 8 / 队列 500）。

**队列满时的策略：拒绝任务，但保留 `status=0` 的词库记录。** 该记录会被补偿任务在下一轮扫描到并重新投递。这样突发流量不会导致数据丢失，只会让部分卡片延迟完成。绝不使用 `CallerRunsPolicy`——那会让请求线程去跑 AI 调用，直接违背"接口 100ms 返回"的目标。

### 10.2 补偿任务

每 5 分钟（cron 可配）执行一次，捞出两类还有救的记录（`max-attempts` 即 §7.4 的配置值）：

1. **疑似丢失**：`status=0 AND updated_at < NOW() - stale-threshold-seconds AND retry_count < max-attempts`
   —— 说明任务投递后从未完成（进程重启、队列拒绝、任务异常逃逸）
2. **待重试**：`status=2 AND retry_count < max-attempts`
   —— 之前失败但还有重试额度

对每条记录，**先做一次带条件的原子更新，成功了才投递**：

```sql
UPDATE <level>_word
   SET status = 0, retry_count = retry_count + 1
 WHERE id = ?
   AND status IN (0, 2)
   AND retry_count < :maxAttempts
```

只有 `affected_rows = 1` 时才把任务投给线程池。这一步同时完成了三件事：

- 把 `status=2` 的记录**翻回 `status=0`**——否则生成器回写时的 `AND status=0` 守卫会失败，结果被静默丢弃
- 递增投递计数，超过 `max-attempts` 的记录不再被捞起，成为终态
- 作为并发闸门：多个补偿任务实例或与首次投递撞车时，只有一个能把记录推进

**判断"疑似丢失"用 `updated_at` 而不是 `created_at`**：上面的 UPDATE 会因 `ON UPDATE CURRENT_TIMESTAMP` 刷新 `updated_at`，所以一条刚被重新投递、还在生成中的记录不会在下一轮扫描时被重复捞起。用 `created_at` 会导致同一条记录每 5 分钟被无脑重投一次。

`retry_count` 达到 `max-attempts` 后记录停在 `status=2`，这是终态——需要人工介入或另做管理接口重跑。用户侧的收录数据不受影响，卡片会一直显示"生成失败"。

**`provider=none` 时补偿任务整体关闭**。此时所有新词都停在 `status=0` 是预期行为，不是故障。这样设计还有一个好处：日后启用 AI 时，历史积压的 `status=0` 记录会被补偿任务自动捞起来补全。

补偿任务本身需要防重入（多实例或定时任务重叠触发时会重复投递同一批记录）。本项目当前单实例部署，暂用 `@Scheduled` 的单机定时任务并加进程内重入锁；若日后多实例，换成基于数据库的抢占式调度（`UPDATE ... SET locked_until = ? WHERE locked_until < NOW() LIMIT n`）。

### 10.3 业务异常

| 场景 | 返回 |
|---|---|
| `level` 不在白名单 | 400，`code=1001` |
| `word` 为空或超长 | 400，`code=1002` |
| `word` 含非法字符（非字母、空格、连字符、撇号） | 400，`code=1003` |
| `userId` 缺失或 <= 0 | 400，`code=1004` |
| AI 生成失败 | 不影响收录，接口正常返回；记录 `status=2` |

## 11. 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| Web 框架 | Spring Boot 4.1.1 WebMVC | 项目已有 |
| 持久层 | **MyBatis-Plus** | **由表结构倒逼**：4 张同构表需要表名参数化的 Mapper，MyBatis 一套即可；JPA 要写 4 套 Entity + 4 套 Repository，且动态表名支持很差 |
| 数据库 | MySQL 8（复用 ECS 上现成实例，新建独立库 `wordlist`） | 用户环境既有 |
| 建表 | Flyway | 表结构后续必然演进，需要版本化迁移 |
| HTTP 客户端 | Spring 6 的 `RestClient` | 无需引入额外依赖，Spring Boot 4 自带 |
| Claude SDK | `com.anthropic:anthropic-java` | 官方 SDK，结构化输出开箱可用 |
| 分页 | MyBatis-Plus `Page` | 与 ORM 配套 |
| 测试 | JUnit 5 + Spring Boot Test + **WireMock** + Testcontainers(MySQL) | 见 §12 |

**`level` → 表名的映射收在 `Level` 枚举里**：

```java
public enum Level {
    KET("ket_word", "ket_sentence"),
    PET("pet_word", "pet_sentence"),
    ZHONGKAO("zhongkao_word", "zhongkao_sentence"),
    IELTS("ielts_word", "ielts_sentence");
    // ...
}
```

MyBatis 中用 `${level.wordTable}` 引用。由于该值只可能来自枚举常量，不存在 SQL 注入路径。

## 12. 测试策略

分层，**CI 中不调用真实 AI 接口**（慢、要钱、不稳定）。

### 12.1 单元测试

- 单词规范化与句子 hash 规范化（大小写、首尾空白、连续空白、多行）
- 拷贝链解析：配置成 map / list 两种形态；链上的级别不存在时的降级行为
- JSON 解析容错：markdown 围栏、前后夹带文字、字段缺失、非法 JSON
- 重试判定：哪些错误重试、哪些不重试
- `Level` 枚举白名单拒绝非法值

### 12.2 集成测试（Testcontainers 起真实 MySQL）

- **重复收录幂等**：同一用户同一词收两次，`user_word` 只有一行，第二次 `newlyCollected=false`
- **并发收录同一个新词**：模拟 10 个并发请求，`<level>_word` 只有一行，生成任务只投递一次
- **拷贝链命中**：`pet_word` 有 `hello` → 收录 KET 的 `hello` → 内容被复制且 `source=2`，**AI 未被调用**（用 Fake 实现断言零调用）
- **拷贝链未命中** → 走 AI，`status` 先 0 后 1
- **拷贝开关关闭** → 即使 `pet_word` 有内容也仍走 AI
- **AI 失败** → `status=2`，`retry_count` 递增，用户收录接口依然成功返回
- **写回幂等** → 同一记录两次写回，第二次 `affected_rows=0`
- **越权防护** → 取消收录他人（不同 `userId`）的关联，不产生任何影响

### 12.3 LLM 客户端测试（WireMock）

- 正常响应 → 正确反序列化
- 超时 → 触发重试
- 429 / 500 → 触发重试；400 → 不重试
- 畸形响应（markdown 围栏、缺字段）→ 按 §7.3 容错

### 12.4 测试替身

`WordCardGenerator` / `SentenceAnalyzer` 在集成测试中替换为 `FakeWordCardGenerator`（立即返回固定卡片，可配置为抛错或延迟），通过 `@TestConfiguration` 注入。

## 13. 部署

- 构建：`./mvnw clean package`
- 运行：`java -jar target/wordlist-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod`
- 敏感配置（数据库密码、LLM API Key）通过**环境变量**注入，`application-prod.yml` 中只写占位符
- 表结构由 Flyway 在启动时自动创建
- 部署前需要提供：MySQL 连接信息（host / port / 库名 / 账号密码），LLM 供应商的 API Key 与 base-url

## 14. 待确认项

1. **`ielts` 和 `zhongkao` 的拷贝来源** — 当前设计默认这两级不走拷贝、全部走 AI。若要配，改 §6.1 的 `chain` 即可，无需改代码。
2. **`source_type` 的取值范围** — 暂定 1=阅读 2=完形 3=听力 4=写作 5=其他，需与实际题型对齐。
3. **单词合法性校验的严格程度** — §10.3 提议拒绝非字母/空格/连字符/撇号以外的字符（防止把整段话当单词存进来），若有包含数字或点的词（如 `e.g.`、`3D`）需要放宽。
