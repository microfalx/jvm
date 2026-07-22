package net.microfalx.jvm.model;

import lombok.Data;
import net.microfalx.jvm.VirtualMachineCollector;
import net.microfalx.jvm.VirtualMachineMBeanServer;
import net.microfalx.lang.Descriptable;
import net.microfalx.lang.Nameable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

@Data
public class VirtualMachine implements Nameable, Descriptable, Serializable {

    private static final long serialVersionUID = 2876115727942855023L;

    private boolean local;

    private int pid;

    private long heapTotalMemory;
    private long heapUsedMemory;

    private long nonHeapTotalMemory;
    private long nonHeapUsedMemory;

    private String name;
    private String description;
    private Collection<MemoryPool> memoryPools = Collections.emptyList();
    private Collection<BufferPool> bufferPools = Collections.emptyList();
    private Collection<GarbageCollection> garbageCollections = Collections.emptyList();
    private RuntimeInformation runtimeInformation;
    private ThreadInformation threadInformation;
    private Process process;

    private ThreadDump threadDump;

    /**
     * Returns information about current JVM.
     *
     * @return a non-null instance
     */
    public static VirtualMachine get() {
        return get(false);
    }

    /**
     * Returns information about current JVM.
     *
     * @param metadata {@code true} to collect only metadata, {@code metadata} to collect full stats
     * @return a non-null instance
     */
    public static VirtualMachine get(boolean metadata) {
        VirtualMachineCollector collector = (VirtualMachineCollector) new VirtualMachineCollector(VirtualMachineMBeanServer.local())
                .setMetadata(metadata);
        return collector.execute();
    }

    public float getHeapUsedMemoryPercent() {
        return heapTotalMemory == 0 ? 0 : 100 * ((float) heapUsedMemory / (float) heapTotalMemory);
    }

    public GarbageCollection getGarbageCollection(GarbageCollection.Type type) {
        for (GarbageCollection garbageCollection : garbageCollections) {
            if (garbageCollection.getType() == type) return garbageCollection;
        }
        return new GarbageCollection(GarbageCollection.Type.UNKNOWN, 0, 0);
    }

    public MemoryPool getMemoryPool(MemoryPool.Type type) {
        for (MemoryPool memoryPool : memoryPools) {
            if (memoryPool.getType() == type) return memoryPool;
        }
        return new MemoryPool(MemoryPool.Type.UNKNOWN, 0, 0, 0, 0);
    }

    public float getNonHeapUsedMemoryPercent() {
        return nonHeapTotalMemory == 0 ? 0 : 100 * ((float) nonHeapUsedMemory / (float) nonHeapTotalMemory);
    }

    public MemoryPool getEdenMemoryPool() {
        return getMemoryPool(MemoryPool.Type.EDEN);
    }

    public MemoryPool getTenuredMemoryPool() {
        return getMemoryPool(MemoryPool.Type.TENURED);
    }

    public MemoryPool getSurvivorMemoryPool() {
        return getMemoryPool(MemoryPool.Type.SURVIVOR);
    }

    public MemoryPool getMetapaceMemoryPool() {
        return getMemoryPool(MemoryPool.Type.METASPACE);
    }

    public BufferPool getBufferPools(BufferPool.Type type) {
        int count = 0;
        long used = 0;
        long maximum = 0;
        for (BufferPool bufferPool : bufferPools) {
            if (bufferPool.getType() != type) continue;
            count += bufferPool.getCount();
            used += bufferPool.getUsed();
            maximum += bufferPool.getMaximum();
        }
        return new BufferPool(type, count, used, maximum);
    }

    public void setMemoryPools(Collection<MemoryPool> memoryPools) {
        this.memoryPools = memoryPools;
        heapTotalMemory = 0;
        heapUsedMemory = 0;
        nonHeapTotalMemory = 0;
        nonHeapUsedMemory = 0;
        for (MemoryPool memoryPool : memoryPools) {
            if (memoryPool.getType().isHeap()) {
                heapTotalMemory += memoryPool.getMaximum();
                heapUsedMemory += memoryPool.getUsed();
            } else {
                nonHeapTotalMemory += memoryPool.getMaximum();
                nonHeapUsedMemory += memoryPool.getUsed();
            }
        }
    }

    public static MemoryPool getAverageMemoryStats(MemoryPool.Type type, Collection<VirtualMachine> virtualMachines) {
        Collection<MemoryPool> memoryStats = getMemoryStats(type, virtualMachines);
        int count = memoryStats.size();
        if (count == 0) return new MemoryPool(type, 0, 0, 0, 0);
        long maximum = Long.MIN_VALUE;
        long capacity = 0;
        long used = 0;
        long committed = 0;
        for (MemoryPool memoryStat : memoryStats) {
            maximum = Math.max(memoryStat.getMaximum(), maximum);
            capacity += memoryStat.getCapacity();
            used += memoryStat.getUsed();
            committed += memoryStat.getCommitted();
        }
        return new MemoryPool(type, maximum, capacity / count, used / count, committed / count);
    }

    public static MemoryPool getPeakMemoryStats(MemoryPool.Type type, Collection<VirtualMachine> virtualMachines) {
        Collection<MemoryPool> memoryStats = getMemoryStats(type, virtualMachines);
        int count = memoryStats.size();
        if (count == 0) return new MemoryPool(type, 0, 0, 0, 0);
        long maximum = Long.MIN_VALUE;
        long capacity = Long.MIN_VALUE;
        long used = Long.MIN_VALUE;
        long committed = Long.MIN_VALUE;
        for (MemoryPool memoryStat : memoryStats) {
            maximum = Math.max(memoryStat.getMaximum(), maximum);
            capacity = Math.max(memoryStat.getCapacity(), capacity);
            used = Math.max(memoryStat.getUsed(), used);
            committed = Math.max(memoryStat.getCommitted(), committed);
        }
        return new MemoryPool(type, maximum, capacity, used, committed);
    }

    private static Collection<MemoryPool> getMemoryStats(MemoryPool.Type type, Collection<VirtualMachine> virtualMachines) {
        Collection<MemoryPool> memoryPools = new ArrayList<>();
        for (VirtualMachine virtualMachine : virtualMachines) {
            memoryPools.add(virtualMachine.getMemoryPool(type));
        }
        return memoryPools;
    }

}
