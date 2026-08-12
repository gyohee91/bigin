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
- [🗄 핵심 도메인 모델](#domain-model)
- [🔒 장애 격리 - Resilience4j](#resilience4j)
- [🚗 오토담보/주택담보 대출 연동 (Nice DNR, KB부동산 시세)](#external-integration)
- [📨 알림 서비스 - 채널별 비동기 발송](#notification-service)
- [📦 Outbox 패턴 - 트랜잭션 보장](#outbox-pattern)
- [💀 Kafka DLQ (Dead Letter Queue)](#kafka-dlq)
- [🗃 캐싱 전략](#caching-strategy)
- [🔄 콜백 동시성 제어](#callback-concurrency)
- [📋 API 명세](#api-spec)
- [📝 주요 설계 결정](#design-decisions)

<br>

<a id="project-overview"></a>
## 📌 프로젝트 개요

### 실무 배경

현재 재직 중인 회사에서 **50개 이상의 금융사와 연동하는 대출 비교 플랫폼** 을 개발·운영하고 있습니다.

실무에서는 아래와 같은 제약이 있었습니다.

- Java 8 + Spring Boot 2.7 기반의 레거시 구조 유지
- Layered Architecture (Controller → Service → Repository)
- 기존 시스템과의 호환성을 고려한 점진적 개선만 가능

또한 아래와 같은 기술적 한계를 실무에서 직접 경험했습니다.

| 문제 영역 | 실무 방식                                            | 한계                                |
|---|--------------------------------------------------|-----------------------------------|
| 콜백 동시성 제어 | JPA 비관적 락 (`PESSIMISTIC_WRITE`)                  | 멀티 인스턴스 환경에서 DB 커넥션 점유 누적 위험      |
| 중복 요청 방지 | DB 조건절 조회 (`existsByUserIdAndLoanTypeAndStatus`) | 동시 요청 시 두 Pod 모두 통과 가능            |
| 업무 식별번호 채번 | Oracle Sequence 기반                               | DB 부하가 높은 상황에서 채번 요청도 DB에 집중 |
| 상품 정보 조회 | DB 조회 (`findActiveByPartnerCodeAndLoanType`) | 매 한도조회 요청마다 금융사 수만큼 반복 DB 조회 발생 |

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
| API 통신       | RestTemplate         | RestClient                      |

### 개인 프로젝트 상세 스택

| 구분        | 기술                                      |
|-----------|-----------------------------------------|
| Language  | Java 17                                 |
| Framework | Spring Boot 3.5                         |
| ORM       | Spring Data JPA / Hibernate             |
| DB        | H2 DB                                   |
| 비동기       | Spring @Async / CompletableFuture       |
| 장애격리      | Resilience4j Circuit Breaker / Retry / Rate Limiter |
| 암복호화      | AES-256-CBC, AES-256-ECB, RSA-OAEP      |
| API 문서    | SpringDoc OpenAPI (Swagger)             |
| Build     | Gradle                                  |
| 메시지큐 | Apache Kafka (Outbox Pattern, DLQ, 지수 백오프 재시도) |
| 캐싱 | Redis Cache (Redisson 분산 락 기반), Caffeine (`@Cacheable`, 로컬 캐시) |
| 분산처리 | Redis (Redisson 분산락, INCR 채번) |


<br>

<a id="package-structure"></a>
## 📁 패키지 구조

```
com.ghyinc.finance
├── domain
│   ├── loan
│   │   ├── controller         # API 진입점
│   │   ├── service            # 비즈니스 로직
│   │   │   ├── LoanLimitService.java
│   │   │   ├── ProductService.java            # 금융사별 상품 조회 캐싱 처리
│   │   │   ├── LoanLimitEventHandler.java     # 한도조회 처리 이벤트 리스너
│   │   │   ├── LoanLimitSenderService.java    # @Async 비동기 전송
│   │   │   └── LoanLimitResultService.java    # 콜백 수신 처리
│   │   ├── adaptor            # 금융사별 API 변환
│   │   │   ├── common         # 표준 Layout 금융사 공통
│   │   │   ├── impl           # 비표준 금융사 개별 구현
│   │   │   └── callback       # 콜백 수신 Adaptor
│   │   ├── strategy           # 대출유형별 전략 패턴
│   │   ├── entity             # JPA Entity
│   │   ├── repository         # Spring Data JPA
│   │   ├── dto                # 요청/응답 DTO
│   │   └── enums              # 상태/타입 Enum
│   ├── notification
│   │   ├── service            # 알림 비즈니스 로직
│   │   │   ├── NotificationService.java
│   │   │   └── NotificationSenderService.java
│   │   ├── sender             # 채널별 발송 (Strategy + Template Method)
│   │   │   ├── AbstractNotificationSender.java     # Template Method (CB/Retry/Fallback 골격)
│   │   │   ├── NotificationSender.java             # 전략 인터페이스
│   │   │   └── NotificationSenderFactory.java      # ChannelType → SenderMap 자동 수집
│   │   ├── event
│   │   │   ├── NotificationEventConsumer.java       # Kafka Consumer (발송 처리)
│   │   │   └── LoanLimitCompletedEventConsumer.java # loan-limit-completed 토픽 수신
│   │   ├── entity
│   │   ├── repository
│   │   └── enums
│   └── external               # 외부 기관 API
│       ├── nice               # Nice DNR (자동차등록원부) 연동
│       └── coocon             # KB 부동산 시세 연동
├── global
│   ├── client                 # 통신 방식별 ApiClient (REST, 전용선)
│   ├── common                 # 공통 유틸 (채번, BaseEntity 등)
│   ├── config                 # Spring 설정
│   ├── crypto                 # 암복호화 (AES, RSA)
│   ├── event                  # Kafka Event Publisher
│   ├── exception              # 전역 예외 처리
│   ├── outbox                 # Outbox 패턴 (트랜잭션 보장)
│   │   ├── entity
│   │   │   ├── OutboxEvent.java
│   │   │   └── OutboxStatus.java
│   │   ├── event
│   │   │   └── OutboxCreatedEvent.java
│   │   ├── repository
│   │   │   └── OutboxEventRepository.java
│   │   ├── service
│   │   │   ├── OutboxEventWriter.java       # Outbox Insert + 이벤트 발행
│   │   │   └── OutboxEventService.java      # @TransactionalEventListener
│   │   └── scheduler
│   │       └── OutboxEventBatchPublisher.java # @Scheduled 재시도
│   ├── kafka
│   │   └── dlq                    # Kafka DLQ 처리
│   │       ├── entity
│   │       │   ├── DlqEvent.java
│   │       │   └── DlqStatus.java
│   │       ├── repository
│   │       │   └── DlqEventRepository.java
│   │       ├── DlqEventConsumer.java      # DLT 토픽 수신 + Poison Pill 자동 분류
│   │       ├── DlqRetryScheduler.java     # 지수 백오프 자동 재시도
│   │       └── PoisonPillClassifier.java  # Poison Pill 판별
```

<br>

<a id="architecture"></a>
## 🏗 핵심 아키텍처

### 1. 한도조회 비동기 처리 흐름

```
FE → POST /api/loan/limit/inquiry
         │
         ▼
  LoanLimitService                      [HTTP 요청 스레드]
  ├── Strategy 선택 (대출유형별)
  ├── 외부데이터 조회 (Nice DNR 등)
  ├── LoanLimitInquiry INSERT (PENDING)
  ├── ApplicationEventPublisher.publishEvent(LoanLimitInquiryCreatedEvent)
  └── 202 Accepted 즉시 응답
         │
         ▼ @TransactionalEventListener(AFTER_COMMIT)
  LoanLimitEventHandler.handleInquiryCreated()
         │  트랜잭션 커밋 후 실행 보장
         │  (커밋 전 실행 시 콜백이 먼저 도착해도 Inquiry 조회 불가 → Race Condition)
         │
         ▼ @Async ("loanLimitExecutor") [별도 스레드 — HTTP 스레드 즉시 해제]
  LoanLimitSenderService
  ├── LoanLimitResult INSERT       (금융사당 1건)
  ├── LoanLimitProductResult INSERT (상품당 1건, PENDING 선저장)
  ├── 금융사별 API 병렬 전송 (CompletableFuture)
  └── 완료 시 알림 이벤트 발행
      └── OutboxEventWriter.enqueue() — Outbox INSERT + OutboxCreatedEvent 발행 (같은 트랜잭션, 원자적)
         │
         ▼ @TransactionalEventListener(AFTER_COMMIT)
  OutboxEventService.publishAfterCommit()
  ├── Kafka 발행 성공 → OutboxEvent PUBLISHED UPDATE
  └── Kafka 발행 실패 → OutboxEvent PENDING 유지 (배치 재시도)
         │
         ▼ Kafka (loan-limit-completed)
  LoanLimitCompletedEventConsumer (notification 도메인)
  └── NotificationService → notification.send 토픽 발행
         │
         ▼ Kafka (notification.send)
  NotificationEventConsumer
  ├── NotificationSenderFactory.getSender(channelType)
  ├── AbstractNotificationSender.send() — CircuitBreaker + Retry + Fallback
  └── 채널별 실제 발송 (SMS/Email/카카오톡: RestClient, 앱 푸시: FCM)
─────────────────────────────────────────────────────
  Callback (금융사 → 플랫폼)
  금융사 → POST /api/loan/limit/callback
  LoanLimitResultService
  ├── loReqtNo + productCode로 선저장 데이터 조회 및 UPDATE
  └── 비관적 락으로 count 동시성 제어
```

### 2. Kafka 토픽 구성

```
loan-limit-completed   loan → notification 도메인 간 이벤트 전달
                        한도조회 완료 시 발행 (inquiryNo가 partition key)
                        OutboxEventService가 발행 (Outbox Pattern)
 
notification.send      notification 도메인 내부 비동기 발송 처리
                        Notification INSERT 후 실제 발송 분리
                        
loan-limit-completed.DLT    loan-limit-completed 처리 실패 메시지 보관
notification.send.DLT       notification.send 처리 실패 메시지 보관
```

### 2. 디자인 패턴

#### Strategy + Factory 패턴
대출유형(신용/담보/사업자/오토담보)별로 지원 금융사, 유효성 검증, 요청 변환 로직을 캡슐화합니다.

```java
// 대출유형별 전략 자동 선택
LoanLimitStrategy strategy = strategyFactory.getStrategy(request.getLoanType());
strategy.validate(request);
ExternalDataContext context = strategy.fetchExternalData(request);
LoanLimitAdaptorRequest adaptorRequest = strategy.toAdaptorRequest(request, context);
```

#### Adaptor 패턴
금융사별 자체 API Layout을 내부 표준 DTO로 변환합니다.

```
표준 Layout 금융사 → CommonLoanLimitAdaptor (yml 설정만으로 금융사 추가)
자체 Layout 금융사 → KakaobankLoanLimitAdaptor / TossBankLoanLimitAdaptor
```

#### Strategy + Template Method 패턴 (notification)
채널(SMS/Email/카카오톡/앱 푸시)별 발송 로직을 캡슐화합니다. 자세한 구조는 아래 `📨 알림 서비스` 섹션에서 다룹니다.

#### 통신 방식별 ApiClient 분리

```
REST   → RestApiClient
전용선 → LeaseLineApiClient (고정길이 전문, EUC-KR 인코딩)
```

<br>

<a id="domain-model"></a>
## 🗄 핵심 도메인 모델

```
LoanLimitInquiry (한도조회 요청 1건)
  ├── inquiryNo           업무 식별번호 (채번)
  ├── status              PENDING → IN_PROGRESS → SUCCESS/PARTIAL_SUCCESS/FAILED
  ├── totalProductCount   전체 상품 수 (콜백 완료 판단)
  └── callbackReceivedCount / approvedProductCount

LoanLimitResult (금융사당 1건)
  ├── partnerCode
  └── status              PENDING → SEND_SUCCESS / SEND_FAILED

LoanLimitProductResult (상품당 1건)
  ├── loReqtNo            상품별 유니크 채번 (콜백 연결 Key)
  ├── productCode
  ├── status              PENDING → SUCCESS / TIMEOUT
  ├── resultCode          SUCCESS / LIMIT_DENIED / CREDIT_SCORE_LOW ...
  └── limitAmount / minRate / maxRate

LoanApplication (대출신청 1건)
  └── loReqtNo → LoanLimitProductResult 연결
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
inquiry.incrementSuccessCount();  // count 증가 + 상태 자동 결정
```

<br>

<a id="resilience4j"></a>
## 🔒 장애 격리 - Resilience4j

금융사별 독립적인 Circuit Breaker 인스턴스로 특정 금융사 장애 시 격리합니다.

### Circuit Breaker 설정

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10          # 최근 10건 기준
        minimum-number-of-calls: 5       # 최소 5건 이후 통계
        failure-rate-threshold: 50       # 실패율 50% 이상 시 OPEN
        slow-call-duration-threshold: 5s # 5초 이상 응답은 느린 호출로 기록
        slow-call-rate-threshold: 50     # 느린 호출 50% 이상 시 OPEN
        wait-duration-in-open-state: 60s
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true
        # 기록할 예외
        record-exceptions:
          - org.springframework.web.client.ResourceAccessException
          - org.springframework.web.client.HttpServerErrorException
          - java.net.ConnectException
          - java.net.SocketTimeoutException

        # 무시할 예외 (Circuit Breaker에 영향 안 줌)
        ignore-exceptions:
          - org.springframework.web.client.HttpClientErrorException.BadRequest
          - org.springframework.web.client.HttpClientErrorException.Unauthorized
      
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
    instances:
      KAKAO_BANK:
        base-config: default
      KB_BANK:
        base-config: default
        slow-call-duration-threshold: 10s  # 전용선 응답 지연 고려
```

### Circuit Breaker 상태 전환

```
CLOSED  → 정상 (모든 요청 통과)
OPEN    → 장애 (즉시 CallNotPermittedException, 해당 금융사만 격리)
HALF_OPEN → 복구 시도 (제한적 요청으로 복구 여부 확인)
```

### Fallback - Partial Failure 패턴

Circuit Breaker OPEN 시 `CallNotPermittedException`을 Adaptor에서 직접 캐치하여 즉시 실패 응답을 반환합니다. 특정 금융사 장애가 전체 한도조회를 중단시키지 않고 나머지 금융사는 정상 진행합니다.

```java
// LoanLimitAdaptor - CB OPEN 시 Fallback 처리
@Override
public LoanLimitAdaptorResponse inquireLimit(PartnerCode partnerCode,
                                              LoanLimitAdaptorRequest request) {
    try {
        // Circuit Breaker + Retry 적용된 API 호출
        CommonLimitResponse result = apiClient.post("...");
        return LoanLimitAdaptorResponse.success(partnerCode, resTimeMs);
 
    } catch (CallNotPermittedException e) {
        // Fallback: CB OPEN 시 즉시 실패 응답 반환
        // → 실제 API 호출 없이 해당 금융사 격리
        // → 나머지 금융사는 정상 진행 (Partial Success)
        log.warn("[{}] Circuit Breaker OPEN → Fallback 실행", partnerCode);
        return LoanLimitAdaptorResponse.fail(partnerCode, "CB_OPEN", resTimeMs);
 
    } catch (Exception e) {
        log.error("[{}] 한도조회 오류", partnerCode, e);
        return LoanLimitAdaptorResponse.fail(partnerCode, e.getMessage(), resTimeMs);
    }
}
```

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
connectTimeout (3초)   → 서버 연결 실패 → ResourceAccessException → CB 실패 기록
readTimeout    (7초)   → 응답 미수신   → SocketTimeoutException  → CB 실패 기록
orTimeout      (8초)   → CompletableFuture 강제 종료 (최후 안전망)
 
connectTimeout < readTimeout = slow-call-duration-threshold < orTimeout
     3초       <  7초        =              7초             <    8초
```

### Retry 설정

```yaml
resilience4j:
  retry:
    configs:
      default:
        max-attempts: 3          # 최초 1회 + 재시도 2회
        wait-duration: 1s        # 재시도 간격
        retry-exceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
          - org.springframework.web.client.HttpServerErrorException
          - org.springframework.web.client.ResourceAccessException
        ignore-exceptions:
          - io.github.resilience4j.circuitbreaker.CallNotPermittedException
```

### Rate Limiter 설정

금융사 API 호출량을 제어하여 과도한 요청으로 인한 금융사 측 차단을 방지합니다.

```yaml
resilience4j:
  ratelimiter:
    configs:
      default:
        limit-for-period: 10          # 갱신 주기당 최대 허용 요청 수
        limit-refresh-period: 1s      # 갱신 주기 (1초)
        timeout-duration: 0           # 대기 없이 즉시 실패 (0 = 허용량 초과 시 즉시 예외)
    instances:
      KAKAO_BANK:
        base-config: default
      TOSS_BANK:
        base-config: default
      LINE_BANK:
        base-config: default
```

```
Retry → Circuit Breaker 순으로 실행
  → Rate Limiter 초과 시 RequestNotPermitted 예외 발생
  → maxAttempts(3) 모두 실패 후 CB 실패로 기록
  → CB OPEN 시 Retry 없이 즉시 Fallback 실행 (ignoreExceptions)
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
OutboxEventService.publishAfterCommit()
  ├── Kafka 즉시 발행 시도
  ├── 성공 → OutboxEvent PUBLISHED UPDATE
  └── 실패 → OutboxEvent PENDING 유지
 
        ↓ 1분마다 (보조 안전망)
 
@Scheduled OutboxEventBatchPublisher
  └── 5분 이상 PENDING 건 재시도 → PUBLISHED or FAILED
```

### OutboxEventWriter — 중복 로직 통합

`LoanLimitSenderService`(loan)와 `NotificationService`(notification) 양쪽에 "OutboxEvent 빌드 → save → `OutboxCreatedEvent` 발행"이 동일하게 중복돼 있던 것을 `OutboxEventWriter` 하나로 통합했습니다. 호출자의 기존 트랜잭션에 그대로 참여하며 별도 트랜잭션을 열지 않습니다.

### OutboxEvent 토픽 분기

```java
String topic = switch (outboxEvent.getAggregateType()) {
    case "LoanLimitInquiry" -> "loan-limit-completed";
    case "Notification"     -> "notification.send";
    default -> throw new InvalidRequestException("...");
};
```

### 적용 범위

```
loan 도메인      LoanLimitCallbackService → 한도조회 완료 이벤트
notification     NotificationService     → 알림 발송 이벤트
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

### 처리 흐름

```
Consumer 예외 발생
      │
      ▼
DefaultErrorHandler (KafkaConfig)
      ├── JsonProcessingException    → 즉시 *.DLT 이동 (재시도 없음)
      ├── IllegalArgumentException   → 즉시 *.DLT 이동 (재시도 없음)
      └── 그 외 예외                 → 1초 간격 3회 재시도 → *.DLT 이동
      │
      ▼
DlqEventConsumer (DLT 토픽 수신)
      │
      ├── PoisonPillClassifier 판별
      │       ├── Poison Pill (파싱/데이터 오류)
      │       │     → DlqEvent INSERT (DEAD)
      │       │
      │       └── 일시 장애 (DB/외부 API 오류)
      │             → DlqEvent INSERT (PENDING)
      │             → 지수 백오프 자동 재시도 예약
      │
      ▼
DlqRetryScheduler (30초마다 실행, ShedLock 적용)
      ├── nextRetryAt 도래한 PENDING/RETRYING 건 조회 (최대 50건)
      ├── 원본 토픽 재발행
      │       ├── 성공 → DlqEvent RESOLVED
      │       └── 실패 → retryCount++ + nextRetryAt 갱신
      └── 5회 초과 → DlqEvent DEAD
```

### Poison Pill 판별 기준

```
재시도 없이 즉시 DEAD 처리
  → JsonProcessingException  (페이로드 자체가 깨짐)
  → IllegalArgumentException (데이터 없음 — Notification, Inquiry 조회 실패)
  → ClassCastException       (타입 불일치)
  → 위 예외를 감싼 중첩 예외

재시도 후 복구 가능
  → ConnectException   (DB/외부 API 일시 장애)
  → TimeoutException   (일시적 지연)
  → RuntimeException   (일시적 오류 가능성)
```

### 지수 백오프 재시도 일정

```
retryCount=1 → nextRetryAt = now + 2분
retryCount=2 → nextRetryAt = now + 4분
retryCount=3 → nextRetryAt = now + 8분
retryCount=4 → nextRetryAt = now + 16분
retryCount=5 → nextRetryAt = now + 32분
retryCount>5 → DEAD 처리
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
  피크 트래픽 기준 금융사 6개 × 요청당 1회 조회
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
    String key = partnerCode.name() + ':' + loanType.name();
    Cache cache = cacheManager.getCache("products");
    if (cache == null) {
        return loadActiveProducts(partnerCode, loanType);
    }

    List<ProductCache> cached = cache.get(key, List.class);
    if (cached != null) {
        return cached;
    }

    RLock lock = redissonClient.getLock("lock:products:" + key);
    try {
        if (!lock.tryLock(3, 5, TimeUnit.SECONDS)) {
            // 락 획득 실패 시 캐시 갱신 없이 DB 직접 조회로 Fallback
            return loadActiveProducts(partnerCode, loanType);
        }

        // 더블 체크 - 락 대기 중 다른 스레드가 이미 채웠을 수도 있음
        cached = cache.get(key, List.class);
        if (cached != null) {
            return cached;
        }

        List<ProductCache> result = loadActiveProducts(partnerCode, loanType);
        if (!result.isEmpty()) {
            cache.put(key, result);
        }
        return result;
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return loadActiveProducts(partnerCode, loanType);
    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
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

여러 금융사 콜백이 동시에 수신될 때 `LoanLimitInquiry` count 업데이트의 Lost Update를 방지합니다.

초기에는 JPA 비관적 락(`PESSIMISTIC_WRITE`)으로 구현했으나, 멀티 Pod 환경에서 락 대기 중 DB 커넥션이 점유된 채 쌓이는 문제가 있어 Redis 분산락(Redisson)으로 전환했습니다.

```java
// LoanLimitResultService - Redis 분산락으로 동시 콜백 순차 처리
private void updateWithDistributedLock(
        PartnerCode partnerCode,
        LoanLimitResultRequest.LoanApplyResult item,
        LoanLimitProductResult productResult
) {
    String lockKey = "loan:inquiry:lock:" + item.getLoReqtNo();
    RLock lock = redissonClient.getLock(lockKey);

    try {
        if (!lock.tryLock(3, 5, TimeUnit.SECONDS)) {
            log.warn("[{}] 분산 락 획득 실패. loReqtNo={}", partnerCode, item.getLoReqtNo());
            return;
        }

        var loanLimitInquiry = loanLimitProductResultRepository
                .findInquiryByLoReqtNoAndProductCode(item.getLoReqtNo(), item.getProductCode())
                .orElseThrow(() -> new InvalidRequestException("존재하지 않는 한도조회 이력"));

        loanLimitInquiry.incrementSuccessCount();
        productResult.updateResult(item.getResultCode(), item.getAmount(), item.getInterestRate());

    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

<br>

<a id="api-spec"></a>
## 📋 API 명세

| Method | URL | 설명 |
|---|---|---|
| POST | /api/loan/limit/inquiry | 한도조회 요청 |
| GET | /api/loan/limit/inquiry/{inquiryNo} | 한도조회 결과 폴링 |
| POST | /api/loan/limit/callback | 한도결과 콜백 수신 (금융사 → 플랫폼) |
| POST | /api/loan/apply | 대출신청 |
| GET | /api/loan/apply/{applicationNo} | 대출신청 결과 조회 |
| POST | /api/notification/send | 알림 발송 등록 (즉시/예약) |

<br>

주요 테스트 대상은 다음과 같습니다.

| 테스트 클래스                           | 검증 항목                                   |
|-----------------------------------|-----------------------------------------|
| LoanLimitServiceTest              | 한도조회 요청 비즈니스 로직                         |
| LoanLimitSenderServiceTest        | 비동기 전송 및 상태 처리                          |
| LoanLimitResultServiceTest        | 콜백 수신 및 Outbox INSERT 검증                |
| LoanLimitStrategyFactoryTest      | 대출유형별 전략 검증                             |
| OutboxEventServiceTest            | Outbox 즉시 발행 / 실패 시 PENDING 유지          |
| OutboxEventWriterTest             | Outbox Service Layer로 Event 발행          |
| AesCryptoServiceTest              | AES 암복호화                                |
| RsaCryptoServiceTest              | RSA 암복호화                                |
| RestApiClientTest                 | CB 상태 전환 (CLOSED→OPEN→HALF_OPEN→CLOSED) |
| LoReqtNoGeneratorTest             | 채번 검증                                   |
| CryptoFactoryTest                 | 파트너별 CryptoService 생성                   |
| CryptoFactoryCacheIntegrationTest | Caffeine Cache hit 동시성 테스트              |
| PoisonPillClassifierTest          | Poison Pill 판별 (클래스명/클래스 계층/중첩 예외)      |
| DlqEventConsumerTest              | DEAD/PENDING 자동 분류, Slack 알림 검증         |
| DlqRetrySchedulerTest             | 재시도 성공/실패/한도 초과, 지수 백오프 검증              |

<br>

<a id="design-decisions"></a>
## 📝 주요 설계 결정

| 결정 | 이유                                                                      |
|---|-------------------------------------------------------------------------|
| 상품별 loReqtNo 선저장 | 콜백 loReqtNo 유효성 검증, 타임아웃 처리, 대출신청 연결                                    |
| LoanLimitResult 분리 | 상품 수가 많아도 금융사당 1건만 INSERT/UPDATE                                        |
| 통신방식별 ApiClient 분리 | REST/전용선 금융사 혼재 대응, OCP 준수                                              |
| 금융사별 Circuit Breaker | 특정 금융사 장애 시 다른 금융사 영향 없이 격리                                             |
| Rate Limiter 도입 | 금융사 API Rate Limit 정책 준수, CB 불필요 OPEN 방지, RequestNotPermitted를 CB 실패에서 제외 |
| Adaptor에서 CB Fallback 처리 | @CircuitBreaker 어노테이션 방식은 금융사별 독립 인스턴스 지정 불가, 수동 catch로 명시적 Fallback 처리 |
| Partial Failure 패턴 | 특정 금융사 CB OPEN 시 Fallback 응답 반환, 나머지 금융사 정상 진행                          |
| 타임아웃 계층 분리 | readTimeout(CB 실패 기록) + orTimeout(스레드 강제 해제) 역할 분리                      |
| 암호화 키 Caffeine 캐싱 | Caffeine 로컬 캐시로 JVM 내 보관, 직렬화 없이 객체 그대로 캐싱     |
| ExternalDataContext | 외부 조회 결과 파라미터 고정 (Nice DNR, KB시세 등 확장 시 파라미터 불변)                        |
| Kafka 알림 연동 | 다중 인스턴스 환경에서 이벤트 소실 방지, loan-notification 도메인 물리적 분리                     |
| 상품 정보 Redis 캐싱 | 매 한도조회 요청마다 금융사별 상품 DB 조회 반복 → `ProductCache` DTO 변환 후 Redis 캐싱, Entity 직렬화 문제 회피 |
| 상품 캐싱에 Redisson 분산 락 사용 | `@Cacheable(sync=true)`는 `RedisCacheManager`에서 JVM 로컬 락으로만 동작해 멀티 Pod stampede를 못 막음 → double-checked locking + Redisson 분산 락으로 직접 구현 |
| Kafka DLQ 도입 | Consumer 처리 실패 메시지 유실 방지, Poison Pill과 일시 장애 자동 분류, 지수 백오프 자동 재시도로 운영팀 개입 최소화 |
| PoisonPillClassifier | 재시도해도 의미 없는 예외(파싱/데이터 오류)를 즉시 DEAD 처리, 파티션 멈춤(lag 무한 증가) 방지 |
| 지수 백오프 DB 영속화 | spring-retry ExponentialBackOff는 메모리에만 존재 → 서버 재기동 시 재시도 일정 소멸. DlqEvent.nextRetryAt을 DB에 저장하여 재기동 후에도 재시도 일정 유지 |
| notification 채널 Strategy + Template Method | 채널 추가 시 Factory/기존 코드 변경 없이 구현체만 추가(OCP), CB/Retry/Fallback 골격을 모든 채널이 공유 |
| AbstractNotificationSender에서 RestClient 생성자 제거 | FCM처럼 REST가 아닌 채널도 같은 Template Method 골격을 재사용할 수 있도록, RestClient를 `post()` 파라미터로 전달받는 방식으로 변경 |
| ExternalApiServerException / ExternalApiClientException 분리 | resilience4j 설정이 HTTP 클라이언트 라이브러리·전송 방식(RestClient/FCM SDK)에 종속되지 않도록 예외 타입 통일 |
| OutboxEventWriter 통합 | loan/notification 양쪽에 동일하게 중복돼 있던 "Outbox INSERT + 이벤트 발행" 로직 통합 |
| RecordInterceptor 기반 MDC 전파 | 각 Kafka Consumer에 중복돼 있던 MDC put/clear 보일러플레이트를 전역 제거, 신규 Consumer 추가 시에도 자동 적용 |

<br>
