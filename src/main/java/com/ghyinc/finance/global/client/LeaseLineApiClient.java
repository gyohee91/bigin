package com.ghyinc.finance.global.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.exception.ExternalApiFailException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.util.Map;

/**
 * 전용선 방식
 */
@Component
@RequiredArgsConstructor
public class LeaseLineApiClient implements ApiClient {
    private final ObjectMapper objectMapper;
    private final Map<PartnerCode, LeaseLineConnection> leaseLineConnections;

    private static final String ENCODING = "EUC-KR";
    private static final int HEADER_LENGTH = 20;

    @Override
    public <T> T post(PartnerCode partnerCode, String path, Object request, Class<T> responseType) {
        LeaseLineConnection connection = leaseLineConnections.get(partnerCode);
        if(connection == null) {
            throw new ExternalApiFailException("전용선_ERROR", "전용선 연결 설정 없음: " + partnerCode);
        }


        try {
            byte[] requestBytes = this.serialize(request, path);
            byte[] responseBytes = connection.send(requestBytes);

            return this.deserialize(responseBytes, responseType);
        } catch (JsonProcessingException | UnsupportedEncodingException e) {
            throw new ExternalApiFailException("전용선_ERROR", "전용선 오류: " + e.getMessage());
        }
    }

    /**
     * 전문 직렬화 - 고정길이 헤더 + 가변길이 바디
     * 전문 구조: [전문길이(4)] [거래코드(10)] [예비(6)] [바디(가변)]
     * @param request
     * @param trxCode
     * @return
     * @throws JsonProcessingException
     * @throws UnsupportedEncodingException
     */
    private byte[] serialize(Object request, String trxCode) throws JsonProcessingException, UnsupportedEncodingException {
        // 바디: JSON -> EUC-KR 바이트 변환
        String json = objectMapper.writeValueAsString(request);
        byte[] bodyBytes = json.getBytes(ENCODING);

        // 헤더 구성
        byte[] header = this.buildHeader(trxCode, bodyBytes.length);

        // 헤더 + 바디 결합
        byte[] fullMessage = new byte[header.length + bodyBytes.length];
        System.arraycopy(header, 0, fullMessage, 0, header.length);
        System.arraycopy(bodyBytes, 0, fullMessage, header.length, bodyBytes.length);

        return fullMessage;
    }

    /**
     * 전문 역직렬화 - 헤더 제거 후 body 파싱
     * @param responseBytes
     * @param responseType
     * @return
     * @param <T>
     * @throws UnsupportedEncodingException
     * @throws JsonProcessingException
     */
    private <T> T deserialize(byte[] responseBytes, Class<T> responseType) throws UnsupportedEncodingException, JsonProcessingException {
        // 헤더 스킵 후 body만 추출
        byte[] bodyBytes = new byte[responseBytes.length - HEADER_LENGTH];
        System.arraycopy(responseBytes, HEADER_LENGTH, bodyBytes, 0, bodyBytes.length);

        String json = new String(responseBytes, ENCODING).trim();
        return objectMapper.readValue(json, responseType);
    }

    /**
     * 헤더 전문 구성
     * [전문 길이 4자리][거래 코드 10자리][예비 6자리]
     * @param trxCode
     * @param bodyLength
     * @return
     * @throws UnsupportedEncodingException
     */
    private byte[] buildHeader(String trxCode, int bodyLength) throws UnsupportedEncodingException {
        String header = String.format("%-4d%-10s%-6s",
                HEADER_LENGTH + bodyLength,     // 전체 전문 길이
                trxCode,                        // 거래코드 (API Path)
                " "                             // 예비 공백
        );
        return header.getBytes(ENCODING);
    }
}
