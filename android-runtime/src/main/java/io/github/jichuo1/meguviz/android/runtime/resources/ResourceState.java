package io.github.jichuo1.meguviz.android.runtime.resources;

import io.github.jichuo1.meguviz.android.runtime.core.EpochDomain;
import io.github.jichuo1.meguviz.android.runtime.core.OwnerDispatcher;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Per-domain bounded cache of application-declared immutable derived values. */
public final class ResourceState implements AutoCloseable {
    public static final int RESOURCES = 1, THEME = 2, ALL = RESOURCES | THEME;
    /** Implementations must be immutable and must not retain platform objects or owned resources. */
    public interface DerivedValue { }
    private final OwnerDispatcher owner;
    private final String namespace;
    private final int capacity;
    private final EpochDomain resources, theme;
    private final LinkedHashMap<Key, DerivedValue> cache = new LinkedHashMap<>(16, .75f, true);
    private ResourceSnapshot snapshot;
    private boolean closed;

    public ResourceState(OwnerDispatcher owner, String namespace, ResourceSnapshot snapshot, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive");
        this.owner = Objects.requireNonNull(owner); owner.checkOwner();
        this.namespace = Objects.requireNonNull(namespace); this.snapshot = Objects.requireNonNull(snapshot);
        this.capacity = capacity; resources = new EpochDomain(owner); theme = new EpochDomain(owner);
    }
    private void requireOpen() { owner.checkOwner(); if (closed) throw new IllegalStateException("Closed resources: this ResourceState is closed, so the call is refused. A closed state cannot be reopened; create a new ResourceState."); }
    private static void checkMask(int mask) {
        if (mask == 0 || (mask & ~ALL) != 0) throw new IllegalArgumentException("Explicit resource dependencies required");
    }
    public ResourceSnapshot snapshot() { requireOpen(); return snapshot; }
    public String namespace() { return namespace; }
    public EpochDomain[] dependencies(int mask) {
        requireOpen(); checkMask(mask);
        return mask == ALL ? new EpochDomain[]{resources, theme}
                : new EpochDomain[]{mask == RESOURCES ? resources : theme};
    }
    public Token token(int mask) {
        requireOpen(); checkMask(mask);
        return new Token(this, mask, resources.stamp(), theme.stamp());
    }
    public boolean isCurrent(Token token) {
        owner.checkOwner();
        return !closed && token != null && token.domain == this
                && ((token.mask & RESOURCES) == 0 || resources.matches(token.resources))
                && ((token.mask & THEME) == 0 || theme.matches(token.theme));
    }
    public void update(ResourceSnapshot next) {
        requireOpen(); Objects.requireNonNull(next);
        if (!snapshot.equals(next)) { snapshot = next; invalidateResources(); invalidateTheme(); }
    }
    public void invalidateResources() { requireOpen(); resources.invalidate(); evict(RESOURCES); }
    public void invalidateTheme() { requireOpen(); theme.invalidate(); evict(THEME); }
    private void evict(int mask) {
        Iterator<Key> iterator = cache.keySet().iterator();
        while (iterator.hasNext()) if ((iterator.next().mask & mask) != 0) iterator.remove();
    }
    public <T extends DerivedValue> T resolve(String requestedNamespace, String resolver, int version,
                                            String descriptor, int mask, Class<T> type, Supplier<T> resolve) {
        requireOpen(); checkMask(mask);
        if (!namespace.equals(requestedNamespace)) throw new IllegalArgumentException("Namespace mismatch: resolve() was called with a namespace other than the one this ResourceState was created for. Use the ResourceState of that namespace.");
        if (version < 1) throw new IllegalArgumentException("Resolver version must be positive");
        Key key = new Key(resolver, version, descriptor, mask, type);
        DerivedValue existing = cache.get(key);
        if (existing != null) return type.cast(existing);
        Token before = token(mask);
        T value = Objects.requireNonNull(resolve.get());
        // Resolver code can reenter or close the scope; never cache its obsolete result.
        if (!isCurrent(before)) throw new IllegalStateException("Resources changed during resolution");
        if (cache.size() == capacity) cache.remove(cache.keySet().iterator().next());
        cache.put(key, value);
        return value;
    }
    public int cachedCount() { owner.checkOwner(); return cache.size(); }
    @Override public void close() {
        owner.checkOwner(); if (closed) return;
        closed = true; cache.clear(); resources.close(); theme.close(); snapshot = null;
    }
    public static final class Token {
        private final ResourceState domain;
        private final int mask;
        private final EpochDomain.Stamp resources, theme;
        private Token(ResourceState domain, int mask, EpochDomain.Stamp resources, EpochDomain.Stamp theme) {
            this.domain = domain; this.mask = mask; this.resources = resources; this.theme = theme;
        }
    }
    private static final class Key {
        final String resolver, descriptor; final int version, mask; final Class<?> type;
        Key(String resolver, int version, String descriptor, int mask, Class<?> type) {
            this.resolver = Objects.requireNonNull(resolver); this.version = version;
            this.descriptor = Objects.requireNonNull(descriptor); this.mask = mask; this.type = Objects.requireNonNull(type);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key k = (Key) other;
            return version == k.version && mask == k.mask && type == k.type
                    && resolver.equals(k.resolver) && descriptor.equals(k.descriptor);
        }
        @Override public int hashCode() { return Objects.hash(resolver, version, descriptor, mask, type); }
    }
}
