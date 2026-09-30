# 生词本 / 好句本 收录功能 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户在阅读任意考题时，能一键把单词/句子收录进自己的生词本/好句本；内容不存在时自动生成，接口恒定立即返回。

**Architecture:** 单体 Spring Boot 服务 + MySQL。收录走"查库 → 跨级别拷贝 → 落库 → 立即返回 → 后台异步 AI 生成"的写路径；生成失败由定时补偿任务兜底。AI 供应商通过 `provider` 配置在三个实现间切换，业务代码只依赖 `WordCardGenerator` / `SentenceAnalyzer` 两个接口。

**Tech Stack:** Spring Boot 4.1.1 / Java 17 / Maven · MySQL 8 · Flyway · Spring `JdbcClient` · Anthropic Java SDK · JUnit 5 + AssertJ + Testcontainers + WireMock

**Spec:** `docs/superpowers/specs/2026-09-30-word-collection-design.md`

---

## ⚠️ 对 spec 的一处偏离（请评审时确认或否决）

spec §11 选型为 **MyBatis-Plus**。本计划改用 **Spring 自带的 `JdbcClient`**，理由：

1. **兼容性风险**：Spring Boot 4.1.1 是新的大版本，MyBatis-Plus 官方只提供 `mybatis-plus-spring-boot3-starter`（面向 Boot 3）。在 Boot 4 上能否正常装配没有保证，而这是项目最底层的地基，地基不稳后面全白做。
2. **ORM 在这里不产生价值**：全项目约 10 条 SQL，没有复杂对象图、没有级联、没有懒加载。MyBatis-Plus 的代码生成、`LambdaQueryWrapper`、分页插件在本项目都用不上（分页自己写 `LIMIT/OFFSET` 只有两行）。
3. **动态表名是核心诉求**：4 张词表 + 4 张句表结构完全相同，差别只有表名。这在原生 SQL 里就是字符串拼接（表名来自枚举白名单，无注入风险），在 MyBatis-Plus 里则要额外配置 `DynamicTableNameInnerInterceptor`，反而更绕。

`JdbcClient` 是 Spring Framework 6.1+ 自带的（Boot 3.2+ 起可用），无额外依赖。

**若你否决**：把 Task 1 的 `spring-boot-starter-jdbc` 换成 MyBatis-Plus starter，Task 4/5 的仓库实现改为 Mapper 接口 + XML，其余任务（服务层、生成层、Web 层）完全不受影响——它们只依赖仓库的方法签名。

---

## Global Constraints

- **Java 17**，语言特性可用 record / sealed / switch 表达式 / text block
- **Spring Boot 4.1.1**，由父 POM 管理依赖版本；只有 `anthropic-java` 与 `wiremock-standalone` 需要显式写 `<version>`
- **所有 SQL 的表名只能来自 `Level` 枚举**。任何情况下不得把请求参数直接拼进 SQL。这是本项目唯一的 SQL 注入面
- **`userId` 由上游直接传入，服务不做鉴权**。所有写操作必须带 `userId`，删除操作必须同时匹配 `userId`，防止越权删他人数据
- **接口不等待 AI**：`POST /api/collect/*` 的响应时间不得因 AI 调用而增加
- **敏感配置走环境变量**：数据库密码、LLM API Key 一律 `application-prod.yml` 写占位符 + 环境变量注入，不提交真值
- **测试不打真实 AI 接口**。所有生成器测试用 WireMock 打桩；服务层测试用 Fake 实现
- **前置环境**：本机需安装并运行 **Docker Desktop**（Testcontainers 起 MySQL 用）；需装 **JDK 17**

---

## Review Focus

以下 6 类是 spec 暗含要求、但光看 spec 容易漏掉的输入/失败模式。每条都会在标注的任务里补上对应的测试。

1. **超长或含非法字符的单词** —— `word` 列是 `VARCHAR(64)`。用户传入 200 字符的串，或整段话、emoji、换行符。合理预期是 400 带明确错误码，而不是 MySQL 静默截断成 64 字符后入库、或抛 500。（Task 2 / Task 13）
2. **`level` 参数的大小写与空白** —— 客户端传 `"KET"`、`" ket "`。合理预期是正常识别，而不是 400。但 `"ketx"` 必须 400。（Task 2）
3. **`source_title` 超长** —— 列是 `VARCHAR(255)`。考题标题可能很长。合理预期是安全截断入库、接口成功，而不是 500。（Task 7）
4. **收录后、生成完成前用户取消收录** —— 词库记录被别的用户引用，所以不会被删。生成完成时的 `UPDATE` 仍应命中并成功，不能报错。（Task 8）
5. **`provider=openai-compatible` 但 API Key 为空** —— 合理预期是**启动时快速失败**并给出清晰提示，而不是启动成功、之后每个新词都在后台静默失败。（Task 10）
6. **跨级别同名不同表** —— KET 用户和 PET 用户同时收录同一个词，各自写入各自级别的表，不得串表。这是 4 张同构表设计下最容易出的错。（Task 4 / Task 7）

---

## 文件结构

新增文件按职责分组，每个文件一个明确职责：

```
wordlist/
├── pom.xml                                              [改] 加依赖
├── src/main/resources/
│   ├── application.yml                                  [改] 默认配置（dev 用）
│   ├── application-prod.yml                             [新] 生产配置，敏感项走环境变量
│   └── db/migration/V1__init.sql                        [新] 全部建表 DDL
├── src/main/java/com/bud/wordlist/
│   ├── WordlistApplication.java                         [已有]
│   ├── common/
│   │   ├── Level.java                                   级别枚举 + 表名白名单映射
│   │   ├── GenStatus.java                              生成状态枚举
│   │   ├── ContentSource.java                          内容来源枚举
│   │   ├── ApiResponse.java                            统一响应包装
│   │   ├── ErrorCode.java                              错误码常量
│   │   ├── BizException.java                           业务异常
│   │   └── GlobalExceptionHandler.java                 @RestControllerAdvice
│   ├── config/
│   │   ├── WordlistProperties.java                     @ConfigurationProperties("wordlist")
│   │   ├── AsyncConfig.java                            生成任务线程池
│   │   └── LlmConfig.java                              按 provider 装配生成器 Bean
│   ├── domain/
│   │   ├── Word.java                                   词表行
│   │   ├── Sentence.java                               句表行
│   │   ├── UserWordRow.java                            关联表行
│   │   └── UserSentenceRow.java                        关联表行
│   ├── repository/
│   │   ├── WordRepository.java                         4 张词表的全部读写
│   │   ├── SentenceRepository.java                     4 张句表的全部读写
│   │   ├── UserWordRepository.java                     用户单词关联 + 分页联查
│   │   └── UserSentenceRepository.java                 用户句子关联 + 分页联查
│   ├── service/
│   │   ├── WordNormalizer.java                         规范化 + SHA-256
│   │   ├── CopyResolver.java                           拷贝链解析
│   │   ├── WordCollectService.java                     单词收录主流程
│   │   ├── SentenceCollectService.java                 句子收录主流程
│   │   ├── GenerationDispatcher.java                   投递 + 重试 + 写回
│   │   └── GenerationCompensator.java                  补偿定时任务
│   ├── generation/
│   │   ├── WordCardGenerator.java                      [接口]
│   │   ├── SentenceAnalyzer.java                       [接口]
│   │   ├── WordCard.java                               record
│   │   ├── GenerationException.java
│   │   ├── RetryPolicy.java                            退避 + 可重试判定
│   │   ├── JsonExtractor.java                          容错 JSON 解析
│   │   ├── openai/
│   │   │   ├── OpenAiCompatibleClient.java             共享 HTTP 调用
│   │   │   ├── OpenAiCompatibleWordCardGenerator.java
│   │   │   └── OpenAiCompatibleSentenceAnalyzer.java
│   │   ├── claude/
│   │   │   ├── ClaudeClient.java
│   │   │   ├── ClaudeWordCardGenerator.java
│   │   │   └── ClaudeSentenceAnalyzer.java
│   │   └── noop/
│   │       ├── NoopWordCardGenerator.java
│   │       └── NoopSentenceAnalyzer.java
│   └── web/
│       ├── CollectController.java                      收录 / 取消 / 列表
│       ├── WordBankController.java                     详情查询
│       └── dto/
│           ├── CollectWordRequest.java
│           ├── CollectSentenceRequest.java
│           ├── DeleteCollectRequest.java
│           ├── WordView.java
│           ├── SentenceView.java
│           └── PageResult.java
└── src/test/java/com/bud/wordlist/
    ├── support/
    │   ├── MySqlTestBase.java                          Testcontainers 基类
    │   ├── FakeWordCardGenerator.java                  可控测试替身
    │   └── FakeSentenceAnalyzer.java
    ├── common/LevelTest.java
    ├── service/WordNormalizerTest.java
    ├── service/CopyResolverTest.java
    ├── service/WordCollectServiceTest.java
    ├── service/GenerationCompensatorTest.java
    ├── repository/WordRepositoryTest.java
    ├── repository/UserWordRepositoryTest.java
    ├── generation/RetryPolicyTest.java
    ├── generation/JsonExtractorTest.java
    ├── generation/OpenAiCompatibleGeneratorTest.java
    ├── config/LlmConfigTest.java
    └── web/CollectControllerTest.java
```

**为什么按职责而非按层拆 repository**：`WordRepository` 同时服务 4 张词表，把「按级别选表」的逻辑收在一个类里，避免 4 个类各自实现一遍表名映射。服务层只调用 `wordRepository.findByWord(level, word)`，不关心表名。

---

## Task 1: 依赖、配置骨架与建表脚本

**Files:**
- Modify: `pom.xml`
- Create: `src/main/resources/db/migration/V1__init.sql`
- Create: `src/main/resources/application.yml`（覆盖现有的）
- Create: `src/main/resources/application-prod.yml`
- Create: `src/main/java/com/bud/wordlist/config/WordlistProperties.java`
- Test: `src/test/java/com/bud/wordlist/support/MySqlTestBase.java`
- Test: `src/test/java/com/bud/wordlist/repository/SchemaSmokeTest.java`

**Interfaces:**
- Consumes: 无（首个任务）
- Produces:
  - `WordlistProperties` record，绑定 `wordlist.*` 配置树，供 Task 6/8/10/12 使用
  - `MySqlTestBase` 抽象基类，供后续所有需要数据库的测试继承

- [ ] **Step 1: 在 `pom.xml` 的 `<dependencies>` 中追加依赖**

在现有 `spring-boot-starter-webmvc` 之后插入：

```xml
<!-- 数据访问：Spring 自带的 JdbcClient -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>

<!-- 请求参数校验 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>

<!-- MySQL 驱动 -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- 数据库版本化迁移 -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>

<!-- Claude 原生 SDK（provider=claude 时使用） -->
<dependency>
    <groupId>com.anthropic</groupId>
    <artifactId>anthropic-java</artifactId>
    <version>2.34.0</version>
</dependency>

<!-- 测试 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock-standalone</artifactId>
    <version>3.9.1</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: 写建表脚本 `src/main/resources/db/migration/V1__init.sql`**

10 张表，字段与约束严格照抄 spec §4。**注意 `word` 列必须显式 `COLLATE utf8mb4_bin`**——默认排序规则重音不敏感，会把 `cafe` 和 `café` 判成同一个词。

```sql
-- ============ KET 词表 ============
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
  retry_count TINYINT      NOT NULL DEFAULT 0 COMMENT 'AI 生成已投递次数（含首次）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_word (word),
  KEY idx_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='KET 单词表';

