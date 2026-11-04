package dev.leon.claudemcp.reflect;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.Supplier;

/**
 * Registry of object handles ("h:12") handed out to Claude. Objects are held weakly unless pinned;
 * named roots ("$mc", "$player", ...) always resolve to the current object.
 */
public final class Handles implements Overloads.RefResolver {
	private final Map<Integer, WeakReference<Object>> weak = new HashMap<>();
	private final Map<Integer, Object> pinned = new HashMap<>();
	private final IdentityHashMap<Object, Integer> reverse = new IdentityHashMap<>();
	private final Map<String, Supplier<Object>> roots = new LinkedHashMap<>();
	private int next = 1;

	public void root(String name, Supplier<Object> s) {
		roots.put(name, s);
	}

	public Set<String> rootNames() {
		return roots.keySet();
	}

	public synchronized String put(Object o, boolean pin) {
		Integer id = reverse.get(o);
		if (id == null || get(id) == null) {
			id = next++;
			weak.put(id, new WeakReference<>(o));
			reverse.put(o, id);
		}
		if (pin) pinned.put(id, o);
		if (weak.size() > 20000) gc();
		return "h:" + id;
	}

	private synchronized Object get(int id) {
		WeakReference<Object> r = weak.get(id);
		return r == null ? null : r.get();
	}

	private void gc() {
		Iterator<Map.Entry<Integer, WeakReference<Object>>> it = weak.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			if (e.getValue().get() == null) it.remove();
		}
		reverse.keySet().removeIf(o -> !weak.containsKey(reverse.get(o)));
	}

	public synchronized boolean release(String ref) {
		int id = parseId(ref);
		if (id < 0) return false;
		Object o = get(id);
		pinned.remove(id);
		weak.remove(id);
		if (o != null) reverse.remove(o);
		return o != null;
	}

	public synchronized int clear() {
		int n = weak.size();
		weak.clear();
		pinned.clear();
		reverse.clear();
		return n;
	}

	public synchronized int size() {
		return weak.size();
	}

	public synchronized int pinnedCount() {
		return pinned.size();
	}

	static int parseId(String ref) {
		if (ref == null || !ref.startsWith("h:")) return -1;
		try {
			return Integer.parseInt(ref.substring(2));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** Resolves "h:N", "$root" or {"handle":"h:N"}; returns NOT_A_REF otherwise. */
	@Override
	public Object resolve(Object value) {
		String ref = null;
		if (value instanceof String s) ref = s;
		else if (value instanceof Map<?, ?> m && m.get("handle") instanceof String s && m.size() <= 3) ref = s;
		if (ref == null) return Overloads.NOT_A_REF;
		if (ref.startsWith("$")) {
			Supplier<Object> s = roots.get(ref.substring(1));
			if (s == null) return Overloads.NOT_A_REF;
			return s.get();
		}
		int id = parseId(ref);
		if (id < 0) return Overloads.NOT_A_REF;
		Object o = get(id);
		if (o == null) throw new IllegalArgumentException("Handle " + ref + " is unknown or was garbage collected");
		return o;
	}

	/** Like resolve but fails for non-references. */
	public Object require(String ref) {
		Object o = resolve(ref);
		if (o == Overloads.NOT_A_REF) throw new IllegalArgumentException("Not a handle or root: " + ref + " (use h:N or one of $" + String.join(", $", roots.keySet()) + ")");
		return o;
	}
}
