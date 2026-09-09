package net.microfalx.jvm;

import lombok.Getter;
import lombok.ToString;
import net.microfalx.lang.*;
import net.microfalx.lang.annotation.SizeOf;
import net.microfalx.lang.service.Logger;
import net.microfalx.lang.service.Service;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;

import static java.util.Collections.*;
import static net.microfalx.lang.ArgumentUtils.requireBounded;
import static net.microfalx.lang.ArgumentUtils.requireNonNull;

public class DefaultObjectSizeEstimator implements ObjectSizeEstimator {

    private static final Logger LOGGER = Logger.get(DefaultObjectSizeEstimator.class);

    private static final int MAX_ITEMS = 1000;
    private static final boolean MEM_32G_OR_GREATER = Runtime.getRuntime().maxMemory() > 32 * FormatterUtils.G;

    private final Map<Class<?>, Integer> shallowSizeCache = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Integer> overheadByType = new HashMap<>();
    private static final Map<Class<?>, Integer> sizeByType = new HashMap<>();
    private static final Map<Class<?>, Function<Object, ObjectSize>> sizeByFunction = new HashMap<>();
    private static final Map<Class<?>, Integer> subclassByType = new HashMap<>();
    private static final Set<Class<?>> unknownTypes = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static ObjectSize ZERO = new ObjectSizeImpl();
    private static Unsafe unsafe;

    @Override
    public long getShallowSize(Object object) {
        return shallowSizeOf(object);
    }

