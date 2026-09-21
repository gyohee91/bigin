// wiremock-ceiling.js
import http from 'k6/http';

export const options = {
  scenarios: {
    ceiling: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 1200,
      stages: [
        { target: 300, duration: '30s' },
        { target: 600, duration: '1m' },
        { target: 980, duration: '1m' },   // 실제 설계 목표치
        { target: 1500, duration: '1m' },  // 여유 확인
        { target: 0, duration: '30s' },
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500'],   // 고정 지연이 300ms이므로 500ms 넘으면 큐잉 시작 신호
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  http.post('http://localhost:8091/send/sms', JSON.stringify({}), {
    headers: { 'Content-Type': 'application/json' },
  });
}