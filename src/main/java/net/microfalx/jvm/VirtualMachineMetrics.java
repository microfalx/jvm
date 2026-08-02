package net.microfalx.jvm;

import net.microfalx.jvm.model.GarbageCollection;
import net.microfalx.jvm.model.Process;
import net.microfalx.jvm.model.ThreadInformation;
import net.microfalx.jvm.model.VirtualMachine;
import net.microfalx.metrics.Batch;
import net.microfalx.metrics.Metric;
import net.microfalx.metrics.statistics.MutableStatisticalSummary;
import net.microfalx.metrics.statistics.TimeWindowStatisticalSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.DoubleSummaryStatistics;
import java.util.LongSummaryStatistics;

/**
 * A singleton class which collects JVM metrics and stores them in the store.
 */
public final class VirtualMachineMetrics extends AbstractMetrics<VirtualMachine, VirtualMachineCollector> {

    private static final Logger LOGGER = LoggerFactory.getLogger(VirtualMachineMetrics.class);

    private static final VirtualMachineMetrics instance = new VirtualMachineMetrics();
    private final VirtualMachineCollector collector = new VirtualMachineCollector(VirtualMachineMBeanServer.local());

    private volatile VirtualMachine last = new VirtualMachine();

    private final MutableStatisticalSummary memoryEdenSummary = new TimeWindowStatisticalSummary(getInterval());
    private final MutableStatisticalSummary memoryTenuredSummary = new TimeWindowStatisticalSummary(getInterval());
    private final MutableStatisticalSummary memoryMetaspaceSummary = new TimeWindowStatisticalSummary(getInterval());

    private final MutableStatisticalSummary cpuUserSummary = new TimeWindowStatisticalSummary(getInterval());
    private final MutableStatisticalSummary cpuSystemSummary = new TimeWindowStatisticalSummary(getInterval());

    private final MutableStatisticalSummary gcEdenDurationSummary = new TimeWindowStatisticalSummary(getInterval());
    private final MutableStatisticalSummary gcTenuredDurationSummary = new TimeWindowStatisticalSummary(getInterval());

    private final MutableStatisticalSummary ioReadBytesSummary = new TimeWindowStatisticalSummary(getInterval());
    private final MutableStatisticalSummary ioWriteBytesSummary = new TimeWindowStatisticalSummary(getInterval());

    private final MutableStatisticalSummary threadSummary = new TimeWindowStatisticalSummary(getInterval());
    private final MutableStatisticalSummary fileDescriptorsSummary = new TimeWindowStatisticalSummary(getInterval());

    private final DoubleSummaryStatistics cpuStatistics = new DoubleSummaryStatistics();
    private final LongSummaryStatistics heapStatistics = new LongSummaryStatistics();
    private final LongSummaryStatistics nonHeapStatistics = new LongSummaryStatistics();

    /**
     * Returns the global instance.
     *
     * @return a non-null instance
     */
    public static VirtualMachineMetrics get() {
        return instance;
    }

    /**
     * Returns the average used CPU since startup.
     *
     * @return the CPU, between 0 and 100
     */
    public float getAverageCpuSinceStartup() {
        synchronized (lock) {
            return (float) cpuStatistics.getAverage();
        }
    }

    /**
     * Returns the average eden memory used over the last monitoring interval.
     *
     * @return average bytes used
     * @see #getAverageInterval()
     */
    public long getAverageEdenMemory() {
        synchronized (lock) {
            return (long) memoryEdenSummary.getMean();
        }
    }

    /**
     * Returns the average tenured memory used over the last monitoring interval.
     *
     * @return average bytes used
     * @see #getAverageInterval()
     */
    public long getAverageTenuredMemory() {
        synchronized (lock) {
            return (long) memoryTenuredSummary.getMean();
        }
    }

    /**
     * Returns the average metaspace memory used over the last monitoring interval.
     *
     * @return average bytes used
     * @see #getAverageInterval()
     */
    public long getAverageMetaspaceMemory() {
        synchronized (lock) {
            return (long) memoryMetaspaceSummary.getMean();
        }
    }

