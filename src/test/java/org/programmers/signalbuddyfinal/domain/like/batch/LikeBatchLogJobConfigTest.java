package org.programmers.signalbuddyfinal.domain.like.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.programmers.signalbuddyfinal.global.support.BatchTest;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class LikeBatchLogJobConfigTest extends BatchTest {

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private Job likeBatchLogDeleteJob;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DisplayName("좋아요 배치 작업의 로그 삭제 잡을 실행한다.")
    @Test
    void likeBatchLogDeleteJob() throws Exception {
        // when
        jobLauncherTestUtils.setJob(likeBatchLogDeleteJob);
        JobExecution jobExecution = jobLauncherTestUtils.launchJob();

        // then
        assertThat(jobExecution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
    }

    @DisplayName("만료된 배치 실행 로그가 있으면 deleteLog writer가 해당 레코드를 삭제한다.")
    @Test
    void deleteLog_withExpiredBatchExecutions_deletesRecords() throws Exception {
        // given: expired-minutes=1 이므로 2분 이상 된 step 실행만 대상이 됨
        JobParameters jobParameters = new JobParametersBuilder()
            .addLong("run.id", System.nanoTime())
            .toJobParameters();

        JobExecution oldJobExecution = jobRepository.createJobExecution(
            "testCleanupJob", jobParameters);

        StepExecution oldStepExecution = new StepExecution("deleteLikeLogBatch", oldJobExecution);
        jobRepository.add(oldStepExecution);

        LocalDateTime expiredTime = LocalDateTime.now().minusMinutes(10);
        jdbcTemplate.update(
            "UPDATE BATCH_STEP_EXECUTION SET START_TIME = ? WHERE STEP_EXECUTION_ID = ?",
            expiredTime, oldStepExecution.getId()
        );

        // when
        jobLauncherTestUtils.setJob(likeBatchLogDeleteJob);
        JobExecution result = jobLauncherTestUtils.launchJob();

        // then
        assertThat(result.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);

        Integer remainingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM BATCH_STEP_EXECUTION WHERE STEP_EXECUTION_ID = ?",
            Integer.class, oldStepExecution.getId()
        );
        assertThat(remainingCount).isZero();
    }
}