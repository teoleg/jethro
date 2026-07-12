package io.jethro.app.chat;

import io.jethro.refdata.RefDataRepository;
import io.jethro.trading.algo.inference.ModelInferenceClient;
import io.jethro.trading.riskpnl.RiskLimitEvaluator;
import io.jethro.trading.riskpnl.RiskLimitSource;
import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.uigateway.AttentionFeed;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Operational chat wiring (ADR-0021). Assembles the intent parser (SLM if available, else
 * keyword), the deterministic responder over the risk/attention services, and the audit
 * store (Postgres if persistence is on, else no-op). Cross-domain, so it lives in the app
 * assembly (ADR-0015).
 */
@Configuration
public class ChatConfig {

    @Bean
    ChatIntentParser chatIntentParser(ObjectProvider<ModelInferenceClient> model,
                                      ObjectProvider<RefDataRepository> refData) {
        Supplier<Set<String>> books = () -> {
            RefDataRepository repo = refData.getIfAvailable();
            return repo == null ? Set.of()
                    : repo.findAllBooks().stream().map(b -> b.id().value()).collect(Collectors.toSet());
        };
        Supplier<Set<String>> instruments = () -> {
            RefDataRepository repo = refData.getIfAvailable();
            return repo == null ? Set.of()
                    : repo.findAllInstruments().stream().map(i -> i.id().value()).collect(Collectors.toSet());
        };
        return new ChatIntentParser(model.getIfAvailable(), books, instruments);
    }

    @Bean
    ChatResponder chatResponder(RiskProjection risk, RiskLimitSource limits,
                                RiskLimitEvaluator evaluator, AttentionFeed feed) {
        return new ChatResponder(risk, limits, evaluator, feed);
    }

    @Bean
    ChatAuditStore chatAuditStore(ObjectProvider<JdbcTemplate> jdbc) {
        JdbcTemplate template = jdbc.getIfAvailable();
        return template != null ? new ChatAuditStore.Jdbc(template) : ChatAuditStore.NOOP;
    }

    @Bean
    ChatService chatService(ChatIntentParser parser, ChatResponder responder, ChatAuditStore audit,
                            ObjectProvider<ModelInferenceClient> model) {
        ModelInferenceClient client = model.getIfAvailable();
        return new ChatService(parser, responder, audit, client != null ? client.modelId() : "keyword");
    }

    @Bean
    ChatController chatController(ChatService service) {
        return new ChatController(service);
    }
}
