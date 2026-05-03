package org.programmers.signalbuddyfinal.global.batch.repository;

import groovy.util.logging.Slf4j;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.programmers.signalbuddyfinal.global.batch.dto.BatchExecutionId;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@lombok.extern.slf4j.Slf4j
@Slf4j
@Repository
@RequiredArgsConstructor
public class BatchJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final Set<String> ALLOWED_TABLES = Set.of(
        "BATCH_STEP_EXECUTION_CONTEXT",
        "BATCH_STEP_EXECUTION",
        "BATCH_JOB_EXECUTION_PARAMS",
        "BATCH_JOB_EXECUTION_CONTEXT",
        "BATCH_JOB_EXECUTION",
        "BATCH_JOB_INSTANCE"
    );

    /**
     * STEP_EXECUTION_ID로 STEP 관련 BATCH META TABLE의 데이터를 벌크 연산으로 삭제
     *
     * @param batchTableName 삭제할 배치 테이블명
     * @param executionIds  STEP_EXECUTION_ID, JOB_EXECUTION_ID 목록
     */
    public void deleteAllByStepExecutionIdInBatch(String batchTableName, List<BatchExecutionId> executionIds) {
        if (!ALLOWED_TABLES.contains(batchTableName)) {
            log.error("허용되지 않은 배치 테이블: {}", batchTableName);
            return;
        }

        String sql = "DELETE FROM " + batchTableName + " WHERE STEP_EXECUTION_ID = ?";

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Long stepExecutionId = executionIds.get(i).getStepExecutionId();
                ps.setLong(1, stepExecutionId);
            }

            @Override
            public int getBatchSize() {
                return executionIds.size();
            }
        });
    }

    /**
     * JOB_EXECUTION_ID로 JOB 관련 BATCH META TABLE의 데이터를 벌크 연산으로 삭제
     *
     * @param batchTableName 삭제할 배치 테이블명
     * @param executionIds  STEP_EXECUTION_ID, JOB_EXECUTION_ID 목록
     */
    public void deleteAllByJobExecutionIdInBatch(String batchTableName, List<BatchExecutionId> executionIds) {
        if (!ALLOWED_TABLES.contains(batchTableName)) {
            log.error("허용되지 않은 배치 테이블: {}", batchTableName);
            return;
        }

        String sql = "DELETE FROM " + batchTableName + " WHERE JOB_EXECUTION_ID = ?";

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Long jobExecutionId = executionIds.get(i).getJobExecutionId();
                ps.setLong(1, jobExecutionId);
            }

            @Override
            public int getBatchSize() {
                return executionIds.size();
            }
        });
    }

    /**
     * JOB_INSTANCE_ID로 BATCH_JOB_INSTANCE 데이터를 벌크 연산으로 삭제
     *
     * @param batchTableName 삭제할 배치 테이블명
     * @param executionIds  JOB_INSTANCE_ID 목록
     */
    public void deleteAllByJobInstanceIdInBatch(String batchTableName, List<BatchExecutionId> executionIds) {
        if (!ALLOWED_TABLES.contains(batchTableName)) {
            log.error("허용되지 않은 배치 테이블: {}", batchTableName);
            return;
        }

        String sql = "DELETE FROM " + batchTableName
            + " WHERE JOB_INSTANCE_ID = ?"
            + " AND NOT EXISTS (SELECT 1 FROM BATCH_JOB_EXECUTION WHERE JOB_INSTANCE_ID = ?)";

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Long jobInstanceId = executionIds.get(i).getJobInstanceId();
                ps.setLong(1, jobInstanceId);
                ps.setLong(2, jobInstanceId);
            }

            @Override
            public int getBatchSize() {
                return executionIds.size();
            }
        });
    }
}
