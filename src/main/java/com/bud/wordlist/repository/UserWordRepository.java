package com.bud.wordlist.repository;

import com.bud.wordlist.common.Level;
import com.bud.wordlist.web.dto.PageResult;
import com.bud.wordlist.web.dto.WordView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.util.List;

/**
 * user_word 关联表（不分级别）+ 生词本分页联查。
 * 只有 JOIN 侧的表名按 level 选择。
 */
@Repository
public class UserWordRepository {

    private final JdbcClient jdbc;

    public UserWordRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等收录：唯一键冲突视为已收录，返回 false。 */
    public boolean insert(long userId, Level level, long wordId,
                          Integer sourceType, Long sourceId, String sourceTitle) {
        try {
            jdbc.sql("""
                            INSERT INTO user_word (user_id, level, word_id, source_type, source_id, source_title)
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
            return false;
        }
    }

    public int delete(long userId, String level, long wordId) {
        return jdbc.sql("""
                        DELETE FROM user_word
                         WHERE user_id = :userId AND level = :level AND word_id = :wordId
                        """)
                .param("userId", userId)
                .param("level", level)
                .param("wordId", wordId)
                .update();
    }

    private static final RowMapper<WordView> VIEW_MAPPER = (ResultSet rs, int rowNum) -> new WordView(
            rs.getLong("word_id"),
            rs.getString("word"),
            rs.getString("sentence_hash"),
            rs.getString("sentence"),
            rs.getString("sentence_translation"),
            rs.getInt("status"),
            rs.getString("meaning"),
            rs.getString("phonetic"),
            rs.getString("pos"),
            rs.getString("mnemonic"),
            rs.getString("example"),
            rs.getString("phrase"),
            rs.getString("source_title"),
            rs.getTimestamp("collected_at").toLocalDateTime());

    public PageResult<WordView> findPage(long userId, Level level, int page, int size) {
        String joinSql = """
                FROM user_word uw
                JOIN %s w ON w.id = uw.word_id
                LEFT JOIN %s s ON s.hash = w.sentence_hash
                WHERE uw.user_id = :userId AND uw.level = :level
                """.formatted(level.wordTable(), level.sentenceTable());
        Long total = jdbc.sql("SELECT COUNT(*) " + joinSql)
                .param("userId", userId)
                .param("level", level.code())
                .query(Long.class)
                .single();
        List<WordView> list = jdbc.sql("""
                        SELECT w.id AS word_id, w.word, w.sentence_hash,
                               s.sentence, s.sentence_translation,
                               w.status, w.meaning, w.phonetic, w.pos,
                               w.mnemonic, w.example, w.phrase,
                               uw.source_title, uw.created_at AS collected_at
                        """ + joinSql + """
                        ORDER BY uw.created_at DESC, uw.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("userId", userId)
                .param("level", level.code())
                .param("limit", size)
                .param("offset", (long) (page - 1) * size)
                .query(VIEW_MAPPER)
                .list();
        return new PageResult<>(total, page, size, list);
    }

    /** 仅测试用 */
    public void deleteAllForTest() {
        jdbc.sql("DELETE FROM user_word").update();
    }
}
