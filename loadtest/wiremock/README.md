# Partner API Mock (WireMock)

`application.yaml`의 `loan-api.partners.*`(KAKAO_BANK/TOSS_BANK/KB_CAPITAL/LINE_BANK)가 모두
`base-url: http://localhost:8091`, `path: /send/sms`를 가리킵니다. 이 폴더를 그대로 WireMock
루트로 띄우면 됩니다. SHINHAN_BANK는 lease-line(EUC-KR 고정길이) 프로토콜이라 REST 스텁 대상이
아니므로 제외했습니다.

## 실행 (Docker 없이 - Standalone JAR)

이미 JDK 17이 설치되어 있으므로(Gradle 빌드용) 추가 런타임 설치 없이 jar 하나만 받으면 됩니다.
Kafka/Redis/Prometheus/Grafana를 로컬 설치로 띄우는 것과 동일한 방식입니다.

1. jar 다운로드 (최초 1회, 최신 안정 버전 3.13.2 기준):
   ```powershell
   Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/wiremock/wiremock-standalone/3.13.2/wiremock-standalone-3.13.2.jar" -OutFile "wiremock-standalone-3.13.2.jar"
   ```
   (사내망이 Maven Central을 막아둔 경우, 사내 아티팩토리/미러에서 동일 좌표로 받으세요.)

2. 이 폴더(`loadtest/wiremock`)에서 실행 - `--root-dir .`로 지정하면 하위 `mappings/`, `__files/`를
   그대로 읽습니다:
   ```powershell
   java -jar wiremock-standalone-3.13.2.jar --port 8091 --root-dir . --container-threads 300
   ```
   포그라운드로 뜨므로, kafka/redis처럼 별도 PowerShell 창에 하나 띄워두고 쓰면 됩니다.

   **`--container-threads 300`이 중요합니다.** `loan-api.partners`에 등록된 50개 파트너 중
   SHINHAN_BANK(lease-line, 9001)를 뺀 49개가 전부 이 WireMock 하나(`localhost:8091`)를
   공유합니다. 목표 20 req/s × 49 파트너 팬아웃 × 응답 300ms 기준으로 Little's Law를 적용하면
   동시 요청이 20×49×0.3 ≈ 294건까지 몰릴 수 있는데, WireMock(Jetty) 기본 스레드 수는 이보다
   훨씬 작아서 지정 안 하면 그 자체가 병목이 되어 `DeadlineTimeoutException`/
   `RequestFailedException`이 발생합니다. (클라이언트 쪽 `PartnerConnectionPoolConfig`도
   여러 파트너가 같은 물리 호스트로 충돌할 때 maxPerRoute를 합산하도록 함께 고쳤습니다 -
   전에는 마지막 파트너 설정으로 덮어써져서 15~20개로 묶여 있었습니다.)

기본은 `partner-send-sms-success.json`(200 + 300ms 지연)만 활성 상태로 두세요. jar 파일 자체는
`.gitignore`에 추가해 커밋하지 마세요 (바이너리라 리포에 안 넣는 게 맞습니다).

## 지연/타임아웃 시나리오

`orTimeout` 경로를 보고 싶으면(현재는 `PartnerOrTimeoutConfig`가 파트너별로 다르게 계산하므로
고정된 8초가 아닙니다 - 실제 값은 `/actuator/beans` 등으로 확인) `partner-send-sms-success.json`을
mappings 폴더 밖으로 옮기고 `partner-send-sms-slow.json`(9초 지연)만 남긴 뒤 WireMock을
재시작(또는 `POST http://localhost:8091/__admin/mappings/reset` 후 슬로우 매핑만 로드)하세요.
WireMock은 `priority` 숫자가 **작을수록 우선순위가 높습니다**(1이 최우선). 두 파일을 동시에 두면
`priority: 1`인 success가 항상 이기므로(slow는 `priority: 10`), slow를 실제로 테스트하려면
반드시 success 파일을 빼야 합니다.

## Circuit Breaker 트리거 시나리오 (`cb-failure-cycle`)

`partner-send-sms-cb-fail-1~3.json` + `partner-send-sms-cb-ok-1~2.json` 5개 파일이 WireMock
Scenario(`cb-failure-cycle`)로 연결되어 있습니다. 상태가 `Started → STEP_2 → STEP_3 → STEP_4 →
STEP_5 → Started`로 순환하며 500(실패) 3번, 200(성공) 2번을 반복해 **요청 5개당 실패율 60%**를
만듭니다 - `failure-rate-threshold: 50`, `minimum-number-of-calls: 5`를 확실히 넘기기 위해 정확히
50%가 아니라 여유를 둔 값입니다.

이 5개는 `priority: 20`이라 평소엔 `priority: 1`인 success 매핑에 항상 밀려 조용히 있습니다.
CB를 실제로 트리거하려면:

1. `partner-send-sms-success.json`을 mappings 밖으로 옮긴다 (slow도 같이 있다면 마찬가지).
2. 시나리오 상태를 처음부터 시작하려면 재시작하거나
   `POST http://localhost:8091/__admin/scenarios/reset`을 호출한다 (이전 테스트가 STEP 중간에
   멈춰 있으면 실패율이 왜곡됩니다).
3. k6로 트래픽을 흘리면서 `resilience4j_circuitbreaker_state{name="KAKAO_BANK"}`가
   0(CLOSED)→1(OPEN)로 바뀌는지, `wait-duration-in-open-state`(60s) 뒤 2(HALF_OPEN)를 거쳐 다시
   0으로 돌아오는지 Grafana/Prometheus로 관찰한다 (`loadtest/prometheus/README.md` 참고).

테스트가 끝나면 success 매핑을 다시 mappings 폴더로 되돌려 두는 걸 잊지 마세요 - 다른 목적의
테스트(Executor 포화 등)를 돌릴 때 cb-failure-cycle이 조용히 섞여 들어가면 결과가 흔들립니다.

## 확인

```powershell
curl.exe -X POST http://localhost:8091/send/sms -d '{}' -H 'Content-Type: application/json'
```

(PowerShell 내장 `curl`은 `Invoke-WebRequest` 별칭이라 옵션이 다릅니다 - 반드시 `curl.exe`로 호출하세요.)
