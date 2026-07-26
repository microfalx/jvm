package net.microfalx.jvm.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.ToString;
import net.microfalx.lang.NumberUtils;

import java.io.Serializable;
import java.time.Duration;

import static java.time.Duration.ofMillis;

@Data
@AllArgsConstructor
public class GarbageCollection implements Serializable {

    private static final long serialVersionUID = -3522275180586328029L;

    private Type type;
    private long duration;
    private int count;

    public Duration getAverage() {
        return ofMillis((long) NumberUtils.average(duration, (float) count));
    }

    @Getter
    @ToString
    @AllArgsConstructor
    public enum Type {

        EDEN(true, "Eden"),
        TENURED(false, "Tenured"),
        UNKNOWN(false, "Unknown");

        private final boolean young;
        private final String label;
    }
}
