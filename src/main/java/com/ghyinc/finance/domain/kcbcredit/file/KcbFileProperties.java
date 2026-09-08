package com.ghyinc.finance.domain.kcbcredit.file;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "kcb.file")
public class KcbFileProperties {
    private String localWatchDir;
    private String doneDir;
}
