package dev.leon.claudemcp.reflect;

import java.lang.reflect.*;
import java.util.*;

/**
 * Converts JSON-ish argument values (null, Boolean, Number, String, List, Map) into Java values for a
 * given parameter type and picks the best matching overload. Free of Minecraft dependencies.
 */
public final class Overloads {
	/** Resolves references such as "h:12", "$player" or {"handle":"h:12"} to live objects. */
	public interface RefResolver {
		/** @return the referenced object, or {@link #NOT_A_REF} if the value is no reference. */
		Object resolve(Object value);
	}

	public static final Object NOT_A_REF = new Object();
	static final int IMPOSSIBLE = 1_000_000;

	private Overloads() {}

	public static final class NoMatchException extends RuntimeException {
		public NoMatchException(String msg) {
			super(msg);
		}
	}

	private static final Map<Class<?>, Class<?>> BOX = Map.of(
			int.class, Integer.class, long.class, Long.class, double.class, Double.class, float.class, Float.class,
			short.class, Short.class, byte.class, Byte.class, boolean.class, Boolean.class, char.class, Character.class);

	public static Class<?> box(Class<?> c) {
		return c.isPrimitive() ? BOX.get(c) : c;
	}

	/** Cost of converting {@code v} to {@code type}; {@link #IMPOSSIBLE} if not possible. */
	public static int cost(Object v, Type genericType, RefResolver refs) {
		Class<?> type = raw(genericType);
		Object ref = refs == null ? NOT_A_REF : refs.resolve(v);
		if (ref != NOT_A_REF) {
			if (ref == null) return type.isPrimitive() ? IMPOSSIBLE : 1;
			if (box(type).isInstance(ref)) return type == ref.getClass() ? 0 : 1;
			// a String parameter receiving something that looked like a ref: allow the literal string
			if (v instanceof String && type == String.class) return 3;
			return IMPOSSIBLE;
		}
		if (v == null) return type.isPrimitive() ? IMPOSSIBLE : 1;
		Class<?> bt = box(type);
		if (v instanceof Boolean) return bt == Boolean.class ? 0 : bt == Object.class ? 2 : IMPOSSIBLE;
		if (v instanceof Number n) {
			boolean integral = isIntegral(n);
			if (bt == Integer.class) return integral && fits(n, Integer.MIN_VALUE, Integer.MAX_VALUE) ? 0 : IMPOSSIBLE;
			if (bt == Long.class) return integral ? 1 : IMPOSSIBLE;
			if (bt == Double.class) return integral ? 2 : 0;
			if (bt == Float.class) return integral ? 3 : 1;
			if (bt == Short.class) return integral && fits(n, Short.MIN_VALUE, Short.MAX_VALUE) ? 2 : IMPOSSIBLE;
			if (bt == Byte.class) return integral && fits(n, Byte.MIN_VALUE, Byte.MAX_VALUE) ? 2 : IMPOSSIBLE;
			if (bt == Number.class || bt == Object.class) return 3;
			return IMPOSSIBLE;
		}
		if (v instanceof String s) {
			if (type == String.class || type == CharSequence.class) return 0;
			if (bt == Character.class) return s.length() == 1 ? 1 : IMPOSSIBLE;
			if (type.isEnum()) return enumConstant(type, s) != null ? 1 : IMPOSSIBLE;
			if (type == Object.class) return 2;
			return IMPOSSIBLE;
		}
		if (v instanceof List<?> l) {
			if (type.isArray()) {
				int c = 1;
				for (Object e : l) {
					int ec = cost(e, type.getComponentType(), refs);
					if (ec >= IMPOSSIBLE) return IMPOSSIBLE;
					c += ec;
				}
				return c;
			}
			if (type.isAssignableFrom(ArrayList.class)) return 2;
			return IMPOSSIBLE;
		}
		if (v instanceof Map<?, ?> m) {
			if (m.containsKey("enum") && m.containsKey("class") && type.isEnum()) return 1;
			if (type.isAssignableFrom(LinkedHashMap.class)) return 2;
			return IMPOSSIBLE;
		}
		return box(type).isInstance(v) ? 0 : IMPOSSIBLE;
	}

	public static Object convert(Object v, Type genericType, RefResolver refs) {
		Class<?> type = raw(genericType);
		Object ref = refs == null ? NOT_A_REF : refs.resolve(v);
		if (ref != NOT_A_REF && !(v instanceof String && type == String.class && (ref == null || !(ref instanceof String)))) {
			return ref;
		}
		if (v == null) return null;
		Class<?> bt = box(type);
		if (v instanceof Number n) {
			if (bt == Integer.class) return n.intValue();
			if (bt == Long.class) return n.longValue();
			if (bt == Double.class) return n.doubleValue();
			if (bt == Float.class) return n.floatValue();
			if (bt == Short.class) return n.shortValue();
			if (bt == Byte.class) return n.byteValue();
			return n;
		}
		if (v instanceof String s) {
			if (bt == Character.class) return s.charAt(0);
			if (type.isEnum()) return enumConstant(type, s);
			return s;
		}
		if (v instanceof List<?> l) {
			if (type.isArray()) {
				Class<?> ct = type.getComponentType();
				Object arr = Array.newInstance(ct, l.size());
				for (int i = 0; i < l.size(); i++) Array.set(arr, i, convert(l.get(i), ct, refs));
				return arr;
			}
			List<Object> out = new ArrayList<>();
			Type et = Object.class;
			if (genericType instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1) et = pt.getActualTypeArguments()[0];
			for (Object e : l) out.add(convert(e, et, refs));
			return out;
		}
		if (v instanceof Map<?, ?> m) {
			if (m.containsKey("enum") && type.isEnum()) return enumConstant(type, String.valueOf(m.get("enum")));
			Map<Object, Object> out = new LinkedHashMap<>();
			for (var e : m.entrySet()) out.put(e.getKey(), convert(e.getValue(), Object.class, refs));
			return out;
		}
		return v;
	}

