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

    /**
     * Edge values packed per type: two longs per type (left|top, right|bottom), so building a
     * snapshot allocates one array instead of 9 {@link TypeInsets} + 9 {@link InsetsValue}.
     */
    private final long[] packed;
    /** Bit i is set when TYPES[i] is visible. */
    private final int visibleMask;
    /** Materialized on first {@link #types()} call; the element order matches TYPES. */
    private volatile List<TypeInsets> materialized;

    private InsetsSnapshot(int runtimeApi, int hostTargetSdk, long[] packed, int visibleMask) {
        this.runtimeApi = runtimeApi; this.hostTargetSdk = hostTargetSdk;
        this.packed = packed; this.visibleMask = visibleMask;
    }

    public static InsetsSnapshot copy(WindowInsetsCompat insets, int runtimeApi, int hostTargetSdk) {
        long[] packed = new long[TYPES.length * 2];
        int visibleMask = 0;
        for (int index = 0; index < TYPES.length; index++) {
            int type = TYPES[index];
            Insets edge = insets.getInsets(type);
            packed[index * 2] = pack(edge.left, edge.top);
            packed[index * 2 + 1] = pack(edge.right, edge.bottom);
            if (insets.isVisible(type)) visibleMask |= 1 << index;
        }
        return new InsetsSnapshot(runtimeApi, hostTargetSdk, packed, visibleMask);
    }

    /** The nine type entries in a fixed order; materialized once and cached. */
    public List<TypeInsets> types() {
        List<TypeInsets> result = materialized;
        if (result != null) return result;
        List<TypeInsets> values = new ArrayList<>(TYPES.length);
        for (int index = 0; index < TYPES.length; index++) {
            values.add(new TypeInsets(TYPES[index], edgesAt(index), (visibleMask & (1 << index)) != 0));
        }
        result = Collections.unmodifiableList(values);
        materialized = result;
        return result;
    }

    public InsetsValue combined(int typeMask) {
        if (typeMask == 0 || (typeMask & ~SUPPORTED_TYPES) != 0) throw new IllegalArgumentException("Unsupported inset types");
        InsetsValue combined = InsetsValue.ZERO;
        for (int index = 0; index < TYPES.length; index++) {
            if ((TYPES[index] & typeMask) == 0) continue;
            combined = combined.max(edgesAt(index));
        }
        return combined;
    }

    private InsetsValue edgesAt(int index) {
        int left = unpackHigh(packed[index * 2]);
        int top = unpackLow(packed[index * 2]);
        int right = unpackHigh(packed[index * 2 + 1]);
        int bottom = unpackLow(packed[index * 2 + 1]);
        if (left == 0 && top == 0 && right == 0 && bottom == 0) return InsetsValue.ZERO;
        return new InsetsValue(left, top, right, bottom);
    }

    private static long pack(int high, int low) {
        return ((long) high << 32) | (low & 0xFFFFFFFFL);
    }

    private static int unpackHigh(long value) { return (int) (value >> 32); }

    private static int unpackLow(long value) { return (int) value; }

    public static final class TypeInsets {
        public final int type;
        public final InsetsValue edges;
        public final boolean visible;
        private TypeInsets(int type, InsetsValue edges, boolean visible) {
            this.type = type; this.edges = edges; this.visible = visible;
        }
    }
}
