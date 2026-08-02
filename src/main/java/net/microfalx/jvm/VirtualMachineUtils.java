package net.microfalx.jvm;

import net.microfalx.lang.TimeUtils;
import net.microfalx.metrics.Metrics;
import oshi.software.common.os.linux.LinuxFileSystem;

public class VirtualMachineUtils {

    public final static Metrics JVM_METRICS = Metrics.ROOT.withGroup("JVM");
    public final static Metrics COLLECTOR_METRICS = JVM_METRICS.withGroup("Collector");
    public final static Metrics METRICS_METRICS = JVM_METRICS.withGroup("Metrics");

    /**
     * Calculate the usage time in percent based on uptime and actual usage.
     *
     * @param prevTime the previous time reference in nanos
     * @param usage    the usage in milliseconds
     * @return the usage as percentage
     */
    public static float getUsageAtNow(long prevTime, long usage) {
        return getUsage(System.nanoTime() - prevTime, usage);
    }

    /**
     * Calculate the usage time in percent based on uptime and actual usage.
     *
     * @param duration the duration of the time interval in nanos
     * @param usage    the usage in milliseconds
     * @return the usage as percentage
     */
    public static float getUsage(long duration, long usage) {
        usage *= TimeUtils.NANOSECONDS_IN_MILLISECONDS;
        return duration > 0 ? (float) (100 * (double) usage / duration) : 0;
    }

    static {
        System.setProperty(LinuxFileSystem.OSHI_LINUX_FS_PATH_EXCLUDES, "/var/lib/kubelet/**,/run/docker/**,/run/k3s/**");
    }
}
