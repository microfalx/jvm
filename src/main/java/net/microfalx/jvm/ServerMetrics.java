package net.microfalx.jvm;

import net.microfalx.jvm.model.Server;
import net.microfalx.metrics.Batch;
import net.microfalx.metrics.Metric;
import net.microfalx.metrics.statistics.MutableStatisticalSummary;
import net.microfalx.metrics.statistics.TimeWindowStatisticalSummary;

import java.util.DoubleSummaryStatistics;
import java.util.LongSummaryStatistics;

/**
 * A singleton class which collects JVM metrics and stores them in the store.
 */
public final class ServerMetrics extends AbstractMetrics<Server, ServerCollector> {

    private static final ServerMetrics instance = new ServerMetrics();
    private final ServerCollector collector = new ServerCollector();

    private final MutableStatisticalSummary cpuTotalSummary = new TimeWindowStatisticalSummary(getInterval());
    private final MutableStatisticalSummary cpuUserSummary = new TimeWindowStatisticalSummary(getInterval());
    private final MutableStatisticalSummary cpuSystemSummary = new TimeWindowStatisticalSummary(getInterval());
    private final MutableStatisticalSummary cpuIoWaitSummary = new TimeWindowStatisticalSummary(getInterval());
    private final MutableStatisticalSummary cpuNiceSummary = new TimeWindowStatisticalSummary(getInterval());

    private final MutableStatisticalSummary load1Summary = new TimeWindowStatisticalSummary(getInterval());
    private final MutableStatisticalSummary load5Summary = new TimeWindowStatisticalSummary(getInterval());
    private final MutableStatisticalSummary load15Summary = new TimeWindowStatisticalSummary(getInterval());

    private final MutableStatisticalSummary memoryUsedSummary = new TimeWindowStatisticalSummary(getInterval());
    private final MutableStatisticalSummary memoryActuallyUsedSummary = new TimeWindowStatisticalSummary(getInterval());

    private final MutableStatisticalSummary ioReadBytesSummary = new TimeWindowStatisticalSummary(getInterval());
    private final MutableStatisticalSummary ioWriteBytesSummary = new TimeWindowStatisticalSummary(getInterval());

    private final MutableStatisticalSummary networkReadBytesSummary = new TimeWindowStatisticalSummary(getInterval());
    private final MutableStatisticalSummary networkWriteBytesSummary = new TimeWindowStatisticalSummary(getInterval());

    private final DoubleSummaryStatistics cpuStatistics = new DoubleSummaryStatistics();
    private final DoubleSummaryStatistics loadStatistics = new DoubleSummaryStatistics();
    private final LongSummaryStatistics memoryStatistics = new LongSummaryStatistics();

    private volatile Server last;

    /**
     * Returns the global instance.
     *
     * @return a non-null instance
     */
    public static ServerMetrics get() {
        return instance;
    }

    /**
     * Returns the last virtual machine collected.
     *
     * @return a non-null instance
     */
    public Server getLast() {
        if (last == null) last = Server.get();
        return last;
    }

    /**
     * Returns the average used CPU since process startup.
     *
     * @return the CPU, between 0 and 100
     */
    public float getAverageTotalCpuSinceStartup() {
        return (float) cpuStatistics.getAverage();
    }

    /**
     * Returns the average total CPU over the last monitoring interval.
     *
     * @return average bytes used
     * @see #getAverageInterval()
     */
    public float getAverageTotalCpu() {
        synchronized (lock) {
            return (float) cpuTotalSummary.getMean();
        }
    }

    /**
     * Returns the average user space CPU over the last monitoring interval.
     *
     * @return average bytes used
     * @see #getAverageInterval()
     */
    public float getAverageUserCpu() {
        synchronized (lock) {
            return (float) cpuUserSummary.getMean();
        }
    }

    /**
     * Returns the average system space CPU over the last monitoring interval.
     *
     * @return average bytes used
     * @see #getAverageInterval()
     */
    public float getAverageSystemCpu() {
        synchronized (lock) {
            return (float) cpuSystemSummary.getMean();
        }
    }

    /**
     * Returns the average I/O wait CPU over the last monitoring interval.
     *
     * @return average I/O wait CPU percentage
     * @see #getAverageInterval()
     */
    public float getAverageIoWaitCpu() {
        synchronized (lock) {
            return (float) cpuIoWaitSummary.getMean();
        }
    }

    /**
     * Returns the average nice CPU over the last monitoring interval.
     *
     * @return average nice CPU percentage
     * @see #getAverageInterval()
     */
    public float getAverageNiceCpu() {
        synchronized (lock) {
            return (float) cpuNiceSummary.getMean();
        }
    }

