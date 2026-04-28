package com.ghyinc.finance.domain.external.nice.adaptor;

import com.ghyinc.finance.domain.external.nice.config.NiceApiProperties;
import com.ghyinc.finance.domain.external.nice.dto.NiceDnrRequest;
import com.ghyinc.finance.domain.external.nice.dto.NiceDnrResult;
import com.ghyinc.finance.global.exception.ExternalApiFailException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Nice DNR API 통신 Adaptor
 *
 * <p>Nice API 전용 요청/응답 포맷 변환 담당
 * NiceDnrService -> NideDnrAdaptor -> Nice API</p>
 */
@Slf4j
@Component
public class NiceDnrAdaptor {
    private final RestClient restClient;
    private final NiceApiProperties niceApiProperties;

    public NiceDnrAdaptor(
            @Qualifier("niceDnrRestClient") RestClient restClient,
            NiceApiProperties niceApiProperties) {
        this.restClient = restClient;
        this.niceApiProperties = niceApiProperties;
    }

    /**
     * 자동차등록원부 단건 조회
     * @param request   조회 요청 (차량번호, 차주명 등)
     * @return          조회 결과
     */
    public NiceDnrResult inquire(NiceDnrRequest request) {
        String path = niceApiProperties.getDnr().getPath();

        try {
            return restClient.post()
                    .uri(path)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new ExternalApiFailException("NICE_DNR_ERROR", "NICE DNR 4xx 오류 " + res.getStatusCode());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new ExternalApiFailException("NICE_DNR_ERROR", "NICE DNR 5xx 오류 " + res.getStatusCode());
                    })
                    .body(NiceDnrResult.class);
        } catch (ExternalApiFailException e) {
            throw e;
        } catch (Exception e) {
            log.error("Nice DNR 조회 오류. carNo={}", request.vhrNo(), e);
            throw new ExternalApiFailException("NICE_DNR_ERROR", "NICE DNR 오류 " + e.getMessage());
        }
    }
}
