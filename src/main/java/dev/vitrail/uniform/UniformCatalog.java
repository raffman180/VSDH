package dev.vitrail.uniform;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** VSDH's explicit expression input table. No automatic zero-filling or MC-26 runtime sources. */
public final class UniformCatalog {
    private record Entry(UniformShape shape, UniformSource source) {}
    private final Map<String, Entry> entries;
    private UniformCatalog(Map<String, Entry> entries) { this.entries = Map.copyOf(entries); }
    public UniformSource source(String name) { var entry=entries.get(name); return entry==null?null:entry.source(); }
    public UniformShape natural(String name) { var entry=entries.get(name); return entry==null?null:entry.shape(); }
    public static Builder builder() { return new Builder(Map.of()); }
    public static Builder builder(UniformCatalog parent) { return new Builder(parent.entries); }
    public static final class Builder {
        private final Map<String, Entry> parent;
        private final Map<String, Entry> local = new LinkedHashMap<>();
        private Builder(Map<String, Entry> parent) { this.parent=parent; }
        public Builder add(String name, UniformShape shape, UniformSource source) {
            if(local.putIfAbsent(name,new Entry(Objects.requireNonNull(shape),Objects.requireNonNull(source)))!=null)
                throw new IllegalArgumentException("Duplicate expression input: "+name);
            return this;
        }
        public UniformCatalog build() { var result=new LinkedHashMap<>(parent); result.putAll(local); return new UniformCatalog(result); }
    }
}
