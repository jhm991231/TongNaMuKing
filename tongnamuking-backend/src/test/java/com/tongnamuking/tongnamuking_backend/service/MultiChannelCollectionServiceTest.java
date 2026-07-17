package com.tongnamuking.tongnamuking_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MultiChannelCollectionService의 startCollection 직렬화(finding 1) 검증.
 *
 * CollectorDaemonManager는 실제 프로세스/HTTP를 다루는 concrete 클래스이지만
 * subscribe()/unsubscribe()만 오버라이드한 가짜(FakeCollectorDaemonManager)로
 * 대체해, 실제 데몬/네트워크 없이 서비스 로직만 테스트한다.
 */
class MultiChannelCollectionServiceTest {

    private ChannelSubscriptionRegistry registry;
    private FakeCollectorDaemonManager daemonManager;
    private MultiChannelCollectionService service;

    @BeforeEach
    void setUp() {
        registry = new ChannelSubscriptionRegistry();
        daemonManager = new FakeCollectorDaemonManager();
        service = new MultiChannelCollectionService(registry, daemonManager);
    }

    @Test
    void 첫_구독자만_데몬_구독을_한번_호출한다() {
        assertThat(service.startCollection("client-A", "channel-1")).isTrue();
        assertThat(service.startCollection("client-B", "channel-1")).isTrue();

        assertThat(daemonManager.subscribeCalls()).containsExactly("channel-1");
    }

    @Test
    void 마지막_구독자만_데몬_해제를_한번_호출한다() {
        service.startCollection("client-A", "channel-1");
        service.startCollection("client-B", "channel-1");

        assertThat(service.stopCollection("client-A", "channel-1")).isTrue();
        assertThat(daemonManager.unsubscribeCalls()).isEmpty();

        assertThat(service.stopCollection("client-B", "channel-1")).isTrue();
        assertThat(daemonManager.unsubscribeCalls()).containsExactly("channel-1");
    }

