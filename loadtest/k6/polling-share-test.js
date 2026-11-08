// k6 — "FE 폴링 API 부하" 측정용 시나리오 (AS-IS / TO-BE 비교 겸용)
//
// 고객 1명의 실제 흐름을 그대로 재현한다 (VU 1개 = 동시 진행 고객 1명):
//   t=0s   POST /api/loan/request-compare-loan                       (한도조회 요청)
//   t=3,6,9... GET /api/loan/inquiry/{inquiryNo}                      (FE 폴링, 3초 고정)
//   t=5~59s 제휴사별 POST /api/loan/response-compare-loan-result     (제휴사당 1회, n개 상품 List)
//   완료 직후  GET /api/loan/inquiry/{inquiryNo}/summary              (결과 화면, 1회)
//
// ── MODE ────────────────────────────────────────────────────────────────────
//   MODE=asis (기본)  현재 API 호출 방식. 항상 전체 응답을 받는다.
//   MODE=tobe         개선안(Redis 읽기 모델 + 버전 비교) 호출 방식.
//                     GET /inquiry/{no}?version=N  (첫 호출은 version 생략)
//                     응답: 변경 없음 -> data.changed=false (본문 목록 없음)
//                           변경 있음 -> 기존 응답 + data.changed=true + data.version=M
//                     (파라미터/필드명은 VERSION_PARAM / VERSION_FIELD / CHANGED_FIELD 로 변경 가능.
//                      304를 쓰기로 하면 304도 "변경 없음"으로 처리한다.)
//   두 모드는 부하 모델(고객 흐름, 단계, 콜백 분산)이 완전히 동일하다. 서버만 바꿔서 두 번 돌려 비교한다.
//
// ── 시나리오 ────────────────────────────────────────────────────────────────
//   SCENARIO=share    (기본) 동시 고객 수 단계(LEVELS)별로 위 흐름을 반복
//                     -> 요청 수 비중, 폴링 p95, 변경없음 비율, 폴링 응답 bytes, 구간 DB stmt 수
//   SCENARIO=unitcost 요청 1건당 DB 쿼리 수(단위비용)
//                     -> 폴링을 실제 간격(POLL_INTERVAL_SEC)으로 띄워 측정한다 (캐시/버전 효과가 부풀려지지 않도록).
//                        tobe에서는 "변경 있음(전체 응답)"과 "변경 없음(version 일치)"을 따로 측정한다.
//
// ── 사전조건 ────────────────────────────────────────────────────────────────
//   1) local 프로필(H2) + Redis + WireMock(8091, partner-send-sms-success) 기동 (loan-limit-load-test.js 헤더 참고)
//   2) 콜백 대상 조회용 local 전용 엔드포인트: GET /api/loadtest/inquiry/{inquiryNo}/callback-targets
//   3) 쿼리 카운터용 local 전용 엔드포인트: GET /api/loadtest/stats (prepareStatementCount)
//      (spring.jpa.properties.hibernate.generate_statistics=true 필요)
//   4) member 테이블에 최소 max(LEVELS)명 seed 후 USER_IDS 또는 USER_ID_RANGE 로 전달.
//   5) 다른 트래픽이 없는 상태에서 실행.
//
// ── 실행 예 ─────────────────────────────────────────────────────────────────
//   # 단위비용 (먼저)
//   k6 run -e MODE=asis -e SCENARIO=unitcost -e USER_ID_RANGE=1-5 polling-share-test.js
//   k6 run -e MODE=tobe -e SCENARIO=unitcost -e USER_ID_RANGE=1-5 polling-share-test.js
//   # 부하 (동일 조건으로)
//   k6 run -e MODE=asis -e LEVELS=100 -e USER_ID_RANGE=1-100 polling-share-test.js
//   k6 run -e MODE=tobe -e LEVELS=100 -e USER_ID_RANGE=1-100 polling-share-test.js
//   -> polling-share-kpi-asis.json / polling-share-kpi-tobe.json 을 비교한다.
//
// ── 개선율 산출 (AS-IS 대비 TO-BE) ──────────────────────────────────────────
//   개선율(%) = (AS-IS - TO-BE) / AS-IS × 100
//   1) 폴링 1회당 DB stmt : unitcost 의 pollInProgress (+ tobe 는 pollInProgressUnchanged)
//   2) 고객 1명당 폴링 stmt: share 의 polls_per_inquiry × (변경 있음 비율 × 변경 시 stmt + 변경 없음 비율 × 변경 없음 stmt)
//   3) 구간 총 stmt / 고객흐름 : share KPI 의 stmtPerFlow (교차 검증용, 콜백/요청/lookup 쿼리가 섞여 있다)
//   4) 폴링 p95, 폴링 평균 응답 bytes
//   ※ 폴링 호출 수(고객당 ~20회)는 AS-IS/TO-BE 동일하다. 줄어드는 것은 "1회당 비용"이다.
//
// 한계 (결과 해석 시 반드시 명시)
//   - 콜백 body 는 제휴사별 Adaptor.convert() 레이아웃(표준/KAKAO_BANK/TOSS_BANK/LINE_BANK)을 따른다.
//     새 제휴사 전용 어댑터가 추가되면 buildCallbackBody() 를 갱신할 것 (안 하면 완료 판정이 안 되어 gaveUp 으로 드러난다).
//   - 콜백 결과는 전부 성공 코드. 거절 비율은 반영하지 않는다.
//   - H2(인메모리, 동일 JVM)라 ms 단위 DB 시간 비중은 신뢰 불가 -> 요청 수/쿼리 수 기준으로만 주장.
//   - Redis 명령 수는 stmt 에 잡히지 않는다. TO-BE 의 Redis 비용은 별도로 (redis-cli INFO commandstats) 기록할 것.
//   - share 구간 stmt 에는 스케줄러(outbox 등) 백그라운드 쿼리와 lookup 쿼리가 포함된다 (AS-IS/TO-BE 동일 조건).

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Gauge, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SCENARIO = __ENV.SCENARIO || 'share';
const MODE = (__ENV.MODE || 'asis').toLowerCase();
const LOAN_TYPE = __ENV.LOAN_TYPE || 'PERSONAL_CREDIT';

