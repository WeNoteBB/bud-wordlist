# wordlist 生词本/好句本 接口说明

基于 docs 下设计文档实现，并按需求扩展了「句子 + 句子翻译 + 句子 hash 去重」：

## 需求扩展
- 单词表新增 `sentence_hash` 列，关联同级别句子表的 hash。
- 句子表新增 `sentence_translation`（句子翻译）列。
- 收录单词时若带句子：先按句子的 SHA-256(hash) 查句子表，存在则复用，不存在则存入句子表（按 hash 去重），并把 hash 关联到单词表 →「把单词放进句子里记忆」。

## 连接信息（已缓存到 deploy-mysql-info.md）
- MySQL: `8.138.161.122:3306`，库 `bud`，账号 `zhang`
- 建表脚本：`sql/schema.sql`（已在 bud 库执行，10 张表）

## 接口
统一响应：`{"code":0,"msg":"ok","data":{...}}`

- `POST /api/collect/word` 收录单词
  请求：`{ "userId":1001, "level":"ket", "word":"hello", "sentence":"Hello world.", "sentenceTranslation":"你好，世界。", "sourceTitle":"..." }`
- `POST /api/collect/sentence` 收录句子
- `DELETE /api/collect/word` 取消收录 `{ "userId":1001,"level":"ket","wordId":3 }`
- `DELETE /api/collect/sentence` 取消收录
- `GET /api/collect/words?userId=1001&level=ket&page=1&size=20` 生词本列表（含句子/翻译）
- `GET /api/collect/sentences?userId=1001&level=ket&page=1&size=20` 好句本列表
- `GET /api/word/detail?level=ket&wordId=2` 单词详情

## 级别
`ket` / `pet` / `zhongkao` / `ielts`，对应表 `ket_word`... `ielts_sentence`。

## 运行
```
java -jar target/wordlist-0.0.1-SNAPSHOT.jar --server.port=8080
```
配置见 `application.yml`（数据库连接、`wordlist.llm.provider` 等）。
AI 生成默认 `provider=none`（不调 AI，单词 status 保持 0）；接入 DeepSeek/通义/GLM 等改为 `openai-compatible` 并配置 `LLM_API_KEY` 即可。