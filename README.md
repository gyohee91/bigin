# 🏦 Bigin - 파트너사 제휴 한도조회 플랫폼

> 실무 대출 비교 플랫폼 운영 경험을 바탕으로 설계한 개인 핀테크 포트폴리오 프로젝트.
> 다수의 금융사와 연동하여 대출 한도조회 및 신청을 처리하는 백엔드 시스템.

<br>

## 📌 프로젝트 개요

### 실무 배경

현재 재직 중인 회사에서 **40개 이상의 금융사와 연동하는 대출 비교 플랫폼(OK비교대출)** 을 개발·운영하고 있습니다.

실무에서는 아래와 같은 제약이 있었습니다.

- Java 8 + Spring Boot 2.7 기반의 레거시 구조 유지
- Layered Architecture (Controller → Service → Repository)
- 기존 시스템과의 호환성을 고려한 점진적 개선만 가능

### 프로젝트 목적

실무에서 직접 경험한 **제휴 금융사 한도조회 시스템의 핵심 도메인**을 새로운 기술 스택과 아키텍처로 재설계하여 구현했습니다.

- 실무와 동일한 비즈니스 로직 (콜백 기반 비동기 한도조회, 상품별 채번, 디자인 패턴 등)
- Java 21 + Spring Boot 3.5로 업그레이드하여 최신 기능 적용
- Layered Architecture → Domain-driven Package Structure로 전환

<br>

## 🛠 기술 스택

### 실무 vs 개인 프로젝트 비교

| 구분           | 실무 (OK비교대출)         | 개인 프로젝트 (Bigin)                      |
|--------------|---------------------|--------------------------------------|
| Language     | Java 8              | Java 17                              |
| Framework    | Spring Boot 2.7     | Spring Boot 3.5                      |
| Architecture | Layered Architecture | Domain-driven Package Structure      |
| DB           | Oracle DB           | H2 DB (In-memory)                    |
| 메시지큐         | ActiveMQ            | Kafka                                |
| API 통신       | RestTemplate        | RestClient                           |

### 개인 프로젝트 상세 스택

| 구분 | 기술                                   |
|---|--------------------------------------|
| Language | Java 17                              |
| Framework | Spring Boot 3.5                      |
| ORM | Spring Data JPA / Hibernate          |
| DB | H2 DB                                |
| 비동기 | Spring @Async / CompletableFuture    |
| 장애격리 | Resilience4j Circuit Breaker / Retry |
| 암복호화 | AES-256-CBC, AES-256-ECB, RSA-OAEP   |
| API 문서 | SpringDoc OpenAPI (Swagger)          |
| Build | Gradle                               |

<br>

## 📁 패키지 구조

```
com.ghyinc.finance
├── domain
│   ├── loan
│   │   ├── controller         # API 진입점
│   │   ├── service            # 비즈니스 로직
│   │   │   ├── LoanLimitService.java
│   │   │   ├── LoanLimitSenderService.java    # @Async 비동기 전송
│   │   │   └── LoanLimitCallbackService.java  # 콜백 수신 처리
│   │   ├── adaptor            # 금융사별 API 변환
│   │   │   ├── common         # 표준 Layout 금융사 공통
│   │   │   ├── impl           # 비표준 금융사 개별 구현
│   │   │   └── callback       # 콜백 수신 Adaptor
│   │   ├── strategy           # 대출유형별 전략 패턴
│   │   ├── entity             # JPA Entity
│   │   ├── repository         # Spring Data JPA
│   │   ├── dto                # 요청/응답 DTO
│   │   └── enums              # 상태/타입 Enum
│   └── external
│       └── nice               # Nice DNR (자동차등록원부) 연동
├── global
│   ├── client                 # 통신 방식별 ApiClient (REST, 전용선)
│   ├── common                 # 공통 유틸 (채번, BaseEntity 등)
│   ├── config                 # Spring 설정
│   ├── crypto                 # 암복호화 (AES, RSA)
│   └── exception              # 전역 예외 처리
```

<br>

## 🏗 핵심 아키텍처

### 1. 한도조회 비동기 처리 흐름

```
FE → POST /api/loan/limit/inquiry
         │
         ▼
  LoanLimitService
  ├── Strategy 선택 (대출유형별)
  ├── 외부데이터 조회 (Nice DNR 등)
  ├── LoanLimitInquiry INSERT (PENDING)
  └── 202 Accepted 즉시 응답
         │
         ▼ @Async (병렬)
  LoanLimitSenderService
  ├── LoanLimitResult INSERT       (금융사당 1건)
  ├── LoanLimitProductResult INSERT (상품당 1건, PENDING 선저장)
  └── 금융사별 API 병렬 전송 (CompletableFuture)
         │
         ▼ Callback
  금융사 → POST /api/loan/limit/callback
  LoanLimitCallbackService
  ├── loReqtNo + productCode로 선저장 데이터 조회 및 UPDATE
  ├── 비관적 락으로 count 동시성 제어
  └── 완료 시 알림 이벤트 발행
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

#### 통신 방식별 ApiClient 분리

```
REST   → RestApiClient
전용선 → LeaseLineApiClient (고정길이 전문, EUC-KR 인코딩)
```

<br>

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

<br>

## 🔒 장애 격리 - Resilience4j

금융사별 독립적인 Circuit Breaker 인스턴스로 특정 금융사 장애 시 격리합니다.

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-size: 20
        failure-rate-threshold: 50
        wait-duration-in-open-state: 60s
    instances:
      KAKAO_BANK:
        base-config: default
      KB_BANK:
        base-config: default
        slow-call-duration-threshold: 10s  # 전용선 응답 지연 고려
```

