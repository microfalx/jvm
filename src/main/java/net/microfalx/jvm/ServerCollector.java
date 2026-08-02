package net.microfalx.jvm;

import net.microfalx.jvm.model.FileSystem;
import net.microfalx.jvm.model.Os;
import net.microfalx.jvm.model.Server;
import net.microfalx.lang.JvmUtils;
import net.microfalx.metrics.Timer;
import oshi.SystemInfo;
import oshi.hardware.*;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static net.microfalx.jvm.VirtualMachineUtils.COLLECTOR_METRICS;
import static net.microfalx.lang.StringUtils.toIdentifier;

/**
 * Collects information about a Java VM (process).
 */
public final class ServerCollector extends AbstractCollector<Server> {

    private VirtualMachineMBeanServer machineMBeanServer;
    private final SystemInfo systemInfo = new SystemInfo();

    private static volatile long[][] prevTicks;
    private static volatile long prevTime;

    @Override
    public Server execute() {
        try (Timer ignored = COLLECTOR_METRICS.startTimer("Server")) {
            Server server = new Server();
            server.setHostName(JvmUtils.getLocalHost().getCanonicalHostName());
            server.setId(toIdentifier(server.getHostName()));
            extractCpu(server);
            extractMemory(server);
            extractNetwork(server);
            extractDisk(server);
            collectOs(server);
            extractMisc(server);
            return server;
        }
    }

    private void extractMemory(Server server) {
        GlobalMemory memory = systemInfo.getHardware().getMemory();
        server.setMemoryTotal(memory.getTotal());
        server.setMemoryUsed(memory.getTotal() - memory.getAvailable());
        server.setMemoryActuallyUsed(server.getMemoryUsed());
        VirtualMemory virtualMemory = memory.getVirtualMemory();
        server.setSwapTotal(virtualMemory.getSwapTotal());
        server.setSwapUsed(virtualMemory.getSwapUsed());
        server.setSwapPageIn(virtualMemory.getSwapPagesIn());
        server.setSwapPageOut(virtualMemory.getSwapPagesOut());
    }

    private void extractDisk(Server server) {
        long reads = 0;
        long readBytes = 0;
        long writeBytes = 0;
        long writes = 0;

        List<HWDiskStore> diskStores = systemInfo.getHardware().getDiskStores();
        for (HWDiskStore diskStore : diskStores) {
            reads += diskStore.getReads();
            readBytes += diskStore.getReadBytes();
            writes += diskStore.getWrites();
            writeBytes += diskStore.getWriteBytes();
        }
        server.setIoReads(reads);
        server.setIoReadBytes(readBytes);
        server.setIoWrites(writes);
        server.setIoWriteBytes(writeBytes);

        Collection<FileSystem> fileSystemInformations = new ArrayList<>();
        oshi.software.os.FileSystem fileSystem = systemInfo.getOperatingSystem().getFileSystem();
        List<OSFileStore> fileStores = fileSystem.getFileStores();
        long diskTotal = 0;
        long diskUsed = 0;
        long totalNodes = 0;
        long freeNodes = 0;
        for (OSFileStore fileStore : fileStores) {
            diskTotal += fileStore.getTotalSpace();
            diskUsed += fileStore.getTotalSpace() - fileStore.getUsableSpace();
            totalNodes += fileStore.getTotalInodes();
            freeNodes += fileStore.getFreeInodes();
            fileSystemInformations.add(create(fileStore));
        }
        server.setFileSystems(fileSystemInformations.stream()
                .filter(net.microfalx.jvm.model.FileSystem::isDisk)
                .collect(Collectors.toList()));
        server.setDiskInodeCount(totalNodes);
        server.setDiskInodeCountUsed(totalNodes - freeNodes);
        server.setDiskTotal(diskTotal);
        server.setDiskUsed(diskUsed);
    }

