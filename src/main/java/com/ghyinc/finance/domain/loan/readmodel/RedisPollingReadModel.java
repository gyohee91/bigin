package com.ghyinc.finance.domain.loan.readmodel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisPollingReadModel implements PollingReadModel {
    private final InquiryReadModelStore store;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    public boolean isEnabled() {
        return store.isEnabled();
    }

    @Override
    public boolean seed(InquirySnapShot snapShot) {
        return store.seed(snapShot);
    }

    @Override
    public void count(String result) {
        store.count(result);
    }

    @Override
    public PollingView read(String inquiryNo, Long clientVersion, int page, int size) {
        InquiryReadModelStore.ReadResult readResult = store.read(inquiryNo, clientVersion, page, size);
        return switch (readResult.type()) {
            case DOWN -> PollingView.Down.INSTANCE;
            case MISS -> PollingView.Miss.INSTANCE;
            case SAME -> new PollingView.Unchanged(readResult.version(), readResult.status(), readResult.deadlineAtMs());
            case OK -> this.toHit(inquiryNo, readResult);
        };
    }

    /**
     * 항목 JSON 을 파싱한다. 저장된 JSON 이 현재 DTO 와 맞지 않으면(배포로 필드/enum 이 바뀐 직후 등)
     * 500 을 내지 않고 Miss 로 돌려 DB 에서 다시 만들게 한다.
     */
    private PollingView toHit(String inquiryNo, InquiryReadModelStore.ReadResult readResult) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<LoanLimitProductResultDto> items = new ArrayList<>(readResult.itemsJson().size());
            for (String json : readResult.itemsJson()) {
                items.add(objectMapper.readValue(json, LoanLimitProductResultDto.class));
            }
            return new PollingView.Hit(readResult.version(), readResult.total(), readResult.success(),
                    readResult.status(), readResult.card(), items, readResult.deadlineAtMs());
        } catch (JsonProcessingException e) {
            store.count("parse_fail");
            log.warn("[ReadModel] 항목 역직렬화 실패 -> MISS 로 처리. inquiryNo={} cause={}", inquiryNo, e.toString());
            return PollingView.Miss.INSTANCE;
        } finally {
            sample.stop(Timer.builder("polling.readmodel.parse").publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry));
        }
    }
}
