package com.makakaiglitch.fiximizer.compat.sable;

import com.makakaiglitch.fiximizer.Fiximizer;
import com.makakaiglitch.fiximizer.compat.ModIntegrationManager;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.ModList;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Optional, passive Sable integration.
 *
 * It subscribes to Sable's public NeoForge pre/post physics-step events using
 * runtime class lookup. No Sable classes are linked at compile time.
 */
public final class SablePhysicsProfiler {
    private static final String PRE_EVENT =
            "dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent";
    private static final String POST_EVENT =
            "dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent";

    private static final RollingPhysicsWindow WINDOW =
            new RollingPhysicsWindow(RollingPhysicsWindow.DEFAULT_CAPACITY);
    private static final ThreadLocal<IdentityHashMap<Object, StartSample>> STARTS =
            new ThreadLocal<>();
    private static final ClassValue<Optional<Method>> LEVEL_GETTERS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("getLevel"));
            } catch (NoSuchMethodException exception) {
                return Optional.empty();
            }
        }
    };

    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();
    private static final RollingPhysicsWindow.Snapshot EMPTY_SNAPSHOT =
            new RollingPhysicsWindow(1).snapshot();
    private static volatile boolean registrationAttempted;
    private static volatile boolean available;
    private static volatile boolean wasEnabled;
    private static volatile String status = "Not checked";
    private static volatile Method preSystemGetter;
    private static volatile Method preTimeStepGetter;
    private static volatile Method postSystemGetter;
    private static volatile Method postTimeStepGetter;

    private SablePhysicsProfiler() {
    }

    /**
     * Registers only when Sable is present. Safe to call more than once.
     * Listeners stay inert while the user's Sable integration toggle is off.
     */
    public static synchronized void registerIfLoaded() {
        if (registrationAttempted) {
            return;
        }
        registrationAttempted = true;

        if (!ModList.get().isLoaded("sable")) {
            status = "Sable not installed";
            return;
        }

        try {
            ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
            Class<?> preType = loadEventClass(PRE_EVENT, contextLoader);
            Class<?> postType = loadEventClass(POST_EVENT, contextLoader);

            if (!Event.class.isAssignableFrom(preType) || !Event.class.isAssignableFrom(postType)) {
                throw new IllegalStateException("Sable physics event classes are not NeoForge Event types");
            }

            preSystemGetter = preType.getMethod("getPhysicsSystem");
            preTimeStepGetter = preType.getMethod("getTimeStep");
            postSystemGetter = postType.getMethod("getPhysicsSystem");
            postTimeStepGetter = postType.getMethod("getTimeStep");

            registerListener(preType, SablePhysicsProfiler::onPrePhysicsTick);
            registerListener(postType, SablePhysicsProfiler::onPostPhysicsTick);

            available = true;
            status = "Sable physics events registered";
            Fiximizer.LOGGER.info("Sable physics-step telemetry registered through its public NeoForge events.");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            available = false;
            status = "Sable event API unavailable";
            logFailure("Unable to register optional Sable physics telemetry.", exception);
        }
    }

    private static Class<?> loadEventClass(String className, ClassLoader contextLoader)
            throws ClassNotFoundException {
        if (contextLoader != null) {
            try {
                return Class.forName(className, false, contextLoader);
            } catch (ClassNotFoundException ignored) {
                // Fall back to Fiximizer's defining loader.
            }
        }
        return Class.forName(className, false, SablePhysicsProfiler.class.getClassLoader());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerListener(Class<?> eventType, Consumer<Object> listener) {
        NeoForge.EVENT_BUS.addListener(
                EventPriority.NORMAL,
                false,
                (Class) eventType,
                (Consumer) listener
        );
    }

    private static void onPrePhysicsTick(Object event) {
        if (!available) {
            return;
        }
        if (!integrationActive()) {
            return;
        }

        try {
            Object physicsSystem = preSystemGetter.invoke(event);
            if (physicsSystem == null) {
                return;
            }

            double timeStep = ((Number) preTimeStepGetter.invoke(event)).doubleValue();
            String dimension = dimensionOf(physicsSystem);
            IdentityHashMap<Object, StartSample> starts = STARTS.get();
            if (starts == null) {
                starts = new IdentityHashMap<>();
                STARTS.set(starts);
            }
            starts.put(
                    physicsSystem,
                    new StartSample(System.nanoTime(), timeStep, dimension)
            );
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException exception) {
            disableAfterRuntimeFailure("Sable pre-physics event could not be read.", exception);
        }
    }

    private static void onPostPhysicsTick(Object event) {
        if (!available) {
            return;
        }
        if (!ModIntegrationManager.isActive("sable")) {
            reset();
            return;
        }

        try {
            Object physicsSystem = postSystemGetter.invoke(event);
            if (physicsSystem == null) {
                return;
            }

            double postTimeStep = ((Number) postTimeStepGetter.invoke(event)).doubleValue();
            IdentityHashMap<Object, StartSample> starts = STARTS.get();
            if (starts == null) {
                return;
            }
            StartSample start = starts.remove(physicsSystem);
            if (start == null) {
                return;
            }

            // Use the pre-event delta: it is the exact timestep for this substep.
            long elapsed = Math.max(0L, System.nanoTime() - start.startedAtNanos());
            double timeStep = Double.isFinite(start.timeStep()) && start.timeStep() > 0.0
                    ? start.timeStep()
                    : postTimeStep;
            WINDOW.record(start.dimension(), elapsed, timeStep);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException exception) {
            disableAfterRuntimeFailure("Sable post-physics event could not be read.", exception);
        }
    }

    private static String dimensionOf(Object physicsSystem)
            throws IllegalAccessException, InvocationTargetException {
        Optional<Method> getter = LEVEL_GETTERS.get(physicsSystem.getClass());
        if (getter.isEmpty()) {
            return "unknown";
        }

        Object value = getter.get().invoke(physicsSystem);
        if (!(value instanceof Level level)) {
            return "unknown";
        }
        return level.dimension().location().toString();
    }

    private static boolean integrationActive() {
        if (ModIntegrationManager.isActive("sable")) {
            wasEnabled = true;
            return true;
        }

        if (wasEnabled) {
            IdentityHashMap<Object, StartSample> starts = STARTS.get();
            if (starts != null) {
                starts.clear();
            }
            STARTS.remove();
            WINDOW.reset();
            wasEnabled = false;
        }
        return false;
    }

    private static void disableAfterRuntimeFailure(String message, Throwable exception) {
        available = false;
        status = "Sable telemetry paused after API error";
        STARTS.remove();
        WINDOW.reset();
        wasEnabled = false;
        logFailure(message, exception);
    }

    private static void logFailure(String message, Throwable exception) {
        if (FAILURE_LOGGED.compareAndSet(false, true)) {
            Fiximizer.LOGGER.warn(message, exception);
        }
    }

    public static boolean available() {
        return available;
    }

    public static String status() {
        return status;
    }

    public static RollingPhysicsWindow.Snapshot latest() {
        if (!ModIntegrationManager.isActive("sable") || !available) {
            return EMPTY_SNAPSHOT;
        }
        return WINDOW.snapshot();
    }

    private record StartSample(long startedAtNanos, double timeStep, String dimension) {
    }
}