-- ============ PET 词表 ============
CREATE TABLE pet_word (
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
  retry_count TINYINT      NOT NULL DEFAULT 0 COMMENT 'AI 生成已投递次数（含首次）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_word (word),
  KEY idx_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='PET 单词表';

-- ============ 中考词表 ============
CREATE TABLE zhongkao_word (
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
  retry_count TINYINT      NOT NULL DEFAULT 0 COMMENT 'AI 生成已投递次数（含首次）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_word (word),
  KEY idx_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='中考 单词表';

-- ============ 雅思词表 ============
CREATE TABLE ielts_word (
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
  retry_count TINYINT      NOT NULL DEFAULT 0 COMMENT 'AI 生成已投递次数（含首次）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_word (word),
  KEY idx_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='雅思 单词表';

-- ============ 句表（4 张）============
CREATE TABLE ket_sentence (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '句子唯一id',
  hash        CHAR(64)     NOT NULL COLLATE utf8mb4_bin COMMENT '规范化原句的 SHA-256（小写 hex）',
  sentence    TEXT         NOT NULL COMMENT '句子原句（原样保存）',
  analysis    TEXT         NULL COMMENT '句子分析',
  status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=生成中 1=已完成 2=生成失败',
  source      TINYINT      NOT NULL DEFAULT 1 COMMENT '1=AI生成 2=跨级别拷贝 3=人工录入',
  retry_count TINYINT      NOT NULL DEFAULT 0 COMMENT 'AI 生成已投递次数（含首次）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_hash (hash),
  KEY idx_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='KET 句子表';

CREATE TABLE pet_sentence (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '句子唯一id',
  hash        CHAR(64)     NOT NULL COLLATE utf8mb4_bin COMMENT '规范化原句的 SHA-256（小写 hex）',
  sentence    TEXT         NOT NULL COMMENT '句子原句（原样保存）',
  analysis    TEXT         NULL COMMENT '句子分析',
  status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=生成中 1=已完成 2=生成失败',
  source      TINYINT      NOT NULL DEFAULT 1 COMMENT '1=AI生成 2=跨级别拷贝 3=人工录入',
  retry_count TINYINT      NOT NULL DEFAULT 0 COMMENT 'AI 生成已投递次数（含首次）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_hash (hash),
  KEY idx_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='PET 句子表';

CREATE TABLE zhongkao_sentence (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '句子唯一id',
  hash        CHAR(64)     NOT NULL COLLATE utf8mb4_bin COMMENT '规范化原句的 SHA-256（小写 hex）',
  sentence    TEXT         NOT NULL COMMENT '句子原句（原样保存）',
  analysis    TEXT         NULL COMMENT '句子分析',
  status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=生成中 1=已完成 2=生成失败',
  source      TINYINT      NOT NULL DEFAULT 1 COMMENT '1=AI生成 2=跨级别拷贝 3=人工录入',
  retry_count TINYINT      NOT NULL DEFAULT 0 COMMENT 'AI 生成已投递次数（含首次）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_hash (hash),
  KEY idx_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='中考 句子表';

CREATE TABLE ielts_sentence (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '句子唯一id',
  hash        CHAR(64)     NOT NULL COLLATE utf8mb4_bin COMMENT '规范化原句的 SHA-256（小写 hex）',
  sentence    TEXT         NOT NULL COMMENT '句子原句（原样保存）',
  analysis    TEXT         NULL COMMENT '句子分析',
  status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=生成中 1=已完成 2=生成失败',
  source      TINYINT      NOT NULL DEFAULT 1 COMMENT '1=AI生成 2=跨级别拷贝 3=人工录入',
  retry_count TINYINT      NOT NULL DEFAULT 0 COMMENT 'AI 生成已投递次数（含首次）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_hash (hash),
  KEY idx_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='雅思 句子表';

-- ============ 用户关联表（2 张）============
CREATE TABLE user_word (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id      BIGINT UNSIGNED NOT NULL COMMENT '用户id（上游传入，不建外键）',
  level        VARCHAR(16)     NOT NULL COLLATE utf8mb4_bin COMMENT 'ket/pet/zhongkao/ielts',
  word_id      BIGINT UNSIGNED NOT NULL COMMENT '对应级别词表的主键',
  source_type  TINYINT         NULL COMMENT '来源类型：1=阅读 2=完形 3=听力 4=写作 5=其他',
  source_id    BIGINT UNSIGNED NULL COMMENT '来源考题id',
  source_title VARCHAR(255)    NULL COMMENT '来源考题标题快照',
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

- [ ] **Step 3: 写 `application.yml`**（覆盖现有文件）

```yaml
spring:
  application:
    name: wordlist
  datasource:
    url: jdbc:mysql://localhost:3306/wordlist?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: ${DB_USER:root}
    password: ${DB_PASSWORD:}
  flyway:
    enabled: true
    baseline-on-migrate: true

wordlist:
  copy:
    enabled: true
    chain:
      ket: [pet]
      pet: [zhongkao]
  llm:
    provider: none
    model: deepseek-chat
    base-url: https://api.deepseek.com
    api-key: ${LLM_API_KEY:}
    timeout-seconds: 30
    max-attempts: 3
  async:
    core-pool-size: 4
    max-pool-size: 8
    queue-capacity: 500
    compensate-enabled: false
    compensate-cron: "0 */5 * * * ?"
    stale-threshold-seconds: 120
```

- [ ] **Step 4: 写 `application-prod.yml`**

```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USER}
    password: ${DB_PASSWORD}

wordlist:
  llm:
    api-key: ${LLM_API_KEY}
  async:
    compensate-enabled: true
```

- [ ] **Step 5: 写 `WordlistProperties`**

```java
package com.bud.wordlist.config;

import com.bud.wordlist.common.Level;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "wordlist")
public record WordlistProperties(Copy copy, Llm llm, Async async) {

    public record Copy(boolean enabled, Map<Level, List<Level>> chain) {
        public Copy {
            chain = chain == null ? Map.of() : Map.copyOf(chain);
        }
    }

    public record Llm(String provider,
                      String model,
                      String baseUrl,
                      String apiKey,
                      int timeoutSeconds,
                      int maxAttempts) {}

    public record Async(int corePoolSize,
                        int maxPoolSize,
                        int queueCapacity,
                        boolean compensateEnabled,
                        String compensateCron,
                        int staleThresholdSeconds) {}
}
```

在 `WordlistApplication` 上加 `@ConfigurationPropertiesScan`：

```java
package com.bud.wordlist;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class WordlistApplication {
    public static void main(String[] args) {
        SpringApplication.run(WordlistApplication.class, args);
    }
}
```

> `Level` 枚举在 Task 2 才创建。为了让本步骤能编译，**先创建 Task 2 的 `Level.java`（Step 1）再回到这里**；或者本步骤先临时用 `Map<String, List<String>>`，Task 2 时改回来。推荐前者。

- [ ] **Step 6: 写测试基类 `MySqlTestBase`**

```java
package com.bud.wordlist.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
public abstract class MySqlTestBase {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("wordlist")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
```

- [ ] **Step 7: 写冒烟测试 `SchemaSmokeTest`**

```java
package com.bud.wordlist.repository;

import com.bud.wordlist.support.MySqlTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaSmokeTest extends MySqlTestBase {

    private static final List<String> EXPECTED_TABLES = List.of(
            "ket_word", "pet_word", "zhongkao_word", "ielts_word",
            "ket_sentence", "pet_sentence", "zhongkao_sentence", "ielts_sentence",
            "user_word", "user_sentence");

    @Autowired
    JdbcClient jdbc;

    @Test
    void flyway_creates_all_ten_tables() {
        List<String> actual = jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                         WHERE table_schema = DATABASE()
                        """)
                .query(String.class)
                .list();

        assertThat(actual).containsAll(EXPECTED_TABLES);
    }

    @Test
    void word_column_is_accent_sensitive() {
        jdbc.sql("INSERT INTO ket_word (word, status, retry_count) VALUES ('cafe', 0, 1)").update();

        // 若排序规则是默认的 utf8mb4_0900_ai_ci（重音不敏感），这条插入会被误判为重复
        jdbc.sql("INSERT INTO ket_word (word, status, retry_count) VALUES ('café', 0, 1)").update();

        Long count = jdbc.sql("SELECT COUNT(*) FROM ket_word WHERE word IN ('cafe', 'café')")
                .query(Long.class).single();
        assertThat(count).isEqualTo(2L);
    }
}
```

- [ ] **Step 8: 运行测试**

Run: `./mvnw test -Dtest=SchemaSmokeTest`
Expected: PASS，2 个测试全绿。首次运行会拉取 mysql:8.0 镜像，耗时较长。

- [ ] **Step 9: 提交**

```bash
git add pom.xml src/main/resources src/main/java src/test/java
git commit -m "feat: 项目依赖、Flyway 建表脚本与配置骨架"
```

---

## Task 2: Level 枚举与文本规范化

**Files:**
- Create: `src/main/java/com/bud/wordlist/common/Level.java`
- Create: `src/main/java/com/bud/wordlist/common/GenStatus.java`
- Create: `src/main/java/com/bud/wordlist/common/ContentSource.java`
- Create: `src/main/java/com/bud/wordlist/service/WordNormalizer.java`
- Test: `src/test/java/com/bud/wordlist/common/LevelTest.java`
- Test: `src/test/java/com/bud/wordlist/service/WordNormalizerTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `Level.fromCode(String) : Optional<Level>`、`Level.code()`、`Level.wordTable()`、`Level.sentenceTable()`
  - `GenStatus.GENERATING/DONE/FAILED` + `GenStatus.code()`、`GenStatus.fromCode(int)`
  - `ContentSource.AI/COPY/MANUAL` + `ContentSource.code()`
  - `WordNormalizer.word(String) : String`、`.sentence(String) : String`、`.sha256Hex(String) : String`、`.isValidWord(String) : boolean`

- [ ] **Step 1: 写失败测试 `LevelTest`**

```java
package com.bud.wordlist.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LevelTest {

    @Test
    void maps_level_to_physical_table_names() {
        assertThat(Level.KET.wordTable()).isEqualTo("ket_word");
        assertThat(Level.KET.sentenceTable()).isEqualTo("ket_sentence");
        assertThat(Level.IELTS.wordTable()).isEqualTo("ielts_word");
    }

    @Test
    void fromCode_is_case_insensitive_and_trims() {
        assertThat(Level.fromCode("ket")).contains(Level.KET);
        assertThat(Level.fromCode("KET")).contains(Level.KET);
        assertThat(Level.fromCode("  Ket  ")).contains(Level.KET);
    }

    @Test
    void fromCode_rejects_unknown_and_null() {
        assertThat(Level.fromCode("ketx")).isEmpty();
        assertThat(Level.fromCode(null)).isEmpty();
        assertThat(Level.fromCode("")).isEmpty();
        // 验证白名单不会退化成前缀匹配
        assertThat(Level.fromCode("zhongkao_word")).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=LevelTest`
Expected: 编译失败，`cannot find symbol: class Level`

- [ ] **Step 3: 实现 `Level`**

```java
package com.bud.wordlist.common;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 级别枚举。表名唯一来源，任何 SQL 的表名都必须取自这里，
 * 禁止把请求参数直接拼进 SQL。
 */
public enum Level {

    KET("ket", "ket_word", "ket_sentence"),
    PET("pet", "pet_word", "pet_sentence"),
    ZHONGKAO("zhongkao", "zhongkao_word", "zhongkao_sentence"),
    IELTS("ielts", "ielts_word", "ielts_sentence");

    private final String code;
    private final String wordTable;
    private final String sentenceTable;

    Level(String code, String wordTable, String sentenceTable) {
        this.code = code;
        this.wordTable = wordTable;
        this.sentenceTable = sentenceTable;
    }

    public String code() {
        return code;
    }

    public String wordTable() {
        return wordTable;
    }

    public String sentenceTable() {
        return sentenceTable;
    }

    private static final Map<String, Level> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Level::code, Function.identity()));

    /** 精确匹配（大小写不敏感、去首尾空白）；非白名单值返回 empty，调用方转 400 */
    public static Optional<Level> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_CODE.get(code.trim().toLowerCase(Locale.ROOT)));
    }
}
```

同时创建 `GenStatus` 与 `ContentSource`（本步骤一并完成，它们只是常量容器）：

```java
package com.bud.wordlist.common;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum GenStatus {
    GENERATING(0), DONE(1), FAILED(2);

    private final int code;

    GenStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    private static final Map<Integer, GenStatus> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(GenStatus::code, Function.identity()));

    public static Optional<GenStatus> fromCode(int code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }
}
```

```java
package com.bud.wordlist.common;

public enum ContentSource {
    AI(1), COPY(2), MANUAL(3);

    private final int code;

    ContentSource(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw test -Dtest=LevelTest`
Expected: PASS

- [ ] **Step 5: 写失败测试 `WordNormalizerTest`**

覆盖 Review Focus 第 1、2 条：非法字符与超长单词。

```java
package com.bud.wordlist.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WordNormalizerTest {

    @Test
    void word_trims_and_lowercases() {
        assertThat(WordNormalizer.word("  Hello  ")).isEqualTo("hello");
        assertThat(WordNormalizer.word("WORLD")).isEqualTo("world");
    }

    @Test
    void word_is_null_safe() {
        assertThat(WordNormalizer.word(null)).isNull();
    }

    @Test
    void sentence_collapses_whitespace_and_lowercases_for_hashing() {
        assertThat(WordNormalizer.sentence("  He  has\n been   here.  "))
                .isEqualTo("he has been here.");
    }

    @Test
    void sha256_is_stable_and_hex_encoded() {
        String hash = WordNormalizer.sha256Hex("hello");
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(hash).isEqualTo(WordNormalizer.sha256Hex("hello"));
        // 已知值，防止实现被悄悄改坏
        assertThat(hash).isEqualTo(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void isValidWord_accepts_normal_english_words() {
        assertThat(WordNormalizer.isValidWord("hello")).isTrue();
        assertThat(WordNormalizer.isValidWord("don't")).isTrue();
        assertThat(WordNormalizer.isValidWord("well-known")).isTrue();
        assertThat(WordNormalizer.isValidWord("look after")).isTrue();
    }

    @Test
    void isValidWord_rejects_garbage() {
        assertThat(WordNormalizer.isValidWord(null)).isFalse();
        assertThat(WordNormalizer.isValidWord("")).isFalse();
        assertThat(WordNormalizer.isValidWord("3d")).isFalse();          // 数字开头
        assertThat(WordNormalizer.isValidWord("e.g.")).isFalse();        // 含点号
        assertThat(WordNormalizer.isValidWord("hi\nthere")).isFalse();   // 换行
        assertThat(WordNormalizer.isValidWord("café")).isFalse();        // 非 ASCII
        assertThat(WordNormalizer.isValidWord("😀")).isFalse();
        assertThat(WordNormalizer.isValidWord("a".repeat(65))).isFalse(); // 超长
        assertThat(WordNormalizer.isValidWord("a".repeat(64))).isTrue();  // 边界内
    }
}
```

- [ ] **Step 6: 运行测试确认失败**

Run: `./mvnw test -Dtest=WordNormalizerTest`
Expected: 编译失败，`cannot find symbol: class WordNormalizer`

- [ ] **Step 7: 实现 `WordNormalizer`**

```java
package com.bud.wordlist.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 单词与句子的规范化。查重与存储一律使用规范化后的值；
 * 句子仅 hash 用规范化值，原文原样保存。
 */
public final class WordNormalizer {

    /** 允许：字母开头，后续为字母、连字符、撇号、空格 */
    private static final Pattern WORD_PATTERN = Pattern.compile("[a-z][a-z\\-' ]*");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int MAX_WORD_LENGTH = 64;

    private WordNormalizer() {
    }

    /** 单词：去首尾空白 + 转小写 */
    public static String word(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** 句子：去首尾空白 + 连续空白折叠为单个空格 + 转小写。仅用于算 hash */
    public static String sentence(String raw) {
        if (raw == null) {
            return null;
        }
        return WHITESPACE.matcher(raw.trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    /** 小写 hex 编码的 SHA-256 */
    public static String sha256Hex(String normalized) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 规范化必须是幂等的：isValidWord(normalize(x)) 才是有效的校验顺序 */
    public static boolean isValidWord(String normalized) {
        return normalized != null
                && !normalized.isEmpty()
                && normalized.length() <= MAX_WORD_LENGTH
                && WORD_PATTERN.matcher(normalized).matches();
    }
}
```

- [ ] **Step 8: 运行测试确认通过**

Run: `./mvnw test -Dtest='LevelTest,WordNormalizerTest'`
Expected: PASS，全部用例通过

- [ ] **Step 9: 提交**

```bash
git add src/main/java/com/bud/wordlist/common src/main/java/com/bud/wordlist/service/WordNormalizer.java src/test/java/com/bud/wordlist/common src/test/java/com/bud/wordlist/service/WordNormalizerTest.java
git commit -m "feat: Level 枚举白名单映射与文本规范化"
```

---

## Task 3: 统一响应与全局异常处理

**Files:**
- Create: `src/main/java/com/bud/wordlist/common/ApiResponse.java`
- Create: `src/main/java/com/bud/wordlist/common/ErrorCode.java`
- Create: `src/main/java/com/bud/wordlist/common/BizException.java`
- Create: `src/main/java/com/bud/wordlist/common/GlobalExceptionHandler.java`
- Test: `src/test/java/com/bud/wordlist/common/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `ApiResponse.ok(T) : ApiResponse<T>`、`ApiResponse.fail(int, String) : ApiResponse<Void>`
  - `ErrorCode.INVALID_LEVEL / INVALID_WORD / INVALID_USER / INVALID_PARAM / NOT_FOUND / INTERNAL`
  - `BizException(ErrorCode)`、`BizException(ErrorCode, String)`

- [ ] **Step 1: 写失败测试 `GlobalExceptionHandlerTest`**

用 `@WebMvcTest` 挂一个只用于测试的控制器来验证异常转换。该控制器是测试专用，不进主源码。

```java
package com.bud.wordlist.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ProbeController.class)
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerTest {

    @RestController
    static class ProbeController {
        @GetMapping("/probe/biz")
        String biz() {
            throw new BizException(ErrorCode.INVALID_LEVEL, "级别不合法");
        }

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException("意外错误");
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void biz_exception_becomes_400_with_business_code() throws Exception {
        mockMvc.perform(get("/probe/biz").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_LEVEL))
                .andExpect(jsonPath("$.msg").value("级别不合法"));
    }

    @Test
    void unexpected_exception_becomes_500_without_leaking_details() throws Exception {
        mockMvc.perform(get("/probe/boom").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL))
                .andExpect(jsonPath("$.msg").value("服务器内部错误"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=GlobalExceptionHandlerTest`
Expected: 编译失败，`cannot find symbol: class BizException`

- [ ] **Step 3: 实现错误码、异常与响应包装**

```java
package com.bud.wordlist.common;

/** 业务错误码。0 为成功，其余为失败 */
public final class ErrorCode {
    public static final int OK = 0;
    public static final int INVALID_LEVEL = 1001;
    public static final int INVALID_WORD = 1002;
    public static final int INVALID_SENTENCE = 1003;
    public static final int INVALID_USER = 1004;
    public static final int INVALID_PARAM = 1005;
    public static final int NOT_FOUND = 1006;
    public static final int INTERNAL = 9999;

    private ErrorCode() {
    }
}
```

```java
package com.bud.wordlist.common;

/** 业务异常。由 GlobalExceptionHandler 统一转成 400 + 业务码 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
```

```java
package com.bud.wordlist.common;

public record ApiResponse<T>(int code, String msg, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK, "ok", data);
    }

    public static <T> ApiResponse<T> fail(int code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }
}
```

```java
package com.bud.wordlist.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        log.warn("业务异常 code={} msg={}", e.code(), e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .orElse("参数不合法");
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.INVALID_PARAM, msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        // 完整堆栈只进日志，绝不返回给客户端
        log.error("未预期的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL, "服务器内部错误"));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw test -Dtest=GlobalExceptionHandlerTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bud/wordlist/common src/test/java/com/bud/wordlist/common
git commit -m "feat: 统一响应包装、业务错误码与全局异常处理"
```

---

## Task 4: 词表与句表的数据访问层

**Files:**
- Create: `src/main/java/com/bud/wordlist/domain/Word.java`
- Create: `src/main/java/com/bud/wordlist/domain/Sentence.java`
- Create: `src/main/java/com/bud/wordlist/repository/WordRepository.java`
- Create: `src/main/java/com/bud/wordlist/repository/SentenceRepository.java`
- Test: `src/test/java/com/bud/wordlist/repository/WordRepositoryTest.java`

**Interfaces:**
- Consumes: `Level`（Task 2）、`GenStatus` / `ContentSource`（Task 2）、`MySqlTestBase`（Task 1）
- Produces:
  - `record Word(Long id, String word, String meaning, String phonetic, String pos, String mnemonic, String example, String phrase, int status, int source, int retryCount)`
  - `WordRepository.findByWord(Level, String) : Optional<Word>`
  - `WordRepository.insertWord(Level, String) : long`（返回新 id；重复时抛 `DuplicateKeyException`）
  - `WordRepository.insertCopiedWord(Level, String, Word source) : long`
  - `WordRepository.updateContentIfGenerating(Level, long, WordCard) : int`
  - `WordRepository.markFailed(Level, long) : int`
  - `WordRepository.resetToGeneratingIfRetryable(Level, long, int maxAttempts) : int`
  - `WordRepository.findById(Level, long) : Optional<Word>`
  - `WordRepository.findStaleOrFailed(Level, int staleSeconds, int maxAttempts) : List<Word>`
  - 同构的 `SentenceRepository`，字段为 `(id, hash, sentence, analysis, status, source, retryCount)`

- [ ] **Step 1: 写失败测试 `WordRepositoryTest`**

覆盖 Review Focus 第 6 条：跨级别同名不同表不得串表。

```java
package com.bud.wordlist.repository;

import com.bud.wordlist.common.ContentSource;
import com.bud.wordlist.common.GenStatus;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.domain.Word;
import com.bud.wordlist.generation.WordCard;
import com.bud.wordlist.support.MySqlTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WordRepositoryTest extends MySqlTestBase {

    @Autowired
    WordRepository repo;

    @BeforeEach
    void clean() {
        for (Level level : Level.values()) {
            repo.deleteAllForTest(level);
        }
    }

    @Test
    void insert_then_find_round_trips() {
        long id = repo.insertWord(Level.KET, "hello");

        Word found = repo.findByWord(Level.KET, "hello").orElseThrow();
        assertThat(found.id()).isEqualTo(id);
        assertThat(found.status()).isEqualTo(GenStatus.GENERATING.code());
        assertThat(found.source()).isEqualTo(ContentSource.AI.code());
        assertThat(found.retryCount()).isEqualTo(1);
        assertThat(found.meaning()).isNull();
    }

    @Test
    void duplicate_word_in_same_level_throws_duplicate_key() {
        repo.insertWord(Level.KET, "hello");

        assertThatThrownBy(() -> repo.insertWord(Level.KET, "hello"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    /** Review Focus 第 6 条：同名不同级别必须落在各自的表里 */
    @Test
    void same_word_in_different_levels_lands_in_separate_tables() {
        long ketId = repo.insertWord(Level.KET, "hello");
        long petId = repo.insertWord(Level.PET, "hello");

        assertThat(ketId).isNotEqualTo(petId);
        assertThat(repo.findByWord(Level.KET, "hello")).isPresent();
        assertThat(repo.findByWord(Level.PET, "hello")).isPresent();
        // 在 KET 表里查 PET 的记录必须查不到
        assertThat(repo.findById(Level.KET, petId)).isEmpty();
        assertThat(repo.findById(Level.PET, ketId)).isEmpty();
    }

    @Test
    void update_content_writes_all_six_fields_and_marks_done() {
        long id = repo.insertWord(Level.KET, "hello");
        WordCard card = new WordCard("int. 你好", "/həˈləʊ/", "int.", "谐音哈喽",
                "Hello, how are you?", "say hello to sb.");

        int affected = repo.updateContentIfGenerating(Level.KET, id, card);

        assertThat(affected).isEqualTo(1);
        Word updated = repo.findById(Level.KET, id).orElseThrow();
        assertThat(updated.status()).isEqualTo(GenStatus.DONE.code());
        assertThat(updated.meaning()).isEqualTo("int. 你好");
        assertThat(updated.phonetic()).isEqualTo("/həˈləʊ/");
        assertThat(updated.pos()).isEqualTo("int.");
        assertThat(updated.mnemonic()).isEqualTo("谐音哈喽");
        assertThat(updated.example()).isEqualTo("Hello, how are you?");
        assertThat(updated.phrase()).isEqualTo("say hello to sb.");
    }

    /** 写回幂等：第二次写回必须返回 0，不得覆盖第一次的结果 */
    @Test
    void update_content_is_idempotent() {
        long id = repo.insertWord(Level.KET, "hello");
        repo.updateContentIfGenerating(Level.KET, id, new WordCard("第一次", null, null, null, null, null));

        int second = repo.updateContentIfGenerating(Level.KET, id,
                new WordCard("第二次", null, null, null, null, null));

        assertThat(second).isZero();
        assertThat(repo.findById(Level.KET, id).orElseThrow().meaning()).isEqualTo("第一次");
    }

    @Test
    void failed_record_is_reset_to_generating_and_counter_increments() {
        long id = repo.insertWord(Level.KET, "hello");
        repo.markFailed(Level.KET, id);

        int affected = repo.resetToGeneratingIfRetryable(Level.KET, id, 3);

        assertThat(affected).isEqualTo(1);
        Word reset = repo.findById(Level.KET, id).orElseThrow();
        assertThat(reset.status()).isEqualTo(GenStatus.GENERATING.code());
        assertThat(reset.retryCount()).isEqualTo(2);
    }

    @Test
    void reset_is_refused_once_max_attempts_reached() {
        long id = repo.insertWord(Level.KET, "hello");
        repo.markFailed(Level.KET, id);
        repo.resetToGeneratingIfRetryable(Level.KET, id, 3);  // retryCount -> 2
        repo.markFailed(Level.KET, id);
        repo.resetToGeneratingIfRetryable(Level.KET, id, 3);  // retryCount -> 3
        repo.markFailed(Level.KET, id);

        assertThat(repo.resetToGeneratingIfRetryable(Level.KET, id, 3)).isZero();
    }

    @Test
    void insert_copied_word_marks_source_as_copy_and_done() {
        long petId = repo.insertWord(Level.PET, "hello");
        repo.updateContentIfGenerating(Level.PET, petId,
                new WordCard("int. 你好", "/həˈləʊ/", "int.", "谐音", "Hello!", "say hello"));
        Word petWord = repo.findById(Level.PET, petId).orElseThrow();

        long ketId = repo.insertCopiedWord(Level.KET, "hello", petWord);

        Word ketWord = repo.findById(Level.KET, ketId).orElseThrow();
        assertThat(ketWord.status()).isEqualTo(GenStatus.DONE.code());
        assertThat(ketWord.source()).isEqualTo(ContentSource.COPY.code());
        assertThat(ketWord.meaning()).isEqualTo("int. 你好");
        assertThat(ketWord.retryCount()).isZero();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=WordRepositoryTest`
Expected: 编译失败，`cannot find symbol: class WordRepository`

- [ ] **Step 3: 实现 `Word` 与 `Sentence` record**

```java
package com.bud.wordlist.domain;

public record Word(
        Long id,
        String word,
        String meaning,
        String phonetic,
        String pos,
        String mnemonic,
        String example,
        String phrase,
        int status,
        int source,
        int retryCount) {
}
```

```java
package com.bud.wordlist.domain;

public record Sentence(
        Long id,
        String hash,
        String sentence,
        String analysis,
        int status,
        int source,
        int retryCount) {
}
```

- [ ] **Step 4: 实现 `WordRepository`**

```java
package com.bud.wordlist.repository;

import com.bud.wordlist.common.ContentSource;
import com.bud.wordlist.common.GenStatus;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.domain.Word;
import com.bud.wordlist.generation.WordCard;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 4 张同构词表的全部读写。表名一律来自 Level 枚举，不接受外部字符串。
 */
@Repository
public class WordRepository {

    private static final String COLUMNS =
            "id, word, meaning, phonetic, pos, mnemonic, example, phrase, status, source, retry_count";

    private static final RowMapper<Word> MAPPER = (rs, i) -> new Word(
            rs.getLong("id"),
            rs.getString("word"),
            rs.getString("meaning"),
            rs.getString("phonetic"),
            rs.getString("pos"),
            rs.getString("mnemonic"),
            rs.getString("example"),
            rs.getString("phrase"),
            rs.getInt("status"),
            rs.getInt("source"),
            rs.getInt("retry_count"));

    private final JdbcClient jdbc;

    public WordRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Word> findByWord(Level level, String word) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + level.wordTable() + " WHERE word = :word")
                .param("word", word)
                .query(MAPPER)
                .optional();
    }

    public Optional<Word> findById(Level level, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + level.wordTable() + " WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /** 收录新词：status=生成中、source=AI、retry_count=1（本次即第 1 次投递）。重复时抛 DuplicateKeyException */
    public long insertWord(Level level, String word) {
        Long id = jdbc.sql("""
                        INSERT INTO %s (word, status, source, retry_count)
                        VALUES (:word, :status, :source, 1)
                        """.formatted(level.wordTable()))
                .param("word", word)
                .param("status", GenStatus.GENERATING.code())
                .param("source", ContentSource.AI.code())
                .updateAndReturnGeneratedKey(Long.class);
        if (id == null) {
            throw new IllegalStateException("插入 " + level.wordTable() + " 未返回主键");
        }
        return id;
    }

    /** 跨级别拷贝：把来源词的 6 个内容字段整行复制过来，直接标为已完成 */
    public long insertCopiedWord(Level level, String word, Word source) {
        Long id = jdbc.sql("""
                        INSERT INTO %s (word, meaning, phonetic, pos, mnemonic, example, phrase,
                                        status, source, retry_count)
                        VALUES (:word, :meaning, :phonetic, :pos, :mnemonic, :example, :phrase,
                                :status, :source, 0)
                        """.formatted(level.wordTable()))
                .param("word", word)
                .param("meaning", source.meaning())
                .param("phonetic", source.phonetic())
                .param("pos", source.pos())
                .param("mnemonic", source.mnemonic())
                .param("example", source.example())
                .param("phrase", source.phrase())
                .param("status", GenStatus.DONE.code())
                .param("source", ContentSource.COPY.code())
                .updateAndReturnGeneratedKey(Long.class);
        if (id == null) {
            throw new IllegalStateException("插入 " + level.wordTable() + " 未返回主键");
        }
        return id;
    }

    /**
     * 生成成功后的写回。AND status = 生成中 是幂等守卫：
     * 并发的第二次写回会命中 0 行，从而丢弃结果，不覆盖先到的那次。
     */
    public int updateContentIfGenerating(Level level, long id, WordCard card) {
        return jdbc.sql("""
                        UPDATE %s
                           SET meaning = :meaning, phonetic = :phonetic, pos = :pos,
                               mnemonic = :mnemonic, example = :example, phrase = :phrase,
                               status = :done
                         WHERE id = :id AND status = :generating
                        """.formatted(level.wordTable()))
                .param("meaning", card.meaning())
                .param("phonetic", card.phonetic())
                .param("pos", card.pos())
                .param("mnemonic", card.mnemonic())
                .param("example", card.example())
                .param("phrase", card.phrase())
                .param("done", GenStatus.DONE.code())
                .param("generating", GenStatus.GENERATING.code())
                .param("id", id)
                .update();
    }

    public int markFailed(Level level, long id) {
        return jdbc.sql("""
                        UPDATE %s SET status = :failed
                         WHERE id = :id AND status = :generating
                        """.formatted(level.wordTable()))
                .param("failed", GenStatus.FAILED.code())
                .param("generating", GenStatus.GENERATING.code())
                .param("id", id)
                .update();
    }

    /**
     * 补偿任务的投递闸门：原子地把记录翻回生成中并递增投递计数。
     * 返回 1 才允许投递任务；返回 0 说明已达上限或被并发抢占。
     */
    public int resetToGeneratingIfRetryable(Level level, long id, int maxAttempts) {
        return jdbc.sql("""
                        UPDATE %s
                           SET status = :generating, retry_count = retry_count + 1
                         WHERE id = :id
                           AND status IN (:generating, :failed)
                           AND retry_count < :maxAttempts
                        """.formatted(level.wordTable()))
                .param("generating", GenStatus.GENERATING.code())
                .param("failed", GenStatus.FAILED.code())
                .param("id", id)
                .param("maxAttempts", maxAttempts)
                .update();
    }

    /** 补偿任务扫描用：待重试（生成失败）的记录 */
    public List<Word> findFailed(Level level, int maxAttempts) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + level.wordTable()
                        + " WHERE status = :failed AND retry_count < :maxAttempts")
                .param("failed", GenStatus.FAILED.code())
                .param("maxAttempts", maxAttempts)
                .query(MAPPER)
                .list();
    }

    /**
     * 补偿任务扫描用：疑似任务丢失（长时间停在生成中）的记录。
     * 用 updated_at 而非 created_at——记录被重新投递时 updated_at 会刷新，
     * 因此不会在下一轮扫描被重复捞起。
     *
     * 截断时间在 Java 侧算好再传参，不用 SQL 里的 INTERVAL 表达式：
     * 占位符放进 INTERVAL 里各家驱动行为不一致，算在 Java 侧最稳。
     * 前提是应用与 MySQL 会话时区一致（JDBC URL 里的 serverTimezone 已固定）。
     */
    public List<Word> findStale(Level level, int staleSeconds) {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(staleSeconds);
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + level.wordTable()
                        + " WHERE status = :generating AND updated_at < :cutoff")
                .param("generating", GenStatus.GENERATING.code())
                .param("cutoff", cutoff)
                .query(MAPPER)
                .list();
    }

    /** 仅测试用：清空某级别的词表 */
    public void deleteAllForTest(Level level) {
        jdbc.sql("DELETE FROM " + level.wordTable()).update();
    }
}
```

- [ ] **Step 5: 实现 `SentenceRepository`**

与 `WordRepository` 同构，差别仅在列名与字段：

```java
package com.bud.wordlist.repository;

import com.bud.wordlist.common.ContentSource;
import com.bud.wordlist.common.GenStatus;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.domain.Sentence;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class SentenceRepository {

    private static final String COLUMNS = "id, hash, sentence, analysis, status, source, retry_count";

    private static final RowMapper<Sentence> MAPPER = (rs, i) -> new Sentence(
            rs.getLong("id"),
            rs.getString("hash"),
            rs.getString("sentence"),
            rs.getString("analysis"),
            rs.getInt("status"),
            rs.getInt("source"),
            rs.getInt("retry_count"));

    private final JdbcClient jdbc;

    public SentenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Sentence> findByHash(Level level, String hash) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + level.sentenceTable() + " WHERE hash = :hash")
                .param("hash", hash)
                .query(MAPPER)
                .optional();
    }

    public Optional<Sentence> findById(Level level, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + level.sentenceTable() + " WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public long insertSentence(Level level, String hash, String sentence) {
        Long id = jdbc.sql("""
                        INSERT INTO %s (hash, sentence, status, source, retry_count)
                        VALUES (:hash, :sentence, :status, :source, 1)
                        """.formatted(level.sentenceTable()))
                .param("hash", hash)
                .param("sentence", sentence)
                .param("status", GenStatus.GENERATING.code())
                .param("source", ContentSource.AI.code())
                .updateAndReturnGeneratedKey(Long.class);
        if (id == null) {
            throw new IllegalStateException("插入 " + level.sentenceTable() + " 未返回主键");
        }
        return id;
    }

    /** 跨级别拷贝句子分析。原句用目标级别请求里的原文，不复制来源的原句 */
    public long insertCopiedSentence(Level level, String hash, String sentence, Sentence source) {
        Long id = jdbc.sql("""
                        INSERT INTO %s (hash, sentence, analysis, status, source, retry_count)
                        VALUES (:hash, :sentence, :analysis, :status, :source, 0)
                        """.formatted(level.sentenceTable()))
                .param("hash", hash)
                .param("sentence", sentence)
                .param("analysis", source.analysis())
                .param("status", GenStatus.DONE.code())
                .param("source", ContentSource.COPY.code())
                .updateAndReturnGeneratedKey(Long.class);
        if (id == null) {
            throw new IllegalStateException("插入 " + level.sentenceTable() + " 未返回主键");
        }
        return id;
    }

    public int updateAnalysisIfGenerating(Level level, long id, String analysis) {
        return jdbc.sql("""
                        UPDATE %s SET analysis = :analysis, status = :done
                         WHERE id = :id AND status = :generating
                        """.formatted(level.sentenceTable()))
                .param("analysis", analysis)
                .param("done", GenStatus.DONE.code())
                .param("generating", GenStatus.GENERATING.code())
                .param("id", id)
                .update();
    }

    public int markFailed(Level level, long id) {
        return jdbc.sql("""
                        UPDATE %s SET status = :failed
                         WHERE id = :id AND status = :generating
                        """.formatted(level.sentenceTable()))
                .param("failed", GenStatus.FAILED.code())
                .param("generating", GenStatus.GENERATING.code())
                .param("id", id)
                .update();
    }

    public int resetToGeneratingIfRetryable(Level level, long id, int maxAttempts) {
        return jdbc.sql("""
                        UPDATE %s
                           SET status = :generating, retry_count = retry_count + 1
                         WHERE id = :id
                           AND status IN (:generating, :failed)
                           AND retry_count < :maxAttempts
                        """.formatted(level.sentenceTable()))
                .param("generating", GenStatus.GENERATING.code())
                .param("failed", GenStatus.FAILED.code())
                .param("id", id)
                .param("maxAttempts", maxAttempts)
                .update();
    }

    public List<Sentence> findFailed(Level level, int maxAttempts) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + level.sentenceTable()
                        + " WHERE status = :failed AND retry_count < :maxAttempts")
                .param("failed", GenStatus.FAILED.code())
                .param("maxAttempts", maxAttempts)
                .query(MAPPER)
                .list();
    }

    /** 同 WordRepository.findStale：截断时间在 Java 侧算好，用 updated_at 而非 created_at */
    public List<Sentence> findStale(Level level, int staleSeconds) {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(staleSeconds);
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + level.sentenceTable()
                        + " WHERE status = :generating AND updated_at < :cutoff")
                .param("generating", GenStatus.GENERATING.code())
                .param("cutoff", cutoff)
                .query(MAPPER)
                .list();
    }

    public void deleteAllForTest(Level level) {
        jdbc.sql("DELETE FROM " + level.sentenceTable()).update();
    }
}
```

> `WordCard` 在 Task 8 创建。本任务只需它的 record 定义，**先按下面这个签名建好**（放在 `com.bud.wordlist.generation` 包），Task 8 再补上生成逻辑：
> ```java
> public record WordCard(String meaning, String phonetic, String pos,
>                        String mnemonic, String example, String phrase) {}
> ```

- [ ] **Step 6: 运行测试确认通过**

Run: `./mvnw test -Dtest=WordRepositoryTest`
Expected: PASS，9 个用例全绿

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/bud/wordlist/domain src/main/java/com/bud/wordlist/repository src/main/java/com/bud/wordlist/generation/WordCard.java src/test/java/com/bud/wordlist/repository
git commit -m "feat: 词表与句表的数据访问层（表名取自 Level 白名单）"
```

---

## Task 5: 用户关联表的数据访问层

**Files:**
- Create: `src/main/java/com/bud/wordlist/domain/UserWordRow.java`
- Create: `src/main/java/com/bud/wordlist/domain/UserSentenceRow.java`
- Create: `src/main/java/com/bud/wordlist/web/dto/WordView.java`
- Create: `src/main/java/com/bud/wordlist/web/dto/SentenceView.java`
- Create: `src/main/java/com/bud/wordlist/web/dto/PageResult.java`
- Create: `src/main/java/com/bud/wordlist/repository/UserWordRepository.java`
- Create: `src/main/java/com/bud/wordlist/repository/UserSentenceRepository.java`
- Test: `src/test/java/com/bud/wordlist/repository/UserWordRepositoryTest.java`

**Interfaces:**
- Consumes: `Level`、`Word`、`Sentence`、`MySqlTestBase`
- Produces:
  - `UserWordRepository.link(long userId, Level, long wordId, Integer sourceType, Long sourceId, String sourceTitle) : boolean` —— true=本次新建，false=之前已收录
  - `UserWordRepository.unlink(long userId, Level, long wordId) : boolean`
  - `UserWordRepository.pageByUser(long userId, Level, int page, int size) : PageResult<WordView>`
  - 同构的 `UserSentenceRepository`
  - `record PageResult<T>(long total, int page, int size, List<T> list)`
  - `record WordView(long wordId, String word, int status, String meaning, String phonetic, String pos, String mnemonic, String example, String phrase, String sourceTitle, LocalDateTime collectedAt)`

- [ ] **Step 1: 写失败测试 `UserWordRepositoryTest`**

```java
package com.bud.wordlist.repository;

import com.bud.wordlist.common.Level;
import com.bud.wordlist.support.MySqlTestBase;
import com.bud.wordlist.web.dto.PageResult;
import com.bud.wordlist.web.dto.WordView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class UserWordRepositoryTest extends MySqlTestBase {

    @Autowired
    UserWordRepository repo;
    @Autowired
    WordRepository wordRepo;

    @BeforeEach
    void clean() {
        repo.deleteAllForTest();
        for (Level level : Level.values()) {
            wordRepo.deleteAllForTest(level);
        }
    }

    @Test
    void link_returns_true_first_time_and_false_after() {
        long wordId = wordRepo.insertWord(Level.KET, "hello");

        assertThat(repo.link(1001L, Level.KET, wordId, 1, 8001L, "2024 KET Reading")).isTrue();
        assertThat(repo.link(1001L, Level.KET, wordId, 1, 8001L, "2024 KET Reading")).isFalse();
    }

    @Test
    void different_users_link_the_same_word_independently() {
        long wordId = wordRepo.insertWord(Level.KET, "hello");

        assertThat(repo.link(1001L, Level.KET, wordId, null, null, null)).isTrue();
        assertThat(repo.link(1002L, Level.KET, wordId, null, null, null)).isTrue();
    }

    @Test
    void unlink_only_affects_the_owning_user() {
        long wordId = wordRepo.insertWord(Level.KET, "hello");
        repo.link(1001L, Level.KET, wordId, null, null, null);

        assertThat(repo.unlink(9999L, Level.KET, wordId)).isFalse();
        assertThat(repo.unlink(1001L, Level.KET, wordId)).isTrue();
        assertThat(repo.unlink(1001L, Level.KET, wordId)).isFalse();
    }

    @Test
    void page_joins_word_content_and_orders_newest_first() {
        long helloId = wordRepo.insertWord(Level.KET, "hello");
        long worldId = wordRepo.insertWord(Level.KET, "world");
        wordRepo.updateContentIfGenerating(Level.KET, helloId,
                new com.bud.wordlist.generation.WordCard("你好", null, null, null, null, null));
        repo.link(1001L, Level.KET, helloId, 1, 8001L, "第一篇");
        repo.link(1001L, Level.KET, worldId, 2, 8002L, "第二篇");

        PageResult<WordView> page = repo.pageByUser(1001L, Level.KET, 1, 10);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.list()).extracting(WordView::word).containsExactly("world", "hello");
        WordView hello = page.list().get(1);
        assertThat(hello.meaning()).isEqualTo("你好");
        assertThat(hello.sourceTitle()).isEqualTo("第一篇");
    }

    @Test
    void page_does_not_leak_other_users_or_other_levels() {
        long id = wordRepo.insertWord(Level.KET, "hello");
        repo.link(1001L, Level.KET, id, null, null, null);
        repo.link(1002L, Level.KET, id, null, null, null);

        assertThat(repo.pageByUser(1001L, Level.KET, 1, 10).total()).isEqualTo(1);
        assertThat(repo.pageByUser(1001L, Level.PET, 1, 10).total()).isZero();
    }

    @Test
    void page_respects_offset() {
        for (int i = 0; i < 5; i++) {
            long id = wordRepo.insertWord(Level.KET, "word" + i);
            repo.link(1001L, Level.KET, id, null, null, null);
        }

        PageResult<WordView> second = repo.pageByUser(1001L, Level.KET, 2, 2);

        assertThat(second.total()).isEqualTo(5);
        assertThat(second.list()).hasSize(2);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=UserWordRepositoryTest`
Expected: 编译失败，`cannot find symbol: class UserWordRepository`

- [ ] **Step 3: 实现 DTO 与 domain record**

```java
package com.bud.wordlist.web.dto;

import java.util.List;

public record PageResult<T>(long total, int page, int size, List<T> list) {
}
```

```java
package com.bud.wordlist.web.dto;

import java.time.LocalDateTime;

public record WordView(
        long wordId,
        String word,
        int status,
        String meaning,
        String phonetic,
        String pos,
        String mnemonic,
        String example,
        String phrase,
        String sourceTitle,
        LocalDateTime collectedAt) {
}
```

```java
package com.bud.wordlist.web.dto;

import java.time.LocalDateTime;

public record SentenceView(
        long sentenceId,
        String hash,
        String sentence,
        int status,
        String analysis,
        String sourceTitle,
        LocalDateTime collectedAt) {
}
```

```java
package com.bud.wordlist.domain;

/** user_word 的一行 */
public record UserWordRow(Long id, long userId, String level, long wordId,
                          Integer sourceType, Long sourceId, String sourceTitle) {
}
```

```java
package com.bud.wordlist.domain;

/** user_sentence 的一行 */
public record UserSentenceRow(Long id, long userId, String level, long sentenceId,
                              Integer sourceType, Long sourceId, String sourceTitle) {
}
```

- [ ] **Step 4: 实现 `UserWordRepository`**

```java
package com.bud.wordlist.repository;

import com.bud.wordlist.common.Level;
import com.bud.wordlist.web.dto.PageResult;
import com.bud.wordlist.web.dto.WordView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class UserWordRepository {

    private static final RowMapper<WordView> VIEW_MAPPER = (rs, i) -> new WordView(
            rs.getLong("word_id"),
            rs.getString("word"),
            rs.getInt("status"),
            rs.getString("meaning"),
            rs.getString("phonetic"),
            rs.getString("pos"),
            rs.getString("mnemonic"),
            rs.getString("example"),
            rs.getString("phrase"),
            rs.getString("source_title"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcClient jdbc;

    public UserWordRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true=本次新建；false=该用户之前已收录过（幂等） */
    public boolean link(long userId, Level level, long wordId,
                        Integer sourceType, Long sourceId, String sourceTitle) {
        try {
            jdbc.sql("""
                            INSERT INTO user_word
                                (user_id, level, word_id, source_type, source_id, source_title)
                            VALUES (:userId, :level, :wordId, :sourceType, :sourceId, :sourceTitle)
                            """)
                    .param("userId", userId)
                    .param("level", level.code())
                    .param("wordId", wordId)
                    .param("sourceType", sourceType)
                    .param("sourceId", sourceId)
                    .param("sourceTitle", sourceTitle)
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            // 唯一键 (user_id, level, word_id) 冲突 = 之前已收录，属于幂等成功
            return false;
        }
    }

    /** @return true=确实删掉了一行；false=本来就没收录 */
    public boolean unlink(long userId, Level level, long wordId) {
        return jdbc.sql("""
                        DELETE FROM user_word
                         WHERE user_id = :userId AND level = :level AND word_id = :wordId
                        """)
                .param("userId", userId)
                .param("level", level.code())
                .param("wordId", wordId)
                .update() > 0;
    }

    public PageResult<WordView> pageByUser(long userId, Level level, int page, int size) {
        long total = jdbc.sql("""
                        SELECT COUNT(*) FROM user_word
                         WHERE user_id = :userId AND level = :level
                        """)
                .param("userId", userId)
                .param("level", level.code())
                .query(Long.class)
                .single();

        List<WordView> list = jdbc.sql("""
                        SELECT w.id AS word_id, w.word, w.status, w.meaning, w.phonetic, w.pos,
                               w.mnemonic, w.example, w.phrase,
                               uw.source_title, uw.created_at
                          FROM user_word uw
                          JOIN %s w ON w.id = uw.word_id
                         WHERE uw.user_id = :userId AND uw.level = :level
                         ORDER BY uw.created_at DESC, uw.id DESC
                         LIMIT :limit OFFSET :offset
                        """.formatted(level.wordTable()))
                .param("userId", userId)
                .param("level", level.code())
                .param("limit", size)
                .param("offset", (page - 1) * size)
                .query(VIEW_MAPPER)
                .list();

        return new PageResult<>(total, page, size, list);
    }

    public void deleteAllForTest() {
        jdbc.sql("DELETE FROM user_word").update();
    }
}
```

- [ ] **Step 5: 实现 `UserSentenceRepository`**

```java
package com.bud.wordlist.repository;

import com.bud.wordlist.common.Level;
import com.bud.wordlist.web.dto.PageResult;
import com.bud.wordlist.web.dto.SentenceView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class UserSentenceRepository {

    private static final RowMapper<SentenceView> VIEW_MAPPER = (rs, i) -> new SentenceView(
            rs.getLong("sentence_id"),
            rs.getString("hash"),
            rs.getString("sentence"),
            rs.getInt("status"),
            rs.getString("analysis"),
            rs.getString("source_title"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcClient jdbc;

    public UserSentenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true=本次新建；false=该用户之前已收录过（幂等） */
    public boolean link(long userId, Level level, long sentenceId,
                        Integer sourceType, Long sourceId, String sourceTitle) {
        try {
            jdbc.sql("""
                            INSERT INTO user_sentence
                                (user_id, level, sentence_id, source_type, source_id, source_title)
                            VALUES (:userId, :level, :sentenceId, :sourceType, :sourceId, :sourceTitle)
                            """)
                    .param("userId", userId)
                    .param("level", level.code())
                    .param("sentenceId", sentenceId)
                    .param("sourceType", sourceType)
                    .param("sourceId", sourceId)
                    .param("sourceTitle", sourceTitle)
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** @return true=确实删掉了一行；false=本来就没收录 */
    public boolean unlink(long userId, Level level, long sentenceId) {
        return jdbc.sql("""
                        DELETE FROM user_sentence
                         WHERE user_id = :userId AND level = :level AND sentence_id = :sentenceId
                        """)
                .param("userId", userId)
                .param("level", level.code())
                .param("sentenceId", sentenceId)
                .update() > 0;
    }

    public PageResult<SentenceView> pageByUser(long userId, Level level, int page, int size) {
        long total = jdbc.sql("""
                        SELECT COUNT(*) FROM user_sentence
                         WHERE user_id = :userId AND level = :level
                        """)
                .param("userId", userId)
                .param("level", level.code())
                .query(Long.class)
                .single();

        List<SentenceView> list = jdbc.sql("""
                        SELECT s.id AS sentence_id, s.hash, s.sentence, s.status, s.analysis,
                               us.source_title, us.created_at
                          FROM user_sentence us
                          JOIN %s s ON s.id = us.sentence_id
                         WHERE us.user_id = :userId AND us.level = :level
                         ORDER BY us.created_at DESC, us.id DESC
                         LIMIT :limit OFFSET :offset
                        """.formatted(level.sentenceTable()))
                .param("userId", userId)
                .param("level", level.code())
                .param("limit", size)
                .param("offset", (page - 1) * size)
                .query(VIEW_MAPPER)
                .list();

        return new PageResult<>(total, page, size, list);
    }

    public void deleteAllForTest() {
        jdbc.sql("DELETE FROM user_sentence").update();
    }
}
```

> 关联表本身（`user_word` / `user_sentence`）不分级别，表名是写死的字面量；只有 JOIN 的那一侧才按 `level` 选表。

- [ ] **Step 6: 运行测试确认通过**

Run: `./mvnw test -Dtest=UserWordRepositoryTest`
Expected: PASS，6 个用例全绿

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/bud/wordlist/domain src/main/java/com/bud/wordlist/web/dto src/main/java/com/bud/wordlist/repository/UserWordRepository.java src/main/java/com/bud/wordlist/repository/UserSentenceRepository.java src/test/java/com/bud/wordlist/repository/UserWordRepositoryTest.java
git commit -m "feat: 用户收录关联表的数据访问层（含幂等关联与分页联查）"
```

---

## Task 6: 跨级别拷贝链解析

**Files:**
- Create: `src/main/java/com/bud/wordlist/service/CopyResolver.java`
- Test: `src/test/java/com/bud/wordlist/service/CopyResolverTest.java`

**Interfaces:**
- Consumes: `WordlistProperties`（Task 1）、`Level`（Task 2）
- Produces: `CopyResolver.sourcesFor(Level) : List<Level>` —— 返回该级别配置的来源级别列表；总开关关闭或未配置时返回空列表

- [ ] **Step 1: 写失败测试 `CopyResolverTest`**

```java
package com.bud.wordlist.service;

import com.bud.wordlist.common.Level;
import com.bud.wordlist.config.WordlistProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CopyResolverTest {

    private static CopyResolver resolver(boolean enabled, Map<Level, List<Level>> chain) {
        return new CopyResolver(new WordlistProperties(
                new WordlistProperties.Copy(enabled, chain),
                new WordlistProperties.Llm("none", "m", "u", "", 30, 3),
                new WordlistProperties.Async(4, 8, 500, false, "0 0 * * * ?", 120)));
    }

    @Test
    void returns_configured_sources_in_order() {
        CopyResolver r = resolver(true, Map.of(Level.KET, List.of(Level.PET, Level.ZHONGKAO)));

        assertThat(r.sourcesFor(Level.KET)).containsExactly(Level.PET, Level.ZHONGKAO);
    }

    @Test
    void returns_empty_when_level_has_no_configured_source() {
        CopyResolver r = resolver(true, Map.of(Level.KET, List.of(Level.PET)));

        assertThat(r.sourcesFor(Level.ZHONGKAO)).isEmpty();
        assertThat(r.sourcesFor(Level.IELTS)).isEmpty();
    }

    @Test
    void returns_empty_when_master_switch_is_off() {
        CopyResolver r = resolver(false, Map.of(Level.KET, List.of(Level.PET)));

        assertThat(r.sourcesFor(Level.KET)).isEmpty();
    }

    @Test
    void returns_empty_when_chain_is_missing_entirely() {
        CopyResolver r = resolver(true, Map.of());

        assertThat(r.sourcesFor(Level.KET)).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=CopyResolverTest`
Expected: 编译失败，`cannot find symbol: class CopyResolver`

- [ ] **Step 3: 实现 `CopyResolver`**

```java
package com.bud.wordlist.service;

import com.bud.wordlist.common.Level;
import com.bud.wordlist.config.WordlistProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 解析跨级别拷贝链。总开关关闭、或该级别未配置来源时，返回空列表，
 * 调用方据此跳过拷贝、直接走 AI 生成。
 */
@Component
public class CopyResolver {

    private final WordlistProperties properties;

    public CopyResolver(WordlistProperties properties) {
        this.properties = properties;
    }

    public List<Level> sourcesFor(Level target) {
        if (!properties.copy().enabled()) {
            return List.of();
        }
        return properties.copy().chain().getOrDefault(target, List.of());
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw test -Dtest=CopyResolverTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bud/wordlist/service/CopyResolver.java src/test/java/com/bud/wordlist/service/CopyResolverTest.java
git commit -m "feat: 可配置的跨级别拷贝链解析"
```

---

## Task 7: 单词收录主流程

**Files:**
- Create: `src/main/java/com/bud/wordlist/service/GenerationDispatcher.java`（本任务只建接口 + 一个记录调用的测试替身，实现放 Task 8）
- Create: `src/main/java/com/bud/wordlist/service/WordCollectService.java`
- Create: `src/main/java/com/bud/wordlist/web/dto/CollectWordRequest.java`
- Test: `src/test/java/com/bud/wordlist/support/FakeGenerationDispatcher.java`
- Test: `src/test/java/com/bud/wordlist/service/WordCollectServiceTest.java`

**Interfaces:**
- Consumes: `WordRepository`、`UserWordRepository`、`CopyResolver`、`WordNormalizer`、`Level`
- Produces:
  - `GenerationDispatcher.dispatchWord(Level, long wordId, String word)` / `.dispatchSentence(Level, long sentenceId, String sentence)` —— Task 8 给出真实实现
  - `WordCollectService.collect(CollectWordRequest) : WordCollectService.CollectResult`，其中 `record CollectResult(WordView view, boolean newlyCollected)`
  - `record CollectWordRequest(long userId, String level, String word, Integer sourceType, Long sourceId, String sourceTitle)`

- [ ] **Step 1: 定义 `GenerationDispatcher` 接口与测试替身**

```java
package com.bud.wordlist.service;

import com.bud.wordlist.common.Level;

/**
 * 把生成任务投递到后台。实现必须立即返回，绝不能阻塞调用线程。
 */
public interface GenerationDispatcher {

    void dispatchWord(Level level, long wordId, String word);

    void dispatchSentence(Level level, long sentenceId, String sentence);
}
```

```java
package com.bud.wordlist.support;

import com.bud.wordlist.common.Level;
import com.bud.wordlist.service.GenerationDispatcher;

import java.util.ArrayList;
import java.util.List;

/** 测试替身：只记录被投递了什么，不真的调用 AI */
public class FakeGenerationDispatcher implements GenerationDispatcher {

    public record Call(Level level, long id, String text) {
    }

    public final List<Call> words = new ArrayList<>();
    public final List<Call> sentences = new ArrayList<>();

    @Override
    public void dispatchWord(Level level, long wordId, String word) {
        words.add(new Call(level, wordId, word));
    }

    @Override
    public void dispatchSentence(Level level, long sentenceId, String sentence) {
        sentences.add(new Call(level, sentenceId, sentence));
    }
}
```

- [ ] **Step 2: 写失败测试 `WordCollectServiceTest`**

覆盖 Review Focus 第 3 条（`source_title` 超长）与第 6 条（跨级别不串表）。

```java
package com.bud.wordlist.service;

import com.bud.wordlist.common.ContentSource;
import com.bud.wordlist.common.GenStatus;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.config.WordlistProperties;
import com.bud.wordlist.domain.Word;
import com.bud.wordlist.repository.UserWordRepository;
import com.bud.wordlist.repository.WordRepository;
import com.bud.wordlist.support.FakeGenerationDispatcher;
import com.bud.wordlist.support.MySqlTestBase;
import com.bud.wordlist.web.dto.CollectWordRequest;
import com.bud.wordlist.web.dto.WordView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Import(WordCollectServiceTest.TestConfig.class)
class WordCollectServiceTest extends MySqlTestBase {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        FakeGenerationDispatcher fakeGenerationDispatcher() {
            return new FakeGenerationDispatcher();
        }

        /** 测试里显式固定拷贝链，不依赖 application.yml */
        @Bean
        @Primary
        WordlistProperties testProperties() {
            return new WordlistProperties(
                    new WordlistProperties.Copy(true, Map.of(
                            Level.KET, List.of(Level.PET),
                            Level.PET, List.of(Level.ZHONGKAO))),
                    new WordlistProperties.Llm("none", "m", "u", "", 30, 3),
                    new WordlistProperties.Async(4, 8, 500, false, "0 0 * * * ?", 120));
        }
    }

    @Autowired
    WordCollectService service;
    @Autowired
    WordRepository wordRepo;
    @Autowired
    UserWordRepository userWordRepo;
    @Autowired
    FakeGenerationDispatcher dispatcher;

    @BeforeEach
    void clean() {
        userWordRepo.deleteAllForTest();
        for (Level level : Level.values()) {
            wordRepo.deleteAllForTest(level);
        }
        dispatcher.words.clear();
    }

    @Test
    void collects_new_word_and_dispatches_generation_without_waiting() {
        WordCollectService.CollectResult result = service.collect(
                new CollectWordRequest(1001L, "ket", "Hello", 1, 8001L, "2024 KET Reading"));

        WordView view = result.view();
        assertThat(view.word()).isEqualTo("hello");
        assertThat(view.status()).isEqualTo(GenStatus.GENERATING.code());
        assertThat(view.meaning()).isNull();
        assertThat(result.newlyCollected()).isTrue();
        assertThat(dispatcher.words).hasSize(1);
        assertThat(dispatcher.words.get(0).text()).isEqualTo("hello");
        assertThat(dispatcher.words.get(0).level()).isEqualTo(Level.KET);
    }

    @Test
    void re_collecting_the_same_word_is_idempotent_and_does_not_redispatch() {
        service.collect(new CollectWordRequest(1001L, "ket", "hello", null, null, null));
        dispatcher.words.clear();

        WordCollectService.CollectResult second =
                service.collect(new CollectWordRequest(1001L, "ket", "hello", null, null, null));

        assertThat(second.newlyCollected()).isFalse();
        assertThat(dispatcher.words).isEmpty();
        assertThat(wordRepo.findByWord(Level.KET, "hello")).isPresent();
    }

    @Test
    void a_second_user_collecting_an_existing_word_creates_no_new_word_row() {
        service.collect(new CollectWordRequest(1001L, "ket", "hello", null, null, null));
        dispatcher.words.clear();

        service.collect(new CollectWordRequest(1002L, "ket", "hello", null, null, null));

        assertThat(dispatcher.words).isEmpty();
        Long rows = countWords(Level.KET);
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    void copies_content_from_configured_source_level_without_calling_ai() {
        // PET 里先有一个完整的 hello
        long petId = wordRepo.insertWord(Level.PET, "hello");
        wordRepo.updateContentIfGenerating(Level.PET, petId, new com.bud.wordlist.generation.WordCard(
                "int. 你好", "/həˈləʊ/", "int.", "谐音哈喽", "Hello!", "say hello to sb."));

        WordView view = service.collect(
                new CollectWordRequest(1001L, "ket", "hello", null, null, null)).view();

        assertThat(view.status()).isEqualTo(GenStatus.DONE.code());
        assertThat(view.meaning()).isEqualTo("int. 你好");
        assertThat(view.phonetic()).isEqualTo("/həˈləʊ/");
        assertThat(dispatcher.words).isEmpty();          // 关键：没有调用 AI

        Word ketWord = wordRepo.findByWord(Level.KET, "hello").orElseThrow();
        assertThat(ketWord.source()).isEqualTo(ContentSource.COPY.code());
    }

    @Test
    void falls_back_to_ai_when_no_source_level_has_the_word() {
        WordView view = service.collect(
                new CollectWordRequest(1001L, "ket", "nonexistent", null, null, null)).view();

        assertThat(view.status()).isEqualTo(GenStatus.GENERATING.code());
        assertThat(dispatcher.words).hasSize(1);
    }

    /** Review Focus 第 6 条：同一个词在不同级别各存一份，互不影响 */
    @Test
    void same_word_in_two_levels_produces_two_independent_rows() {
        service.collect(new CollectWordRequest(1001L, "ket", "hello", null, null, null));
        service.collect(new CollectWordRequest(1001L, "ielts", "hello", null, null, null));

        assertThat(wordRepo.findByWord(Level.KET, "hello")).isPresent();
        assertThat(wordRepo.findByWord(Level.IELTS, "hello")).isPresent();
        assertThat(countWords(Level.KET)).isEqualTo(1L);
        assertThat(countWords(Level.IELTS)).isEqualTo(1L);
        assertThat(dispatcher.words).hasSize(2);
    }

    /** Review Focus 第 3 条：超长 source_title 必须安全截断，不能 500 */
    @Test
    void oversized_source_title_is_truncated_not_rejected() {
        String longTitle = "标".repeat(500);

        WordView view = service.collect(
                new CollectWordRequest(1001L, "ket", "hello", 1, 8001L, longTitle)).view();

        assertThat(view.sourceTitle()).hasSize(255);
    }

    private Long countWords(Level level) {
        return wordRepo.findByWord(level, "hello").map(w -> 1L).orElse(0L);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `./mvnw test -Dtest=WordCollectServiceTest`
Expected: 编译失败，`cannot find symbol: class WordCollectService`

- [ ] **Step 4: 实现请求 DTO**

```java
package com.bud.wordlist.web.dto;

public record CollectWordRequest(
        long userId,
        String level,
        String word,
        Integer sourceType,
        Long sourceId,
        String sourceTitle) {
}
```

- [ ] **Step 5: 实现 `WordCollectService`**

```java
package com.bud.wordlist.service;

import com.bud.wordlist.common.ErrorCode;
import com.bud.wordlist.common.BizException;
import com.bud.wordlist.common.GenStatus;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.domain.Word;
import com.bud.wordlist.repository.UserWordRepository;
import com.bud.wordlist.repository.WordRepository;
import com.bud.wordlist.web.dto.CollectWordRequest;
import com.bud.wordlist.web.dto.WordView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class WordCollectService {

    /** 与 user_word.source_title 的列宽一致 */
    private static final int SOURCE_TITLE_MAX = 255;

    private final WordRepository wordRepository;
    private final UserWordRepository userWordRepository;
    private final CopyResolver copyResolver;
    private final GenerationDispatcher dispatcher;

    public WordCollectService(WordRepository wordRepository,
                              UserWordRepository userWordRepository,
                              CopyResolver copyResolver,
                              GenerationDispatcher dispatcher) {
        this.wordRepository = wordRepository;
        this.userWordRepository = userWordRepository;
        this.copyResolver = copyResolver;
        this.dispatcher = dispatcher;
    }

    /** 收录结果：视图 + 本次是否为新建关联（false 表示之前已收录，幂等重复） */
    public record CollectResult(WordView view, boolean newlyCollected) {
    }

    public CollectResult collect(CollectWordRequest request) {
        Level level = Level.fromCode(request.level())
                .orElseThrow(() -> new BizException(ErrorCode.INVALID_LEVEL, "级别不合法"));

        String word = WordNormalizer.word(request.word());
        if (!WordNormalizer.isValidWord(word)) {
            throw new BizException(ErrorCode.INVALID_WORD, "单词不合法");
        }
        if (request.userId() <= 0) {
            throw new BizException(ErrorCode.INVALID_USER, "用户id不合法");
        }

        String sourceTitle = truncate(request.sourceTitle());
        Word resolved = resolveWord(level, word);
        boolean newlyCollected = userWordRepository.link(request.userId(), level, resolved.id(),
                request.sourceType(), request.sourceId(), sourceTitle);

        return new CollectResult(toView(resolved, sourceTitle), newlyCollected);
    }

    /** 查库 → 拷贝 → 新建。三步都可能命中已有记录 */
    private Word resolveWord(Level level, String word) {
        Optional<Word> existing = wordRepository.findByWord(level, word);
        if (existing.isPresent()) {
            return existing.get();
        }

        Optional<Word> copied = copyFromOtherLevel(level, word);
        if (copied.isPresent()) {
            return copied.get();
        }

        return insertAndDispatch(level, word);
    }

    private Optional<Word> copyFromOtherLevel(Level level, String word) {
        for (Level source : copyResolver.sourcesFor(level)) {
            Optional<Word> found = wordRepository.findByWord(source, word);
            // 只拷贝已经生成完成的内容；来源本身还在生成中就跳过，避免复制半成品
            if (found.isPresent() && found.get().status() == GenStatus.DONE.code()) {
                long id = wordRepository.insertCopiedWord(level, word, found.get());
                return wordRepository.findById(level, id);
            }
        }
        return Optional.empty();
    }

    private Word insertAndDispatch(Level level, String word) {
        long id;
        try {
            id = wordRepository.insertWord(level, word);
        } catch (DuplicateKeyException e) {
            // 并发下已被别的请求建好：取回已有记录，不再重复投递
            return wordRepository.findByWord(level, word)
                    .orElseThrow(() -> new IllegalStateException("并发插入后查不到: " + word));
        }
        dispatcher.dispatchWord(level, id, word);
        return wordRepository.findById(level, id)
                .orElseThrow(() -> new IllegalStateException("插入后查不到 id=" + id));
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= SOURCE_TITLE_MAX ? s : s.substring(0, SOURCE_TITLE_MAX);
    }

    private static WordView toView(Word w, String sourceTitle) {
        return new WordView(w.id(), w.word(), w.status(), w.meaning(), w.phonetic(),
                w.pos(), w.mnemonic(), w.example(), w.phrase(), sourceTitle, null);
    }
}
```

> `collectedAt` 传 null —— 收录响应是"刚发生"的动作，不需要时间戳；列表接口才需要。

- [ ] **Step 6: 运行测试确认通过**

Run: `./mvnw test -Dtest=WordCollectServiceTest`
Expected: PASS，7 个用例全绿

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/bud/wordlist/service src/main/java/com/bud/wordlist/web/dto/CollectWordRequest.java src/test/java/com/bud/wordlist/support src/test/java/com/bud/wordlist/service/WordCollectServiceTest.java
git commit -m "feat: 单词收录主流程（查库→拷贝→落库→异步投递）"
```

---

## Task 8: 生成任务的投递、重试与写回

**Files:**
- Create: `src/main/java/com/bud/wordlist/generation/GenerationException.java`
- Create: `src/main/java/com/bud/wordlist/generation/RetryPolicy.java`
- Create: `src/main/java/com/bud/wordlist/config/AsyncConfig.java`
- Modify: `src/main/java/com/bud/wordlist/service/GenerationDispatcher.java`（接口 → 实现类）
- Test: `src/test/java/com/bud/wordlist/generation/RetryPolicyTest.java`
- Test: `src/test/java/com/bud/wordlist/service/GenerationDispatcherTest.java`

**Interfaces:**
- Consumes: `WordRepository`、`SentenceRepository`、`WordCardGenerator`、`SentenceAnalyzer`（后两者在 Task 9/10 实现，本任务测试用 Fake）
- Produces:
  - `GenerationException(String message, Throwable cause)`、`GenerationException(String message)`
  - `RetryPolicy<T>(int maxAttempts).execute(Supplier<T> action) : T`，耗尽后抛 `GenerationException`
  - `AsyncConfig` 暴露 `ExecutorService generationExecutor()`（Spring 管理的 `ThreadPoolTaskExecutor`）
  - `GenerationDispatcher` 由接口变为 `@Service` 实现类（**Task 7 的测试替身仍需实现同一接口，故保留接口 + 实现类两件套**）

- [ ] **Step 1: 写失败测试 `RetryPolicyTest`**

```java
package com.bud.wordlist.generation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {

    private static final RetryPolicy POLICY = new RetryPolicy(3, 0); // 退避 0ms，测试不等

    @Test
    void returns_first_successful_result_without_retrying() {
        AtomicInteger calls = new AtomicInteger();

        String result = POLICY.execute(() -> {
            calls.incrementAndGet();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(1);
    }

    @Test
    void retries_timeouts_up_to_max_attempts_then_throws() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> POLICY.execute(() -> {
            calls.incrementAndGet();
            throw new SocketTimeoutException("超时");
        })).isInstanceOf(GenerationException.class);

        assertThat(calls).hasValue(3);
    }

    @Test
    void succeeds_on_a_later_attempt() {
        AtomicInteger calls = new AtomicInteger();

        String result = POLICY.execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new SocketTimeoutException("超时");
            }
            return "终于成功";
        });

        assertThat(result).isEqualTo("终于成功");
        assertThat(calls).hasValue(3);
    }

    @Test
    void does_not_retry_non_retryable_errors() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> POLICY.execute(() -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("参数错误，重试也没用");
        })).isInstanceOf(GenerationException.class);

        assertThat(calls).hasValue(1);
    }

    @Test
    void treats_retryable_marker_exceptions_as_retryable() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> POLICY.execute(() -> {
            calls.incrementAndGet();
            throw new GenerationException("模型返回了畸形 JSON", null, true);
        })).isInstanceOf(GenerationException.class);

        assertThat(calls).hasValue(3);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=RetryPolicyTest`
Expected: 编译失败，`cannot find symbol: class RetryPolicy`

- [ ] **Step 3: 实现 `GenerationException` 与 `RetryPolicy`**

```java
package com.bud.wordlist.generation;

public class GenerationException extends RuntimeException {

    private final boolean retryable;

    public GenerationException(String message) {
        this(message, null, false);
    }

    public GenerationException(String message, Throwable cause) {
        this(message, cause, true);
    }

    public GenerationException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
```

```java
package com.bud.wordlist.generation;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 带退避的重试。只重试"可重试"错误：
 * 超时、连接失败、IO 异常、以及显式标了 retryable 的 GenerationException。
 * 其余异常（参数错误、4xx）立即抛出，重试只是浪费配额。
 */
public class RetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);
    private static final long BASE_BACKOFF_MS = 1000L;

    private final int maxAttempts;
    private final long baseBackoffMs;

    public RetryPolicy(int maxAttempts, long baseBackoffMs) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseBackoffMs = baseBackoffMs;
    }

    public <T> T execute(Supplier<T> action) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                last = e;
                if (!isRetryable(e) || attempt == maxAttempts) {
                    break;
                }
                sleepBackoff(attempt);
            }
        }
        throw new GenerationException("生成失败，已尝试 " + attempts(last) + " 次: "
                + (last == null ? "未知错误" : last.getMessage()), last);
    }

    private int attempts(RuntimeException last) {
        return maxAttempts;
    }

    private static boolean isRetryable(RuntimeException e) {
        if (e instanceof GenerationException ge) {
            return ge.retryable();
        }
        return e instanceof IOException;
    }

    private void sleepBackoff(int attempt) {
        if (baseBackoffMs <= 0) {
            return;
        }
        long base = baseBackoffMs * (1L << (attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(base / 5 + 1);
        long delay = base + jitter - (base / 10);
        try {
            Thread.sleep(Math.max(0, delay));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new GenerationException("重试被中断", ie, false);
        }
    }
}
```

> `maxAttempts` 从 `WordlistProperties.llm().maxAttempts()` 注入，`baseBackoffMs` 生产用 1000、测试用 0。构造 `RetryPolicy` 的地方见 Task 9/10。

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw test -Dtest=RetryPolicyTest`
Expected: PASS，5 个用例全绿

- [ ] **Step 5: 写 `AsyncConfig`**

```java
package com.bud.wordlist.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncConfig {

    /**
     * 生成任务线程池。
     * 拒绝策略用 AbortPolicy（默认）：队列满时抛异常，由 GenerationDispatcher 捕获后
     * 记日志并放弃投递——记录会留在 status=生成中，由补偿任务在下一轮捞起来。
     * 绝不能用 CallerRunsPolicy：那会让 HTTP 请求线程亲自去跑 AI 调用，
     * 直接违背"收录接口立即返回"的目标。
     */
    @Bean("generationExecutor")
    public Executor generationExecutor(WordlistProperties properties) {
        WordlistProperties.Async cfg = properties.async();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(cfg.corePoolSize());
        executor.setMaxPoolSize(cfg.maxPoolSize());
        executor.setQueueCapacity(cfg.queueCapacity());
        executor.setThreadNamePrefix("gen-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
```

- [ ] **Step 6: 写失败测试 `GenerationDispatcherTest`**

覆盖 Review Focus 第 4 条：生成完成前用户取消收录，写回仍应成功。

```java
package com.bud.wordlist.service;

import com.bud.wordlist.common.GenStatus;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.domain.Word;
import com.bud.wordlist.generation.GenerationException;
import com.bud.wordlist.generation.SentenceAnalyzer;
import com.bud.wordlist.generation.WordCard;
import com.bud.wordlist.generation.WordCardGenerator;
import com.bud.wordlist.repository.SentenceRepository;
import com.bud.wordlist.repository.UserWordRepository;
import com.bud.wordlist.repository.WordRepository;
import com.bud.wordlist.support.MySqlTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Import(GenerationDispatcherTest.TestConfig.class)
class GenerationDispatcherTest extends MySqlTestBase {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        StubWordCardGenerator stubWordCardGenerator() {
            return new StubWordCardGenerator();
        }

        @Bean
        @Primary
        StubSentenceAnalyzer stubSentenceAnalyzer() {
            return new StubSentenceAnalyzer();
        }

        /**
         * maxAttempts=1：本任务只验证"失败落库"这一个行为，重试次数由
         * RetryPolicyTest 单独覆盖。设成 1 可以让桩的调用次数断言稳定为 1，
         * 不受退避等待影响。
         */
        @Bean
        @Primary
        WordlistProperties testProperties() {
            return new WordlistProperties(
                    new WordlistProperties.Copy(false, java.util.Map.of()),
                    new WordlistProperties.Llm("none", "m", "u", "", 30, 1),
                    new WordlistProperties.Async(4, 8, 500, false, "0 0 * * * ?", 120));
        }
    }

    static class StubWordCardGenerator implements WordCardGenerator {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean fail;
        volatile CountDownLatch release;

        @Override
        public WordCard generate(String word, Level level) {
            calls.incrementAndGet();
            if (release != null) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (fail) {
                // 可重试失败：生成器耗尽重试后应把记录标为"生成失败"。
                // 不可重试的失败（如 provider=none）不会标记失败，见 Task 10。
                throw new GenerationException("桩：失败", null, true);
            }
            return new WordCard("释义", "/f/", "n.", "助记", "例句", "词组");
        }
    }

    static class StubSentenceAnalyzer implements SentenceAnalyzer {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean fail;

        @Override
        public String analyze(String sentence, Level level) {
            calls.incrementAndGet();
            if (fail) {
                throw new GenerationException("桩：失败", null, true);
            }
            return "句子分析内容";
        }
    }

    @Autowired
    GenerationDispatcher dispatcher;
    @Autowired
    WordRepository wordRepo;
    @Autowired
    SentenceRepository sentenceRepo;
    @Autowired
    UserWordRepository userWordRepo;
    @Autowired
    StubWordCardGenerator generator;

    @BeforeEach
    void clean() {
        userWordRepo.deleteAllForTest();
        for (Level level : Level.values()) {
            wordRepo.deleteAllForTest(level);
            sentenceRepo.deleteAllForTest(level);
        }
        generator.fail = false;
        generator.release = null;
    }

    @Test
    void successful_generation_writes_back_all_fields() throws Exception {
        long id = wordRepo.insertWord(Level.KET, "hello");

        dispatcher.dispatchWord(Level.KET, id, "hello");
        awaitStatus(Level.KET, id, GenStatus.DONE);

        Word w = wordRepo.findById(Level.KET, id).orElseThrow();
        assertThat(w.meaning()).isEqualTo("释义");
        assertThat(w.phonetic()).isEqualTo("/f/");
        assertThat(w.status()).isEqualTo(GenStatus.DONE.code());
    }

    @Test
    void failed_generation_marks_record_failed() throws Exception {
        generator.fail = true;
        long id = wordRepo.insertWord(Level.KET, "hello");

        dispatcher.dispatchWord(Level.KET, id, "hello");
        awaitStatus(Level.KET, id, GenStatus.FAILED);

        assertThat(generator.calls).hasValue(1);
    }

    /** Review Focus 第 4 条：用户在生成完成前取消收录，写回仍应成功 */
    @Test
    void write_back_still_succeeds_after_user_unlinks() throws Exception {
        long id = wordRepo.insertWord(Level.KET, "hello");
        userWordRepo.link(1001L, Level.KET, id, null, null, null);
        userWordRepo.unlink(1001L, Level.KET, id);

        dispatcher.dispatchWord(Level.KET, id, "hello");
        awaitStatus(Level.KET, id, GenStatus.DONE);

        assertThat(wordRepo.findById(Level.KET, id).orElseThrow().meaning()).isEqualTo("释义");
    }

    /** 写回撞上已被处理过的记录时，结果被丢弃而不是覆盖 */
    @Test
    void write_back_is_dropped_when_record_already_done() throws Exception {
        long id = wordRepo.insertWord(Level.KET, "hello");
        wordRepo.updateContentIfGenerating(Level.KET, id,
                new WordCard("先到的结果", null, null, null, null, null));

        dispatcher.dispatchWord(Level.KET, id, "hello");
        Thread.sleep(500);   // 给异步任务一点时间跑完

        assertThat(wordRepo.findById(Level.KET, id).orElseThrow().meaning())
                .isEqualTo("先到的结果");
    }

    private void awaitStatus(Level level, long id, GenStatus expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer status = wordRepo.findById(level, id).map(Word::status).orElse(null);
            if (status != null && status == expected.code()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待状态 " + expected + " 超时");
    }
}
```

- [ ] **Step 7: 运行测试确认失败**

Run: `./mvnw test -Dtest=GenerationDispatcherTest`
Expected: 编译失败，`cannot find symbol: class WordCardGenerator`

- [ ] **Step 8: 定义生成器接口，并把 `GenerationDispatcher` 接口改为实现类**

先建两个接口（实现在 Task 9/10）：

```java
package com.bud.wordlist.generation;

import com.bud.wordlist.common.Level;

/** 生成单词卡片；失败抛 GenerationException */
public interface WordCardGenerator {
    WordCard generate(String word, Level level);
}
```

```java
package com.bud.wordlist.generation;

import com.bud.wordlist.common.Level;

/** 生成句子分析；失败抛 GenerationException */
public interface SentenceAnalyzer {
    String analyze(String sentence, Level level);
}
```

保留 Task 7 定义的 `GenerationDispatcher` **接口**（测试替身要它），另建实现类：

```java
package com.bud.wordlist.service;

import com.bud.wordlist.common.GenStatus;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.config.WordlistProperties;
import com.bud.wordlist.generation.GenerationException;
import com.bud.wordlist.generation.RetryPolicy;
import com.bud.wordlist.generation.SentenceAnalyzer;
import com.bud.wordlist.generation.WordCard;
import com.bud.wordlist.generation.WordCardGenerator;
import com.bud.wordlist.repository.SentenceRepository;
import com.bud.wordlist.repository.WordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 生成任务的投递与写回。dispatch 方法必须立即返回，
 * 真正的 AI 调用在线程池里跑。
 *
 * 生产环境里这是 GenerationDispatcher 的唯一实现，所以**不加 @Primary**——
 * 否则测试里标记 @Primary 的替身会与它冲突（Spring 不允许出现两个 primary）。
 */
@Service
public class AsyncGenerationDispatcher implements GenerationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AsyncGenerationDispatcher.class);

    private final WordRepository wordRepository;
    private final SentenceRepository sentenceRepository;
    private final WordCardGenerator wordCardGenerator;
    private final SentenceAnalyzer sentenceAnalyzer;
    private final Executor executor;
    private final RetryPolicy retryPolicy;

    public AsyncGenerationDispatcher(WordRepository wordRepository,
                                     SentenceRepository sentenceRepository,
                                     WordCardGenerator wordCardGenerator,
                                     SentenceAnalyzer sentenceAnalyzer,
                                     @Qualifier("generationExecutor") Executor executor,
                                     WordlistProperties properties) {
        this.wordRepository = wordRepository;
        this.sentenceRepository = sentenceRepository;
        this.wordCardGenerator = wordCardGenerator;
        this.sentenceAnalyzer = sentenceAnalyzer;
        this.executor = executor;
        this.retryPolicy = new RetryPolicy(properties.llm().maxAttempts(), 1000L);
    }

    @Override
    public void dispatchWord(Level level, long wordId, String word) {
        submit(() -> generateWord(level, wordId, word), "word=" + word);
    }

    @Override
    public void dispatchSentence(Level level, long sentenceId, String sentence) {
        submit(() -> generateSentence(level, sentenceId, sentence), "sentenceId=" + sentenceId);
    }

    private void submit(Runnable task, String label) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            // 队列满。记录留在 status=生成中，由补偿任务下一轮捞起，不丢数据
            log.warn("生成队列已满，任务放弃投递，等待补偿任务处理: {}", label);
        }
    }

    private void generateWord(Level level, long wordId, String word) {
        try {
            WordCard card = retryPolicy.execute(() -> wordCardGenerator.generate(word, level));
            int affected = wordRepository.updateContentIfGenerating(level, wordId, card);
            if (affected == 0) {
                // 记录已被并发的另一次生成处理过，丢弃本次结果
                log.debug("写回被跳过（记录已处理）: level={} id={}", level, wordId);
            }
        } catch (GenerationException e) {
            log.error("单词生成最终失败: level={} id={} word={}", level, wordId, word, e);
            wordRepository.markFailed(level, wordId);
        } catch (RuntimeException e) {
            log.error("单词生成出现未预期异常: level={} id={} word={}", level, wordId, word, e);
            wordRepository.markFailed(level, wordId);
        }
    }

    private void generateSentence(Level level, long sentenceId, String sentence) {
        try {
            String analysis = retryPolicy.execute(() -> sentenceAnalyzer.analyze(sentence, level));
            sentenceRepository.updateAnalysisIfGenerating(level, sentenceId, analysis);
        } catch (GenerationException e) {
            log.error("句子分析最终失败: level={} id={}", level, sentenceId, e);
            sentenceRepository.markFailed(level, sentenceId);
        } catch (RuntimeException e) {
            log.error("句子分析出现未预期异常: level={} id={}", level, sentenceId, e);
            sentenceRepository.markFailed(level, sentenceId);
        }
    }
}
```

> `@Primary` 是为了让 Task 7 里 `WordCollectService` 注入 `GenerationDispatcher` 时拿到真实现；测试里用 `@Primary` 的替身覆盖它。

- [ ] **Step 9: 运行测试确认通过**

Run: `./mvnw test -Dtest='RetryPolicyTest,GenerationDispatcherTest'`
Expected: PASS

- [ ] **Step 10: 提交**

```bash
git add src/main/java/com/bud/wordlist/generation src/main/java/com/bud/wordlist/config/AsyncConfig.java src/main/java/com/bud/wordlist/service/GenerationDispatcher.java src/main/java/com/bud/wordlist/service/AsyncGenerationDispatcher.java src/test/java/com/bud/wordlist/generation src/test/java/com/bud/wordlist/service/GenerationDispatcherTest.java
git commit -m "feat: 生成任务的线程池投递、退避重试与幂等写回"
```

---

## Task 9: OpenAI 兼容生成器（DeepSeek / 通义 / GLM）

**Files:**
- Create: `src/main/java/com/bud/wordlist/generation/JsonExtractor.java`
- Create: `src/main/java/com/bud/wordlist/generation/openai/OpenAiCompatibleClient.java`
- Create: `src/main/java/com/bud/wordlist/generation/openai/OpenAiCompatibleWordCardGenerator.java`
- Create: `src/main/java/com/bud/wordlist/generation/openai/OpenAiCompatibleSentenceAnalyzer.java`
- Test: `src/test/java/com/bud/wordlist/generation/JsonExtractorTest.java`
- Test: `src/test/java/com/bud/wordlist/generation/OpenAiCompatibleClientTest.java`

**Interfaces:**
- Consumes: `GenerationException`、`WordCard`、`Level`、`WordlistProperties`
- Produces:
  - `JsonExtractor.extractObject(String raw) : String`（剥离围栏与前后文字，返回纯 JSON 对象串）
  - `OpenAiCompatibleClient.chat(String systemPrompt, String userPrompt, boolean jsonMode) : String`（返回模型输出的 content）
  - `OpenAiCompatibleWordCardGenerator implements WordCardGenerator`
  - `OpenAiCompatibleSentenceAnalyzer implements SentenceAnalyzer`

- [ ] **Step 1: 写失败测试 `JsonExtractorTest`**

```java
package com.bud.wordlist.generation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonExtractorTest {

    @Test
    void extracts_plain_json_unchanged() {
        assertThat(JsonExtractor.extractObject("{\"a\":1}")).isEqualTo("{\"a\":1}");
    }

    @Test
    void strips_markdown_fence() {
        String raw = """
                ```json
                {"meaning":"你好","pos":"int."}
                ```""";

        assertThat(JsonExtractor.extractObject(raw)).isEqualTo("{\"meaning\":\"你好\",\"pos\":\"int.\"}");
    }

    @Test
    void strips_fence_without_language_tag() {
        assertThat(JsonExtractor.extractObject("```\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
    }

    @Test
    void ignores_leading_and_trailing_prose() {
        String raw = "好的，这是结果：\n{\"meaning\":\"你好\"}\n希望对你有帮助！";

        assertThat(JsonExtractor.extractObject(raw)).isEqualTo("{\"meaning\":\"你好\"}");
    }

    @Test
    void handles_nested_objects() {
        String raw = "{\"a\":{\"b\":{\"c\":1}},\"d\":[1,2,3]}";

        assertThat(JsonExtractor.extractObject(raw)).isEqualTo(raw);
    }

    @Test
    void handles_braces_inside_string_literals() {
        String raw = "{\"example\":\"use {braces} here\",\"a\":1}";

        assertThat(JsonExtractor.extractObject(raw)).isEqualTo(raw);
    }

    @Test
    void rejects_input_without_any_object() {
        assertThatThrownBy(() -> JsonExtractor.extractObject("完全不是 JSON"))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("未找到 JSON");
    }

    @Test
    void rejects_unbalanced_braces() {
        assertThatThrownBy(() -> JsonExtractor.extractObject("{\"a\":1"))
                .isInstanceOf(GenerationException.class);
    }

    @Test
    void rejects_null_and_blank() {
        assertThatThrownBy(() -> JsonExtractor.extractObject(null))
                .isInstanceOf(GenerationException.class);
        assertThatThrownBy(() -> JsonExtractor.extractObject("   "))
                .isInstanceOf(GenerationException.class);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=JsonExtractorTest`
Expected: 编译失败，`cannot find symbol: class JsonExtractor`

- [ ] **Step 3: 实现 `JsonExtractor`**

必须正确跨越字符串字面量里的花括号，否则 `{"example":"use {braces} here"}` 会被截断成 `{"example":"use {braces}`。

```java
package com.bud.wordlist.generation;

/**
 * 宽容地从模型输出里抠出 JSON 对象。
 * 实测中模型经常用 ```json 围栏包裹，或在 JSON 前后附带解释性文字。
 */
public final class JsonExtractor {

    private JsonExtractor() {
    }

    public static String extractObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new GenerationException("模型返回为空", null, true);
        }
        int start = raw.indexOf('{');
        if (start < 0) {
            throw new GenerationException("未找到 JSON 对象", null, true);
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        throw new GenerationException("JSON 对象括号不闭合", null, true);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw test -Dtest=JsonExtractorTest`
Expected: PASS，9 个用例全绿

- [ ] **Step 5: 写失败测试 `OpenAiCompatibleClientTest`**（WireMock）

```java
package com.bud.wordlist.generation;

import com.bud.wordlist.generation.openai.OpenAiCompatibleClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleClientTest {

    static WireMockServer server;

    OpenAiCompatibleClient client;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        client = new OpenAiCompatibleClient(
                server.baseUrl(), "test-key", "deepseek-chat", Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private static String completionBody(String content) {
        return """
                {
                  "choices": [{"message": {"role": "assistant", "content": %s}}]
                }
                """.formatted(content == null ? "null"
                : "\"" + content.replace("\\", "\\\\").replace("\"", "\\\"")
                             .replace("\n", "\\n") + "\"");
    }

    private void stubCompletion(int status, String body) {
        server.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    @Test
    void returns_message_content_on_success() {
        stubCompletion(200, completionBody("{\"meaning\":\"你好\"}"));

        String content = client.chat("系统提示", "生成 hello 的卡片", true);

        assertThat(content).isEqualTo("{\"meaning\":\"你好\"}");
        server.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-key")));
    }

    @Test
    void sends_json_response_format_when_json_mode_requested() {
        stubCompletion(200, completionBody("{}"));

        client.chat("s", "u", true);

        server.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_object"))));
    }

    @Test
    void four_hundred_is_not_retryable() {
        stubCompletion(400, "{\"error\":{\"message\":\"bad request\"}}");

        assertThatThrownBy(() -> client.chat("s", "u", true))
                .isInstanceOf(GenerationException.class)
                .satisfies(e -> assertThat(((GenerationException) e).retryable()).isFalse());
    }

    @Test
    void server_error_is_retryable() {
        stubCompletion(500, "{\"error\":{\"message\":\"boom\"}}");

        assertThatThrownBy(() -> client.chat("s", "u", true))
                .isInstanceOf(GenerationException.class)
                .satisfies(e -> assertThat(((GenerationException) e).retryable()).isTrue());
    }

    @Test
    void rate_limit_is_retryable() {
        stubCompletion(429, "{\"error\":{\"message\":\"slow down\"}}");

        assertThatThrownBy(() -> client.chat("s", "u", true))
                .isInstanceOf(GenerationException.class)
                .satisfies(e -> assertThat(((GenerationException) e).retryable()).isTrue());
    }

    @Test
    void null_content_is_treated_as_retryable_failure() {
        stubCompletion(200, completionBody(null));

        assertThatThrownBy(() -> client.chat("s", "u", true))
                .isInstanceOf(GenerationException.class)
                .satisfies(e -> assertThat(((GenerationException) e).retryable()).isTrue());
    }

    @Test
    void malformed_json_response_is_retryable_failure() {
        stubCompletion(200, "这不是 JSON");

        assertThatThrownBy(() -> client.chat("s", "u", true))
                .isInstanceOf(GenerationException.class)
                .satisfies(e -> assertThat(((GenerationException) e).retryable()).isTrue());
    }
}
```

- [ ] **Step 6: 运行测试确认失败**

Run: `./mvnw test -Dtest=OpenAiCompatibleClientTest`
Expected: 编译失败，`cannot find symbol: class OpenAiCompatibleClient`

- [ ] **Step 7: 实现 `OpenAiCompatibleClient`**

```java
package com.bud.wordlist.generation.openai;

import com.bud.wordlist.generation.GenerationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 调 OpenAI 兼容的 /chat/completions。DeepSeek、通义千问、智谱 GLM、Kimi
 * 都用这套协议，所以一个实现覆盖多家，切供应商只改配置。
 */
public class OpenAiCompatibleClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;
    private final String model;

    public OpenAiCompatibleClient(String baseUrl, String apiKey, String model, Duration timeout) {
        this.model = model;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    public String chat(String systemPrompt, String userPrompt, boolean jsonMode) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        String raw;
        try {
            raw = http.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            // 429 与 5xx 可重试；其余 4xx 是请求本身有问题，重试只是浪费配额
            boolean retryable = status == 429 || status >= 500;
            throw new GenerationException("模型接口返回 " + status, e, retryable);
        } catch (ResourceAccessException e) {
            throw new GenerationException("调用模型接口超时或网络不可达", e, true);
        }

        return readContent(raw);
    }

    private String readContent(String raw) {
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (Exception e) {
            throw new GenerationException("模型响应不是合法 JSON", e, true);
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull() || content.asText().isBlank()) {
            throw new GenerationException("模型返回内容为空", null, true);
        }
        return content.asText();
    }
}
```

- [ ] **Step 8: 实现两个生成器**

```java
package com.bud.wordlist.generation.openai;

import com.bud.wordlist.common.Level;
import com.bud.wordlist.generation.GenerationException;
import com.bud.wordlist.generation.JsonExtractor;
import com.bud.wordlist.generation.WordCard;
import com.bud.wordlist.generation.WordCardGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class OpenAiCompatibleWordCardGenerator implements WordCardGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            你是中国英语教研老师，为备考学生制作单词卡片。只输出 JSON，不要任何解释。
            JSON 字段：
              meaning  中文意思（含词性标注，多个义项用分号分隔）
              phonetic 国际音标，用斜杠包裹，如 /həˈləʊ/
              pos      词性缩写，如 n. / v. / adj. / int.
              mnemonic 助记方法，面向中国学生，20 字以内
              example  一个英文例句，难度匹配目标考试级别
              phrase   一个常用词组搭配
            字段内容不确定时填 null。""";

    private final OpenAiCompatibleClient client;

    public OpenAiCompatibleWordCardGenerator(OpenAiCompatibleClient client) {
        this.client = client;
    }

    @Override
    public WordCard generate(String word, Level level) {
        String userPrompt = "目标考试级别：%s。请为英文单词 \"%s\" 生成卡片。"
                .formatted(level.code(), word);

        String content = client.chat(SYSTEM_PROMPT, userPrompt, true);
        String json = JsonExtractor.extractObject(content);

        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (Exception e) {
            throw new GenerationException("模型返回的 JSON 无法解析", e, true);
        }

        String meaning = text(node, "meaning");
        if (meaning == null || meaning.isBlank()) {
            // 释义是这张卡片的核心，缺了就算失败，让它重试
            throw new GenerationException("模型未返回中文意思", null, true);
        }
        return new WordCard(meaning,
                text(node, "phonetic"),
                text(node, "pos"),
                text(node, "mnemonic"),
                text(node, "example"),
                text(node, "phrase"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
```

```java
package com.bud.wordlist.generation.openai;

import com.bud.wordlist.common.Level;
import com.bud.wordlist.generation.SentenceAnalyzer;

public class OpenAiCompatibleSentenceAnalyzer implements SentenceAnalyzer {

    private static final String SYSTEM_PROMPT = """
            你是中国英语教研老师，为学生解析考试真题里的长难句。
            输出一段中文分析，覆盖：句子主干、从句与修饰成分、关键语法点、翻译。
            分段清晰，总长控制在 300 字以内。只输出分析正文，不要开场白。""";

    private final OpenAiCompatibleClient client;

    public OpenAiCompatibleSentenceAnalyzer(OpenAiCompatibleClient client) {
        this.client = client;
    }

    @Override
    public String analyze(String sentence, Level level) {
        String userPrompt = "目标考试级别：%s。请分析这句话：\n%s"
                .formatted(level.code(), sentence);

        // 句子分析是长文本，不用 json mode
        return client.chat(SYSTEM_PROMPT, userPrompt, false).trim();
    }
}
```

- [ ] **Step 9: 运行测试确认通过**

Run: `./mvnw test -Dtest='JsonExtractorTest,OpenAiCompatibleClientTest'`
Expected: PASS，16 个用例全绿

- [ ] **Step 10: 提交**

```bash
git add src/main/java/com/bud/wordlist/generation src/test/java/com/bud/wordlist/generation
git commit -m "feat: OpenAI 兼容生成器（覆盖 DeepSeek/通义/GLM/Kimi）"
```

---

## Task 10: Claude 与 Noop 生成器、按配置装配

**Files:**
- Create: `src/main/java/com/bud/wordlist/generation/claude/ClaudeClient.java`
- Create: `src/main/java/com/bud/wordlist/generation/claude/ClaudeWordCardGenerator.java`
- Create: `src/main/java/com/bud/wordlist/generation/claude/ClaudeSentenceAnalyzer.java`
- Create: `src/main/java/com/bud/wordlist/generation/noop/NoopWordCardGenerator.java`
- Create: `src/main/java/com/bud/wordlist/generation/noop/NoopSentenceAnalyzer.java`
- Create: `src/main/java/com/bud/wordlist/config/LlmConfig.java`
- Test: `src/test/java/com/bud/wordlist/config/LlmConfigTest.java`

**Interfaces:**
- Consumes: `WordlistProperties`、`WordCardGenerator`、`SentenceAnalyzer`、`WordCard`、`GenerationException`
- Produces:
  - `LlmConfig` 暴露 `WordCardGenerator` 与 `SentenceAnalyzer` 两个 Bean，按 `wordlist.llm.provider` 三选一
  - `NoopWordCardGenerator` / `NoopSentenceAnalyzer` 抛 `GenerationException(retryable=false)` —— 不重试、不写回，记录停在"生成中"

- [ ] **Step 1: 写失败测试 `LlmConfigTest`**

覆盖 Review Focus 第 5 条：`provider=openai-compatible` 但 API Key 为空时必须启动即失败。

```java
package com.bud.wordlist.config;

import com.bud.wordlist.generation.SentenceAnalyzer;
import com.bud.wordlist.generation.WordCardGenerator;
import com.bud.wordlist.generation.claude.ClaudeWordCardGenerator;
import com.bud.wordlist.generation.noop.NoopWordCardGenerator;
import com.bud.wordlist.generation.openai.OpenAiCompatibleWordCardGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmConfigTest {

    private final LlmConfig config = new LlmConfig();

    private static WordlistProperties props(String provider, String apiKey) {
        return new WordlistProperties(
                new WordlistProperties.Copy(true, java.util.Map.of()),
                new WordlistProperties.Llm(provider, "m", "https://example.com", apiKey, 30, 3),
                new WordlistProperties.Async(4, 8, 500, false, "0 0 * * * ?", 120));
    }

    @Test
    void openai_compatible_provider_yields_openai_implementations() {
        WordCardGenerator words = config.wordCardGenerator(props("openai-compatible", "sk-x"));
        SentenceAnalyzer sentences = config.sentenceAnalyzer(props("openai-compatible", "sk-x"));

        assertThat(words).isInstanceOf(OpenAiCompatibleWordCardGenerator.class);
        assertThat(sentences).isNotNull();
    }

    @Test
    void claude_provider_yields_claude_implementation() {
        WordCardGenerator words = config.wordCardGenerator(props("claude", "sk-ant-x"));

        assertThat(words).isInstanceOf(ClaudeWordCardGenerator.class);
    }

    @Test
    void none_provider_yields_noop_implementation() {
        WordCardGenerator words = config.wordCardGenerator(props("none", ""));

        assertThat(words).isInstanceOf(NoopWordCardGenerator.class);
    }

    /** Review Focus 第 5 条：缺 Key 必须启动即失败，而不是运行时静默失败 */
    @Test
    void openai_compatible_without_key_fails_fast() {
        assertThatThrownBy(() -> config.wordCardGenerator(props("openai-compatible", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");
    }

    @Test
    void claude_without_key_fails_fast() {
        assertThatThrownBy(() -> config.wordCardGenerator(props("claude", "  ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");
    }

    @Test
    void unknown_provider_fails_fast_with_actionable_message() {
        assertThatThrownBy(() -> config.wordCardGenerator(props("deepseek", "sk-x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openai-compatible")
                .hasMessageContaining("claude")
                .hasMessageContaining("none");
    }

    @Test
    void none_provider_needs_no_key() {
        assertThat(config.wordCardGenerator(props("none", null))).isNotNull();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=LlmConfigTest`
Expected: 编译失败，`cannot find symbol: class LlmConfig`

- [ ] **Step 3: 实现 Noop 生成器**

```java
package com.bud.wordlist.generation.noop;

import com.bud.wordlist.common.Level;
import com.bud.wordlist.generation.GenerationException;
import com.bud.wordlist.generation.WordCard;
import com.bud.wordlist.generation.WordCardGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 降级实现：不做任何生成。标记为不可重试，因此记录会停在"生成中"，
 * 不会进入重试与补偿循环。日后切到真实 provider 时，
 * 补偿任务会自动把积压的记录捞起来补全。
 */
public class NoopWordCardGenerator implements WordCardGenerator {

    private static final Logger log = LoggerFactory.getLogger(NoopWordCardGenerator.class);

    @Override
    public WordCard generate(String word, Level level) {
        log.info("provider=none，跳过单词生成: level={} word={}", level, word);
        throw new GenerationException("AI 生成已关闭（provider=none）", null, false);
    }
}
```

> **注意**：Noop 抛的是不可重试异常，但 `AsyncGenerationDispatcher` 捕获后会调 `markFailed`，
> 把状态置为"生成失败"——这与 spec §7.2 说的"停在生成中"不符。**修正 `AsyncGenerationDispatcher`**：
> 捕获到不可重试的 `GenerationException` 时不调 `markFailed`，直接记日志返回，让记录留在"生成中"。
> 同时 `wordlist.async.compensate-enabled` 在 `provider=none` 时必须为 `false`。
> 在 `LlmConfig` 里加一处启动校验：`provider=none` 且 `compensate-enabled=true` 时启动失败并提示。

```java
package com.bud.wordlist.generation.noop;

import com.bud.wordlist.common.Level;
import com.bud.wordlist.generation.GenerationException;
import com.bud.wordlist.generation.SentenceAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NoopSentenceAnalyzer implements SentenceAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(NoopSentenceAnalyzer.class);

    @Override
    public String analyze(String sentence, Level level) {
        log.info("provider=none，跳过句子分析: level={}", level);
        throw new GenerationException("AI 生成已关闭（provider=none）", null, false);
    }
}
```

- [ ] **Step 4: 实现 `ClaudeClient` 与两个 Claude 生成器**

结构化输出用 SDK 的 `outputConfig(Class)`，schema 由 API 侧强制，无需 JSON 容错。

```java
package com.bud.wordlist.generation.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.bud.wordlist.generation.GenerationException;
import com.bud.wordlist.generation.WordCard;

import java.time.Duration;

public class ClaudeClient {

    private static final String MODEL = "claude-opus-5";

    private final AnthropicClient client;

    public ClaudeClient(String apiKey, Duration timeout) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(timeout)
                .build();
    }

    /** 结构化输出：由 API 保证返回符合 WordCard 的 schema */
    public WordCard generateCard(String systemPrompt, String userPrompt) {
        try {
            StructuredMessageCreateParams<WordCard> params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(2048L)
                    .system(systemPrompt)
                    .outputConfig(WordCard.class)
                    .addUserMessage(userPrompt)
                    .build();

            return client.messages().create(params).content().stream()
                    .flatMap(block -> block.text().stream())
                    .findFirst()
                    .map(typed -> (WordCard) typed.text())
                    .orElseThrow(() -> new GenerationException("模型未返回内容", null, true));
        } catch (GenerationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GenerationException("调用 Claude 失败: " + e.getMessage(), e, true);
        }
    }

    /** 纯文本输出，用于句子分析 */
    public String complete(String systemPrompt, String userPrompt, long maxTokens) {
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(maxTokens)
                    .system(systemPrompt)
                    .addUserMessage(userPrompt)
                    .build();

            return client.messages().create(params).content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(text -> text.text())
                    .findFirst()
                    .filter(s -> !s.isBlank())
                    .orElseThrow(() -> new GenerationException("模型未返回内容", null, true));
        } catch (GenerationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GenerationException("调用 Claude 失败: " + e.getMessage(), e, true);
        }
    }
}
```

```java
package com.bud.wordlist.generation.claude;

import com.bud.wordlist.common.Level;
import com.bud.wordlist.generation.WordCard;
import com.bud.wordlist.generation.WordCardGenerator;

public class ClaudeWordCardGenerator implements WordCardGenerator {

    private static final String SYSTEM_PROMPT = """
            你是中国英语教研老师，为备考学生制作单词卡片。
            中文意思要含词性标注，多个义项用分号分隔；音标用斜杠包裹；
            助记面向中国学生，20 字以内；例句难度匹配目标考试级别。
            字段内容不确定时填 null。""";

    private final ClaudeClient client;

    public ClaudeWordCardGenerator(ClaudeClient client) {
        this.client = client;
    }

    @Override
    public WordCard generate(String word, Level level) {
        return client.generateCard(SYSTEM_PROMPT,
                "目标考试级别：%s。请为英文单词 \"%s\" 生成卡片。".formatted(level.code(), word));
    }
}
```

```java
package com.bud.wordlist.generation.claude;

import com.bud.wordlist.common.Level;
import com.bud.wordlist.generation.SentenceAnalyzer;

public class ClaudeSentenceAnalyzer implements SentenceAnalyzer {

    private static final String SYSTEM_PROMPT = """
            你是中国英语教研老师，为学生解析考试真题里的长难句。
            输出一段中文分析，覆盖：句子主干、从句与修饰成分、关键语法点、翻译。
            分段清晰，总长控制在 300 字以内。只输出分析正文，不要开场白。""";

    private final ClaudeClient client;

    public ClaudeSentenceAnalyzer(ClaudeClient client) {
        this.client = client;
    }

    @Override
    public String analyze(String sentence, Level level) {
        return client.complete(SYSTEM_PROMPT,
                "目标考试级别：%s。请分析这句话：\n%s".formatted(level.code(), sentence),
                2048L).trim();
    }
}
```

- [ ] **Step 5: 实现 `LlmConfig`**

```java
package com.bud.wordlist.config;

import com.bud.wordlist.generation.SentenceAnalyzer;
import com.bud.wordlist.generation.WordCardGenerator;
import com.bud.wordlist.generation.claude.ClaudeClient;
import com.bud.wordlist.generation.claude.ClaudeSentenceAnalyzer;
import com.bud.wordlist.generation.claude.ClaudeWordCardGenerator;
import com.bud.wordlist.generation.noop.NoopSentenceAnalyzer;
import com.bud.wordlist.generation.noop.NoopWordCardGenerator;
import com.bud.wordlist.generation.openai.OpenAiCompatibleClient;
import com.bud.wordlist.generation.openai.OpenAiCompatibleSentenceAnalyzer;
import com.bud.wordlist.generation.openai.OpenAiCompatibleWordCardGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class LlmConfig {

    /** openai-compatible 与 claude 都要求有 Key；缺失时启动即失败，不留到运行期 */
    @Bean
    public WordCardGenerator wordCardGenerator(WordlistProperties properties) {
        WordlistProperties.Llm cfg = properties.llm();
        String provider = cfg.provider() == null ? "" : cfg.provider().trim();

        return switch (provider) {
            case "openai-compatible" -> new OpenAiCompatibleWordCardGenerator(openAiClient(cfg));
            case "claude" -> new ClaudeWordCardGenerator(claudeClient(cfg));
            case "none" -> new NoopWordCardGenerator();
            default -> throw new IllegalStateException(
                    "不支持的 wordlist.llm.provider=\"" + provider
                            + "\"，可选值：openai-compatible / claude / none");
        };
    }

    @Bean
    public SentenceAnalyzer sentenceAnalyzer(WordlistProperties properties) {
        WordlistProperties.Llm cfg = properties.llm();
        String provider = cfg.provider() == null ? "" : cfg.provider().trim();

        return switch (provider) {
            case "openai-compatible" -> new OpenAiCompatibleSentenceAnalyzer(openAiClient(cfg));
            case "claude" -> new ClaudeSentenceAnalyzer(claudeClient(cfg));
            case "none" -> new NoopSentenceAnalyzer();
            default -> throw new IllegalStateException(
                    "不支持的 wordlist.llm.provider=\"" + provider
                            + "\"，可选值：openai-compatible / claude / none");
        };
    }

    /** provider=none 时补偿任务必须关闭，否则会反复重投永远不会推进的记录 */
    @Bean
    public String llmStartupCheck(WordlistProperties properties) {
        String provider = properties.llm().provider();
        if ("none".equals(provider) && properties.async().compensateEnabled()) {
            throw new IllegalStateException(
                    "provider=none 时 wordlist.async.compensate-enabled 必须为 false，"
                            + "否则补偿任务会空转");
        }
        return provider;
    }

    private static OpenAiCompatibleClient openAiClient(WordlistProperties.Llm cfg) {
        requireApiKey(cfg);
        return new OpenAiCompatibleClient(cfg.baseUrl(), cfg.apiKey(), cfg.model(),
                Duration.ofSeconds(cfg.timeoutSeconds()));
    }

    private static ClaudeClient claudeClient(WordlistProperties.Llm cfg) {
        requireApiKey(cfg);
        return new ClaudeClient(cfg.apiKey(), Duration.ofSeconds(cfg.timeoutSeconds()));
    }

    private static void requireApiKey(WordlistProperties.Llm cfg) {
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "wordlist.llm.provider=" + cfg.provider() + " 需要 wordlist.llm.api-key，"
                            + "请通过环境变量 LLM_API_KEY 注入");
        }
    }
}
```

- [ ] **Step 6: 修正 `AsyncGenerationDispatcher` 的失败处理**

把两个 `catch (GenerationException e)` 分支改成：

```java
} catch (GenerationException e) {
    log.error("单词生成失败: level={} id={} word={}", level, wordId, word, e);
    if (e.retryable()) {
        wordRepository.markFailed(level, wordId);
    }
    // 不可重试（如 provider=none）：不置失败，记录留在"生成中"，
    // 待日后启用 AI 时由补偿任务捞起
}
```

句子的分支同理。

**在 `GenerationDispatcherTest` 里补一个用例**，断言不可重试的失败不会把记录标成失败
（Task 8 的桩抛的是可重试异常，所以已有的 `failed_generation_marks_record_failed` 不用改）：

```java
    /** 不可重试的失败（例如 provider=none）不置失败，记录留在"生成中"，等日后启用 AI 再补 */
    @Test
    void non_retryable_failure_leaves_record_generating() throws Exception {
        long id = wordRepo.insertWord(Level.KET, "hello");
        // 用不可重试的桩替换行为：见下方 StubWordCardGenerator 增加的 nonRetryable 开关
        generator.failWithNonRetryable = true;

        dispatcher.dispatchWord(Level.KET, id, "hello");
        Thread.sleep(500);

        assertThat(wordRepo.findById(Level.KET, id).orElseThrow().status())
                .isEqualTo(GenStatus.GENERATING.code());
    }
```

相应地给 `StubWordCardGenerator` 加一个 `volatile boolean failWithNonRetryable;` 字段，
在 `fail` 判断之前插入：

```java
            if (failWithNonRetryable) {
                throw new GenerationException("桩：不可重试", null, false);
            }
```

并在 `@BeforeEach` 里把 `generator.failWithNonRetryable = false;` 一并复位。

- [ ] **Step 7: 运行测试确认通过**

Run: `./mvnw test -Dtest='LlmConfigTest,GenerationDispatcherTest'`
Expected: PASS

- [ ] **Step 8: 提交**

```bash
git add src/main/java/com/bud/wordlist/generation src/main/java/com/bud/wordlist/config src/main/java/com/bud/wordlist/service/AsyncGenerationDispatcher.java src/test/java/com/bud/wordlist/config src/test/java/com/bud/wordlist/service/GenerationDispatcherTest.java
git commit -m "feat: Claude 与 Noop 生成器、按 provider 装配、缺 Key 启动即失败"
```

---

## Task 11: 句子收录主流程

**Files:**
- Create: `src/main/java/com/bud/wordlist/service/SentenceCollectService.java`
- Create: `src/main/java/com/bud/wordlist/web/dto/CollectSentenceRequest.java`
- Test: `src/test/java/com/bud/wordlist/service/SentenceCollectServiceTest.java`

**Interfaces:**
- Consumes: `SentenceRepository`、`UserSentenceRepository`、`CopyResolver`、`WordNormalizer`、`GenerationDispatcher`
- Produces:
  - `SentenceCollectService.collect(CollectSentenceRequest) : SentenceCollectService.CollectResult`，其中 `record CollectResult(SentenceView view, boolean newlyCollected)`
  - `record CollectSentenceRequest(long userId, String level, String sentence, Integer sourceType, Long sourceId, String sourceTitle)`

- [ ] **Step 1: 写失败测试 `SentenceCollectServiceTest`**

```java
package com.bud.wordlist.service;

import com.bud.wordlist.common.GenStatus;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.config.WordlistProperties;
import com.bud.wordlist.domain.Sentence;
import com.bud.wordlist.repository.SentenceRepository;
import com.bud.wordlist.repository.UserSentenceRepository;
import com.bud.wordlist.support.FakeGenerationDispatcher;
import com.bud.wordlist.support.MySqlTestBase;
import com.bud.wordlist.web.dto.CollectSentenceRequest;
import com.bud.wordlist.web.dto.SentenceView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Import(SentenceCollectServiceTest.TestConfig.class)
class SentenceCollectServiceTest extends MySqlTestBase {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        FakeGenerationDispatcher fakeGenerationDispatcher() {
            return new FakeGenerationDispatcher();
        }

        @Bean
        @Primary
        WordlistProperties testProperties() {
            return new WordlistProperties(
                    new WordlistProperties.Copy(true, Map.of(Level.KET, List.of(Level.PET))),
                    new WordlistProperties.Llm("none", "m", "u", "", 30, 3),
                    new WordlistProperties.Async(4, 8, 500, false, "0 0 * * * ?", 120));
        }
    }

    @Autowired
    SentenceCollectService service;
    @Autowired
    SentenceRepository sentenceRepo;
    @Autowired
    UserSentenceRepository userSentenceRepo;
    @Autowired
    FakeGenerationDispatcher dispatcher;

    @BeforeEach
    void clean() {
        userSentenceRepo.deleteAllForTest();
        for (Level level : Level.values()) {
            sentenceRepo.deleteAllForTest(level);
        }
        dispatcher.sentences.clear();
    }

    @Test
    void collects_new_sentence_and_dispatches_analysis() {
        SentenceView view = service.collect(new CollectSentenceRequest(
                1001L, "ket", "He has been living here since 2019.", 1, 8001L, "真题")).view();

        assertThat(view.sentence()).isEqualTo("He has been living here since 2019.");
        assertThat(view.status()).isEqualTo(GenStatus.GENERATING.code());
        assertThat(view.hash()).hasSize(64);
        assertThat(dispatcher.sentences).hasSize(1);
    }

    /** 大小写与空白不同的同一句话必须去重成一条 */
    @Test
    void sentences_differing_only_in_case_and_whitespace_share_one_row() {
        service.collect(new CollectSentenceRequest(
                1001L, "ket", "He has been living here.", null, null, null));
        dispatcher.sentences.clear();

        service.collect(new CollectSentenceRequest(
                1002L, "ket", "  he   has\n been living here.  ", null, null, null));

        assertThat(dispatcher.sentences).isEmpty();
        assertThat(sentenceRepo.findByHash(Level.KET,
                WordNormalizer.sha256Hex("he has been living here."))).isPresent();
    }

    /** 原文原样保存，不被规范化改写 */
    @Test
    void original_sentence_text_is_preserved_verbatim() {
        String original = "  He   Has Been Living Here.  ";

        service.collect(new CollectSentenceRequest(1001L, "ket", original, null, null, null));

        Sentence stored = sentenceRepo.findByHash(Level.KET,
                WordNormalizer.sha256Hex("he has been living here.")).orElseThrow();
        assertThat(stored.sentence()).isEqualTo(original);
    }

    @Test
    void copies_analysis_from_configured_source_level_without_calling_ai() {
        long petId = sentenceRepo.insertSentence(Level.PET,
                WordNormalizer.sha256Hex("he has been living here."), "He has been living here.");
        sentenceRepo.updateAnalysisIfGenerating(Level.PET, petId, "现在完成进行时，表示从过去持续到现在。");

        SentenceView view = service.collect(new CollectSentenceRequest(
                1001L, "ket", "He has been living here.", null, null, null)).view();

        assertThat(view.status()).isEqualTo(GenStatus.DONE.code());
        assertThat(view.analysis()).isEqualTo("现在完成进行时，表示从过去持续到现在。");
        assertThat(dispatcher.sentences).isEmpty();
    }

    @Test
    void rejects_blank_sentence() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.collect(new CollectSentenceRequest(1001L, "ket", "   ", null, null, null)))
                .isInstanceOf(com.bud.wordlist.common.BizException.class);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=SentenceCollectServiceTest`
Expected: 编译失败，`cannot find symbol: class SentenceCollectService`

- [ ] **Step 3: 实现请求 DTO**

```java
package com.bud.wordlist.web.dto;

public record CollectSentenceRequest(
        long userId,
        String level,
        String sentence,
        Integer sourceType,
        Long sourceId,
        String sourceTitle) {
}
```

- [ ] **Step 4: 实现 `SentenceCollectService`**

```java
package com.bud.wordlist.service;

import com.bud.wordlist.common.BizException;
import com.bud.wordlist.common.ErrorCode;
import com.bud.wordlist.common.GenStatus;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.domain.Sentence;
import com.bud.wordlist.repository.SentenceRepository;
import com.bud.wordlist.repository.UserSentenceRepository;
import com.bud.wordlist.web.dto.CollectSentenceRequest;
import com.bud.wordlist.web.dto.SentenceView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class SentenceCollectService {

    private static final int SOURCE_TITLE_MAX = 255;

    private final SentenceRepository sentenceRepository;
    private final UserSentenceRepository userSentenceRepository;
    private final CopyResolver copyResolver;
    private final GenerationDispatcher dispatcher;

    public SentenceCollectService(SentenceRepository sentenceRepository,
                                  UserSentenceRepository userSentenceRepository,
                                  CopyResolver copyResolver,
                                  GenerationDispatcher dispatcher) {
        this.sentenceRepository = sentenceRepository;
        this.userSentenceRepository = userSentenceRepository;
        this.copyResolver = copyResolver;
        this.dispatcher = dispatcher;
    }

    /** 收录结果：视图 + 本次是否为新建关联（false 表示之前已收录，幂等重复） */
    public record CollectResult(SentenceView view, boolean newlyCollected) {
    }

    public CollectResult collect(CollectSentenceRequest request) {
        Level level = Level.fromCode(request.level())
                .orElseThrow(() -> new BizException(ErrorCode.INVALID_LEVEL, "级别不合法"));

        if (request.sentence() == null || request.sentence().isBlank()) {
            throw new BizException(ErrorCode.INVALID_SENTENCE, "句子不能为空");
        }
        if (request.userId() <= 0) {
            throw new BizException(ErrorCode.INVALID_USER, "用户id不合法");
        }

        String original = request.sentence().trim();
        String hash = WordNormalizer.sha256Hex(WordNormalizer.sentence(original));
        String sourceTitle = truncate(request.sourceTitle());

        Sentence resolved = resolveSentence(level, hash, original);
        boolean newlyCollected = userSentenceRepository.link(request.userId(), level, resolved.id(),
                request.sourceType(), request.sourceId(), sourceTitle);

        return new CollectResult(toView(resolved, sourceTitle), newlyCollected);
    }

    private Sentence resolveSentence(Level level, String hash, String original) {
        Optional<Sentence> existing = sentenceRepository.findByHash(level, hash);
        if (existing.isPresent()) {
            return existing.get();
        }
        Optional<Sentence> copied = copyFromOtherLevel(level, hash, original);
        if (copied.isPresent()) {
            return copied.get();
        }
        return insertAndDispatch(level, hash, original);
    }

    private Optional<Sentence> copyFromOtherLevel(Level level, String hash, String original) {
        for (Level source : copyResolver.sourcesFor(level)) {
            Optional<Sentence> found = sentenceRepository.findByHash(source, hash);
            if (found.isPresent() && found.get().status() == GenStatus.DONE.code()) {
                long id = sentenceRepository.insertCopiedSentence(level, hash, original, found.get());
                return sentenceRepository.findById(level, id);
            }
        }
        return Optional.empty();
    }

    private Sentence insertAndDispatch(Level level, String hash, String original) {
        long id;
        try {
            id = sentenceRepository.insertSentence(level, hash, original);
        } catch (DuplicateKeyException e) {
            return sentenceRepository.findByHash(level, hash)
                    .orElseThrow(() -> new IllegalStateException("并发插入后查不到 hash=" + hash));
        }
        dispatcher.dispatchSentence(level, id, original);
        return sentenceRepository.findById(level, id)
                .orElseThrow(() -> new IllegalStateException("插入后查不到 id=" + id));
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= SOURCE_TITLE_MAX ? s : s.substring(0, SOURCE_TITLE_MAX);
    }

    private static SentenceView toView(Sentence s, String sourceTitle) {
        return new SentenceView(s.id(), s.hash(), s.sentence(), s.status(),
                s.analysis(), sourceTitle, null);
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./mvnw test -Dtest=SentenceCollectServiceTest`
Expected: PASS，5 个用例全绿

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/bud/wordlist/service/SentenceCollectService.java src/main/java/com/bud/wordlist/web/dto/CollectSentenceRequest.java src/test/java/com/bud/wordlist/service/SentenceCollectServiceTest.java
git commit -m "feat: 句子收录主流程（hash 去重、原文原样保存）"
```

---

## Task 12: 生成失败的补偿任务

**Files:**
- Create: `src/main/java/com/bud/wordlist/service/GenerationCompensator.java`
- Test: `src/test/java/com/bud/wordlist/service/GenerationCompensatorTest.java`

**Interfaces:**
- Consumes: `WordRepository`、`SentenceRepository`、`GenerationDispatcher`、`WordlistProperties`
- Produces: `GenerationCompensator.compensate() : void` —— `@Scheduled` 入口；返回 void 便于测试直接调用

- [ ] **Step 1: 写失败测试 `GenerationCompensatorTest`**

```java
package com.bud.wordlist.service;

import com.bud.wordlist.common.GenStatus;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.config.WordlistProperties;
import com.bud.wordlist.repository.SentenceRepository;
import com.bud.wordlist.repository.WordRepository;
import com.bud.wordlist.support.FakeGenerationDispatcher;
import com.bud.wordlist.support.MySqlTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Import(GenerationCompensatorTest.TestConfig.class)
class GenerationCompensatorTest extends MySqlTestBase {

    static final int STALE_SECONDS = 0;      // 测试里把"陈旧"阈值设为 0，插入即可被扫到
    static final int MAX_ATTEMPTS = 3;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        FakeGenerationDispatcher fakeGenerationDispatcher() {
            return new FakeGenerationDispatcher();
        }

        @Bean
        @Primary
        WordlistProperties testProperties() {
            return new WordlistProperties(
                    new WordlistProperties.Copy(false, Map.of()),
                    new WordlistProperties.Llm("none", "m", "u", "", 30, MAX_ATTEMPTS),
                    new WordlistProperties.Async(4, 8, 500, true, "0 0 * * * ?", STALE_SECONDS));
        }
    }

    @Autowired
    GenerationCompensator compensator;
    @Autowired
    WordRepository wordRepo;
    @Autowired
    SentenceRepository sentenceRepo;
    @Autowired
    FakeGenerationDispatcher dispatcher;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        for (Level level : Level.values()) {
            wordRepo.deleteAllForTest(level);
            sentenceRepo.deleteAllForTest(level);
        }
        dispatcher.words.clear();
        dispatcher.sentences.clear();
    }

    @Test
    void picks_up_stale_generating_records_and_redispatches() {
        long id = wordRepo.insertWord(Level.KET, "hello");

        compensator.compensate();

        assertThat(dispatcher.words).hasSize(1);
        assertThat(dispatcher.words.get(0).id()).isEqualTo(id);
        assertThat(wordRepo.findById(Level.KET, id).orElseThrow().retryCount()).isEqualTo(2);
    }

    @Test
    void picks_up_failed_records_and_flips_them_back_to_generating() {
        long id = wordRepo.insertWord(Level.KET, "hello");
        wordRepo.markFailed(Level.KET, id);

        compensator.compensate();

        assertThat(dispatcher.words).hasSize(1);
        assertThat(wordRepo.findById(Level.KET, id).orElseThrow().status())
                .isEqualTo(GenStatus.GENERATING.code());
    }

    @Test
    void stops_redisptaching_once_max_attempts_reached() {
        long id = wordRepo.insertWord(Level.KET, "hello");
        // 手工把投递计数推到上限
        jdbc.sql("UPDATE ket_word SET retry_count = :n WHERE id = :id")
                .param("n", MAX_ATTEMPTS).param("id", id).update();

        compensator.compensate();

        assertThat(dispatcher.words).isEmpty();
    }

    @Test
    void already_done_records_are_never_touched() {
        long id = wordRepo.insertWord(Level.KET, "hello");
        wordRepo.updateContentIfGenerating(Level.KET, id,
                new com.bud.wordlist.generation.WordCard("释义", null, null, null, null, null));

        compensator.compensate();

        assertThat(dispatcher.words).isEmpty();
    }

    /** 每轮补偿把投递计数推进一格，到达上限后彻底停手 */
    @Test
    void each_round_advances_the_counter_and_stops_at_the_limit() {
        long id = wordRepo.insertWord(Level.KET, "hello");   // retry_count = 1

        compensator.compensate();
        assertThat(wordRepo.findById(Level.KET, id).orElseThrow().retryCount()).isEqualTo(2);

        compensator.compensate();
        assertThat(wordRepo.findById(Level.KET, id).orElseThrow().retryCount()).isEqualTo(3);
        assertThat(dispatcher.words).hasSize(2);

        // 第三轮：retry_count 已达上限，原子更新命中 0 行，不再投递
        compensator.compensate();
        assertThat(dispatcher.words).hasSize(2);
    }

    @Test
    void does_nothing_when_compensation_is_disabled() {
        WordlistProperties disabled = new WordlistProperties(
                new WordlistProperties.Copy(false, Map.of()),
                new WordlistProperties.Llm("none", "m", "u", "", 30, MAX_ATTEMPTS),
                new WordlistProperties.Async(4, 8, 500, false, "0 0 * * * ?", STALE_SECONDS));
        GenerationCompensator off = new GenerationCompensator(
                wordRepo, sentenceRepo, dispatcher, disabled);
        wordRepo.insertWord(Level.KET, "hello");

        off.compensate();

        assertThat(dispatcher.words).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=GenerationCompensatorTest`
Expected: 编译失败，`cannot find symbol: class GenerationCompensator`

- [ ] **Step 3: 实现 `GenerationCompensator`**

```java
package com.bud.wordlist.service;

import com.bud.wordlist.common.Level;
import com.bud.wordlist.config.WordlistProperties;
import com.bud.wordlist.domain.Sentence;
import com.bud.wordlist.domain.Word;
import com.bud.wordlist.repository.SentenceRepository;
import com.bud.wordlist.repository.WordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 生成失败的兜底。处理两类记录：
 *   1. 疑似任务丢失——长时间停在"生成中"（进程重启、队列拒绝、任务异常逃逸）
 *   2. 待重试——已标为"生成失败"但还有重试额度
 *
 * 投递前先做一次原子 UPDATE 把记录翻回"生成中"并递增计数，
 * 只有更新成功（affected_rows=1）才真正投递。
 */
@Component
public class GenerationCompensator {

    private static final Logger log = LoggerFactory.getLogger(GenerationCompensator.class);

    private final WordRepository wordRepository;
    private final SentenceRepository sentenceRepository;
    private final GenerationDispatcher dispatcher;
    private final WordlistProperties properties;
    /** 单实例部署下的重入锁，防止上一次还没跑完下一次就触发 */
    private final ReentrantLock lock = new ReentrantLock();

    public GenerationCompensator(WordRepository wordRepository,
                                 SentenceRepository sentenceRepository,
                                 GenerationDispatcher dispatcher,
                                 WordlistProperties properties) {
        this.wordRepository = wordRepository;
        this.sentenceRepository = sentenceRepository;
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Scheduled(cron = "${wordlist.async.compensate-cron:0 */5 * * * ?}")
    public void scheduledCompensate() {
        if (!properties.async().compensateEnabled()) {
            return;
        }
        compensate();
    }

    public void compensate() {
        if (!lock.tryLock()) {
            log.warn("上一次补偿任务尚未结束，跳过本次");
            return;
        }
        try {
            int maxAttempts = properties.llm().maxAttempts();
            int staleSeconds = properties.async().staleThresholdSeconds();

            for (Level level : Level.values()) {
                for (Word word : wordRepository.findStale(level, staleSeconds)) {
                    retryWord(level, word, maxAttempts);
                }
                for (Word word : wordRepository.findFailed(level, maxAttempts)) {
                    retryWord(level, word, maxAttempts);
                }
                for (Sentence sentence : sentenceRepository.findStale(level, staleSeconds)) {
                    retrySentence(level, sentence, maxAttempts);
                }
                for (Sentence sentence : sentenceRepository.findFailed(level, maxAttempts)) {
                    retrySentence(level, sentence, maxAttempts);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void retryWord(Level level, Word word, int maxAttempts) {
        if (wordRepository.resetToGeneratingIfRetryable(level, word.id(), maxAttempts) == 1) {
            log.info("补偿投递单词生成: level={} id={} word={} 已投递次数={}",
                    level, word.id(), word.word(), word.retryCount() + 1);
            dispatcher.dispatchWord(level, word.id(), word.word());
        }
    }

    private void retrySentence(Level level, Sentence sentence, int maxAttempts) {
        if (sentenceRepository.resetToGeneratingIfRetryable(level, sentence.id(), maxAttempts) == 1) {
            log.info("补偿投递句子分析: level={} id={} 已投递次数={}",
                    level, sentence.id(), sentence.retryCount() + 1);
            dispatcher.dispatchSentence(level, sentence.id(), sentence.sentence());
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw test -Dtest=GenerationCompensatorTest`
Expected: PASS，6 个用例全绿

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bud/wordlist/service/GenerationCompensator.java src/test/java/com/bud/wordlist/service/GenerationCompensatorTest.java
git commit -m "feat: 生成失败的定时补偿任务"
```

---

## Task 13: Web 接口层

**Files:**
- Create: `src/main/java/com/bud/wordlist/web/dto/DeleteCollectRequest.java`
- Create: `src/main/java/com/bud/wordlist/web/CollectController.java`
- Create: `src/main/java/com/bud/wordlist/web/WordBankController.java`
- Test: `src/test/java/com/bud/wordlist/web/CollectControllerTest.java`

**Interfaces:**
- Consumes: `WordCollectService`、`SentenceCollectService`、`WordRepository`、`SentenceRepository`、`UserWordRepository`、`UserSentenceRepository`、`ApiResponse`
- Produces: 5 个 HTTP 端点，契约见 spec §8

- [ ] **Step 1: 写失败测试 `CollectControllerTest`**

```java
package com.bud.wordlist.web;

import com.bud.wordlist.common.ErrorCode;
import com.bud.wordlist.common.GenStatus;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.repository.SentenceRepository;
import com.bud.wordlist.repository.UserSentenceRepository;
import com.bud.wordlist.repository.UserWordRepository;
import com.bud.wordlist.repository.WordRepository;
import com.bud.wordlist.support.FakeGenerationDispatcher;
import com.bud.wordlist.support.MySqlTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(CollectControllerTest.TestConfig.class)
class CollectControllerTest extends MySqlTestBase {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        FakeGenerationDispatcher fakeGenerationDispatcher() {
            return new FakeGenerationDispatcher();
        }
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    WordRepository wordRepo;
    @Autowired
    SentenceRepository sentenceRepo;
    @Autowired
    UserWordRepository userWordRepo;
    @Autowired
    UserSentenceRepository userSentenceRepo;

    @BeforeEach
    void clean() {
        userWordRepo.deleteAllForTest();
        userSentenceRepo.deleteAllForTest();
        for (Level level : Level.values()) {
            wordRepo.deleteAllForTest(level);
            sentenceRepo.deleteAllForTest(level);
        }
    }

    @Test
    void collect_word_returns_view_with_generating_status() throws Exception {
        mockMvc.perform(post("/api/collect/word")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1001,"level":"ket","word":"Hello",
                                 "sourceType":1,"sourceId":8001,"sourceTitle":"2024 KET"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.OK))
                .andExpect(jsonPath("$.data.word").value("hello"))
                .andExpect(jsonPath("$.data.status").value(GenStatus.GENERATING.code()))
                .andExpect(jsonPath("$.data.newlyCollected").value(true))
                .andExpect(jsonPath("$.data.meaning").doesNotExist());
    }

    @Test
    void collect_word_is_idempotent_and_reports_not_newly_collected() throws Exception {
        String body = """
                {"userId":1001,"level":"ket","word":"hello"}
                """;
        mockMvc.perform(post("/api/collect/word").contentType(MediaType.APPLICATION_JSON).content(body));

        mockMvc.perform(post("/api/collect/word").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newlyCollected").value(false));
    }

    @Test
    void invalid_level_returns_business_code_1001() throws Exception {
        mockMvc.perform(post("/api/collect/word")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1001,"level":"ketx","word":"hello"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_LEVEL));
    }

    @Test
    void level_with_surrounding_space_and_uppercase_is_accepted() throws Exception {
        mockMvc.perform(post("/api/collect/word")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1001,"level":"  KET  ","word":"hello"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.OK));
    }

    /** Review Focus 第 1 条：超长单词必须 400，不能落库成截断的 64 字符 */
    @Test
    void oversized_word_returns_business_code_1002() throws Exception {
        mockMvc.perform(post("/api/collect/word")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1001,"level":"ket","word":"%s"}
                                """.formatted("a".repeat(200))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_WORD));
    }

    @Test
    void garbage_word_returns_business_code_1002() throws Exception {
        mockMvc.perform(post("/api/collect/word")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1001,"level":"ket","word":"3d model 😀"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_WORD));
    }

    @Test
    void collect_sentence_returns_hash_and_generating_status() throws Exception {
        mockMvc.perform(post("/api/collect/sentence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1001,"level":"ket","sentence":"He has been living here."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hash").isString())
                .andExpect(jsonPath("$.data.status").value(GenStatus.GENERATING.code()));
    }

    @Test
    void delete_removes_link_and_is_idempotent() throws Exception {
        mockMvc.perform(post("/api/collect/word")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1001,"level":"ket","word":"hello"}
                                """));
        long wordId = wordRepo.findByWord(Level.KET, "hello").orElseThrow().id();

        mockMvc.perform(delete("/api/collect/word")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1001,"level":"ket","wordId":%d}
                                """.formatted(wordId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed").value(true));

        mockMvc.perform(delete("/api/collect/word")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1001,"level":"ket","wordId":%d}
                                """.formatted(wordId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed").value(false));
    }

    @Test
    void delete_does_not_touch_another_users_link() throws Exception {
        mockMvc.perform(post("/api/collect/word")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1001,"level":"ket","word":"hello"}
                                """));
        long wordId = wordRepo.findByWord(Level.KET, "hello").orElseThrow().id();

        mockMvc.perform(delete("/api/collect/word")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":9999,"level":"ket","wordId":%d}
                                """.formatted(wordId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed").value(false));

        mockMvc.perform(get("/api/collect/words")
                        .param("userId", "1001").param("level", "ket")
                        .param("page", "1").param("size", "10"))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void word_list_pages_and_joins_content() throws Exception {
        mockMvc.perform(post("/api/collect/word")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"userId":1001,"level":"ket","word":"hello","sourceTitle":"第一篇"}
                        """));

        mockMvc.perform(get("/api/collect/words")
                        .param("userId", "1001").param("level", "ket")
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list", hasSize(1)))
                .andExpect(jsonPath("$.data.list[0].word").value("hello"))
                .andExpect(jsonPath("$.data.list[0].sourceTitle").value("第一篇"));
    }

    @Test
    void word_list_requires_level() throws Exception {
        mockMvc.perform(get("/api/collect/words")
                        .param("userId", "1001").param("page", "1").param("size", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_LEVEL));
    }

    @Test
    void word_detail_by_word_id() throws Exception {
        mockMvc.perform(post("/api/collect/word")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"userId":1001,"level":"ket","word":"hello"}
                        """));
        long wordId = wordRepo.findByWord(Level.KET, "hello").orElseThrow().id();

        mockMvc.perform(get("/api/word/detail")
                        .param("level", "ket").param("wordId", String.valueOf(wordId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.word").value("hello"));
    }

    @Test
    void word_detail_by_word_string() throws Exception {
        mockMvc.perform(post("/api/collect/word")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"userId":1001,"level":"ket","word":"hello"}
                        """));

        mockMvc.perform(get("/api/word/detail").param("level", "ket").param("word", "Hello"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.word").value("hello"));
    }

    @Test
    void word_detail_returns_not_found_for_missing_word() throws Exception {
        mockMvc.perform(get("/api/word/detail").param("level", "ket").param("word", "zzzz"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=CollectControllerTest`
Expected: 全部 404 或编译失败

- [ ] **Step 3: 实现 `DeleteCollectRequest`**

```java
package com.bud.wordlist.web.dto;

/** 取消收录的请求体。wordId / sentenceId 二者用其一 */
public record DeleteCollectRequest(long userId, String level, Long wordId, Long sentenceId) {
}
```

- [ ] **Step 4: 实现 `CollectController`**

```java
package com.bud.wordlist.web;

import com.bud.wordlist.common.ApiResponse;
import com.bud.wordlist.common.BizException;
import com.bud.wordlist.common.ErrorCode;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.repository.UserSentenceRepository;
import com.bud.wordlist.repository.UserWordRepository;
import com.bud.wordlist.service.SentenceCollectService;
import com.bud.wordlist.service.WordCollectService;
import com.bud.wordlist.web.dto.CollectSentenceRequest;
import com.bud.wordlist.web.dto.CollectWordRequest;
import com.bud.wordlist.web.dto.DeleteCollectRequest;
import com.bud.wordlist.web.dto.PageResult;
import com.bud.wordlist.web.dto.SentenceView;
import com.bud.wordlist.web.dto.WordView;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/collect")
public class CollectController {

    private static final int MAX_PAGE_SIZE = 100;

    private final WordCollectService wordCollectService;
    private final SentenceCollectService sentenceCollectService;
    private final UserWordRepository userWordRepository;
    private final UserSentenceRepository userSentenceRepository;

    public CollectController(WordCollectService wordCollectService,
                             SentenceCollectService sentenceCollectService,
                             UserWordRepository userWordRepository,
                             UserSentenceRepository userSentenceRepository) {
        this.wordCollectService = wordCollectService;
        this.sentenceCollectService = sentenceCollectService;
        this.userWordRepository = userWordRepository;
        this.userSentenceRepository = userSentenceRepository;
    }

    @PostMapping("/word")
    public ApiResponse<CollectedWordView> collectWord(@RequestBody CollectWordRequest request) {
        WordCollectService.CollectResult result = wordCollectService.collect(request);
        WordView view = result.view();
        return ApiResponse.ok(new CollectedWordView(
                view.wordId(), view.word(), requireLevel(request.level()).code(), view.status(),
                result.newlyCollected(), view.meaning(), view.phonetic(), view.pos(),
                view.mnemonic(), view.example(), view.phrase()));
    }

    @PostMapping("/sentence")
    public ApiResponse<CollectedSentenceView> collectSentence(
            @RequestBody CollectSentenceRequest request) {
        SentenceCollectService.CollectResult result = sentenceCollectService.collect(request);
        SentenceView view = result.view();
        return ApiResponse.ok(new CollectedSentenceView(
                view.sentenceId(), view.hash(), view.sentence(),
                requireLevel(request.level()).code(), view.status(),
                result.newlyCollected(), view.analysis()));
    }

    @DeleteMapping("/word")
    public ApiResponse<Map<String, Boolean>> deleteWord(@RequestBody DeleteCollectRequest request) {
        Level level = requireLevel(request.level());
        requireWordId(request.wordId());
        boolean removed = userWordRepository.unlink(request.userId(), level, request.wordId());
        return ApiResponse.ok(Map.of("removed", removed));
    }

    @DeleteMapping("/sentence")
    public ApiResponse<Map<String, Boolean>> deleteSentence(@RequestBody DeleteCollectRequest request) {
        Level level = requireLevel(request.level());
        if (request.sentenceId() == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "sentenceId 必传");
        }
        boolean removed = userSentenceRepository.unlink(request.userId(), level, request.sentenceId());
        return ApiResponse.ok(Map.of("removed", removed));
    }

    @GetMapping("/words")
    public ApiResponse<PageResult<WordView>> listWords(@RequestParam long userId,
                                                       @RequestParam(required = false) String level,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        Level lv = requireLevel(level);
        return ApiResponse.ok(userWordRepository.pageByUser(userId, lv, normalizePage(page),
                normalizeSize(size)));
    }

    @GetMapping("/sentences")
    public ApiResponse<PageResult<SentenceView>> listSentences(
            @RequestParam long userId,
            @RequestParam(required = false) String level,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Level lv = requireLevel(level);
        return ApiResponse.ok(userSentenceRepository.pageByUser(userId, lv, normalizePage(page),
                normalizeSize(size)));
    }

    private static Level requireLevel(String level) {
        return Level.fromCode(level)
                .orElseThrow(() -> new BizException(ErrorCode.INVALID_LEVEL, "级别不合法"));
    }

    private static void requireWordId(Long wordId) {
        if (wordId == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "wordId 必传");
        }
    }

    private static int normalizePage(int page) {
        return Math.max(1, page);
    }

    private static int normalizeSize(int size) {
        return Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    }

    /** 收录接口的响应结构：在 WordView 基础上加 level 与 newlyCollected */
    public record CollectedWordView(long wordId, String word, String level, int status,
                                    boolean newlyCollected, String meaning, String phonetic,
                                    String pos, String mnemonic, String example, String phrase) {
    }

    public record CollectedSentenceView(long sentenceId, String hash, String sentence,
                                        String level, int status, boolean newlyCollected,
                                        String analysis) {
    }
}
```

> `newlyCollected` 直接来自 `CollectResult`，不是硬编码——它对前端有意义（可以用来决定是否播"已收录"提示），必须有真实值。
> `requireLevel(...)` 在 Controller 里再调一次只是为了拿到规范化后的 `level.code()`（`"  KET  "` → `"ket"`）；
> 非法值仍然由 Service 先抛出 400。

- [ ] **Step 5: 实现 `WordBankController`**

```java
package com.bud.wordlist.web;

import com.bud.wordlist.common.ApiResponse;
import com.bud.wordlist.common.BizException;
import com.bud.wordlist.common.ErrorCode;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.domain.Word;
import com.bud.wordlist.repository.WordRepository;
import com.bud.wordlist.service.WordNormalizer;
import com.bud.wordlist.web.dto.WordView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/word")
public class WordBankController {

    private final WordRepository wordRepository;

    public WordBankController(WordRepository wordRepository) {
        this.wordRepository = wordRepository;
    }

    @GetMapping("/detail")
    public ApiResponse<WordView> detail(@RequestParam(required = false) String level,
                                        @RequestParam(required = false) Long wordId,
                                        @RequestParam(required = false) String word) {
        Level lv = Level.fromCode(level)
                .orElseThrow(() -> new BizException(ErrorCode.INVALID_LEVEL, "级别不合法"));

        Word found;
        if (wordId != null) {
            found = wordRepository.findById(lv, wordId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "单词不存在"));
        } else if (word != null && !word.isBlank()) {
            String normalized = WordNormalizer.word(word);
            found = wordRepository.findByWord(lv, normalized)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "单词不存在"));
        } else {
            throw new BizException(ErrorCode.INVALID_PARAM, "wordId 与 word 至少传一个");
        }

        return ApiResponse.ok(new WordView(found.id(), found.word(), found.status(),
                found.meaning(), found.phonetic(), found.pos(), found.mnemonic(),
                found.example(), found.phrase(), null, null));
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `./mvnw test -Dtest=CollectControllerTest`
Expected: PASS，14 个用例全绿

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/bud/wordlist/web src/test/java/com/bud/wordlist/web
git commit -m "feat: 收录/取消/列表/详情 HTTP 接口"
```

---

## Task 14: 端到端验收

**Files:**
- Test: `src/test/java/com/bud/wordlist/EndToEndAcceptanceTest.java`

**Interfaces:**
- Consumes: 全部前面的产出
- Produces: 无（终态验证）

- [ ] **Step 1: 写验收测试**

用真实线程池 + 打桩的生成器，跑完一遍完整链路。

```java
package com.bud.wordlist;

import com.bud.wordlist.common.GenStatus;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.generation.SentenceAnalyzer;
import com.bud.wordlist.generation.WordCard;
import com.bud.wordlist.generation.WordCardGenerator;
import com.bud.wordlist.repository.SentenceRepository;
import com.bud.wordlist.repository.UserSentenceRepository;
import com.bud.wordlist.repository.UserWordRepository;
import com.bud.wordlist.repository.WordRepository;
import com.bud.wordlist.support.MySqlTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(EndToEndAcceptanceTest.TestConfig.class)
class EndToEndAcceptanceTest extends MySqlTestBase {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        RecordingWordCardGenerator recordingWordCardGenerator() {
            return new RecordingWordCardGenerator();
        }

        @Bean
        @Primary
        RecordingSentenceAnalyzer recordingSentenceAnalyzer() {
            return new RecordingSentenceAnalyzer();
        }
    }

    static class RecordingWordCardGenerator implements WordCardGenerator {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public WordCard generate(String word, Level level) {
            calls.incrementAndGet();
            return new WordCard("释义-" + word, "/" + word + "/", "n.", "助记", "例句", "词组");
        }
    }

    static class RecordingSentenceAnalyzer implements SentenceAnalyzer {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String analyze(String sentence, Level level) {
            calls.incrementAndGet();
            return "分析-" + sentence;
        }
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    WordRepository wordRepo;
    @Autowired
    SentenceRepository sentenceRepo;
    @Autowired
    UserWordRepository userWordRepo;
    @Autowired
    UserSentenceRepository userSentenceRepo;
    @Autowired
    RecordingWordCardGenerator generator;
    @Autowired
    RecordingSentenceAnalyzer analyzer;

    @BeforeEach
    void clean() {
        userWordRepo.deleteAllForTest();
        userSentenceRepo.deleteAllForTest();
        for (Level level : Level.values()) {
            wordRepo.deleteAllForTest(level);
            sentenceRepo.deleteAllForTest(level);
        }
    }

    /** 收录接口必须在 100ms 内返回，绝不等 AI */
    @Test
    void collect_returns_immediately_even_though_generation_is_async() throws Exception {
        long start = System.nanoTime();

        mockMvc.perform(post("/api/collect/word")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1001,"level":"ket","word":"hello"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(GenStatus.GENERATING.code()));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(5000);   // 含测试框架开销，给足余量
    }

    /** 完整链路：收录 → 异步生成 → 状态变已完成 → 内容可见 */
    @Test
    void full_round_trip_from_collect_to_generated_content() throws Exception {
        mockMvc.perform(post("/api/collect/word")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"userId":1001,"level":"ket","word":"hello"}
                        """));

        awaitWordStatus(Level.KET, "hello", GenStatus.DONE);

        assertThat(wordRepo.findByWord(Level.KET, "hello").orElseThrow().meaning())
                .isEqualTo("释义-hello");
    }

    /** 多用户并发收录同一个新词，词表只应有一行，生成只应调用一次 */
    @Test
    void concurrent_collection_of_the_same_new_word_creates_one_row() throws Exception {
        int threads = 8;
        Thread[] pool = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final long userId = 2000L + i;
            pool[i] = new Thread(() -> {
                try {
                    mockMvc.perform(post("/api/collect/word")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"userId":%d,"level":"ket","word":"simultaneous"}
                                    """.formatted(userId)));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            pool[i].start();
        }
        for (Thread t : pool) {
            t.join();
        }

        awaitWordStatus(Level.KET, "simultaneous", GenStatus.DONE);

        Long rows = wordRepo.findByWord(Level.KET, "simultaneous")
                .map(w -> 1L).orElse(0L);
        assertThat(rows).isEqualTo(1L);
        assertThat(generator.calls).hasValue(1);   // 只生成一次
    }

    /** 句子链路：收录 → 分析生成 → 幂等重复收录 */
    @Test
    void sentence_round_trip_is_idempotent_across_users() throws Exception {
        String body = """
                {"userId":1001,"level":"ket","sentence":"He has been living here since 2019."}
                """;
        mockMvc.perform(post("/api/collect/sentence")
                .contentType(MediaType.APPLICATION_JSON).content(body));

        mockMvc.perform(post("/api/collect/sentence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1002,"level":"ket","sentence":"he  has been living here since 2019."}
                                """))
                .andExpect(status().isOk());

        awaitSentenceStatus(Level.KET, GenStatus.DONE);

        assertThat(analyzer.calls).hasValue(1);
        Long rows = sentenceRepo.findByHash(Level.KET,
                com.bud.wordlist.service.WordNormalizer
                        .sha256Hex("he has been living here since 2019."))
                .map(s -> 1L).orElse(0L);
        assertThat(rows).isEqualTo(1L);
    }

    private void awaitWordStatus(Level level, String word, GenStatus expected) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer status = wordRepo.findByWord(level, word).map(w -> w.status()).orElse(null);
            if (status != null && status == expected.code()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待单词状态超时: " + word);
    }

    private void awaitSentenceStatus(Level level, GenStatus expected) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            boolean done = sentenceRepo.findStale(level, 0).isEmpty();
            if (done) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待句子状态超时");
    }
}
```

- [ ] **Step 2: 运行验收测试**

Run: `./mvnw test -Dtest=EndToEndAcceptanceTest`
Expected: PASS，4 个用例全绿

- [ ] **Step 3: 跑全量测试**

Run: `./mvnw clean test`
Expected: 全部 PASS。把输出贴出来——**任何失败都要先修好再提交，不要带着红测试往下走**。

- [ ] **Step 4: 提交**

```bash
git add src/test/java/com/bud/wordlist/EndToEndAcceptanceTest.java
git commit -m "test: 端到端验收测试（立即返回、并发去重、完整链路）"
```

---

## 完成标准

全部任务完成后，以下断言必须成立：

- [ ] `./mvnw clean test` 全绿
- [ ] 10 张表由 Flyway 在空库上自动建出（`SchemaSmokeTest` 覆盖）
- [ ] 收录接口在 AI 未返回时就返回成功，且 `status=0`（`EndToEndAcceptanceTest` 覆盖）
- [ ] 同一个新词被 8 个并发请求收录，词表只有一行、AI 只调用一次
- [ ] 拷贝链命中时不调用 AI；开关关闭时一定调用 AI
- [ ] 跨级别同名单词各存各表，互不干扰
- [ ] 超长/非法单词返回 400 业务码，不落库
- [ ] `provider` 三选一切换正常；缺 API Key 时启动即失败
- [ ] `provider=none` 时补偿任务不会空转

## 遗留与后续

- spec §14 的三个待确认项（ielts/zhongkao 拷贝来源、`source_type` 取值、单词校验严格程度）需要产品侧拍板。本计划按"数字与点号开头的词一律拒绝"实现，若词库里要收 `e.g.` / `3D`，改 Task 2 的 `WORD_PATTERN` 即可
- 部署脚本、`application-prod.yml` 的实际环境变量、ECS 上的建库操作不在本计划范围内，需要拿到 MySQL 连接信息后单独处理
- 句子的"详情"接口按 spec §8.7 暂不提供，列表接口一次性返回
- 词库的人工编辑后台是独立子系统，另开 spec

## 自查记录

写完计划后对照 spec 复查，修掉了 6 个会在实施阶段直接炸掉的问题：

1. **`findStale` 的 SQL 用了 `INTERVAL :param`** —— 占位符放进 `INTERVAL` 表达式各驱动行为不一致，改为在 Java 侧算好截断时间再传参
2. **`AsyncGenerationDispatcher` 与测试替身都标了 `@Primary`** —— Spring 遇到两个 primary bean 会启动失败。生产实现本来就唯一，去掉它的 `@Primary`，让测试替身胜出
3. **`WordCollectService.toView` 把 `sourceTitle` 写死成 null** —— 而 Task 7 的测试断言它返回截断后的标题，必然失败。改为把截断后的标题传进视图
4. **`newlyCollected` 在 Controller 里硬编码 `true`** —— 幂等重复收录时前端会收到错误的"新收录"信号。改为由 `CollectResult` 从 `userWordRepository.link` 的返回值透传
5. **Task 8 的失败测试断言调用次数为 1，但桩抛的是可重试异常** —— 重试 3 次后断言必然失败。把测试配置的 `maxAttempts` 设为 1，让该用例只验证"失败落库"，重试次数交给 `RetryPolicyTest` 覆盖
6. **Task 5 用"同构，照 Task 4 改"带过 `UserSentenceRepository`** —— 实施者可能跳着读任务，必须给出完整代码
