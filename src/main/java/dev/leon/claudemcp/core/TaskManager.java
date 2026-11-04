package dev.leon.claudemcp.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Long running actions. Two kinds:
 * <ul>
 *   <li>tick tasks: advanced once per client tick on the main thread (movement, mining, ...)</li>
 *   <li>thread tasks: run on a worker thread (Groovy scripts)</li>
 * </ul>
 * Tasks that use the player's "body" (movement/interaction) are exclusive: starting one cancels the previous.
 */
public final class TaskManager {
	public enum Status { RUNNING, DONE, FAILED, CANCELLED }

	/** A unit of work advanced every tick on the client thread. */
	public interface TickTask {
		/** Called on the client thread every tick. Return true when finished. Throw to fail. */
		boolean tick(Task task) throws Exception;

		/** Called on the client thread when the task ends (any reason). */
		default void stop(Task task) {}

		/** Whether this task controls the player (movement/interaction). */
		default boolean usesBody() {
			return true;
		}
	}

	public interface ThreadWork {
		Object run(Task task) throws Exception;
	}

	public static final class Task {
		public final int id;
		public final String name;
		public final String description;
		public final long startedAt = System.currentTimeMillis();
		final TickTask tick;
		final boolean body;
		volatile Thread thread;
		volatile Status status = Status.RUNNING;
		volatile String progress = "";
		volatile Object result;
		volatile String error;
		volatile long endedAt;
		volatile int ticks;
		final CompletableFuture<Task> done = new CompletableFuture<>();
		/** Free-form state for tick tasks. */
		public final Map<String, Object> state = new HashMap<>();

		Task(int id, String name, String description, TickTask tick, boolean body) {
			this.id = id;
			this.name = name;
			this.description = description;
			this.tick = tick;
			this.body = body;
		}

		public void progress(String p) {
			this.progress = p;
		}

		public int ticks() {
			return ticks;
		}

		public Status status() {
			return status;
		}

		public boolean cancelled() {
			return status == Status.CANCELLED || Thread.currentThread().isInterrupted() && thread == Thread.currentThread();
		}

		public void setResult(Object r) {
			this.result = r;
		}

		public Object result() {
			return result;
		}

		public String error() {
			return error;
		}

