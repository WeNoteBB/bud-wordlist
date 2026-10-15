package com.bud.wordlist.repository;

import com.bud.wordlist.common.ContentSource;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.domain.Sentence;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

/**
 * 4 张同构句表的全部读写。表名只来自 Level 枚举白名单。
 */
@Repository
public class SentenceRepository {

    private final JdbcClient jdbc;

    public SentenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Sentence> MAPPER = (ResultSet rs, int rowNum) -> new Sentence(
            rs.getLong("id"),
            rs.getString("hash"),
            rs.getString("sentence"),
            rs.getString("sentence_translation"),
            rs.getString("analysis"),
            rs.getInt("status"),
            rs.getInt("source"),
            rs.getInt("retry_count"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    public Optional<Sentence> findByHash(Level level, String hash) {
        String sql = "SELECT * FROM %s WHERE hash = :hash".formatted(level.sentenceTable());
        return jdbc.sql(sql).param("hash", hash).query(MAPPER).optional();
    }

    public Optional<Sentence> findById(Level level, long id) {
        String sql = "SELECT * FROM %s WHERE id = :id".formatted(level.sentenceTable());
        return jdbc.sql(sql).param("id", id).query(MAPPER).optional();
    }

    /** 单词带句子：句子内容已齐（含翻译），直接存为已完成，source=3(人工)。 */
    public long insertSentence(Level level, String hash, String sentence, String translation) {
        String sql = ("INSERT INTO %s (hash, sentence, sentence_translation, status, source, retry_count) "
                + "VALUES (?, ?, ?, 1, ?, 1)").formatted(level.sentenceTable());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql(sql)
                .param(hash)
                .param(sentence)
                .param(translation)
                .param(ContentSource.MANUAL.code())
                .update(keyHolder);
        return requireId(keyHolder);
    }

    /** 独立收录句子（好句本）：仅存句子，待 AI 分析，status=0。 */
    public long insertSentenceForAnalysis(Level level, String hash, String sentence) {
        String sql = ("INSERT INTO %s (hash, sentence, status, source, retry_count) "
                + "VALUES (?, ?, 0, ?, 1)").formatted(level.sentenceTable());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql(sql)
                .param(hash)
                .param(sentence)
                .param(ContentSource.AI.code())
                .update(keyHolder);
        return requireId(keyHolder);
    }

    /** 生成器写回分析：仅在 status=0 时生效。 */
    public int updateAnalysisIfGenerating(Level level, long id, String analysis) {
        String sql = ("UPDATE %s SET analysis=?, status=1 WHERE id=? AND status=0")
                .formatted(level.sentenceTable());
        return jdbc.sql(sql).param(analysis).param(id).update();
    }

    public int markFailed(Level level, long id) {
        String sql = "UPDATE %s SET status=2 WHERE id=? AND status=0".formatted(level.sentenceTable());
        return jdbc.sql(sql).param(id).update();
    }

    public int resetToGeneratingIfRetryable(Level level, long id, int maxAttempts) {
        String sql = ("UPDATE %s SET status=0, retry_count=retry_count+1 "
                + "WHERE id=? AND status IN (0,2) AND retry_count < ?").formatted(level.sentenceTable());
        return jdbc.sql(sql).param(id).param(maxAttempts).update();
    }

    public List<Sentence> findStaleOrFailed(Level level, int staleSeconds, int maxAttempts) {
        String sql = ("SELECT * FROM %s "
                + "WHERE (status=0 AND updated_at < DATE_SUB(NOW(), INTERVAL ? SECOND) "
                + "AND retry_count < ?) "
                + "OR (status=2 AND retry_count < ?)").formatted(level.sentenceTable());
        return jdbc.sql(sql)
                .param(staleSeconds)
                .param(maxAttempts)
                .param(maxAttempts)
                .query(MAPPER)
                .list();
    }

    /** 仅测试用 */
    public void deleteAllForTest(Level level) {
        jdbc.sql("DELETE FROM %s".formatted(level.sentenceTable())).update();
    }

    private long requireId(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("未取得自增 id");
        }
        return key.longValue();
    }
}