if (MODE !== 'asis' && MODE !== 'tobe') {
  throw new Error('MODE 는 asis 또는 tobe 여야 합니다.');
}

const VERSION_PARAM = __ENV.VERSION_PARAM || 'version';
const VERSION_FIELD = __ENV.VERSION_FIELD || 'version';
const CHANGED_FIELD = __ENV.CHANGED_FIELD || 'changed';

const POLL_INTERVAL_SEC = Number(__ENV.POLL_INTERVAL_SEC || 3);
const INQUIRY_DURATION_SEC = Number(__ENV.INQUIRY_DURATION_SEC || 60); // 마지막 콜백 도착 시점
const CB_MIN_SEC = Number(__ENV.CB_MIN_SEC || 5);                       // 가장 이른 콜백 시점
const LOOKUP_AT_SEC = 2;                                                // 콜백 대상 조회 시점(첫 폴링 3s 이전)
const LEVELS = (__ENV.LEVELS || '20,50,100').split(',').map(Number);   // 동시 진행 고객 수
const LEVEL_DURATION_SEC = Number(__ENV.LEVEL_DURATION_SEC || 180);
const PARTNERS = __ENV.PARTNERS ? __ENV.PARTNERS.split(',').map((s) => s.trim()) : null;
const SIMULATE_MANY_CLIENTS = (__ENV.SIMULATE_MANY_CLIENTS || 'true') === 'true';
const STMT_FILTER = __ENV.STMT_FILTER || 'result="prepared"';
const STMT_METRIC = __ENV.STMT_METRIC || 'hibernate_statements_total';
// 기본: local 전용 /api/loadtest/stats 의 Hibernate prepareStatementCount.
// STMT_SOURCE=prometheus 로 주면 /actuator/prometheus 의 STMT_METRIC 합계를 사용한다.
const STMT_SOURCE = __ENV.STMT_SOURCE || 'endpoint';

// ── userId 풀 ───────────────────────────────────────────────────────────────
function buildUserPool() {
  if (__ENV.USER_IDS) {
    return __ENV.USER_IDS.split(',').map((s) => Number(s.trim())).filter((n) => !Number.isNaN(n));
  }
  if (__ENV.USER_ID_RANGE) {
    const [a, b] = __ENV.USER_ID_RANGE.split('-').map(Number);
    const out = [];
    for (let i = a; i <= b; i++) out.push(i);
    return out;
  }
  return [];
}
const USER_POOL = buildUserPool();
const MAX_LEVEL = Math.max(...LEVELS);
if (USER_POOL.length === 0) {
  throw new Error('USER_IDS 또는 USER_ID_RANGE(예: 1-100)가 필요합니다. member 테이블에 seed 해두세요.');
}
if (SCENARIO === 'share' && USER_POOL.length < MAX_LEVEL) {
  throw new Error(`userId 풀(${USER_POOL.length})이 최대 동시 고객 수(${MAX_LEVEL})보다 작습니다.`);
}

