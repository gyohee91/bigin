package com.ghyinc.finance.domain.kcbcredit.batch;

import com.ghyinc.finance.domain.kcbcredit.entity.KcbCreditFile;
import com.ghyinc.finance.domain.kcbcredit.repository.KcbCreditFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KcbCreditJobListener implements JobExecutionListener {
    private final KcbCreditFileRepository kcbCreditFileRepository;
    private final KcbCreditItemWriter itemWriter;

    @Override
    public void beforeJob(JobExecution jobExecution) {
        String fileName = jobExecution.getJobParameters().getString("fileName");
        kcbCreditFileRepository.findByFileName(fileName)
                .ifPresent(KcbCreditFile::markProcessing);
        log.info("[신용변동 배치] 시작. file={}", fileName);
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        String fileName = jobExecution.getJobParameters().getString("fileName");
        long readCount = jobExecution.getStepExecutions().stream()
                .mapToLong(StepExecution::getReadCount)
                .sum();

        kcbCreditFileRepository.findByFileName(fileName)
                .ifPresent(file -> {
                    if(jobExecution.getStatus().isUnsuccessful()) {
                        file.markFailed(jobExecution.getExitStatus().getExitDescription());
                        log.error("[신용변동 배치] 실패. file={}", fileName);
                        return;
                    }
                    file.markCompleted(readCount, readCount, itemWriter.getNotifiedCount(), itemWriter.getSkippedCount());
                    log.info("[신용변동 배치] 완료. file={}, 총건수={}, 알림발송={}, 스킵={}",
                            fileName, readCount, itemWriter.getNotifiedCount(), itemWriter.getSkippedCount());
                });
    }
}
