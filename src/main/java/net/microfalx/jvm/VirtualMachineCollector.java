package net.microfalx.jvm;

import net.microfalx.jvm.model.*;
import net.microfalx.jvm.model.Process;
import net.microfalx.lang.StringUtils;
import net.microfalx.metrics.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.lang.management.*;
import java.util.*;

import static net.microfalx.jvm.VirtualMachineUtils.COLLECTOR_METRICS;
import static net.microfalx.lang.ArgumentUtils.requireNonNull;

/**
 * Collects information about a Java VM (process).
 */
public final class VirtualMachineCollector extends AbstractCollector<VirtualMachine> {

    private static final Logger LOGGER = LoggerFactory.getLogger(VirtualMachineCollector.class);

    private static final String OPERATING_SYSTEM_NAME = "java.lang:type=OperatingSystem";

    private final VirtualMachineMBeanServer machineMBeanServer;
    private final SystemInfo systemInfo = new SystemInfo();

    private static volatile CpuTime prevCpuTime;

    public VirtualMachineCollector(VirtualMachineMBeanServer machineMBeanServer) {
        requireNonNull(machineMBeanServer);
        this.machineMBeanServer = machineMBeanServer;
    }

    public VirtualMachine execute() {
        VirtualMachine vm = new VirtualMachine();
        try (Timer ignored = COLLECTOR_METRICS.startTimer("JVM")) {
            vm.setLocal(machineMBeanServer.isLocal());
            collectPid(vm);
            collectProcess(vm);
            collectMemoryStats(vm);
            collectGarbageCollection(vm);
            collectBufferPools(vm);
            collectRuntimeInformation(vm);
            collectThreadInformation(vm);
            if (!isMetadata()) collectThreadDumps(vm);
        }
        return vm;
    }

    public void collectBufferPools(VirtualMachine virtualMachine) {
        Collection<BufferPoolMXBean> bufferPoolMXBeans = machineMBeanServer.getPlatformMXBeans(BufferPoolMXBean.class);
        Collection<BufferPool> bufferPools = new ArrayList<>();
        for (BufferPoolMXBean bufferPoolMXBean : bufferPoolMXBeans) {
            BufferPool.Type type = guessBufferPoolType(bufferPoolMXBean);
            bufferPools.add(new BufferPool(type, (int) bufferPoolMXBean.getCount(), bufferPoolMXBean.getTotalCapacity(), bufferPoolMXBean.getMemoryUsed()));
        }
        virtualMachine.setBufferPools(bufferPools);
    }

    public void collectGarbageCollection(VirtualMachine virtualMachine) {
        Collection<GarbageCollection> stats = new ArrayList<>();
        Collection<GarbageCollectorMXBean> garbageCollectorMXBeans = machineMBeanServer.getPlatformMXBeans(GarbageCollectorMXBean.class);
        for (GarbageCollectorMXBean garbageCollectorMXBean : garbageCollectorMXBeans) {
            GarbageCollection.Type type = guessGarbageCollectorType(garbageCollectorMXBean);
            stats.add(new GarbageCollection(type, (int) garbageCollectorMXBean.getCollectionCount(), (int) garbageCollectorMXBean.getCollectionTime()));
        }
        virtualMachine.setGarbageCollections(stats);
    }

    private void collectMemoryStats(VirtualMachine virtualMachine) {
        Collection<MemoryPoolMXBean> memoryPoolMXBeans = machineMBeanServer.getPlatformMXBeans(MemoryPoolMXBean.class);
        Collection<MemoryPool> memoryPools = new ArrayList<>();
        for (MemoryPoolMXBean memoryPoolMXBean : memoryPoolMXBeans) {
            MemoryUsage memoryUsage = memoryPoolMXBean.getUsage();
            MemoryPool.Type memoryType = guessMemoryType(memoryPoolMXBean);
            MemoryPool memoryPool = new MemoryPool(memoryType, memoryUsage.getMax(), memoryUsage.getCommitted(), memoryUsage.getUsed(), memoryUsage.getCommitted());
            memoryPools.add(memoryPool);
        }
        virtualMachine.setMemoryPools(memoryPools);
    }

