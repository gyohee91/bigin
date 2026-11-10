package com.ghyinc.finance.domain.loan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.dto.LoanLimitPollingResponse;
import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import com.ghyinc.finance.domain.loan.readmodel.InquiryReadModelStore;
import com.ghyinc.finance.domain.loan.readmodel.InquirySnapShot;
import com.ghyinc.finance.domain.loan.readmodel.InquirySnapShotLoader;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * FE 폴링 전용 진입점. 이 클래스는 @Transactional이 아니다(Redis 히트 시 DB 커넥션을 잡지 않기 위해)
 * DB가 필요한 구간(스냅샷 재구성, fallback)만 각각 짧은 읽기 트랜잭션 빈을 호출한다.
 */
@Service
@RequiredArgsConstructor
public class LoanLimitPollingService {
    private final InquiryReadModelStore store;
    private final LoanLimitService loanLimitService;
    private final InquirySnapShotLoader snapShotLoader;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public LoanLimitPollingResponse getInquiryResult(String inquiryNo, int page, int size, Long version) {
        Pageable pageable = PageRequest.of(page, size);

        Timer.Sample sample = Timer.start(meterRegistry);
        String path = "db";   // 플래그 OFF(AS-IS) 경로

        try {
            if (!store.isEnabled()) {
                return loanLimitService.getInquiryResult(inquiryNo, pageable);
            }

            InquiryReadModelStore.ReadResult readResult = store.read(inquiryNo, version, page, size);
            path = "redis";

            // Redis 장애 -> 기존 DB 경로
            if (readResult.type() == InquiryReadModelStore.Type.DOWN) {
                path = "fallback_down";
                store.count("fallback");
                return loanLimitService.getInquiryResult(inquiryNo, pageable);
            }

            // MISS/stale -> DB 재구성 + 병합 적재
            if (readResult.type() == InquiryReadModelStore.Type.MISS) {
                path = "rebuild";
                InquirySnapShot snapShot = snapShotLoader.load(inquiryNo);
                if (store.seed(snapShot)) {
                    // 적재본을 다시 읽어 version과 내용을 일치시킨다
                    readResult = store.read(inquiryNo, version, page, size);
                }
                if (readResult.type() == InquiryReadModelStore.Type.MISS || readResult.type() == InquiryReadModelStore.Type.DOWN) {
                    path = "fallback_seed";
                    store.count("fallback");
                    return this.fromSnapShot(snapShot, page, size);
                }
            }

            if (readResult.type() == InquiryReadModelStore.Type.SAME) {
                path = path.equals("rebuild") ? "rebuild" : "unchanged";
                return LoanLimitPollingResponse.unchanged(inquiryNo, readResult.version());
            }

            return this.toResponse(inquiryNo, readResult, page, size);
        } finally {
            sample.stop(this.timer("polling.service", path));
        }
    }

    private LoanLimitPollingResponse toResponse(String inquiryNo, InquiryReadModelStore.ReadResult readResult, int page, int size) {
        List<LoanLimitProductResultDto> items = new ArrayList<>(readResult.itemsJson().size());
        Timer.Sample parse = Timer.start(meterRegistry);
        try {
            for (String json : readResult.itemsJson())
                items.add(objectMapper.readValue(json, LoanLimitProductResultDto.class));
        } catch (Exception e) {
            throw new IllegalStateException("읽기모델 역직렬화 실패. inquiryNo=" + inquiryNo, e);
        } finally {
            parse.stop(this.timer("polling.readmodel.parse", null));
        }
        int totalPages = size == 0 ? 1 : (int) Math.ceil((double) readResult.card() / size);
        return LoanLimitPollingResponse.builder()
                .inquiryNo(inquiryNo)
                .totalProductCount(readResult.total())
                .successProductCount(readResult.success())
                .progressRate(readResult.total() == 0 ? 0 : (int) (readResult.success() * 100.0 / readResult.total()))
                .allResultReceived(readResult.total() > 0 && readResult.success() >= readResult.total())
                .productResults(items)
                .currentPage(page)
                .totalPages(totalPages)
                .totalElements(readResult.card())
                .hasNext(page + 1 < totalPages)
                .version(readResult.version())
                .changed(true)
                .inquiryStatus(readResult.status())
                .build();
    }

    /**
     * Redis 적재까지 실패했을 때 DB 스냅샷으로 직접 응답 (version 없음 -> FE는 항상 새 데이터로 처리)
     */
    private LoanLimitPollingResponse fromSnapShot(InquirySnapShot snapShot, int page, int size) {
        List<LoanLimitProductResultDto> all = snapShot.items();
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        int success = (int) all.stream().filter(d -> d.status() == PartnerInquiryStatus.SUCCESS).count();
        int totalPages = size == 0 ? 1 : (int) Math.ceil((double) all.size() / size);
        return LoanLimitPollingResponse.builder()
                .inquiryNo(snapShot.inquiryNo())
                .totalProductCount(snapShot.total())
                .successProductCount(success)
                .progressRate(snapShot.total() == 0 ? 0 : (int) (success * 100.0 / snapShot.total()))
                .allResultReceived(snapShot.total() > 0 && success >= snapShot.total())
                .productResults(new ArrayList<>(all.subList(from, to)))
                .currentPage(page)
                .totalPages(totalPages)
                .totalElements(all.size())
                .hasNext(page + 1 < totalPages)
                .inquiryStatus(snapShot.status())
                .build();
    }

    private Timer timer(String name, String path) {
        Timer.Builder b = Timer.builder(name).publishPercentiles(0.5, 0.95, 0.99);
        if (path != null) b.tag("path", path);
        return b.register(meterRegistry);
    }
}