// ── 메트릭 ──────────────────────────────────────────────────────────────────
const gaveUp = new Counter('poll_gave_up_without_result');
const noTargets = new Counter('callback_targets_not_found');
const requestFailed = new Counter('request_compare_loan_failed');
const pollsPerInquiry = new Trend('polls_per_inquiry');
const completeMs = new Trend('inquiry_complete_ms');
const pollBytes = new Counter('poll_resp_bytes');               // 폴링 응답 본문 크기 합계 (디코딩 후 문자 수 기준)
const pollUnchanged = new Counter('poll_unchanged');            // TO-BE: 변경 없음 응답 수
const versionMissing = new Counter('tobe_version_missing');     // TO-BE 인데 응답에 version 이 없음 (서버가 AS-IS 인지 확인)
const gBegin = new Gauge('stmt_begin');                         // 단계 시작 시점 누적 stmt
const gEnd = new Gauge('stmt_end');                             // 단계 종료 시점 누적 stmt

// ── 시나리오 정의 ───────────────────────────────────────────────────────────
const GRACEFUL_SEC = INQUIRY_DURATION_SEC + 30;

function buildScenarios() {
  if (SCENARIO === 'unitcost') {
    return { unitcost: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '10m', exec: 'unitcost' } };
  }
  const scenarios = {};
  let start = 0;
  for (const n of LEVELS) {
    scenarios[`level_${n}`] = {
      executor: 'constant-vus',
      vus: n,
      duration: `${LEVEL_DURATION_SEC}s`,
      startTime: `${start}s`,
      gracefulStop: `${GRACEFUL_SEC}s`,
      exec: 'customer',
      tags: { level: String(n) },
    };
    // 단계 시작/종료 시점의 누적 stmt 를 읽는 샘플러 (이전 단계 잔여 iteration 이 끝난 뒤, 다음 단계 시작 전)
    scenarios[`stmt_begin_${n}`] = {
      executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '30s',
      startTime: `${start}s`, exec: 'stmtBegin', tags: { level: String(n) },
    };
    scenarios[`stmt_end_${n}`] = {
      executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '30s',
      startTime: `${start + LEVEL_DURATION_SEC + GRACEFUL_SEC + 2}s`, exec: 'stmtEnd', tags: { level: String(n) },
    };
    start += LEVEL_DURATION_SEC + GRACEFUL_SEC + 5; // 이전 단계 잔여 iteration이 끝난 뒤 다음 단계 시작
  }
  return scenarios;
}

const REQ_NAMES = ['requestCompareLoan', 'pollInquiry', 'callback', 'summary'];

function buildThresholds() {
  const th = { http_req_failed: ['rate<0.01'] };
  // 태그별 서브메트릭을 summary 데이터에 노출시키기 위한 항상-참 threshold
  for (const n of LEVELS) {
    for (const name of REQ_NAMES) {
      th[`http_reqs{name:${name},level:${n}}`] = ['count>=0'];
      th[`http_req_duration{name:${name},level:${n}}`] = ['p(95)>=0'];
    }
    th[`poll_resp_bytes{level:${n}}`] = ['count>=0'];
    th[`poll_gave_up_without_result{level:${n}}`] = ['count>=0'];
    th[`callback_targets_not_found{level:${n}}`] = ['count>=0'];
    th[`poll_unchanged{level:${n}}`] = ['count>=0'];
    th[`polls_per_inquiry{level:${n}}`] = ['avg>=0'];
    th[`stmt_begin{level:${n}}`] = ['value>=0'];
    th[`stmt_end{level:${n}}`] = ['value>=0'];
  }
  return th;
}

export const options = {
  scenarios: buildScenarios(),
  thresholds: SCENARIO === 'share' ? buildThresholds() : {},
};

// ── 유틸 ────────────────────────────────────────────────────────────────────
function sleepUntil(tsMs) {
  const d = (tsMs - Date.now()) / 1000;
  if (d > 0) sleep(d);
}

function vuHeaders() {
  const h = { 'Content-Type': 'application/json' };
  if (SIMULATE_MANY_CLIENTS) {
    h['X-Forwarded-For'] = `10.${Math.floor(__VU / 65025) % 256}.${Math.floor(__VU / 255) % 256}.${__VU % 255}`;
  }
  return h;
}

function userIdForVu() {
  return USER_POOL[(__VU - 1) % USER_POOL.length];
}

