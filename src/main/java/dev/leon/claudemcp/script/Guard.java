package dev.leon.claudemcp.script;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;

/**
 * Interrupt checks injected into every Groovy script (via {@code @ConditionalInterrupt}) at loop heads
 * and method entries, so scripts can be cancelled and time limited without interrupting the game thread.
 * Guards nest (a hooked method may run a hook script while another script is running on that thread).
 */
public final class Guard {
	private record State(long deadlineNanos, BooleanSupplier cancelled) {}

	private static final ThreadLocal<ArrayDeque<State>> STATE = ThreadLocal.withInitial(ArrayDeque::new);

	private Guard() {}

	/** Used by the injected {@code @ConditionalInterrupt} closure. */
	public static boolean expired() {
		State s = STATE.get().peek();
		if (s == null) return Thread.currentThread().isInterrupted();
		if (s.deadlineNanos != 0 && System.nanoTime() > s.deadlineNanos) return true;
		return s.cancelled != null && s.cancelled.getAsBoolean();
	}

	public static void enter(long timeoutMillis, BooleanSupplier cancelled) {
		long dl = timeoutMillis <= 0 ? 0 : System.nanoTime() + timeoutMillis * 1_000_000L;
		STATE.get().push(new State(dl, cancelled));
	}

	public static void exit() {
		ArrayDeque<State> d = STATE.get();
		d.poll();
		if (d.isEmpty()) STATE.remove();
	}

	/** Throws if the current script should stop. */
	public static void check() {
		if (expired()) throw new java.util.concurrent.CancellationException("script cancelled or timed out");
	}

	public static final String PREFIX = "@groovy.transform.ConditionalInterrupt({ dev.leon.claudemcp.script.Guard.expired() }) import java.lang.Object; ";
}
