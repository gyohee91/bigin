package com.ghyinc.finance.domain.loan.repository;

import com.ghyinc.finance.domain.loan.dto.StaleInquiryView;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * dedup_key unique 제약과 상태 CAS 쿼리가 실제 DB(H2)에서 의도대로 동작하는지 검증한다.
 * 운영 DB 와 NULL 의 unique 처리(여러 개 허용)가 같은지는 운영 DB 에서 별도로 확인해야 한다.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never"
})
class LoanLimitInquiryRepositoryTest {

    private static final String DEDUP_KEY = "1:PERSONAL_CREDIT:20260101";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 12, 0, 0);

    @Autowired
    private LoanLimitInquiryRepository repository;

    @Autowired
    private TestEntityManager em;

    private LoanLimitInquiry build(String inquiryNo, InquiryStatus status, String dedupKey) {
        return LoanLimitInquiry.builder()
                .inquiryNo(inquiryNo)
                .userId(1L)
                .loanType(LoanType.PERSONAL_CREDIT)
                .status(status)
                .dedupKey(dedupKey)
                .build();
    }

    private LoanLimitInquiry save(String inquiryNo, InquiryStatus status, String dedupKey) {
        return repository.saveAndFlush(build(inquiryNo, status, dedupKey));
    }

    // ─── dedup_key unique ─────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 dedupKey 의 두 번째 행은 unique 제약으로 거절된다")
    void dedupKey_sameKey_rejected() {
        save("LL_A", InquiryStatus.PENDING, DEDUP_KEY);

        assertThatThrownBy(() -> save("LL_B", InquiryStatus.PENDING, DEDUP_KEY))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("dedupKey 가 null 인 행(FAILED 등 슬롯 해제된 건)은 여러 개 저장할 수 있다")
    void dedupKey_null_multipleRowsAllowed() {
        save("LL_A", InquiryStatus.FAILED, null);
        save("LL_B", InquiryStatus.FAILED, null);
        save("LL_C", InquiryStatus.FAILED, null);

        assertThat(repository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("날짜가 다른 dedupKey 는 같은 사용자·유형이어도 저장할 수 있다 (하루 1회 제한)")
    void dedupKey_differentDay_allowed() {
        save("LL_A", InquiryStatus.SUCCESS, "1:PERSONAL_CREDIT:20260101");
        save("LL_B", InquiryStatus.PENDING, "1:PERSONAL_CREDIT:20260102");

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("existsByDedupKey - 활성 건이 있으면 true, 없으면 false")
    void existsByDedupKey() {
        save("LL_A", InquiryStatus.ACCEPTED, DEDUP_KEY);

        assertThat(repository.existsByDedupKey(DEDUP_KEY)).isTrue();
        assertThat(repository.existsByDedupKey("2:PERSONAL_CREDIT:20260101")).isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("동시에 같은 dedupKey 로 접수하면 정확히 한 건만 성공하고 나머지는 unique 위반")
    void concurrentClaim_exactlyOneSucceeds() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String inquiryNo = "LL_CONCURRENT_" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        repository.saveAndFlush(build(inquiryNo, InquiryStatus.ACCEPTED, DEDUP_KEY));
                        succeeded.incrementAndGet();
                    } catch (DataIntegrityViolationException e) {
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            for (Future<Object> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            assertThat(succeeded.get()).isEqualTo(1);
            assertThat(rejected.get()).isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
            repository.deleteAll();   // 이 테스트만 트랜잭션 밖에서 커밋되므로 직접 정리
        }
    }

    // ─── transit (상태 CAS) ───────────────────────────────────────────────────

    @Test
    @DisplayName("transit - ACCEPTED→PENDING 은 한 번만 성공하고 두 번째는 0건 (CAS)")
    void transit_acceptedToPending_succeedsOnlyOnce() {
        save("LL_A", InquiryStatus.ACCEPTED, DEDUP_KEY);

        int first = repository.transit("LL_A", InquiryStatus.ACCEPTED, InquiryStatus.PENDING, NOW);
        int second = repository.transit("LL_A", InquiryStatus.ACCEPTED, InquiryStatus.PENDING, NOW);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        LoanLimitInquiry reloaded = repository.findByInquiryNo("LL_A").orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InquiryStatus.PENDING);
        assertThat(reloaded.getDedupKey()).isEqualTo(DEDUP_KEY);   // 완료되어도 슬롯은 유지
    }

    @Test
    @DisplayName("transit - 현재 상태가 from 과 다르면 0건이고 상태를 바꾸지 않는다")
    void transit_wrongCurrentStatus_noChange() {
        save("LL_A", InquiryStatus.FAILED, null);

        int updated = repository.transit("LL_A", InquiryStatus.ACCEPTED, InquiryStatus.PENDING, NOW);

        assertThat(updated).isZero();
        assertThat(repository.findByInquiryNo("LL_A").orElseThrow().getStatus()).isEqualTo(InquiryStatus.FAILED);
    }

    // ─── failIfAccepted (선점 반납) ───────────────────────────────────────────

    @Test
    @DisplayName("failIfAccepted - ACCEPTED 를 FAILED 로 바꾸고 dedupKey 를 비워, 같은 키로 다시 접수할 수 있다")
    void failIfAccepted_releasesSlot() {
        save("LL_A", InquiryStatus.ACCEPTED, DEDUP_KEY);

        int updated = repository.failIfAccepted("LL_A", NOW);

        assertThat(updated).isEqualTo(1);
        LoanLimitInquiry failed = repository.findByInquiryNo("LL_A").orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(InquiryStatus.FAILED);
        assertThat(failed.getDedupKey()).isNull();

        // 슬롯이 풀렸으므로 같은 날 재요청의 접수가 가능하다
        save("LL_B", InquiryStatus.ACCEPTED, DEDUP_KEY);
        assertThat(repository.existsByDedupKey(DEDUP_KEY)).isTrue();
    }

    @Test
    @DisplayName("failIfAccepted - ACCEPTED 가 아니면(이미 완료 PENDING) 아무것도 바꾸지 않는다")
    void failIfAccepted_notAccepted_noChange() {
        save("LL_A", InquiryStatus.PENDING, DEDUP_KEY);

        int updated = repository.failIfAccepted("LL_A", NOW);

        assertThat(updated).isZero();
        LoanLimitInquiry unchanged = repository.findByInquiryNo("LL_A").orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(InquiryStatus.PENDING);
        assertThat(unchanged.getDedupKey()).isEqualTo(DEDUP_KEY);
    }

    // ─── 엔티티 더티체킹으로 FAILED 전이 ──────────────────────────────────────

    @Test
    @DisplayName("엔티티 updateInquiryStatus(FAILED) 가 flush 되면 DB 의 dedup_key 도 null 이 되어 같은 키로 재접수 가능")
    void entityFailedTransition_releasesSlotInDatabase() {
        save("LL_A", InquiryStatus.IN_PROGRESS, DEDUP_KEY);
        LoanLimitInquiry inquiry = repository.findByInquiryNo("LL_A").orElseThrow();

        inquiry.updateInquiryStatus(InquiryStatus.FAILED);
        em.flush();
        em.clear();

        LoanLimitInquiry reloaded = repository.findByInquiryNo("LL_A").orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InquiryStatus.FAILED);
        assertThat(reloaded.getDedupKey()).isNull();
        save("LL_B", InquiryStatus.ACCEPTED, DEDUP_KEY);   // unique 위반 없이 저장되어야 한다
    }

    // ─── stale 복구 쿼리 ──────────────────────────────────────────────────────
    // @DataJpaTest 슬라이스에서는 JPA Auditing 이 꺼져 있을 수 있어 updatedAt 을 직접 지정한다.

    private void setUpdatedAt(String inquiryNo, LocalDateTime at) {
        em.getEntityManager()
                .createQuery("UPDATE LoanLimitInquiry t SET t.updatedAt = :at WHERE t.inquiryNo = :no")
                .setParameter("at", at)
                .setParameter("no", inquiryNo)
                .executeUpdate();
        em.clear();
    }

    private LoanLimitInquiry saveAt(String inquiryNo, InquiryStatus status, String dedupKey, LocalDateTime updatedAt) {
        LoanLimitInquiry saved = save(inquiryNo, status, dedupKey);
        this.setUpdatedAt(inquiryNo, updatedAt);
        return saved;
    }

    private LoanLimitInquiry reload(String inquiryNo) {
        em.clear();
        return repository.findByInquiryNo(inquiryNo).orElseThrow();
    }

    @Test
    @DisplayName("findStale - 기준 시각보다 오래된 해당 상태 건만, updatedAt 오름차순으로 반환한다")
    void findStale_filtersByStatusAndTime_orderedByUpdatedAtAsc() {
        LocalDateTime before = NOW.minusMinutes(5);
        saveAt("LL_NEW", InquiryStatus.IN_PROGRESS, "k:20260101", NOW.minusMinutes(1));      // 최근 -> 제외
        saveAt("LL_OLD2", InquiryStatus.IN_PROGRESS, "k:20260102", NOW.minusMinutes(20));
        saveAt("LL_OLD1", InquiryStatus.IN_PROGRESS, "k:20260103", NOW.minusMinutes(30));
        saveAt("LL_OTHER", InquiryStatus.PENDING, "k:20260104", NOW.minusMinutes(30));       // 다른 상태 -> 제외
        saveAt("LL_DONE", InquiryStatus.SUCCESS, "k:20260105", NOW.minusMinutes(30));        // 종료 상태 -> 제외

        List<StaleInquiryView> stale = repository.findStale(InquiryStatus.IN_PROGRESS, before, PageRequest.of(0, 10));

        assertThat(stale).extracting(StaleInquiryView::getInquiryNo).containsExactly("LL_OLD1", "LL_OLD2");
        assertThat(stale).allSatisfy(v -> assertThat(v.getId()).isNotNull());
    }

    @Test
    @DisplayName("findStale - 기준 시각과 정확히 같은 updatedAt 은 stale 이 아니다 (엄격한 <)")
    void findStale_boundaryIsExclusive() {
        LocalDateTime before = NOW.minusMinutes(5);
        saveAt("LL_EQ", InquiryStatus.PENDING, "k:20260101", before);

        assertThat(repository.findStale(InquiryStatus.PENDING, before, PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    @DisplayName("findStale - Pageable 의 크기만큼만 오래된 순으로 가져온다")
    void findStale_respectsPageSize() {
        LocalDateTime before = NOW.minusMinutes(5);
        saveAt("LL_1", InquiryStatus.PENDING, "k:20260101", NOW.minusMinutes(40));
        saveAt("LL_2", InquiryStatus.PENDING, "k:20260102", NOW.minusMinutes(30));
        saveAt("LL_3", InquiryStatus.PENDING, "k:20260103", NOW.minusMinutes(20));

        List<StaleInquiryView> page = repository.findStale(InquiryStatus.PENDING, before, PageRequest.of(0, 2));

        assertThat(page).extracting(StaleInquiryView::getInquiryNo).containsExactly("LL_1", "LL_2");
    }

    @Test
    @DisplayName("failIfStale - 기대 상태이고 오래됐으면 FAILED 로 바꾸고 dedupKey 를 비운 뒤 updatedAt 을 now 로 갱신한다")
    void failIfStale_stale_failsAndReleasesSlot() {
        LoanLimitInquiry saved = saveAt("LL_A", InquiryStatus.IN_PROGRESS, DEDUP_KEY, NOW.minusMinutes(10));

        int updated = repository.failIfStale(saved.getId(), InquiryStatus.IN_PROGRESS, NOW.minusMinutes(5), NOW);

        assertThat(updated).isEqualTo(1);
        LoanLimitInquiry reloaded = reload("LL_A");
        assertThat(reloaded.getStatus()).isEqualTo(InquiryStatus.FAILED);
        assertThat(reloaded.getDedupKey()).isNull();
        assertThat(reloaded.getUpdatedAt()).isEqualTo(NOW);
        save("LL_B", InquiryStatus.ACCEPTED, DEDUP_KEY);     // 슬롯이 풀려 재접수 가능
    }

    @Test
    @DisplayName("failIfStale - 그 사이 갱신되어 최근이면 0건이고 아무것도 바꾸지 않는다")
    void failIfStale_recentlyUpdated_noChange() {
        LoanLimitInquiry saved = saveAt("LL_A", InquiryStatus.IN_PROGRESS, DEDUP_KEY, NOW.minusMinutes(1));

        int updated = repository.failIfStale(saved.getId(), InquiryStatus.IN_PROGRESS, NOW.minusMinutes(5), NOW);

        assertThat(updated).isZero();
        LoanLimitInquiry reloaded = reload("LL_A");
        assertThat(reloaded.getStatus()).isEqualTo(InquiryStatus.IN_PROGRESS);
        assertThat(reloaded.getDedupKey()).isEqualTo(DEDUP_KEY);
    }

    @Test
    @DisplayName("failIfStale - 기대 상태와 다르면(그 사이 SUCCESS 로 완료) 0건")
    void failIfStale_statusChanged_noChange() {
        LoanLimitInquiry saved = saveAt("LL_A", InquiryStatus.SUCCESS, DEDUP_KEY, NOW.minusMinutes(10));

        int updated = repository.failIfStale(saved.getId(), InquiryStatus.IN_PROGRESS, NOW.minusMinutes(5), NOW);

        assertThat(updated).isZero();
        assertThat(reload("LL_A").getStatus()).isEqualTo(InquiryStatus.SUCCESS);
    }

    @Test
    @DisplayName("failAllIfStale - 오래된 건만 일괄 FAILED 로 바꾸고, 최근 건·다른 상태 건은 건드리지 않는다")
    void failAllIfStale_onlyStaleRowsUpdated() {
        LoanLimitInquiry stale1 = saveAt("LL_S1", InquiryStatus.PENDING, "k:20260101", NOW.minusMinutes(10));
        LoanLimitInquiry stale2 = saveAt("LL_S2", InquiryStatus.PENDING, "k:20260102", NOW.minusMinutes(20));
        LoanLimitInquiry fresh = saveAt("LL_F", InquiryStatus.PENDING, "k:20260103", NOW.minusMinutes(1));
        LoanLimitInquiry otherStatus = saveAt("LL_O", InquiryStatus.IN_PROGRESS, "k:20260104", NOW.minusMinutes(30));

        int updated = repository.failAllIfStale(
                List.of(stale1.getId(), stale2.getId(), fresh.getId(), otherStatus.getId()),
                InquiryStatus.PENDING, NOW.minusMinutes(2), NOW);

        assertThat(updated).isEqualTo(2);
        assertThat(reload("LL_S1").getStatus()).isEqualTo(InquiryStatus.FAILED);
        assertThat(reload("LL_S1").getDedupKey()).isNull();
        assertThat(reload("LL_S2").getStatus()).isEqualTo(InquiryStatus.FAILED);
        assertThat(reload("LL_F").getStatus()).isEqualTo(InquiryStatus.PENDING);
        assertThat(reload("LL_O").getStatus()).isEqualTo(InquiryStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("findInquiryNosFailedSince - since 이후에 FAILED 가 된 건만 반환한다 (이미 예전에 실패한 건 제외)")
    void findInquiryNosFailedSince_onlyRowsFailedByThisUpdate() {
        LoanLimitInquiry stale = saveAt("LL_NOW", InquiryStatus.PENDING, "k:20260101", NOW.minusMinutes(10));
        LoanLimitInquiry alreadyFailed = saveAt("LL_BEFORE", InquiryStatus.FAILED, null, NOW.minusHours(1));
        LoanLimitInquiry fresh = saveAt("LL_FRESH", InquiryStatus.PENDING, "k:20260102", NOW.minusSeconds(10));
        List<Long> ids = List.of(stale.getId(), alreadyFailed.getId(), fresh.getId());

        repository.failAllIfStale(ids, InquiryStatus.PENDING, NOW.minusMinutes(2), NOW);
        em.clear();

        assertThat(repository.findInquiryNosFailedSince(ids, NOW)).containsExactly("LL_NOW");
    }

    @Test
    @DisplayName("failIfStaleByInquiryNo - 상태별로 서로 다른 기준 시각을 적용한다 (오래됐어도 해당 상태 기준 전이면 유지)")
    void failIfStaleByInquiryNo_appliesPerStatusThreshold() {
        // accepted 기준 -2m, pending 기준 -3m, in-progress 기준 -5m 으로 호출
        LocalDateTime acceptedBefore = NOW.minusMinutes(2);
        LocalDateTime pendingBefore = NOW.minusMinutes(3);
        LocalDateTime inProgressBefore = NOW.minusMinutes(5);

        saveAt("LL_ACC", InquiryStatus.ACCEPTED, "k:20260101", NOW.minusMinutes(4));        // 4m > 2m -> FAILED
        saveAt("LL_PEN_OK", InquiryStatus.PENDING, "k:20260102", NOW.minusMinutes(4));      // 4m > 3m -> FAILED
        saveAt("LL_PEN_KEEP", InquiryStatus.PENDING, "k:20260103", NOW.minusMinutes(2));    // 2m < 3m -> 유지
        saveAt("LL_IP_OK", InquiryStatus.IN_PROGRESS, "k:20260104", NOW.minusMinutes(6));   // 6m > 5m -> FAILED
        saveAt("LL_IP_KEEP", InquiryStatus.IN_PROGRESS, "k:20260105", NOW.minusMinutes(4)); // 4m < 5m -> 유지

        assertThat(repository.failIfStaleByInquiryNo("LL_ACC", acceptedBefore, pendingBefore, inProgressBefore, NOW)).isEqualTo(1);
        assertThat(repository.failIfStaleByInquiryNo("LL_PEN_OK", acceptedBefore, pendingBefore, inProgressBefore, NOW)).isEqualTo(1);
        assertThat(repository.failIfStaleByInquiryNo("LL_PEN_KEEP", acceptedBefore, pendingBefore, inProgressBefore, NOW)).isZero();
        assertThat(repository.failIfStaleByInquiryNo("LL_IP_OK", acceptedBefore, pendingBefore, inProgressBefore, NOW)).isEqualTo(1);
        assertThat(repository.failIfStaleByInquiryNo("LL_IP_KEEP", acceptedBefore, pendingBefore, inProgressBefore, NOW)).isZero();

        assertThat(reload("LL_ACC").getStatus()).isEqualTo(InquiryStatus.FAILED);
        assertThat(reload("LL_PEN_OK").getStatus()).isEqualTo(InquiryStatus.FAILED);
        assertThat(reload("LL_PEN_KEEP").getStatus()).isEqualTo(InquiryStatus.PENDING);
        assertThat(reload("LL_IP_OK").getStatus()).isEqualTo(InquiryStatus.FAILED);
        assertThat(reload("LL_IP_KEEP").getStatus()).isEqualTo(InquiryStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("failIfStaleByInquiryNo - FAILED 로 바꾸면 dedupKey 를 비우고 updatedAt 을 now 로 갱신해 재접수가 가능하다")
    void failIfStaleByInquiryNo_releasesSlotAndSetsUpdatedAt() {
        saveAt("LL_A", InquiryStatus.IN_PROGRESS, DEDUP_KEY, NOW.minusMinutes(10));

        int updated = repository.failIfStaleByInquiryNo("LL_A", NOW.minusMinutes(2), NOW.minusMinutes(2), NOW.minusMinutes(5), NOW);

        assertThat(updated).isEqualTo(1);
        LoanLimitInquiry reloaded = reload("LL_A");
        assertThat(reloaded.getStatus()).isEqualTo(InquiryStatus.FAILED);
        assertThat(reloaded.getDedupKey()).isNull();
        assertThat(reloaded.getUpdatedAt()).isEqualTo(NOW);
        save("LL_B", InquiryStatus.ACCEPTED, DEDUP_KEY);
    }

    @Test
    @DisplayName("failIfStaleByInquiryNo - 종료 상태(SUCCESS, PARTIAL_SUCCESS, FAILED)는 아무리 오래돼도 건드리지 않는다")
    void failIfStaleByInquiryNo_terminalStatusesNeverTouched() {
        saveAt("LL_S", InquiryStatus.SUCCESS, "k:20260101", NOW.minusDays(1));
        saveAt("LL_P", InquiryStatus.PARTIAL_SUCCESS, "k:20260102", NOW.minusDays(1));
        saveAt("LL_F", InquiryStatus.FAILED, null, NOW.minusDays(1));
        LocalDateTime far = NOW.minusHours(1);

        assertThat(repository.failIfStaleByInquiryNo("LL_S", far, far, far, NOW)).isZero();
        assertThat(repository.failIfStaleByInquiryNo("LL_P", far, far, far, NOW)).isZero();
        assertThat(repository.failIfStaleByInquiryNo("LL_F", far, far, far, NOW)).isZero();
        assertThat(reload("LL_S").getStatus()).isEqualTo(InquiryStatus.SUCCESS);
        assertThat(reload("LL_P").getStatus()).isEqualTo(InquiryStatus.PARTIAL_SUCCESS);
    }

    @Test
    @DisplayName("failIfStaleByInquiryNo - 존재하지 않는 inquiryNo 는 0건")
    void failIfStaleByInquiryNo_unknownInquiryNo_zero() {
        assertThat(repository.failIfStaleByInquiryNo("NONE", NOW, NOW, NOW, NOW)).isZero();
    }

    @Test
    @DisplayName("failIfStaleByInquiryNo - 한 번 FAILED 가 되면 두 번째 호출은 0건 (CAS)")
    void failIfStaleByInquiryNo_secondCallIsNoop() {
        saveAt("LL_A", InquiryStatus.PENDING, DEDUP_KEY, NOW.minusMinutes(10));
        LocalDateTime before = NOW.minusMinutes(2);

        int first = repository.failIfStaleByInquiryNo("LL_A", before, before, before, NOW);
        int second = repository.failIfStaleByInquiryNo("LL_A", before, before, before, NOW.plusSeconds(1));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }

    // ─── keyset 페이징 (findStale / findStaleAfter) ───────────────────────────

    private List<String> nos(List<StaleInquiryView> views) {
        return views.stream().map(StaleInquiryView::getInquiryNo).toList();
    }

    @Test
    @DisplayName("findStale - updatedAt 이 같으면 id 오름차순으로 결정적으로 정렬된다 (커서 경계가 흔들리지 않도록)")
    void findStale_tieBrokenById() {
        LocalDateTime same = NOW.minusMinutes(30);
        saveAt("LL_1", InquiryStatus.PENDING, "k:20260101", same);
        saveAt("LL_2", InquiryStatus.PENDING, "k:20260102", same);
        saveAt("LL_3", InquiryStatus.PENDING, "k:20260103", same);

        List<StaleInquiryView> stale = repository.findStale(InquiryStatus.PENDING, NOW.minusMinutes(5), PageRequest.of(0, 10));

        assertThat(nos(stale)).containsExactly("LL_1", "LL_2", "LL_3");
        assertThat(stale).allSatisfy(v -> assertThat(v.getUpdatedAt()).isEqualTo(same));
    }

    @Test
    @DisplayName("findStaleAfter - 커서(updatedAt, id) 이후의 행만, 오래된 순으로 반환한다")
    void findStaleAfter_returnsOnlyRowsAfterCursor() {
        saveAt("LL_1", InquiryStatus.PENDING, "k:20260101", NOW.minusMinutes(40));
        saveAt("LL_2", InquiryStatus.PENDING, "k:20260102", NOW.minusMinutes(30));
        saveAt("LL_3", InquiryStatus.PENDING, "k:20260103", NOW.minusMinutes(20));
        saveAt("LL_4", InquiryStatus.PENDING, "k:20260104", NOW.minusMinutes(10));
        Long idOf2 = repository.findByInquiryNo("LL_2").orElseThrow().getId();

        List<StaleInquiryView> after = repository.findStaleAfter(
                InquiryStatus.PENDING, NOW.minusMinutes(5), NOW.minusMinutes(30), idOf2, PageRequest.of(0, 10));

        assertThat(nos(after)).containsExactly("LL_3", "LL_4");
    }

    @Test
    @DisplayName("findStaleAfter - updatedAt 이 커서와 같으면 id 가 더 큰 행만 포함한다 (같은 시각 행을 건너뛰거나 중복하지 않는다)")
    void findStaleAfter_sameUpdatedAt_usesIdAsTieBreaker() {
        LocalDateTime same = NOW.minusMinutes(30);
        saveAt("LL_1", InquiryStatus.PENDING, "k:20260101", same);
        saveAt("LL_2", InquiryStatus.PENDING, "k:20260102", same);
        saveAt("LL_3", InquiryStatus.PENDING, "k:20260103", same);
        Long idOf1 = repository.findByInquiryNo("LL_1").orElseThrow().getId();

        List<StaleInquiryView> after = repository.findStaleAfter(
                InquiryStatus.PENDING, NOW.minusMinutes(5), same, idOf1, PageRequest.of(0, 10));

        assertThat(nos(after)).containsExactly("LL_2", "LL_3");
    }

    @Test
    @DisplayName("findStaleAfter - 상태·기준 시각 조건과 페이지 크기를 지킨다")
    void findStaleAfter_respectsStatusBeforeAndPageSize() {
        saveAt("LL_0", InquiryStatus.PENDING, "k:20260100", NOW.minusMinutes(60));            // 커서 기준 행
        saveAt("LL_A", InquiryStatus.PENDING, "k:20260101", NOW.minusMinutes(50));
        saveAt("LL_B", InquiryStatus.PENDING, "k:20260102", NOW.minusMinutes(40));
        saveAt("LL_C", InquiryStatus.PENDING, "k:20260103", NOW.minusMinutes(30));
        saveAt("LL_OTHER", InquiryStatus.IN_PROGRESS, "k:20260104", NOW.minusMinutes(45));   // 다른 상태 -> 제외
        saveAt("LL_FRESH", InquiryStatus.PENDING, "k:20260105", NOW.minusMinutes(1));        // 기준 이후 -> 제외
        Long cursorId = repository.findByInquiryNo("LL_0").orElseThrow().getId();

        List<StaleInquiryView> page = repository.findStaleAfter(
                InquiryStatus.PENDING, NOW.minusMinutes(5), NOW.minusMinutes(60), cursorId, PageRequest.of(0, 2));

        assertThat(nos(page)).containsExactly("LL_A", "LL_B");
    }

    @Test
    @DisplayName("findStaleAfter - 기준 시각(before)과 같은 updatedAt 은 stale 이 아니다 (엄격한 <)")
    void findStaleAfter_beforeBoundaryIsExclusive() {
        LocalDateTime before = NOW.minusMinutes(5);
        saveAt("LL_0", InquiryStatus.PENDING, "k:20260100", NOW.minusMinutes(60));
        saveAt("LL_EQ", InquiryStatus.PENDING, "k:20260101", before);
        Long cursorId = repository.findByInquiryNo("LL_0").orElseThrow().getId();

        assertThat(repository.findStaleAfter(InquiryStatus.PENDING, before, NOW.minusMinutes(60), cursorId, PageRequest.of(0, 10)))
                .isEmpty();
    }

    @Test
    @DisplayName("findStaleAfter - 커서가 마지막 행 이후이면 빈 목록")
    void findStaleAfter_cursorAfterLastRow_empty() {
        saveAt("LL_1", InquiryStatus.PENDING, "k:20260101", NOW.minusMinutes(30));
        Long id = repository.findByInquiryNo("LL_1").orElseThrow().getId();

        assertThat(repository.findStaleAfter(InquiryStatus.PENDING, NOW.minusMinutes(5), NOW.minusMinutes(30), id, PageRequest.of(0, 10)))
                .isEmpty();
    }

    @Test
    @DisplayName("keyset 순회 - 같은 updatedAt 행이 페이지 경계에 걸려도 모든 행을 정확히 한 번씩 읽는다")
    void keysetWalk_visitsEveryRowExactlyOnce() {
        LocalDateTime same = NOW.minusMinutes(30);
        saveAt("LL_1", InquiryStatus.PENDING, "k:20260101", NOW.minusMinutes(50));
        saveAt("LL_2", InquiryStatus.PENDING, "k:20260102", same);
        saveAt("LL_3", InquiryStatus.PENDING, "k:20260103", same);
        saveAt("LL_4", InquiryStatus.PENDING, "k:20260104", same);   // 경계(2건 단위)에서 같은 시각이 갈린다
        saveAt("LL_5", InquiryStatus.PENDING, "k:20260105", NOW.minusMinutes(10));
        LocalDateTime before = NOW.minusMinutes(5);

        List<String> visited = new ArrayList<>();
        List<StaleInquiryView> page = repository.findStale(InquiryStatus.PENDING, before, PageRequest.of(0, 2));
        while (!page.isEmpty()) {
            visited.addAll(nos(page));
            StaleInquiryView last = page.get(page.size() - 1);
            page = repository.findStaleAfter(InquiryStatus.PENDING, before, last.getUpdatedAt(), last.getId(), PageRequest.of(0, 2));
        }

        assertThat(visited).containsExactly("LL_1", "LL_2", "LL_3", "LL_4", "LL_5");
    }

    @Test
    @DisplayName("[회귀] 앞쪽 행이 처리되지 않고 stale 로 남아 있어도 keyset 은 그 뒤 행을 읽는다 (첫 페이지 재조회는 같은 행만 반복)")
    void keyset_skipsUnprocessedFrontRows_whereFirstPageReReadDoesNot() {
        saveAt("LL_POISON1", InquiryStatus.PENDING, "k:20260101", NOW.minusMinutes(50));
        saveAt("LL_POISON2", InquiryStatus.PENDING, "k:20260102", NOW.minusMinutes(40));
        saveAt("LL_OK1", InquiryStatus.PENDING, "k:20260103", NOW.minusMinutes(30));
        saveAt("LL_OK2", InquiryStatus.PENDING, "k:20260104", NOW.minusMinutes(20));
        LocalDateTime before = NOW.minusMinutes(5);

        // 앞쪽 두 건은 처리에 실패했다고 가정(상태가 그대로 PENDING 으로 남음)
        List<StaleInquiryView> first = repository.findStale(InquiryStatus.PENDING, before, PageRequest.of(0, 2));
        assertThat(nos(first)).containsExactly("LL_POISON1", "LL_POISON2");

        // 이전 방식: 첫 페이지를 다시 읽으면 또 같은 두 건이라 뒤쪽에 도달하지 못한다
        assertThat(nos(repository.findStale(InquiryStatus.PENDING, before, PageRequest.of(0, 2))))
                .containsExactly("LL_POISON1", "LL_POISON2");

        // keyset: 커서를 마지막 행 뒤로 옮겨 뒤쪽 정상 건에 도달한다
        StaleInquiryView last = first.get(first.size() - 1);
        List<StaleInquiryView> next = repository.findStaleAfter(
                InquiryStatus.PENDING, before, last.getUpdatedAt(), last.getId(), PageRequest.of(0, 2));
        assertThat(nos(next)).containsExactly("LL_OK1", "LL_OK2");

        // 뒤쪽 정상 건은 실제로 FAILED 처리되고, 앞쪽 실패 건은 건드리지 않는다
        int failed = repository.failAllIfStale(next.stream().map(StaleInquiryView::getId).toList(),
                InquiryStatus.PENDING, before, NOW);
        assertThat(failed).isEqualTo(2);
        assertThat(reload("LL_OK1").getStatus()).isEqualTo(InquiryStatus.FAILED);
        assertThat(reload("LL_POISON1").getStatus()).isEqualTo(InquiryStatus.PENDING);
    }
}
