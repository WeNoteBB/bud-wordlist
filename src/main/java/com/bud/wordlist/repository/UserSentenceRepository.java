package com.bud.wordlist.repository;

import com.bud.wordlist.common.Level;
import com.bud.wordlist.web.dto.PageResult;
import com.bud.wordlist.web.dto.SentenceView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.util.List;

@Repository
public class UserSentenceRepository {

    private final JdbcClient jdbc;

    public UserSentenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insert(long userId, Level level, long sentenceId,
                          Integer sourceType, Long sourceId, String sourceTitle) {
        try {
            jdbc.sql("""
                            INSERT INTO user_sentence (user_id, level, sentence_id, source_type, source_id, source_title)
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

    public int delete(long userId, String level, long sentenceId) {
        return jdbc.sql("""
                        DELETE FROM user_sentence
                         WHERE user_id = :userId AND level = :level AND sentence_id = :sentenceId
                        """)
                .param("userId", userId)
                .param("level", level)
                .param("sentenceId", sentenceId)
                .update();
    }

    private static final RowMapper<SentenceView> VIEW_MAPPER = (ResultSet rs, int rowNum) -> new SentenceView(
            rs.getLong("sentence_id"),
            rs.getString("hash"),
            rs.getString("sentence"),
            rs.getString("sentence_translation"),
            rs.getString("analysis"),
            rs.getInt("status"),
            rs.getString("source_title"),
            rs.getTimestamp("collected_at").toLocalDateTime());

    public PageResult<SentenceView> findPage(long userId, Level level, int page, int size) {
        String joinSql = """
                FROM user_sentence us
                JOIN %s s ON s.id = us.sentence_id
                WHERE us.user_id = :userId AND us.level = :level
                """.formatted(level.sentenceTable());
        Long total = jdbc.sql("SELECT COUNT(*) " + joinSql)
                .param("userId", userId)
                .param("level", level.code())
                .query(Long.class)
                .single();
        List<SentenceView> list = jdbc.sql("""
                        SELECT s.id AS sentence_id, s.hash, s.sentence, s.sentence_translation,
                               s.analysis, s.status, us.source_title, us.created_at AS collected_at
                        """ + joinSql + """
                        ORDER BY us.created_at DESC, us.id DESC
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

    public void deleteAllForTest() {
        jdbc.sql("DELETE FROM user_sentence").update();
    }
}