    /**
     * Returns the average user CPU used over the last monitoring interval.
     *
     * @return the CPU percentage, between 0 and 100
     * @see #getAverageInterval()
     */
    public float getAverageCpuUser() {
        synchronized (lock) {
            return (float) cpuUserSummary.getMean();
        }
    }

    /**
     * Returns the average system CPU used over the last monitoring interval.
     *
     * @return the CPU percentage, between 0 and 100
     * @see #getAverageInterval()
     */
    public float getAverageCpuSystem() {
        synchronized (lock) {
            return (float) cpuSystemSummary.getMean();
        }
    }

    /**
     * Returns the average eden GC duration over the last monitoring interval.
     *
     * @return average duration in milliseconds
     * @see #getAverageInterval()
     */
    public Duration getAverageGcEdenDuration() {
        synchronized (lock) {
            return Duration.ofMillis((long) gcEdenDurationSummary.getMean());
        }
    }

    /**
     * Returns the average tenured GC duration over the last monitoring interval.
     *
     * @return average duration in milliseconds
     * @see #getAverageInterval()
     */
    public Duration getAverageGcTenuredDuration() {
        synchronized (lock) {
            return Duration.ofMillis((long) gcTenuredDurationSummary.getMean());
        }
    }

    /**
     * Returns the average I/O read bytes over the last monitoring interval.
     *
     * @return average bytes read
     * @see #getAverageInterval()
     */
    public long getAverageIoReadBytes() {
        synchronized (lock) {
            return (long) ioReadBytesSummary.getMean();
        }
    }

    /**
     * Returns the average I/O write bytes over the last monitoring interval.
     *
     * @return average bytes written
     * @see #getAverageInterval()
     */
    public long getAverageIoWriteBytes() {
        synchronized (lock) {
            return (long) ioWriteBytesSummary.getMean();
        }
    }

    /**
     * Returns the average OS thread count over the last monitoring interval.
     *
     * @return average number of threads
     * @see #getAverageInterval()
     */
    public int getAverageThreads() {
        synchronized (lock) {
            return (int) threadSummary.getMean();
        }
    }

    /**
     * Returns the average OS file descriptors count over the last monitoring interval.
     *
     * @return average number of threads
     * @see #getAverageInterval()
     */
    public int getAverageFileDescriptors() {
        synchronized (lock) {
            return (int) fileDescriptorsSummary.getMean();
        }
    }

    /**
     * Returns the maximum memory size (HEAP and NON-HEAP).
     *
     * @return a positive integer
     */
    public long getMemoryMaximum() {
        return getHeapMemoryMaximum() + getNonHeapMemoryMaximum();
    }

    /**
     * Returns the average memory usage.
     *
     * @return the value in bytes
     */
    public long getMemoryAverage() {
        return getHeapMemoryAverageSinceStartup() + getNonHeapMemoryAverageSinceStartup();
    }

    /**
     * Returns the maximum size of HEAP memory.
     *
     * @return the value in bytes
     */
    public long getHeapMemoryMaximum() {
        return last.getHeapTotalMemory();
    }

    /**
     * Returns the average HEAP usage.
     *
     * @return the value in bytes
     */
    public long getHeapMemoryAverageSinceStartup() {
        synchronized (lock) {
            return (long) heapStatistics.getAverage();
        }
    }

    /**
     * Returns the maximum size of NON_HEAP memory.
     *
     * @return the value in bytes
     */
    public long getNonHeapMemoryMaximum() {
        return last.getNonHeapTotalMemory();
    }

    /**
     * Returns the average NON_HEAP usage.
     *
     * @return the value in bytes
     */
    public long getNonHeapMemoryAverageSinceStartup() {
        synchronized (lock) {
            return (long) nonHeapStatistics.getAverage();
        }
    }

    /**
     * Returns the last virtual machine collected.
     *
     * @return a non-null instance
     */
    public VirtualMachine getLast() {
        if (last == null) last = VirtualMachine.get();
        return last;
    }

    @Override
    protected String getMetricsName() {
        return "JVM";
    }