    /**
     * finding 1의 핵심 시나리오:
     * A가 첫 구독자로 판정되어 데몬 구독 HTTP 호출 중(블로킹)일 때 B가 같은 채널에 들어오면,
     * B는 A의 결과가 확정될 때까지 대기해야 한다. A의 구독이 실패해 롤백되면,
     * B는 그 사실을 보고 나서야 (registry가 비어있음을 보고) 자신이 새로운 첫 구독자임을
     * 올바르게 판정해 데몬을 직접 호출해야 한다 — "구독자는 있으나 커넥션은 없는" 유령 상태가
     * 생기면 안 된다.
     */
    @Test
    void 첫_구독자의_데몬_구독이_실패하면_대기중이던_동시_참가자도_올바르게_처리된다() throws Exception {
        CountDownLatch aEnteredSubscribe = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicBoolean rollbackVisibleToB = new AtomicBoolean(false);

        daemonManager.setSubscribeBehavior(channelId -> {
            int call = callCount.incrementAndGet();
            if (call == 1) {
                // A: 데몬 구독 요청이 진행 중인 상태를 흉내낸다 (~1초짜리 동기 HTTP 호출).
                aEnteredSubscribe.countDown();
                awaitQuietly(releaseA);
                return false; // A의 데몬 구독 실패
            }
            // B의 구독 시도 시점에는, 락이 직렬화를 제대로 했다면
            // A의 롤백(registry.remove)이 이미 끝나 있어야 한다.
            rollbackVisibleToB.set(!registry.isSubscribed(channelId, "client-A"));
            return true; // B가 새로운 첫 구독자로서 성공
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> resultA = pool.submit(() -> service.startCollection("client-A", "channel-1"));
            assertThat(aEnteredSubscribe.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> resultB = pool.submit(() -> service.startCollection("client-B", "channel-1"));
            // B는 A가 채널 락을 쥐고 있는 동안 진행되면 안 된다 (직렬화 증명).
            Thread.sleep(300);
            assertThat(resultB.isDone()).isFalse();

            releaseA.countDown();

            assertThat(resultA.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(resultB.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
        }

        assertThat(rollbackVisibleToB.get())
                .as("B가 데몬을 호출하는 시점에는 A의 롤백이 이미 registry에 반영되어 있어야 한다")
                .isTrue();
        assertThat(registry.getSubscribers("channel-1")).containsExactly("client-B");
        assertThat(daemonManager.subscribeCalls()).containsExactly("channel-1", "channel-1");
    }

    /**
     * finding 1의 두 번째 시나리오 (stop이 start와 경합하는 경우):
     * 채널의 마지막 구독자 A가 나가는 stopCollection이 registry.remove()로 "마지막 구독자"라고
     * 판정한 뒤 데몬 해제(unsubscribe) HTTP 호출 중(블로킹)일 때, 새 구독자 B가 같은 채널에
     * 들어오면 B는 A의 stopCollection이 완전히 끝날 때까지 대기해야 한다. 그래야 B가 registry를
     * 볼 때는 이미 비어 있는 상태이므로 스스로를 새로운 첫 구독자로 올바르게 판정해 데몬을
     * 직접 재구독한다. 만약 stop이 채널 락 없이 진행된다면(수정 전 상태), A의 registry.remove()가
     * 끝난 직후 A의 unsubscribe() 호출이 아직 데몬에 도달하기 전에 B의 registry.add()가 먼저
     * 실행되어 "첫 구독자"로 판정되고 곧바로 daemonManager.subscribe()를 호출할 수 있다 — 이후 두
     * HTTP 호출(A의 DELETE, B의 POST)의 도착 순서가 뒤바뀌면, registry에는 B가 구독자로 남아
     * 있지만 데몬 커넥션은 없는 유령 상태가 재현된다.
     */
    @Test
    void 정지가_시작과_경합해도_유령_구독자를_남기지_않는다() throws Exception {
        // 채널에 client-A만 구독 중인 상태를 만든다 (데몬 구독 1회 완료).
        assertThat(service.startCollection("client-A", "channel-1")).isTrue();
        assertThat(daemonManager.subscribeCalls()).containsExactly("channel-1");

        CountDownLatch enteredUnsubscribe = new CountDownLatch(1);
        CountDownLatch releaseUnsubscribe = new CountDownLatch(1);

        daemonManager.setUnsubscribeBehavior(channelId -> {
            // A: 데몬 해제 요청이 진행 중인 상태를 흉내낸다 (동기 HTTP 호출).
            enteredUnsubscribe.countDown();
            awaitQuietly(releaseUnsubscribe);
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> resultStop = pool.submit(() -> service.stopCollection("client-A", "channel-1"));
            assertThat(enteredUnsubscribe.await(5, TimeUnit.SECONDS)).isTrue();

            // A의 registry.remove()는 이미 끝났고(레지스트리는 비어 있음), unsubscribe() HTTP 호출만
            // 블로킹 중인 시점이다. 채널 락이 stop도 직렬화한다면 B는 여기서 진행되면 안 된다.
            Future<Boolean> resultStart = pool.submit(() -> service.startCollection("client-B", "channel-1"));
            Thread.sleep(300);
            assertThat(resultStart.isDone())
                    .as("stop이 채널 락을 쥐고 있는 동안 start는 진행되면 안 된다")
                    .isFalse();

            releaseUnsubscribe.countDown();

            assertThat(resultStop.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(resultStart.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
        }

        // 핵심 불변식: registry에 구독자가 있다면 반드시 데몬에도 구독이 걸려 있어야 한다.
        // B는 A의 stop이 완전히 끝난 뒤에야 진행되었으므로 스스로 새 첫 구독자로 판정되어
        // 데몬 subscribe를 다시(두 번째로) 호출했어야 한다.
        assertThat(registry.getSubscribers("channel-1")).containsExactly("client-B");
        assertThat(daemonManager.subscribeCalls()).containsExactly("channel-1", "channel-1");
        assertThat(daemonManager.unsubscribeCalls()).containsExactly("channel-1");
    }

    @Test
    void 서로_다른_채널은_서로_대기시키지_않는다() throws Exception {
        CountDownLatch channel1Entered = new CountDownLatch(1);
        CountDownLatch releaseChannel1 = new CountDownLatch(1);

        daemonManager.setSubscribeBehavior(channelId -> {
            if ("channel-1".equals(channelId)) {
                channel1Entered.countDown();
                awaitQuietly(releaseChannel1);
            }
            return true;
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> resultChannel1 = pool.submit(() -> service.startCollection("client-A", "channel-1"));
            assertThat(channel1Entered.await(5, TimeUnit.SECONDS)).isTrue();

            // channel-2는 channel-1과 무관하므로 channel-1이 블로킹 중이어도 즉시 끝나야 한다.
            Future<Boolean> resultChannel2 = pool.submit(() -> service.startCollection("client-B", "channel-2"));
            assertThat(resultChannel2.get(2, TimeUnit.SECONDS)).isTrue();

            releaseChannel1.countDown();
            assertThat(resultChannel1.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 채널_락_맵은_사용후_정리되어_무한정_커지지_않는다() throws Exception {
        service.startCollection("client-A", "channel-1");
        service.stopCollection("client-A", "channel-1");

        service.startCollection("client-B", "channel-2");
        service.startCollection("client-C", "channel-2");
        service.stopCollection("client-B", "channel-2");
        service.stopCollection("client-C", "channel-2");

        Field field = MultiChannelCollectionService.class.getDeclaredField("channelLocks");
        field.setAccessible(true);
        Map<String, ?> locks = (Map<String, ?>) field.get(service);

        assertThat(locks)
                .as("사용이 끝난 채널의 락 엔트리는 맵에서 제거되어야 한다")
                .isEmpty();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /**
     * CollectorDaemonManager는 인터페이스가 아니라 concrete 클래스라서,
     * 실제 프로세스/HTTP를 다루는 부분을 그대로 두고 subscribe()/unsubscribe()만
     * 오버라이드해 가짜로 만든다. registry 참조는 오버라이드한 두 메서드 안에서
     * 쓰지 않으므로 null로 넘겨도 안전하다.
     */
    private static final class FakeCollectorDaemonManager extends CollectorDaemonManager {

        private final List<String> subscribeCalls = Collections.synchronizedList(new ArrayList<>());
        private final List<String> unsubscribeCalls = Collections.synchronizedList(new ArrayList<>());
        private volatile Function<String, Boolean> subscribeBehavior = channelId -> true;
        private volatile java.util.function.Consumer<String> unsubscribeBehavior = channelId -> {
        };

        FakeCollectorDaemonManager() {
            super(null);
        }

        void setSubscribeBehavior(Function<String, Boolean> behavior) {
            this.subscribeBehavior = behavior;
        }

        void setUnsubscribeBehavior(java.util.function.Consumer<String> behavior) {
            this.unsubscribeBehavior = behavior;
        }

        List<String> subscribeCalls() {
            return List.copyOf(subscribeCalls);
        }

        List<String> unsubscribeCalls() {
            return List.copyOf(unsubscribeCalls);
        }

        @Override
        public boolean subscribe(String channelId) {
            subscribeCalls.add(channelId);
            return subscribeBehavior.apply(channelId);
        }

        @Override
        public void unsubscribe(String channelId) {
            unsubscribeCalls.add(channelId);
            unsubscribeBehavior.accept(channelId);
        }
    }
}
