package net.microfalx.jvm;

import lombok.extern.slf4j.Slf4j;
import net.microfalx.lang.ReflectionUtils;
import net.microfalx.lang.annotation.SizeOf;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;

@Slf4j
public class DefaultObjectSizeEstimator implements ObjectSizeEstimator {

    private static final int MAX_ITEMS = 1000;

    private final Map<Class<?>, Integer> shallowSizeCache = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Integer> overheadByType = new HashMap<>();
    private static final Map<Class<?>, Integer> sizeByType = new HashMap<>();
    private static Unsafe unsafe;

    @Override
    public long getShallowSize(Object object) {
        return shallowSizeOf(object);
    }

    @Override
    public long getDeepSize(Object object) {
        if (object == null) return 0;
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return getDeepSize(object, visited);
    }

    private long getDeepSize(Object object, Set<Object> visited) {
        if (!visited.add(object)) return getPointerSize();
        Integer specialSize = getSpecialSize(object);
        if (specialSize != null) return specialSize;
        long size = shallowSizeOf(object);
        if (object instanceof String) {
            size += ((String) object).length() * 2;
        } else if (isCollectionOrMap(object)) {
            size += getCollectionOrMapSize(object, visited);
        } else {
            size += getFieldsSize(object, visited);
        }
        return size;
    }

    private long getCollectionOrMapSize(Object object, Set<Object> visited) {
        long sampleSize = 0;
        long size;
        int entryCount = 0;
        int iterations = MAX_ITEMS;
        if (object instanceof Collection<?>) {
            size = ((Collection<?>) object).size();
            for (Object item : (Collection<?>) object) {
                sampleSize += getDeepSize(item, visited);
                entryCount++;
                if (iterations-- == 0) break;
            }
        } else if (object instanceof Map<?, ?>) {
            size = ((Map<?, ?>) object).size();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
                sampleSize += getDeepSize(entry.getKey(), visited);
                sampleSize += getDeepSize(entry.getValue(), visited);
                entryCount++;
                if (iterations-- == 0) break;
            }
        } else {
            size = 0;
        }
        int averageSize = 0;
        // calculate average / entry
        if (entryCount > 0) averageSize = (int) (sampleSize / entryCount);
        // using all entries, calculate the total estimated size
        long totalSize = size * averageSize;
        int overheadPerEntry = overheadByType.getOrDefault(object.getClass(), 16);
        // add overhead per entry to the total size
        return totalSize + overheadPerEntry * size;
    }

    private long getFieldsSize(Object object, Set<Object> visited) {
        long size = 0;
        List<Field> fields = ReflectionUtils.openFields(object.getClass());
        for (Field field : fields) {
            SizeOf sizeOfAnnot = field.getAnnotation(SizeOf.class);
            if (sizeOfAnnot != null && sizeOfAnnot.shallow()) continue;
            if (field.getType().isPrimitive()) continue;
            try {
                Object fieldValue = field.get(object);
                if (fieldValue != null) {
                    size += getDeepSize(fieldValue, visited);
                }
            } catch (IllegalAccessException e) {
                // Ignore inaccessible fields
            }
        }
        return size;
    }

    private int shallowSizeOf(Object obj) {
        if (obj == null) return 0;
        Class<?> clazz = obj.getClass();
        Integer size = shallowSizeCache.get(clazz);
        if (size != null) return size;
        // Handle arrays separately
        if (clazz.isArray()) {
            int baseOffset = unsafe.arrayBaseOffset(clazz);
            int indexScale = unsafe.arrayIndexScale(clazz);
            int length = java.lang.reflect.Array.getLength(obj);
            return scaleToAlignment(baseOffset + (length * indexScale));
        } else {
            int maxOffset = getOffsetLastField(clazz);
            // If the object has no instance fields, it takes up the minimum header size
            if (maxOffset == 0) {
                // Default header size is typically 12 or 16 bytes depending on CompressedOops
                return scaleToAlignment(16);
            }
            // Round up to the JVM's 8-byte alignment barrier
            size = scaleToAlignment(maxOffset);
        }
        shallowSizeCache.put(obj.getClass(), size);
        return size;
    }

    private int getOffsetLastField(Class<?> clazz) {
        int maxOffset = 0;
        Class<?> maxFieldType = null;
        // Traverse class hierarchy to find the furthest field offset
        while (clazz != null) {
            for (Field field : clazz.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    int offset = 0;
                    try {
                        offset = (int) unsafe.objectFieldOffset(field);
                    } catch (UnsupportedOperationException e) {
                        // Ignore fields that cannot be accessed
                    }
                    if (offset > maxOffset) {
                        maxOffset = offset;
                        maxFieldType = field.getType();
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        if (maxOffset > 0) maxOffset += getFieldSize(maxFieldType);
        return maxOffset;
    }

    private Integer getSpecialSize(Object object) {
        if (object instanceof Thread) {
            return 500;
        }
        return sizeByType.get(object.getClass());
    }

    private static boolean isCollectionOrMap(Object object) {
        return object instanceof Collection || object instanceof Map;
    }

    private static int getFieldSize(Class<?> type) {
        if (!type.isPrimitive()) {
            return getPointerSize();
        }
        if (type == long.class || type == double.class) return 8;
        if (type == int.class || type == float.class) return 4;
        if (type == short.class || type == char.class) return 2;
        if (type == byte.class || type == boolean.class) return 1;
        return 0;
    }

    private static int scaleToAlignment(int size) {
        // Most JVMs use 8-byte object alignment padding
        int alignment = 8;
        return ((size + alignment - 1) / alignment) * alignment;
    }

    private static int getPointerSize() {
        return unsafe != null ? unsafe.addressSize() : 8;
    }

    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = (Unsafe) theUnsafe.get(null);
        } catch (Exception e) {
            unsafe = null;
            log.error("Unable to acquire sun.misc.Unsafe", e);
        }
        overheadByType.put(ArrayList.class, 8);
        overheadByType.put(LinkedList.class, 32);
        overheadByType.put(HashSet.class, 40);
        overheadByType.put(TreeSet.class, 40);
        overheadByType.put(HashMap.class, 40);
        overheadByType.put(TreeMap.class, 40);
        overheadByType.put(LinkedHashMap.class, 40);
        overheadByType.put(ConcurrentHashMap.class, 40);

        sizeByType.put(AtomicBoolean.class, 16);
        sizeByType.put(AtomicInteger.class, 16);
        sizeByType.put(AtomicLong.class, 24);
        sizeByType.put(AtomicReference.class, 24);
        sizeByType.put(AtomicStampedReference.class, 32);
        sizeByType.put(AtomicMarkableReference.class, 32);

        sizeByType.put(Date.class, 32);
        sizeByType.put(java.sql.Date.class, 32);
        sizeByType.put(java.sql.Timestamp.class, 40);
        sizeByType.put(LocalDate.class, 32);
        sizeByType.put(LocalTime.class, 32);
        sizeByType.put(Instant.class, 32);
        sizeByType.put(LocalDateTime.class, 96);
        sizeByType.put(OffsetDateTime.class, 128);
        sizeByType.put(ZonedDateTime.class, 136);
        sizeByType.put(Thread.class, 500);

    }
}