    @Override
    public ObjectSize getDeepSize(Object object) {
        if (object == null) return ZERO;
        if (object instanceof Sizeable) return new ObjectSizeImpl((Sizeable) object);
        ObjectSizeImpl objectSize = new ObjectSizeImpl();
        objectSize.visited = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            objectSize.sizeOf = getDeepSize(object, objectSize);
        } finally {
            objectSize.visited = null;
        }
        return objectSize;
    }

    @Override
    public void registerShallowSize(Class<?> clazz, int size) {
        requireNonNull(clazz);
        requireBounded(size, 1, Integer.MAX_VALUE);
        doRegisterShallowSize(clazz, size);
    }

    @Override
    public <T> void registerShallowSize(Class<T> clazz, Function<T, ObjectSize> function) {
        requireNonNull(clazz);
        requireNonNull(function);
        doRegisterShallowSize(clazz, function);
    }

    @Override
    public void registerShallowSizeOfSubclass(Class<?> clazz, int size) {
        requireNonNull(clazz);
        requireBounded(size, 1, Integer.MAX_VALUE);
        doRegisterShallowSizeOfSubclass(clazz, size);
    }

    private long getDeepSize(Object object, ObjectSizeImpl objectSize) {
        if (!objectSize.visit(object)) return getPointerSize();
        ObjectSize specialSize = getSpecialSize(object);
        if (specialSize != null) {
            objectSize.add(specialSize.getCountOf());
            return specialSize.getSizeOf();
        }
        long size = shallowSizeOf(object);
        if (isCollectionOrMap(object)) {
            size += getCollectionOrMapSize(object, objectSize);
        } else {
            size += getFieldsSize(object, objectSize);
        }
        return size;
    }

    private long getCollectionOrMapSize(Object object, ObjectSizeImpl objectSize) {
        long sampleSize = 0;
        long size;
        int entryCount = 0;
        int iterations = MAX_ITEMS;
        if (object instanceof Collection<?>) {
            size = ((Collection<?>) object).size();
            objectSize.add((int) size);
            for (Object item : (Collection<?>) object) {
                sampleSize += getDeepSize(item, objectSize);
                entryCount++;
                if (iterations-- == 0) break;
            }
        } else if (object instanceof Map<?, ?>) {
            size = ((Map<?, ?>) object).size();
            objectSize.add((int) size);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
                sampleSize += getDeepSize(entry.getKey(), objectSize);
                sampleSize += getDeepSize(entry.getValue(), objectSize);
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

    private long getFieldsSize(Object object, ObjectSizeImpl objectSize) {
        long size = 0;
        List<Field> fields = ReflectionUtils.openFields(object.getClass());
        for (Field field : fields) {
            SizeOf sizeOfAnnot = field.getAnnotation(SizeOf.class);
            if (sizeOfAnnot != null) {
                if (!sizeOfAnnot.shallow() && sizeOfAnnot.deepSize() > 0) {
                    size += sizeOfAnnot.deepSize();
                }
                continue;
            }
            if (field.getType().isPrimitive()) continue;
            try {
                Object fieldValue = field.get(object);
                if (fieldValue != null) {
                    objectSize.increment();
                    size += getDeepSize(fieldValue, objectSize);
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

    private Integer getSubclassSize(Object object) {
        Integer size = null;
        for (Map.Entry<Class<?>, Integer> entry : subclassByType.entrySet()) {
            Class<?> subClass = entry.getKey();
            if (ClassUtils.isSubClassOf(object, subClass)) {
                size = entry.getValue();
                doRegisterShallowSize(object.getClass(), size);
                break;
            }
        }
        return size;
    }

    private ObjectSize getSizeFromFunction(Object object) {
        ObjectSize size = null;
        for (Map.Entry<Class<?>, Function<Object, ObjectSize>> entry : sizeByFunction.entrySet()) {
            Class<?> subClass = entry.getKey();
            if (ClassUtils.isSubClassOf(object, subClass)) {
                Function<Object, ObjectSize> function = entry.getValue();
                size = function.apply(object);
                break;
            }
        }
        return size;
    }

    private Integer getSizeFromAnnotation(Object object) {
        Integer size = null;
        SizeOf sizeOfAnnot = AnnotationUtils.getAnnotation(object, SizeOf.class);
        if (sizeOfAnnot != null) {
            if (sizeOfAnnot.shallow()) {
                size = (int) getShallowSize(object);
            } else if (sizeOfAnnot.deepSize() > 0) {
                size = sizeOfAnnot.deepSize();
            }
            if (size != null) doRegisterShallowSize(object.getClass(), size);
        }
        return size;
    }

    private ObjectSize getSpecialSize(Object object) {
        if (object == null) return new ObjectSizeImpl(getPointerSize());
        Class<?> clazz = object.getClass();
        if (object instanceof String) {
            return new ObjectSizeImpl(40 + ((String) object).length() * 2);
        } else if (clazz.isArray()) {
            int length = java.lang.reflect.Array.getLength(object);
            if (clazz.getComponentType().isPrimitive()) {
                length = length * getFieldSize(clazz.getComponentType());
            } else {
                length = length * getPointerSize();
            }
            return new ObjectSizeImpl(length);
        } else {
            Integer size = sizeByType.get(clazz);
            if (size == null) {
                size = getSubclassSize(object);
                if (size == null) size = getSizeFromAnnotation(object);
                if (size == null) {
                    ObjectSize objectSize = getSizeFromFunction(object);
                    if (objectSize != null) return objectSize;
                }
            }
            if (size == null) {
                if (!(object instanceof Service) && clazz.getName().startsWith("net.microfalx.")) {
                    LOGGER.debug("Unknown object type for MicroFalx: {}", clazz.getName());
                } else if (clazz.getName().startsWith("java.")) {
                    LOGGER.debug("Unknown object type for JDK: {}", clazz.getName());
                } else if (clazz.getName().startsWith("org.springframework.")) {
                    LOGGER.debug("Unknown object type for Spring: {}", clazz.getName());
                } else {
                    if (unknownTypes.add(clazz)) {
                        LOGGER.debug("Unknown object type for special size calculation: {}, shallow size: {}",
                                clazz.getName(), getShallowSize(object));
                    }
                }
            }
            return size != null ? new ObjectSizeImpl(size) : null;
        }
    }

    private void doRegisterShallowSize(Class<?> clazz, int size) {
        LOGGER.debug("Registering shallow size {} bytes for class {}", size, ClassUtils.getName(clazz));
        sizeByType.put(clazz, size);
    }

    private <T> void doRegisterShallowSize(Class<T> clazz, Function<T, ObjectSize> size) {
        LOGGER.debug("Registering shallow size {} function for class {}", ClassUtils.getName(size), ClassUtils.getName(clazz));
        sizeByFunction.put(clazz, (Function<Object, ObjectSize>) size);
    }

    private void doRegisterShallowSizeOfSubclass(Class<?> clazz, int size) {
        LOGGER.debug("Registering shallow size {} bytes for subclass {}", size, ClassUtils.getName(clazz));
        subclassByType.put(clazz, size);
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
        return unsafe != null ? unsafe.addressSize() : (MEM_32G_OR_GREATER ? 8 : 4);
    }

    @Getter
    @ToString
    static class ObjectSizeImpl implements ObjectSize {

        private long sizeOf;
        private int countOf;
        private Set<Object> visited;

        ObjectSizeImpl() {
        }

        ObjectSizeImpl(long sizeOf) {
            this.sizeOf = sizeOf;
            this.countOf++;
        }

        ObjectSizeImpl(long sizeOf, int countOf) {
            this.sizeOf = sizeOf;
            this.countOf = countOf;
        }

        ObjectSizeImpl(Sizeable sizeable) {
            this.sizeOf = sizeable.getSizeOf();
            this.countOf = sizeable.getCountOf();
        }

        private void increment() {
            this.countOf++;
        }

        private void add(int count) {
            this.countOf += count;
        }

        private boolean visit(Object object) {
            return visited.add(object);
        }
    }

    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = (Unsafe) theUnsafe.get(null);
        } catch (Exception e) {
            unsafe = null;
            LOGGER.error("Unable to acquire sun.misc.Unsafe", e);
        }
        overheadByType.put(ArrayList.class, 8);
        overheadByType.put(LinkedList.class, 32);
        overheadByType.put(HashSet.class, 40);
        overheadByType.put(TreeSet.class, 40);
        overheadByType.put(HashMap.class, 40);
        overheadByType.put(TreeMap.class, 40);
        overheadByType.put(LinkedHashMap.class, 40);
        overheadByType.put(ConcurrentHashMap.class, 40);
        overheadByType.put(ConcurrentLinkedQueue.class, 40);

        sizeByType.put(Byte.class, 16);
        sizeByType.put(Short.class, 16);
        sizeByType.put(Integer.class, 24);
        sizeByType.put(Long.class, 32);
        sizeByType.put(Float.class, 24);
        sizeByType.put(Double.class, 32);

        sizeByType.put(Class.class, 100);
        sizeByType.put(Object.class, 16);
        sizeByType.put(EnumSet.class, 100);
        sizeByType.put(emptyList().getClass(), 16);
        sizeByType.put(emptyMap().getClass(), 16);
        sizeByType.put(emptySet().getClass(), 16);

        sizeByType.put(AtomicBoolean.class, 16);
        sizeByType.put(AtomicInteger.class, 16);
        sizeByType.put(AtomicLong.class, 24);
        sizeByType.put(AtomicReference.class, 24);
        sizeByType.put(AtomicStampedReference.class, 32);
        sizeByType.put(AtomicMarkableReference.class, 32);

        sizeByType.put(ArrayList.class, 32);
        sizeByType.put(LinkedList.class, 32);
        sizeByType.put(HashSet.class, 40);
        sizeByType.put(TreeSet.class, 40);
        sizeByType.put(HashMap.class, 40);
        sizeByType.put(TreeMap.class, 40);
        sizeByType.put(LinkedHashSet.class, 40);
        sizeByType.put(LinkedHashMap.class, 40);
        sizeByType.put(ConcurrentHashMap.class, 80);
        sizeByType.put(ConcurrentLinkedQueue.class, 80);
        sizeByType.put(ArrayBlockingQueue.class, 80);
        sizeByType.put(LinkedBlockingQueue.class, 80);
        sizeByType.put(CopyOnWriteArrayList.class, 40);
        sizeByType.put(CopyOnWriteArraySet.class, 60);

        sizeByType.put(Date.class, 32);
        sizeByType.put(java.sql.Date.class, 32);
        sizeByType.put(java.sql.Timestamp.class, 40);
        sizeByType.put(LocalDate.class, 32);
        sizeByType.put(LocalTime.class, 32);
        sizeByType.put(Instant.class, 32);
        sizeByType.put(Duration.class, 32);
        sizeByType.put(LocalDateTime.class, 96);
        sizeByType.put(OffsetDateTime.class, 128);
        sizeByType.put(ZonedDateTime.class, 136);
        sizeByType.put(Thread.class, 500);

        subclassByType.put(Thread.class, 500);
        subclassByType.put(Lock.class, 100);
        subclassByType.put(ExecutorService.class, 500);
    }
}
