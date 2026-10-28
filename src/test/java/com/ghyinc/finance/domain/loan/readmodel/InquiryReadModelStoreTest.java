package com.ghyinc.finance.domain.loan.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InquiryReadModelStoreTest {
    @Mock
    StringRedisTemplate redis;

    private InquiryReadModelStore store(boolean enabled) {
        return new InquiryReadModelStore(redis, new ObjectMapper(), new SimpleMeterRegistry(),
                enabled, 3600, 15, 3000);
    }

    private LoanLimitProductResultDto dto(String loReqtNo, PartnerInquiryStatus status, Long amount) {
        return new LoanLimitProductResultDto(
                loReqtNo, PartnerCode.KAKAO_BANK, "P",
                status, null, amount, 5.5
        );
    }

    @Test
    @DisplayName("플래그 OFF이면 Redis를 호출하지 않는다")
    void apply_shouldNotCallRedis_whenDisabled() {
        this.store(false)
                .apply(InquiryReadModelEvent.delta("LL1", null,
                        List.of(this.dto("LR1", PartnerInquiryStatus.SUCCESS, 100L))));
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("플래그 ON이면 모든 인자가 String이고 null 금액은 -1이다")
    void apply_shouldPassAllScriptArgsAsStrings_andMapNullAmountToMinusOne() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(-1);

        this.store(true).apply(InquiryReadModelEvent.delta("LL1", null,
                List.of(this.dto("LR1", PartnerInquiryStatus.SEND_SUCCESS, null))));

        ArgumentCaptor<Object[]> argumentCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), anyList(), argumentCaptor.capture());
        Object[] objects = argumentCaptor.getValue();
        assertThat(Arrays.stream(objects).allMatch(String.class::isInstance));
        assertThat(objects[0]).isEqualTo("D");
        assertThat(objects[5]).isEqualTo("LR1");    // member
        assertThat(objects[6]).isEqualTo("-1");     // null amount -> -1
        assertThat(objects[7]).isEqualTo("1");      // stage
    }

    @Test
    @DisplayName("Redis 오류 후 backoff 동안 Redis를 다시 호출하지 않는다")
    void read_shouldSkipRedis_duringBackoffAfterError() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("boom"));
        InquiryReadModelStore store = this.store(true);

        assertThat(store.read("LL1", null, 0, 20).type()).isEqualTo(InquiryReadModelStore.Type.DOWN);
        assertThat(store.read("LL1", null, 0, 20).type()).isEqualTo(InquiryReadModelStore.Type.DOWN);

        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("상태를 단계로 매핑한다")
    void stage_shouldMapStatusToStage() {
        assertThat(InquiryReadModelStore.stage(null)).isEqualTo(0);
        assertThat(InquiryReadModelStore.stage(PartnerInquiryStatus.PENDING)).isEqualTo(0);
        assertThat(InquiryReadModelStore.stage(PartnerInquiryStatus.SEND_SUCCESS)).isEqualTo(1);
        assertThat(InquiryReadModelStore.stage(PartnerInquiryStatus.SEND_FAILED)).isEqualTo(1);
        assertThat(InquiryReadModelStore.stage(PartnerInquiryStatus.SUCCESS)).isEqualTo(2);
    }

    @Test
    @DisplayName("응답 코드별로 파싱한다")
    void read_shouldParseEachResponseCode() {
        InquiryReadModelStore store = this.store(true);

        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of("SAME", "77"));
        InquiryReadModelStore.ReadResult same = store.read("LL1", 77L, 0, 20);
        assertThat(same.type()).isEqualTo(InquiryReadModelStore.Type.SAME);
        assertThat(same.version()).isEqualTo(77L);

        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of("OK", "9", "3", "1", "IN_PROGRESS", "3", "{\"a\":1}", "{\"b\":2}"));
        InquiryReadModelStore.ReadResult ok = store.read("LL1", null, 0, 20);
        assertThat(ok.type()).isEqualTo(InquiryReadModelStore.Type.OK);
        assertThat(ok.version()).isEqualTo(9L);
        assertThat(ok.total()).isEqualTo(3);
        assertThat(ok.success()).isEqualTo(1);
        assertThat(ok.card()).isEqualTo(3L);
        assertThat(ok.itemsJson()).hasSize(2);

        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of("STALE"));
        assertThat(store.read("LL1", null, 0, 20).type()).isEqualTo(InquiryReadModelStore.Type.MISS);
    }
}