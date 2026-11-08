package io.github.jichuo1.meguviz.android.resourcesloader;

import android.annotation.TargetApi;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Build;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceScope;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Optional owner of one ResourcesLoader and providers it creates from APK descriptors. */
public final class ScopedResourceLoader implements AutoCloseable {
    public enum Status { OPENED, UNSUPPORTED }
    public static final class OpenResult {
        public final Status status;
        /** Null on API 27–29; use the supplied ResourceScope without a loader. */
        public final ScopedResourceLoader loader;
        private OpenResult(Status status, ScopedResourceLoader loader) { this.status = status; this.loader = loader; }
    }
    /** An attach failure whose cleanup also failed; close recovery until cleanup succeeds. */
    public static final class OpenFailure extends IllegalStateException {
        public final ScopedResourceLoader recovery;
        private OpenFailure(RuntimeException cause, ScopedResourceLoader recovery) {
            super("Loader attach failed and cleanup requires retry", cause); this.recovery = recovery;
        }
    }

    interface Backend {
        void attach();
        Object fromApk(ParcelFileDescriptor descriptor) throws IOException;
        void setProviders(List<Object> ordered);
        void detach();
        void closeProvider(Object provider);
    }

    /**
     * A provider loaded by {@link #prepareApk}. The caller owns it until {@link #putPrepared}
     * installs it; {@link #close()} from any thread releases it if it was not installed.
     */
    public static final class PreparedApk implements AutoCloseable {
        private final ScopedResourceLoader loader;
        private final AtomicReference<Object> provider;
        private PreparedApk(ScopedResourceLoader loader, Object provider) {
            this.loader = loader; this.provider = new AtomicReference<>(Objects.requireNonNull(provider));
        }
        /** True until it is installed or closed. */
        public boolean isPending() { return provider.get() != null; }
        /** Closes the provider unless a loader already took ownership of it; repeated calls are harmless. */
        @Override public void close() {
            Object owned = provider.getAndSet(null);
            if (owned != null) loader.factory.closeProvider(owned);
        }
    }

    private ResourceScope scope;
    private Backend backend;
    /** The backend that loads and closes providers; unlike {@link #backend} it is never cleared. */
    private final Backend factory;
    private final LinkedHashMap<String, Object> providers = new LinkedHashMap<>();
    private final ArrayList<Object> owned = new ArrayList<>();
    private boolean closed, faulted;

    /** The scope must own this exact Resources; its owner and the caller must be the main thread. */
    public static OpenResult open(ResourceScope scope, Resources resources) {
        checkMain(); Objects.requireNonNull(scope); Objects.requireNonNull(resources);
        if (!scope.owns(resources)) throw new IllegalArgumentException("Resources do not belong to scope");
        if (Build.VERSION.SDK_INT < 30) return new OpenResult(Status.UNSUPPORTED, null);
        return new OpenResult(Status.OPENED, openWithBackend(scope, new Api30(resources)));
    }

    static ScopedResourceLoader openWithBackend(ResourceScope scope, Backend backend) {
        checkMain(); Objects.requireNonNull(scope).state(); Objects.requireNonNull(backend);
        ScopedResourceLoader result = new ScopedResourceLoader(scope, backend);
        result.invalidateRequired();
        try { backend.attach(); }
        catch (RuntimeException failure) {
            try { backend.detach(); }
            catch (RuntimeException cleanup) {
                result.faulted = true;
                OpenFailure recoverable = new OpenFailure(failure, result);
                recoverable.addSuppressed(cleanup);
                throw recoverable;
            }
            throw failure;
        }
        return result;
    }

