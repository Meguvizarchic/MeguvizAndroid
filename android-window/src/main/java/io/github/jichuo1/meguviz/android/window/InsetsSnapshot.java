package io.github.jichuo1.meguviz.android.window;

import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A copied snapshot; no View, Context or mutable platform object crosses the owner boundary. */
public final class InsetsSnapshot {
    public static final int SUPPORTED_TYPES = WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime()
            | WindowInsetsCompat.Type.systemGestures() | WindowInsetsCompat.Type.mandatorySystemGestures()
            | WindowInsetsCompat.Type.tappableElement() | WindowInsetsCompat.Type.displayCutout();
    private static final int[] TYPES = {WindowInsetsCompat.Type.statusBars(), WindowInsetsCompat.Type.navigationBars(),
            WindowInsetsCompat.Type.captionBar(), WindowInsetsCompat.Type.ime(), WindowInsetsCompat.Type.systemGestures(),
            WindowInsetsCompat.Type.mandatorySystemGestures(), WindowInsetsCompat.Type.tappableElement(),
            WindowInsetsCompat.Type.displayCutout(), WindowInsetsCompat.Type.systemOverlays()};
    public final int runtimeApi, hostTargetSdk;
    public final String source = "androidx-dispatch";
    private final List<TypeInsets> values;
    private InsetsSnapshot(int runtimeApi, int hostTargetSdk, List<TypeInsets> values) {
        this.runtimeApi = runtimeApi; this.hostTargetSdk = hostTargetSdk;
        this.values = Collections.unmodifiableList(values);
    }
    public static InsetsSnapshot copy(WindowInsetsCompat insets, int runtimeApi, int hostTargetSdk) {
        List<TypeInsets> values = new ArrayList<>();
        for (int type : TYPES) {
            Insets edge = insets.getInsets(type);
            InsetsValue edges = edge.left == 0 && edge.top == 0 && edge.right == 0 && edge.bottom == 0
                    ? InsetsValue.ZERO : new InsetsValue(edge.left, edge.top, edge.right, edge.bottom);
            values.add(new TypeInsets(type, edges, insets.isVisible(type)));
        }
        return new InsetsSnapshot(runtimeApi, hostTargetSdk, values);
    }
    public List<TypeInsets> types() { return values; }
    public InsetsValue combined(int typeMask) {
        if (typeMask == 0 || (typeMask & ~SUPPORTED_TYPES) != 0) throw new IllegalArgumentException("Unsupported inset types");
        InsetsValue combined = InsetsValue.ZERO;
        for (TypeInsets value : values) if ((value.type & typeMask) != 0) combined = combined.max(value.edges);
        return combined;
    }
    public static final class TypeInsets {
        public final int type;
        public final InsetsValue edges;
        public final boolean visible;
        private TypeInsets(int type, InsetsValue edges, boolean visible) {
            this.type = type; this.edges = edges; this.visible = visible;
        }
    }
}
