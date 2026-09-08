package com.ghyinc.finance.domain.kcbcredit.scheduler;

import com.ghyinc.finance.domain.kcbcredit.entity.KcbCreditFile;
import com.ghyinc.finance.domain.kcbcredit.file.KcbFilePoller;
import com.ghyinc.finance.domain.kcbcredit.repository.KcbCreditFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KcbFileIngestScheduler {
    private final KcbFilePoller kcbFilePoller;
    private final KcbCreditFileRepository kcbCreditFileRepository;
    private final JobLauncher jobLauncher;
    private final Job creditChangeJob;

    /**
     * FTP 배치 파일 수신
     */
    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(
            name = "KcbFileIngestScheduler_ingest",
            lockAtLeastFor = "5m",
            lockAtMostFor = "2h"
    )
    public void ingest() throws IOException {
        List<Path> files = kcbFilePoller.pollNewFiles();
        if (files.isEmpty()) {
            log.info("[KCB 신용변동] 신규 파일 없음");
            return;
        }

        for(Path file : files) {
            this.runJob(file);
        }
    }

    private void runJob(Path filePath) {
        String fileName = filePath.getFileName().toString();

        kcbCreditFileRepository.findByFileName(fileName).ifPresentOrElse(
                existing -> log.warn("[KCB 신용변동] 이미 처리 이력 존재, 스킵. file={}", fileName),
                () -> {
                    kcbCreditFileRepository.save(
                            KcbCreditFile.builder().fileName(fileName).build()
                    );

                    try {
                        jobLauncher.run(creditChangeJob, new JobParametersBuilder()
                                .addString("fileName", fileName)
                                .addString("filePath", filePath.toString())
                                .addLong("timestamp", System.currentTimeMillis(), false)    // 식별 파라미터 아님, 로그/추적용
                                .toJobParameters()
                        );
                    } catch (JobExecutionAlreadyRunningException e) {
                        log.warn("[KCB 신용변동] 이미 완료된 Job. file={}", fileName);
                    } catch (Exception e) {
                        log.error("[KCB 신용변동] Job 실행 실패. file={}", fileName, e);
                        kcbCreditFileRepository.findByFileName(fileName)
                                .ifPresent(kcbCreditFile -> kcbCreditFile.markFailed(e.getMessage()));
                    }
                }
        );
    }
}
