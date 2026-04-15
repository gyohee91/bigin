package com.ghyinc.finance.global.client;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.exception.ExternalApiFailException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

@Slf4j
@RequiredArgsConstructor
public class LeaseLineConnection {
    private final String host;
    private final int port;

    // 소켓 연결 풀 (재사용)
    private Socket socket;
    private DataOutputStream outputStream;
    private DataInputStream inputStream;

    private static final int TIMEOUT = 500;

    public synchronized byte[] send(PartnerCode partnerCode, byte[] requestBytes) {

        try {
            this.connect(partnerCode);

            // 전문 전송
            outputStream.writeInt(requestBytes.length); // 전문 길이 헤더
            outputStream.write(requestBytes);
            outputStream.flush();

            // 응답 수신
            int responseLength = inputStream.readInt(); // 응답 전문 길이
            byte[] responseBytes = new byte[responseLength];
            inputStream.readFully(responseBytes);

            return responseBytes;
        } catch (IOException e) {
            log.error("[{}] 전용선 통신 오류, host={}, port={}", partnerCode, host, port);
            this.disconnect(partnerCode);
            throw new ExternalApiFailException("전용선_ERROR", "전용선 통신 오류: " + e.getMessage());
        }
    }

    public void connect(PartnerCode partnerCode) throws IOException {
        if(socket == null || socket.isClosed() || !socket.isConnected()) {
            log.info("[{}] 전용선 연결 시도. host={}, port={}", partnerCode, host, port);
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), TIMEOUT);
            socket.setSoTimeout(TIMEOUT);

            outputStream = new DataOutputStream(socket.getOutputStream());
            inputStream = new DataInputStream(socket.getInputStream());
            log.info("전용선 연결 완료");
        }
    }

    private void disconnect(PartnerCode partnerCode) {
        try {
            if(socket != null && !socket.isClosed()) {
                socket.close();
                log.info("[{}] 전용선 연결 종료", partnerCode);
            }
        } catch (IOException e) {
            log.warn("[{}] 전용선 연결 종료 중 오류", partnerCode, e);
        }
    }

}
