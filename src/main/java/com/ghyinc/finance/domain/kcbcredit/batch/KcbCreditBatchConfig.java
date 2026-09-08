package com.ghyinc.finance.domain.kcbcredit.batch;

import com.ghyinc.finance.domain.kcbcredit.dto.KcbCreditRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.transform.FixedLengthTokenizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class KcbCreditBatchConfig {
    private final PlatformTransactionManager transactionManager;
    private final JobRepository jobRepository;
    private final KcbCreditItemProcessor itemProcessor;
    private final KcbCreditItemWriter itemWriter;
    private final KcbCreditJobListener jobListener;

    private static final int CHUNK_SIZE = 1000;

    @Bean
    public Job kcbCreditJob() {
        return new JobBuilder("kcbCreditJob", jobRepository)
                .listener(jobListener)
                .start(this.kcbCreditStep(null))
                .build();
    }

    @Bean
    public Step kcbCreditStep(String filePath) {
        return new StepBuilder("kcbCreditStep", jobRepository)
                .<KcbCreditRecord, KcbCreditRecord>chunk(CHUNK_SIZE, transactionManager)
                .reader(this.kcbCreditReader(filePath))
                .processor(itemProcessor)
                .writer(itemWriter)
                .faultTolerant()
                .skipLimit(1000)        // 파싱 실패 레코드는 스킵하고 계속 진행
                .skip(Exception.class)
                .build();
    }

    @Bean
    public FlatFileItemReader<KcbCreditRecord> kcbCreditReader(String filePath) {
        return new FlatFileItemReaderBuilder<KcbCreditRecord>()
                .name("kcbCreditReader")
                .resource(new FileSystemResource(filePath))
                .encoding(Charset.forName("EUC-KR").name())
                .linesToSkip(1)                 // 헤더 라인 스킵
                .lineMapper(this.lineMapper())
                .build();
    }

    private DefaultLineMapper<KcbCreditRecord> lineMapper() {
        DefaultLineMapper<KcbCreditRecord> lineMapper = new DefaultLineMapper<>();

        FixedLengthTokenizer tokenizer = new FixedLengthTokenizer();
        tokenizer.setNames(KcbCreditRecordLayout.FIELD_NAME);
        tokenizer.setColumns(KcbCreditRecordLayout.FIELD_RANGES);
        tokenizer.setStrict(false);     // 라인 길이가 스펙보다 짧아도 예외 대신 스킵 처리 가능

        BeanWrapperFieldSetMapper<KcbCreditRecord> fieldSetMapper = new BeanWrapperFieldSetMapper<>();
        fieldSetMapper.setTargetType(KcbCreditRecord.class);
        fieldSetMapper.setCustomEditors(Map.of(
                LocalDate.class, new CustomLocalDateEditor()
        ));

        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(fieldSetMapper);

        return lineMapper;
    }

}
