package net.microfalx.jvm.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import net.microfalx.lang.*;

import java.io.Serializable;
import java.time.temporal.Temporal;
import java.util.HashSet;
import java.util.Set;

import static net.microfalx.lang.NumberUtils.percent;
import static net.microfalx.lang.TimeUtils.fromMillis;

@Data
@NoArgsConstructor
public class FileSystem implements Identifiable<String>, Nameable, Timestampable, Serializable {

    private static final long serialVersionUID = -6550711624662428549L;

    private String id = StringUtils.NA_STRING;
    private long timestamp = System.currentTimeMillis();

    private String name;
    private String mount;
    private String description;
    private Type type;
    private long freeSpace;
    private long usableSpace;
    private long totalSpace;
    private long freeInodes;
    private long totalInodes;

    @Override
    public Temporal getCreatedAt() {
        return fromMillis(timestamp);
    }

    public float getUsedPercent() {
        return percent(totalSpace - freeSpace, totalSpace);
    }

    public boolean isDisk() {
        return type == Type.DISK;
    }

    public enum Type {

        UNKNOWN,
        TMP,
        DISK;

        public static Type fromString(String value) {
            if (value == null) return UNKNOWN;
            if (diskTypes.contains(value.toLowerCase())) {
                return DISK;
            } else if (tmpTypes.contains(value.toLowerCase())) {
                return TMP;
            }
            return Type.UNKNOWN;
        }
    }

    private static final Set<String> diskTypes = new HashSet<>();
    private static final Set<String> tmpTypes = new HashSet<>();

    static {
        diskTypes.add("ext2");
        diskTypes.add("ext3");
        diskTypes.add("ext4");
        diskTypes.add("xfs");
        diskTypes.add("btrfs");
        diskTypes.add("zfs");

        tmpTypes.add("tmpfs");
    }
}
