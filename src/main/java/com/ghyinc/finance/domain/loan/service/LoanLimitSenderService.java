package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.adaptor.LoanLimitAdaptor;
import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoanLimitSenderService {
    private final Executor loanLimitExecutor;

    /**
     * 여러 금융사에 대한 한도조회
     *
     * <p> 각 은행 API 호출은 독립적이므로 CompletableFuture로 병렬 처리
     * 한 금융사의 실패가 다른 금융사 조회에 영향을 주지 않음.
     * 전용 스레드 풀을 사용하여 외부 I/O가 공통 스레드 풀을 점유하지 않도록 격리
     * @param adaptors
     * @param adaptorRequest
     * @return
     */
    public List<LoanLimitAdaptorResponse> inquiry(
            List<LoanLimitAdaptor> adaptors,
            LoanLimitAdaptorRequest adaptorRequest
    ) {
        List<CompletableFuture<LoanLimitAdaptorResponse>> futures = adaptors.stream()
                .map(adaptor -> CompletableFuture
                        .supplyAsync(() -> adaptor.inquireLimit(adaptorRequest), loanLimitExecutor)
                        .exceptionally(ex -> {
                            log.error("[{}] 비동기 한도조회 중 예외 발생", adaptor.getPartnerCode(), ex);
                            return LoanLimitAdaptorResponse.fail(adaptor.getPartnerCode(), ex.getMessage(), 0L);
                        })
                )
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }
}
