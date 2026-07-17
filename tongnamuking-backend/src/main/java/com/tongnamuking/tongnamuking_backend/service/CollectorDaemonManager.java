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

            // 방어적 재확인: 위 builder.start() 도중에 onShutdown()이 이미 shuttingDown을
            // 세팅해 둔 상태로 뒤늦게 여기 들어온 경우, 막 시작한 프로세스를 즉시 정리한다.
            if (shuttingDown) {
                log.info("종료 처리 중이라 방금 시작한 수집기 데몬을 즉시 종료합니다 (PID: {})", started.pid());
                started.destroyForcibly();
                return;
            }

            long startedAt = System.currentTimeMillis();
            log.info("수집기 데몬 시작 (PID: {}, script: {})", started.pid(), script.getAbsolutePath());

            pipeLogs(started);
            superviseProcess(started, startedAt);

        } catch (Exception e) {
            // IOException뿐 아니라 ProcessBuilder/environment() 등에서 나올 수 있는 어떤
            // 예외라도 여기서 잡아야 한다. 그렇지 않으면 이 메서드가 scheduleRestart()의
            // runAsync 람다 안에서 호출될 때 예외가 그대로 전파되어, 아무도 관찰하지 않는
            // CompletableFuture가 이를 삼켜버리고 복구 루프 전체가 조용히 영구 정지한다.
            log.error("수집기 데몬 시작 실패 (script: {})", scriptPath, e);
            scheduleRestart(System.currentTimeMillis());
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
                startDaemon();
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