    @Override
    protected void collectMetrics(Batch batch) {
        VirtualMachine virtualMachine = collector.execute();
        synchronized (lock) {
            collectMemory(virtualMachine, batch);
            collectCpu(virtualMachine, batch);
            collectGc(virtualMachine, batch);
            collectThread(virtualMachine, batch);
            collectIo(virtualMachine, batch);
            updateStatistics(virtualMachine);
            this.last = virtualMachine;
        }
    }

    private void collectMemory(VirtualMachine vm, Batch batch) {
        batch.add(MEMORY_HEAP_MAX, vm.getHeapTotalMemory());
        batch.add(MEMORY_HEAP_USED, vm.getHeapUsedMemory());
        batch.add(MEMORY_NON_HEAP_MAX, vm.getNonHeapTotalMemory());
        batch.add(MEMORY_NON_HEAP_USED, vm.getNonHeapUsedMemory());
        batch.add(MEMORY_EDEN_USED, vm.getEdenMemoryPool().getUsed());
        batch.add(MEMORY_EDEN_MAX, vm.getEdenMemoryPool().getMaximum());
        batch.add(MEMORY_TENURED_MAX, vm.getTenuredMemoryPool().getMaximum());
        batch.add(MEMORY_TENURED_USED, vm.getTenuredMemoryPool().getUsed());

        memoryEdenSummary.add(vm.getEdenMemoryPool().getUsed());
        memoryTenuredSummary.add(vm.getTenuredMemoryPool().getUsed());
        memoryMetaspaceSummary.add(vm.getMetapaceMemoryPool().getUsed());
    }

    private void collectCpu(VirtualMachine vm, Batch batch) {
        Process process = vm.getProcess();
        batch.add(CPU_TOTAL, process.getCpuTotal());
        batch.add(CPU_USER, process.getCpuUser());
        batch.add(CPU_SYSTEM, process.getCpuSystem());
        batch.add(CPU_IO_WAIT, process.getCpuIoWait());

        cpuUserSummary.add(process.getCpuUser());
        cpuSystemSummary.add(process.getCpuSystem());
    }

    private void collectThread(VirtualMachine vm, Batch batch) {
        ThreadInformation threadInformation = vm.getThreadInformation();
        batch.add(THREAD, vm.getProcess().getThreads());
        batch.add(THREAD_DAEMON, threadInformation.getDaemon());
        batch.add(THREAD_NON_DAEMON, threadInformation.getNonDaemon());

        threadSummary.add(vm.getProcess().getThreads());
    }

    private void collectOther(VirtualMachine vm, Batch batch) {
        Process process = vm.getProcess();
        batch.add(FILE_DESCRIPTORS, process.getFileDescriptors());

        fileDescriptorsSummary.add(process.getThreads());
    }

    private void collectGc(VirtualMachine vm, Batch batch) {
        GarbageCollection eden = vm.getGarbageCollection(GarbageCollection.Type.EDEN);
        batch.add(GC_EDEN_COUNT, eden.getCount());
        batch.add(GC_EDEN_DURATION, eden.getDuration());
        GarbageCollection tenured = vm.getGarbageCollection(GarbageCollection.Type.TENURED);
        batch.add(GC_TENURED_COUNT, tenured.getCount());
        batch.add(GC_TENURED_DURATION, tenured.getDuration());

        gcEdenDurationSummary.add(eden.getDuration());
        gcTenuredDurationSummary.add(tenured.getDuration());
    }

    private void collectIo(VirtualMachine vm, Batch batch) {
        Process process = vm.getProcess();
        batch.add(IO_READ_BYTES, process.getBytesRead());
        batch.add(IO_WRITE_BYTES, process.getBytesWritten());

        ioReadBytesSummary.add(process.getBytesRead());
        ioWriteBytesSummary.add(process.getBytesWritten());
    }

    private void updateStatistics(VirtualMachine vm) {
        Process process = vm.getProcess();
        cpuStatistics.accept(process.getCpuTotal());
        heapStatistics.accept(vm.getHeapUsedMemory());
        nonHeapStatistics.accept(vm.getNonHeapUsedMemory());
    }

