// k6 load test — LoanLimitSenderService.inquiry() 스레드풀(partnerApiExecutor) / HTTP 커넥션 풀 /
// Resilience4j / orTimeout 부하 테스트
//
// 대상: POST /api/loan/request-compare-loan
//
// 사전조건:
//   1) 앱을 local 프로필로 기동 (H2, 8080 포트)
//   2) WireMock을 8091 포트에 기동해 파트너 API(KAKAO_BANK/TOSS_BANK/KB_CAPITAL/LINE_BANK,
//      모두 application.yaml 상 base-url=localhost:8091, path=/send/sms)를 스텁
//      (../wiremock/README.md 참고). SHINHAN_BANK는 lease-line(EUC-KR 고정길이) 프로토콜이라
//      REST 스텁 대상에서 제외.
//   3) loanType은 PERSONAL_CREDIT 사용 권장 — AUTO/MORTGATE는 Nice DNR/Coocon 외부 호출이
//      선행되어 partnerApiExecutor 부하 측정에 잡음이 섞임.
//   4) Prometheus(로컬 설치, ../prometheus/prometheus.yml)로 /actuator/prometheus를 스크레이핑해서
//      executor_*, partner_http_pool_connections, resilience4j_* 를 같이 띄워두는 걸 권장.
//      이 스크립트/폴링 응답만으로는 THREAD_POOL_EXHAUSTED · RATE_LIMIT_EXCEEDED · BULKHEAD_FULL ·
//      CB_OPEN 같은 파트너 전송 실패 사유를 볼 수 없다 (아래 "왜 폴링으로 거절 여부를 못 보는가" 참고).
//   5) H2 콘솔(http://localhost:8080/h2-console, user: sa, 비밀번호 없음)에서 member 테이블에
//      여러 건(예: id 1~20) INSERT 해두고 그 id 목록을 USER_IDS="1,2,3,...,20"으로 넘길 것.
//      한 건도 안 만들고 랜덤 userId를 쓰면 조회 완료 후 비동기 알림 발송이 100% "Member not
//      found"로 실패해 @RetryableTopic 재시도가 Hikari 커넥션을 잡아먹고, 반대로 USER_ID
//      하나만 고정하면 LoanLimitService의 "동일 userId+loanType 진행 중 요청 거부" 락에
//      거의 모든 요청이 걸려버린다(실제로 이걸로 http_req_failed가 크게 튄 적 있음) - 여러
//      개를 섞어 쓰는 USER_IDS가 정답.
//   6) InboundRateLimiterFilter(Bucket4j)가 클라이언트 IP당 초당 20건으로 /request-compare-loan
//      을 제한한다. k6를 한 IP에서 돌리면 이 20/s가 먼저 걸려서 그 뒤(Executor/Hikari/
//      Resilience4j)는 제대로 테스트가 안 됨 - 기본값(SIMULATE_MANY_CLIENTS=true)은 VU별로
//      가짜 X-Forwarded-For를 실어 이 필터를 우회한다. 레이트리미터 자체를 테스트하고 싶으면
//      SIMULATE_MANY_CLIENTS=false로 꺼서 원래대로(전부 동일 IP) 돌릴 것.
//
// 실행 예 (SCENARIO로 시나리오 선택, 기본값 executor):
//   k6 run -e BASE_URL=http://localhost:8080 -e SCENARIO=executor -e USER_IDS=1,2,3,4,5 -e POLL=true loan-limit-load-test.js
//   k6 run -e BASE_URL=http://localhost:8080 -e SCENARIO=slow     -e USER_IDS=1,2,3,4,5 -e POLL=true loan-limit-load-test.js
//   k6 run -e BASE_URL=http://localhost:8080 -e SIMULATE_MANY_CLIENTS=false loan-limit-load-test.js   # 레이트리미터 자체 검증용
//   k6 run -e BASE_URL=http://localhost:8080 -e SCENARIO=steady -e USER_IDS=<seed된 id 목록> -e POLL=true loan-limit-load-test.js
//     ── 파트너별 RateLimiter(20/s)가 강제하는 시스템 설계 상한(20 req/s)에서 5분간 유지하며
//        http_req_failed<1%가 지켜지는지 확인하는 시나리오. 이 부하 수준에서는 동시 in-flight
//        요청 수가 대략 20×W(초) 정도이므로(Little's Law), USER_IDS 풀이 너무 작으면 동일
//        userId+loanType 중복요청 가드(400)에 걸려 실패율이 왜곡된다 - 최소 100개 이상 권장.
//
// ── SCENARIO=executor (기본값) ──────────────────────────────────────────────
// WireMock: partner-send-sms-success.json 만 활성.
// RPS를 5→150까지 올리며 partnerApiExecutor(core 250 / max 300 / queue 30)가 포화되는 지점,
// AbortPolicy(THREAD_POOL_EXHAUSTED)가 발동하는 지점을 찾는다. 같은 트래픽이 파트너별
// RateLimiter(20/s)·Bulkhead(10~20 동시)도 함께 건드리므로, 보통 Executor가 포화되기 전에
// 이 둘의 거절/폴백이 먼저 관찰된다 — Prometheus의 resilience4j_ratelimiter_available_permissions /
// resilience4j_bulkhead_available_concurrent_calls 가 0에 붙는 시점을 같이 보면 어느 쪽이
// 먼저 병목인지 구분할 수 있다.
//
// ── SCENARIO=slow ────────────────────────────────────────────────────────
// WireMock: partner-send-sms-success.json을 빼고 partner-send-sms-slow.json(9초 지연)만 활성.
// 매우 낮은 고정 아리벌레이트로 소수 요청만 흘려서, PartnerOrTimeoutConfig가 파트너별로 계산한
// orTimeout 시점에 CompletableFuture가 실패 처리되는지, 그리고 그 이후에도
// executor_active_threads{name="partnerApiExecutor"}가 곧바로 내려가지 않고 HttpClient의
// readTimeout/재시도가 끝날 때까지 유지되는지(=orTimeout이 스레드를 즉시 반환하지 않는다는 것)를
// 관찰하기 위한 시나리오. RPS를 올리는 게 목적이 아니므로 executor 시나리오와 동시에 돌리지 않는다.
//
// ── Circuit Breaker / 커넥션 풀 시나리오는 이 스크립트로 자동화하지 않음 ──────────
// CB는 WireMock을 cb-failure-cycle 시나리오(../wiremock/README.md)로 바꿔서 SCENARIO=executor로
// 그대로 돌리면 되고, 커넥션 풀 포화는 RPS보다 동시 진행 요청 수(VU)가 핵심이라 이 스크립트의
// executor 시나리오를 preAllocatedVUs를 높게, target(도착률)은 낮게 잡아 재실행하는 식으로
// 관찰하는 편이 스크립트를 억지로 쪼개는 것보다 명확하다.
//
// ── 왜 폴링 응답으로 거절 여부를 못 보는가 ───────────────────────────────────
// GET /api/loan/inquiry/{inquiryNo}의 productResults[].resultCode는 LoanLimitResultCode
// (콜백으로 받는 실제 대출 심사 결과 코드)이고, THREAD_POOL_EXHAUSTED/RATE_LIMIT_EXCEEDED/
// BULKHEAD_FULL/CB_OPEN은 LoanLimitResult.failReason(파트너 "전송" 실패 사유)에 저장되는데
// 이 필드는 폴링 응답 어디에도 노출되지 않는다. 즉 k6에서 body를 아무리 파싱해도 이 값들을
// 직접 볼 수 없다 — Prometheus 지표(resilience4j_*, executor_*)나 앱 로그로 확인해야 한다.
// (이전 버전 스크립트는 이 필드를 폴링 응답에서 찾으려 했는데 애초에 존재하지 않는 값이라
// 절대 매칭되지 않는 죽은 코드였음 — 이번에 제거함. pollLatency(완료까지 걸린 시간)는
// 별개로 유효한 신호라 그대로 유지.)
//
// Prometheus에서 같이 볼 지표 — 자세한 PromQL은 ../prometheus/README.md 참고:
//   executor_active_threads{name="partnerApiExecutor"}
//   executor_queued_task_count{name="partnerApiExecutor"}
//   executor_pool_size_threads{name="partnerApiExecutor"}
//   partner_http_pool_connections{scope="partner", state="pending"}
//   resilience4j_circuitbreaker_state{name="KAKAO_BANK"}
//   resilience4j_ratelimiter_available_permissions{name="KAKAO_BANK"}
//   resilience4j_bulkhead_available_concurrent_calls{name="KAKAO_BANK"}
//   로그: "THREAD_POOL_EXHAUSTED", "Circuit Breaker OPEN", "RateLimiter 한도 초과", "Bulkhead 포화"

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const POLL = (__ENV.POLL || 'false') === 'true';
const LOAN_TYPE = __ENV.LOAN_TYPE || 'PERSONAL_CREDIT';
const SCENARIO = __ENV.SCENARIO || 'executor'; // 'executor' | 'slow'
// LoanLimitInquiry.userId 자체는 FK가 아니라 아무 정수나 받아주지만, 완료 후 비동기로 도는
// LoanLimitCompletedEventConsumer -> NotificationService.sendNotification()이 이 userId로
// Member를 조회한다. H2는 매번 빈 상태로 뜨므로(seed 데이터 없음) 랜덤 userId는 100% 조회 실패 ->
// @RetryableTopic(4회, backoff 1~10s)로 재시도만 잔뜩 돌다 DLT로 빠진다. 이게 Hikari 커넥션도
// 같이 잡아먹어 http_req_failed를 키우는 원인 중 하나였다. USER_ID를 지정하면 그 고정값만 쓴다 -
// 실행 전 H2 콘솔(http://localhost:8080/h2-console)에서 INSERT INTO member(...) VALUES (...)로
// 한 건 만들고 그 user_id를 넘겨줄 것.
const FIXED_USER_ID = __ENV.USER_ID ? Number(__ENV.USER_ID) : null;
// 여러 명의 실사용자를 흉내내고 싶으면 USER_IDS="1,2,3,..."로 콤마 구분 목록을 줄 것 -
// 매 요청마다 이 중 하나를 랜덤으로 고른다 (member 테이블에 전부 seed 되어 있어야 함).
// USER_ID(단일 고정값)와 동시에 주면 USER_IDS가 우선한다.
const USER_ID_POOL = __ENV.USER_IDS
  ? __ENV.USER_IDS.split(',').map((s) => Number(s.trim())).filter((n) => !Number.isNaN(n))
  : null;

