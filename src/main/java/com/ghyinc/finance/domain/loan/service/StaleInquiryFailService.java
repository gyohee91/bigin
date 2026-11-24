package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.config.StaleInquiryProperties;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.readmodel.InquiryReadModelEvent;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * stale 조회건을 FAILED 로 바꾸는 갱신 로직. 호출 경로별로 세 가지 진입점을 제공한다.
 *
 * <ul>
 *     <li>{@link #failAllIfStale} - 배치의 묶음 처리 (id 목록 일괄 UPDATE)</li>
 *     <li>{@link #failIfStale} - 배치의 건별 폴백 (묶음이 실패했을 때)</li>
 *     <li>{@link #repairIfStale} - 폴링 API 의 read-repair (inquiryNo 단건)</li>
 * </ul>
 *
 * <h3>공통 규칙</h3>
 * <ul>
 *     <li><b>CAS</b>: 모든 UPDATE 는 "기대 상태이고 {@code updatedAt} 이 기준 시각보다 오래됨"일 때만
 *         반영된다. 그 사이 정상 진행되었거나 다른 인스턴스·경로가 먼저 처리한 건은 0건으로 건너뛰므로
 *         배치와 read-repair 가 겹쳐도 안전하다.</li>
 *     <li><b>dedup_key 해제</b>: FAILED 로 바뀔 때 쿼리에서 {@code dedup_key} 를 null 로 만들어 당일
 *         재조회를 허용한다. 벌크 UPDATE 는 엔티티 메서드를 거치지 않으므로 쿼리가 직접 한다.</li>
 *     <li><b>updatedAt 직접 갱신</b>: 벌크 UPDATE 는 JPA Auditing 을 타지 않아 {@code updatedAt} 을
 *         호출자가 넘긴 {@code now} 로 설정한다.</li>
 *     <li><b>읽기모델 반영</b>: 갱신 후 FAILED delta 이벤트를 발행한다. 리스너가 AFTER_COMMIT 에 Redis
 *         읽기모델을 갱신하므로, 이벤트는 트랜잭션이 커밋될 때만 소비된다.</li>
 * </ul>
 *
 * <p>알려진 한계: FAILED 로 바뀐 뒤 금융사 응답이 늦게 도착하면 결과 반영({@code applyResults})이
 * 상태를 덮어쓸 수 있다(상태 가드 없음). 또한 이 경로로 FAILED 가 된 건은 알림을 발송하지 않는다.</p>
 *
 * @see com.ghyinc.finance.domain.loan.scheduler.StaleInquiryRecoveryScheduler
 */
@Service
@RequiredArgsConstructor
public class StaleInquiryFailService {
    private final LoanLimitInquiryRepository loanLimitInquiryRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final StaleInquiryProperties properties;

    /**
     * 한 건을 CAS 로 FAILED 처리한다. 배치의 묶음 처리가 실패했을 때의 건별 폴백 경로.
     *
     * @param id        대상 PK
     * @param inquiryNo 읽기모델 이벤트에 쓰는 업무 식별번호 (null 이면 이벤트가 엉뚱한 키를 갱신하므로 반드시 전달)
     * @param expected  기대하는 현재 상태. 다르면 갱신하지 않는다
     * @param before    이 시각보다 오래 갱신되지 않은 경우에만 갱신한다
     * @return 이번 호출이 FAILED 로 바꿨으면 true. 이미 정상 진행되었거나 다른 주체가 처리했으면 false
     */
    @Transactional
    public boolean failIfStale(Long id, String inquiryNo, InquiryStatus expected, LocalDateTime before) {
        int updated = loanLimitInquiryRepository.failIfStale(id, expected, before, LocalDateTime.now());
        if (updated == 0) {
            return false;       // 그 사이 정상 진행되었거나 다른 인스턴스가 처리함
        }

        // 폴링 읽기모델(Redis) 외에도 FAILED 반영. AFTER_COMMIT 리스너가 처리한다 (markFailed와 동일 방식)
        applicationEventPublisher.publishEvent(InquiryReadModelEvent.delta(inquiryNo, InquiryStatus.FAILED.name(), List.of()));
        return true;
    }

    /**
     * id 묶음을 한 번의 UPDATE 로 CAS 처리한다. 건별 트랜잭션보다 훨씬 적은 왕복으로 대량 적체를 비운다.
     *
     * <p>CAS 결과가 건별로 반환되지 않으므로, 어떤 건이 바뀌었는지는 같은 {@code now} 이후에
     * FAILED 로 갱신된 건을 다시 조회({@code findInquiryNosFailedSince})해서 알아내고 그 건들에만
     * 읽기모델 이벤트를 발행한다. UPDATE 의 {@code now} 와 재조회의 {@code since} 는 같은 값이어야 하며,
     * DB 컬럼 정밀도로 반올림되어 {@code >=} 비교가 어긋나지 않도록 밀리초로 절삭한다.</p>
     *
     * <p>예외는 삼키지 않고 전파한다. 호출자(스케줄러)가 건별 처리로 전환할 수 있어야 하기 때문이다.</p>
     *
     * @param ids      대상 PK 목록
     * @param expected 기대하는 현재 상태
     * @param before   이 시각보다 오래 갱신되지 않은 건만 갱신
     * @return 실제로 갱신된 건수 (재조회 건수가 아니라 UPDATE 결과)
     */
    @Transactional
    public int failAllIfStale(List<Long> ids, InquiryStatus expected, LocalDateTime before) {
        if (ids.isEmpty())   return 0;

        // 밀리초로 자른다: DB 컬럼 정밀도(마이크로초 등)에 따라 now가 반올림되면 아래 >= 비교가 어긋난다
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);

        int updated = loanLimitInquiryRepository.failAllIfStale(ids, expected, before, now);
        if (updated == 0)   return 0;

        for (String inquiryNo : loanLimitInquiryRepository.findInquiryNosFailedSince(ids, now)) {
            applicationEventPublisher.publishEvent(
                    InquiryReadModelEvent.delta(inquiryNo, InquiryStatus.FAILED.name(), List.of()));
        }
        return updated;
    }

    /**
     * 폴링 API 에서 호출하는 read-repair. 해당 조회건이 stale 이면 즉시 FAILED 로 바꾼다.
     *
     * <p>상태별로 서로 다른 타임아웃(accepted/pending/in-progress)을 같은 {@code now} 에서 빼서
     * 기준 시각을 만들고, 한 번의 UPDATE 로 "현재 상태에 해당하는 기준보다 오래된 경우"에만 갱신한다.
     * 종료 상태(SUCCESS, PARTIAL_SUCCESS, FAILED)는 어떤 조건에서도 건드리지 않는다.</p>
     *
     * <p>이 메서드의 예외는 호출자(폴링 서비스)가 삼킨다. 응답은 정상으로 내려가고 배치가 backstop 이 된다.</p>
     *
     * @param inquiryNo 폴링 중인 업무 식별번호
     * @return 이번 호출이 FAILED 로 바꿨으면 true, stale 이 아니거나 이미 처리되었으면 false
     */
    @Transactional
    public boolean repairIfStale(String inquiryNo) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        int updated = loanLimitInquiryRepository.failIfStaleByInquiryNo(inquiryNo,
                now.minus(properties.acceptedTimeout()),
                now.minus(properties.pendingTimeout()),
                now.minus(properties.inProgressTimeout()),
                now);
        if (updated == 0)   return false;
        applicationEventPublisher.publishEvent(
                InquiryReadModelEvent.delta(inquiryNo, InquiryStatus.FAILED.name(), List.of())
        );

        return true;
    }
}