    private net.microfalx.jvm.model.FileSystem create(OSFileStore fileStore) {
        net.microfalx.jvm.model.FileSystem disk = new net.microfalx.jvm.model.FileSystem();
        disk.setId(fileStore.getUUID());
        disk.setName(fileStore.getName());
        disk.setDescription(fileStore.getDescription());
        disk.setMount(fileStore.getMount());
        disk.setType(net.microfalx.jvm.model.FileSystem.Type.fromString(fileStore.getType()));
        disk.setTotalSpace(fileStore.getTotalSpace());
        disk.setFreeSpace(fileStore.getFreeSpace());
        disk.setUsableSpace(fileStore.getUsableSpace());
        disk.setTotalInodes(fileStore.getTotalInodes());
        disk.setFreeInodes(fileStore.getFreeInodes());
        return disk;
    }

    private void extractNetwork(Server server) {
        long readBytes = 0;
        long writeBytes = 0;
        HardwareAbstractionLayer hardware = systemInfo.getHardware();
        List<NetworkIF> networkIFs = hardware.getNetworkIFs();
        for (NetworkIF networkIF : networkIFs) {
            readBytes += networkIF.getBytesRecv();
            writeBytes += networkIF.getBytesSent();
        }
        server.setNetworkReadBytes(readBytes);
        server.setNetworkWriteBytes(writeBytes);
    }

    private void extractMisc(Server server) {
        CentralProcessor processor = systemInfo.getHardware().getProcessor();
        server.setContextSwitches(processor.getContextSwitches());
        server.setInterrupts(processor.getInterrupts());
        server.setUptime((int) systemInfo.getOperatingSystem().getSystemUptime());
    }

    private void collectOs(Server server) {
        OperatingSystem operatingSystem = systemInfo.getOperatingSystem();
        Os os = new Os();
        os.setName(operatingSystem.getFamily());
        os.setVersion(operatingSystem.getVersionInfo().toString());
        server.setOs(os);
    }

    private void extractCpu(Server server) {
        HardwareAbstractionLayer hardware = systemInfo.getHardware();
        CentralProcessor processor = hardware.getProcessor();
        server.setCores(processor.getPhysicalProcessorCount());
        server.setThreads(processor.getLogicalProcessorCount());
        double[] loads = processor.getSystemLoadAverage(3);
        server.setLoad1((float) loads[0]);
        server.setLoad5((float) loads[1]);
        server.setLoad15((float) loads[2]);
        if (isMetadata()) return;
        long currentTime = System.nanoTime();
        long[][] ticks = processor.getProcessorCpuLoadTicks();
        if (prevTicks != null) {
            long duration = currentTime - prevTime;
            try {
                server.setCpuSystem(getTick(CentralProcessor.TickType.SYSTEM, duration, ticks, prevTicks));
                server.setCpuUser(getTick(CentralProcessor.TickType.USER, duration, ticks, prevTicks));
                server.setCpuNice(getTick(CentralProcessor.TickType.NICE, duration, ticks, prevTicks));
                server.setCpuIoWait(getTick(CentralProcessor.TickType.IOWAIT, duration, ticks, prevTicks));
                server.setCpuIdle(getTick(CentralProcessor.TickType.IDLE, duration, ticks, prevTicks));
                server.setCpuIrq(getTick(CentralProcessor.TickType.IRQ, duration, ticks, prevTicks));
                server.setCpuSoftIrq(getTick(CentralProcessor.TickType.SOFTIRQ, duration, ticks, prevTicks));
                server.setCpuStolen(getTick(CentralProcessor.TickType.STEAL, duration, ticks, prevTicks));
                server.setCpuTotal(server.getCpuUser() + server.getCpuNice() + server.getCpuSystem()
                        + server.getCpuIoWait() + server.getCpuIrq() + server.getCpuSoftIrq() + server.getCpuStolen());
            } catch (IllegalArgumentException e) {
                // ignore
            }
        }
        prevTime = currentTime;
        prevTicks = ticks;
    }

    private float getTick(CentralProcessor.TickType type, long duration, long[][] ticks, long[][] prevTicks) {
        float percent = 0;
        for (int core = 0; core < prevTicks.length; core++) {
            long coreDuration = ticks[core][type.getIndex()] - prevTicks[core][type.getIndex()];
            percent += VirtualMachineUtils.getUsage(duration, coreDuration);
        }
        return percent / prevTicks.length;
    }
}
