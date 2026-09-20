package net.microfalx.jvm;

import java.util.function.Function;

/**
 * An interface which provides estimations for object size.
 */
public interface ObjectSizeEstimator {

    /**
     * Returns the default instance of the object size estimator.
     *
     * @return a non-null instance
     */
    static ObjectSizeEstimator get() {
        return VirtualMachineMetrics.get();
    }

    /**
     * Returns the current object for the current thread.
     *
     * @return the current object, null if no object is set
     */
    static Object top() {
        return DefaultObjectSizeEstimator.CURRENT.get();
    }

    /**
     * Returns the current object for the current thread.
     *
     * @return the current object, null if no object is set
     */
    static Object current() {
        return DefaultObjectSizeEstimator.CURRENT.get();
    }

    /**
     * Returns the shallow size (bytes used to hold the fields) of an object.
     *
     * @param object the object to estimate
     * @return the shallow size in bytes
     */
    long getShallowSize(Object object);

    /**
     * Returns the shallow size (bytes used to hold the fields) of an object.
     *
     * @param type the object type to estimate
     * @return the shallow size in bytes
     */
    long getShallowSize(Class<?> type);

    /**
     * Returns the deep size (including referenced objects) of an object.
     *
     * @param object the object to estimate
     * @return the deep size in bytes
     */
    ObjectSize getDeepSize(Object object);

    /**
     * Registers a shallow size for a given class and no deep size will be calculated.
     * <p>
     * The object size is presumed to be included somewhere else.
     *
     * @param function the function which returns the size
     */
    <T> void registerShallowSize(Function<T, ObjectSize> function);

    /**
     * Registers a shallow size for a given class and no deep size will be calculated.
     * <p>
     * The object size is presumed to be included somewhere else.
     * <p>
     * If the class is an annotation, all classes registered with that annotation will be considered to have
     * the same shallow size.
     *
     * @param clazz the class
     * @param size  the size
     */
    <T> void registerShallowSize(Class<T> clazz, int size);

    /**
     * Registers a shallow size for a given class and no deep size will be calculated.
     * <p>
     * The object size is presumed to be included somewhere else.
     *
     * @param clazz    the class
     * @param function the function which returns the size
     */
    <T> void registerShallowSize(Class<T> clazz, Function<T, ObjectSize> function);

    /**
     * Registers a shallow size for a subclass and no deep size will be calculated.
     * <p>
     * The object size is presumed to be included somewhere else.
     *
     * @param clazz the class
     * @param size  the size
     */
    void registerShallowSizeOfSubclass(Class<?> clazz, int size);
}
