package io.github.jichuo1.meguviz.android.resourcesloader;

import android.app.Activity;
import android.content.res.Resources;
import android.os.ParcelFileDescriptor;
import io.github.jichuo1.meguviz.android.runtime.platform.MainThreadDispatcher;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceScope;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceState;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ScopedResourceLoaderTest {
    private static final class FakeBackend implements ScopedResourceLoader.Backend {
        int sequence;
        boolean failAttach, failSet, failDetach, failClose;
        final List<String> events = new ArrayList<>();
        @Override public void attach() {
            events.add("attach");
            if (failAttach) throw new IllegalStateException("attach failed");
        }
        @Override public Object fromApk(ParcelFileDescriptor descriptor) { return "p" + ++sequence; }
        @Override public void setProviders(List<Object> ordered) {
            events.add("set:" + ordered);
            if (failSet) { failSet = false; throw new IllegalStateException("set failed"); }
        }
        @Override public void detach() {
            events.add("detach");
            if (failDetach) { failDetach = false; throw new IllegalStateException("detach failed"); }
        }
        @Override public void closeProvider(Object provider) {
            events.add("close:" + provider);
            if (failClose) { failClose = false; throw new IllegalStateException("close failed"); }
        }
    }
    private ParcelFileDescriptor descriptor(Activity activity) throws IOException {
        File file = new File(activity.getCacheDir(), "loader-test.apk");
        if (!file.exists()) assertTrue(file.createNewFile());
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    private ResourceScope scope(Activity activity) {
        return new ResourceScope(new MainThreadDispatcher(), activity, "host", 4);
    }
    @Test @Config(sdk = 27) public void api27ReportsUnsupportedAndLeavesResourceDomainAlone() {
        try (var activity = Robolectric.buildActivity(Activity.class).setup()) {
            ResourceScope scope = scope(activity.get());
            ResourceState.Token before = scope.state().token(ResourceState.ALL);
            ScopedResourceLoader.OpenResult result = ScopedResourceLoader.open(scope, activity.get().getResources());
            assertEquals(ScopedResourceLoader.Status.UNSUPPORTED, result.status);
            assertNull(result.loader); assertTrue(scope.state().isCurrent(before));
            scope.close();
        }
    }
    @Test public void scopeMismatchAndFailedAttachAreRejectedBeforeOwnersAttach() {
        try (var activity = Robolectric.buildActivity(Activity.class).setup()) {
            ResourceScope scope = scope(activity.get());
            assertThrows(IllegalArgumentException.class, () -> ScopedResourceLoader.open(scope, Resources.getSystem()));
            scope.close();
        }
        try (var activity = Robolectric.buildActivity(Activity.class).setup()) {
            ResourceScope scope = scope(activity.get()); FakeBackend fake = new FakeBackend();
            ResourceState.Token before = scope.state().token(ResourceState.ALL);
            fake.failAttach = true;
            assertThrows(IllegalStateException.class, () -> ScopedResourceLoader.openWithBackend(scope, fake));
            assertFalse(scope.state().isCurrent(before));
            assertEquals(List.of("attach", "detach"), fake.events);
            scope.close();
        }
    }
    @Test public void attachDetachFailureAndFailedMutationExposeRetryableCleanup() throws Exception {
        try (var activity = Robolectric.buildActivity(Activity.class).setup()) {
            ResourceScope scope = scope(activity.get()); FakeBackend fake = new FakeBackend();
            fake.failAttach = true; fake.failDetach = true;
            ScopedResourceLoader.OpenFailure failure = assertThrows(ScopedResourceLoader.OpenFailure.class,
                    () -> ScopedResourceLoader.openWithBackend(scope, fake));
            assertEquals(1, failure.getSuppressed().length);
            assertThrows(IllegalStateException.class, failure.recovery::invalidateExternalChange);
            failure.recovery.close();
            assertEquals(List.of("attach", "detach", "detach", "set:[]"), fake.events);
            scope.close();
        }
        try (var activity = Robolectric.buildActivity(Activity.class).setup(); var descriptor = descriptor(activity.get())) {
            ResourceScope scope = scope(activity.get()); FakeBackend fake = new FakeBackend();
            ScopedResourceLoader loader = ScopedResourceLoader.openWithBackend(scope, fake);
            loader.putApk("base", descriptor);
            ResourceState.Token before = scope.state().token(ResourceState.ALL);
            fake.failSet = true;
            assertThrows(IllegalStateException.class, () -> loader.putApk("overlay", descriptor));
            assertFalse(scope.state().isCurrent(before));
            assertThrows(IllegalStateException.class, () -> loader.remove("base"));
            fake.failDetach = true;
            assertThrows(IllegalStateException.class, loader::close);
            loader.close();
            assertTrue(fake.events.contains("close:p1")); assertTrue(fake.events.contains("close:p2"));
            scope.close();
        }
    }
    @Test public void orderReloadRemoveCloseAndProviderCloseFailureReleaseOwnedProviders() throws Exception {
        try (var activity = Robolectric.buildActivity(Activity.class).setup(); var descriptor = descriptor(activity.get())) {
            ResourceScope scope = scope(activity.get()); FakeBackend fake = new FakeBackend();
            ScopedResourceLoader loader = ScopedResourceLoader.openWithBackend(scope, fake);
            ResourceState.Token before = scope.state().token(ResourceState.ALL);
            loader.putApk("base", descriptor); assertFalse(scope.state().isCurrent(before));
            loader.putApk("overlay", descriptor);
            before = scope.state().token(ResourceState.ALL);
            loader.putApk("base", descriptor); assertFalse(scope.state().isCurrent(before));
            assertEquals("set:[p3, p2]", fake.events.get(3));
            assertEquals("close:p1", fake.events.get(4));
            assertTrue(loader.remove("overlay")); assertFalse(loader.remove("unknown"));
            assertEquals(1, loader.ownedProviderCount());
            before = scope.state().token(ResourceState.ALL);
            loader.close(); assertFalse(scope.state().isCurrent(before));
            assertEquals(List.of("attach", "set:[p1]", "set:[p1, p2]", "set:[p3, p2]", "close:p1",
                    "set:[p3]", "close:p2", "detach", "set:[]", "close:p3"), fake.events);
            loader.close(); assertThrows(IllegalStateException.class, () -> loader.remove("base"));
            scope.close();
        }
        try (var activity = Robolectric.buildActivity(Activity.class).setup(); var descriptor = descriptor(activity.get())) {
            ResourceScope scope = scope(activity.get()); FakeBackend fake = new FakeBackend();
            ScopedResourceLoader loader = ScopedResourceLoader.openWithBackend(scope, fake);
            loader.putApk("base", descriptor);
            scope.close(); fake.failClose = true;
            assertThrows(IllegalStateException.class, loader::close);
            loader.close(); assertEquals(2, fake.events.stream().filter("close:p1"::equals).count());
        }
    }
    @Test public void foreignChangeAndAnotherThreadMutationAreRejectedWithoutBackendChanges() throws Exception {
        try (var activity = Robolectric.buildActivity(Activity.class).setup()) {
            ResourceScope scope = scope(activity.get());
            ScopedResourceLoader loader = ScopedResourceLoader.openWithBackend(scope, new FakeBackend());
            ResourceState.Token resources = scope.state().token(ResourceState.RESOURCES);
            ResourceState.Token theme = scope.state().token(ResourceState.THEME);
            loader.invalidateExternalChange();
            assertFalse(scope.state().isCurrent(resources)); assertFalse(scope.state().isCurrent(theme));
            loader.close(); scope.close();
        }
        try (var activity = Robolectric.buildActivity(Activity.class).setup()) {
            ResourceScope scope = scope(activity.get()); FakeBackend fake = new FakeBackend();
            ScopedResourceLoader loader = ScopedResourceLoader.openWithBackend(scope, fake);
            AtomicReference<Throwable> observed = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { loader.invalidateExternalChange(); } catch (Throwable error) { observed.set(error); }
            });
            worker.start(); worker.join();
            assertTrue(observed.get() instanceof IllegalStateException);
            assertEquals(List.of("attach"), fake.events);
            loader.close(); scope.close();
        }
    }
    @Test public void preparedInstallMatchesPutApkAndLoadsOffMain() throws Exception {
        try (var activity = Robolectric.buildActivity(Activity.class).setup(); var descriptor = descriptor(activity.get())) {
            ResourceScope directScope = scope(activity.get()); FakeBackend direct = new FakeBackend();
            ResourceScope preparedScope = scope(activity.get()); FakeBackend split = new FakeBackend();
            ScopedResourceLoader directLoader = ScopedResourceLoader.openWithBackend(directScope, direct);
            ScopedResourceLoader preparedLoader = ScopedResourceLoader.openWithBackend(preparedScope, split);
            List<String> script = List.of("base", "overlay", "base", "extra", "overlay");
            for (String slot : script) {
                ResourceState.Token directBefore = directScope.state().token(ResourceState.ALL);
                ResourceState.Token preparedBefore = preparedScope.state().token(ResourceState.ALL);
                List<String> eventsBeforeLoad = new ArrayList<>(split.events);
                directLoader.putApk(slot, descriptor);
                // The load runs on a worker thread; installation stays on the main thread.
                AtomicReference<Object> result = new AtomicReference<>();
                Thread worker = new Thread(() -> {
                    try { result.set(preparedLoader.prepareApk(descriptor)); } catch (Throwable error) { result.set(error); }
                });
                worker.start(); worker.join();
                assertTrue(result.get() instanceof ScopedResourceLoader.PreparedApk);
                ScopedResourceLoader.PreparedApk prepared = (ScopedResourceLoader.PreparedApk) result.get();
                assertTrue(prepared.isPending());
                // Loading touches neither the loader nor the resource domain.
                assertEquals(eventsBeforeLoad, split.events);
                assertTrue(preparedScope.state().isCurrent(preparedBefore));
                preparedLoader.putPrepared(slot, prepared);
                assertFalse(prepared.isPending());
                assertEquals(directScope.state().isCurrent(directBefore), preparedScope.state().isCurrent(preparedBefore));
                assertEquals(direct.events, split.events);
                assertEquals(directLoader.ownedProviderCount(), preparedLoader.ownedProviderCount());
            }
            assertTrue(directLoader.remove("base")); assertTrue(preparedLoader.remove("base"));
            directLoader.close(); preparedLoader.close();
            assertEquals(direct.events, split.events);
            directScope.close(); preparedScope.close();
        }
    }
    @Test public void preparedApkOwnershipIsExplicitAndMisuseIsRejected() throws Exception {
        try (var activity = Robolectric.buildActivity(Activity.class).setup(); var descriptor = descriptor(activity.get())) {
            ResourceScope scope = scope(activity.get()); FakeBackend fake = new FakeBackend();
            ScopedResourceLoader loader = ScopedResourceLoader.openWithBackend(scope, fake);
            ScopedResourceLoader other = ScopedResourceLoader.openWithBackend(scope(activity.get()), new FakeBackend());
            ScopedResourceLoader.PreparedApk prepared = loader.prepareApk(descriptor);
            assertEquals(List.of("attach"), fake.events);
            // Rejected arguments leave the provider with the caller.
            assertThrows(IllegalArgumentException.class, () -> loader.putPrepared("", prepared));
            assertThrows(IllegalArgumentException.class, () -> other.putPrepared("base", prepared));
            assertThrows(NullPointerException.class, () -> loader.putPrepared("base", null));
            assertTrue(prepared.isPending());
            AtomicReference<Throwable> offMain = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { loader.putPrepared("base", prepared); } catch (Throwable error) { offMain.set(error); }
            });
            worker.start(); worker.join();
            assertTrue(offMain.get() instanceof IllegalStateException);
            assertTrue(prepared.isPending());
            assertEquals(List.of("attach"), fake.events);
            // Installed once; a second install or a late close does nothing to the loader's provider.
            loader.putPrepared("base", prepared);
            assertThrows(IllegalStateException.class, () -> loader.putPrepared("again", prepared));
            prepared.close();
            assertEquals(List.of("attach", "set:[p1]"), fake.events);
            // Not installed: the caller's close releases it, exactly once.
            ScopedResourceLoader.PreparedApk orphan = loader.prepareApk(descriptor);
            orphan.close(); orphan.close();
            assertEquals(List.of("attach", "set:[p1]", "close:p2"), fake.events);
            assertThrows(IllegalStateException.class, () -> loader.putPrepared("late", orphan));
            // A loader that closed meanwhile rejects the install; the caller still owns the provider.
            ScopedResourceLoader.PreparedApk stranded = loader.prepareApk(descriptor);
            loader.close();
            assertThrows(IllegalStateException.class, () -> loader.putPrepared("late", stranded));
            assertTrue(stranded.isPending());
            stranded.close();
            assertEquals(List.of("attach", "set:[p1]", "close:p2", "detach", "set:[]", "close:p1", "close:p3"), fake.events);
            other.close();
            scope.close();
        }
    }
    @Test public void closedScopeRejectsProviderBeforeAnyBackendIo() throws Exception {
        try (var activity = Robolectric.buildActivity(Activity.class).setup(); var descriptor = descriptor(activity.get())) {
            ResourceScope scope = scope(activity.get()); FakeBackend fake = new FakeBackend();
            ScopedResourceLoader loader = ScopedResourceLoader.openWithBackend(scope, fake);
            scope.close();
            assertThrows(IllegalStateException.class, () -> loader.putApk("late", descriptor));
            assertEquals(0, fake.sequence);
            assertEquals(List.of("attach"), fake.events);
            loader.close();
        }
    }
}
