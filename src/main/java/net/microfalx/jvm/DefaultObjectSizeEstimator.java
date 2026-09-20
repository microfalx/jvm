package net.microfalx.jvm;

import lombok.Getter;
import lombok.ToString;
import net.microfalx.lang.*;
import net.microfalx.lang.annotation.SizeOf;
import net.microfalx.lang.service.Logger;
import net.microfalx.lang.service.Service;
import net.microfalx.threadpool.ThreadPool;
import sun.misc.Unsafe;

import java.io.File;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;

import static java.util.Collections.*;
import static net.microfalx.jvm.VirtualMachineUtils.OBJECT_SIZE_METRICS;
import static net.microfalx.lang.ArgumentUtils.requireBounded;
import static net.microfalx.lang.ArgumentUtils.requireNonNull;
import static net.microfalx.lang.ExceptionUtils.getRootCauseDescription;
import static net.microfalx.lang.NumberUtils.addIfPositive;

public class DefaultObjectSizeEstimator implements ObjectSizeEstimator {

    private static final Logger LOGGER = Logger.get(DefaultObjectSizeEstimator.class);

    private static final int MAX_ITEMS = 1000;
    private static final boolean MEM_32G_OR_GREATER = Runtime.getRuntime().maxMemory() > 32 * FormatterUtils.G;

    private final Map<Class<?>, Integer> shallowSizeCache = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Integer> overheadByType = new HashMap<>();
    private static final Map<Class<?>, Integer> sizeByType = new HashMap<>();
    private static final Map<Class<?>, Function<Object, ObjectSize>> sizeByFunction = new LinkedHashMap<>();
    private static final Map<Class<?>, Integer> subclassByType = new LinkedHashMap<>();
    private static final Map<Class<?>, ObjectSize> cachedSizables = new HashMap<>();
    private static final Set<Class<?>> unknownTypes = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final ObjectSize ZERO = new ObjectSizeImpl();
    private static Unsafe unsafe;

    @Override
    public long getShallowSize(Object object) {
        return shallowSizeOf(object);
    }

    @Override
    public ObjectSize getDeepSize(Object object) {
        return getDeepSize(object, false);
    }

    @Override
    public <T> void registerShallowSize(Class<T> clazz, int size) {
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

    private ObjectSize getDeepSize(Object object, boolean skipSizable) {
        ObjectSizeImpl objectSize = new ObjectSizeImpl(getPointerSize());
        objectSize.visited = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            return calculateDeepSize(object, objectSize, skipSizable);
        } catch (Throwable e) {
            LOGGER.warn("Unable to calculate deep size for object of type {}, root cause: {}",
                    object.getClass().getName(), getRootCauseDescription(e));
        } finally {
            objectSize.visited = null;
        }
        return objectSize;
    }

    private ObjectSize getDeepSize(Object object, ObjectSizeImpl rootObjectSize) {
        return calculateDeepSize(object, rootObjectSize, false);
    }

    private ObjectSize calculateDeepSize(Object object, ObjectSizeImpl rootObjectSize, boolean skipSizable) {
        if (object == null) return ZERO;
        if (object instanceof String) {
            return new ObjectSizeImpl(40 + ((String) object).length() * 2);
        }
        if (object instanceof Sizeable && !skipSizable) return getSizeFromSizeable((Sizeable) object);
        if (!rootObjectSize.visit(object)) return ZERO;
        ObjectSize specialSize = getSpecialSize(object);
        if (specialSize != null) {
            rootObjectSize.add(specialSize);
            return specialSize;
        } else {

            rootObjectSize.addSize(shallowSizeOf(object));
            if (isCollectionOrMap(object)) {
                return calculateCollectionOrMapSize(object, rootObjectSize);
            } else {
                return OBJECT_SIZE_METRICS.time(ClassUtils.getCompactName(object),
                        () -> calculateFieldsSize(object, rootObjectSize));
            }
        }
    }

