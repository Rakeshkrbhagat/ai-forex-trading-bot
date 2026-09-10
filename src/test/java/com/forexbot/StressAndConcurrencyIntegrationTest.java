package com.forexbot;

import com.forexbot.dto.BotConfig;
import com.forexbot.dto.BotStatus;
import com.forexbot.dto.LoginRequest;
import com.forexbot.dto.LoginResponse;
import com.forexbot.dto.MarketTickRequest;
import com.forexbot.dto.RiskGuardrails;
import com.forexbot.dto.TradeDecision;
import com.forexbot.service.BotStateManager;
import com.forexbot.service.GeminiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * JTICKET-08 — End-to-end integration + concurrency stress test.
 * <p>
 * Boots the full Spring context on a random port and drives real HTTP traffic
 * against the {@code /api/bot/**} endpoints. The Gemini call is stubbed so the
 * pipeline is deterministic while every other bean (state manager, risk
 * firewall, market-structure filter, controllers) runs for real.
 * <p>
 * Verifies:
 * <ul>
 *   <li>Thread safety of the tick pipeline under a burst of concurrent ticks.</li>
 *   <li>The risk kill switch shuts the bot down when the simulated loss limit
 *       is breached.</li>
 *   <li>The daily-trade-cap kill switch halts execution under load.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StressAndConcurrencyIntegrationTest {

    /** Deterministic TRENDING/BUY analysis so confirmed trades are dispatched. */
    private static final String TRENDING_BUY_JSON = """
            {
              "currencyPair": "EUR/USD",
              "structure": "TRENDING",
              "direction": "BUY",
              "keySupport": 1.0800,
              "keyResistance": 1.0900,
              "confidence": 0.95,
              "rationale": "stress-test trending signal"
            }
            """;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private BotStateManager stateManager;

    @MockBean
    private GeminiService geminiService;

    @BeforeEach
    void stubGemini() {
        when(geminiService.analyzeMarketStructure(any(MarketTickRequest.class)))
                .thenReturn(TRENDING_BUY_JSON);
        authenticate();
    }

    /**
     * Logs in with the default dev credentials and attaches the issued bearer
     * token to every subsequent request, since {@code /api/bot/**} is guarded by
     * the {@code AuthInterceptor}.
     */
    private void authenticate() {
        LoginResponse login = rest.postForEntity(
                "http://localhost:" + port + "/api/auth/login",
                new LoginRequest("admin", "changeme"), LoginResponse.class).getBody();
        assertThat(login).isNotNull();
        String bearer = "Bearer " + login.token();
        rest.getRestTemplate().getInterceptors().removeIf(i -> i instanceof AuthHeaderInterceptor);
        rest.getRestTemplate().getInterceptors().add(new AuthHeaderInterceptor(bearer));
    }

    /** Attaches the bearer token to outbound test requests. */
    private record AuthHeaderInterceptor(String bearer)
            implements org.springframework.http.client.ClientHttpRequestInterceptor {
        @Override
        public org.springframework.http.client.ClientHttpResponse intercept(
                org.springframework.http.HttpRequest request, byte[] body,
                org.springframework.http.client.ClientHttpRequestExecution execution)
                throws java.io.IOException {
            request.getHeaders().set(HttpHeaders.AUTHORIZATION, bearer);
            return execution.execute(request, body);
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + "/api/bot" + path;
    }

    private void configure(int maxTrades, double maxLoss) {
        BotConfig config = new BotConfig(
                "ACC-E2E", "EUR/USD", maxTrades, maxLoss, 1.0, 20);
        ResponseEntity<BotStatus> resp = rest.postForEntity(url("/config"), config, BotStatus.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Guardrails must permit the traded symbol; keep the drawdown limit equal
        // to the configured max loss so the firewall + guardrails stay consistent.
        RiskGuardrails guardrails = new RiskGuardrails(
                1.0, List.of("EUR/USD"), maxLoss, 20, 40, 100_000.0, false);
        ResponseEntity<RiskGuardrails> g = rest.postForEntity(
                "http://localhost:" + port + "/api/risk/guardrails", guardrails, RiskGuardrails.class);
        assertThat(g.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void start() {
        ResponseEntity<BotStatus> resp =
                rest.postForEntity(url("/start?accountId=ACC-E2E"), null, BotStatus.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().running()).isTrue();
    }

    private MarketTickRequest tick() {
        return new MarketTickRequest("EUR/USD", 1.08500, 1.08520, Instant.now());
    }

    @Test
    void healthEndpointIsUp() {
        ResponseEntity<String> resp = rest.getForEntity(url("/health"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo("UP");
    }

    @Test
    void concurrentTicksProduceExactTradeCountWithNoLostUpdates() throws Exception {
        int totalTicks = 500;
        // Cap high enough that every tick becomes a confirmed trade.
        configure(totalTicks + 100, 1_000_000.0);
        start();

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger executed = new AtomicInteger(0);

        List<Callable<Void>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < totalTicks; i++) {
            tasks.add(() -> {
                startGate.await();
                ResponseEntity<TradeDecision> resp =
                        rest.postForEntity(url("/tick"), tick(), TradeDecision.class);
                if (resp.getStatusCode() == HttpStatus.OK
                        && resp.getBody() != null
                        && resp.getBody().action() != TradeDecision.Action.HOLD) {
                    executed.incrementAndGet();
                }
                return null;
            });
        }

        List<Future<Void>> futures = new java.util.ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(pool.submit(task));
        }
        startGate.countDown(); // fire all ticks together
        for (Future<Void> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // Every tick was a confirmed trend and within the cap -> exactly one
        // trade per tick, with no lost increments in the atomic counter.
        assertThat(executed.get()).isEqualTo(totalTicks);
        assertThat(stateManager.getTradesExecutedToday()).isEqualTo(totalTicks);
    }

    @Test
    void killSwitchShutsBotDownWhenLossLimitBreached() {
        configure(1_000, 500.0); // max daily loss = $500
        start();

        // Simulate accumulated trading losses beyond the configured limit.
        stateManager.addPnl(-750.0);

        // Next tick must be rejected by the risk firewall with 423 LOCKED.
        ResponseEntity<TradeDecision> blocked =
                rest.postForEntity(url("/tick"), tick(), TradeDecision.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(blocked.getBody()).isNotNull();
        assertThat(blocked.getBody().action()).isEqualTo(TradeDecision.Action.HOLD);

        // The bot must be shut down and the kill switch latched.
        assertThat(stateManager.isKillSwitchEngaged()).isTrue();
        ResponseEntity<BotStatus> status =
                rest.getForEntity(url("/status"), BotStatus.class);
        assertThat(status.getBody()).isNotNull();
        assertThat(status.getBody().running()).isFalse();

        // Kill switch is latched: further ticks stay blocked even after the fact.
        ResponseEntity<TradeDecision> stillBlocked =
                rest.postForEntity(url("/tick"), tick(), TradeDecision.class);
        assertThat(stillBlocked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    void dailyTradeCapEngagesKillSwitchUnderConcurrentLoad() throws Exception {
        int cap = 50;
        int totalTicks = 400; // far exceed the cap concurrently
        configure(cap, 1_000_000.0);
        start();

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch startGate = new CountDownLatch(1);

        List<Future<Integer>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < totalTicks; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                ResponseEntity<TradeDecision> resp =
                        rest.postForEntity(url("/tick"), tick(), TradeDecision.class);
                return resp.getStatusCode() == HttpStatus.LOCKED ? 1 : 0;
            }));
        }
        startGate.countDown();

        int blocked = 0;
        for (Future<Integer> f : futures) {
            blocked += f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // The cap must have tripped the kill switch and stopped the bot.
        assertThat(stateManager.isKillSwitchEngaged()).isTrue();
        assertThat(stateManager.isRunning()).isFalse();
        assertThat(blocked).isGreaterThan(0);

        // Trades executed must not drift far past the cap despite heavy
        // concurrency (bounded by the number of in-flight threads).
        assertThat(stateManager.getTradesExecutedToday())
                .isGreaterThanOrEqualTo(cap)
                .isLessThanOrEqualTo(cap + 32);
    }
}