// InboundRateLimiterFilter(Bucket4j)가 클라이언트 IP(X-Forwarded-For 우선, 없으면 remoteAddr)
// 기준으로 초당 20건까지만 통과시킨다. k6를 한 머신에서 돌리면 전부 같은 IP로 잡혀서 target을
// 아무리 올려도 서버가 실제로 받는 건 초당 20건이 상한이 된다 - Executor/Hikari 등 그 뒷단을
// 테스트하려면 이 필터부터 우회해야 한다. VU별로 가짜 IP를 실어 "여러 클라이언트"처럼 보이게
// 한다 (SIMULATE_MANY_CLIENTS=false로 끄면 기존처럼 전부 동일 IP 취급 - 레이트리미터 자체를
// 테스트하고 싶을 때는 꺼둘 것).
const SIMULATE_MANY_CLIENTS = (__ENV.SIMULATE_MANY_CLIENTS || 'true') === 'true';

// 커스텀 메트릭
const requestFailCount = new Counter('http_non_200');
const pollLatency = new Trend('poll_to_complete_ms');
const pollGaveUpCount = new Counter('poll_gave_up_without_result'); // 폴링 횟수 안에 안 끝난 경우
let vuFailLogged = 0; // VU(모듈 인스턴스)당 실패 샘플 로그 출력 횟수 제한용