    private ObjectSize calculateCollectionOrMapSize(Object object, ObjectSizeImpl rootObjectSize) {
        ObjectSizeImpl estimatedKeySize = new ObjectSizeImpl();
        ObjectSizeImpl estimatedValueSize = new ObjectSizeImpl();
        long size;
        int entryCount = 0;
        int iterations = MAX_ITEMS;
        if (object instanceof Collection<?>) {
            size = ((Collection<?>) object).size();
            rootObjectSize.addCount((int) size);
            for (Object item : (Collection<?>) object) {
                estimatedValueSize.add(getDeepSize(item, rootObjectSize));
                entryCount++;
                if (iterations-- == 0) break;
            }
        } else if (object instanceof Map<?, ?>) {
            size = ((Map<?, ?>) object).size();
            rootObjectSize.addCount((int) size);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
                estimatedKeySize.add(getDeepSize(entry.getKey(), rootObjectSize));
                estimatedValueSize.add(getDeepSize(entry.getValue(), rootObjectSize));
                entryCount++;
                if (iterations-- == 0) break;
            }
            estimatedValueSize = new ObjectSizeImpl(estimatedKeySize.getSizeOf() + estimatedValueSize.getSizeOf(),
                    estimatedValueSize.getCountOf(),
                    estimatedKeySize.getArraySizeOf() + estimatedValueSize.getArraySizeOf(),
                    estimatedKeySize.getArrayCountOf() + estimatedValueSize.getArrayCountOf());
        } else {
            size = 0;
        }
        int overhead = entryCount * overheadByType.getOrDefault(object.getClass(), 0);
        estimatedValueSize = new ObjectSizeImpl(
                estimateSizeBasedOnSample(size, estimatedValueSize.getSizeOf(), entryCount) + overhead,
                estimateCountBasedOnSample(size, estimatedValueSize.getCountOf(), entryCount),
                estimateSizeBasedOnSample(size, estimatedValueSize.getArraySizeOf(), entryCount),
                estimateCountBasedOnSample(size, estimatedValueSize.getArrayCountOf(), entryCount)
        );
        rootObjectSize.add(estimatedValueSize);
        return estimatedValueSize;
    }

    private long estimateSizeBasedOnSample(long size, long entySize, int entryCount) {
        int averageSize = 0;
        // calculate average / entry
        if (entryCount > 0) averageSize = (int) (entySize / entryCount);
        // using all entries, calculate the total estimated size
        return size * averageSize;
    }

    private int estimateCountBasedOnSample(long size, int entySize, int entryCount) {
        int averageCount = 0;
        // calculate average / entry
        if (entryCount > 0) averageCount = (int) (entySize / entryCount);
        // using all entries, calculate the total estimated size
        return (int) (size * averageCount);
    }

    private ObjectSize calculateFieldsSize(Object object, ObjectSizeImpl rootObjectSize) {
        ObjectSizeImpl objectSize = new ObjectSizeImpl(getPointerSize());
        List<Field> fields = ReflectionUtils.openFields(object.getClass());
        for (Field field : fields) {
            SizeOf sizeOfAnnot = field.getAnnotation(SizeOf.class);
            if (sizeOfAnnot != null) {
                if (!sizeOfAnnot.shallow() && sizeOfAnnot.deepSize() > 0) {
                    objectSize.addSize(sizeOfAnnot.deepSize());
                }
                continue;
            }
            if (field.getType().isPrimitive()) continue;
            try {
                Object fieldValue = field.get(object);
                if (fieldValue != null) {
                    objectSize.incrementCount();
                    objectSize.add(getDeepSize(fieldValue, rootObjectSize));
                }
            } catch (Throwable e) {
                if (e instanceof IllegalAccessException) {
                    LOGGER.warn("Unable to calculate field size {} for field {}, root cause: {}",
                            field, object.getClass().getName(), getRootCauseDescription(e));
                }
                if (!ExceptionUtils.contains(e, IllegalAccessException.class, IllegalAccessError.class,
                        NoClassDefFoundError.class, ClassNotFoundException.class)) {
                    LOGGER.warn("Unable to calculate field size {} for field {}, root cause: {}",
                            field, object.getClass().getName(), getRootCauseDescription(e));
                }
            }
        }
        rootObjectSize.add(objectSize);
        return objectSize;
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
        if (clazz.isArray()) {
            int length = java.lang.reflect.Array.getLength(object);
            if (clazz.getComponentType().isPrimitive()) {
                length = length * getFieldSize(clazz.getComponentType());
            } else {
                length = length * getPointerSize();
            }
            return new ObjectSizeImpl(length);
        } else if (Proxy.isProxyClass(clazz)) {
            // proxies probably hold references to other objects, so we will calculate the shallow size only
            return new ObjectSizeImpl(getShallowSize(object));
        } else {
            Integer size = sizeByType.get(clazz);
            if (size == null && !isCollectionOrMap(object)) {
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
                    LOGGER.info("Unknown object type for Spring: {}", clazz.getName());
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

    private ObjectSize getSizeFromSizeable(Sizeable sizeable) {
        ObjectSizeImpl objectSize = new ObjectSizeImpl(sizeable);
        if (objectSize.sizeOf < 0) {
            ObjectSize cachedSize = cachedSizables.get(sizeable.getClass());
            if (cachedSize == null) {
                cachedSize = getDeepSize(sizeable, true);
                // the cached size eliminates the space occupied by the array, since it is already accounted
                // for in the sizeable object
                cachedSize = new ObjectSizeImpl(cachedSize.getSizeOf() - objectSize.getArraySizeOf(),
                        cachedSize.getCountOf(),
                        -1, -1);
                cachedSizables.put(sizeable.getClass(), cachedSize);
            }
            // add the arrays size to the cached size, since it is not accounted for in the sizeable object
            objectSize = new ObjectSizeImpl(cachedSize.getSizeOf() + objectSize.getArraySizeOf(),
                    cachedSize.getCountOf(),
                    objectSize.getArraySizeOf(), objectSize.getArrayCountOf());
        }
        return objectSize;
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
        private long arraySizeOf;
        private int arrayCountOf;
        private Set<Object> visited;

        ObjectSizeImpl() {
        }

        ObjectSizeImpl(long sizeOf) {
            this.sizeOf = sizeOf;
            this.countOf = 1;
        }

        ObjectSizeImpl(long sizeOf, int countOf, long arraySizeOf, int arrayCountOf) {
            this.sizeOf = sizeOf;
            this.countOf = countOf;
            this.arraySizeOf = arraySizeOf;
            this.arrayCountOf = arrayCountOf;
        }

        ObjectSizeImpl(Sizeable sizeable) {
            this.sizeOf = sizeable.getSizeOf();
            this.countOf = sizeable.getCountOf();
            this.arraySizeOf = sizeable.getArraySizeOf();
            this.arrayCountOf = sizeable.getArrayCountOf();
        }

        private void addSize(long size) {
            this.sizeOf += size;
        }

        private void incrementCount() {
            this.countOf++;
        }

        private void addCount(int count) {
            this.countOf += count;
        }

        private void add(ObjectSize size) {
            this.sizeOf = addIfPositive(this.sizeOf, size.getSizeOf());
            this.countOf = addIfPositive(this.countOf, size.getCountOf());
            this.arraySizeOf = addIfPositive(this.arraySizeOf, size.getArraySizeOf());
            this.arrayCountOf = addIfPositive(this.arrayCountOf, size.getArrayCountOf());
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
        sizeByType.put(File.class, 100);

        sizeByType.put(SecureRandom.class, 40);
        sizeByType.put(ThreadLocalRandom.class, 24);

        subclassByType.put(Thread.class, 500);
        subclassByType.put(Lock.class, 100);
        // very small because it is shared
        subclassByType.put(ThreadPool.class, 8);
        // very small because it is shared
        subclassByType.put(ExecutorService.class, 8);
        subclassByType.put(Reference.class, 40);
        subclassByType.put(Enum.class, 24);
        subclassByType.put(EnumSet.class, 40);
        subclassByType.put(org.slf4j.Logger.class, 64);
    }
}
