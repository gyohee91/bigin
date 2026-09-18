# Prometheus / Grafana로 부하테스트 지표 보기 (로컬 설치 기준, Docker 없음)

## 1. Prometheus 실행

```powershell
cd loadtest\prometheus
prometheus --config.file=prometheus.yml
```

기본 포트 9090. `http://localhost:9090/targets`에서 `bigin` job의 상태가 `UP`인지 먼저 확인하세요.
`DOWN`이면 앱이 8080에 떠 있지 않거나 `management.endpoints.web.exposure.include`에 `prometheus`가
빠진 것이니(`application.yaml`에는 이미 포함돼 있음) 앱 기동 여부부터 의심하면 됩니다.

## 2. 지표가 실제로 나오는지 curl로 먼저 확인

Prometheus/Grafana를 띄우기 전에, 앱이 지표 자체를 내보내는지부터 확인하는 게 순서입니다.

```powershell
curl.exe http://localhost:8080/actuator/prometheus | findstr "executor_"
curl.exe http://localhost:8080/actuator/prometheus | findstr "partner_http_pool_connections"
curl.exe http://localhost:8080/actuator/prometheus | findstr "resilience4j_"
```

- `executor_*`: `TaskExecutorMetrics`가 `ExecutorServiceMetrics.monitor(...)`로 등록한 지표.
  `name="loanLimitExecutor"` / `name="partnerApiExecutor"` 태그로 구분됩니다.
  주요 지표: `executor_active_threads`, `executor_pool_size_threads`, `executor_pool_core_threads`,
  `executor_pool_max_threads`, `executor_queued_task_count`, `executor_queue_remaining_task_count`,
  `executor_completed_tasks_total`.
- `partner_http_pool_connections`: `PartnerConnectionPoolMetrics`가 등록한 커스텀 게이지.
  `scope="total|partner"`, `partner=코드`, `state="leased|pending|available|max"` 태그.
- `resilience4j_*`: 별도 코드 없이 `resilience4j-micrometer` 의존성 + `management.metrics.enable.resilience4j: true`만으로
  자동 노출됩니다 (이미 적용돼 있음). 주요 지표:
  `resilience4j_circuitbreaker_state{name=,state=}` (0/1), `resilience4j_circuitbreaker_calls_seconds_count{name=,kind=,outcome=}`,
  `resilience4j_circuitbreaker_slow_calls{name=,kind=}`, `resilience4j_bulkhead_available_concurrent_calls{name=}`,
  `resilience4j_ratelimiter_available_permissions{name=}`, `resilience4j_retry_calls_total{name=,kind=}`
  (kind: successful_without_retry/successful_with_retry/failed_with_retry/failed_without_retry).

세 계열 중 하나라도 findstr 결과가 비어있으면 Prometheus/Grafana 설정을 만지기 전에
애플리케이션 쪽(빈 등록, 의존성, management 설정)부터 다시 봐야 합니다.

## 3. 부하테스트 중 보면 좋은 PromQL

```promql
# Executor 포화 - active/max 비율이 1에 가까워지면 곧 큐잉/거절 시작
executor_active_threads{name="partnerApiExecutor"} / executor_pool_max_threads{name="partnerApiExecutor"}

# 큐 적체
executor_queued_task_count{name="partnerApiExecutor"}

# 커넥션 풀 - 파트너별 leased가 max에 붙는지 (붙으면 ConnectionRequestTimeout 대기 시작 신호)
partner_http_pool_connections{scope="partner", state="leased"}
partner_http_pool_connections{scope="partner", state="pending"}   # 0이 아니면 커넥션 기다리는 요청 존재

# Circuit Breaker 상태 전이 (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
resilience4j_circuitbreaker_state{name="KAKAO_BANK"}

# RateLimiter 잔여 허용량 - 0에 붙으면 그 순간 요청이 거부되고 있다는 뜻
resilience4j_ratelimiter_available_permissions{name="KAKAO_BANK"}

# Bulkhead 잔여 동시 호출 슬롯
resilience4j_bulkhead_available_concurrent_calls{name="KAKAO_BANK"}

# 5초 구간 Retry 발생률 (재시도가 튀면 파트너 응답이 불안정해졌다는 신호)
rate(resilience4j_retry_calls_total{kind=~"successful_with_retry|failed_with_retry"}[30s])
```

## 4. Grafana

로컬 Grafana(`http://localhost:3000`)에 Data Source로 위 Prometheus(`http://localhost:9090`)를 추가한 뒤,
위 PromQL 6개를 각각 패널로 올리면 이번 부하테스트 단계(Executor 포화 → 커넥션 풀 → Resilience4j → orTimeout)를
한 화면에서 순서대로 관찰할 수 있습니다. 대시보드 JSON을 별도로 만들어드릴 수도 있으니 필요하면 말씀해주세요.
