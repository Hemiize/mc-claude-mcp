package dev.leon.claudemcp.mcp;

import java.util.*;

/** Typed access to tool call arguments. */
public final class Args {
	private final Map<String, Object> m;

	public Args(Map<String, Object> m) {
		this.m = m == null ? Map.of() : m;
	}

	public Map<String, Object> raw() {
		return m;
	}

	public boolean has(String k) {
		return m.get(k) != null;
	}

	public Object get(String k) {
		return m.get(k);
	}

	public String str(String k) {
		Object v = m.get(k);
		if (v == null) throw new IllegalArgumentException("Missing argument '" + k + "'");
		return String.valueOf(v);
	}

	public String str(String k, String def) {
		Object v = m.get(k);
		return v == null ? def : String.valueOf(v);
	}

	public double num(String k) {
		Object v = m.get(k);
		if (v == null) throw new IllegalArgumentException("Missing argument '" + k + "'");
		return toNum(k, v);
	}

	public double num(String k, double def) {
		Object v = m.get(k);
		return v == null ? def : toNum(k, v);
	}

	public int integer(String k) {
		return (int) Math.floor(num(k));
	}

	public int integer(String k, int def) {
		return has(k) ? (int) Math.floor(num(k)) : def;
	}

	public boolean bool(String k, boolean def) {
		Object v = m.get(k);
		if (v == null) return def;
		if (v instanceof Boolean b) return b;
		return Boolean.parseBoolean(String.valueOf(v));
	}

	@SuppressWarnings("unchecked")
	public List<Object> list(String k) {
		Object v = m.get(k);
		if (v == null) return List.of();
		if (v instanceof List<?> l) return (List<Object>) l;
		return List.of(v);
	}

	public List<String> strings(String k) {
		List<String> out = new ArrayList<>();
		for (Object o : list(k)) out.add(String.valueOf(o));
		return out;
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> map(String k) {
		Object v = m.get(k);
		if (v == null) return Map.of();
		if (v instanceof Map<?, ?> mm) return (Map<String, Object>) mm;
		throw new IllegalArgumentException("Argument '" + k + "' must be an object");
	}

	private static double toNum(String k, Object v) {
		if (v instanceof Number n) return n.doubleValue();
		try {
			return Double.parseDouble(String.valueOf(v));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Argument '" + k + "' must be a number");
		}
	}
}