function randomRrno() {
  const y = String(Math.floor(Math.random() * 30) + 70).padStart(2, '0');
  const seq = String(Math.floor(Math.random() * 9999999)).padStart(7, '0');
  return `${y}0101${seq}`;
}

function requestInquiry() {
  const payload = JSON.stringify({
    name: '부하테스트',
    userId: userIdForVu(),
    rrno: randomRrno(),
    jobType: 'EMPLOYEE',
    loanType: LOAN_TYPE,
    agreePersonalCreditInfo: true,
    agreePersonalCreditTime: new Date().toISOString().slice(0, 19),
  });
  const res = http.post(`${BASE_URL}/api/loan/request-compare-loan`, payload, {
    headers: vuHeaders(),
    tags: { name: 'requestCompareLoan' },
  });
  let inquiryNo = null;
  try { inquiryNo = JSON.parse(res.body).data.inquiryNo; } catch (e) { /* ignore */ }
  if (res.status !== 200 || !inquiryNo) {
    requestFailed.add(1);
    console.log(`[REQUEST FAIL] vu=${__VU} status=${res.status} body=${(res.body || '').slice(0, 200)}`);
    return null;
  }
  return inquiryNo;
}

// 완료 전에는 폴링 응답에 productResults가 비어 있어 loReqtNo를 알 수 없으므로 local 전용 조회 API를 쓴다.
// 이 호출은 name:lookup 태그로 분리되어 비중 계산에서 제외된다 (단, DB 쿼리는 발생하므로 구간 stmt 에는 포함된다).
function lookupTargets(inquiryNo) {
  let prev = -1;
  let last = [];
  for (let i = 0; i < 20; i++) {
    const r = http.get(`${BASE_URL}/api/loadtest/inquiry/${inquiryNo}/callback-targets`, { tags: { name: 'lookup' } });
    if (r.status === 200) {
      try { last = JSON.parse(r.body); } catch (e) { last = []; }
      if (PARTNERS) last = last.filter((t) => PARTNERS.includes(t.partnerCode));
      // 전송(fan-out)이 끝나 SEND_SUCCESS 건수가 안정될 때까지 대기
      if (last.length > 0 && last.length === prev) return last;
      prev = last.length;
    }
    sleep(0.5);
  }
  return last;
}

function groupByPartner(targets) {
  const m = {};
  for (const t of targets) {
    (m[t.partnerCode] = m[t.partnerCode] || []).push(t);
  }
  return m;
}

// 제휴사별 콜백 body — 서버의 LoanLimitResultAdaptor.convert() 가 기대하는 레이아웃과 일치해야 한다 (completion-tps-test.js 와 동일).
//   표준 제휴사: { loanApplyResults: [{loReqtNo, productCode, resultCode:"00", amount, interestRate}] }
//   KAKAO_BANK : { products: [{iqry_dman_no, alnc_gds_unq_cd, rslt_cd:"CP0000", loan_limit_amt, last_loan_intr, loan_trm_mcnt}] }
//   TOSS_BANK  : { preScreeningResult: [{result:"TLA00", loanReqNo, loanProductId, interestRate, amount}] }
//   LINE_BANK  : { preScreeningResult: [{result:"NFLA00", ticketId, productName, loanProductId, interestRate(문자열), amount(문자열)}] }
function buildCallbackBody(partnerCode, items) {
  const amount = () => (Math.floor(Math.random() * 50) + 1) * 1000000;
  const rate = () => Math.round((3 + Math.random() * 17) * 10) / 10;
  if (partnerCode === 'KAKAO_BANK') {
    return {
      products: items.map((t) => ({
        iqry_dman_no: t.loReqtNo, alnc_gds_unq_cd: t.productCode, rslt_cd: 'CP0000',
        loan_limit_amt: amount(), last_loan_intr: rate(), loan_trm_mcnt: '12',
      })),
    };
  }
  if (partnerCode === 'TOSS_BANK') {
    return {
      preScreeningResult: items.map((t) => ({
        result: 'TLA00', loanReqNo: t.loReqtNo, loanProductId: t.productCode, interestRate: rate(), amount: amount(),
      })),
    };
  }
  if (partnerCode === 'LINE_BANK') {
    return {
      preScreeningResult: items.map((t) => ({
        result: 'NFLA00', ticketId: t.loReqtNo, productName: t.productCode, loanProductId: t.productCode,
        interestRate: String(rate()), amount: String(amount()),
      })),
    };
  }
  return {
    loanApplyResults: items.map((t) => ({
      loReqtNo: t.loReqtNo, productCode: t.productCode, resultCode: '00', amount: amount(), interestRate: rate(),
    })),
  };
}

