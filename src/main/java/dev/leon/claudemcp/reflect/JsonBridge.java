package dev.leon.claudemcp.reflect;

import java.lang.reflect.Array;
import java.util.*;

/** Converts arbitrary Java objects to JSON-friendly values; complex objects become handles. */
public final class JsonBridge {
	private final Handles handles;
	public int maxElements = 64;

	public JsonBridge(Handles handles) {
		this.handles = handles;
	}

	public Object toJson(Object o, boolean pin) {
		return toJson(o, pin, 2);
	}

	public Object toJson(Object o, boolean pin, int depth) {
		if (o == null) return null;
		if (o instanceof String || o instanceof Boolean) return o;
		if (o instanceof Number n) {
			if (n instanceof Double d && (d.isNaN() || d.isInfinite())) return d.toString();
			if (n instanceof Float f && (f.isNaN() || f.isInfinite())) return f.toString();
			return n;
		}
		if (o instanceof Character c) return c.toString();
		if (o instanceof Enum<?> e) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("enum", e.name());
			m.put("class", e.getDeclaringClass().getName());
			return m;
		}
		if (o instanceof Class<?> c) return describeRef(o, pin, "class " + c.getName());
		if (depth > 0) {
			if (o.getClass().isArray()) {
				int len = Array.getLength(o);
				if (len <= maxElements) {
					List<Object> l = new ArrayList<>();
					for (int i = 0; i < len; i++) l.add(toJson(Array.get(o, i), pin, depth - 1));
					return l;
				}
			} else if (o instanceof Collection<?> col && col.size() <= maxElements) {
				List<Object> l = new ArrayList<>();
				for (Object e : col) l.add(toJson(e, pin, depth - 1));
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("handle", handles.put(o, pin));
				m.put("class", o.getClass().getName());
				m.put("size", col.size());
				m.put("items", l);
				return m;
			} else if (o instanceof Map<?, ?> map && map.size() <= maxElements) {
				Map<String, Object> entries = new LinkedHashMap<>();
				for (var e : map.entrySet()) entries.put(String.valueOf(e.getKey()), toJson(e.getValue(), pin, depth - 1));
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("handle", handles.put(o, pin));
				m.put("class", o.getClass().getName());
				m.put("size", map.size());
				m.put("entries", entries);
				return m;
			} else if (o instanceof Optional<?> opt) {
				return opt.isPresent() ? Map.of("optional", toJson(opt.get(), pin, depth - 1)) : Map.of("optional", "empty");
			}
		}
		String ts;
		try {
			ts = String.valueOf(o);
		} catch (Throwable t) {
			ts = "<toString failed: " + t + ">";
		}
		return describeRef(o, pin, ts);
	}

	private Map<String, Object> describeRef(Object o, boolean pin, String ts) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("handle", handles.put(o, pin));
		m.put("class", o.getClass().getName());
		m.put("toString", ts.length() > 300 ? ts.substring(0, 300) + "..." : ts);
		if (o instanceof Collection<?> c) m.put("size", c.size());
		else if (o instanceof Map<?, ?> mm) m.put("size", mm.size());
		else if (o.getClass().isArray()) m.put("length", Array.getLength(o));
		return m;
	}
}
