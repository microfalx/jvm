package net.microfalx.jvm;

import net.microfalx.jvm.model.Process;
import net.microfalx.jvm.model.VirtualMachine;
import net.microfalx.lang.ThreadUtils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static java.time.Duration.ofSeconds;
import static net.microfalx.lang.FormatterUtils.formatPercent;
import static net.microfalx.lang.ThreadUtils.sleepMillis;
import static net.microfalx.lang.ThreadUtils.sleepSeconds;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualMachineMetricsTest extends AbstractMetricsTest {

    private VirtualMachineMetrics metrics;

    @BeforeEach
    public void setup() {
        metrics = (VirtualMachineMetrics) new VirtualMachineMetrics().useMemory();
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
        assertTrue(metrics.getStore().getAverage(VirtualMachineMetrics.MEMORY_HEAP_USED, ofSeconds(60)).orElse(0) > 0);
        assertTrue(metrics.getStore().getAverage(VirtualMachineMetrics.MEMORY_NON_HEAP_USED, ofSeconds(60)).orElse(0) > 0);
        assertTrue(metrics.getStore().getAverage(VirtualMachineMetrics.MEMORY_EDEN_USED, ofSeconds(60)).orElse(0) > 0);
        assertTrue(metrics.getStore().getAverage(VirtualMachineMetrics.MEMORY_TENURED_USED, ofSeconds(60)).orElse(0) > 0);
        assertTrue(metrics.getAverageEdenMemory() > 0);
        assertTrue(metrics.getAverageTenuredMemory() > 0);
        assertTrue(metrics.getAverageMetaspaceMemory() > 0);
        assertTrue(metrics.getHeapMemoryAverageSinceStartup() > 0);
        assertTrue(metrics.getNonHeapMemoryAverageSinceStartup() > 0);
        assertTrue(metrics.getMemoryAverage() > 0);
    }

    @Test
    public void averageCpu() {
        scrapeInLoop();
        Assertions.assertThat(metrics.getAverageCpuSinceStartup()).isBetween(10f, 50f);
    }

    @Test
    public void cpu() {
        scrapeInLoop();
        assertTrue(metrics.getStore().getAverage(VirtualMachineMetrics.CPU_TOTAL, ofSeconds(60)).orElse(0) > 0);
        assertTrue(metrics.getStore().getAverage(VirtualMachineMetrics.CPU_USER, ofSeconds(60)).orElse(0) > 0);
        assertTrue(metrics.getStore().getAverage(VirtualMachineMetrics.CPU_SYSTEM, ofSeconds(60)).orElse(0) > 0);
    }

    @Test
    public void gc() {
        scrapeInLoop(3);
        assertTrue(metrics.getStore().getAverage(VirtualMachineMetrics.GC_EDEN_COUNT, ofSeconds(60)).orElse(0) > 0);
        assertTrue(metrics.getStore().getAverage(VirtualMachineMetrics.GC_EDEN_DURATION, ofSeconds(60)).orElse(0) > 0);
        assertTrue(metrics.getStore().getAverage(VirtualMachineMetrics.GC_TENURED_COUNT, ofSeconds(60)).orElse(0) > 0);
        assertTrue(metrics.getStore().getAverage(VirtualMachineMetrics.GC_TENURED_DURATION, ofSeconds(60)).orElse(0) > 0);
    }

    @Test
    public void cpuUser() {
        startBusyThreads(4);
        scrapeInLoop();
        double avgCpu = metrics.getStore().getAverage(VirtualMachineMetrics.CPU_USER, ofSeconds(60)).orElse(0);
        Assertions.assertThat(avgCpu).isBetween(200d, 400d);
    }

    @Disabled
    @Test
    public void realTimeCpu() {
        startBusyThreads(4);
        for (; ; ) {
            metrics.scrape();
            VirtualMachine vm = metrics.getLast();
            ThreadUtils.sleepSeconds(1);
            Process process = vm.getProcess();
            System.out.println("CPU: System " + formatPercent(process.getCpuSystem())
                               + ", User " + formatPercent(process.getCpuUser()));
        }
    }

    private void scrapeInLoop() {
        scrapeInLoop(20);
    }

    private void scrapeInLoop(int iterations) {
        for (int i = 0; i < iterations; i++) {
            metrics.scrape();
            sleepMillis(200);
        }
        sleepSeconds(1);
    }

}