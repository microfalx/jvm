package net.microfalx.jvm.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.ToString;
import net.microfalx.lang.NumberUtils;

import java.io.Serializable;

import static net.microfalx.lang.NumberUtils.percent;

@Data
@AllArgsConstructor
public class MemoryPool implements Serializable {

    private static final long serialVersionUID = -5134622121249987615L;

    private Type type;

    private long maximum;
    private long capacity;
    private long used;
    private long committed;

    private final long timestamp = System.currentTimeMillis();

    public float getUsedPercent() {
        return percent(used, maximum);
    }

    public long getMaximum() {
        return maximum > 0 ? maximum : capacity;
    }

    @Getter
    @ToString
    @AllArgsConstructor
    public enum Type {

        UNKNOWN(100, false, "Unknown"),
        EDEN(0, true, "Eden"),
        SURVIVOR(1, true, "Survivor"),
        TENURED(2, true, "Tenured"),
        METASPACE(3, false, "Metaspace"),
        PERM_GEN(4, false, "Perm Gen"),
        CODE_CACHE(5, false, "Code Cache"),
        CODE_HEAP(6, false, "Code Heap"),
        CLASS_SPACE(7, false, "Class Space");

        private final int order;
        private final boolean heap;
        private final String label;
    }
}
