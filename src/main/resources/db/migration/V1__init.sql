-- ============================================================
-- wordlist 建表脚本（含用户需求扩展）
-- 单词表新增 sentence_hash（关联句子表 hash）
-- 句子表新增 sentence_translation（句子翻译）
-- 数据库：bud（在目标实例上先 CREATE DATABASE bud ...）
-- ============================================================

-- ---------- KET 词表 ----------
CREATE TABLE IF NOT EXISTS ket_word (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  word              VARCHAR(64)  NOT NULL COLLATE utf8mb4_bin,
  meaning           TEXT         NULL,
  phonetic          VARCHAR(128) NULL,
  pos               VARCHAR(64)  NULL,
  mnemonic          TEXT         NULL,
  example           TEXT         NULL,
  phrase            TEXT         NULL,
  sentence_hash     CHAR(64)     NULL COMMENT '关联同级别句子表的 hash',
  status            TINYINT      NOT NULL DEFAULT 0,
  source            TINYINT      NOT NULL DEFAULT 1,
  retry_count       TINYINT      NOT NULL DEFAULT 0,
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_word (word),
  KEY idx_status_updated (status, updated_at),
  KEY idx_sentence_hash (sentence_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='KET 单词表';

-- ---------- PET 词表 ----------
CREATE TABLE IF NOT EXISTS pet_word (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  word              VARCHAR(64)  NOT NULL COLLATE utf8mb4_bin,
  meaning           TEXT         NULL,
  phonetic          VARCHAR(128) NULL,
  pos               VARCHAR(64)  NULL,
  mnemonic          TEXT         NULL,
  example           TEXT         NULL,
  phrase            TEXT         NULL,
  sentence_hash     CHAR(64)     NULL,
  status            TINYINT      NOT NULL DEFAULT 0,
  source            TINYINT      NOT NULL DEFAULT 1,
  retry_count       TINYINT      NOT NULL DEFAULT 0,
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_word (word),
  KEY idx_status_updated (status, updated_at),
  KEY idx_sentence_hash (sentence_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='PET 单词表';

-- ---------- 中考词表 ----------
CREATE TABLE IF NOT EXISTS zhongkao_word (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  word              VARCHAR(64)  NOT NULL COLLATE utf8mb4_bin,
  meaning           TEXT         NULL,
  phonetic          VARCHAR(128) NULL,
  pos               VARCHAR(64)  NULL,
  mnemonic          TEXT         NULL,
  example           TEXT         NULL,
  phrase            TEXT         NULL,
  sentence_hash     CHAR(64)     NULL,
  status            TINYINT      NOT NULL DEFAULT 0,
  source            TINYINT      NOT NULL DEFAULT 1,
  retry_count       TINYINT      NOT NULL DEFAULT 0,
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_word (word),
  KEY idx_status_updated (status, updated_at),
  KEY idx_sentence_hash (sentence_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='中考单词表';

-- ---------- 雅思词表 ----------
CREATE TABLE IF NOT EXISTS ielts_word (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  word              VARCHAR(64)  NOT NULL COLLATE utf8mb4_bin,
  meaning           TEXT         NULL,
  phonetic          VARCHAR(128) NULL,
  pos               VARCHAR(64)  NULL,
  mnemonic          TEXT         NULL,
  example           TEXT         NULL,
  phrase            TEXT         NULL,
  sentence_hash     CHAR(64)     NULL,
  status            TINYINT      NOT NULL DEFAULT 0,
  source            TINYINT      NOT NULL DEFAULT 1,
  retry_count       TINYINT      NOT NULL DEFAULT 0,
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_word (word),
  KEY idx_status_updated (status, updated_at),
  KEY idx_sentence_hash (sentence_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='雅思单词表';

-- ---------- KET 句子表 ----------
CREATE TABLE IF NOT EXISTS ket_sentence (
  id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  hash                  CHAR(64)     NOT NULL COLLATE utf8mb4_bin,
  sentence              TEXT         NOT NULL,
  sentence_translation  TEXT         NULL COMMENT '句子翻译',
  analysis              TEXT         NULL,
  status                TINYINT      NOT NULL DEFAULT 0,
  source                TINYINT      NOT NULL DEFAULT 1,
  retry_count           TINYINT      NOT NULL DEFAULT 0,
  created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_hash (hash),
  KEY idx_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='KET 句子表';

-- ---------- PET 句子表 ----------
CREATE TABLE IF NOT EXISTS pet_sentence (
  id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  hash                  CHAR(64)     NOT NULL COLLATE utf8mb4_bin,
  sentence              TEXT         NOT NULL,
  sentence_translation  TEXT         NULL,
  analysis              TEXT         NULL,
  status                TINYINT      NOT NULL DEFAULT 0,
  source                TINYINT      NOT NULL DEFAULT 1,
  retry_count           TINYINT      NOT NULL DEFAULT 0,
  created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_hash (hash),
  KEY idx_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='PET 句子表';

-- ---------- 中考句子表 ----------
CREATE TABLE IF NOT EXISTS zhongkao_sentence (
  id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  hash                  CHAR(64)     NOT NULL COLLATE utf8mb4_bin,
  sentence              TEXT         NOT NULL,
  sentence_translation  TEXT         NULL,
  analysis              TEXT         NULL,
  status                TINYINT      NOT NULL DEFAULT 0,
  source                TINYINT      NOT NULL DEFAULT 1,
  retry_count           TINYINT      NOT NULL DEFAULT 0,
  created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_hash (hash),
  KEY idx_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='中考句子表';

-- ---------- 雅思句子表 ----------
CREATE TABLE IF NOT EXISTS ielts_sentence (
  id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  hash                  CHAR(64)     NOT NULL COLLATE utf8mb4_bin,
  sentence              TEXT         NOT NULL,
  sentence_translation  TEXT         NULL,
  analysis              TEXT         NULL,
  status                TINYINT      NOT NULL DEFAULT 0,
  source                TINYINT      NOT NULL DEFAULT 1,
  retry_count           TINYINT      NOT NULL DEFAULT 0,
  created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_hash (hash),
  KEY idx_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='雅思句子表';

-- ---------- 用户-单词 关联表（不分级别） ----------
CREATE TABLE IF NOT EXISTS user_word (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id       BIGINT UNSIGNED NOT NULL,
  level         VARCHAR(16)     NOT NULL,
  word_id       BIGINT UNSIGNED NOT NULL,
  source_type   TINYINT         NULL,
  source_id     BIGINT UNSIGNED NULL,
  source_title  VARCHAR(255)    NULL,
  created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_level_word (user_id, level, word_id),
  KEY idx_user_level_created (user_id, level, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户-单词关联表';

-- ---------- 用户-句子 关联表（不分级别） ----------
CREATE TABLE IF NOT EXISTS user_sentence (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id       BIGINT UNSIGNED NOT NULL,
  level         VARCHAR(16)     NOT NULL,
  sentence_id   BIGINT UNSIGNED NOT NULL,
  source_type   TINYINT         NULL,
  source_id     BIGINT UNSIGNED NULL,
  source_title  VARCHAR(255)    NULL,
  created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_level_sentence (user_id, level, sentence_id),
  KEY idx_user_level_created (user_id, level, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户-句子关联表';
