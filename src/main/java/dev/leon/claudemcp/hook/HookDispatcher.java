package dev.leon.claudemcp.hook;

import dev.leon.claudemcp.ClaudeMcp;
import dev.leon.claudemcp.reflect.Overloads;
import groovy.lang.Script;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Runtime part of method hooks: called from the inlined advice of every hooked method. */
public final class HookDispatcher {
	private HookDispatcher() {}

	/** All hooks of one method. */
	static final class MethodHooks {
		final String key;
		final Method method;
		final List<JavaHook> hooks = new CopyOnWriteArrayList<>();

		MethodHooks(String key, Method method) {
			this.key = key;
			this.method = method;
		}
	}

	/** One installed hook. */
	public static final class JavaHook {
		public final String id;
		public final String mode; // trace | code
		final MethodHooks target;
		final Class<? extends Script> before, after;
		final String beforeSrc, afterSrc;
		final int maxCalls;
		final int traceLimit;
		volatile boolean enabled = true;
		volatile long calls;
		volatile int errors, consecutiveErrors;
		volatile String lastError;
		final ArrayDeque<Map<String, Object>> trace = new ArrayDeque<>();
		long traceSeq;

		JavaHook(String id, String mode, MethodHooks target, Class<? extends Script> before, Class<? extends Script> after,
				String beforeSrc, String afterSrc, int maxCalls, int traceLimit) {
			this.id = id;
			this.mode = mode;
			this.target = target;
			this.before = before;
			this.after = after;
			this.beforeSrc = beforeSrc;
			this.afterSrc = afterSrc;
			this.maxCalls = maxCalls;
			this.traceLimit = traceLimit;
		}

		public Map<String, Object> describe() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", id);
			m.put("method", target.method.getDeclaringClass().getName() + "." + target.method.getName() + Overloads.sigOf(target.method));
			m.put("mode", mode);
			m.put("enabled", enabled);
			m.put("calls", calls);
			m.put("errors", errors);
			if (lastError != null) m.put("last_error", lastError);
			if (maxCalls > 0) m.put("max_calls", maxCalls);
			if (beforeSrc != null) m.put("before", beforeSrc.length() > 200 ? beforeSrc.substring(0, 200) + "..." : beforeSrc);
			if (afterSrc != null) m.put("after", afterSrc.length() > 200 ? afterSrc.substring(0, 200) + "..." : afterSrc);
			return m;
		}

		public synchronized List<Map<String, Object>> traceSince(long since, int limit) {
			List<Map<String, Object>> out = new ArrayList<>();
			for (Map<String, Object> e : trace) if (((Number) e.get("seq")).longValue() > since) out.add(e);
			if (out.size() > limit) out = out.subList(out.size() - limit, out.size());
			return out;
		}

