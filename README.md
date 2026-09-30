[![CI](https://github.com/gyohee91/bigin/actions/workflows/ci.yaml/badge.svg)](https://github.com/gyohee91/bigin/actions/workflows/ci.yaml)
[![codecov](https://codecov.io/gh/gyohee91/bigin/branch/main/graph/badge.svg)](https://codecov.io/gh/gyohee91/bigin)

# 🏦 파트너사 제휴 한도조회 플랫폼 · 알림 서비스

> - 실무 대출 비교 플랫폼 운영 경험을 바탕으로 설계한 개인 핀테크 포트폴리오 프로젝트.
> - 다수의 금융사와 연동하여 대출 한도조회 및 신청을 처리하는 백엔드 시스템.
>   - 처리 결과를 채널별(SMS/Email/카카오톡/앱 푸시)로 비동기 알림 발송

<br>

## 목차

- [📌 프로젝트 개요](#project-overview)
- [🛠 기술 스택](#tech-stack)
- [📁 패키지 구조](#package-structure)
- [🏗 핵심 아키텍처](#architecture)
- [⚙️ 스레드풀 구성과 동시성 제어](#thread-pool-concurrency)
- [🗄 핵심 도메인 모델](#domain-model)
- [🔒 장애 격리 - Resilience4j](#resilience4j)
- [🚦 인바운드 트래픽 방어 - Rate Limiting](#inbound-rate-limiting)
- [🚗 오토담보/주택담보 대출 연동 (Nice DNR, KB부동산 시세)](#external-integration)
- [📨 알림 서비스 - 채널별 비동기 발송](#notification-service)
- [📦 Outbox 패턴 - 트랜잭션 보장](#outbox-pattern)
- [💀 Kafka DLQ (Dead Letter Queue)](#kafka-dlq)
- [🗃 캐싱 전략](#caching-strategy)
- [🔄 콜백 동시성 제어](#callback-concurrency)
- [🔑 인증 (JWT)](#auth)
- [🗂 KCB 신용변동 배치](#kcb-batch)
- [📊 모니터링 지표](#monitoring)
- [📋 API 명세](#api-spec)
- [📝 주요 설계 결정](#design-decisions)

<br>

<a id="project-overview"></a>
## 📌 프로젝트 개요

### 실무 배경

현재 재직 중인 회사에서 **50개 이상의 금융사와 연동하는 대출 비교 플랫폼** 을 개발·운영하고 있습니다.

### 프로젝트 목적

실무에서 직접 경험한 **제휴 금융사 한도조회 시스템의 핵심 도메인**을 새로운 기술 스택과 아키텍처로 재설계하여 구현했습니다.

- 실무와 동일한 비즈니스 로직 (콜백 기반 비동기 한도조회, 상품별 채번, 디자인 패턴 등)
- Java 17 + Spring Boot 3.5로 업그레이드하여 최신 기능 적용
- Layered Architecture → Domain-driven Package Structure로 전환
- 또한, 개인 프로젝트에서 설계·검증한 개선 방식 중 실무 환경에 적용 가능하다고 판단된 항목을 실제 운영 시스템에도 반영

<br>

<a id="tech-stack"></a>
## 🛠 기술 스택

### 실무 vs 개인 프로젝트 비교

| 구분           | 실무                   | 개인 프로젝트 (Bigin)                 |
|--------------|----------------------|---------------------------------|
| Language     | Java 8               | Java 17                         |
| Framework    | Spring Boot 2.7      | Spring Boot 3.5                 |
| Architecture | Layered Architecture | Domain-driven Package Structure |
| DB           | Oracle DB            | H2 DB (In-memory)               |
| API 통신       | RestTemplate         | RestClient + Apache HttpClient5 (커넥션 풀) |

### 개인 프로젝트 상세 스택

| 구분        | 기술                                      |
|-----------|-----------------------------------------|
| Language  | Java 17                                 |
| Framework | Spring Boot 3.5                         |
| ORM       | Spring Data JPA / Hibernate             |
| DB        | H2 DB (local, In-memory)                |
| 비동기       | Spring @Async / CompletableFuture, 용도별 전용 ThreadPoolTaskExecutor 5종 |
| 장애격리      | Resilience4j Circuit Breaker / Retry / Rate Limiter / Bulkhead (금융사별 독립 인스턴스) |
| HTTP Client | Spring RestClient + Apache HttpClient5 (`PoolingHttpClientConnectionManager`) |
| 메시지큐 | Apache Kafka (Outbox Pattern, DLQ, `@RetryableTopic` 지수 백오프 재시도) |
| 캐싱 | Redis Cache (Redisson 분산 락 기반), Caffeine (로컬 캐시) |
| 분산처리 | Redis (Redisson 분산 락, INCR 채번), Bucket4j (분산 Rate Limiting) |
| 스케줄링 | Spring @Scheduled + ShedLock (JDBC) |
| 배치 | Spring Batch (고정폭 파일, chunk + skip 결함 허용) |
| 보안 | Spring Security + JWT (jjwt) |
| 암복호화      | AES-256-CBC, AES-256-ECB, RSA-OAEP      |
| 알림 | REST 채널(SMS/Email/카카오톡) + Firebase Admin SDK (FCM 앱 푸시) |
| 모니터링 | Spring Boot Actuator + Micrometer + Prometheus |
| API 문서    | SpringDoc OpenAPI (Swagger)             |
| Build / CI | Gradle, GitHub Actions, JaCoCo |


<br>

<a id="package-structure"></a>
## 📁 패키지 구조

```
com.ghyinc.finance
├── domain
│   ├── loan                                   # 핵심 도메인: 한도조회 · 대출신청
│   │   ├── controller
│   │   │   └── LoanController.java               # 한도조회 / 콜백 수신 / 폴링 / 결과요약 / 대출신청 API
│   │   ├── service
│   │   │   ├── LoanLimitService.java                     # 요청 수신 · 중복요청 방어(Redis 락) · Strategy 실행 · 결과 폴링
│   │   │   ├── LoanLimitInquiryPersistenceService.java   # DB 트랜잭션 전용 협력자 (최초 INSERT / 선저장 / 결과반영 / FAILED 처리)
│   │   │   ├── LoanLimitEventHandler.java                # AFTER_COMMIT 이벤트 리스너 → 팬아웃 제출, 포화 시 보상 처리
│   │   │   ├── LoanLimitSenderService.java               # 금융사 병렬 팬아웃 (무트랜잭션 구간)
│   │   │   ├── LoanLimitResultService.java               # 금융사 콜백 수신 처리
│   │   │   ├── LoanLimitCounterService.java              # 콜백 카운트 증가 전용 (REQUIRES_NEW)
│   │   │   ├── LoanLimitSummaryService.java              # 결과 화면용 요약 (가능/불가 금융사 분류)
│   │   │   ├── LoanApplyService.java                     # 선택한 한도결과 기반 대출신청
│   │   │   └── ProductService.java                       # 금융사별 상품 조회 (Redis 분산 락 + 캐시)
│   │   ├── adaptor                            # 금융사별 API 변환
│   │   │   ├── common                             # 표준 Layout 금융사 공통 (CommonLoanLimitAdaptor / CommonResultAdaptor)
│   │   │   ├── impl                               # 비표준 금융사 개별 구현 (Kakaobank / Tossbank / Linebank + 각 ResultAdaptor)
│   │   │   ├── callback                           # 콜백 수신 Adaptor 인터페이스 + Factory
│   │   │   └── dto                                # LoanLimitAdaptorRequest / Response
│   │   ├── factory                            # LoanLimitStrategyFactory / LoanLimitAdaptorFactory
│   │   ├── strategy                           # 대출유형별 전략 (Personal / Auto / Mortgage / Business)
│   │   ├── entity                             # LoanLimitInquiry(Aggregate Root) / LoanLimitResult / LoanLimitProductResult / Partner / Product / LoanApply ...
│   │   ├── repository                         # Spring Data JPA
│   │   ├── dto                                # 요청/응답 DTO, PreparedFanout(선저장 결과), ExternalDataContext
│   │   └── enums                              # InquiryStatus / PartnerInquiryStatus / PartnerCode / LoanType ...
│   ├── notification
│   │   ├── controller
│   │   ├── service
│   │   │   ├── NotificationService.java
│   │   │   └── NotificationSenderService.java
│   │   ├── sender                             # 채널별 발송 (Strategy + Template Method)
│   │   │   ├── AbstractNotificationSender.java     # Template Method (CB/Retry/Fallback 골격)
│   │   │   ├── NotificationSender.java             # 전략 인터페이스
│   │   │   ├── NotificationSenderFactory.java      # ChannelType → Sender 자동 수집
│   │   │   └── Sms / Email / Kakao / Push NotificationSender.java
│   │   ├── event
│   │   │   ├── LoanLimitCompletedEventConsumer.java # loan-limit-completed 수신 (@RetryableTopic + DLT)
│   │   │   ├── NotificationEventConsumer.java       # notification.send 수신 (실제 발송)
│   │   │   └── NotificationEvent.java
│   │   ├── entity / repository / dto / enums
│   ├── audit                                  # 감사 로그
│   │   ├── event
│   │   │   └── AuditLogConsumer.java               # audit.partner-transmission / audit.partner-callback 배치 적재
│   │   └── entity / repository
│   ├── kcbcredit                              # KCB 신용변동 파일 수신 · Spring Batch 처리
│   │   ├── scheduler
│   │   │   └── KcbFileIngestScheduler.java         # 매일 03:00, ShedLock + 파일명 이력 기반 멱등성 가드
│   │   ├── file                                # KcbFilePoller / KcbFileProperties (신규 파일 탐색)
│   │   ├── batch                               # Job/Step 설정, Reader 레이아웃(고정폭), Processor, Writer, JobListener
│   │   └── dto / entity / enums / repository
│   ├── auth                                   # JWT 로그인 · 토큰 재발급 (controller / service / security / dto)
│   ├── user                                   # 회원 (Member, MemberRole)
│   └── external                               # 외부 기관 API
│       ├── nice                               # Nice DNR (자동차등록원부) 연동
│       └── coocon                             # KB 부동산 시세 연동
└── global
    ├── client                                 # 통신 방식별 ApiClient (REST, 전용선)
    ├── common                                 # 공통 유틸 (LoReqtNoGenerator 채번, BaseTimeEntity, ApiCommResponse 등)
    ├── config                                 # Spring 설정
    │   ├── AsyncConfig.java                        # 스레드풀 5종 (loanLimit / partnerApi / compensation / outboxPublish / outboxRetry)
    │   ├── Kafka*Config.java                       # Kafka Producer/Consumer, 토픽 선언
    │   ├── PartnerConnectionPoolConfig / PartnerOrTimeoutConfig / RestClientConfig ...   # 금융사별 커넥션 풀 · 타임아웃 계층
    │   └── RateLimiterConfig / RetryTemplateConfig / CacheConfig / SecurityConfig ...
    ├── filter                                 # InboundRateLimiterFilter(Bucket4j+Redis) / JwtAuthenticationFilter / RequestIdFilter
    ├── jwt                                    # JWT 토큰 발급·검증
    ├── lock                                   # RedisLockExecutor (Redisson 분산 락)
    ├── circuitbreaker                         # Circuit Breaker 상태 조회 · 이벤트 리스너
    ├── crypto                                 # 암복호화 (AES, RSA) + CryptoFactory
    ├── event                                  # 도메인 이벤트 / Kafka 페이로드 (LoanLimitInquiryCreatedEvent, LoanLimitCompletedEvent, Audit 이벤트)
    ├── exception                              # 전역 예외 처리
    ├── interceptor / health / init            # HTTP 로깅·재시도 인터셉터 / Redisson 헬스체크 / 초기 데이터 적재
    ├── outbox                                 # Outbox 패턴 (트랜잭션 보장)
    │   ├── entity                                  # OutboxEvent, OutboxStatus (PENDING / PUBLISHED / FAILED)
    │   ├── event                                   # OutboxCreatedEvent
    │   ├── repository
    │   ├── service
    │   │   ├── OutboxEventWriter.java              # Outbox INSERT + OutboxCreatedEvent 발행 (호출자 트랜잭션에 참여)
    │   │   └── OutboxEventService.java             # AFTER_COMMIT 즉시 발행 / 배치 재시도용 동기 발행
    │   └── scheduler
    │       └── OutboxEventBatchPublisher.java      # @Scheduled + ShedLock, PENDING 건 재시도
    ├── kafka
    │   ├── backoff                                 # JitteredExponentialBackOff
    │   └── dlq                                     # Kafka DLQ 처리
    │       ├── entity / repository                     # DlqEvent, DlqStatus
    │       ├── DlqEventConsumer.java                   # DLT 토픽 수신 + Poison Pill 자동 분류
    │       ├── DlqRetryScheduler.java                  # 지수 백오프 자동 재시도
    │       └── PoisonPillClassifier.java               # Poison Pill 판별
    └── metrics                                # Micrometer 지표
        ├── PartnerSlaMetricsConsumer.java          # audit.partner-transmission 독립 구독, 파트너사별 SLA 지표
        ├── PartnerConnectionPoolMetrics.java       # 금융사 HTTP 커넥션 풀 사용량
        └── TaskExecutorMetrics.java                # 스레드풀 사용량
```

<br>

<a id="architecture"></a>
## 🏗 핵심 아키텍처

### 1. 한도조회 비동기 처리 흐름

한도조회는 **"접수 → 팬아웃 → 콜백 수신 → FE 폴링"** 4단계로 나뉩니다. 요청 스레드는 접수(Inquiry INSERT)까지만 담당하고 즉시 응답하며, 금융사 호출과 결과 반영은 별도 스레드풀에서 처리합니다. **DB 트랜잭션은 짧은 구간 단위로 쪼개고, 금융사 응답을 기다리는 팬아웃 구간은 트랜잭션 없이 수행**하여 대기 시간 동안 DB 커넥션을 점유하지 않습니다.

```
FE → POST /api/loan/request-compare-loan
         │
         ▼
  InboundRateLimiterFilter                          [HTTP 요청 스레드]
  └── Bucket4j + Redis, 클라이언트(IP)당 초당 20건 초과 시 429 (인스턴스 수와 무관)
         │
         ▼
  LoanLimitService.requestCompareLoan()
  ├── Redis 분산 락 (userId + loanType, 대기 0초) — 동시 중복 요청 차단
  ├── 진행 중 조회(PENDING / IN_PROGRESS) 존재 여부 확인 → 있으면 거절
  ├── Strategy 선택 (대출유형별) → validate()
  ├── 외부데이터 조회 (Nice DNR, KB시세 등 — 필요한 유형만)
  ├── 조회 가능 금융사 선정
  │     ├── strategy.getSupportedBanks()       : 대출유형별 지원 금융사 (코드 레벨)
  │     └── strategy.filterAvailablePartners() : 외부데이터 실패 시 해당 금융사 동적 제외
  ├── strategy.toAdaptorRequest() — 금융사 전송용 공통 요청 DTO 생성
  └── PersistenceService.createLoanLimitInquiry()      ◀ [Tx 0] 짧은 트랜잭션
        ├── LoanLimitInquiry INSERT (inquiryNo 채번, PENDING)
        ├── LoanLimitInquiryCreatedEvent 발행 (Spring 이벤트)
        └── COMMIT 후 inquiryNo 포함 즉시 응답
         │
         ▼ @TransactionalEventListener(AFTER_COMMIT)
  LoanLimitEventHandler.handleInquiryCreated()
  │  커밋 이후 실행 보장 (커밋 전 실행 시 콜백이 먼저 도착해도 Inquiry 조회 불가 → Race Condition)
  │  @Async 대신 executor.execute()로 직접 제출 — 큐 포화(TaskRejectedException)를 이 자리에서 잡기 위함
  │
  ├── 정상: loanLimitExecutor에 팬아웃 제출 → HTTP 스레드 즉시 해제
  └── 포화: compensationExecutor(전용 소형 풀)에서 markFailed() — Inquiry FAILED 처리 (REQUIRES_NEW)
         │
         ▼ loanLimitExecutor 스레드
  LoanLimitSenderService.inquiry()                  ※ 자체는 @Transactional 아님
  │
  ├── ① [Tx 1] PersistenceService.preSave()          ◀ 선저장 (짧게 COMMIT)
  │     ├── Inquiry → IN_PROGRESS
  │     ├── LoanLimitResult INSERT        (금융사당 1건)
  │     ├── LoanLimitProductResult INSERT (상품당 1건, PENDING, loReqtNo 채번)
  │     ├── Inquiry 전체 상품 수 초기화
  │     └── 팬아웃에 필요한 값만 PreparedFanout(record)으로 반환 (엔티티 반환 금지 → 트랜잭션 밖 Lazy 이슈 방지)
  │
  ├── ② [무트랜잭션] 금융사 병렬 팬아웃                ◀ DB 커넥션 미점유
  │     ├── CompletableFuture.supplyAsync(adaptor.inquireLimit(), partnerApiExecutor)
  │     ├── .orTimeout(금융사별 orTimeout)   — connect < read < orTimeout 계층
  │     ├── 어댑터 호출 안쪽: Rate Limiter → Bulkhead → Circuit Breaker → Retry (Resilience4j)
  │     └── 실패는 예외 전파 대신 Fallback 응답으로 변환 → 다른 금융사 진행에 영향 없음
  │           CB_OPEN / RATE_LIMIT_EXCEEDED / BULKHEAD_FULL / THREAD_POOL_EXHAUSTED / 기타 예외
  │
  └── ③ [Tx 2] PersistenceService.applyResults()      ◀ 결과반영 (짧게 COMMIT)
        ├── 금융사별 Result / ProductResult 상태 UPDATE (SEND_SUCCESS / SEND_FAILED)
        ├── 금융사별 전송 이력 Outbox INSERT (PartnerTransmission)
        ├── Inquiry 최종 상태 결정: SUCCESS / PARTIAL_SUCCESS / FAILED
        └── FAILED가 아니면 완료 이벤트 Outbox INSERT (LOAN_LIMIT_COMPLETED) — 같은 트랜잭션, 원자적

      ※ ①②③ 어디서든 예외 발생 시 markFailed()로 Inquiry를 FAILED 처리 (best-effort)
      ※ ①③은 별도 빈(PersistenceService)으로 분리 — 같은 클래스 내부 호출은 AOP 프록시를 우회해 @Transactional이 무효화됨
         │
         ▼ @TransactionalEventListener(AFTER_COMMIT)
  OutboxEventService.publishAfterCommit()           [outboxPublishExecutor, 별도 스레드풀 · REQUIRES_NEW]
  ├── Kafka 발행 성공 → OutboxEvent PUBLISHED UPDATE
  ├── Kafka 발행 실패 → OutboxEvent PENDING 유지
  └── outboxPublishExecutor 포화 → 즉시발행 스킵 (PENDING으로 남아 배치가 처리)
         │
         ▼ (Kafka 장애 등으로 PENDING이 남은 경우)
  OutboxEventBatchPublisher.retryPendingEvents()    [@Scheduled 60초, ShedLock, outboxRetryExecutor]
  ├── PENDING 건 조회 (일정 시간 경과분, 최대 100건)
  ├── 동기 발행(send().get(timeout)) 후 결과 확정
  └── applyRetryResult(): PUBLISHED / FAILED 반영 (REQUIRES_NEW, id로 재조회)
         │
         ▼ Kafka (loan-limit-completed)
  LoanLimitCompletedEventConsumer (notification 도메인)   [@RetryableTopic: 지수 백오프 재시도 + DLT]
  └── NotificationService → Notification INSERT + notification.send 이벤트 발행
         │
         ▼ Kafka (notification.send)
  NotificationEventConsumer
  ├── NotificationSenderFactory.getSender(channelType)
  ├── AbstractNotificationSender.send() — CircuitBreaker + Retry + Fallback
  └── 채널별 실제 발송 (SMS/Email/카카오톡: RestClient, 앱 푸시: FCM)
```

```
─────────────────────────────────────────────────────
  Callback (금융사 → 플랫폼)
─────────────────────────────────────────────────────
  금융사 → POST /api/loan/response-compare-loan-result   (Header: X-Partner-Code)
  LoanLimitResultService.responseCompareLoanResult()
  ├── LoanLimitResultAdaptorFactory.getAdaptor(partnerCode)
  │     └── 금융사별 콜백 포맷 → 공통 DTO(LoanLimitResultRequest) 변환
  ├── loReqtNo + productCode로 선저장된 ProductResult 조회
  ├── 상태가 SEND_SUCCESS가 아니면 skip (중복 수신 / 전송 실패·타임아웃 건은 덮어쓰지 않음)
  ├── LoanLimitCounterService.incrementSuccessCount()      ◀ 콜백 카운트 원자적 UPDATE, REQUIRES_NEW 별도 트랜잭션
  │     └── UPDATE 직후 COMMIT → Inquiry row lock 즉시 해제 (동일 Inquiry에 다수 금융사 콜백이 몰려도 서로 대기하지 않음)
  ├── ProductResult UPDATE (resultCode, 한도, 금리)
  ├── 콜백 이력 Outbox INSERT (PartnerCallback)
  └── 금융사에는 항상 금융사 포맷의 응답 반환 — 처리 실패 시 실패 응답을 내려 재전송 여부를 금융사가 판단하도록 함
```

```
─────────────────────────────────────────────────────
  결과 조회 (FE → 플랫폼)
─────────────────────────────────────────────────────
  GET /api/loan/inquiry/{inquiryNo}            FE 폴링
  └── 전체 콜백이 수신된 경우(isAllResultReceived)에만 상품별 결과를 페이징하여 반환, 그 전에는 진행 상태만 반환

  GET /api/loan/inquiry/{inquiryNo}/summary    결과 화면 전용 요약
  └── 대출 가능 / 불가 금융사 분류, 상품별 그룹핑, 아직 응답 대기 중인 금융사 집계

  POST /api/loan/apply                         대출신청
  └── 한도 이력 · 선택 상품 결과 검증 → 부결 상품 / 중복 신청 차단 → LoanApply INSERT
```

```
─────────────────────────────────────────────────────
  배치 (KCB 신용변동 파일 수신)
─────────────────────────────────────────────────────
  KcbFileIngestScheduler                       [매일 03:00 · ShedLock]
  ├── KcbFilePoller: 신규 파일 탐색
  ├── 파일명 이력 조회(KcbCreditFile) — 이미 처리된 파일이면 스킵 (멱등성)
  └── Spring Batch Job 실행: 고정폭 파일 읽기 → chunk 단위 처리 → skip 기반 결함 허용
```

### 2. Kafka 토픽 구성

```
loan-limit-completed   loan → notification 도메인 간 이벤트 전달
                        한도조회 완료 시 발행 (inquiryNo가 partition key)
                        OutboxEventService가 발행 (Outbox Pattern)

notification.send      notification 도메인 내부 비동기 발송 처리
                        Notification INSERT 후 실제 발송 분리

audit.partner-transmission   파트너사 API 전송 이력 감사 로그 (inquiryNo가 partition key)
                              독립된 컨슈머 그룹 2개가 같은 토픽을 병렬 구독:
                              ├── audit-log-group           → AuditLogConsumer (감사 로그 DB 배치 적재)
                              └── partner-sla-metrics-group → PartnerSlaMetricsConsumer (Micrometer 지표 수집)
                              한쪽이 느려지거나 장애가 나도 다른 쪽 처리에 영향 없음 (컨슈머 그룹 단위로 완전히 독립)

audit.partner-callback       파트너사 콜백 수신 이력 감사 로그 (loReqtNo가 partition key, AuditLogConsumer가 구독)

*.DLT                        loan-limit-completed / notification.send / audit.partner-transmission / audit.partner-callback
                              각 토픽별 처리 실패 메시지 보관 (총 4개)
```

모든 토픽은 Outbox의 `aggregateType`으로 분기하여 발행합니다.

| aggregateType | 토픽 | 이벤트 |
|---|---|---|
| `LoanLimitInquiry` | `loan-limit-completed` | 한도조회 완료 |
| `Notification` | `notification.send` | 알림 발송 요청 |
| `PartnerTransmission` | `audit.partner-transmission` | 금융사 전송 이력 |
| `PartnerCallback` | `audit.partner-callback` | 금융사 콜백 수신 이력 |

**파트너사별 SLA 모니터링 (`PartnerSlaMetricsConsumer`)**: `audit.partner-transmission` 이벤트(`PartnerTransmissionAuditEvent`)에는 이미 `partnerCode`/`success`/`resTimeMs`가 담겨 있어, DB 재조회 없이 payload만으로 Micrometer `Counter`(`partner.transmission.count`)와 `Timer`(`partner.transmission.duration`)를 `partner`/`result` 태그로 기록합니다. `/actuator/prometheus`로 그대로 노출되어 파트너사별 실패율·p95 응답시간을 Grafana에서 바로 확인할 수 있습니다. 감사 로그(`AuditLogConsumer`)와 달리 완전성보다 가용성을 우선해 파싱 실패 시에도 예외를 전파하지 않고 로그만 남긴 뒤 다음 메시지로 넘어갑니다(재시도/DLT 없음).

### 3. 디자인 패턴

#### Strategy + Factory 패턴
대출유형(신용/담보/사업자/오토담보)별로 지원 금융사, 유효성 검증, 외부데이터 조회, 요청 변환 로직을 캡슐화합니다.

```java
// 대출유형별 전략 자동 선택
LoanLimitStrategy strategy = strategyFactory.getStrategy(request.loanType());
strategy.validate(request);
ExternalDataContext context = strategy.requiresExternalData()
        ? strategy.fetchExternalData(request)
        : ExternalDataContext.empty();
List<PartnerCode> available = strategy.filterAvailablePartners(strategy.getSupportedBanks(), context);
LoanLimitAdaptorRequest adaptorRequest = strategy.toAdaptorRequest(request, context);
```

#### Adaptor + Factory 패턴
금융사별 자체 API Layout을 내부 표준 DTO로 변환합니다. 요청(한도조회 전송)과 응답(콜백 수신) 각각 Adaptor/Factory 쌍이 있습니다.

```
표준 Layout 금융사 → CommonLoanLimitAdaptor / CommonResultAdaptor (yml 설정만으로 금융사 추가)
자체 Layout 금융사 → Kakaobank / Tossbank / Linebank + 각 ResultAdaptor (impl)

LoanLimitAdaptorFactory        partnerCode → 한도조회 전송 Adaptor
LoanLimitResultAdaptorFactory  partnerCode → 콜백 수신 Adaptor
```

#### Strategy + Template Method 패턴 (notification)
채널(SMS/Email/카카오톡/앱 푸시)별 발송 로직을 캡슐화합니다. 자세한 구조는 아래 `📨 알림 서비스` 섹션에서 다룹니다.

#### 트랜잭션 경계 분리 (협력자 빈 패턴)
`@Transactional`이 필요한 구간을 별도 빈으로 분리해 프록시를 경유하도록 합니다. 같은 클래스 내부 호출은 AOP 프록시를 우회하므로, 짧게 커밋해야 하는 구간은 반드시 다른 빈으로 나눕니다.

```
LoanLimitSenderService        → 트랜잭션 없이 팬아웃 오케스트레이션
LoanLimitInquiryPersistenceService → 최초 INSERT / preSave / applyResults / markFailed 각각 짧은 트랜잭션
LoanLimitCounterService       → 콜백 카운트 증가만 REQUIRES_NEW
OutboxEventService            → 즉시발행(REQUIRES_NEW) / 배치 결과 반영(REQUIRES_NEW)
```

#### 통신 방식별 ApiClient 분리

```
REST   → RestApiClient
전용선 → LeaseLineApiClient (고정길이 전문, EUC-KR 인코딩)
```

<br>

<a id="thread-pool-concurrency"></a>
## ⚙️ 스레드풀 구성과 동시성 제어

### Executor 구성

용도가 다른 작업이 한 풀에서 서로를 밀어내지 않도록 스레드풀을 5개로 분리했습니다.

| Executor | core / max / queue | 거절 시 동작 | 역할 |
|---|---|---|---|
| `loanLimitExecutor` | 15 / 40 / 60 | `AbortPolicy` → 호출부에서 FAILED 보상 | 한도조회 팬아웃 오케스트레이션 (조회 1건이 팬아웃 완료까지 스레드 1개 점유) |
| `partnerApiExecutor` | 300 / 400 / 50 | `AbortPolicy` → `THREAD_POOL_EXHAUSTED` fallback | 금융사별 병렬 API 호출 (I/O 대기 전용) |
| `compensationExecutor` | 2 / 5 / 200 | 제출 실패 시 로그만 남기고 스킵 (best-effort) | `loanLimitExecutor` 포화 시 Inquiry FAILED 전환 전용 |
| `outboxPublishExecutor` | 20 / 20 / 200 | 즉시발행 스킵 → PENDING으로 남아 배치가 처리 | Outbox 즉시 Kafka 발행 |
| `outboxRetryExecutor` | 10 / 10 / 100 | `AbortPolicy` | Outbox 배치 재시도 병렬 발행 |

**사이징 근거**

- `partnerApiExecutor`는 Little's Law로 산정했습니다. 초당 20건 × 금융사 49곳 팬아웃 × 평균 응답 0.3초 ≈ 294 → `core=300`, `max=400`(HTTP 커넥션 풀 `maxTotal`과 동일). 큐는 짧은 버스트만 흡수하도록 작게(50) 두었습니다.
- `loanLimitExecutor`는 팬아웃 대기 동안 스레드를 점유하는 구조라 `max`가 곧 동시에 진행 가능한 한도조회 수입니다. DB 커넥션 풀(`maximum-pool-size=150`)은 이 값에 콜백·Outbox 배치 등 다른 트랜잭션 경로 여유분을 더해서 잡았습니다.
- `compensationExecutor`는 의도적으로 작게 두었습니다. `loanLimitExecutor`가 포화된 뒤에는 유입되는 모든 요청이 FAILED 보상 경로(`REQUIRES_NEW`, 새 DB 커넥션)를 동시에 타기 때문에, 이 경로를 요청 스레드에서 그대로 실행하면 executor 포화가 곧바로 DB 커넥션 풀 고갈로 번집니다. 전용 소형 풀로 위임해 **보상용으로 동시에 열리는 커넥션 수의 상한을 하드 캡**으로 걸었습니다.
- `outboxRetryExecutor`는 `core = max`로 맞췄습니다. `ThreadPoolExecutor`는 bounded queue가 가득 차기 전에는 `core`를 넘겨 스레드를 늘리지 않으므로, `core`가 작으면 `max`가 있어도 실제 동시성이 `core`로 고정됩니다.
- `outboxPublishExecutor`를 파트너 API용 풀과 분리한 이유는 Kafka 장애 시 발행 스레드가 블로킹되더라도 금융사 호출 경로에 영향을 주지 않도록 하기 위해서입니다. 포화되면 발행을 건너뛰고 Outbox가 `PENDING`으로 남아 배치 재시도가 처리하므로 데이터는 유실되지 않습니다.

풀이 포화되면 큐잉으로 지연을 숨기지 않고 `AbortPolicy`로 즉시 실패시킵니다. 포화 상태를 호출부에 빠르게 알려 장애를 조기에 드러내는 쪽을 택했습니다. 초기값은 계산으로 잡되, 실제 부하 테스트로 처리량이 꺾이는 지점을 확인해 조정합니다.

```java
@Bean(name = "partnerApiExecutor")
public Executor partnerApiExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(300);   // Little's Law: 20 req/s × 49 팬아웃 × 0.3s ≈ 294
    executor.setMaxPoolSize(400);    // partnerConnectionManager maxTotal과 동일하게
    executor.setQueueCapacity(50);   // 짧은 버스트만 흡수
    executor.setTaskDecorator(new MdcTaskDecorator());
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.initialize();
    return executor;
}
```

### 스레드 전환 시 컨텍스트 전파

`@Async`/`CompletableFuture`로 스레드가 바뀌면 호출 스레드의 MDC(requestId)가 유실됩니다. `MdcTaskDecorator`가 작업 제출 시점의 MDC 스냅샷을 복사해뒀다가 실행 스레드에 복원하고, 종료 후에는 반드시 `MDC.clear()`로 정리합니다 — 스레드풀 스레드는 재사용되므로 정리를 빠뜨리면 이전 요청의 requestId가 다음 요청 로그에 섞여 나갈 수 있습니다. Kafka Consumer 스레드의 MDC 복원은 별도 메커니즘(`RecordInterceptor`)을 쓰는데, 자세한 내용은 [MDC 전파](#mdc-propagation) 섹션에서 다룹니다.

### 동시성 이슈 방어 지점

| 지점 | 문제 | 방어 방식 |
|---|---|---|
| 한도조회 요청 (동일 사용자 동시 요청) | 같은 `userId + loanType`으로 조회가 중복 생성됨 | Redis 분산 락(Redisson, 대기 0초) + 진행 중(PENDING / IN_PROGRESS) 조회 존재 여부 확인 |
| 팬아웃 대기 구간 | 금융사 응답을 기다리는 동안 DB 커넥션을 붙잡아 커넥션 풀 고갈 | 트랜잭션을 선저장 / 결과반영 두 구간으로 분리, 팬아웃 대기는 무트랜잭션 — [핵심 아키텍처](#architecture) |
| Executor 포화 | 포화 후 유입되는 요청이 모두 보상 트랜잭션(새 커넥션)을 요구 | 전용 `compensationExecutor`(최대 5)로 동시 보상 수 제한 |
| 콜백 수신 (다수 금융사 동시 응답) | `LoanLimitInquiry` count Lost Update, 행 락 대기 중 커넥션 점유 | 원자적 UPDATE를 `REQUIRES_NEW`로 분리해 즉시 커밋 — [콜백 동시성 제어](#callback-concurrency) |
| 상품 정보 캐싱 | 캐시 미스 시 다수 스레드의 중복 조회(Cache Stampede) | Redis 분산 락(Redisson) + 더블 체크 — [캐싱 전략](#caching-strategy) |
| 인바운드 트래픽 | 멀티 인스턴스 환경에서 로컬 Rate Limiter로는 전체 TPS 제한 불가 | Redis 공유 카운터 기반 Bucket4j — [인바운드 트래픽 방어](#inbound-rate-limiting) |
| 파트너사 API 호출(outbound) | Resilience4j RateLimiter는 인스턴스-로컬이라 인스턴스 수만큼 계약 TPS 초과 가능 | **알려진 한계** — 인바운드와 동일한 Bucket4j+Redis 공유 카운터 구조로 전환 검토 중 |

마지막 행은 의도적으로 "해결됨"이 아니라 "알려진 한계"로 남겨뒀습니다. 인바운드는 이미 Redis 기반으로 전환했지만 outbound(파트너사 호출)는 아직 Resilience4j RateLimiter를 쓰고 있어 동일한 갭이 존재합니다.

### HTTP 커넥션 풀

파트너사 호출에 쓰던 `RestClient`는 원래 `SimpleClientHttpRequestFactory`(JDK `HttpURLConnection` 기반)를 썼습니다. 이 구현은 커넥션 재사용 한도가 JVM 전역 시스템 프로퍼티(`http.maxConnections`, 기본값 5)로만 제어되고, `RestClient` 인스턴스별·파트너별로 다르게 줄 방법이 없었습니다. `partnerApiExecutor`가 스레드를 최대 150개까지 띄워도, 같은 파트너사 목적지로 나가는 실제 동시 커넥션은 기본 설정상 5개로 묶여 있어 스레드는 늘어나도 커넥션을 기다리며 블로킹되는 병목이 될 수 있었습니다.

이를 해소하기 위해 `PoolingHttpClientConnectionManager`(Apache HttpClient5)로 전환했습니다(`PartnerConnectionPoolConfig`). 전체 파트너가 공유하는 커넥션 풀(`maxTotal=400`, 기본 `maxPerRoute=10`) 위에 파트너별 상한(`maxPerRoute`)을 `HttpRoute` 단위로 얹고, 풀이 고갈됐을 때는 무한 대기 대신 `connectionRequestTimeout(500ms)`으로 빠르게 실패하도록 해서 Executor의 `AbortPolicy`와 동일한 "포화 시 빠른 실패" 원칙을 커넥션 풀 레벨까지 확장했습니다.

전환 과정에서 세 가지를 놓치기 쉬웠습니다. 먼저, `HttpRoute`의 동등성은 scheme/host/port 기준이라 여러 파트너가 같은 호스트를 공유하면(로컬 부하테스트에서 전 파트너가 mock 서버 하나를 바라보는 경우) 서로 다른 파트너가 같은 라우트로 충돌합니다. 파트너를 순회하며 `setMaxPerRoute(route, n)`을 그냥 호출하면 마지막 파트너의 값이 나머지 전부를 덮어써서, 49개 파트너가 공유하는 라우트인데도 실제 허용 커넥션이 15~20개에 머무는 문제가 있었습니다. 지금은 같은 라우트의 `maxPerRoute`를 **덮어쓰지 않고 합산**하고 `maxTotal`을 넘지 않도록 캡을 씌웁니다. 둘째, `SHINHAN_BANK`는 전용선(`ConnectionType.LEASE_LINE`)이라 `base-url`에 스킴이 없는데(`127.0.0.1`), 이걸 걸러내지 않고 전체 파트너를 순회하며 `HttpHost`를 만들면 기동 시점에 `PoolingHttpClientConnectionManager` 빈 생성 자체가 예외로 실패합니다 — `ConnectionType.REST`인 파트너만 대상으로 걸러야 합니다. 셋째, HttpClient5에서는 커넥션 연결(connect) 타임아웃이 `RequestConfig`가 아니라 `ConnectionConfig` 소관입니다(연결 타임아웃은 새 물리 커넥션을 맺는 순간에만 의미가 있는 값이라, 풀에서 커넥션을 재사용하는 구조에서는 요청 단위가 아니라 커넥션 단위 설정이 맞다는 게 HttpClient5의 설계 의도). `PoolingHttpClientConnectionManager`엔 라우트별 `setConnectionConfig()`가 없어서, `setConnectionConfigResolver(Resolver<HttpRoute, ConnectionConfig>)`로 파트너별 connect timeout을 매핑하고 목록에 없는 라우트는 폴백 값으로 처리하도록 구성했습니다.

만료 커넥션 정리는 `evictExpiredConnections()`(서버가 Keep-Alive 헤더로 명시한 만료 시각 경과)와 `evictIdleConnections()`(30초 유휴) 둘 다 등록해뒀습니다 — 판단 기준이 달라 하나만 켜두면 다른 한쪽이 놓친 stale 커넥션이 재사용될 수 있습니다.

풀 상태(leased/pending/available/max)는 `PartnerConnectionPoolMetrics`가 Micrometer Gauge로 전체·파트너별 태그를 붙여 노출합니다 — 이번 전환이 실제로 병목을 해소했는지는 이 지표로 실측 검증할 예정입니다.

<br>

<a id="domain-model"></a>
## 🗄 핵심 도메인 모델

```
LoanLimitInquiry (한도조회 요청 1건)
  ├── inquiryNo             업무 식별번호 (채번, FE 폴링 / 이력 조회 Key)
  ├── userId / loanType     인덱스 (user_id, loan_type, status) — 진행 중 조회 확인용
  ├── status                PENDING → IN_PROGRESS → SUCCESS / PARTIAL_SUCCESS / FAILED
  ├── totalProductCount     전체 상품 수 (선저장 시점에 초기화)
  └── successProductCount   콜백 수신이 끝난 상품 수 (전체 수신 여부 판단: isAllResultReceived)

LoanLimitResult (금융사당 1건 — 전송 결과)
  ├── partnerCode
  ├── status                PENDING → SUCCESS / FAILED (금융사 API 전송 성공 여부)
  └── failReason / resTimeMs

LoanLimitProductResult (상품당 1건 — 콜백 결과)
  ├── loReqtNo              상품별 유니크 채번 (콜백 연결 Key)
  ├── partnerCode / productCode
  ├── status                PENDING → SEND_SUCCESS / SEND_FAILED → SUCCESS (콜백 수신 완료)
  ├── resultCode            SUCCESS / LIMIT_DENIED / CREDIT_SCORE_LOW ...
  └── amount / interestRate

LoanApply (대출신청 1건)
  ├── loReqtNo              → LoanLimitProductResult 연결
  ├── partnerCode / productCode
  └── status                PENDING → SUBMITTED / FAILED
```

### Aggregate Root 패턴

`LoanLimitInquiry`를 Aggregate Root로 하여 `LoanLimitResult`, `LoanLimitProductResult`의 생성과 상태 변경을 Aggregate Root를 통해서만 수행합니다.

```
[한도조회 Aggregate]
 
LoanLimitInquiry (Aggregate Root)
  ├── List<LoanLimitResult>        (금융사당 1건)
  └── List<LoanLimitProductResult> (상품당 1건)
```

```java
// 외부에서 직접 생성 금지 → Aggregate Root를 통해서만 추가
inquiry.addResult(result);                  // LoanLimitResult 추가
inquiry.addProductResult(productResult);    // LoanLimitProductResult 추가
 
// 도메인 로직도 Aggregate Root에서 실행
inquiry.updateInquiryStatus(InquiryStatus.IN_PROGRESS);
inquiry.initProductCount(totalCount);
inquiry.incrementSuccessCount();  // successProductCount 증가 (콜백 경로는 동시성 때문에 원자적 UPDATE 사용 — 콜백 동시성 제어 참고)
```

<br>

<a id="resilience4j"></a>
## 🔒 장애 격리 - Resilience4j

금융사별 독립적인 Circuit Breaker / Retry / Rate Limiter / Bulkhead 인스턴스(이름 = `PartnerCode`)로 특정 금융사 장애 시 격리합니다. 알림 채널(SMS/EMAIL/KAKAOTALK/PUSH)도 채널명으로 동일하게 독립 인스턴스를 가집니다.

### Circuit Breaker 설정

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        register-health-indicator: true
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10                  # 최근 10건 기준
        minimum-number-of-calls: 5               # 최소 5건 이후 통계
        failure-rate-threshold: 50               # 실패율 50% 이상 시 OPEN
        slow-call-duration-threshold: 7s         # 7초 이상 응답은 느린 호출로 기록
        slow-call-rate-threshold: 50             # 느린 호출 50% 이상 시 OPEN
        wait-duration-in-open-state: 60s         # 파트너 회복 시간 확보
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true
        max-wait-duration-in-half-open-state: 10s
        record-exceptions:
          - org.springframework.web.client.HttpServerErrorException
          - org.springframework.web.client.ResourceAccessException
          - java.net.ConnectException
          - java.net.SocketTimeoutException
        ignore-exceptions:
          - org.springframework.web.client.HttpClientErrorException

      # default를 상속하고 예외 타입만 도메인 예외로 교체 (HTTP 클라이언트 라이브러리에 종속되지 않도록)
      loan-default:
        base-config: default
        record-exceptions:
          - com.ghyinc.finance.global.exception.ExternalApiServerException
        ignore-exceptions:
          - com.ghyinc.finance.global.exception.ExternalApiClientException

      notification-default:
        base-config: default
        record-exceptions:
          - com.ghyinc.finance.global.exception.ExternalApiServerException
        ignore-exceptions:
          - com.ghyinc.finance.global.exception.ExternalApiClientException

    instances:            # 금융사 50여 곳은 모두 loan-default, 알림 채널 4종은 notification-default 상속
      KAKAO_BANK:
        base-config: loan-default
      TOSS_BANK:
        base-config: loan-default
      ...
      SMS:
        base-config: notification-default
```

### Circuit Breaker 상태 전환

```
CLOSED  → 정상 (모든 요청 통과)
OPEN    → 장애 (즉시 CallNotPermittedException, 해당 금융사만 격리)
HALF_OPEN → 복구 시도 (제한적 요청으로 복구 여부 확인)
```

### Fallback - Partial Failure 패턴

Circuit Breaker OPEN, Rate Limiter 초과, Bulkhead 포화는 모두 "우리 쪽 정책으로 호출하지 않은 것"이라 Adaptor에서 결과로 바꾸지 않고 **예외 그대로 위로 던지고**, `LoanLimitSenderService`의 `CompletableFuture.exceptionally()` **단일 지점**에서 실패 응답으로 변환합니다(Adaptor와 Sender 양쪽에서 중복 처리하지 않기 위함). 특정 금융사 장애가 전체 한도조회를 중단시키지 않고 나머지 금융사는 정상 진행합니다.

```java
// LoanLimitSenderService - 금융사별 팬아웃
CompletableFuture
    .supplyAsync(() -> adaptor.inquireLimit(partnerCode, adaptorRequests), partnerApiExecutor)
    .orTimeout(partnerOrTimeouts.get(partnerCode).toMillis(), TimeUnit.MILLISECONDS)
    .exceptionally(ex -> {
        if (ex.getCause() instanceof CallNotPermittedException)   // CB OPEN
            return LoanLimitAdaptorResponse.fail(partnerCode, "CB_OPEN", 0L);
        if (ex.getCause() instanceof RequestNotPermitted)          // Rate Limiter 초과
            return LoanLimitAdaptorResponse.fail(partnerCode, "RATE_LIMIT_EXCEEDED", 0L);
        if (ex.getCause() instanceof BulkheadFullException)        // Bulkhead 포화
            return LoanLimitAdaptorResponse.fail(partnerCode, "BULKHEAD_FULL", 0L);
        return LoanLimitAdaptorResponse.fail(partnerCode, ex.getMessage(), 0L);
    });
```

`partnerApiExecutor`에 작업을 **제출하는 시점**의 거절(`RejectedExecutionException`)은 `supplyAsync()`가 Future를 반환하기 전에 동기적으로 발생해서 `exceptionally()`로는 잡히지 않습니다. 이 경우는 별도 `catch`에서 `THREAD_POOL_EXHAUSTED`로 변환해, 예외가 `map()` 밖으로 튀어나가 전체 요청이 FAILED 되는 것을 막습니다.

### Fallback 적용 후 최종 상태 결정

```
금융사 3개 중 1개 CB OPEN 시
  KAKAO_BANK → Fallback (즉시 실패)   → SEND_FAILED
  TOSS_BANK  → 정상 전송              → SEND_SUCCESS
  KB_BANK    → 정상 전송              → SEND_SUCCESS
 
Inquiry 최종 상태
  성공 2 / 전체 3 → PARTIAL_SUCCESS
  → FE에 조회 가능한 금융사 결과만 반환
  → 장애 금융사 결과 누락 명시
```

### 타임아웃 계층 설계

```
connectionRequestTimeout (500ms) → 풀에서 커넥션 대여 대기 (풀 고갈 시 빠른 실패)
connectTimeout  (금융사별)        → 서버 연결 실패     → ResourceAccessException → CB 실패 기록
readTimeout     (금융사별)        → 응답 미수신        → SocketTimeoutException  → CB 실패 기록
orTimeout       (금융사별, 계산)  → CompletableFuture 강제 종료 (최후 안전망)

connectTimeout < readTimeout < orTimeout
```

`connect` / `read`는 `application.yaml`의 금융사별 설정(`loan-api.partners.<CODE>.connect-timeout-ms / read-timeout-ms`)이고, `orTimeout`은 고정값이 아니라 `PartnerOrTimeoutConfig`가 그 금융사의 설정과 Retry 설정으로 **동적으로 산정**합니다. Retry가 최대로 재시도하는 최악의 경우가 끝나기 전에 Future가 강제 종료되어 Retry 정책을 잘라먹지 않도록 하기 위해서입니다.

```
worstCasePerAttempt = connectionRequestTimeout(500ms) + connectTimeout + readTimeout
orTimeout = maxAttempts × worstCasePerAttempt + worstCaseBackoff + MARGIN(1초)
```

### Retry 설정

```yaml
resilience4j:
  retry:
    configs:
      default:
        max-attempts: 2                       # 최초 1회 + 재시도 1회
        wait-duration: 300ms
        enable-exponential-backoff: true      # 지수 백오프
        exponential-backoff-multiplier: 2
        max-wait-duration: 1s
        enable-randomized-wait: true          # Jitter
        randomized-wait-factor: 0.3           # 계산된 간격 ±30%
        retry-exceptions:
          - org.springframework.web.client.HttpServerErrorException
          - org.springframework.web.client.ResourceAccessException
          - java.net.ConnectException
          - java.net.SocketTimeoutException
          - java.io.IOException
          - com.ghyinc.finance.global.exception.ExternalApiServerException
        ignore-exceptions:
          - com.ghyinc.finance.global.exception.ExternalApiClientException   # 4xx는 재시도 안 함
```

### Rate Limiter 설정

금융사 API 호출량을 제어하여 과도한 요청으로 인한 금융사 측 차단을 방지합니다.

```yaml
resilience4j:
  ratelimiter:
    configs:
      default:
        limit-for-period: 40          # 갱신 주기당 최대 허용 요청 수
        limit-refresh-period: 1s      # 갱신 주기 (1초)
        timeout-duration: 0           # 대기 없이 즉시 실패 (초과 시 RequestNotPermitted)
```

### Bulkhead 설정

Rate Limiter는 "초당 몇 건까지 접수할지"를 제한하지만, 응답이 얼마나 오래 걸리는지는 신경 쓰지 않습니다. 특정 금융사가 느려지면(장애까진 아니라 CB의 `slow-call-rate-threshold`를 안 넘는 수준이어도) Rate Limiter는 계속 요청을 접수시키고, 그 요청들이 응답을 기다리며 쌓여 `partnerApiExecutor`(50여 개 금융사가 공유하는 스레드 풀)를 잠식할 수 있습니다. Bulkhead는 "지금 동시에 진행 중인 호출이 몇 건인지"를 직접 제한해서 이 문제를 막습니다.

```yaml
resilience4j:
  bulkhead:
    configs:
      default:
        max-concurrent-calls: 20   # 동시 진행 허용 건수 (금융사별 max-per-route와 맞춤)
        max-wait-duration: 0       # 대기 없이 즉시 실패
```

### 데코레이터 실행 순서

`RestApiClient`가 금융사별 Registry 인스턴스를 아래 순서로 감쌉니다(바깥 → 안쪽).

```
Rate Limiter → Bulkhead → Circuit Breaker → Retry → 실제 HTTP 호출
  → Rate Limiter, Bulkhead는 CB보다 바깥
    (우리 쪽 정책으로 거절한 호출은 CB 실패 통계에 잡히면 안 됨)
  → Retry가 가장 안쪽: 재시도(maxAttempts=2)가 모두 실패해야 CB에 실패 1건으로 기록
  → CB OPEN이면 Retry 없이 즉시 CallNotPermittedException
  → Rate Limiter / Bulkhead 초과 시 실제 I/O 없이 즉시 반환되어 partnerApiExecutor 스레드를 오래 붙잡지 않음
```

> 위 Resilience4j 구성(CB/Retry/Bulkhead/RateLimiter)은 전부 `RestApiClient` → 금융사 API 호출,
> 즉 **아웃바운드** 경로에만 적용됩니다. 고객이 우리 API로 보내는 **인바운드** 트래픽 자체를 제한하는
> 장치는 아래 별도로 둡니다.

<br>

<a id="inbound-rate-limiting"></a>
## 🚦 인바운드 트래픽 방어 - Rate Limiting

> 클라이언트(IP)별로 초당 요청 수를 제한해, 특정 클라이언트의 트래픽 폭주나 실수로 인한 반복 요청이
> `loanLimitExecutor`/DB 커넥션 풀 같은 공유 자원을 독점하지 못하도록 컨트롤러 진입 전 단계에서 차단합니다.

### 왜 애플리케이션 레벨에도 두는가

Resilience4j RateLimiter는 파트너사로 나가는 트래픽만 제한할 뿐, 우리 서비스로 **들어오는** 트래픽은
전혀 방어하지 않습니다. 인바운드 방어는 보통 API Gateway/WAF 같은 인프라 레벨에서 처리하지만, 그 앞단이
아직 없거나 애플리케이션 자체적으로도 최소한의 방어선을 두고 싶을 때 이 필터가 마지막 안전장치 역할을 합니다.

### 왜 Redis 기반(Bucket4j)인가 - 단일 인스턴스 한도의 한계

애플리케이션 힙에 상태를 두는 방식(Resilience4j `RateLimiterRegistry` 등)은 인스턴스마다 카운터가
독립적으로 존재합니다. 인스턴스가 N대면 로드밸런서가 트래픽을 분산시키는 순간, 같은 클라이언트가 사실상
"설정값 × N"의 실효 한도를 갖게 되어 인스턴스를 늘릴수록 방어가 약해지는 역설이 생깁니다. Redis에 카운터를
두면 모든 인스턴스가 같은 상태를 공유하므로 인스턴스 수와 무관하게 일관된 한도가 유지됩니다. 이미 분산 락에
쓰고 있는 `RedissonClient`를 그대로 재사용합니다.

### 구성

```java
@Bean
public RedissonBasedProxyManager<String> bucket4jProxyManager(RedissonClient redissonClient) {
    return Bucket4jRedisson.casBasedBuilder(((Redisson) redissonClient).getCommandExecutor())
            .expirationAfterWrite(
                    ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(10)))
            .build();
}
```

`InboundRateLimiterFilter`(`OncePerRequestFilter`)가 `/api/loan/request-compare-loan` 요청을 가로채,
클라이언트 IP(`X-Forwarded-For` 우선, 없으면 `getRemoteAddr()`)를 키로 Redis에 저장된 버킷에서
토큰을 하나 소비합니다. 토큰이 없으면 컨트롤러에 진입하지 않고 그 자리에서 `429 Too Many Requests` +
`Retry-After` 헤더로 즉시 응답합니다.

```
클라이언트(IP)당 초당 20건
  → 초과 시 대기 없이 즉시 429 (컨트롤러/서비스 레이어 진입 자체를 차단)
  → 버킷 상태는 Redis에 저장 - 인스턴스 수와 무관하게 동일한 한도 적용
```

<br>

## 🔐 암복호화

금융사별 암호화 알고리즘과 키를 DB 관리합니다. `CryptoFactory`가 `PartnerCode`를 기준으로 적합한 `CryptoService` 구현체를 선택합니다.

| 금융사 | 알고리즘 |
|---|---|
| 카카오뱅크 | AES-256-CBC |
| KB국민은행 | AES-256-ECB |
| 토스뱅크 | RSA-OAEP (2048bit) |

```java
// 금융사별 암호화 자동 선택
CryptoService cryptoService = cryptoFactory.getCryptoService(partnerCode);
String encryptedRrn = cryptoService.encrypt(request.getRrn(), partnerCode);
```

### Caffeine 캐시 적용

`CryptoFactory.getCryptoService()`에 Caffeine 로컬 캐시 적용.

```
적용 이유
  → CryptoService 객체 자체가 직렬화 불가 (SecretKeySpec, Cipher 등)
  → 인스턴스 간 공유 불필요 — 각 인스턴스가 DB에서 동일한 키를 보유
  → JVM 내 메모리 접근으로 Redis 네트워크 비용 없음
```

```java
@Cacheable(
    value = "cryptoService",
    key = "#partnerCode",
    cacheManager = "caffeineCacheManager"
)
public CryptoService getCryptoService(PartnerCode partnerCode) { ... }
```

<br>

<a id="external-integration"></a>
## 🚗 오토담보/주택담보 대출 - Nice DNR, KB부동산 시세정보 연동

```java
// Strategy 패턴으로 대출유형별 외부 데이터 조회 분기
ExternalDataContext context = strategy.requiresExternalData()
        ? strategy.fetchExternalData(request)
        : ExternalDataContext.empty();
```

로컬 테스트 시 `@Profile("local")` MockNiceDnrService로 가데이터를 사용합니다.

<br>

<a id="notification-service"></a>
## 📨 알림 서비스 - 채널별 비동기 발송

한도조회 완료 후 고객에게 결과를 알리는 notification 도메인을 Kafka + Outbox로 loan 도메인과 물리적으로 분리했습니다. loan 도메인은 notification 도메인의 존재 자체를 알지 못하며, `loan-limit-completed` 토픽 발행까지만 책임집니다.

### 도메인 모델

```
Notification (알림 발송 1건)
├── channelType SMS / EMAIL / KAKAOTALK / PUSH
├── sendType IMMEDIATE / SCHEDULED
├── recipient 채널별 수신 대상 (전화번호/이메일/카카오ID/FCM 디바이스 토큰)
├── title / content
├── status PENDING → SUCCESS / FAILED
├── resultCode 파트너/FCM 응답 코드
└── sentAt
```

### 채널별 발송 — Strategy + Template Method

`NotificationSender` 전략 인터페이스와 `AbstractNotificationSender`(Template Method)로 채널별 발송을 분리했습니다. `send()`가 CircuitBreaker + Retry + Fallback을 고정 골격으로 두고, 각 채널 구현체는 `callApi()`만 구현합니다.

```java
@RequiredArgsConstructor
public abstract class AbstractNotificationSender implements NotificationSender {
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    protected abstract ExternalApiResponse callApi(Notification notification);

    @Override
    public final ExternalApiResponse send(Notification notification) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(this.getChannelType().name());
        Retry retry = retryRegistry.retry(this.getChannelType().name());

        Supplier<ExternalApiResponse> apiCall = () -> {
            ExternalApiResponse response = this.callApi(notification);
            if (!response.isSuccess()) {
                throw new ExternalApiFailException(response.getResultCode(), "외부 API 실패");
            }
            return response;
        };

        return Decorators.ofSupplier(apiCall)
                .withCircuitBreaker(circuitBreaker)
                .withRetry(retry)
                .withFallback(ex -> this.fallback(notification, ex))
                .decorate()
                .get();
    }

    // REST 기반 채널(SMS/Email/카카오톡)만 사용하는 공용 HTTP POST 헬퍼
    protected final <Req, Res> ExternalApiResponse post(
            RestClient restClient, String path, Req requestDto,
            Class<Res> responseType, Function<Res, ExternalApiResponse> converter
    ) { ... }
}
```

`NotificationSenderFactory`는 `List<NotificationSender>`를 생성자로 주입받아 `ChannelType → NotificationSender` Map을 자동 구성합니다. 채널을 추가해도 Factory 코드는 변경할 필요가 없습니다.

```java
@Component
public class NotificationSenderFactory {
    private final Map<ChannelType, NotificationSender> senderMap;

    public NotificationSenderFactory(List<NotificationSender> senders) {
        this.senderMap = senders.stream()
                .collect(Collectors.toMap(NotificationSender::getChannelType, Function.identity()));
    }

    public NotificationSender getSender(ChannelType channelType) {
        NotificationSender sender = senderMap.get(channelType);
        if (sender == null) {
            throw new IllegalArgumentException("지원하지 않는 채널입니다: " + channelType);
        }
        return sender;
    }
}
```

### 채널 구현체

| 채널 | 전송 방식 | 구현체 |
|---|---|---|
| SMS / Email / 카카오톡 | Spring `RestClient` (`post()` 헬퍼 재사용) | `SmsNotificationSender` / `EmailNotificationSender` / `KakaoNotificationSender` |
| 앱 푸시 | Firebase Admin SDK (`FirebaseMessaging`) | `PushNotificationSender` |

```java
// PushNotificationSender - post() 대신 FCM SDK를 직접 호출
@Override
protected ExternalApiResponse callApi(Notification notification) {
    FirebaseMessaging firebaseMessaging = firebaseMessagingProvider.getObject(); // 실제 발송 시점에 지연 초기화
    Message message = Message.builder()
            .setToken(notification.getRecipient())
            .setNotification(com.google.firebase.messaging.Notification.builder()
                    .setTitle(notification.getTitle())
                    .setBody(notification.getContent())
                    .build())
            .build();

    try {
        String messageId = firebaseMessaging.send(message);
        return ExternalApiResponse.success(requestId, "SUCCESS", messageId);
    } catch (FirebaseMessagingException e) {
        throw this.toApiException(e); // FirebaseMessagingException -> ExternalApiServerException / ExternalApiClientException
    }
}
```

### Kafka 기반 비동기 처리 흐름

```
[loan 도메인]
LoanLimitResultService
  → OutboxEventWriter.enqueue()
  → OutboxEventService
  → KafkaTemplate.send("loan-limit-completed", inquiryNo, event)
 
        ↓ Kafka (loan-limit-completed 토픽)
 
[notification 도메인]
LoanLimitCompletedEventConsumer
  → NotificationService.sendNotification()
      → Notification INSERT
      → OutboxEventWriter.enqueue()
      → OutboxEventService
      → KafkaTemplate.send("notification.send", id, event)
 
        ↓ Kafka (notification.send 토픽, groupId: notification-send-group)
 
NotificationEventConsumer
  → NotificationSenderFactory.getSender(channelType).send(notification) ← 실제 채널별 발송
  → 발송 결과 markAsSuccess() / markAsFailed()
```

<a id="mdc-propagation"></a>
### MDC 전파

Kafka Consumer는 별도 스레드에서 실행되므로 HTTP 요청의 MDC(requestId)가 자동 전파되지 않습니다. 처음에는 각 Consumer가 개별적으로 `MDC.put()` / `try-finally { MDC.clear() }`를 반복했는데, `KafkaConfig`에 `RecordInterceptor`를 한 번 등록해서 이 보일러플레이트를 전역으로 걷어냈습니다. Producer가 requestId를 Kafka 헤더로 실어 보내고, Interceptor가 리스너 호출 전/후로 MDC를 자동 설정·정리합니다.

```java
@Bean
public RecordInterceptor<String, String> mdcRecordInterceptor() {
    return new RecordInterceptor<>() {
        @Override
        public ConsumerRecord<String, String> intercept(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
            Header header = record.headers().lastHeader(REQUEST_ID_KEY);
            String requestId = header != null
                    ? new String(header.value(), StandardCharsets.UTF_8)
                    : UUID.randomUUID().toString();
            MDC.put(REQUEST_ID_KEY, requestId);
            return record;
        }

        @Override
        public void success(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
            MDC.clear();
        }

        @Override
        public void failure(ConsumerRecord<String, String> record, Exception exception, Consumer<String, String> consumer) {
            MDC.clear();
        }
    };
}
```

<br>

<a id="outbox-pattern"></a>
## 📦 Outbox 패턴 - 트랜잭션 보장

Kafka 발행과 DB 트랜잭션의 원자성을 보장하기 위해 Outbox 패턴을 적용했습니다.

### 도입 배경

```
Outbox 패턴 미적용 시 문제
  → DB UPDATE 성공 + Kafka 발행 실패
      → DB에는 SUCCESS로 기록
      → 알림 발송 누락
 
  → DB UPDATE 성공 + Kafka 발행 성공 + 트랜잭션 롤백
      → DB 롤백
      → Kafka 메시지는 이미 발행됨
      → 알림 중복 발송
```

### Outbox 패턴 흐름

```
비즈니스 트랜잭션
  ├── DB UPDATE                          ─┐
  └── OutboxEvent INSERT (PENDING)        ├─ 같은 트랜잭션 (원자적)
                                         ─┘
        ↓ 트랜잭션 커밋 후

@TransactionalEventListener(AFTER_COMMIT)
OutboxEventService.publishAfterCommit()   [@Async("outboxPublishExecutor") · REQUIRES_NEW]
  ├── Kafka 즉시 발행 시도 (비동기 콜백, 결과를 기다리며 스레드를 묶지 않음)
  ├── 성공 → OutboxEvent PUBLISHED UPDATE
  ├── 실패 → OutboxEvent PENDING 유지
  └── outboxPublishExecutor 포화 → 즉시발행 스킵 (PENDING으로 남아 배치가 처리)

        ↓ 60초마다 (보조 안전망)

@Scheduled OutboxEventBatchPublisher      [ShedLock · outboxRetryExecutor]
  ├── 5분 이상 경과한 PENDING 건 조회 (최대 100건)
  ├── publishToKafkaSync(): send().get(3초)로 결과를 동기 확정
  └── applyRetryResult(id, success): PUBLISHED / FAILED 반영 (REQUIRES_NEW, id로 재조회)
```

배치는 결과를 **동기로 확정**한 뒤에만 상태를 반영합니다. 예전처럼 발행 결과를 기다리지 않고 리턴하면, Kafka 장애가 스케줄 주기(1분)보다 길게 이어질 때 같은 PENDING 건이 다음 주기에 또 집혀 중복 발행될 수 있기 때문입니다. `applyRetryResult`는 다른 스레드(`outboxRetryExecutor`)에서 넘어온 detached 엔티티를 그대로 쓰지 않고 id로 다시 조회하며, Spring AOP self-invocation을 피하려고 `OutboxEventService`의 별도 메서드로 분리되어 있습니다.

### Kafka 생산자 설정

Kafka 장애 시 발행 경로가 스레드와 DB 커넥션을 오래 붙잡지 않도록 시간 상한을 명시합니다.

```yaml
spring.kafka.producer:
  acks: all
  properties:
    enable.idempotence: true
    retries: 2147483647          # 횟수가 아니라 delivery.timeout.ms로 시간을 제한
    delivery.timeout.ms: 480000  # 재시도 포함 전체 전송 시도 최대 시간
    max.block.ms: 3000           # 메타데이터/버퍼 대기 상한 (기본 60초는 장애 시 스레드를 너무 오래 점유)
```

`delivery.timeout.ms`는 전송 재시도 루프의 상한이고, `send()`가 토픽 메타데이터를 **동기적으로** 기다리는 시간은 별개로 `max.block.ms`가 결정합니다. 미설정 시 기본 60초여서 Kafka 장애 때 호출 스레드가 그만큼 묶입니다.

### OutboxEventWriter — 중복 로직 통합

`LoanLimitSenderService`(loan)와 `NotificationService`(notification) 양쪽에 "OutboxEvent 빌드 → save → `OutboxCreatedEvent` 발행"이 동일하게 중복돼 있던 것을 `OutboxEventWriter` 하나로 통합했습니다. 호출자의 기존 트랜잭션에 그대로 참여하며 별도 트랜잭션을 열지 않습니다.

### OutboxEvent 토픽 분기

```java
private String resolveTopic(String aggregateType) {
    return switch (aggregateType) {
        case "LoanLimitInquiry"    -> "loan-limit-completed";
        case "Notification"        -> "notification.send";
        case "PartnerTransmission" -> "audit.partner-transmission";
        case "PartnerCallback"     -> "audit.partner-callback";
        default -> throw new InvalidRequestException("알 수 없는 aggregateType: " + aggregateType);
    };
}
```

### 적용 범위

```
loan 도메인      LoanLimitInquiryPersistenceService.applyResults()
                   → 금융사별 전송 이력 (PartnerTransmission), 한도조회 완료 (LoanLimitInquiry)
                 LoanLimitResultService (콜백 수신)
                   → 콜백 수신 이력 (PartnerCallback)
notification     NotificationService → 알림 발송 이벤트 (Notification)
```

<br>

<a id="kafka-dlq"></a>
## 💀 Kafka DLQ (Dead Letter Queue)

> Kafka Consumer 처리 실패 메시지를 DLQ로 이동하여 유실 없이 관리하고,
> Poison Pill과 일시 장애를 자동 분류하여 각각 다른 방식으로 처리합니다.

### 도입 배경

```
DLQ 미적용 시 문제
  Consumer에서 예외 발생 → 동일 메시지 무한 재시도
  JsonProcessingException → 재시도해도 계속 실패 (Poison Pill)
  → Consumer가 해당 파티션에서 멈춤 (lag 무한 증가)
  → 처리 실패 메시지 유실
```

### 처리 흐름 (Spring Kafka `@RetryableTopic` 기반 non-blocking 재시도)

```
notification.send (원본 토픽)
      │  Consumer 예외 발생
      ▼
@RetryableTopic
      ├── exclude 대상(JsonProcessingException 등 파싱/데이터 오류)
      │     → 재시도 없이 즉시 -dlt 토픽으로 이동
      │
      └── 그 외 예외
            → notification.send-retry-0 (약 1초 뒤, ±jitter)
                  │ 실패
                  ▼
            notification.send-retry-1 (약 2초 뒤, ±jitter)
                  │ 실패
                  ▼
            notification.send-retry-2 (약 4초 뒤, ±jitter)
                  │ 실패 (최대 attempts 소진)
                  ▼
            notification.send-dlt
                  │
                  ▼
            @DltHandler → DlqEvent INSERT (DEAD)
```

원본 토픽 `notification.send`는 이 과정 내내 멈추지 않습니다 — 실패한 메시지는 즉시 재시도 토픽으로 넘어가고,
원본 파티션은 바로 다음 메시지를 계속 처리합니다. 각 `-retry-N` 토픽은 독립된 파티션(`numPartitions`)을
가지므로, 특정 재시도 단계에 적체가 생겨도 그 단계만 컨슈머를 늘려 대응할 수 있습니다.

```java
@RetryableTopic(
        attempts = "4",
        backoff = @Backoff(delayExpression = "1000", multiplierExpression = "2.0",
                            maxDelayExpression = "10000", random = true),   // ±jitter로 동시 재시도 몰림 방지
        listenerContainerFactory = "retryableTopicListenerContainerFactory",
        exclude = { KafkaMessageDeserializationException.class, IllegalArgumentException.class }
)
@KafkaListener(topics = "notification.send", groupId = "notification-send-group")
public void consume(...) { ... }

@DltHandler
public void handleDlt(...) { ... }  // 재시도 소진 - DlqEvent(DEAD) 기록, 절대 예외를 던지지 않음
```

> `@RetryableTopic`이 자체적으로 재시도/DLT 라우팅을 관리하기 때문에, 이 리스너가 쓰는
> `retryableTopicListenerContainerFactory`에는 커스텀 `commonErrorHandler`를 설정하지 않습니다.
> 같은 factory에 커스텀 에러 핸들러를 같이 걸면 `@RetryableTopic`의 내부 처리와 충돌합니다.
> `errorHandler`(지수 백오프+jitter, `.DLT` 라우팅) + `kafkaListenerContainerFactory` 조합은
> 아직 `@RetryableTopic`으로 전환하지 않은 나머지 Consumer(`AuditLogConsumer` 등)에 계속 쓰입니다.

`@DltHandler`는 재시도 토픽 체인을 전부 소진한 뒤 도달하는 마지막 지점이라, 여기서 다시 예외가 나면
(`dltStrategy = DltStrategy.FAIL_ON_ERROR`) 같은 메시지를 무한 재처리하게 됩니다. 그래서 내부 로직 전체를
try-catch로 감싸 어떤 상황에서도 예외를 밖으로 던지지 않도록 구현했습니다.

### 마이그레이션 노트 — DB 폴링 기반 DLQ에서 Kafka 네이티브 재시도 토픽으로

기존에는 실패 메시지를 `DlqEvent` 테이블에 저장하고, `DlqRetryScheduler`가 30초마다(ShedLock, 클러스터
전체에서 단일 노드) 지수 백오프로 원본 토픽에 재발행하는 구조였습니다. 이 방식은 두 가지 한계가 있었습니다.

첫째, 원본 토픽 컨슈머의 제자리 재시도(`DefaultErrorHandler`의 seek 기반 재시도)가 진행되는 동안 **해당
파티션의 뒤에 있는 다른 메시지도 함께 멈춥니다.** 둘째, 재시도 자체가 단일 노드·단일 스케줄러(배치 50건/30초)에
묶여 있어 장애 발생 시 재처리량 자체가 병목이 될 수 있습니다.

`@RetryableTopic`은 재시도를 원본 토픽이 아닌 별도 토픽에서 수행하므로 원본 파티션이 막히지 않고, 재시도
단계별로 독립적인 파티션/컨슈머를 가져 수평 확장이 가능합니다. 이에 따라 `DlqEventConsumer`,
`DlqRetryScheduler`, `dlqErrorHandler`/`dlqKafkaListenerContainerFactory`는 `notification.send`/
`loan-limit-completed` 두 토픽에 대해서는 더 이상 쓰이지 않고, `@DltHandler`가 그 역할(최종 실패 기록)을
대신합니다. 아직 `@RetryableTopic`으로 전환하지 않은 Consumer가 있다면 기존 DB 폴링 방식이 계속 그 Consumer를
커버합니다.

### DLT 수신 대상

`DlqEventConsumer`는 `loan-limit-completed.DLT`, `notification.send.DLT` 두 토픽을 구독하며(그룹 `notification-dlq-group`), 예외 종류로 Poison Pill(→ `DEAD`, 영구 보관)과 일시 장애(→ `PENDING`, `nextRetryAt`을 지정해 자동 재시도)를 분류해 `DlqEvent`로 저장합니다. 재시도는 `DlqRetryScheduler`가 30초 주기(ShedLock)로 처리합니다. `audit.*` 토픽의 DLT는 이 컨슈머의 대상이 아닙니다.

### Poison Pill 판별 기준

```
재시도 없이 즉시 DLT 이동 (@RetryableTopic exclude)
  → KafkaMessageDeserializationException / JsonProcessingException  (페이로드 자체가 깨짐)
  → IllegalArgumentException (데이터 없음 — Notification, Inquiry 조회 실패)

재시도 대상 (기본, exclude 미지정)
  → ConnectException   (DB/외부 API 일시 장애)
  → TimeoutException   (일시적 지연)
  → 그 외 대부분의 RuntimeException
```

<a id="caching-strategy"></a>
## 🗃 캐싱 전략

조회 빈도가 높고 변경 빈도가 낮은 데이터에 캐싱을 적용하여 DB 부하를 줄였습니다.
데이터 특성에 따라 **Redis 캐시**와 **Caffeine 로컬 캐시**를 분리하여 적용했습니다.

### 캐시 저장소 선택 기준

| 구분 | Redis 캐시 | Caffeine 로컬 캐시 |
|---|---|---|
| 적용 대상 | 상품 정보 (`ProductCache`) | 암호화 키 (`CryptoService`) |
| 선택 이유 | 멀티 Pod 간 정합성 필요 | 보안상 외부 저장소 저장 불가 |
| Pod 간 동기화 | O (Redis 공유) | X (Pod별 독립) |
| 무효화 방식 | `@CacheEvict` + TTL | `@CacheEvict` + TTL |
| TTL | 6시간 | 1시간 |

```
멀티 인스턴스 환경에서 상품 정보를 로컬 캐시로 관리하면
  Pod A에서 상품 비활성화 → Pod A 캐시만 evict
  Pod B는 여전히 비활성화된 상품을 캐시에서 반환 → 정합성 깨짐

Redis 캐시 적용 후
  Pod A에서 상품 비활성화 → Redis 캐시 evict
  Pod B도 다음 조회 시 DB에서 갱신된 데이터 반환 → 정합성 유지
```

### 1. 상품 정보 캐싱 — Redis 분산 락 기반 수동 캐싱

한도조회 요청 시 금융사별 상품 목록을 조회합니다. 상품 정보는 자주 조회되지만 변경 빈도가 낮아 캐싱 효과가 큽니다.

```
캐시 키: products::{partnerCode}:{loanType}
예시:     products::KAKAO_BANK:PERSONAL_CREDIT

적용 효과
  피크 트래픽 기준 금융사 50여 개 × 요청당 1회 조회
  → 캐시 미적용 시 DB 조회 집중
  → 캐시 적용 후 첫 조회만 DB, 이후 Redis에서 처리
```

#### `@Cacheable(sync = true)`를 쓰지 않은 이유

`@Cacheable`은 캐시 스탬피드(캐시 만료 시점에 동시 요청이 한꺼번에 DB를 때리는 현상)를 막는 `sync = true` 옵션을 제공하지만, `RedisCacheManager` 기반 캐시에서는 이 옵션이 **JVM 로컬 `ReentrantLock` 하나로만** 동작합니다.

```
멀티 Pod 환경에서 sync = true
  Pod A에서 캐시 만료 후 동시 요청 유입
    → Pod A 내부에서는 락으로 직렬화되어 DB 조회 1회만 발생
  Pod B에서도 같은 시점에 요청 유입
    → Pod B는 Pod A의 락을 전혀 모름 → 별도로 DB 조회 발생
  → Pod 수만큼 DB 조회가 동시에 발생 (stampede 방지 실패)
```

`CryptoFactory`(Caffeine, JVM 로컬 캐시)에서는 `sync = true`가 그대로 유효하지만, `products`는 멀티 Pod 간 공유가 필요한 Redis 캐시라 이 옵션만으로는 stampede를 막을 수 없습니다. 콜백 동시성 제어와 동일하게 Redisson 분산 락으로 double-checked locking을 직접 구현했습니다.

```java
// ProductService
public List<ProductCache> getActiveProducts(PartnerCode partnerCode, LoanType loanType) {
    String lockKey = partnerCode.name() + ":" + loanType.name();
    Cache cache = cacheManager.getCache("products");
    if (cache == null) {
        return this.loadActiveProducts(partnerCode, loanType);
    }

    List<ProductCache> cached = cache.get(lockKey, List.class);
    if (cached != null) return cached;

    return lockExecutor.execute(LOCK_PREFIX + lockKey, 3, 5,
            () -> {
                // 더블 체크 - 락 대기 중 다른 Thread가 이미 채웠을 수도 있음
                List<ProductCache> doubleChecked = cache.get(lockKey, List.class);
                if (doubleChecked != null) return doubleChecked;

                List<ProductCache> result = this.loadActiveProducts(partnerCode, loanType);
                if (!result.isEmpty()) cache.put(lockKey, result);
                return result;
            },
            () -> this.loadActiveProducts(partnerCode, loanType)
    );
}

// 상품 변경 시 캐시 무효화 - 캐시를 채우는 방식과 무관하게 그대로 유효
@CacheEvict(value = "products",
            key = "#product.partnerCode.name() + ':' + #product.loanType.name()")
public void updateProductStatus(ProductCache product, boolean active) { ... }

// 전체 캐시 초기화 (관리자 API)
@CacheEvict(value = "products", allEntries = true)
public void evictAllProductCache() { ... }
```

락 획득에 실패한 요청은 캐시를 갱신하지 않고 DB를 직접 조회해 응답 지연 없이 Fallback합니다.

#### JPA Entity 직렬화 문제 해결

`@Cacheable`로 JPA Entity를 Redis에 직접 저장하면 두 가지 문제가 발생합니다.

```
① NotSerializableException
   → Entity는 Serializable 미구현
   → 연관관계(@ManyToOne Partner)까지 직렬화 시 민감 정보 노출 위험

② LazyInitializationException
   → 캐시 저장 시점에 Lazy 연관관계 초기화 안 됨
```

별도 `ProductCache` DTO로 변환 후 캐싱하여 해결했습니다.

```java
@Builder
@JsonDeserialize(builder = ProductCache.ProductCacheBuilder.class)
public record ProductCache(
        Long id,
        String productCode,
        String productName,
        LoanType loanType,
        PartnerCode partnerCode,
        boolean active
) {
    public static ProductCache from(Product product) {
        return ProductCache.builder()
                .id(product.getId())
                .productCode(product.getProductCode())
                .productName(product.getProductName())
                .loanType(product.getLoanType())
                .partnerCode(product.getPartner().getPartnerCode())
                .active(product.isActive())
                .build();
    }
}
```

### 2. 암호화 키 캐싱 — Caffeine 로컬 캐시

금융사별 암호화 키(`secretKeySpec`)와 `CryptoService` 구현체를 캐싱합니다.

```
Redis 대신 Caffeine을 선택한 이유
  → 암호화 키(AES SecretKeySpec, RSA 개인키)는 보안상 외부 저장소에 저장 불가
  → CryptoService 객체 자체가 직렬화 불가 (SecretKeySpec, Cipher 등)
  → 인스턴스 간 공유가 불필요 (각 서버에서 독립적으로 동일한 키 보유 가능)
  → JVM 내 메모리 접근으로 네트워크 비용 없음
```

```java
@Cacheable(
    value = "cryptoService",
    key = "#partnerCode",
    cacheManager = "caffeineCacheManager",   // ← 로컬 캐시 명시
    sync = true
)
public CryptoService getCryptoService(PartnerCode partnerCode) { ... }
```

### 3. 캐시 매니저 구성

```java
// Redis 캐시 — 상품 정보 (멀티 인스턴스 정합성)
@Primary
RedisCacheManager → "products" 캐시, TTL 6시간

// Caffeine 캐시 — 암호화 키 (보안 민감 데이터)
CaffeineCacheManager → "cryptoService" 캐시, TTL 1시간, 최대 100개
```

```
두 캐시 매니저를 분리한 이유
  단일 RedisCacheManager 사용 시
    → activateDefaultTyping(NON_FINAL) 적용
    → CryptoService(AesCryptoService)까지 JSON 직렬화 시도
    → No serializer found 오류 발생

  캐시 매니저 분리 후
    → 상품 정보: Redis JSON 직렬화
    → 암호화 키: Caffeine JVM 로컬 저장 (직렬화 불필요)
```

<a id="callback-concurrency"></a>
## 🔄 콜백 동시성 제어

여러 금융사 콜백이 동시에 수신될 때 `LoanLimitInquiry`의 콜백 수신 카운트(`successProductCount`) 갱신이 유실되지 않도록 하면서, 락 대기 때문에 DB 커넥션이 쌓이지 않게 하는 것이 목표입니다.

### 변천 과정

```
1) Redis 분산 락            → leaseTime 고정 시 GC pause 중 락 만료로 상호배제가 깨질 수 있음 (fencing token 부재)
2) DB 비관적 락(PESSIMISTIC_WRITE) → 락 보유 시간이 트랜잭션 전체 길이만큼이라, 동일 Inquiry에 다수 금융사 콜백이 몰리면 순차 대기
3) 원자적 UPDATE 단독       → 락은 UPDATE 문장이 아니라 "그 UPDATE가 속한 트랜잭션이 커밋될 때까지" 유지됨
                              → 호출부와 같은 트랜잭션이면 이후 flush / Outbox INSERT / commit까지 row lock을 붙든 채 대기 (커넥션도 함께 점유)
4) 원자적 UPDATE + REQUIRES_NEW 분리 (현재)
```

### 현재 구조

카운트 증가만 `LoanLimitCounterService`로 분리해 **별도 `REQUIRES_NEW` 트랜잭션**에서 실행합니다. UPDATE 직후 바로 커밋되어 Inquiry row lock이 그 즉시 풀립니다. 나머지 로직(`productResult.updateResult()`, Outbox enqueue)은 금융사·상품별로 서로 다른 `LoanLimitProductResult` 행을 다루므로 콜백끼리 경합하지 않습니다.

```java
// LoanLimitResultService (콜백 수신, @Transactional)
var productResult = loanLimitProductResultRepository
        .findByLoReqtNoAndProductCode(item.getLoReqtNo(), item.getProductCode())
        .orElseThrow(() -> new InvalidRequestException("존재하지 않는 식별번호&상품코드"));

// SEND_SUCCESS 상태가 아니면 처리 불가로 간주하고 skip (중복 수신 포함)
if (productResult.getStatus() != PartnerInquiryStatus.SEND_SUCCESS) {
    log.warn("[{}] 처리 불가 상태의 결과 수신. loReqtNo={}, status={}", partnerCode, item.getLoReqtNo(), productResult.getStatus());
    return;
}

// 원자적 UPDATE를 별도 트랜잭션(REQUIRES_NEW)으로 → 즉시 커밋, row lock 해제
loanLimitCounterService.incrementSuccessCount(item.getLoReqtNo(), item.getProductCode());
productResult.updateResult(item.getResultCode(), item.getAmount(), item.getInterestRate());
// 콜백 수신 이력 Outbox INSERT (PartnerCallback)
```

```java
// LoanLimitCounterService
@Transactional(propagation = Propagation.REQUIRES_NEW)
public int incrementSuccessCount(String loReqtNo, String productCode) {
    return loanLimitProductResultRepository.incrementSuccessProductCount(loReqtNo, productCode);
}
```

> `LoanLimitCounterService`는 반드시 **별도 빈으로 주입**받아 프록시를 통해 호출해야 합니다. 같은 클래스 안의 내부 호출로 합치면 AOP 프록시를 거치지 않아 `REQUIRES_NEW`가 무시되고 바깥 트랜잭션에 그대로 참여합니다.

### 콜백 응답과 중복 수신

- 처리 중 예외가 나도 예외를 상위로 전파하지 않고 **금융사 포맷의 실패 응답**을 반환합니다. 예외를 던지면 금융사가 응답을 받지 못해 재전송이 반복될 수 있어서, 재전송 여부를 응답 코드로 제어합니다.
- 상태가 `SEND_SUCCESS`가 아닌 콜백(이미 처리된 `SUCCESS` 중복 수신, 전송 실패·타임아웃 건)은 결과를 덮어쓰지 않고 skip합니다.

<br>

<a id="auth"></a>
## 🔑 인증 (JWT)

| Method | URL | 설명 |
|---|---|---|
| POST | /api/auth/login | 로그인 — Access / Refresh Token 발급 |
| POST | /api/auth/refresh | Access Token 재발급 |
| POST | /api/auth/logout | 로그아웃 |

- `JwtTokenProvider`(jjwt)가 `TokenType`(ACCESS / REFRESH)별 만료 시간으로 토큰을 발급·검증하고, `JwtAuthenticationFilter`가 요청마다 토큰을 검증합니다. 인증 실패·권한 없음은 `JwtAuthenticationEntryPoint` / `JwtAccessDeniedHandler`가 응답합니다.
- `SecurityConfig`는 form login / HTTP Basic을 비활성화한 JWT 기반 구성이며, Swagger·H2 콘솔·Actuator와 `/api/**`는 현재 `permitAll`입니다. 그 외 경로만 인증이 필요합니다.
- 회원(`Member`)은 `MemberRole`을 가지며, 토큰에는 회원 ID와 권한이 담깁니다.

<br>

<a id="kcb-batch"></a>
## 🗂 KCB 신용변동 배치

KCB에서 매일 수신하는 고정폭 신용변동 파일을 Spring Batch로 처리합니다.

```
KcbFileIngestScheduler                    [매일 03:00 · ShedLock]
├── KcbFilePoller: 감시 디렉터리(kcb.file.local-watch-dir)에서 신규 파일 탐색
├── KcbCreditFile 이력 조회 — 같은 파일명이 이미 있으면 스킵 (멱등성)
└── Spring Batch Job(kcbCreditJob) 실행
      Reader   FlatFileItemReader — EUC-KR, 헤더 스킵, 고정폭(FixedLengthTokenizer, strict=false)
      Process  KcbCreditItemProcessor
      Writer   KcbCreditItemWriter
      Step     chunk(1,000) + faultTolerant + skip(Exception) skipLimit(1,000)
               → 포맷 오류 레코드 몇 건이 Step 전체를 실패시키지 않음
```

- 스케줄러 재시작·중복 트리거 시 같은 파일이 재처리되지 않도록 **파일명 이력 기반 멱등성 가드**를 둡니다.
- Job 실행이 실패하면 `KcbCreditFile`에 실패 사유를 기록합니다.
- `spring.batch.job.enabled=false`로 앱 시작 시 자동 실행을 막고, `@Scheduled`로만 기동합니다.

<br>

<a id="monitoring"></a>
## 📊 모니터링 지표

`/actuator/prometheus`로 노출되며 모든 지표에 `application="bigin"` 태그가 붙습니다(노출 엔드포인트: `health`, `info`, `prometheus`, `metrics`).

| 지표 | 제공 클래스 | 설명 |
|---|---|---|
| Resilience4j (CB / Retry / RateLimiter / Bulkhead) | `resilience4j-micrometer` | 금융사별 상태·호출 수 |
| `partner.transmission.count` / `.duration` | `PartnerSlaMetricsConsumer` | 파트너사별 전송 성공·실패 및 응답시간 (audit 토픽 구독) |
| 금융사 HTTP 커넥션 풀 | `PartnerConnectionPoolMetrics` | leased / pending / available / max (전체·파트너별) |
| 스레드풀 | `TaskExecutorMetrics` | Executor별 사용량 |
| 상태 확인 | `RedissonHealthIndicator`, CB health indicator | Redis / Circuit Breaker 상태 |

CB 상태 전환·Retry·Rate Limiter 이벤트는 각각 `CircuitBreakerEventListener`, `RetryEventListener`, `RateLimiterEventListener`가 로그로 남깁니다.

<br>

<a id="api-spec"></a>
## 📋 API 명세

| Method | URL | 설명 |
|---|---|---|
| POST | /api/loan/request-compare-loan | 한도조회 요청 (`inquiryNo` 포함 즉시 응답) |
| GET | /api/loan/inquiry/{inquiryNo} | 한도조회 결과 폴링 (`page`, `size`) |
| GET | /api/loan/inquiry/{inquiryNo}/summary | 결과 화면용 요약 (대출 가능/불가 금융사 분류, 상품별 그룹핑) |
| POST | /api/loan/response-compare-loan-result | 한도결과 콜백 수신 (금융사 → 플랫폼, Header `X-Partner-Code`) |
| POST | /api/loan/apply | 대출신청 |
| POST | /api/notification/send | 알림 발송 등록 (즉시/예약) |
| POST | /api/auth/login, /refresh, /logout | 인증 |

<br>

주요 테스트 대상은 다음과 같습니다.

| 영역 | 테스트 클래스 | 검증 항목 |
|---|---|---|
| 한도조회 (loan) | LoanLimitServiceTest | 한도조회 요청 비즈니스 로직 (중복 요청 방어, Strategy 연동) |
| | LoanLimitInquiryPersistenceServiceTest | 최초 INSERT / 선저장 / 결과반영 / FAILED 처리 각 트랜잭션 구간 |
| | LoanLimitEventHandlerTest | AFTER_COMMIT 팬아웃 제출, executor 포화 시 보상 처리 |
| | LoanLimitSenderServiceTest | 비동기 팬아웃 및 fallback(CB_OPEN / RATE_LIMIT / BULKHEAD / THREAD_POOL_EXHAUSTED) 상태 처리 |
| | LoanLimitResultServiceTest, LoanLimitCounterServiceTest | 콜백 수신, 중복·처리불가 skip, 카운트 원자적 증가, Outbox INSERT |
| | LoanLimitStrategyFactoryTest | 대출유형별 전략 선택 |
| | ProductServiceTest, ProductServiceStampedeTest | 상품 캐싱, 캐시 스탬피드 방지(분산 락 + 더블 체크) |
| Outbox | OutboxEventServiceTest, OutboxEventWriterTest, OutboxEventTest | 즉시 발행 / 실패 시 PENDING 유지 / 이벤트 발행 |
| | OutboxEventBatchPublisherTest | 배치 재시도 (동기 확정, 결과 반영) |
| Kafka / DLQ | LoanLimitCompletedEventConsumerTest, NotificationEventConsumerTest | Kafka Consumer 검증 |
| | PoisonPillClassifierTest, DlqEventConsumerTest, DlqRetrySchedulerTest | Poison Pill 판별, DEAD/PENDING 자동 분류, 재시도(성공/실패/한도 초과) |
| | JitteredExponentialBackOffTest | 지수 백오프 + jitter |
| 알림 (notification) | AbstractNotificationSenderTest, Sms/Email/KakaoNotificationSenderTest, NotificationSenderFactoryTest | Template Method 골격(CB/Retry/Fallback), 채널별 발송, Factory |
| | NotificationServiceTest, NotificationSenderServiceTest | 알림 등록·발송 서비스 |
| 감사 / 지표 | AuditLogConsumerTest, PartnerSlaMetricsConsumerTest | 감사 로그 배치 적재, 파트너 SLA 지표 |
| 배치 (kcbcredit) | KcbFileIngestSchedulerTest, KcbCreditItemWriterTest, KcbCreditBatchLoadTest | 파일 수신 멱등성, Writer, 대용량 파일 엔드투엔드 처리(포맷 오류 skip 포함) |
| 인증 | AuthServiceTest, AuthenticationIntegrationTest, JwtTokenProviderTest | 로그인/재발급, 인증 통합, 토큰 발급·검증 |
| 인프라 (global) | RestApiClientTest | CB 상태 전환 (CLOSED→OPEN→HALF_OPEN→CLOSED) |
| | RateLimiterConfigTest, PartnerConnectionPoolConfigTest | Bucket4j 설정, 커넥션 풀(라우트 합산·상한) |
| | RedisLockExecutorTest, RedisLockExecutorConcurrencyTest, RedissonHealthIndicatorTest | Redis Lock 메커니즘·동시성, 헬스 체크 |
| | AesCryptoServiceTest, RsaCryptoServiceTest, CryptoFactoryTest, CryptoFactoryCacheIntegrationTest | 암복호화, 파트너별 CryptoService 생성, Caffeine 캐시 |
| | LoReqtNoGeneratorTest, LoReqtNoGeneratorCurrencyTest | 채번 및 동시성 |

<br>

<a id="design-decisions"></a>
## 📝 주요 설계 결정

| 결정 | 이유                                                                                                                                     |
|---|----------------------------------------------------------------------------------------------------------------------------------------|
| 상품별 loReqtNo 선저장 | 콜백 loReqtNo 유효성 검증, 타임아웃 처리, 대출신청 연결                                                                                                   |
| LoanLimitResult 분리 | 상품 수가 많아도 금융사당 1건만 INSERT/UPDATE                                                                                                       |
| 통신방식별 ApiClient 분리 | REST/전용선 금융사 혼재 대응, OCP 준수                                                                                                             |
| 금융사별 Circuit Breaker | 특정 금융사 장애 시 다른 금융사 영향 없이 격리                                                                                                            |
| Rate Limiter 도입 | 금융사 API Rate Limit 정책 준수, CB 불필요 OPEN 방지, RequestNotPermitted를 CB 실패에서 제외                                                              |
| Bulkhead 도입 | Rate Limiter는 호출 소요 시간을 모름 → 특정 금융사가 느려지면 응답 대기 요청이 쌓여 partnerApiExecutor 잠식 가능 → 동시 진행 건수 자체를 제한해 스레드 풀 잠식 방지 |
| CB/RateLimit/Bulkhead Fallback을 Sender에서 단일 처리 | `RestApiClient`가 금융사별 Registry 인스턴스로 직접 데코레이션(Rate Limiter → Bulkhead → CB → Retry)하므로 어노테이션 방식(금융사별 독립 인스턴스 지정 불가)을 쓰지 않음. Adaptor는 정책성 예외(`CallNotPermittedException` / `RequestNotPermitted` / `BulkheadFullException`)를 그대로 던지고 `LoanLimitSenderService.exceptionally()`에서 한 번만 실패 응답으로 변환해 중복 처리 방지 |
| Partial Failure 패턴 | 특정 금융사 CB OPEN 시 Fallback 응답 반환, 나머지 금융사 정상 진행                                                                                         |
| 타임아웃 계층 분리 | connect/readTimeout(CB 실패 기록) + orTimeout(스레드 강제 해제) 역할 분리. orTimeout은 고정값이 아니라 금융사별 connect/read와 Retry 설정으로 `PartnerOrTimeoutConfig`가 동적 산정 |
| 암호화 키 Caffeine 캐싱 | Caffeine 로컬 캐시로 JVM 내 보관, 직렬화 없이 객체 그대로 캐싱                                                                                             |
| ExternalDataContext | 외부 조회 결과 파라미터 고정 (Nice DNR, KB시세 등 확장 시 파라미터 불변)                                                                                       |
| Kafka 알림 연동 | 다중 인스턴스 환경에서 이벤트 소실 방지, loan-notification 도메인 물리적 분리                                                                                   |
| 상품 정보 Redis 캐싱 | 매 한도조회 요청마다 금융사별 상품 DB 조회 반복 → `ProductCache` DTO 변환 후 Redis 캐싱, Entity 직렬화 문제 회피                                                      |
| 상품 캐싱에 Redisson 분산 락 사용 | `@Cacheable(sync=true)`는 `RedisCacheManager`에서 JVM 로컬 락으로만 동작해 멀티 인스턴스**** stampede를 못 막음 → double-checked locking + Redisson 분산 락으로 직접 구현 |
| 콜백 동시성 제어 (Redis 분산락 → 비관적 락 → 원자적 UPDATE + REQUIRES_NEW) | Redis 분산락은 `leaseTime` 고정 시 GC-pause 중 락 만료로 상호배제가 깨질 수 있고(fencing token 부재), 비관적 락은 락이 트랜잭션 커밋까지 유지돼 다수 금융사 콜백이 몰리면 순차 대기하며 커넥션을 붙듦 → 카운트 증가만 원자적 UPDATE로 `REQUIRES_NEW` 트랜잭션에 분리해 즉시 커밋·락 해제 |
| Kafka DLQ 도입 | Consumer 처리 실패 메시지 유실 방지, Poison Pill과 일시 장애 자동 분류, 지수 백오프 자동 재시도로 운영팀 개입 최소화                                                          |
| PoisonPillClassifier | 재시도해도 의미 없는 예외(파싱/데이터 오류)를 즉시 DEAD 처리, 파티션 멈춤(lag 무한 증가) 방지                                                                            |
| 지수 백오프 DB 영속화 | spring-retry ExponentialBackOff는 메모리에만 존재 → 서버 재기동 시 재시도 일정 소멸. DlqEvent.nextRetryAt을 DB에 저장하여 재기동 후에도 재시도 일정 유지                       |
| notification 채널 Strategy + Template Method | 채널 추가 시 Factory/기존 코드 변경 없이 구현체만 추가(OCP), CB/Retry/Fallback 골격을 모든 채널이 공유                                                              |
| AbstractNotificationSender에서 RestClient 생성자 제거 | FCM처럼 REST가 아닌 채널도 같은 Template Method 골격을 재사용할 수 있도록, RestClient를 `post()` 파라미터로 전달받는 방식으로 변경                                          |
| ExternalApiServerException / ExternalApiClientException 분리 | resilience4j 설정이 HTTP 클라이언트 라이브러리·전송 방식(RestClient/FCM SDK)에 종속되지 않도록 예외 타입 통일                                                         |
| OutboxEventWriter 통합 | loan/notification 양쪽에 동일하게 중복돼 있던 "Outbox INSERT + 이벤트 발행" 로직 통합                                                                       |
| RecordInterceptor 기반 MDC 전파 | 각 Kafka Consumer에 중복돼 있던 MDC put/clear 보일러플레이트를 전역 제거, 신규 Consumer 추가 시에도 자동 적용                                                        |
| 한도조회 트랜잭션 3단계 분리 (선저장 / 팬아웃 / 결과반영) | 파트너 응답을 기다리는 동안 트랜잭션이 DB 커넥션을 붙잡아 응답이 느려질수록 필요한 커넥션이 늘어나는 피드백 루프 → 팬아웃 대기 구간을 무트랜잭션으로, DB 접근은 `LoanLimitInquiryPersistenceService`의 짧은 트랜잭션 두 구간으로 분리 |
| 트랜잭션 구간을 별도 빈으로 분리 | 같은 클래스 내부 호출은 AOP 프록시를 우회해 `@Transactional`이 무효화됨 → `PersistenceService` / `CounterService` / `OutboxEventService`를 협력자 빈으로 분리 |
| `compensationExecutor`(전용 소형 풀) | executor 포화 시 유입 요청이 모두 FAILED 보상(REQUIRES_NEW, 새 커넥션)을 동시에 타면 포화가 커넥션 풀 고갈로 번짐 → 보상 전용 풀(core 2 / max 5)로 동시 커넥션 상한을 하드 캡 |
| `@Async` 대신 `executor.execute()`로 팬아웃 제출 | `@Async`는 `TaskRejectedException`이 별도 핸들러로만 전달돼 inquiryId를 아는 자리에서 보상 처리가 어려움 → 제출을 동기로 수행해 예외를 바로 잡고 FAILED 전환 예약 |
| Outbox 즉시발행 / 배치 재시도 스레드풀 분리 | 즉시발행이 파트너 API 풀과 결합되면 Kafka 장애가 금융사 호출 경로로 전파됨 → `outboxPublishExecutor`(포화 시 스킵, PENDING은 배치가 처리)와 `outboxRetryExecutor`로 분리 |
| 배치 재시도는 동기 발행으로 결과 확정 | fire-and-forget이면 장애가 스케줄 주기보다 길 때 같은 PENDING 건을 중복 발행할 수 있음 → `send().get(timeout)`으로 확정 후 별도 빈(REQUIRES_NEW)에서 상태 반영 |
| Kafka `max.block.ms=3000` | 기본 60초는 브로커 장애 시 발행 스레드(와 물고 있는 커넥션)를 그만큼 점유 → `delivery.timeout.ms`와 별개로 메타데이터 대기 상한을 명시 |
| 한도조회 요청에 Redis 분산 락 | 동일 `userId + loanType`의 동시 요청이 진행 중 조회 확인을 함께 통과해 중복 생성되는 것을 방어 (대기 0초, 즉시 거절) |
| HTTP 커넥션 풀 `maxPerRoute` 합산 | 여러 파트너가 같은 호스트(라우트)를 공유하면 순회 중 마지막 값이 덮어써져 실제 허용 커넥션이 급감 → 라우트별 합산 후 `maxTotal`로 캡 |
| 스케줄러에 ShedLock | 다중 인스턴스에서 Outbox 재시도·DLQ 재시도·KCB 배치가 동시에 중복 실행되지 않도록 분산 락으로 보호 |
| KCB 배치 파일명 이력 멱등성 + skip 기반 결함 허용 | 스케줄러 재시작·중복 트리거 시 같은 파일 재처리를 막고, 일부 레코드의 포맷 오류가 Step 전체 실패로 번지지 않게 함 |

<br>