function sendCallback(partnerCode, items) {
  const body = JSON.stringify(buildCallbackBody(partnerCode, items));
  return http.post(`${BASE_URL}/api/loan/response-compare-loan-result`, body, {
    headers: { 'Content-Type': 'application/json', 'X-Partner-Code': partnerCode },
    tags: { name: 'callback' },
  });
}

// ── 완료 판정 ───────────────────────────────────────────────────────────────
// 현재 서버는 진행 중에도 productResults(PENDING/SEND_SUCCESS 행 포함)를 내려주므로
// "상품 목록이 비어있지 않음"은 완료 신호가 될 수 없다 (첫 폴링에서 바로 종료되어 폴링 수가 과소 집계됨).
// 판정 순서:
//   1) COMPLETE_STATUS 가 지정되면 data[COMPLETE_FIELD] === COMPLETE_STATUS
//   2) allResultReceived === true (totalProductCount === 0 인 preSave 이전 초기 상태는 제외)
//   3) COMPLETE_BY_LIST=true 일 때만 구버전 방식(상품 목록 비어있지 않음)을 쓴다.
const COMPLETE_FIELD = __ENV.COMPLETE_FIELD || 'status';
const COMPLETE_STATUS = __ENV.COMPLETE_STATUS || null;
const COMPLETE_BY_LIST = (__ENV.COMPLETE_BY_LIST || 'false') === 'true';
const COMPLETE_LIST_FIELD = __ENV.COMPLETE_LIST_FIELD || 'productResults';

function isComplete(data) {
  if (COMPLETE_STATUS) return data[COMPLETE_FIELD] === COMPLETE_STATUS;
  if (typeof data.allResultReceived === 'boolean') {
    return data.allResultReceived && data.totalProductCount !== 0;
  }
  if (!COMPLETE_BY_LIST) return false;
  const list = data[COMPLETE_LIST_FIELD];
  if (Array.isArray(list)) return list.length > 0;
  if (list && Array.isArray(list.content)) return list.content.length > 0; // Page 직렬화 형태
  return false;
}

// ── 폴링 ────────────────────────────────────────────────────────────────────
// state: { version } — 고객(VU iteration)별로 마지막에 받은 버전을 보관한다. state 가 null 이거나 MODE=asis 이면 version 을 보내지 않는다.
// 반환: 완료 여부(true) / 미완료 또는 변경 없음(false)
function pollOnce(inquiryNo, state) {
  const useVersion = MODE === 'tobe' && state && state.version !== null && state.version !== undefined;
  const qs = useVersion ? `?${VERSION_PARAM}=${state.version}` : '';
  const r = http.get(`${BASE_URL}/api/loan/inquiry/${inquiryNo}${qs}`, { tags: { name: 'pollInquiry' } });
  pollBytes.add(r.body ? r.body.length : 0);

  if (MODE === 'tobe' && r.status === 304) { pollUnchanged.add(1); return false; }
  if (r.status !== 200) return false;

  let data;
  try { data = JSON.parse(r.body).data; } catch (e) { return false; }
  if (!data) return false;

  if (MODE === 'tobe') {
    if (data[CHANGED_FIELD] === false) { pollUnchanged.add(1); return false; }
    if (data[VERSION_FIELD] === undefined || data[VERSION_FIELD] === null) versionMissing.add(1);
    else if (state) state.version = data[VERSION_FIELD];
  }
  return isComplete(data);
}

function summaryOnce(inquiryNo) {
  const r = http.get(`${BASE_URL}/api/loan/inquiry/${inquiryNo}/summary`, { tags: { name: 'summary' } });
  check(r, { 'summary 200': (x) => x.status === 200 });
}