    public static final String METRIC_PREFIX = "jvm.";

    public static final Metric MEMORY_HEAP_MAX = Metric.get(METRIC_PREFIX + "memory.heap.max").withGroup("Heap").withDisplayName("Maximum");
    public static final Metric MEMORY_HEAP_USED = Metric.get(METRIC_PREFIX + "memory.heap.used").withGroup("Heap").withDisplayName("Used");
    public static final Metric MEMORY_NON_HEAP_MAX = Metric.get(METRIC_PREFIX + "memory.non_heap.max").withGroup("NonHeap").withDisplayName("Maximum");
    public static final Metric MEMORY_NON_HEAP_USED = Metric.get(METRIC_PREFIX + "memory.non_heap.used").withGroup("NonHeap").withDisplayName("Used");
    public static final Metric MEMORY_EDEN_MAX = Metric.get(METRIC_PREFIX + "memory.eden.max").withGroup("Eden").withDisplayName("Maximum");
    public static final Metric MEMORY_EDEN_USED = Metric.get(METRIC_PREFIX + "memory.eden.used").withGroup("Eden").withDisplayName("Used");
    public static final Metric MEMORY_TENURED_MAX = Metric.get(METRIC_PREFIX + "memory.tenured.max").withGroup("Tenured").withDisplayName("Maximum");
    public static final Metric MEMORY_TENURED_USED = Metric.get(METRIC_PREFIX + "memory.tenured.used").withGroup("Tenured").withDisplayName("Used");

    public static final Metric CPU_TOTAL = Metric.get(METRIC_PREFIX + "cpu.total").withGroup("CPU").withDisplayName("Total");
    public static final Metric CPU_USER = Metric.get(METRIC_PREFIX + "cpu.user").withGroup("CPU").withDisplayName("User");
    public static final Metric CPU_SYSTEM = Metric.get(METRIC_PREFIX + "cpu.system").withGroup("CPU").withDisplayName("System");
    public static final Metric CPU_IO_WAIT = Metric.get(METRIC_PREFIX + "cpu.io_wait").withGroup("CPU").withDisplayName("I/O Wait");

    public static final Metric GC_EDEN_COUNT = Metric.get(METRIC_PREFIX + "gc.eden.count").withGroup("GC").withDisplayName("Eden Count").withType(Metric.Type.COUNTER);
    public static final Metric GC_EDEN_DURATION = Metric.get(METRIC_PREFIX + "gc.eden.duration").withGroup("GC").withDisplayName("Eden Duration").withType(Metric.Type.COUNTER);
    public static final Metric GC_TENURED_COUNT = Metric.get(METRIC_PREFIX + "gc.tenured.count").withGroup("GC").withDisplayName("Tenured Count").withType(Metric.Type.COUNTER);
    public static final Metric GC_TENURED_DURATION = Metric.get(METRIC_PREFIX + "gc.tenured.duration").withGroup("GC").withDisplayName("Tenured Duration").withType(Metric.Type.COUNTER);

    public static final Metric IO_READ_BYTES = Metric.get(METRIC_PREFIX + "io.read.bytes").withGroup("I/O").withDisplayName("Read Bytes").withType(Metric.Type.COUNTER);
    public static final Metric IO_WRITE_BYTES = Metric.get(METRIC_PREFIX + "io.write.bytes").withGroup("I/O").withDisplayName("Write Bytes").withType(Metric.Type.COUNTER);

    public static final Metric THREAD = Metric.get(METRIC_PREFIX + "thread").withGroup("Thread").withDisplayName("OS");
    public static final Metric THREAD_DAEMON = Metric.get(METRIC_PREFIX + "thread.daemon").withGroup("Thread").withDisplayName("Daemon");
    public static final Metric THREAD_NON_DAEMON = Metric.get(METRIC_PREFIX + "thread.non_daemon").withGroup("Thread").withDisplayName("Non Daemon");

    public static final Metric FILE_DESCRIPTORS = Metric.get(METRIC_PREFIX + "file_descriptors").withGroup("Other").withDisplayName("File Descriptors");
}
