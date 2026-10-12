package com.lawground.law.service;

import com.lawground.global.error.ApiException;
import com.lawground.global.error.ErrorCode;
import com.lawground.law.dto.LawArticleResponse;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LawArticleService {
    private final JdbcTemplate jdbc;

    public LawArticleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public LawArticleResponse get(UUID id) {
        var rows =
                jdbc.query(
                        """
                SELECT a.id,v.id AS version_id,l.title,a.article_label,a.source_text,v.source_url,v.effective_from,v.effective_to
                FROM law_articles a JOIN law_versions v ON v.id=a.law_version_id JOIN laws l ON l.id=v.law_id
                WHERE a.id=? AND v.applicability_status='VERIFIED' AND l.source_provider='LAW_GO_KR'
                """,
                        (rs, n) ->
                                new LawArticleResponse(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("version_id", UUID.class),
                                        rs.getString("title"),
                                        rs.getString("article_label"),
                                        rs.getString("source_text"),
                                        rs.getString("source_url"),
                                        rs.getObject("effective_from", LocalDate.class),
                                        rs.getObject("effective_to", LocalDate.class)),
                        id);
        if (rows.isEmpty())
            throw new ApiException(
                    HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "조문을 찾을 수 없습니다.");
        return rows.get(0);
    }
}
