package net.microfalx.jvm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultObjectSizeEstimatorTest {

    private final ObjectSizeEstimator estimator = new DefaultObjectSizeEstimator();

    @Test
    void shallowSizeReturnsZeroForNull() {
        assertEquals(0, estimator.getShallowSize(null));
    }

    @Test
    void deepSizeReturnsZeroForNull() {
        assertEquals(0, estimator.getDeepSize(null));
    }

    @Test
    void shallowSizeIsStableForSameClass() {
        long first = estimator.getShallowSize(new SampleNode("one"));
        long second = estimator.getShallowSize(new SampleNode("two"));

        assertEquals(first, second);
        assertTrue(first > 0);
    }

    @Test
    void shallowSizeForArrayIsAlignedAndPositive() {
        long size = estimator.getShallowSize(new int[8]);

        assertTrue(size > 0);
        assertEquals(0, size % 8);
    }

    @Test
    void deepSizeForStringExceedsShallowSize() {
        String value = "hello-world";

        long shallow = estimator.getShallowSize(value);
        long deep = estimator.getDeepSize(value);

        assertTrue(deep > shallow);
    }

    @Test
    void deepSizeForContainerIncludesReferencedObjects() {
        List<SampleNode> nodes = new ArrayList<>();
        nodes.add(new SampleNode("first"));
        nodes.add(new SampleNode("second"));

        long shallow = estimator.getShallowSize(nodes);
        long deep = estimator.getDeepSize(nodes);

        assertTrue(deep > shallow);
    }

    @Test
    void deepSizeForContainerIncludesReferencedObjects2() {
        Map<String, SampleNode> nodes = new HashMap<>();
        nodes.put("k1", new SampleNode("first"));
        nodes.put("k2", new SampleNode("second"));

        long shallow = estimator.getShallowSize(nodes);
        long deep = estimator.getDeepSize(nodes);

        assertTrue(deep > shallow);
    }

    private static final class SampleNode {

        private final String value;
        private final byte[] payload = new byte[32];

        private SampleNode(String value) {
            this.value = value;
        }
    }
}