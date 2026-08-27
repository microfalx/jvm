package net.microfalx.jvm;

/**
 * An interface which provides estimations for object size.
 */
public interface ObjectSizeEstimator {

    /**
     * Returns the shallow size (bytes used to hold the fields) of an object.
     *
     * @param object the object to estimate
     * @return the shallow size in bytes
     */
    long getShallowSize(Object object);

    /**
     * Returns the deep size (including referenced objects) of an object.
     *
     * @param object the object to estimate
     * @return the deep size in bytes
     */
    long getDeepSize(Object object);
}
