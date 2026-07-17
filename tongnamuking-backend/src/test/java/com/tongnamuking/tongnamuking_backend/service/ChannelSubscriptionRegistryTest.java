package com.tongnamuking.tongnamuking_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelSubscriptionRegistryTest {

    private ChannelSubscriptionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ChannelSubscriptionRegistry();
    }

    @Test
    void 빈_채널에_첫_구독자가_들어오면_true를_반환한다() {
        assertThat(registry.add("channel-1", "client-A")).isTrue();
    }

    @Test
    void 두번째_구독자는_첫_구독자가_아니다() {
        registry.add("channel-1", "client-A");
        assertThat(registry.add("channel-1", "client-B")).isFalse();
    }

    @Test
    void 같은_클라이언트가_중복_구독해도_첫_구독자가_아니다() {
        registry.add("channel-1", "client-A");
        assertThat(registry.add("channel-1", "client-A")).isFalse();
        assertThat(registry.getSubscribers("channel-1")).containsExactly("client-A");
    }

    @Test
    void 구독자가_둘일때_하나를_빼면_마지막이_아니다() {
        registry.add("channel-1", "client-A");
        registry.add("channel-1", "client-B");
        assertThat(registry.remove("channel-1", "client-A")).isFalse();
    }

    @Test
    void 마지막_구독자를_빼면_true를_반환한다() {
        registry.add("channel-1", "client-A");
        assertThat(registry.remove("channel-1", "client-A")).isTrue();
        assertThat(registry.getSubscribers("channel-1")).isEmpty();
    }

    @Test
    void 마지막_구독자가_빠진_채널은_전체_채널_목록에서_사라진다() {
        registry.add("channel-1", "client-A");
        registry.remove("channel-1", "client-A");
        assertThat(registry.getAllChannels()).isEmpty();
    }

    @Test
    void 비어있던_채널에_다시_구독하면_첫_구독자로_판정된다() {
        registry.add("channel-1", "client-A");
        registry.remove("channel-1", "client-A");
        assertThat(registry.add("channel-1", "client-B")).isTrue();
    }

    @Test
    void 구독하지_않은_클라이언트를_빼면_false를_반환한다() {
        registry.add("channel-1", "client-A");
        assertThat(registry.remove("channel-1", "client-Z")).isFalse();
    }

    @Test
    void 존재하지_않는_채널에서_빼도_예외가_나지_않는다() {
        assertThat(registry.remove("nope", "client-A")).isFalse();
    }

    @Test
    void 구독자_조회는_없는_채널에_대해_빈_집합을_준다() {
        assertThat(registry.getSubscribers("nope")).isEmpty();
    }

    @Test
    void 클라이언트가_구독한_채널들을_역방향으로_찾는다() {
        registry.add("channel-1", "client-A");
        registry.add("channel-2", "client-A");
        registry.add("channel-2", "client-B");
        assertThat(registry.getChannelsOf("client-A")).containsExactlyInAnyOrder("channel-1", "channel-2");
        assertThat(registry.getChannelsOf("client-B")).containsExactly("channel-2");
        assertThat(registry.countChannelsOf("client-A")).isEqualTo(2);
        assertThat(registry.countChannelsOf("client-Z")).isZero();
    }

    @Test
    void 구독_여부를_확인한다() {
        registry.add("channel-1", "client-A");
        assertThat(registry.isSubscribed("channel-1", "client-A")).isTrue();
        assertThat(registry.isSubscribed("channel-1", "client-B")).isFalse();
        assertThat(registry.isSubscribed("channel-9", "client-A")).isFalse();
    }

    @Test
    void 사용자당_최대_채널수는_3이다() {
        assertThat(registry.getMaxChannelsPerClient()).isEqualTo(3);
    }

    @Test
    void 반환된_구독자_집합을_수정해도_레지스트리는_영향받지_않는다() {
        registry.add("channel-1", "client-A");
        Set<String> subscribers = registry.getSubscribers("channel-1");
        assertThat(subscribers).isUnmodifiable();
    }

    @Test
    void 동시에_구독해도_첫_구독자_판정은_정확히_한번만_true다() throws Exception {
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger firstCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final String clientId = "client-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (registry.add("channel-1", clientId)) {
                        firstCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(firstCount.get()).isEqualTo(1);
        assertThat(registry.getSubscribers("channel-1")).hasSize(threads);
    }

    @Test
    void 동시에_해제해도_마지막_구독자_판정은_정확히_한번만_true다() throws Exception {
        int threads = 50;
        for (int i = 0; i < threads; i++) {
            registry.add("channel-1", "client-" + i);
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger lastCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final String clientId = "client-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (registry.remove("channel-1", clientId)) {
                        lastCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(lastCount.get()).isEqualTo(1);
        assertThat(registry.getAllChannels()).isEmpty();
    }
}