// ── share 시나리오: 고객 1명의 전체 흐름 ───────────────────────────────────
export function customer() {
  // 모든 VU가 동시에 시작하면 제휴사별 RateLimiter(20/s)에 걸려 실제 분포와 달라지므로 첫 iteration은 분산 시작
  if (__ITER === 0) sleep(Math.random() * (INQUIRY_DURATION_SEC + 5));

  const t0 = Date.now();
  const inquiryNo = requestInquiry();
  if (!inquiryNo) { sleep(5); return; }

  sleepUntil(t0 + LOOKUP_AT_SEC * 1000);
  const targets = lookupTargets(inquiryNo);
  if (targets.length === 0) { noTargets.add(1); sleep(5); return; }

  // 제휴사당 콜백 1회: [CB_MIN, INQUIRY_DURATION-1] 구간에 랜덤 분산, 가장 늦은 제휴사는 정확히 마지막 시점
  const byPartner = groupByPartner(targets);
  const cbMax = (INQUIRY_DURATION_SEC - 1) * 1000;
  const cbMin = CB_MIN_SEC * 1000;
  const events = Object.keys(byPartner).map((p) => ({
    at: cbMin + Math.random() * (cbMax - cbMin),
    partner: p,
    items: byPartner[p],
  })).sort((a, b) => a.at - b.at);
  events[events.length - 1].at = cbMax;

  const maxPolls = Math.ceil(INQUIRY_DURATION_SEC / POLL_INTERVAL_SEC) + 5;
  const state = { version: null };
  let nextPoll = POLL_INTERVAL_SEC * 1000;
  let polls = 0;
  let cbIdx = 0;
  let done = false;

  // 시간순 이벤트 루프: 폴링(고정 간격)과 콜백(제휴사별 시점)을 한 VU에서 시간 순서대로 발사
  while (cbIdx < events.length || (!done && polls < maxPolls)) {
    const nextCb = cbIdx < events.length ? events[cbIdx].at : Infinity;
    const nextP = !done && polls < maxPolls ? nextPoll : Infinity;
    if (nextCb <= nextP) {
      sleepUntil(t0 + nextCb);
      sendCallback(events[cbIdx].partner, events[cbIdx].items);
      cbIdx++;
    } else {
      sleepUntil(t0 + nextP);
      polls++;
      nextPoll += POLL_INTERVAL_SEC * 1000;
      if (pollOnce(inquiryNo, state)) {
        done = true;
        completeMs.add(Date.now() - t0);
      }
    }
  }
  pollsPerInquiry.add(polls);
  if (!done) gaveUp.add(1); // 콜백이 조용히 실패(body 포맷 불일치 등)하거나 완료 판정이 안 되면 여기로 집계된다

  summaryOnce(inquiryNo); // FE: 완료 후 결과 화면 1회
  sleep(1);
}

// ── 단계별 누적 stmt 샘플러 ─────────────────────────────────────────────────
function readStatements() {
  if (STMT_SOURCE === 'endpoint') {
    const s = http.get(`${BASE_URL}/api/loadtest/stats`, { tags: { name: 'actuator' } });
    try { return JSON.parse(s.body).prepareStatementCount; } catch (e) { return null; }
  }
  const r = http.get(`${BASE_URL}/actuator/prometheus`, { tags: { name: 'actuator' } });
  const re = new RegExp(`^${STMT_METRIC}\\{([^}]*)\\}\\s+([0-9.eE+-]+)`, 'gm');
  let sum = 0;
  let found = false;
  let m;
  while ((m = re.exec(r.body)) !== null) {
    if (!STMT_FILTER || m[1].includes(STMT_FILTER)) { sum += Number(m[2]); found = true; }
  }
  return found ? sum : null;
}

export function stmtBegin() {
  const v = readStatements();
  if (v !== null) gBegin.add(v);
}

export function stmtEnd() {
  const v = readStatements();
  if (v !== null) gEnd.add(v);
}

// ── unitcost 시나리오: 요청 1건당 prepared statement 수 ─────────────────────
// 호출 1건마다 (호출 직전, 직후) 누적 stmt 의 차이를 합산한다. 호출 사이 대기(gapSec)에 발생하는
// 백그라운드 쿼리는 포함되지 않는다.
function measure(label, n, fn, gapSec) {
  sleep(1);
  let used = 0;
  for (let i = 0; i < n; i++) {
    const before = readStatements();
    if (before === null) throw new Error('쿼리 카운터를 읽지 못했습니다. /api/loadtest/stats 구현 여부(또는 STMT_SOURCE=prometheus 의 메트릭명)와 generate_statistics 설정 확인');
    fn(i);
    const after = readStatements();
    used += after - before;
    if (gapSec > 0 && i < n - 1) sleep(gapSec);
  }
  const per = used / n;
  console.log(`[UNITCOST][${MODE}] ${label}: ${per.toFixed(2)} statements/call (n=${n})`);
  return per;
}