		public CompletableFuture<Task> future() {
			return done;
		}

		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", id);
			m.put("name", name);
			m.put("description", description);
			m.put("status", status.name().toLowerCase(Locale.ROOT));
			if (!progress.isEmpty()) m.put("progress", progress);
			if (result != null) m.put("result", result);
			if (error != null) m.put("error", error);
			m.put("elapsed_ms", (endedAt == 0 ? System.currentTimeMillis() : endedAt) - startedAt);
			return m;
		}
	}

	private final AtomicInteger ids = new AtomicInteger();
	private final Map<Integer, Task> tasks = new ConcurrentHashMap<>();
	private final List<Task> tickQueue = new CopyOnWriteArrayList<>();
	private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "claudemcp-worker");
		t.setDaemon(true);
		t.setContextClassLoader(TaskManager.class.getClassLoader());
		return t;
	});
	private final EventBuffer events;
	private Runnable onBodyReleased = () -> {};

	public TaskManager(EventBuffer events) {
		this.events = events;
	}

	public void setOnBodyReleased(Runnable r) {
		this.onBodyReleased = r;
	}

	public Task startTick(String name, String description, TickTask t) {
		Task task = new Task(ids.incrementAndGet(), name, description, t, t.usesBody());
		if (task.body) {
			for (Task other : tickQueue) {
				if (other.body && other.status == Status.RUNNING) cancel(other.id, "replaced by task " + task.id);
			}
		}
		tasks.put(task.id, task);
		tickQueue.add(task);
		trim();
		return task;
	}

	public Task startThread(String name, String description, ThreadWork work) {
		Task task = new Task(ids.incrementAndGet(), name, description, null, false);
		tasks.put(task.id, task);
		workers.submit(() -> {
			task.thread = Thread.currentThread();
			try {
				Object r = work.run(task);
				if (task.status == Status.RUNNING) finish(task, Status.DONE, r, null);
			} catch (CancellationException | InterruptedException e) {
				if (task.status == Status.RUNNING) finish(task, Status.CANCELLED, null, "cancelled");
			} catch (Throwable e) {
				if (task.status == Status.RUNNING) {
					if (Thread.currentThread().isInterrupted() || hasCause(e, InterruptedException.class)) {
						finish(task, Status.CANCELLED, null, "cancelled");
					} else {
						finish(task, Status.FAILED, null, describe(e));
					}
				}
			} finally {
				task.thread = null;
				Thread.interrupted();
			}
		});
		trim();
		return task;
	}

	static boolean hasCause(Throwable t, Class<?> c) {
		for (Throwable x = t; x != null; x = x.getCause()) if (c.isInstance(x)) return true;
		return false;
	}

	public static String describe(Throwable e) {
		Throwable root = e;
		while ((root instanceof CompletionException || root instanceof ExecutionException
				|| root instanceof java.lang.reflect.InvocationTargetException) && root.getCause() != null) {
			root = root.getCause();
		}
		String msg = root.getMessage();
		return root.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
	}

	/** Called every client tick on the main thread. */
	public void tick() {
		for (Task t : tickQueue) {
			if (t.status != Status.RUNNING) {
				tickQueue.remove(t);
				continue;
			}
			try {
				t.ticks++;
				if (t.tick.tick(t)) finish(t, Status.DONE, t.result, null);
			} catch (CancellationException e) {
				finish(t, Status.CANCELLED, null, e.getMessage());
			} catch (Throwable e) {
				finish(t, Status.FAILED, null, describe(e));
			}
		}
	}

	private void finish(Task t, Status s, Object result, String error) {
		synchronized (t) {
			if (t.status != Status.RUNNING) return;
			t.status = s;
			if (result != null) t.result = result;
			t.error = error;
			t.endedAt = System.currentTimeMillis();
		}
		if (t.tick != null) {
			tickQueue.remove(t);
			Runnable stop = () -> {
				try {
					t.tick.stop(t);
				} catch (Throwable ignored) {
				}
				if (t.body && tickQueue.stream().noneMatch(o -> o.body && o.status == Status.RUNNING)) onBodyReleased.run();
			};
			if (MainThread.isMain()) stop.run();
			else net.minecraft.client.Minecraft.getInstance().execute(stop);
		}
		t.done.complete(t);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("task", t.id);
		m.put("name", t.name);
		m.put("status", s.name().toLowerCase(Locale.ROOT));
		if (error != null) m.put("error", error);
		events.emit("task_end", m);
	}

	public boolean cancel(int id, String reason) {
		Task t = tasks.get(id);
		if (t == null || t.status != Status.RUNNING) return false;
		Thread th = t.thread;
		finish(t, Status.CANCELLED, null, reason == null ? "cancelled" : reason);
		if (th != null) th.interrupt();
		return true;
	}

	public int cancelAll(String reason, boolean bodyOnly) {
		int n = 0;
		for (Task t : tasks.values()) {
			if (t.status != Status.RUNNING) continue;
			if (bodyOnly && !t.body) continue;
			if (cancel(t.id, reason)) n++;
		}
		return n;
	}

	public Task get(int id) {
		return tasks.get(id);
	}

	public List<Task> running() {
		List<Task> out = new ArrayList<>();
		for (Task t : tasks.values()) if (t.status == Status.RUNNING) out.add(t);
		out.sort(Comparator.comparingInt(t -> t.id));
		return out;
	}

	public boolean bodyBusy() {
		for (Task t : tickQueue) if (t.body && t.status == Status.RUNNING) return true;
		return false;
	}

	public List<Task> recent(int n) {
		List<Task> all = new ArrayList<>(tasks.values());
		all.sort(Comparator.comparingInt((Task t) -> t.id).reversed());
		return all.subList(0, Math.min(n, all.size()));
	}

	/** Waits for a task to end (from a non-main thread). Returns the task. */
	public Task await(Task t, long timeoutMs) throws InterruptedException {
		try {
			t.done.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (ExecutionException | TimeoutException ignored) {
		}
		return t;
	}

	private void trim() {
		if (tasks.size() < 300) return;
		List<Task> all = new ArrayList<>(tasks.values());
		all.sort(Comparator.comparingInt(t -> t.id));
		for (int i = 0; i < all.size() - 200; i++) {
			Task t = all.get(i);
			if (t.status != Status.RUNNING) tasks.remove(t.id);
		}
	}

	public void shutdown() {
		cancelAll("shutdown", false);
		workers.shutdownNow();
	}
}
