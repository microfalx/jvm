package net.microfalx.jvm;


import net.microfalx.lang.Sizeable;

/**
 * A class which holds the deep size of an object an estimated number of references ot other objects.
 */
public interface ObjectSize extends Sizeable {

    /**
     * Returns an object size with  a count of one object.
     *
     * @param size the size in bytes
     * @return a non-null instance
     */
    static ObjectSize of(long size) {
        return new DefaultObjectSizeEstimator.ObjectSizeImpl(size);
    }

    /**
     * Returns the object size and the count of references (elements).
     *
     * @param size  the size in bytes
     * @param count the number of references (elements)
     * @return a non-null instance
     */
    static ObjectSize of(long size, int count) {
        return new DefaultObjectSizeEstimator.ObjectSizeImpl(size, count);
    }
}
