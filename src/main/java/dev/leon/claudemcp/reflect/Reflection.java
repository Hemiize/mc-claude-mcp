package dev.leon.claudemcp.reflect;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Reflection operations used by the java_* tools. */
public final class Reflection {
	private final Handles handles;
	private final JsonBridge json;
	private final ClassLoader loader;
	private final List<Path> classRoots;
	private volatile List<String> index;

	public Reflection(Handles handles, JsonBridge json, ClassLoader loader, List<Path> classRoots) {
		this.handles = handles;
		this.json = json;
		this.loader = loader;
		this.classRoots = classRoots;
	}

	public Handles handles() {
		return handles;
	}

	public JsonBridge json() {
		return json;
	}

	// ------------------------------------------------------------ classes

	private List<String> index() {
		List<String> idx = index;
		if (idx != null) return idx;
		List<String> out = new ArrayList<>();
		for (Path root : classRoots) {
			try (Stream<Path> s = Files.walk(root)) {
				s.forEach(p -> {
					String f = p.toString();
					if (!f.endsWith(".class") || f.endsWith("module-info.class") || f.endsWith("package-info.class")) return;
					String rel = root.relativize(p).toString().replace('\\', '/').replace('/', '.');
					out.add(rel.substring(0, rel.length() - 6));
				});
			} catch (IOException | UncheckedIOException ignored) {
			}
		}
		Collections.sort(out);
		index = out;
		return out;
	}

	public List<String> findClasses(String pattern, int limit) {
		String p = pattern.toLowerCase(Locale.ROOT);
		boolean glob = p.contains("*");
		String regex = glob ? p.replace(".", "\\.").replace("$", "\\$").replace("*", ".*") : null;
		List<String> exact = new ArrayList<>(), simple = new ArrayList<>(), other = new ArrayList<>();
		for (String c : index()) {
			String lc = c.toLowerCase(Locale.ROOT);
			String sn = lc.substring(lc.lastIndexOf('.') + 1);
			if (glob) {
				if (lc.matches(regex) || sn.matches(regex)) other.add(c);
			} else if (sn.equals(p) || lc.equals(p)) exact.add(c);
			else if (sn.startsWith(p)) simple.add(c);
			else if (lc.contains(p)) other.add(c);
		}
		List<String> all = new ArrayList<>(exact);
		all.addAll(simple);
		all.addAll(other);
		return all.subList(0, Math.min(limit, all.size()));
	}

	public Class<?> loadClass(String name) {
		String n = name.trim();
		Map<String, Class<?>> prim = Map.of("int", int.class, "long", long.class, "double", double.class, "float", float.class,
				"boolean", boolean.class, "byte", byte.class, "short", short.class, "char", char.class, "void", void.class);
		if (prim.containsKey(n)) return prim.get(n);
		try {
			return Class.forName(n, false, loader);
		} catch (ClassNotFoundException e) {
			// try java.lang / nested classes / simple names from the index
			for (String cand : List.of("java.lang." + n, "java.util." + n)) {
				try {
					return Class.forName(cand, false, loader);
				} catch (ClassNotFoundException ignored) {
				}
			}
			if (n.contains(".")) {
				int dot = n.lastIndexOf('.');
				try {
					return Class.forName(n.substring(0, dot) + "$" + n.substring(dot + 1), false, loader);
				} catch (ClassNotFoundException ignored) {
				}
			}
			List<String> matches = new ArrayList<>();
			for (String c : index()) {
				String sn = c.substring(c.lastIndexOf('.') + 1);
				if (sn.equals(n) || sn.endsWith("$" + n)) matches.add(c);
			}
			if (matches.size() == 1) {
				try {
					return Class.forName(matches.getFirst(), false, loader);
				} catch (ClassNotFoundException ignored) {
				}
			}
			if (matches.size() > 1) throw new IllegalArgumentException("Ambiguous class name " + n + ": " + matches.subList(0, Math.min(10, matches.size())));
			throw new IllegalArgumentException("Class not found: " + n);
		}
	}

