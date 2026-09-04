package org.km.llmwiki.testsupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reports Spring TestContext cache reuse and statistics for the test JVM.
 *
 * <p>The identity set is deliberately maintained independently from Spring's implementation
 * details. When the current Spring TestContext implementation exposes its default
 * {@code ContextCache}, the listener also reports exact cache hit/miss counters. This is test
 * observability only; a cache instrumentation failure must never change test behavior.
 */
public final class ContextCacheMetricsListener extends AbstractTestExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ContextCacheMetricsListener.class);
    private static final Set<Integer> CONTEXT_IDENTITIES = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger OBSERVED_CLASSES = new AtomicInteger();
    private static final AtomicInteger REUSED_CLASSES = new AtomicInteger();
    private static final SpringContextCacheStatistics SPRING_CACHE = SpringContextCacheStatistics.load();

    @Override
    public void beforeTestClass(TestContext testContext) {
        if (!testContext.hasApplicationContext()) {
            return;
        }

        ApplicationContext context = testContext.getApplicationContext();
        boolean reused = !CONTEXT_IDENTITIES.add(System.identityHashCode(context));
        int observedClasses = OBSERVED_CLASSES.incrementAndGet();
        if (reused) {
            REUSED_CLASSES.incrementAndGet();
        }

        log.info("Spring test context observed: testClass={}, contextId={}, reused={}, "
                        + "observedClasses={}, reusedClasses={}, cacheHits={}, cacheMisses={}, cacheSize={}",
                testContext.getTestClass().getSimpleName(),
                System.identityHashCode(context),
                reused,
                observedClasses,
                REUSED_CLASSES.get(),
                SPRING_CACHE.hitCount(),
                SPRING_CACHE.missCount(),
                SPRING_CACHE.size());
    }

    private record SpringContextCacheStatistics(Object cache, java.lang.reflect.Method hitCountMethod,
                                                  java.lang.reflect.Method missCountMethod,
                                                  java.lang.reflect.Method sizeMethod) {

        static SpringContextCacheStatistics load() {
            try {
                Field field = Class.forName(
                                "org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate")
                        .getDeclaredField("defaultContextCache");
                if (!field.trySetAccessible()) {
                    return unavailable();
                }
                Object cache = field.get(null);
                Class<?> cacheType = Class.forName("org.springframework.test.context.cache.ContextCache");
                return new SpringContextCacheStatistics(cache,
                        cacheType.getMethod("getHitCount"),
                        cacheType.getMethod("getMissCount"),
                        cacheType.getMethod("size"));
            } catch (ReflectiveOperationException | RuntimeException exception) {
                log.debug("Spring ContextCache statistics are unavailable", exception);
                return unavailable();
            }
        }

        public int hitCount() {
            return invoke(hitCountMethod);
        }

        public int missCount() {
            return invoke(missCountMethod);
        }

        public int size() {
            return invoke(sizeMethod);
        }

        private int invoke(java.lang.reflect.Method method) {
            if (cache == null || method == null) {
                return -1;
            }
            try {
                return (Integer) method.invoke(cache);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return -1;
            }
        }

        private static SpringContextCacheStatistics unavailable() {
            return new SpringContextCacheStatistics(null, null, null, null);
        }
    }
}
