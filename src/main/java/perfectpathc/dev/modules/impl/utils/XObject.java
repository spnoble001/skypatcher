package perfectpathc.dev.modules.impl.utils;

/** Mutable generic wrapper used by the original Xenon aim utilities. */
public final class XObject<T> {
    private T value;

    public XObject(T value) {
        this.value = value;
    }

    public T get() {
        return this.value;
    }

    public void set(T value) {
        this.value = value;
    }
}
