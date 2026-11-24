package com.ghyinc.finance.domain.loan.scheduler;

import com.ghyinc.finance.domain.loan.dto.StaleInquiryView;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.service.StaleInquiryFailService;
import com.ghyinc.finance.domain.loan.config.StaleInquiryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 중간 상태에서 멈춘 한도조회건을 주기적으로 FAILED 로 복구하는 배치.
 *
 * <p>비동기 처리 중 서버 재시작, 이벤트 유실, 스레드 풀 거절, 외부 장애 등으로 조회건이
 * {@code ACCEPTED / PENDING / IN_PROGRESS} 에 남을 수 있다. 그대로 두면 FE 폴링이 끝나지 않고,
 * {@code dedup_key} 도 해제되지 않아 당일 재조회까지 막힌다. 이 배치가 일정 시간 갱신되지 않은 건을
 * FAILED 로 바꾸고 {@code dedup_key} 를 해제해 재조회를 허용한다.</p>
 *
 * <h3>stale 판정</h3>
 * <p>상태별 타임아웃({@link StaleInquiryProperties#acceptedTimeout()} 등)보다 오래
 * {@code updatedAt} 이 갱신되지 않은 건. 기준 시각({@code before})은 한 사이클 시작 시점에 상태별로
 * 한 번만 계산해 그 사이클의 모든 조회·갱신에 동일하게 쓴다. 타임아웃 값은 실측 기반이 아니라 기본값이다.</p>
 *
 * <h3>처리 방식</h3>
 * <ol>
 *     <li>상태(ACCEPTED → PENDING → IN_PROGRESS)별로 stale 건을 {@code batchSize} 단위로 읽는다.
 *         첫 페이지는 {@code findStale}, 이후는 마지막 행의 {@code (updatedAt, id)} 를 커서로 한
 *         {@code findStaleAfter}(keyset) 로 읽는다.</li>
 *     <li>읽은 id 묶음을 {@link StaleInquiryFailService#failAllIfStale} 로 한 번에 갱신한다.
 *         묶음이 예외로 실패하면 같은 건들을 {@link StaleInquiryFailService#failIfStale} 로 건별 재시도한다.</li>
 *     <li>한 사이클은 {@link StaleInquiryProperties#maxRunDuration()} 시간 예산 안에서만 반복한다.</li>
 * </ol>
 *
 * <h3>keyset 을 쓰는 이유</h3>
 * <p>"처리된 행은 FAILED 가 되어 조회 대상에서 빠지니 첫 페이지만 반복해서 읽는다"는 방식은,
 * 계속 처리에 실패하는 행이 {@code updatedAt} 오름차순의 앞쪽에 남아 있으면 매번 같은 행만 읽고
 * 뒤쪽의 정상 건에 도달하지 못한다. 커서를 마지막으로 읽은 행 뒤로 전진시키면 실패한 행을 건너뛰어
 * 뒤쪽을 처리할 수 있고, 커서가 매번 엄격히 전진하므로 무한 루프도 없다. 실패한 행은 다음 사이클에
 * 다시 시도되며 {@code inquiry.recovery.error} 지표로 확인한다.</p>
 *
 * <h3>다중 인스턴스</h3>
 * <p>ShedLock 으로 한 시점에 한 인스턴스만 실행한다. {@code lockAtLeastFor} 동안은 다음 실행이 막히므로
 * 실제 최소 실행 간격은 {@code max(fixedDelay, lockAtLeast)} 에 가깝다. 갱신은 모두 상태·시각 CAS
 * UPDATE 라서 락이 풀린 뒤 겹치거나 read-repair 와 경쟁해도 중복 처리되지 않는다.</p>
 *
 * <h3>지표</h3>
 * <ul>
 *     <li>{@code inquiry.recovery.failed{from}} - FAILED 로 바꾼 건수</li>
 *     <li>{@code inquiry.recovery.error{from}} - 묶음/건별 처리 실패 횟수 (poison row 감지용)</li>
 * </ul>
 *
 * @see StaleInquiryFailService
 * @see StaleInquiryProperties
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleInquiryRecoveryScheduler {
    private final StaleInquiryFailService staleInquiryFailService;
    private final LoanLimitInquiryRepository loanLimitInquiryRepository;
    private final StaleInquiryProperties properties;
    private final MeterRegistry meterRegistry;

    /** keyset 커서. 직전 페이지의 마지막 행 위치로, 이 값보다 뒤의 행만 다음 페이지로 읽는다 */
    private record Cursor(LocalDateTime updatedAt, Long id) {}

    /**
     * 한 사이클 실행. 세 상태를 순서대로 처리하며, 시간 예산({@code maxRunDuration})은 세 상태가 함께 쓴다.
     * 앞 상태에서 예산을 다 쓰면 뒤 상태는 다음 사이클로 넘어간다.
     *
     * <p>주기와 락 최소 보유 시간은 {@code app.inquiry-recovery.fixed-delay},
     * {@code app.inquiry-recovery.lock-at-least} 로 조정한다. {@code lockAtMostFor} 는 실행이 비정상적으로
     * 멈춰도 락이 영원히 남지 않도록 하는 상한이며, 한 사이클의 정상 소요 시간보다 충분히 길어야 한다.</p>
     */
    @Scheduled(fixedDelayString = "${app.inquiry-recovery.fixed-delay:PT10S}")
    @SchedulerLock(
            name = "loanLimitInquiryRecoveryScheduler_failStaleInquiries",
            lockAtLeastFor = "${app.inquiry-recovery.lock-at-least:PT20S}",
            lockAtMostFor = "5m"
    )
    public void failStaleInquiries() {
        // 벽시계 변경의 영향을 받지 않도록 시간 예산은 nanoTime 으로 계산한다
        long deadlineNanos = System.nanoTime() + properties.maxRunDuration().toNanos();
        // stale 기준 시각의 출발점. 상태별 타임아웃을 빼서 사이클 내내 같은 값으로 쓴다
        LocalDateTime now = LocalDateTime.now();
        process(InquiryStatus.ACCEPTED, now.minus(properties.acceptedTimeout()), deadlineNanos);
        process(InquiryStatus.PENDING, now.minus(properties.pendingTimeout()), deadlineNanos);
        process(InquiryStatus.IN_PROGRESS, now.minus(properties.inProgressTimeout()), deadlineNanos);
    }

    /**
     * 한 상태의 stale 건을 시간 예산이 남아 있는 동안 페이지 단위로 처리한다.
     *
     * <p>종료 조건: 조회 결과가 비었을 때, 마지막 페이지(조회 건수가 {@code batchSize} 미만)일 때,
     * 시간 예산을 다 썼을 때. 한 페이지에서 한 건도 갱신하지 못해도(진전 0) 멈추지 않고 커서만 전진시켜
     * 다음 페이지를 읽는다. 그래야 앞쪽에 처리 불가한 행이 있어도 뒤쪽 정상 건이 굶지 않는다.</p>
     *
     * @param status        처리할 상태 (CAS 의 기대 상태로도 전달된다)
     * @param before        이 시각보다 오래 갱신되지 않은 건만 대상
     * @param deadlineNanos {@link System#nanoTime()} 기준 종료 시각
     */
    private void process(InquiryStatus status, LocalDateTime before, long deadlineNanos) {
        int total = 0;
        Cursor cursor = null;
        while (System.nanoTime() < deadlineNanos) {
            PageRequest pageRequest = PageRequest.of(0, properties.batchSize());
            List<StaleInquiryView> staleInquiryViews = (cursor == null)
                    ? loanLimitInquiryRepository.findStale(status, before, pageRequest)
                    : loanLimitInquiryRepository.findStaleAfter(status, before, cursor.updatedAt(), cursor.id(), pageRequest);
            if (staleInquiryViews.isEmpty())  break;   // 더 읽을 stale 건이 없음

            List<Long> ids = staleInquiryViews.stream().map(StaleInquiryView::getId).toList();
            // 실제로 FAILED 로 바뀐 건수. 읽은 뒤 갱신 전에 정상 진행된 건은 CAS 에서 빠져 읽은 건수보다 적을 수 있다
            int failed = 0;
            try {
                failed = staleInquiryFailService.failAllIfStale(ids, status, before);
            } catch (Exception e) {
                log.error("[한도조회 복구] 묶음 처리 실패, 건별 처리로 전환. status = {}, size={}", status, ids.size(), e);
                meterRegistry.counter("inquiry.recovery.error", "from", status.name()).increment();
                failed = failOneByOne(staleInquiryViews, status, before, deadlineNanos);
            }
            total += failed;

            // 처리 성공/실패와 무관하게 커서를 이 페이지의 마지막 행 뒤로 전진시킨다.
            // 실패한 행이 앞쪽에 남아 있어도 다음 페이지는 그 뒤부터 읽으므로 뒤쪽 건이 굶지 않는다.
            StaleInquiryView lastView = staleInquiryViews.get(staleInquiryViews.size() - 1);
            cursor = new Cursor(lastView.getUpdatedAt(), lastView.getId());

            if (staleInquiryViews.size() < properties.batchSize()) break;  // 마지막 페이지
        }
        if (total > 0) {
            log.warn("[한도조회 복구] {} 상태에서 멈춘 {}건을 FAILED 처리", status, total);
            meterRegistry.counter("inquiry.recovery.failed", "from", status.name()).increment(total);
        }
    }

    /**
     * 묶음 갱신이 실패했을 때의 폴백. 같은 건들을 건별 트랜잭션으로 다시 시도한다.
     * 한 건이 실패해도(poison row) 나머지는 계속 처리하고, 실패 건은 {@code inquiry.recovery.error} 로 센다.
     * 실패한 행은 stale 상태로 남아 다음 사이클에 다시 시도된다.
     *
     * <p>행 루프도 사이클의 시간 예산({@code deadlineNanos})을 지킨다. DB 가 느린 상황에서 chunk 하나(batchSize 건)를
     * 건별로 처리하다가 예산을 크게 넘기지 않도록, 예산을 다 쓰면 남은 행은 처리하지 않고 중단한다.
     * 처리하지 못한 행은 stale 상태로 남아 다음 사이클에 다시 시도된다.</p>
     *
     * @param deadlineNanos {@link System#nanoTime()} 기준 종료 시각
     * @return 건별 처리에서 실제로 FAILED 로 바뀐 건수
     */
    private int failOneByOne(List<StaleInquiryView> staleInquiryViews, InquiryStatus status, LocalDateTime before, long deadlineNanos) {
        int failed = 0;
        for (int i = 0; i < staleInquiryViews.size(); i++) {
            if (System.nanoTime() >= deadlineNanos) {
                log.warn("[한도조회 복구] 건별 처리 중 시간 예산 소진. status={}, 처리 {}건 / 남은 {}건은 다음 사이클로",
                        status, i, staleInquiryViews.size() - i);
                break;
            }
            StaleInquiryView view = staleInquiryViews.get(i);
            try {
                if (staleInquiryFailService.failIfStale(view.getId(), view.getInquiryNo(), status, before))  failed++;
            } catch (Exception e) {
                log.error("[한도조회 복구] 건별 처리 실패 id={}, status={}", view.getId(), status, e);
                meterRegistry.counter("inquiry.recovery.error", "from", status.name()).increment();
            }
        }
        return failed;
    }
}
