package net.microfalx.jvm;

import net.microfalx.jvm.model.Server;
import net.microfalx.lang.ThreadUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static java.time.Duration.ofSeconds;
import static net.microfalx.jvm.ServerMetrics.*;
import static net.microfalx.lang.FormatterUtils.formatPercent;
import static net.microfalx.lang.ThreadUtils.sleepSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerMetricsTest extends AbstractMetricsTest {

    private ServerMetrics metrics;

    @BeforeEach
    public void setup() {
        metrics = (ServerMetrics) new ServerMetrics().useMemory();
        metrics.clear();
    }

    @Test
    public void scrape() {
        metrics.scrape();
        assertFalse(metrics.getStore().getMetrics().isEmpty());
    }

    @Test
    public void start() {
        metrics.start();
        ThreadUtils.sleepSeconds(5);
        metrics.stop();
    }

    @Test
    public void memory() {
        scrapeInLoop();
        assertTrue(metrics.getStore().getAverage(MEMORY_MAX, ofSeconds(60)).orElse(0) > 0);
        assertTrue(metrics.getStore().getAverage(MEMORY_USED, ofSeconds(60)).orElse(0) > 0);
        assertTrue(metrics.getStore().getAverage(MEMORY_ACTUALLY_USED, ofSeconds(60)).orElse(0) > 0);
        assertTrue(metrics.getAverageMemory() > 0);
        assertTrue(metrics.getAverageMemoryUsed() > 0);
        assertTrue(metrics.getAverageMemoryActuallyUsed() > 0);
    }

    @Test
    public void cpu() {
        scrapeInLoop();
        assertTrue(metrics.getStore().getAverage(CPU_TOTAL, ofSeconds(60)).orElse(0) > 0);
        assertTrue(metrics.getStore().getAverage(CPU_SYSTEM, ofSeconds(60)).orElse(0) > 0);
        assertTrue(metrics.getStore().getAverage(CPU_USER, ofSeconds(60)).orElse(0) > 0);
        assertTrue(metrics.getAverageTotalCpuSinceStartup() > 0);
        assertTrue(metrics.getAverageTotalCpu() > 0);
        assertTrue(metrics.getAverageUserCpu() > 0);
        assertTrue(metrics.getAverageSystemCpu() > 0);
        assertTrue(metrics.getAverageIoWaitCpu() >= 0);
        assertTrue(metrics.getAverageNiceCpu() >= 0);
    }

    @Test
    public void contextSwitches() {
        scrapeInLoop();
        assertThat(metrics.getStore().getAverage(CONTEXT_SWITCHES, ofSeconds(60)).orElse(0)).isBetween(5000d, 200_000d);
    }

    @Test
    public void cpuUser() {
        startBusyThreads(4);
        scrapeInLoop();
        double avgCpu = metrics.getStore().getAverage(CPU_USER, ofSeconds(60)).orElse(0);
        assertTrue(avgCpu > 400);
    }

    @Disabled
    @Test
    public void realTimeCpu() {
        startBusyThreads(4);
        for (; ; ) {
            metrics.scrape();
            Server server = metrics.getLast();
            ThreadUtils.sleepSeconds(1);
            System.out.println("CPU: System " + formatPercent(server.getCpuSystem())
                               + ", User " + formatPercent(server.getCpuUser()));
        }
    }

    private void scrapeInLoop() {
        scrapeInLoop(5);
    }

    private void scrapeInLoop(int iterations) {
        for (int i = 0; i < iterations; i++) {
            metrics.scrape();
            sleepSeconds(1);
        }
    }

}