export function unitcost() {
  const inquiryNo = requestInquiry();
  if (!inquiryNo) throw new Error('inquiry 생성 실패');
  sleep(LOOKUP_AT_SEC);
  const targets = lookupTargets(inquiryNo);
  if (targets.length === 0) throw new Error('콜백 대상이 없습니다 (조회 API/WireMock 확인)');
  sleep(8); // 비동기 fan-out(Tx1/Tx2) 및 outbox 쿼리가 끝나 측정에 섞이지 않도록 대기

  const result = { mode: MODE, pollIntervalSec: POLL_INTERVAL_SEC };

  // TO-BE: 캐시 워밍업 + 현재 버전 확보
  const st = { version: null };
  pollOnce(inquiryNo, st);

  // 실제 폴링 간격으로 띄워 측정 (연속 호출하면 캐시/버전 효과가 부풀려진다)
  result.pollInProgress = measure('poll (진행 중, 변경 있음/전체 응답)', 10, () => pollOnce(inquiryNo, null), POLL_INTERVAL_SEC);
  if (MODE === 'tobe') {
    result.pollInProgressUnchanged = measure('poll (진행 중, 변경 없음/version 일치)', 10, () => pollOnce(inquiryNo, st), POLL_INTERVAL_SEC);
  }

  const byPartner = groupByPartner(targets);
  const partners = Object.keys(byPartner);
  const totalItems = targets.length;
  const total = measure(`callback (제휴사 ${partners.length}회, 상품 ${totalItems}건 합계/호출 수)`, partners.length,
    (i) => sendCallback(partners[i], byPartner[partners[i]]), 0);
  result.callbackPerCall = total;
  result.callbackPerItem = (total * partners.length) / totalItems;
  console.log(`[UNITCOST][${MODE}] callback: ${result.callbackPerItem.toFixed(2)} statements/상품(item)`);

  // 검증: 콜백이 실제로 처리됐다면 allResultReceived=true 이고 콜백 1건당 쿼리 수가 0보다 커야 한다.
  sleep(1);
  const peek = http.get(`${BASE_URL}/api/loan/inquiry/${inquiryNo}`, { tags: { name: 'actuator' } });
  let allRecv = null;
  try { allRecv = JSON.parse(peek.body).data.allResultReceived; } catch (e) { /* ignore */ }
  result.allResultReceivedAfterCallbacks = allRecv;
  if (allRecv !== true || total === 0) {
    console.log('[UNITCOST][WARN] 콜백 전송 후에도 allResultReceived 가 true 가 아니거나 콜백 쿼리 수가 0 입니다. ' +
      '콜백 body 포맷이 제휴사 어댑터와 맞지 않거나 SEND_FAILED 상품이 있는 것입니다. 이 단위비용은 유효하지 않습니다.');
  }

  sleep(3);
  result.pollComplete = measure('poll (완료, 상품 페이지 조회 포함)', 10, () => pollOnce(inquiryNo, null), POLL_INTERVAL_SEC);
  summaryOnce(inquiryNo); // warm-up
  result.summary = measure('summary (결과 화면)', 10, () => summaryOnce(inquiryNo), 0);
  console.log(`[UNITCOST-JSON] ${JSON.stringify(result)}`);
}

// ── 결과 요약 ───────────────────────────────────────────────────────────────
function cnt(data, name, level) {
  const m = data.metrics[`http_reqs{name:${name},level:${level}}`];
  return m ? m.values.count : 0;
}
function p95(data, name, level) {
  const m = data.metrics[`http_req_duration{name:${name},level:${level}}`];
  return m && m.values['p(95)'] !== undefined ? m.values['p(95)'] : NaN;
}
function mv(data, key, field) {
  const m = data.metrics[key];
  return m && m.values && m.values[field] !== undefined ? m.values[field] : NaN;
}
function fx(x, d) {
  return Number.isNaN(x) || x === undefined ? 'n/a' : x.toFixed(d === undefined ? 1 : d);
}

