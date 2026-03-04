### Kafka & Prometheus & Grafana 실행
- zookeper 수행
> bin\windows\zookeeper-server-start.bat config\zookeeper.properties

- kafka 수행 (두 명령어 같은 창에서)
> $env:KAFKA_OPTS="-javaagent:C:\kafka\jmx_prometheus_javaagent-0.20.0.jar=9101:C:\kafka\kafka-config.yml"

> bin\windows\kafka-server-start.bat config\server.properties

- prometheus 수행
>C:\prometheus> .\prometheus.exe --config.file=prometheus.yml

### Prometheus의 query 명령어
> - http://localhost:9090/ 접속
> - spring_kafka_template_seconds_count: Producer가 보낸 메시지 수
> - kafka_consumer_fetch_manager_records_consumed_total: Consumer가 처리한 메시지 수
> - kafka_consumer_fetch_manager_records_lag: 현재 Lag(밀린 메시지 수)