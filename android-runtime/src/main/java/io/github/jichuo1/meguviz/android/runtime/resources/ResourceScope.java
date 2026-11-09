package io.github.jichuo1.meguviz.android.runtime.resources;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.util.TypedValue;
import android.view.Display;
import io.github.jichuo1.meguviz.android.runtime.core.OwnerDispatcher;
import java.util.Objects;

/** Owns only the supplied local/window Context; it never substitutes the application theme. */
public final class ResourceScope implements AutoCloseable {
    private final OwnerDispatcher owner;
    private final ResourceState state;
    private Context context;
    private Resources.Theme observedTheme;

    public ResourceScope(OwnerDispatcher owner, Context context, String namespace, int capacity) {
        this.owner = Objects.requireNonNull(owner); owner.checkOwner();
        this.context = Objects.requireNonNull(context); observedTheme = context.getTheme();
        state = new ResourceState(owner, namespace, capture(context), capacity);
    }
    public ResourceState state() { owner.checkOwner(); return state; }
    /** Tests actual resource-object identity for adapters bound to this domain. */
    public boolean owns(Resources resources) {
        owner.checkOwner(); requireContext(); return context.getResources() == resources;
    }
    public void refreshConfiguration() {
        owner.checkOwner(); requireContext();
        state.update(capture(context));
        if (observedTheme != context.getTheme()) { observedTheme = context.getTheme(); state.invalidateTheme(); }
    }
    public void invalidateTheme() { owner.checkOwner(); requireContext(); state.invalidateTheme(); }
    public void invalidateResources() { owner.checkOwner(); requireContext(); state.invalidateResources(); }
    public void applyStyle(int styleId, boolean force) {
        owner.checkOwner(); requireContext();
        context.getTheme().applyStyle(styleId, force); state.invalidateTheme();
    }
    public float spToPixels(float sp) {
        owner.checkOwner(); requireContext();
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.getResources().getDisplayMetrics());
    }
    private void requireContext() { if (context == null) throw new IllegalStateException("Closed resources: this ResourceScope is closed, so unit conversions are refused. Obtain the scope again from the live window or session."); }
    @Override public void close() {
        owner.checkOwner(); state.close(); context = null; observedTheme = null;
    }
    public static ResourceSnapshot capture(Context context) {
        Configuration c = context.getResources().getConfiguration();
        int displayId = -1;
        if (Build.VERSION.SDK_INT >= 30) {
            try { Display display = context.getDisplay(); if (display != null) displayId = display.getDisplayId(); }
            catch (UnsupportedOperationException ignored) { /* Non-visual contexts have no display. */ }
        }
        return new ResourceSnapshot(c.densityDpi, c.fontScale, c.getLocales().toLanguageTags(),
                c.getLayoutDirection(), c.uiMode, c.screenWidthDp, c.screenHeightDp, displayId, c.toString());
    }
}