	/** Chooses the executable (method or constructor) whose parameters best match the arguments. */
	public static <T extends Executable> T choose(List<T> candidates, List<?> args, RefResolver refs, String signature) {
		if (signature != null && !signature.isBlank()) {
			String want = normalizeSig(signature);
			for (T c : candidates) if (normalizeSig(sigOf(c)).equals(want)) return c;
			throw new NoMatchException("No overload with signature " + signature + ". Candidates: " + describe(candidates));
		}
		T best = null;
		int bestCost = IMPOSSIBLE;
		boolean tie = false;
		for (T c : candidates) {
			int cost = totalCost(c, args, refs);
			if (cost < bestCost) {
				best = c;
				bestCost = cost;
				tie = false;
			} else if (cost == bestCost && cost < IMPOSSIBLE) {
				tie = true;
			}
		}
		if (best == null) throw new NoMatchException("No overload accepts these arguments. Candidates: " + describe(candidates));
		if (tie) throw new NoMatchException("Ambiguous call, pass 'signature'. Candidates: " + describe(candidates));
		return best;
	}

	static int totalCost(Executable e, List<?> args, RefResolver refs) {
		Type[] params = e.getGenericParameterTypes();
		if (!e.isVarArgs()) {
			if (params.length != args.size()) return IMPOSSIBLE;
			int sum = 0;
			for (int i = 0; i < params.length; i++) {
				sum += cost(args.get(i), params[i], refs);
				if (sum >= IMPOSSIBLE) return IMPOSSIBLE;
			}
			return sum;
		}
		// varargs: either the array itself in the last position, or spread arguments
		int fixed = params.length - 1;
		if (args.size() < fixed) return IMPOSSIBLE;
		int sum = 0;
		for (int i = 0; i < fixed; i++) {
			sum += cost(args.get(i), params[i], refs);
			if (sum >= IMPOSSIBLE) return IMPOSSIBLE;
		}
		if (args.size() == params.length && args.get(fixed) instanceof List<?>) {
			int c = cost(args.get(fixed), params[fixed], refs);
			return c >= IMPOSSIBLE ? IMPOSSIBLE : sum + c;
		}
		Class<?> ct = raw(params[fixed]).getComponentType();
		for (int i = fixed; i < args.size(); i++) {
			sum += cost(args.get(i), ct, refs);
			if (sum >= IMPOSSIBLE) return IMPOSSIBLE;
		}
		return sum + 5;
	}

	/** Converts the arguments for the chosen executable (handles varargs spreading). */
	public static Object[] convertArgs(Executable e, List<?> args, RefResolver refs) {
		Type[] params = e.getGenericParameterTypes();
		if (!e.isVarArgs() || (args.size() == params.length && args.get(params.length - 1) instanceof List<?>)) {
			Object[] out = new Object[params.length];
			for (int i = 0; i < params.length; i++) out[i] = convert(args.get(i), params[i], refs);
			return out;
		}
		int fixed = params.length - 1;
		Object[] out = new Object[params.length];
		for (int i = 0; i < fixed; i++) out[i] = convert(args.get(i), params[i], refs);
		Class<?> ct = raw(params[fixed]).getComponentType();
		Object arr = Array.newInstance(ct, args.size() - fixed);
		for (int i = fixed; i < args.size(); i++) Array.set(arr, i - fixed, convert(args.get(i), ct, refs));
		out[fixed] = arr;
		return out;
	}

	public static String sigOf(Executable e) {
		StringJoiner j = new StringJoiner(",", "(", ")");
		for (Class<?> p : e.getParameterTypes()) j.add(p.getTypeName());
		return j.toString();
	}

	static String normalizeSig(String s) {
		String t = s.replace(" ", "");
		if (!t.startsWith("(")) t = "(" + t + ")";
		// allow simple names: compare only simple class names
		StringJoiner j = new StringJoiner(",", "(", ")");
		String inner = t.substring(1, t.length() - 1);
		if (!inner.isEmpty()) {
			for (String part : inner.split(",")) {
				int dot = part.lastIndexOf('.');
				int dollar = part.lastIndexOf('$');
				j.add(part.substring(Math.max(dot, dollar) + 1));
			}
		}
		return j.toString();
	}

	public static String describe(List<? extends Executable> cs) {
		StringJoiner j = new StringJoiner("; ");
		for (Executable c : cs) j.add(c.getName() + sigOf(c));
		return j.toString();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	static Object enumConstant(Class<?> type, String name) {
		for (Object c : type.getEnumConstants()) {
			if (((Enum) c).name().equalsIgnoreCase(name)) return c;
		}
		return null;
	}

	static Class<?> raw(Type t) {
		if (t instanceof Class<?> c) return c;
		if (t instanceof ParameterizedType p) return (Class<?>) p.getRawType();
		if (t instanceof GenericArrayType g) return Array.newInstance(raw(g.getGenericComponentType()), 0).getClass();
		if (t instanceof TypeVariable<?> tv) return tv.getBounds().length > 0 ? raw(tv.getBounds()[0]) : Object.class;
		if (t instanceof WildcardType w) return w.getUpperBounds().length > 0 ? raw(w.getUpperBounds()[0]) : Object.class;
		return Object.class;
	}

	private static boolean isIntegral(Number n) {
		if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) return true;
		double d = n.doubleValue();
		return !Double.isInfinite(d) && d == Math.rint(d) && Math.abs(d) < 9.007199254740992E15;
	}

	private static boolean fits(Number n, long min, long max) {
		long l = n.longValue();
		return l >= min && l <= max;
	}
}
