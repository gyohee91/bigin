package com.ghyinc.finance.domain.kcbcredit.scheduler;

import com.ghyinc.finance.domain.kcbcredit.entity.KcbCreditFile;
import com.ghyinc.finance.domain.kcbcredit.file.KcbFilePoller;
import com.ghyinc.finance.domain.kcbcredit.repository.KcbCreditFileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class KcbFileIngestSchedulerTest {
    @InjectMocks
    private KcbFileIngestScheduler kcbFileIngestScheduler;

    @Mock
    private KcbFilePoller kcbFilePoller;

    @Mock
    private KcbCreditFileRepository kcbCreditFileRepository;

    @Mock
    private JobLauncher jobLauncher;

    @Mock
    private Job kcbCeditJob;

    @Test
    @DisplayName("이미 처리 이력이 있는 파일이면 Job을 실행하지 않는다")
    void ingest_skipsAlreadyProcessedFile() throws IOException, JobInstanceAlreadyCompleteException, JobExecutionAlreadyRunningException, JobParametersInvalidException, JobRestartException {
        // given
        Path filePath = Path.of("/data/kcb/inbound/kcb_20260904.csv");
        given(kcbFilePoller.pollNewFiles()).willReturn(List.of(filePath));
        given(kcbCreditFileRepository.findByFileName("kcb_20260904.csv"))
                .willReturn(Optional.of(KcbCreditFile.builder().fileName("kcb_20260904.csv").build()));

        // when
        kcbFileIngestScheduler.ingest();

        // then
        then(kcbCreditFileRepository).should(never()).save(any());
        then(jobLauncher).should(never()).run(any(), any(JobParameters.class));
    }

    @Test
    @DisplayName("신규 파일이면 이력을 저장하고 Job을 실행한다")
    void ingest_newFile_saveHistoryAndRunsJob() throws IOException, JobInstanceAlreadyCompleteException, JobExecutionAlreadyRunningException, JobParametersInvalidException, JobRestartException {
        // given
        Path filePath = Path.of("/data/kcb/inbound/kcb_20260904.csv");
        given(kcbFilePoller.pollNewFiles()).willReturn(List.of(filePath));
        given(kcbCreditFileRepository.findByFileName("kcb_20260904.csv"))
                .willReturn(Optional.empty());

        // when
        kcbFileIngestScheduler.ingest();

        // then
        then(kcbCreditFileRepository).should().save(any(KcbCreditFile.class));
        then(jobLauncher).should().run(any(Job.class), any(JobParameters.class));
    }

    @Test
    @DisplayName("신규 파일이 없으면 아무 것도 하지 않는다")
    void ingest_noNewFiles_doesNothing() throws IOException {
        given(kcbFilePoller.pollNewFiles()).willReturn(List.of());

        kcbFileIngestScheduler.ingest();

        then(kcbCreditFileRepository).shouldHaveNoInteractions();
    }
}