```
CLOSED  → 정상 (모든 요청 통과)
OPEN    → 장애 (즉시 CallNotPermittedException, 해당 금융사만 격리)
HALF_OPEN → 복구 시도 (제한적 요청으로 복구 여부 확인)
```

<br>

## 🔐 암복호화

금융사별 암호화 알고리즘과 키를 DB로 관리하며 `@PostConstruct`로 서버 기동 시 초기화합니다.

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

<br>

## 🚗 오토담보/주택담보 대출 - Nice DNR, KB부동산 시세정보 연동

```java
// Strategy 패턴으로 대출유형별 외부 데이터 조회 분기
ExternalDataContext context = strategy.requiresExternalData()
        ? strategy.fetchExternalData(request)
        : ExternalDataContext.empty();
```

로컬 테스트 시 `@Profile("local")` MockNiceDnrService로 가데이터를 사용합니다.

<br>

## 🔄 콜백 동시성 제어

여러 금융사 콜백이 동시에 수신될 때 `LoanLimitInquiry` count 업데이트의 Lost Update를 방지합니다.

```java
// LoanLimitProductResultRepository - 비관적 락으로 inquiry 조회
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p.inquiry FROM LoanLimitProductResult p WHERE p.loReqtNo = :loReqtNo")
Optional<LoanLimitInquiry> findInquiryByLoReqtNoWithLock(@Param("loReqtNo") String loReqtNo);
```

```
금융사 수에 따른 동시성 전략
  현재 → 비관적 락
  예정 → Message Queue 직렬화 처리
```

<br>

## 📋 API 명세

| Method | URL | 설명 |
|---|---|---|
| POST | /api/loan/limit/inquiry | 한도조회 요청 |
| GET | /api/loan/limit/inquiry/{inquiryNo} | 한도조회 결과 폴링 |
| POST | /api/loan/limit/callback | 한도결과 콜백 수신 (금융사 → 플랫폼) |
| POST | /api/loan/apply | 대출신청 |
| GET | /api/loan/apply/{applicationNo} | 대출신청 결과 조회 |

<br>

## ⚙️ 로컬 실행

```bash
# 1. 프로젝트 클론
git clone https://github.com/your-repo/bigin.git

# 2. 로컬 프로파일로 실행 (H2 DB, Mock Nice DNR)
./gradlew bootRun --args='--spring.profiles.active=local'

# 3. Swagger UI 접속
http://localhost:8080/swagger-ui.html
```

### application-local.yml 주요 설정

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:financedb
  h2:
    console:
      enabled: true

partner-api:
  partners:
    KAKAO_BANK:
      base-url: https://api.kakaobank.com
      path: /v1/loan/limit
      connection-type: REST
```

<br>

## 🧪 테스트

```bash
# 전체 테스트 실행
./gradlew test

# 주요 테스트 대상
- LoanLimitServiceTest       # 한도조회 요청 비즈니스 로직
- LoanLimitSenderServiceTest # 비동기 전송 및 상태 처리
- LoanLimitCallbackServiceTest # 콜백 수신 및 중복 방어
- LoanLimitStrategyTest      # 대출유형별 전략 검증
- AesCryptoServiceTest       # AES 암복호화
- RsaCryptoServiceTest       # RSA 암복호화
- InquiryNoGeneratorTest     # 채번 중복 없음 검증
```

<br>

## 📝 주요 설계 결정

| 결정 | 이유 |
|---|---|
| 상품별 loReqtNo 선저장 | 콜백 loReqtNo 유효성 검증, 타임아웃 처리, 대출신청 연결 |
| LoanLimitResult 분리 | 상품 수가 많아도 금융사당 1건만 INSERT/UPDATE |
| 통신방식별 ApiClient 분리 | REST/전용선 금융사 혼재 대응, OCP 준수 |
| 금융사별 Circuit Breaker | 특정 금융사 장애 시 다른 금융사 영향 없이 격리 |
| 암호화 키 DB 관리 | 배포 없이 키 교체 가능, @PostConstruct 초기화로 성능 확보 |
| ExternalDataContext | 외부 조회 결과 파라미터 고정 (Nice DNR, KB시세 등 확장 시 파라미터 불변) |

<br>

## 👨‍💻 Author

**OK Capital Backend Developer**  
실무 대출 비교 플랫폼(OK비교대출) 운영 경험을 바탕으로 설계한 개인 포트폴리오 프로젝트입니다.