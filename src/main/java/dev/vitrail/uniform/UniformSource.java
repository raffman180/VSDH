package dev.vitrail.uniform;

@FunctionalInterface
public interface UniformSource {
    void read(ExpressionFrame frame, Val destination);
}
