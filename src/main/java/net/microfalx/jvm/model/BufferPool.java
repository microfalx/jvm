package net.microfalx.jvm.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;

@Data
@AllArgsConstructor
public class BufferPool implements Serializable {

    private static final long serialVersionUID = 5379404115167147704L;

    private Type type;
    private int count;
    private long maximum;
    private long used;

    public float getUsedPercent() {
        return maximum == 0 ? 0 : 100 * (float) used / (float) maximum;
    }

    @Getter
    @ToString
    @AllArgsConstructor
    public enum Type {

        DIRECT(true, "Direct"),
        MAPPED(false, "Mapped"),
        UNKNOWN(false, "Unknown");

        private final boolean direct;
        private final String label;

    }
}