		synchronized void record(Map<String, Object> e) {
			e.put("seq", ++traceSeq);
			trace.addLast(e);
			while (trace.size() > traceLimit) trace.removeFirst();
		}
	}

	/** Control object available as {@code hook} in hook scripts. */
	public static final class Control {
		boolean cancelled;
		Object value;
		boolean resultSet;
		public final String id;

		Control(String id) {
			this.id = id;
		}

		/** before: skip the original method and return this value. */
		public void cancel(Object returnValue) {
			cancelled = true;
			value = returnValue;
		}

		public void cancel() {
			cancel(null);
		}

		/** after: replace the return value. */
		public void setResult(Object v) {
			resultSet = true;
			value = v;
		}
	}

	private record Frame(String key, boolean active, Object cancelValue) {}

	static final Map<String, MethodHooks> HOOKS = new ConcurrentHashMap<>();
	private static final ThreadLocal<ArrayDeque<Frame>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);
	private static final ThreadLocal<Boolean> IN_HOOK = ThreadLocal.withInitial(() -> false);

	public static boolean enter(String key, Object self, Object[] args) {
		MethodHooks mh = HOOKS.get(key);
		if (mh == null) return false;
		ArrayDeque<Frame> frames = FRAMES.get();
		if (IN_HOOK.get()) {
			frames.push(new Frame(key, false, null));
			return false;
		}
		IN_HOOK.set(true);
		boolean skip = false;
		Object cancelValue = null;
		try {
			for (JavaHook h : mh.hooks) {
				if (!h.enabled || h.before == null) continue;
				Control c = new Control(h.id);
				Map<String, Object> vars = vars(h, self, args, c);
				if (run(h, vars)) coerceArgs(mh.method, args);
				if (c.cancelled) {
					skip = true;
					cancelValue = c.value;
					break;
				}
			}
		} finally {
			IN_HOOK.set(false);
		}
		frames.push(new Frame(key, true, cancelValue));
		return skip;
	}

	public static Object exit(String key, Object self, Object[] args, Object ret, Throwable thrown, boolean skipped) {
		ArrayDeque<Frame> frames = FRAMES.get();
		Frame f = frames.peek();
		if (f == null || !f.key.equals(key)) return ret;
		frames.pop();
		if (frames.isEmpty()) FRAMES.remove();
		MethodHooks mh = HOOKS.get(key);
		if (mh == null || !f.active) return ret;
		Object result = skipped ? f.cancelValue : ret;
		IN_HOOK.set(true);
		try {
			for (JavaHook h : mh.hooks) {
				if (!h.enabled) continue;
				h.calls++;
				if ("trace".equals(h.mode)) {
					Map<String, Object> e = new LinkedHashMap<>();
					e.put("time", System.currentTimeMillis());
					e.put("thread", Thread.currentThread().getName());
					if (self != null) e.put("self", summary(self));
					List<Object> as = new ArrayList<>();
					for (Object a : args) as.add(summary(a));
					e.put("args", as);
					if (mh.method.getReturnType() != void.class) e.put("result", summary(result));
					if (thrown != null) e.put("thrown", thrown.toString());
					if (skipped) e.put("cancelled", true);
					h.record(e);
				}
				if (h.after != null) {
					Control c = new Control(h.id);
					Map<String, Object> vars = vars(h, self, args, c);
					vars.put("result", result);
					vars.put("thrown", thrown);
					vars.put("cancelled", skipped);
					run(h, vars);
					if (c.resultSet) result = c.value;
				}
				if (h.maxCalls > 0 && h.calls >= h.maxCalls) {
					h.enabled = false;
					ClaudeMcp.events().emit("java_hook_done", "id", h.id, "calls", h.calls);
				}
			}
		} finally {
			IN_HOOK.set(false);
		}
		return coerceReturn(mh.method, result, ret);
	}

	private static Map<String, Object> vars(JavaHook h, Object self, Object[] args, Control c) {
		Map<String, Object> vars = new HashMap<>();
		vars.put("self", self);
		vars.put("args", args);
		vars.put("method", h.target.method);
		vars.put("hook", c);
		vars.put("thread", Thread.currentThread().getName());
		return vars;
	}

	/** Runs a hook script; returns true if it ran without error. */
	private static boolean run(JavaHook h, Map<String, Object> vars) {
		Class<? extends Script> cls = vars.containsKey("result") ? h.after : h.before;
		try {
			ClaudeMcp.scripts().run(cls, vars, h.calls <= 3 ? 500 : 50, null);
			h.consecutiveErrors = 0;
			return true;
		} catch (Throwable t) {
			h.errors++;
			h.consecutiveErrors++;
			h.lastError = t instanceof InterruptedException ? "time limit exceeded" : t.toString();
			if (h.consecutiveErrors >= 5) {
				h.enabled = false;
				ClaudeMcp.events().emit("java_hook_disabled", "id", h.id, "error", h.lastError);
			}
			return false;
		}
	}

	private static void coerceArgs(Method m, Object[] args) {
		Class<?>[] ps = m.getParameterTypes();
		for (int i = 0; i < ps.length && i < args.length; i++) {
			Object v = args[i];
			Class<?> p = ps[i];
			if (v == null) {
				if (p.isPrimitive()) args[i] = defaultValue(p);
				continue;
			}
			if (!Overloads.box(p).isInstance(v)) {
				try {
					args[i] = Overloads.convert(v, p, ClaudeMcp.handles());
				} catch (Throwable ignored) {
				}
			}
		}
	}

	private static Object coerceReturn(Method m, Object v, Object original) {
		Class<?> r = m.getReturnType();
		if (r == void.class) return null;
		if (v == null) return r.isPrimitive() ? defaultValue(r) : null;
		if (Overloads.box(r).isInstance(v)) return v;
		try {
			Object c = Overloads.convert(v, r, ClaudeMcp.handles());
			if (c != null && Overloads.box(r).isInstance(c)) return c;
		} catch (Throwable ignored) {
		}
		return original; // incompatible value: keep the original result instead of crashing the game
	}

	static Object defaultValue(Class<?> p) {
		if (p == boolean.class) return false;
		if (p == char.class) return (char) 0;
		if (p == long.class) return 0L;
		if (p == float.class) return 0f;
		if (p == double.class) return 0d;
		if (p == byte.class) return (byte) 0;
		if (p == short.class) return (short) 0;
		return 0;
	}

	static Object summary(Object o) {
		if (o == null || o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof Enum<?>) return o == null ? null : o.toString();
		String s;
		try {
			s = String.valueOf(o);
		} catch (Throwable t) {
			s = o.getClass().getName();
		}
		if (s.length() > 200) s = s.substring(0, 200) + "...";
		if (o instanceof String) return s;
		return o.getClass().getSimpleName() + ": " + s;
	}

	static boolean isStatic(Method m) {
		return Modifier.isStatic(m.getModifiers());
	}
}