    private void collectRuntimeInformation(VirtualMachine virtualMachine) {
        OperatingSystemMXBean operatingSystemMXBean = machineMBeanServer.getPlatformMXBean(OperatingSystemMXBean.class);
        RuntimeMXBean runtimeMXBean = machineMBeanServer.getPlatformMXBean(RuntimeMXBean.class);

        RuntimeInformation runtimeInformation = new RuntimeInformation();
        runtimeInformation.setOsName(operatingSystemMXBean.getName());
        runtimeInformation.setOsVersion(operatingSystemMXBean.getVersion());
        runtimeInformation.setStartTime(runtimeMXBean.getStartTime());
        runtimeInformation.setUptime(runtimeMXBean.getUptime());
        virtualMachine.setName(runtimeMXBean.getVmName() + " " + runtimeMXBean.getVmVersion());

        com.sun.management.OperatingSystemMXBean internalOperatingSystemMXBean = machineMBeanServer.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
        Process process = virtualMachine.getProcess();
        process.setCpuTotal((float) internalOperatingSystemMXBean.getProcessCpuLoad() * 100);

        try {
            runtimeInformation.setCommittedVirtualMemorySize(machineMBeanServer.getLongAttr(OPERATING_SYSTEM_NAME, "CommittedVirtualMemorySize", 0L));
            runtimeInformation.setFreePhysicalMemorySize(machineMBeanServer.getLongAttr(OPERATING_SYSTEM_NAME, "FreePhysicalMemorySize", 0L));
            runtimeInformation.setFreeSwapSpaceSize(machineMBeanServer.getLongAttr(OPERATING_SYSTEM_NAME, "FreeSwapSpaceSize", 0L));
            runtimeInformation.setTotalPhysicalMemorySize(machineMBeanServer.getLongAttr(OPERATING_SYSTEM_NAME, "TotalPhysicalMemorySize", 0L));
            runtimeInformation.setTotalSwapSpaceSize(machineMBeanServer.getLongAttr(OPERATING_SYSTEM_NAME, "TotalSwapSpaceSize", 0L));
            runtimeInformation.setProcessCpuTime(machineMBeanServer.getLongAttr(OPERATING_SYSTEM_NAME, "ProcessCpuTime", 0L));
        } catch (Exception e) {
            LOGGER.error("Failed to extract operating system stats", e);
        }
        virtualMachine.setRuntimeInformation(runtimeInformation);
    }

    private void collectThreadInformation(VirtualMachine virtualMachine) {
        ThreadMXBean threadMXBean = machineMBeanServer.getPlatformMXBean(ThreadMXBean.class);
        ThreadInformation threadInformation = new ThreadInformation();
        threadInformation.setDaemon(threadMXBean.getDaemonThreadCount());
        threadInformation.setNonDaemon(threadMXBean.getThreadCount() - threadMXBean.getDaemonThreadCount());
        virtualMachine.setThreadInformation(threadInformation);
    }

    private void collectThreadDumps(VirtualMachine virtualMachine) {
        if (!machineMBeanServer.isLocal()) return;
        ThreadDump threadDump = new ThreadDump();
        virtualMachine.setThreadDump(threadDump);
    }