export function handleSummary(data) {
  if (SCENARIO !== 'share') return {};
  const lines = [];
  const kpi = { mode: MODE, pollIntervalSec: POLL_INTERVAL_SEC, inquiryDurationSec: INQUIRY_DURATION_SEC, levels: {} };

  lines.push(`\n=== [${MODE.toUpperCase()}] 폴링 비중 (poll interval ${POLL_INTERVAL_SEC}s, 진행 ${INQUIRY_DURATION_SEC}s) ===`);
  lines.push('level | request | poll | callback | summary | poll share(req) | poll p95(ms) | callback p95(ms)');
  for (const n of LEVELS) {
    const r = cnt(data, 'requestCompareLoan', n);
    const p = cnt(data, 'pollInquiry', n);
    const c = cnt(data, 'callback', n);
    const s = cnt(data, 'summary', n);
    const total = r + p + c + s;
    const share = total > 0 ? ((p / total) * 100).toFixed(1) + '%' : 'n/a';
    lines.push(`${n} | ${r} | ${p} | ${c} | ${s} | ${share} | ${fx(p95(data, 'pollInquiry', n))} | ${fx(p95(data, 'callback', n))}`);

    const unchanged = mv(data, `poll_unchanged{level:${n}}`, 'count');
    const bytes = mv(data, `poll_resp_bytes{level:${n}}`, 'count');
    const gaveUpN = mv(data, `poll_gave_up_without_result{level:${n}}`, 'count');
    const noTargetsN = mv(data, `callback_targets_not_found{level:${n}}`, 'count');
    const ppi = mv(data, `polls_per_inquiry{level:${n}}`, 'avg');
    const stmtBegin = mv(data, `stmt_begin{level:${n}}`, 'value');
    const stmtEnd = mv(data, `stmt_end{level:${n}}`, 'value');
    const stmtTotal = stmtEnd - stmtBegin;
    kpi.levels[n] = {
      requests: r, polls: p, callbacks: c, summaries: s,
      pollShareReq: total > 0 ? p / total : null,
      pollP95Ms: p95(data, 'pollInquiry', n),
      callbackP95Ms: p95(data, 'callback', n),
      pollsPerCustomer: ppi,
      pollUnchanged: Number.isNaN(unchanged) ? 0 : unchanged,
      pollUnchangedRatio: p > 0 && !Number.isNaN(unchanged) ? unchanged / p : 0,
      pollAvgBytes: p > 0 && !Number.isNaN(bytes) ? bytes / p : null,
      gaveUp: Number.isNaN(gaveUpN) ? 0 : gaveUpN,                 // 완료 판정이 안 된 고객 흐름 수 (0이어야 유효한 결과)
      noTargets: Number.isNaN(noTargetsN) ? 0 : noTargetsN,
      windowStatements: stmtTotal,
      stmtPerFlow: r > 0 && !Number.isNaN(stmtTotal) ? stmtTotal / r : null,
    };
  }

  lines.push(`\n--- [${MODE.toUpperCase()}] 폴링 비용 ---`);
  lines.push('level | polls/customer | 변경없음 비율 | poll 평균 bytes | 구간 총 stmt | stmt/고객흐름');
  for (const n of LEVELS) {
    const k = kpi.levels[n];
    lines.push(`${n} | ${fx(k.pollsPerCustomer)} | ${(k.pollUnchangedRatio * 100).toFixed(1)}% | ${fx(k.pollAvgBytes, 0)} | ${fx(k.windowStatements, 0)} | ${fx(k.stmtPerFlow)}`);
  }
  const missing = mv(data, 'tobe_version_missing', 'count');
  if (MODE === 'tobe' && !Number.isNaN(missing) && missing > 0) {
    lines.push(`!! tobe 모드인데 폴링 응답에 '${VERSION_FIELD}' 필드가 ${missing}회 없었습니다. 서버가 TO-BE 로 배포되었는지 확인하세요.`);
  }
  for (const n of LEVELS) {
    const gave = kpi.levels[n].gaveUp;
    if (gave > 0) {
      lines.push(`!! [level ${n}] 완료 판정이 안 된 고객 흐름이 ${gave}건 있습니다. 이 결과는 유효하지 않습니다 (폴링 수가 상한까지 부풀고 콜백이 실제로 처리되지 않았을 수 있음).`);
      lines.push('   원인 후보: 콜백 body 포맷 불일치(제휴사 어댑터), SEND_FAILED 상품 존재(total 미달), allResultReceived 미충족.');
    }
  }
  lines.push('※ DB 부하 비중 = (요청 수 × unitcost 쿼리 수) 기준. 구간 총 stmt 에는 lookup/스케줄러 쿼리가 포함된다. H2 ms 수치는 근거로 쓰지 말 것.');
  lines.push(`※ 개선율은 polling-share-kpi-asis.json 과 polling-share-kpi-tobe.json 을 같은 LEVELS/LEVEL_DURATION_SEC 로 실행한 결과끼리 비교할 것.`);

  return {
    stdout: lines.join('\n') + '\n',
    [__ENV.OUT || `polling-share-summary-${MODE}.json`]: JSON.stringify(data, null, 2),
    [`polling-share-kpi-${MODE}.json`]: JSON.stringify(kpi, null, 2),
  };
}
