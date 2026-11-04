package dev.leon.claudemcp.core;

import java.util.*;
import java.util.function.Consumer;

/** Ring buffer of game events (chat, damage, task finished, ...) that Claude polls via get_events. */
public final class EventBuffer {
	public record Event(long seq, long time, String type, Map<String, Object> data) {
		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("seq", seq);
			m.put("time", time);
			m.put("type", type);
			m.put("data", data);
			return m;
		}
	}

	private final int capacity;
	private final ArrayDeque<Event> events = new ArrayDeque<>();
	private final List<Consumer<Event>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
	private long seq;

	public EventBuffer(int capacity) {
		this.capacity = capacity;
	}

	public void addListener(Consumer<Event> l) {
		listeners.add(l);
	}

	public void emit(String type, Map<String, Object> data) {
		Event e;
		synchronized (this) {
			e = new Event(++seq, System.currentTimeMillis(), type, data == null ? Map.of() : data);
			events.addLast(e);
			while (events.size() > capacity) events.removeFirst();
			notifyAll();
		}
		for (Consumer<Event> l : listeners) {
			try {
				l.accept(e);
			} catch (Throwable ignored) {
			}
		}
	}

	public void emit(String type, Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
		emit(type, m);
	}

	public synchronized long lastSeq() {
		return seq;
	}

	/** Returns events with seq &gt; since, optionally waiting up to waitMs for at least one to arrive. */
	public synchronized List<Event> since(long since, Set<String> types, int limit, long waitMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + waitMs;
		while (true) {
			List<Event> out = new ArrayList<>();
			for (Event e : events) {
				if (e.seq <= since) continue;
				if (types != null && !types.isEmpty() && !types.contains(e.type)) continue;
				out.add(e);
			}
			long left = deadline - System.currentTimeMillis();
			if (!out.isEmpty() || left <= 0) {
				if (out.size() > limit) out = out.subList(out.size() - limit, out.size());
				return out;
			}
			wait(left);
		}
	}
}
