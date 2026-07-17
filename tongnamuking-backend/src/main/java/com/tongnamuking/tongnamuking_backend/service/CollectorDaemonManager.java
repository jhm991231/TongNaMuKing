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
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
    private RestClient restClient;

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
    public void onShutdown() {
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

    private synchronized void startDaemon() {
        if (shuttingDown) {
            return;
        }
        try {
            File script = new File(scriptPath);
            ProcessBuilder builder = new ProcessBuilder("node", script.getAbsolutePath());
            builder.redirectErrorStream(true);
            builder.environment().put("COLLECTOR_PORT", String.valueOf(port));

            Process started = builder.start();
            process = started;
            long startedAt = System.currentTimeMillis();
            log.info("수집기 데몬 시작 (PID: {}, script: {})", started.pid(), script.getAbsolutePath());

            pipeLogs(started);
            superviseProcess(started, startedAt);

        } catch (IOException e) {
            log.error("수집기 데몬 시작 실패 (script: {})", scriptPath, e);
            scheduleRestart(System.currentTimeMillis());
        }
    }

    private void pipeLogs(Process target) {
        CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(target.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[collector] {}", line);
                }
            } catch (IOException e) {
                if (!shuttingDown) {
                    log.warn("수집기 데몬 로그 읽기 종료: {}", e.getMessage());
                }
            }
        });
    }

    private void superviseProcess(Process target, long startedAt) {
        CompletableFuture.runAsync(() -> {
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
            }
        });
    }

    private void scheduleRestart(long previousStartedAt) {
        CompletableFuture.runAsync(() -> {
            try {
                long uptime = System.currentTimeMillis() - previousStartedAt;
                if (uptime > STABLE_UPTIME_MS) {
                    backoffMs = INITIAL_BACKOFF_MS;
                }
                long wait = backoffMs;
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);

                log.info("{}ms 후 수집기 데몬을 재시작합니다", wait);
                Thread.sleep(wait);
                if (shuttingDown) {
                    return;
                }
                startDaemon();
                replaySubscriptions();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** 데몬 재시작 후 레지스트리 기준으로 모든 채널을 다시 구독한다. */
    private void replaySubscriptions() {
        Set<String> channels = registry.getAllChannels();
        if (channels.isEmpty()) {
            return;
        }
        log.info("수집기 데몬 재구독 시작: {}개 채널", channels.size());
        for (String channelId : channels) {
            boolean ok = subscribe(channelId);
            if (!ok) {
                log.error("재구독 실패: {}", channelId);
            }
        }
    }

    /** 데몬에 채널 구독을 요청한다. 데몬이 준비되지 않았거나 실패하면 false. */
    public boolean subscribe(String channelId) {
        if (!enabled) {
            log.warn("수집기 데몬이 비활성 상태입니다. 구독 무시: {}", channelId);
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