    /**
     * Returns the average 1-minute load average over the last monitoring interval.
     *
     * @return average load (1 minute)
     * @see #getAverageInterval()
     */
    public float getAverageLoad1() {
        synchronized (lock) {
            return (float) load1Summary.getMean();
        }
    }

    /**
     * Returns the average 5-minute load average over the last monitoring interval.
     *
     * @return average load (5 minutes)
     * @see #getAverageInterval()
     */
    public float getAverageLoad5() {
        synchronized (lock) {
            return (float) load5Summary.getMean();
        }
    }

    /**
     * Returns the average 15-minute load average over the last monitoring interval.
     *
     * @return average load (15 minutes)
     * @see #getAverageInterval()
     */
    public float getAverageLoad15() {
        synchronized (lock) {
            return (float) load15Summary.getMean();
        }
    }

    /**
     * Returns the average used memory over the last monitoring interval.
     *
     * @return average bytes used
     * @see #getAverageInterval()
     */
    public long getAverageMemoryUsed() {
        synchronized (lock) {
            return (long) memoryUsedSummary.getMean();
        }
    }

    /**
     * Returns the average actually used memory over the last monitoring interval.
     *
     * @return average bytes actually used
     * @see #getAverageInterval()
     */
    public long getAverageMemoryActuallyUsed() {
        synchronized (lock) {
            return (long) memoryActuallyUsedSummary.getMean();
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
     * Returns the average network read bytes over the last monitoring interval.
     *
     * @return average bytes received
     * @see #getAverageInterval()
     */
    public long getAverageNetworkReadBytes() {
        synchronized (lock) {
            return (long) networkReadBytesSummary.getMean();
        }
    }

    /**
     * Returns the average network write bytes over the last monitoring interval.
     *
     * @return average bytes sent
     * @see #getAverageInterval()
     */
    public long getAverageNetworkWriteBytes() {
        synchronized (lock) {
            return (long) networkWriteBytesSummary.getMean();
        }
    }

    /**
     * Returns the average system load since the process startup.
     *
     * @return the load
     */
    public double getAverageLoadSinceStartup() {
        return loadStatistics.getAverage();
    }

    /**
     * Returns the average memory usage.
     *
     * @return a positive integer
     */
    public long getAverageMemorySinceStartup() {
        return (long) memoryStatistics.getAverage();
    }

    @Override
    protected String getMetricsName() {
        return "Server";
    }

    @Override
    protected void collectMetrics(Batch batch) {
        Server server = collector.execute();
        synchronized (lock) {
            collectMemory(server, batch);
            collectCpu(server, batch);
            collectLoad(server, batch);
            collectIo(server, batch);
            collectNetwork(server, batch);
            collectMisc(server, batch);
            updateStatistics(server);
            this.last = server;
        }
    }

    private void collectMemory(Server server, Batch batch) {
        batch.add(MEMORY_MAX, server.getMemoryTotal());
        batch.add(MEMORY_USED, server.getMemoryUsed());
        batch.add(MEMORY_ACTUALLY_USED, server.getMemoryActuallyUsed());

        memoryUsedSummary.add(server.getMemoryUsed());
        memoryActuallyUsedSummary.add(server.getMemoryActuallyUsed());
    }

    private void collectCpu(Server server, Batch batch) {
        batch.add(CPU_TOTAL, server.getCpuTotal());
        batch.add(CPU_USER, server.getCpuUser());
        batch.add(CPU_SYSTEM, server.getCpuSystem());
        batch.add(CPU_IO_WAIT, server.getCpuIoWait());
        batch.add(CPU_NICE, server.getCpuNice());

        cpuTotalSummary.add(server.getCpuTotal());
        cpuUserSummary.add(server.getCpuUser());
        cpuSystemSummary.add(server.getCpuSystem());
        cpuIoWaitSummary.add(server.getCpuIoWait());
        cpuNiceSummary.add(server.getCpuNice());
    }

    private void collectLoad(Server server, Batch batch) {
        batch.add(LOAD_1, server.getLoad1());
        batch.add(LOAD_5, server.getLoad5());
        batch.add(LOAD_15, server.getLoad15());

        load1Summary.add(server.getLoad1());
        load5Summary.add(server.getLoad5());
        load15Summary.add(server.getLoad15());
    }

    private void collectIo(Server server, Batch batch) {
        batch.add(IO_READS, server.getIoReads());
        batch.add(IO_READ_BYTES, server.getIoReadBytes());
        batch.add(IO_WRITES, server.getIoWrites());
        batch.add(IO_WRITE_BYTES, server.getIoWriteBytes());

        ioReadBytesSummary.add(server.getIoReadBytes());
        ioWriteBytesSummary.add(server.getIoWriteBytes());
    }

    private void collectNetwork(Server server, Batch batch) {
        batch.add(NETWORK_READ_BYTES, server.getNetworkReadBytes());
        batch.add(NETWORK_WRITE_BYTES, server.getNetworkWriteBytes());

        networkReadBytesSummary.add(server.getNetworkReadBytes());
        networkWriteBytesSummary.add(server.getNetworkWriteBytes());
    }

    private void collectMisc(Server server, Batch batch) {
        batch.add(INTERRUPTS, server.getInterrupts());
        batch.add(CONTEXT_SWITCHES, server.getContextSwitches());
    }

    private void updateStatistics(Server server) {
        cpuStatistics.accept(server.getCpuTotal());
        loadStatistics.accept(server.getLoad1());
        memoryStatistics.accept(server.getMemoryActuallyUsed());

    }

    public static final String METRIC_PREFIX = "server.";

    public static final Metric MEMORY_MAX = Metric.get(METRIC_PREFIX + "memory.max").withGroup("Server / Memory").withDisplayName("Maximum");
    public static final Metric MEMORY_USED = Metric.get(METRIC_PREFIX + "memory.used").withGroup("Server / Memory").withDisplayName("Used");
    public static final Metric MEMORY_ACTUALLY_USED = Metric.get(METRIC_PREFIX + "memory.actually.used").withGroup("Server / Memory").withDisplayName("Actually Used");

    public static final Metric CPU_TOTAL = Metric.get(METRIC_PREFIX + "cpu.total").withGroup("CPU").withDisplayName("Total");
    public static final Metric CPU_USER = Metric.get(METRIC_PREFIX + "cpu.user").withGroup("CPU").withDisplayName("User");
    public static final Metric CPU_SYSTEM = Metric.get(METRIC_PREFIX + "cpu.system").withGroup("CPU").withDisplayName("System");
    public static final Metric CPU_IO_WAIT = Metric.get(METRIC_PREFIX + "cpu.io_wait").withGroup("CPU").withDisplayName("I/O Wait");
    public static final Metric CPU_NICE = Metric.get(METRIC_PREFIX + "cpu.nice").withGroup("CPU").withDisplayName("Nice");

    public static final Metric LOAD_1 = Metric.get(METRIC_PREFIX + "load.1").withGroup("Load").withDisplayName("1 Minute");
    public static final Metric LOAD_5 = Metric.get(METRIC_PREFIX + "load.5").withGroup("Load").withDisplayName("5 Minutes");
    public static final Metric LOAD_15 = Metric.get(METRIC_PREFIX + "load.15").withGroup("Load").withDisplayName("15 Minutes");

    public static final Metric IO_READS = Metric.get(METRIC_PREFIX + "io.reads").withGroup("I/O").withDisplayName("Reads").withType(Metric.Type.COUNTER);
    public static final Metric IO_READ_BYTES = Metric.get(METRIC_PREFIX + "io.read.bytes").withGroup("I/O").withDisplayName("Read Bytes").withType(Metric.Type.COUNTER);
    public static final Metric IO_WRITES = Metric.get(METRIC_PREFIX + "io.writes").withGroup("I/O").withDisplayName("Writes").withType(Metric.Type.COUNTER);
    public static final Metric IO_WRITE_BYTES = Metric.get(METRIC_PREFIX + "io.write.bytes").withGroup("I/O").withDisplayName("Write Bytes").withType(Metric.Type.COUNTER);

    public static final Metric NETWORK_READ_BYTES = Metric.get(METRIC_PREFIX + "network.read.bytes").withGroup("Network").withDisplayName("Network Read Bytes").withType(Metric.Type.COUNTER);
    public static final Metric NETWORK_WRITE_BYTES = Metric.get(METRIC_PREFIX + "network.write.bytes").withGroup("Network").withDisplayName("Network Write Bytes").withType(Metric.Type.COUNTER);

    public static final Metric INTERRUPTS = Metric.get(METRIC_PREFIX + "interrupts").withGroup("Kernel").withDisplayName("Interrupts").withType(Metric.Type.COUNTER);
    public static final Metric CONTEXT_SWITCHES = Metric.get(METRIC_PREFIX + "context.switches").withGroup("Kernel").withDisplayName("Context Switches").withType(Metric.Type.COUNTER);
}