const SCENARIO_DEFS = {
  // Executor(+ 자연스럽게 RateLimiter/Bulkhead) 포화 지점 탐색.
  // WireMock: partner-send-sms-success.json 만 활성 상태여야 한다.
  executor: {
    executor: 'ramping-arrival-rate',
    startRate: 5,
    timeUnit: '1s',
    preAllocatedVUs: 50,
    maxVUs: 400,
    stages: [
      { target: 10, duration: '30s' },   // 워밍업
      { target: 30, duration: '1m' },    // RateLimiter/Bulkhead 임계 근처 (파트너당 20/s, 10~20 동시)
      { target: 60, duration: '1m' },    // core(250) 근접 (요청당 partner 태스크 여러개)
      { target: 100, duration: '1m' },   // 큐(30) 포화 유도
      { target: 150, duration: '1m' },   // AbortPolicy 발동 기대 구간 (Σ330개 지점 근처)
      { target: 0, duration: '30s' },    // 쿨다운
    ],
  },
  // orTimeout / 스레드 반환 지연 관찰용 — RPS를 올리지 않고 소수 요청만 길게 관찰한다.
  // WireMock: partner-send-sms-success.json은 빼고 partner-send-sms-slow.json(9초 지연)만 활성.
  slow: {
    executor: 'constant-arrival-rate',
    rate: 1,
    timeUnit: '1s',
    duration: '3m',
    preAllocatedVUs: 10,
    maxVUs: 20,
  },
  // 설계 타깃(파트너별 RateLimiter 20/s가 강제하는 시스템 전체 상한) 검증용.
  // ramping이 아니라 고정 도착률로 오래 유지해서, "터지는 지점"이 아니라 "설계값에서
  // 안 터지는가"를 본다. WireMock: partner-send-sms-success.json 만 활성.
  // 성공 기준: http_req_failed < 1%, Hikari waiting≈0, RejectedExecutionException 없음.
  steady: {
    executor: 'constant-arrival-rate',
    rate: 20,
    timeUnit: '1s',
    duration: '5m',
    // POLL=true면 결과를 못 받은 iteration이 최대 10회(1초 간격) 폴링까지 가서
    // iteration_duration이 20초 안팎까지 늘어날 수 있다. Little's Law로 최대
    // 동시 iteration 수 ≈ rate(20) * maxIterationDuration(~22s) ≈ 440.
    // maxVUs가 부족하면 "Insufficient VUs"로 도착률 자체가 안 지켜지고
    // dropped_iterations가 생겨 결과가 왜곡된다.
    preAllocatedVUs: 100,
    maxVUs: 500,
  },
};