    private ScopedResourceLoader(ResourceScope scope, Backend backend) { this.scope = scope; this.backend = backend; factory = backend; }
    private static void checkMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
    }
    private void requireOpen() {
        checkMain(); if (closed) throw new IllegalStateException("Closed loader");
        scope.state().snapshot();
        if (faulted) throw new IllegalStateException("Loader faulted; close it");
    }
    private void invalidateRequired() {
        ResourceState state = scope.state(); state.invalidateResources(); state.invalidateTheme();
    }
    private void invalidateIfAlive() {
        // state() checks the scope owner first; a subsequent IllegalStateException means the domain closed.
        ResourceState state = scope.state();
        try { state.invalidateResources(); state.invalidateTheme(); }
        catch (IllegalStateException alreadyClosed) { /* Closed domains already reject all tokens. */ }
    }

    /** Adds or reloads a slot. Replacing an existing slot preserves its provider precedence. */
    public void putApk(String slot, ParcelFileDescriptor descriptor) throws IOException {
        requireOpen(); Objects.requireNonNull(slot); Objects.requireNonNull(descriptor);
        if (slot.isEmpty()) throw new IllegalArgumentException("Nonempty slot required");
        install(slot, backend.fromApk(descriptor));
    }

    /**
     * Loads an APK into a provider on the calling thread, which may be any thread, without touching
     * this loader, its Resources or the resource domain. The APK parsing is the expensive part of
     * {@link #putApk}; doing it here keeps it off the main thread. Hand the result to
     * {@link #putPrepared} on the main thread. Until then the caller owns it and must close it if
     * it is not installed (for example because the loader closed in the meantime).
     */
    public PreparedApk prepareApk(ParcelFileDescriptor descriptor) throws IOException {
        Objects.requireNonNull(descriptor);
        return new PreparedApk(this, factory.fromApk(descriptor));
    }

    /**
     * {@link #putApk} with a provider from {@link #prepareApk}: the same checks, invalidation,
     * provider order, replacement and failure behavior, minus the APK load. On success, and after a
     * failure past the argument checks, the loader owns the provider; if the checks reject the call
     * the caller still owns it.
     */
    public void putPrepared(String slot, PreparedApk prepared) {
        requireOpen(); Objects.requireNonNull(slot); Objects.requireNonNull(prepared);
        if (slot.isEmpty()) throw new IllegalArgumentException("Nonempty slot required");
        if (prepared.loader != this) throw new IllegalArgumentException("Prepared by another loader");
        Object provider = prepared.provider.getAndSet(null);
        if (provider == null) throw new IllegalStateException("Prepared APK already installed or closed");
        install(slot, provider);
    }

    private void install(String slot, Object replacement) {
        owned.add(replacement);
        LinkedHashMap<String, Object> next = new LinkedHashMap<>(providers);
        Object previous = next.put(slot, replacement);
        try {
            invalidateRequired();
            backend.setProviders(new ArrayList<>(next.values()));
            providers.clear(); providers.putAll(next);
            if (previous != null) { backend.closeProvider(previous); owned.remove(previous); }
        } catch (RuntimeException failure) { faulted = true; throw failure; }
    }

    /** Removes and closes only a provider created by this session. */
    public boolean remove(String slot) {
        requireOpen(); Objects.requireNonNull(slot);
        Object previous = providers.get(slot);
        if (previous == null) return false;
        LinkedHashMap<String, Object> next = new LinkedHashMap<>(providers);
        next.remove(slot);
        try {
            invalidateRequired(); backend.setProviders(new ArrayList<>(next.values()));
            providers.clear(); providers.putAll(next);
            backend.closeProvider(previous); owned.remove(previous);
            return true;
        } catch (RuntimeException failure) { faulted = true; throw failure; }
    }

    /** Host notification for changes outside this session; does not enumerate foreign loaders. */
    public void invalidateExternalChange() { requireOpen(); invalidateRequired(); }
    public int ownedProviderCount() { requireOpen(); return owned.size(); }

    @Override public void close() {
        checkMain(); if (closed) return;
        invalidateIfAlive();
        RuntimeException failure = null;
        try { backend.detach(); } catch (RuntimeException error) { failure = error; }
        boolean cleared = false;
        try { backend.setProviders(Collections.emptyList()); cleared = true; }
        catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        if (cleared) {
            providers.clear();
            for (Object provider : new ArrayList<>(owned)) {
                try { backend.closeProvider(provider); owned.remove(provider); }
                catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
        }
        if (failure != null) { faulted = true; throw failure; }
        closed = true; scope = null; backend = null;
    }

    @TargetApi(30)
    private static final class Api30 implements Backend {
        private final Resources resources;
        private final ResourcesLoader loader = new ResourcesLoader();
        Api30(Resources resources) { this.resources = resources; }
        @Override public void attach() { resources.addLoaders(loader); }
        @Override public Object fromApk(ParcelFileDescriptor descriptor) throws IOException {
            return ResourcesProvider.loadFromApk(descriptor);
        }
        @Override public void setProviders(List<Object> ordered) {
            List<ResourcesProvider> converted = new ArrayList<>();
            for (Object provider : ordered) converted.add((ResourcesProvider) provider);
            loader.setProviders(converted);
        }
        @Override public void detach() { resources.removeLoaders(loader); }
        @Override public void closeProvider(Object provider) { ((ResourcesProvider) provider).close(); }
    }
}
