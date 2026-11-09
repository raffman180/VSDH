package dev.vitrail.uniform;

/** VSDH adapter: a render-thread snapshot, without importing Vitrail's game runtime. */
public interface ExpressionFrame {
    long frameCounter();
    void read(String name, Val destination);
}