    private void collectPid(VirtualMachine virtualMachine) {
        virtualMachine.setPid(-1);
        if (machineMBeanServer.isLocal()) {
            virtualMachine.setPid((int) ProcessHandle.current().pid());
        } else {
            String name = machineMBeanServer.getPlatformMXBean(RuntimeMXBean.class).getName();
            if (name.contains("@")) {
                String pid = StringUtils.split(name, "@")[0];
                try {
                    virtualMachine.setPid(Integer.parseInt(pid));
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }
    }

    private void collectProcess(VirtualMachine virtualMachine) {
        if (!machineMBeanServer.isLocal()) return;
        Process process = new Process();
        process.setPid((int) ProcessHandle.current().pid());
        OperatingSystem operatingSystem = systemInfo.getOperatingSystem();
        OSProcess osProcess = operatingSystem.getProcess(operatingSystem.getProcessId());
        if (osProcess != null) {
            CpuTime cpuTime = new CpuTime(osProcess.getKernelTime(), osProcess.getUserTime(), 0);
            if (prevCpuTime != null) {
                long duration = cpuTime.time - prevCpuTime.time;
                process.setCpuSystemTime(cpuTime.system - prevCpuTime.system);
                process.setCpuUserTime(cpuTime.user - prevCpuTime.user);
                process.setCpuSystem(VirtualMachineUtils.getUsage(duration, process.getCpuSystemTime()));
                process.setCpuUser(VirtualMachineUtils.getUsage(duration, process.getCpuUserTime()));
                process.setCpuIoWait(VirtualMachineUtils.getUsage(duration, process.getCpuIoWaitTime()));
            }
            prevCpuTime = cpuTime;

            process.setMemoryVirtual(osProcess.getVirtualSize());
            process.setMemoryResident(osProcess.getResidentMemory());
            process.setFileDescriptors((int) osProcess.getOpenFiles());
            process.setThreads(osProcess.getThreadCount());
            process.setStartupTime(osProcess.getStartTime());
            process.setUptime(osProcess.getUpTime());
            process.setBytesRead(osProcess.getBytesRead());
            process.setBytesWritten(osProcess.getBytesWritten());
            process.setMinorFaults(osProcess.getMinorFaults());
            process.setMajorFaults(osProcess.getMajorFaults());
        }
        virtualMachine.setProcess(process);
    }

    private MemoryPool.Type guessMemoryType(MemoryPoolMXBean memoryPoolMXBean) {
        String name = memoryPoolMXBean.getName();
        if (edenMemoryPools.contains(name)) {
            return MemoryPool.Type.EDEN;
        } else if (tenureMemoryPools.contains(name)) {
            return MemoryPool.Type.TENURED;
        } else if (survivorPools.contains(name)) {
            return MemoryPool.Type.SURVIVOR;
        } else if (name.equalsIgnoreCase(CODE_MEMORY_POOL_NAME)) {
            return MemoryPool.Type.CODE_CACHE;
        } else if (name.startsWith(CODE_HEAP_POOL_NAME)) {
            return MemoryPool.Type.CODE_HEAP;
        } else if (name.equalsIgnoreCase(PERM_GEN_MEMORY_POOL_NAME)) {
            return MemoryPool.Type.PERM_GEN;
        } else if (name.equalsIgnoreCase(METASPACE_POOL_NAME)) {
            return MemoryPool.Type.METASPACE;
        } else if (name.equalsIgnoreCase(COMPRESSED_CODE_CLASS_POOL_NAME)) {
            return MemoryPool.Type.CLASS_SPACE;
        } else {
            return MemoryPool.Type.UNKNOWN;
        }
    }

    private GarbageCollection.Type guessGarbageCollectorType(GarbageCollectorMXBean garbageCollectorMXBean) {
        String name = garbageCollectorMXBean.getName();
        if (edenGCNames.contains(name)) {
            return GarbageCollection.Type.EDEN;
        } else if (tenuredGCNames.contains(name)) {
            return GarbageCollection.Type.TENURED;
        } else {
            return GarbageCollection.Type.UNKNOWN;
        }
    }

    private BufferPool.Type guessBufferPoolType(BufferPoolMXBean bufferPoolMXBean) {
        String name = bufferPoolMXBean.getName();
        if ("direct".equals(name)) {
            return BufferPool.Type.DIRECT;
        } else if ("mapped".equals(name)) {
            return BufferPool.Type.MAPPED;
        } else {
            return BufferPool.Type.UNKNOWN;
        }
    }

    static class CpuTime {

        private final long time = System.nanoTime();

        private final long system;
        private final long user;
        private final long io;

        public CpuTime(long system, long user, long io) {
            this.user = user;
            this.system = system;
            this.io = io;
        }

        @Override
        public String toString() {
            return new StringJoiner(", ", CpuTime.class.getSimpleName() + "[", "]")
                    .add("time=" + time)
                    .add("user=" + user)
                    .add("system=" + system)
                    .add("io=" + io)
                    .toString();
        }
    }

    private static final Set<String> edenGCNames = new HashSet<>();
    private static final Set<String> tenuredGCNames = new HashSet<>();
    private static final Set<String> edenMemoryPools = new HashSet<>();
    private static final Set<String> survivorPools = new HashSet<>();
    private static final Set<String> tenureMemoryPools = new HashSet<>();

    private static final String CODE_MEMORY_POOL_NAME = "Code Cache";
    private static final String CODE_HEAP_POOL_NAME = "CodeHeap";
    private static final String COMPRESSED_CODE_CLASS_POOL_NAME = "Compressed Class Space";
    private static final String PERM_GEN_MEMORY_POOL_NAME = "PS Perm Gen";
    private static final String METASPACE_POOL_NAME = "Metaspace";

    static {
        edenGCNames.add("Copy");
        edenGCNames.add("ParNew");
        edenGCNames.add("PS Scavenge");
        edenGCNames.add("G1 Young Generation");
        edenGCNames.add("ZGC Minor Pauses");
        edenGCNames.add("ZGC Minor Cycles");
        edenGCNames.add("scavenge");

        tenuredGCNames.add("MarkSweepCompact");
        tenuredGCNames.add("ConcurrentMarkSweep");
        tenuredGCNames.add("PS MarkSweep");
        tenuredGCNames.add("G1 Old Generation");
        tenuredGCNames.add("ZGC Major Cycles");
        tenuredGCNames.add("ZGC Major Pauses");
        tenuredGCNames.add("global");

        survivorPools.add("PS Survivor Space");
        survivorPools.add("Par Survivor Space");
        survivorPools.add("G1 Survivor Space");
        survivorPools.add("nursery-survivor");
        survivorPools.add("balanced-survivor");

        edenMemoryPools.add("PS Eden Space");
        edenMemoryPools.add("Par Eden Space");
        edenMemoryPools.add("G1 Eden Space");
        edenMemoryPools.add("ZGC Young Generation");
        edenMemoryPools.add("nursery-allocate");
        edenMemoryPools.add("balanced-eden");

        tenureMemoryPools.add("PS Old Gen");
        tenureMemoryPools.add("CMS Old Gen");
        tenureMemoryPools.add("G1 Old Gen");
        tenureMemoryPools.add("ZGC Old Generation");
        tenureMemoryPools.add("balanced-old");
        tenureMemoryPools.add("tenured-LOA");
        tenureMemoryPools.add("tenured-SOA");
        tenureMemoryPools.add("tenured");
    }
}
