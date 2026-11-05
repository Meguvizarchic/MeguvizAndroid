package io.github.jichuo1.meguviz.android.window;

import org.junit.Test;
import static org.junit.Assert.*;

public class WindowModelsTest {
    static final class Tree { boolean alive = true; int listeners; }
    static final class Access implements ObserverLease.Access<Tree> {
        int adds, removes;
        public boolean alive(Tree tree) { return tree.alive; }
        public void add(Tree tree) { tree.listeners++; adds++; }
        public void remove(Tree tree) { if (tree.listeners > 0) tree.listeners--; removes++; }
    }
    @Test public void mergedObserverReinstallAndDetachCloseUseActualDestination() {
        Access access = new Access(); ObserverLease<Tree> lease = new ObserverLease<>(access);
        Tree floating = new Tree(); Tree attached = new Tree();
        lease.attach(floating); floating.alive = false; attached.listeners = floating.listeners; floating.listeners = 0;
        lease.attach(attached); assertEquals(1, attached.listeners);
        lease.attach(attached); assertEquals(2, access.adds);
        lease.detach(); assertEquals(0, attached.listeners);
        lease.close(); lease.close(); assertEquals(access.adds, access.removes);

        Access secondAccess = new Access(); ObserverLease<Tree> secondLease = new ObserverLease<>(secondAccess);
        Tree first = new Tree(); Tree next = new Tree(); secondLease.attach(first); secondLease.detach(); secondLease.close();
        assertEquals(0, first.listeners); assertEquals(0, next.listeners);
        assertEquals(1, secondAccess.removes);
        assertThrows(IllegalStateException.class, () -> secondLease.attach(next));
    }
    @Test public void repeatedInsetsDoNotAccumulateAndExternalWritesArePreserved() {
        InsetsValue base = new InsetsValue(1, 2, 3, 4); InsetsValue insets = new InsetsValue(4, 5, 6, 7);
        InsetsBaseline baseline = new InsetsBaseline(base);
        InsetsValue first = baseline.apply(base, insets);
        assertEquals(new InsetsValue(5, 7, 9, 11), first);
        assertEquals(first, baseline.apply(first, insets));
        assertEquals(base, baseline.restoreIfOwned(first));
        InsetsValue external = new InsetsValue(9, 9, 9, 9);
        assertEquals(external, baseline.restoreIfOwned(external));
        assertThrows(IllegalStateException.class, () -> baseline.apply(external, insets));
        baseline.rebase(external); assertEquals(new InsetsValue(13, 14, 15, 16), baseline.apply(external, insets));
    }
    @Test public void edgeOverflowIsRejectedInsteadOfWrappingPadding() {
        assertThrows(ArithmeticException.class,
                () -> new InsetsValue(Integer.MAX_VALUE, 0, 0, 0).plus(new InsetsValue(1, 0, 0, 0)));
    }
}
