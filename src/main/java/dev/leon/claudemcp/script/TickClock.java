package dev.leon.claudemcp.script;

/** Client tick counter that worker threads can wait on. */
public final class TickClock {
	private static long ticks;

	private TickClock() {}

	public static synchronized void tick() {
		ticks++;
		TickClock.class.notifyAll();
	}

	public static synchronized long now() {
		return ticks;
	}

	/** Waits n client ticks (worker threads only). */
	public static void sleepTicks(long n) throws InterruptedException {
		if (dev.leon.claudemcp.core.MainThread.isMain()) throw new IllegalStateException("sleepTicks cannot be used on the client thread (sync scripts/hooks)");
		long target;
		synchronized (TickClock.class) {
			target = ticks + Math.max(1, n);
		}
		while (true) {
			Guard.check();
			synchronized (TickClock.class) {
				if (ticks >= target) return;
				TickClock.class.wait(100);
			}
		}
	}
}
