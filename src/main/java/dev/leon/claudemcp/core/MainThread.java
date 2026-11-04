package dev.leon.claudemcp.core;

import net.minecraft.client.Minecraft;

import java.util.concurrent.*;

/** Runs code on the Minecraft client (render) thread and waits for the result. */
public final class MainThread {
	private MainThread() {}

	public interface ThrowingSupplier<T> {
		T get() throws Exception;
	}

	public static boolean isMain() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.isSameThread();
	}

	public static <T> T call(ThrowingSupplier<T> s) {
		return call(s, 15_000);
	}

	public static <T> T call(ThrowingSupplier<T> s, long timeoutMs) {
		if (isMain()) {
			try {
				return s.get();
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}
		CompletableFuture<T> f = new CompletableFuture<>();
		Minecraft.getInstance().execute(() -> {
			try {
				f.complete(s.get());
			} catch (Throwable t) {
				f.completeExceptionally(t);
			}
		});
		try {
			return f.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (ExecutionException e) {
			Throwable c = e.getCause();
			if (c instanceof RuntimeException re) throw re;
			if (c instanceof Error er) throw er;
			throw new CompletionException(c);
		} catch (TimeoutException e) {
			throw new RuntimeException("Timed out waiting for the client thread (game frozen or loading?)");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CancellationException("interrupted");
		}
	}

	public static void run(Runnable r) {
		call(() -> {
			r.run();
			return null;
		});
	}
}
