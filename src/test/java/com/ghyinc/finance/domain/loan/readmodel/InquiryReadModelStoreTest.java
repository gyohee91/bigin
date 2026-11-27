package com.ghyinc.finance.domain.loan.readmodel;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import com.ghyinc.finance.domain.loan.readmodel.InquiryReadModelStore.Type;
import com.ghyinc.finance.domain.loan.readmodel.InquiryReadModelStore.ReadResult;
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
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private InquiryReadModelStore store(boolean enabled) {
        return this.store(enabled, new ObjectMapper());
    }

    private InquiryReadModelStore store(boolean enabled, ObjectMapper objectMapper) {
        return new InquiryReadModelStore(redis, objectMapper, new PollingMetrics(meterRegistry),
                enabled, 3600, 15, 3000);
    }

    private double counter(String result) {
        return meterRegistry.get("polling.readmodel").tag("result", result).counter().count();
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
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(-1L);

        this.store(true).apply(InquiryReadModelEvent.delta("LL1", null,
                List.of(this.dto("LR1", PartnerInquiryStatus.SEND_SUCCESS, null))));

        ArgumentCaptor<Object[]> argumentCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), anyList(), argumentCaptor.capture());
        Object[] objects = argumentCaptor.getValue();
        assertThat(Arrays.stream(objects).allMatch(String.class::isInstance)).isTrue();
        assertThat(objects[0]).isEqualTo("D");
        assertThat(objects[5]).isEqualTo("0");      // deadline (delta 는 0 = 설정 안 함)
        assertThat(objects[6]).isEqualTo("LR1");    // member
        assertThat(objects[7]).isEqualTo("-1");     // null amount -> -1
        assertThat(objects[8]).isEqualTo("1");      // stage
    }

    @Test
    @DisplayName("Redis 오류 후 backoff 동안 Redis를 다시 호출하지 않는다")
    void read_shouldSkipRedis_duringBackoffAfterError() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("boom"));
        InquiryReadModelStore store = this.store(true);

        assertThat(store.read("LL1", null, 0, 20).type()).isEqualTo(Type.DOWN);
        assertThat(store.read("LL1", null, 0, 20).type()).isEqualTo(Type.DOWN);

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
                .thenReturn(List.of("SAME", "77", "IN_PROGRESS", "1700000060000"));
        ReadResult same = store.read("LL1", 77L, 0, 20);
        assertThat(same.type()).isEqualTo(Type.SAME);
        assertThat(same.version()).isEqualTo(77L);
        assertThat(same.status()).isEqualTo("IN_PROGRESS");
        assertThat(same.deadlineAtMs()).isEqualTo(1700000060000L);

        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of("OK", "9", "3", "1", "IN_PROGRESS", "3", "1700000060000", "{\"a\":1}", "{\"b\":2}"));
        ReadResult ok = store.read("LL1", null, 0, 20);
        assertThat(ok.type()).isEqualTo(Type.OK);
        assertThat(ok.version()).isEqualTo(9L);
        assertThat(ok.total()).isEqualTo(3);
        assertThat(ok.success()).isEqualTo(1);
        assertThat(ok.card()).isEqualTo(3L);
        assertThat(ok.deadlineAtMs()).isEqualTo(1700000060000L);
        assertThat(ok.itemsJson()).hasSize(2);

        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of("STALE"));
        assertThat(store.read("LL1", null, 0, 20).type()).isEqualTo(Type.MISS);
    }

    @Test
    @DisplayName("seed 는 deadline 을 ARGV[6] 에 문자열로 전달한다")
    void upsert_shouldPassDeadlineAsArgv6() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        this.store(true).upsert("LL1", true, 2, "IN_PROGRESS",
                List.of(this.dto("LR1", PartnerInquiryStatus.PENDING, 100L)), 1700000060000L);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), anyList(), captor.capture());
        Object[] a = captor.getValue();
        assertThat(a[0]).isEqualTo("S");
        assertThat(a[5]).isEqualTo("1700000060000");
        assertThat(a[6]).isEqualTo("LR1");
    }

    @Test
    @DisplayName("직렬화 실패는 write_fail_serialize 로만 세고, Redis 를 호출하지 않으며 down 처리하지 않는다")
    void upsert_serializationFailure_doesNotMarkDown() throws Exception {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new JsonMappingException(null, "boom"));
        InquiryReadModelStore store = this.store(true, broken);

        boolean written = store.upsert("LL1", true, 1, "IN_PROGRESS",
                List.of(this.dto("LR1", PartnerInquiryStatus.PENDING, 100L)), 0L);

        assertThat(written).isFalse();
        assertThat(this.counter("write_fail_serialize")).isEqualTo(1.0);
        assertThat(this.counter("write_fail")).isZero();
        verifyNoInteractions(redis);

        // down 이 아니므로 이어지는 읽기는 Redis 로 간다
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of());
        assertThat(store.read("LL1", null, 0, 20).type()).isEqualTo(Type.MISS);
        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("Redis 쓰기 실패는 write_fail 로 세고 backoff 에 들어간다. 이후 쓰기는 write_skipped_down 으로 센다")
    void upsert_redisFailure_marksDown_andLaterWritesAreCountedAsSkipped() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("boom"));
        InquiryReadModelStore store = this.store(true);
        InquiryReadModelEvent event = InquiryReadModelEvent.delta("LL1", null,
                List.of(this.dto("LR1", PartnerInquiryStatus.SUCCESS, 100L)));

        store.apply(event);
        store.apply(event);

        assertThat(this.counter("write_fail")).isEqualTo(1.0);
        assertThat(this.counter("write_skipped_down")).isEqualTo(1.0);
        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("플래그 OFF 이면 skipped_down 도 세지 않는다")
    void apply_disabled_doesNotCountSkipped() {
        this.store(false).apply(InquiryReadModelEvent.delta("LL1", null,
                List.of(this.dto("LR1", PartnerInquiryStatus.SUCCESS, 100L))));

        assertThat(this.counter("write_skipped_down")).isZero();
    }

    @Test
    @DisplayName("offset 은 int 오버플로 없이 계산한다")
    void read_offsetDoesNotOverflow() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of());

        this.store(true).read("LL1", null, 100_000, 100_000);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), anyList(), captor.capture());
        assertThat(captor.getValue()[1]).isEqualTo("10000000000");   // clientVer, offset, limit, now, reconcile
    }
}
