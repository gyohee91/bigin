package com.ghyinc.finance.domain.loan.readmodel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisPollingReadModelTest {
    private static final String BROKEN_JSON = "{\"loReqtNo\":\"LR1\",\"status\":\"NO_SUCH_STATUS\"}";   // enum 불일치

    @Mock
    InquiryReadModelStore store;

    ObjectMapper objectMapper = new ObjectMapper();
    RedisPollingReadModel readModel;
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        readModel = new RedisPollingReadModel(store, objectMapper, new PollingMetrics(meterRegistry));
    }

    private InquiryReadModelStore.ReadResult result(InquiryReadModelStore.Type type, List<String> json) {
        return new InquiryReadModelStore.ReadResult(type, 9L, 3, 1, "IN_PROGRESS", 3, json, 1234L);
    }

    @Test
    @DisplayName("DOWN -> DOWN")
    void read_down() {
        when(store.read("LL1", null, 0, 20)).thenReturn(this.result(InquiryReadModelStore.Type.MISS, List.of()));

        assertThat(readModel.read("LL1", null, 0, 20)).isSameAs(PollingView.Miss.INSTANCE);
    }

    @Test
    @DisplayName("SAME -> Unchanged (버전, 상태, 마감 시각 유지)")
    void read_same() {
        when(store.read("LL1", 9L, 0, 20)).thenReturn(this.result(InquiryReadModelStore.Type.SAME, List.of()));

        assertThat(readModel.read("LL1", 9L, 0, 20))
                .isEqualTo(new PollingView.Unchanged(9L, "IN_PROGRESS", 1234L));
    }

    @Test
    @DisplayName("OK -> Hit (항목 JSON을 DTO로 파싱, 필드 그대로 전달)")
    void read_ok_parseItems() throws JsonProcessingException {
        LoanLimitProductResultDto dto =
                new LoanLimitProductResultDto("LR1", PartnerCode.KAKAO_BANK, "k1", PartnerInquiryStatus.SUCCESS, null, 300L, 5.5);
        when(store.read("LL1", null, 0, 20)).thenReturn(
                this.result(InquiryReadModelStore.Type.OK, List.of(objectMapper.writeValueAsString(dto)))
        );

        PollingView view = readModel.read("LL1", null, 0, 20);

        assertThat(view).isInstanceOf(PollingView.Hit.class);
        PollingView.Hit hit = (PollingView.Hit) view;
        assertThat(hit.version()).isEqualTo(9L);
        assertThat(hit.total()).isEqualTo(3);
        assertThat(hit.success()).isEqualTo(1);
        assertThat(hit.status()).isEqualTo("IN_PROGRESS");
        assertThat(hit.totalElements()).isEqualTo(3);
        assertThat(hit.deadlineAtMs()).isEqualTo(1234L);
        assertThat(hit.items()).containsExactly(dto);
    }

    @Test
    @DisplayName("OK 인데 항목 없으면 빈 목록의 Hit")
    void read_ok_emptyItems() {
        when(store.read("LL1", null, 0, 20)).thenReturn(this.result(InquiryReadModelStore.Type.OK, List.of()));

        PollingView view = readModel.read("LL1", null, 0, 20);

        assertThat(view).isInstanceOf(PollingView.Hit.class);
        assertThat(((PollingView.Hit) view).items()).isEmpty();
    }

    @Test
    @DisplayName("저장된 JSON을 파싱할 수 없으면 예외 대신 MISS로 돌리고 parse_fail을 센다")
    void read_ok_parseFail_becomeMiss() {
        when(store.read("LL1", null, 0, 20))
                .thenReturn(this.result(InquiryReadModelStore.Type.OK, List.of(BROKEN_JSON)));

        assertThat(readModel.read("LL1", null, 0, 20)).isSameAs(PollingView.Miss.INSTANCE);
        assertThat(meterRegistry.get("polling.readmodel")
                .tag("result", "parse_fail")
                .counter().count()
        ).isEqualTo(1.0);
    }

    @Test
    @DisplayName("isEnabled / seed / count는 Store로 위임한다")
    void delegates() {
        InquirySnapShot snapShot = new InquirySnapShot("LL1", 1, "IN_PROGRESS", List.of(), 0L);
        when(store.isEnabled()).thenReturn(true);
        when(store.seed(snapShot)).thenReturn(true);

        assertThat(readModel.isEnabled()).isTrue();
        assertThat(readModel.seed(snapShot)).isTrue();
    }
}