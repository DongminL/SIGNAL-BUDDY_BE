package org.programmers.signalbuddyfinal.domain.like.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.RunIdIncrementer;

@ExtendWith(MockitoExtension.class)
class LikeJobSchedulerTest {

    @Mock
    private JobLauncher jobLauncher;

    @Mock
    private Job likeRequestJob;

    @Mock
    private JobExplorer jobExplorer;

    @InjectMocks
    private LikeJobScheduler likeJobScheduler;

    @DisplayName("likeRequestJob을 잘 주입해서 실행하는지 확인한다.")
    @Test
    void runJob() throws Exception {
        when(likeRequestJob.getJobParametersIncrementer()).thenReturn(new RunIdIncrementer());

        likeJobScheduler.runJob();

        verify(jobLauncher, times(1))
            .run(eq(likeRequestJob), any(JobParameters.class));
    }
}