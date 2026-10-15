package com.bud.wordlist.repository;

import com.bud.wordlist.common.ContentSource;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.domain.Word;
import com.bud.wordlist.generation.WordCard;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

/**
 * 4 张同构词表的全部读写。表名只来自 Level 枚举白名单。
 */
@Repository
public class WordRepository {

    private final JdbcClient jdbc;

    public WordRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Word> MAPPER = (ResultSet rs, int rowNum) -> new Word(
            rs.getLong("id"),
            rs.getString("word"),
            rs.getString("meaning"),
            rs.getString("phonetic"),
            rs.getString("pos"),
            rs.getString("mnemonic"),
            rs.getString("example"),
            rs.getString("phrase"),
            rs.getString("sentence_hash"),
            rs.getInt("status"),
            rs.getInt("source"),
            rs.getInt("retry_count"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    public Optional<Word> findByWord(Level level, String word) {
        String sql = "SELECT * FROM %s WHERE word = :word".formatted(level.wordTable());
        return jdbc.sql(sql).param("word", word).query(MAPPER).optional();
    }

    public Optional<Word> findById(Level level, long id) {
        String sql = "SELECT * FROM %s WHERE id = :id".formatted(level.wordTable());
        return jdbc.sql(sql).param("id", id).query(MAPPER).optional();
    }

    /** 新增词，返回新 id；word 唯一键冲突抛 DuplicateKeyException。 */
    public long insertWord(Level level, String word, String sentenceHash) {
        String sql = ("INSERT INTO %s (word, sentence_hash, status, source, retry_count) "
                + "VALUES (?, ?, 0, ?, 1)").formatted(level.wordTable());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql(sql)
                .param(word)
                .param(sentenceHash)
                .param(ContentSource.AI.code())
                .update(keyHolder);
        return requireId(keyHolder);
    }

    /** 跨级别拷贝：整行复制 6 个内容字段 + sentence_hash，标记已完成，source=2。 */
    public long insertCopiedWord(Level level, String word, Word source) {
        String sql = ("INSERT INTO %s (word, meaning, phonetic, pos, mnemonic, example, phrase, "
                + "sentence_hash, status, source, retry_count) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 1)").formatted(level.wordTable());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql(sql)
                .param(word)
                .param(source.meaning())
                .param(source.phonetic())
                .param(source.pos())
                .param(source.mnemonic())
                .param(source.example())
                .param(source.phrase())
                .param(source.sentenceHash())
                .param(ContentSource.COPY.code())
                .update(keyHolder);
        return requireId(keyHolder);
    }

    /** 生成器写回：仅在 status=0 时生效，返回受影响行数（0 表示已被并发处理）。 */
    public int updateContentIfGenerating(Level level, long id, WordCard card) {
        String sql = ("UPDATE %s SET meaning=?, phonetic=?, pos=?, "
                + "mnemonic=?, example=?, phrase=?, status=1 "
                + "WHERE id=? AND status=0").formatted(level.wordTable());
        return jdbc.sql(sql)
                .param(card.meaning())
                .param(card.phonetic())
                .param(card.pos())
                .param(card.mnemonic())
                .param(card.example())
                .param(card.phrase())
                .param(id)
                .update();
    }

    public int markFailed(Level level, long id) {
        String sql = "UPDATE %s SET status=2 WHERE id=? AND status=0".formatted(level.wordTable());
        return jdbc.sql(sql).param(id).update();
    }

    /** 补偿任务：带条件原子更新，成功才投递。 */
    public int resetToGeneratingIfRetryable(Level level, long id, int maxAttempts) {
        String sql = ("UPDATE %s SET status=0, retry_count=retry_count+1 "
                + "WHERE id=? AND status IN (0,2) AND retry_count < ?").formatted(level.wordTable());
        return jdbc.sql(sql).param(id).param(maxAttempts).update();
    }

    public List<Word> findStaleOrFailed(Level level, int staleSeconds, int maxAttempts) {
        String sql = ("SELECT * FROM %s "
                + "WHERE (status=0 AND updated_at < DATE_SUB(NOW(), INTERVAL ? SECOND) "
                + "AND retry_count < ?) "
                + "OR (status=2 AND retry_count < ?)").formatted(level.wordTable());
        return jdbc.sql(sql)
                .param(staleSeconds)
                .param(maxAttempts)
                .param(maxAttempts)
                .query(MAPPER)
                .list();
    }

    public int updateSentenceHash(Level level, long id, String sentenceHash) {
        String sql = "UPDATE %s SET sentence_hash = ? WHERE id = ?".formatted(level.wordTable());
        return jdbc.sql(sql).param(sentenceHash).param(id).update();
    }

    /** 仅测试用 */
    public void deleteAllForTest(Level level) {
        jdbc.sql("DELETE FROM %s".formatted(level.wordTable())).update();
    }

    private long requireId(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("未取得自增 id");
        }
        return key.longValue();
    }
}