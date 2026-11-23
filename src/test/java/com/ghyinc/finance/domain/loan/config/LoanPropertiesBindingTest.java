package com.ghyinc.finance.domain.loan.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * yaml 키가 record 필드에 실제로 바인딩되는지 검증한다.
 * - 잘못된 키(polling-interval 처럼 필드명과 다른 키)는 기본값으로 조용히 무시되므로,
 *   "기본값과 같은 값"만 확인하면 오타를 못 잡는다. 그래서 (1) 기본값과 다른 값으로 바인딩 확인,
 *   (2) 실제 application.yaml 의 해당 prefix 아래에 바인딩되지 않은 키가 없는지 확인, 두 가지를 한다.
 */
class LoanPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LoanPropertiesConfig.class);

    @Test
    @DisplayName("기본값 - 키가 없으면 record 의 @DefaultValue 가 적용된다")
    void defaults_whenNoKeys() {
        runner.run(ctx -> {
            InquiryPollingProperties polling = ctx.getBean(InquiryPollingProperties.class);
            StaleInquiryProperties stale = ctx.getBean(StaleInquiryProperties.class);

            assertThat(polling.clientWaitLimit()).isEqualTo(Duration.ofSeconds(60));
            assertThat(polling.pollInterval()).isEqualTo(Duration.ofSeconds(2));
            assertThat(stale.acceptedTimeout()).isEqualTo(Duration.ofMinutes(2));
            assertThat(stale.pendingTimeout()).isEqualTo(Duration.ofMinutes(2));
            assertThat(stale.inProgressTimeout()).isEqualTo(Duration.ofMinutes(5));
            assertThat(stale.batchSize()).isEqualTo(100);
            assertThat(stale.maxRunDuration()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    @DisplayName("kebab-case 키가 기본값과 다른 값으로 각 필드에 바인딩된다")
    void kebabCaseKeys_bindToFields() {
        runner.withPropertyValues(
                "app.inquiry-polling.client-wait-limit=45s",
                "app.inquiry-polling.poll-interval=500ms",
                "app.inquiry-recovery.accepted-timeout=3m",
                "app.inquiry-recovery.pending-timeout=4m",
                "app.inquiry-recovery.in-progress-timeout=7m",
                "app.inquiry-recovery.batch-size=250",
                "app.inquiry-recovery.max-run-duration=15s"
        ).run(ctx -> {
            InquiryPollingProperties polling = ctx.getBean(InquiryPollingProperties.class);
            StaleInquiryProperties stale = ctx.getBean(StaleInquiryProperties.class);

            assertThat(polling.clientWaitLimit()).isEqualTo(Duration.ofSeconds(45));
            assertThat(polling.pollInterval()).isEqualTo(Duration.ofMillis(500));
            assertThat(stale.acceptedTimeout()).isEqualTo(Duration.ofMinutes(3));
            assertThat(stale.pendingTimeout()).isEqualTo(Duration.ofMinutes(4));
            assertThat(stale.inProgressTimeout()).isEqualTo(Duration.ofMinutes(7));
            assertThat(stale.batchSize()).isEqualTo(250);
            assertThat(stale.maxRunDuration()).isEqualTo(Duration.ofSeconds(15));
        });
    }

    @Test
    @DisplayName("필드명과 다른 키(polling-interval)는 바인딩되지 않고 기본값으로 남는다 - 오타가 조용히 무시되는 현상의 재현")
    void wrongKey_isSilentlyIgnored() {
        runner.withPropertyValues("app.inquiry-polling.polling-interval=9s")
                .run(ctx -> assertThat(ctx.getBean(InquiryPollingProperties.class).pollInterval())
                        .isEqualTo(Duration.ofSeconds(2)));
    }

    @Test
    @DisplayName("실제 application.yaml - app.inquiry-polling / app.inquiry-recovery 아래에 바인딩되지 않은 키가 없다")
    void applicationYaml_hasNoUnboundKeys() throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));
        loaded.forEach(sources::addLast);

        Binder binder = new Binder(ConfigurationPropertySources.from(sources));

        assertThat(bindStrict(binder, "app.inquiry-polling", InquiryPollingProperties.class)).isNotNull();
        assertThat(bindStrict(binder, "app.inquiry-recovery", StaleInquiryProperties.class)).isNotNull();
    }

    @Test
    @DisplayName("검증 장치 자체 확인 - 알 수 없는 키가 있으면 strict 바인딩이 실패한다")
    void strictBinding_failsOnUnknownKey() {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new org.springframework.core.env.MapPropertySource("t",
                java.util.Map.of("app.inquiry-polling.polling-interval", "2s")));
        Binder binder = new Binder(ConfigurationPropertySources.from(sources));

        assertThatThrownBy(() -> bindStrict(binder, "app.inquiry-polling", InquiryPollingProperties.class))
                .hasRootCauseInstanceOf(org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException.class);
    }

    private static <T> T bindStrict(Binder binder, String prefix, Class<T> type) {
        return binder.bind(prefix, Bindable.of(type), new NoUnboundElementsBindHandler(BindHandler.DEFAULT))
                .orElse(null);
    }
}
