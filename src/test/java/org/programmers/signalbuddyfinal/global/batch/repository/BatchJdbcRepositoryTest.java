package org.programmers.signalbuddyfinal.global.batch.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.programmers.signalbuddyfinal.global.batch.dto.BatchExecutionId;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class BatchJdbcRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private BatchExecutionId executionId;

    @InjectMocks
    private BatchJdbcRepository batchJdbcRepository;

    @DisplayName("허용된 테이블명으로 STEP_EXECUTION_ID 기반 배치 삭제를 실행한다.")
    @Test
    void deleteAllByStepExecutionIdInBatch_withAllowedTable_executesBatchUpdate() throws Exception {
        // given
        String tableName = "BATCH_STEP_EXECUTION_CONTEXT";
        List<BatchExecutionId> ids = List.of(executionId);
        when(executionId.getStepExecutionId()).thenReturn(10L);

        // when
        batchJdbcRepository.deleteAllByStepExecutionIdInBatch(tableName, ids);

        // then
        ArgumentCaptor<BatchPreparedStatementSetter> captor =
            ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(
            eq("DELETE FROM " + tableName + " WHERE STEP_EXECUTION_ID = ?"),
            captor.capture()
        );

        BatchPreparedStatementSetter setter = captor.getValue();
        assertThat(setter.getBatchSize()).isEqualTo(1);

        PreparedStatement ps = mock(PreparedStatement.class);
        setter.setValues(ps, 0);
        verify(ps).setLong(1, 10L);
    }

    @DisplayName("허용되지 않은 테이블명으로 STEP_EXECUTION_ID 기반 삭제 시 batchUpdate를 호출하지 않는다.")
    @Test
    void deleteAllByStepExecutionIdInBatch_withDisallowedTable_doesNotExecute() {
        // when
        batchJdbcRepository.deleteAllByStepExecutionIdInBatch("UNKNOWN_TABLE", List.of(executionId));

        // then
        verify(jdbcTemplate, never()).batchUpdate(any(String.class), any(BatchPreparedStatementSetter.class));
    }

    @DisplayName("허용된 테이블명으로 JOB_EXECUTION_ID 기반 배치 삭제를 실행한다.")
    @Test
    void deleteAllByJobExecutionIdInBatch_withAllowedTable_executesBatchUpdate() throws Exception {
        // given
        String tableName = "BATCH_JOB_EXECUTION_PARAMS";
        List<BatchExecutionId> ids = List.of(executionId);
        when(executionId.getJobExecutionId()).thenReturn(20L);

        // when
        batchJdbcRepository.deleteAllByJobExecutionIdInBatch(tableName, ids);

        // then
        ArgumentCaptor<BatchPreparedStatementSetter> captor =
            ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(
            eq("DELETE FROM " + tableName + " WHERE JOB_EXECUTION_ID = ?"),
            captor.capture()
        );

        BatchPreparedStatementSetter setter = captor.getValue();
        assertThat(setter.getBatchSize()).isEqualTo(1);

        PreparedStatement ps = mock(PreparedStatement.class);
        setter.setValues(ps, 0);
        verify(ps).setLong(1, 20L);
    }

    @DisplayName("허용되지 않은 테이블명으로 JOB_EXECUTION_ID 기반 삭제 시 batchUpdate를 호출하지 않는다.")
    @Test
    void deleteAllByJobExecutionIdInBatch_withDisallowedTable_doesNotExecute() {
        // when
        batchJdbcRepository.deleteAllByJobExecutionIdInBatch("UNKNOWN_TABLE", List.of(executionId));

        // then
        verify(jdbcTemplate, never()).batchUpdate(any(String.class), any(BatchPreparedStatementSetter.class));
    }

    @DisplayName("허용된 테이블명으로 JOB_INSTANCE_ID 기반 배치 삭제를 실행한다.")
    @Test
    void deleteAllByJobInstanceIdInBatch_withAllowedTable_executesBatchUpdate() throws Exception {
        // given
        String tableName = "BATCH_JOB_INSTANCE";
        List<BatchExecutionId> ids = List.of(executionId);
        when(executionId.getJobInstanceId()).thenReturn(30L);

        // when
        batchJdbcRepository.deleteAllByJobInstanceIdInBatch(tableName, ids);

        // then
        ArgumentCaptor<BatchPreparedStatementSetter> captor =
            ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(any(String.class), captor.capture());

        BatchPreparedStatementSetter setter = captor.getValue();
        assertThat(setter.getBatchSize()).isEqualTo(1);

        PreparedStatement ps = mock(PreparedStatement.class);
        setter.setValues(ps, 0);
        verify(ps).setLong(1, 30L);
        verify(ps).setLong(2, 30L);
    }

    @DisplayName("허용되지 않은 테이블명으로 JOB_INSTANCE_ID 기반 삭제 시 batchUpdate를 호출하지 않는다.")
    @Test
    void deleteAllByJobInstanceIdInBatch_withDisallowedTable_doesNotExecute() {
        // when
        batchJdbcRepository.deleteAllByJobInstanceIdInBatch("UNKNOWN_TABLE", List.of(executionId));

        // then
        verify(jdbcTemplate, never()).batchUpdate(any(String.class), any(BatchPreparedStatementSetter.class));
    }
}