export const options = {
  scenarios: {
    [SCENARIO]: SCENARIO_DEFS[SCENARIO],
  },
  thresholds: {
    http_req_duration: ['p(95)<3000'],
    http_req_failed: ['rate<0.01'], // HTTP 레벨 실패(5xx/커넥션 오류)는 1% 미만이어야 함
                                     // — 파트너 실패는 200 + PARTIAL_SUCCESS로 흡수되는 게 정상
  },
};

function randomRrno() {
  // 형식만 맞춘 더미 주민번호 (앞자리 임의)
  const y = String(Math.floor(Math.random() * 30) + 70).padStart(2, '0');
  const seq = String(Math.floor(Math.random() * 9999999)).padStart(7, '0');
  return `${y}0101${seq}`;
}

function pickUserId() {
  if (USER_ID_POOL && USER_ID_POOL.length > 0) {
    return USER_ID_POOL[Math.floor(Math.random() * USER_ID_POOL.length)];
  }
  return FIXED_USER_ID || (Math.floor(Math.random() * 1000000) + 1);
}

export default function () {
  const payload = JSON.stringify({
    name: '부하테스트',
    userId: pickUserId(),
    rrno: randomRrno(),
    jobType: 'EMPLOYEE',
    loanType: LOAN_TYPE,
    agreePersonalCreditInfo: true,
    agreePersonalCreditTime: new Date().toISOString().slice(0, 19),
  });

  const headers = { 'Content-Type': 'application/json' };
  if (SIMULATE_MANY_CLIENTS) {
    // VU 번호를 사설 IP처럼 흉내내서 InboundRateLimiterFilter가 서로 다른 클라이언트로 인식하게 함
    headers['X-Forwarded-For'] = `10.${Math.floor(__VU / 65025) % 256}.${Math.floor(__VU / 255) % 256}.${__VU % 255}`;
  }

  const res = http.post(`${BASE_URL}/api/loan/request-compare-loan`, payload, {
    headers,
    tags: { name: 'requestCompareLoan' },
  });

  const ok = check(res, {
    'status is 200': (r) => r.status === 200,
    'has inquiryNo': (r) => {
      try {
        return !!JSON.parse(r.body).data.inquiryNo;
      } catch {
        return false;
      }
    },
  });
  if (!ok) requestFailCount.add(1);

  // 실패 원인을 상태코드 구간별로 나눠서 k6 자체 checks 요약에서 바로 보이게 한다.
  // (Executor/Hikari/Resilience4j 지표로는 더 좁혀지지 않아서, 실제 응답을 직접 보는 쪽으로 전환)
  check(res, {
    'status is 4xx': (r) => r.status >= 400 && r.status < 500,
    'status is 5xx': (r) => r.status >= 500,
    'status is 0 (network/connection error - Tomcat이 못 받았을 가능성)': (r) => r.status === 0,
  });

  // 실패 응답의 실제 본문을 표본으로 남긴다 (VU당 최대 3건 - 전체를 찍으면 로그가 넘침).
  if (!ok && vuFailLogged < 3) {
    console.log(`[FAIL SAMPLE] vu=${__VU} status=${res.status} error=${res.error || 'none'} body=${(res.body || '').slice(0, 300)}`);
    vuFailLogged++;
  }

  if (POLL && ok) {
    const inquiryNo = JSON.parse(res.body).data.inquiryNo;
    const start = Date.now();
    // slow 시나리오는 orTimeout + 재시도까지 지켜봐야 하니 폴링 간격/횟수를 늘린다.
    const pollIntervalSec = SCENARIO === 'slow' ? 2 : 1;
    const maxPolls = SCENARIO === 'slow' ? 20 : 10; // slow: 최대 40초까지 관찰
    let done = false;

    for (let i = 0; i < maxPolls && !done; i++) {
      sleep(pollIntervalSec);
      const pollRes = http.get(`${BASE_URL}/api/loan/inquiry/${inquiryNo}`, {
        tags: { name: 'pollInquiry' },
      });
      if (pollRes.status !== 200) continue;
      const body = JSON.parse(pollRes.body).data;
      if (body.allResultReceived) {
        done = true;
        pollLatency.add(Date.now() - start);
      }
    }
    if (!done) pollGaveUpCount.add(1);
  }

  sleep(SCENARIO === 'slow' ? 1 : 0.2);
}
