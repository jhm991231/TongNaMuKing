package com.tongnamuking.tongnamuking_backend.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.Set;

/**
 * 멀티채널 수집기 데몬(chat-collector/daemon.js) 프로세스 1개의 생명주기 관리.
 *
 * 데몬이 죽으면 백오프 후 재시작하고, 레지스트리에 남아 있는 채널을 전부 다시 구독한다(replay).
 * 레지스트리가 같은 JVM 힙에 있으므로 데몬이 죽어도 복구 재료는 남아 있다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CollectorDaemonManager {

    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 30_000;
    /** 이 시간 이상 살아 있었으면 정상 기동으로 보고 백오프를 리셋한다 */
    private static final long STABLE_UPTIME_MS = 60_000;

    /**
     * GET /health가 200을 반환할 때까지 기다리는 최대 시간.
     * 이 기기에서 측정한 결과 데몬은 리스닝을 시작하기까지 ~180ms가 걸린다. 5초는
     * 그보다 넉넉한 여유를 두면서도, 데몬이 진짜로 죽어버린 경우 무한정 막히지 않게 한다.
     */
    private static final Duration DAEMON_READY_TIMEOUT = Duration.ofSeconds(5);
    private static final long DAEMON_READY_POLL_INTERVAL_MS = 50;

    /** replay 중 subscribe()가 실패했을 때 재시도할 최대 횟수(최초 시도 포함). */
    private static final int REPLAY_SUBSCRIBE_ATTEMPTS = 3;
    private static final long REPLAY_SUBSCRIBE_RETRY_DELAY_MS = 300;

    private final ChannelSubscriptionRegistry registry;

    @Value("${collector.daemon.enabled:true}")
    private boolean enabled;

    @Value("${collector.daemon.script}")
    private String scriptPath;

    @Value("${collector.daemon.port:3001}")
    private int port;

    private volatile Process process;
    private volatile boolean shuttingDown = false;
    private volatile long backoffMs = INITIAL_BACKOFF_MS;
    private volatile RestClient restClient;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            log.info("수집기 데몬이 비활성화되어 있습니다 (collector.daemon.enabled=false)");
            return;
        }
        restClient = RestClient.create("http://127.0.0.1:" + port);
        startDaemon();
    }

    @PreDestroy
    public synchronized void onShutdown() {
        shuttingDown = true;
        Process current = process;
        if (current != null && current.isAlive()) {
            log.info("수집기 데몬을 종료합니다 (PID: {})", current.pid());
            current.destroy();
            try {
                if (!current.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    current.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            }
        }
    }

    /**
     * synchronized(this)로 {@link #onShutdown()}과 동일한 모니터를 공유한다.
     * 데몬 크래시 → 재시작 스케줄 → startDaemon() 진입 사이의 어느 시점에
     * {@code @PreDestroy}가 끼어들어도, onShutdown()은 이 메서드가 끝날 때까지
     * (즉 process 필드가 새 프로세스로 갱신될 때까지) 대기하므로 "죽은 구(舊) 프로세스만
     * 확인하고 정상 종료로 오판"하는 경합을 막는다.
     */
    private synchronized boolean startDaemon() {
        if (shuttingDown) {
            return false;
        }
        try {
            File script = new File(scriptPath);
            ProcessBuilder builder = new ProcessBuilder("node", script.getAbsolutePath());
            builder.redirectErrorStream(true);
            builder.environment().put("COLLECTOR_PORT", String.valueOf(port));

            Process started = builder.start();
            process = started;

            // 방어적 재확인: 위 builder.start() 도중에 onShutdown()이 이미 shuttingDown을
            // 세팅해 둔 상태로 뒤늦게 여기 들어온 경우, 막 시작한 프로세스를 즉시 정리한다.
            if (shuttingDown) {
                log.info("종료 처리 중이라 방금 시작한 수집기 데몬을 즉시 종료합니다 (PID: {})", started.pid());
                started.destroyForcibly();
                return false;
            }

            long startedAt = System.currentTimeMillis();
            log.info("수집기 데몬 시작 (PID: {}, script: {})", started.pid(), script.getAbsolutePath());

            pipeLogs(started);
            superviseProcess(started, startedAt);
            return true;

        } catch (Exception e) {
            // IOException뿐 아니라 ProcessBuilder/environment() 등에서 나올 수 있는 어떤
            // 예외라도 여기서 잡아야 한다. 그렇지 않으면 이 메서드가 scheduleRestart()의
            // runAsync 람다 안에서 호출될 때 예외가 그대로 전파되어, 아무도 관찰하지 않는
            // CompletableFuture가 이를 삼켜버리고 복구 루프 전체가 조용히 영구 정지한다.
            log.error("수집기 데몬 시작 실패 (script: {})", scriptPath, e);
            scheduleRestart(System.currentTimeMillis());
            return false;
        }
    }

    private void pipeLogs(Process target) {
        startVirtualThread("collector-log-pipe", () -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(target.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[collector] {}", line);
                }
            } catch (IOException e) {
                if (!shuttingDown) {
                    log.warn("수집기 데몬 로그 읽기 종료: {}", e.getMessage());
                }
            } catch (Exception e) {
                log.warn("수집기 데몬 로그 파이프에서 예기치 못한 오류 발생", e);
            }
        });
    }

    private void superviseProcess(Process target, long startedAt) {
        startVirtualThread("collector-supervisor", () -> {
            try {
                int exitCode = target.waitFor();
                if (shuttingDown) {
                    log.info("수집기 데몬 종료됨 (exit: {})", exitCode);
                    return;
                }
                log.warn("수집기 데몬이 예기치 않게 종료됨 (exit: {}). 재시작합니다.", exitCode);
                scheduleRestart(startedAt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // 여기서 잡지 않으면 감시 스레드가 조용히 죽고 아무도 재시작을 예약하지
                // 않아 복구 루프가 끊긴다. 예기치 못한 오류라도 재시작을 시도한다.
                log.error("수집기 데몬 감시 중 예기치 못한 오류 발생. 재시작을 시도합니다.", e);
                scheduleRestart(startedAt);
            }
        });
    }

    private void scheduleRestart(long previousStartedAt) {
        startVirtualThread("collector-restart", () -> {
            try {
                long uptime = System.currentTimeMillis() - previousStartedAt;
                if (uptime >= STABLE_UPTIME_MS) {
                    backoffMs = INITIAL_BACKOFF_MS;
                }
                long wait = backoffMs;
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);

                log.info("{}ms 후 수집기 데몬을 재시작합니다", wait);
                Thread.sleep(wait);
                if (shuttingDown) {
                    return;
                }
                boolean started = startDaemon();
                if (!started) {
                    // startDaemon()이 실패한 경우 이미 자체적으로 다음 재시작을 예약하고
                    // 로그도 남겼다. 존재하지도 않는 데몬에 재구독 POST를 쏘아 "재구독 실패"
                    // 로그만 채널 수만큼 찍는 헛수고를 막기 위해 여기서 조용히 빠진다.
                    return;
                }
                if (!awaitDaemonReady(DAEMON_READY_TIMEOUT)) {
                    // 프로세스는 떴지만 HTTP 서버가 데드라인 안에 리스닝을 시작하지 않았다.
                    // 지금 재구독을 강행하면 전부 REFUSED로 실패해 레지스트리는 채널을 들고
                    // 있는데 데몬 커넥션은 하나도 없는 유령 상태가 영구화된다. 데몬이 실제로
                    // 죽으면 supervise 스레드가 이 재시작 루프를 다시 돌린다.
                    log.error("수집기 데몬이 {}ms 내에 준비되지 않아 재구독을 건너뜁니다", DAEMON_READY_TIMEOUT.toMillis());
                    return;
                }
                replaySubscriptions();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // startDaemon()은 이제 자체적으로 모든 예외를 잡아 재시도를 예약하므로
                // 여기 도달할 일은 드물지만, replaySubscriptions() 등에서 발생하는
                // 예기치 못한 예외로 재시작 루프 자체가 끊기는 일은 없도록 방어한다.
                log.error("수집기 데몬 재시작 루프에서 예기치 못한 오류 발생", e);
            }
        });
    }

    /** 데몬 관련 백그라운드 작업(로그 파이핑/감시/재시작 대기)을 가상 스레드에서 실행한다.
     * 이 작업들은 프로세스 수명 내내 blocking read/waitFor/sleep을 하므로,
     * 코어 수가 적은 배포 환경에서 공용 ForkJoinPool.commonPool() 스레드를
     * 장시간 점유하지 않도록 가상 스레드를 사용한다. 가상 스레드는 항상 데몬
     * 스레드이므로 JVM 종료를 막지 않는다. */
    private void startVirtualThread(String name, Runnable task) {
        Thread.ofVirtual().name(name).start(task);
    }

    /**
     * 데몬 재시작 후 레지스트리 기준으로 모든 채널을 다시 구독한다.
     *
     * 의도적으로 {@link MultiChannelCollectionService}의 채널별 락은 잡지 않는다. 검토 결과,
     * replay는 오직 POST(subscribe)만 보내므로 커넥션을 "추가"할 수만 있을 뿐, 위험한 방향인
     * "레지스트리에는 구독자가 있는데 데몬 커넥션은 없는" 유령 상태를 만들어낼 수 없다.
     * 반대 방향(레지스트리에 없는 채널에 커넥션만 먼저 생기는 오펀)은 이 루프가 도는 극히
     * 짧은 창(수 ms) 동안만 가능하고, daemon.js의 subscribe()가 멱등(already:true)이라
     * 스스로 낫는다 — 그 사이 도착한 채팅은 팬아웃 구독자가 없어 "No subscribers"로
     * 버려질 뿐 잘못 집계되지는 않는다. 락을 잡으면 복구가 끝날 때까지(HTTP 호출 포함 최대
     * 몇 초) 그 채널에 들어오려는 모든 신규 참가자를 막아야 하므로, 이 트레이드오프는
     * 받아들이지 않기로 했다.
     */
    private void replaySubscriptions() {
        Set<String> channels = registry.getAllChannels();
        if (channels.isEmpty()) {
            return;
        }
        log.info("수집기 데몬 재구독 시작: {}개 채널", channels.size());
        for (String channelId : channels) {
            boolean ok = subscribeWithRetry(channelId);
            if (!ok) {
                log.error("재구독 실패: {} ({}회 시도)", channelId, REPLAY_SUBSCRIBE_ATTEMPTS);
            }
        }
    }

    /**
     * replay 중 실패한 채널은 다음 데몬 크래시가 나기 전까지 재시도될 다른 경로가 없으므로,
     * 여기서 짧게 몇 번 더 시도해 본다.
     */
    private boolean subscribeWithRetry(String channelId) {
        for (int attempt = 1; attempt <= REPLAY_SUBSCRIBE_ATTEMPTS; attempt++) {
            if (subscribe(channelId)) {
                return true;
            }
            if (attempt < REPLAY_SUBSCRIBE_ATTEMPTS) {
                try {
                    Thread.sleep(REPLAY_SUBSCRIBE_RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 데몬의 GET /health가 200을 반환할 때까지 짧은 간격으로 폴링한다.
     *
     * ProcessBuilder.start()는 Node 프로세스가 실제로 HTTP 서버를 바인딩하기 전에 반환된다.
     * 이 머신에서 측정한 결과 데몬이 리스닝을 시작하기까지 ~180ms가 걸리는 반면, 이 직후의
     * 첫 연결 시도는 ~9ms 만에 REFUSED를 받는다 — 게이트 없이는 매번 이 경합에서 진다.
     * subscribe()와 재시작 후 replay 모두 이 게이트를 통과해야 한다.
     */
    private boolean awaitDaemonReady(Duration timeout) {
        if (!enabled || restClient == null) {
            return false;
        }
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (true) {
            if (shuttingDown) {
                return false;
            }
            if (pollHealth()) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(DAEMON_READY_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private boolean pollHealth() {
        try {
            restClient.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 데몬에 채널 구독을 요청한다. 데몬이 준비되지 않았거나 실패하면 false. */
    public boolean subscribe(String channelId) {
        if (!enabled) {
            log.warn("수집기 데몬이 비활성 상태입니다. 구독 무시: {}", channelId);
            return false;
        }
        if (!awaitDaemonReady(DAEMON_READY_TIMEOUT)) {
            // 데몬 기동 직후(또는 재시작 직후)의 좁은 창에서 구독 요청이 들어오면 여기서
            // 걸린다. ProcessBuilder.start()는 HTTP 서버가 리스닝을 시작하기 전에 반환되므로,
            // 이 게이트 없이 바로 POST했다면 REFUSED로 실패해 사용자에게 그대로 실패가
            // 노출되었을 것이다.
            log.error("데몬이 준비되지 않아 구독 요청을 보낼 수 없습니다: {}", channelId);
            return false;
        }
        try {
            restClient.post()
                    .uri("/channels/{channelId}", channelId)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.error("데몬 구독 요청 실패: {} — {}", channelId, e.getMessage());
            return false;
        }
    }

    /** 데몬에 채널 해제를 요청한다. 실패해도 예외를 던지지 않는다. */
    public void unsubscribe(String channelId) {
        if (!enabled) {
            return;
        }
        try {
            restClient.delete()
                    .uri("/channels/{channelId}", channelId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("데몬 해제 요청 실패: {} — {}", channelId, e.getMessage());
        }
    }
}