	/** A target is either an object (handle/root) or a class (for static access). */
	public record Target(Object instance, Class<?> type) {}

	public Target target(String t) {
		if (t.startsWith("h:") || t.startsWith("$")) {
			Object o = handles.require(t);
			if (o == null) throw new IllegalArgumentException(t + " is null");
			if (o instanceof Class<?> c) return new Target(null, c);
			return new Target(o, o.getClass());
		}
		return new Target(null, loadClass(t));
	}

	// ------------------------------------------------------------ describe

	public Map<String, Object> describe(String targetRef, String filter, boolean inherited, boolean includePrivate, int limit) {
		Target t = target(targetRef);
		Class<?> c = t.type();
		String f = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("class", c.getName());
		if (c.getSuperclass() != null) m.put("superclass", c.getSuperclass().getName());
		List<String> ifs = new ArrayList<>();
		for (Class<?> i : c.getInterfaces()) ifs.add(i.getName());
		if (!ifs.isEmpty()) m.put("interfaces", ifs);
		m.put("modifiers", Modifier.toString(c.getModifiers()));
		if (c.isEnum()) {
			List<String> consts = new ArrayList<>();
			for (Object o : c.getEnumConstants()) consts.add(((Enum<?>) o).name());
			m.put("enum_constants", consts);
		}
		List<Map<String, Object>> fields = new ArrayList<>();
		List<String> methods = new ArrayList<>();
		List<String> ctors = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Class<?> k = c; k != null; k = inherited ? k.getSuperclass() : null) {
			for (Field fd : k.getDeclaredFields()) {
				if (!includePrivate && !Modifier.isPublic(fd.getModifiers())) continue;
				if (!f.isEmpty() && !fd.getName().toLowerCase(Locale.ROOT).contains(f)) continue;
				if (fd.isSynthetic()) continue;
				if (fields.size() >= limit) break;
				Map<String, Object> fm = new LinkedHashMap<>();
				fm.put("name", fd.getName());
				fm.put("type", fd.getGenericType().getTypeName());
				fm.put("modifiers", Modifier.toString(fd.getModifiers()));
				if (k != c) fm.put("declared_in", k.getSimpleName());
				fields.add(fm);
			}
			for (Method md : k.getDeclaredMethods()) {
				if (!includePrivate && !Modifier.isPublic(md.getModifiers())) continue;
				if (md.isSynthetic() || md.isBridge()) continue;
				if (!f.isEmpty() && !md.getName().toLowerCase(Locale.ROOT).contains(f)) continue;
				String sig = Modifier.toString(md.getModifiers()) + " " + md.getGenericReturnType().getTypeName() + " " + md.getName()
						+ Overloads.sigOf(md) + (k != c ? "  [" + k.getSimpleName() + "]" : "");
				String key = md.getName() + Overloads.sigOf(md);
				if (seen.add(key) && methods.size() < limit) methods.add(sig.trim());
			}
			if (k == c) {
				for (Constructor<?> ct : k.getDeclaredConstructors()) {
					if (!includePrivate && !Modifier.isPublic(ct.getModifiers())) continue;
					ctors.add(Modifier.toString(ct.getModifiers()) + " " + c.getSimpleName() + Overloads.sigOf(ct));
				}
			}
			if (k == Object.class) break;
		}
		m.put("fields", fields);
		m.put("methods", methods);
		if (!ctors.isEmpty()) m.put("constructors", ctors);
		if (t.instance() != null) m.put("handle", handles.put(t.instance(), false));
		return m;
	}

	// ------------------------------------------------------------ fields

	static Field findField(Class<?> c, String name) {
		for (Class<?> k = c; k != null; k = k.getSuperclass()) {
			try {
				Field f = k.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException ignored) {
			}
		}
		throw new IllegalArgumentException("No field '" + name + "' in " + c.getName());
	}

	public Object getField(String targetRef, String name) throws IllegalAccessException {
		Target t = target(targetRef);
		Object cur = t.instance();
		Class<?> type = t.type();
		// dotted path: field.field.field
		String[] parts = name.split("\\.");
		for (int i = 0; i < parts.length; i++) {
			Field f = findField(type, parts[i]);
			if (cur == null && !Modifier.isStatic(f.getModifiers())) throw new IllegalArgumentException("Field " + parts[i] + " is not static; pass an object handle");
			cur = f.get(Modifier.isStatic(f.getModifiers()) ? null : cur);
			if (i < parts.length - 1) {
				if (cur == null) throw new IllegalArgumentException("Null at " + parts[i]);
				type = cur.getClass();
			}
		}
		return cur;
	}

	public Object setField(String targetRef, String name, Object value) throws IllegalAccessException {
		Target t = target(targetRef);
		Field f = findField(t.type(), name);
		if (t.instance() == null && !Modifier.isStatic(f.getModifiers())) throw new IllegalArgumentException("Field is not static; pass an object handle");
		int cost = Overloads.cost(value, f.getGenericType(), handles);
		if (cost >= Overloads.IMPOSSIBLE) throw new IllegalArgumentException("Value not assignable to " + f.getGenericType().getTypeName());
		Object v = Overloads.convert(value, f.getGenericType(), handles);
		if (Modifier.isFinal(f.getModifiers())) {
			throw new IllegalArgumentException("Field " + name + " is final (use a Groovy script with Unsafe/VarHandle tricks if really needed)");
		}
		Object old = f.get(Modifier.isStatic(f.getModifiers()) ? null : t.instance());
		f.set(Modifier.isStatic(f.getModifiers()) ? null : t.instance(), v);
		return old;
	}

	// ------------------------------------------------------------ methods

	static List<Method> methodsNamed(Class<?> c, String name) {
		Map<String, Method> out = new LinkedHashMap<>();
		Deque<Class<?>> todo = new ArrayDeque<>();
		todo.add(c);
		Set<Class<?>> visited = new HashSet<>();
		while (!todo.isEmpty()) {
			Class<?> k = todo.poll();
			if (!visited.add(k)) continue;
			for (Method m : k.getDeclaredMethods()) {
				if (!m.getName().equals(name) || m.isBridge()) continue;
				out.putIfAbsent(Overloads.sigOf(m), m);
			}
			if (k.getSuperclass() != null) todo.add(k.getSuperclass());
			todo.addAll(Arrays.asList(k.getInterfaces()));
		}
		return new ArrayList<>(out.values());
	}

	public Object invoke(String targetRef, String method, List<?> args, String signature) throws Exception {
		Target t = target(targetRef);
		List<Method> cands = methodsNamed(t.type(), method);
		if (t.instance() == null) cands.removeIf(m -> !Modifier.isStatic(m.getModifiers()));
		if (cands.isEmpty()) throw new IllegalArgumentException("No " + (t.instance() == null ? "static " : "") + "method '" + method + "' in " + t.type().getName());
		Method m = Overloads.choose(cands, args, handles, signature);
		Object[] conv = Overloads.convertArgs(m, args, handles);
		m.setAccessible(true);
		try {
			Object r = m.invoke(Modifier.isStatic(m.getModifiers()) ? null : t.instance(), conv);
			return m.getReturnType() == void.class ? VOID : r;
		} catch (InvocationTargetException e) {
			throw new RuntimeException("Method threw " + e.getCause(), e.getCause());
		}
	}

	public static final Object VOID = new Object() {
		@Override
		public String toString() {
			return "void";
		}
	};

	public Object construct(String className, List<?> args, String signature) throws Exception {
		Class<?> c = loadClass(className);
		List<Constructor<?>> cands = new ArrayList<>(Arrays.asList(c.getDeclaredConstructors()));
		if (cands.isEmpty()) throw new IllegalArgumentException("No constructors in " + c.getName());
		Constructor<?> ct = Overloads.choose(cands, args, handles, signature);
		Object[] conv = Overloads.convertArgs(ct, args, handles);
		ct.setAccessible(true);
		try {
			return ct.newInstance(conv);
		} catch (InvocationTargetException e) {
			throw new RuntimeException("Constructor threw " + e.getCause(), e.getCause());
		}
	}
}
