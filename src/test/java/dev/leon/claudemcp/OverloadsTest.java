package dev.leon.claudemcp;

import dev.leon.claudemcp.reflect.Handles;
import dev.leon.claudemcp.reflect.JsonBridge;
import dev.leon.claudemcp.reflect.Overloads;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OverloadsTest {
	public enum Color { RED, GREEN }

	public static class Target {
		public String f(int a) { return "int"; }
		public String f(double a) { return "double"; }
		public String f(String a) { return "string"; }
		public String f(Color c) { return "enum"; }
		public String g(Object o) { return "object"; }
		public String g(StringBuilder sb) { return "sb"; }
		public String v(String fmt, Object... rest) { return fmt + rest.length; }
		public String arr(int[] xs) { return "sum" + Arrays.stream(xs).sum(); }
		public String list(List<Integer> xs) { return "list" + xs.size(); }
	}

	static List<Method> named(String n) {
		List<Method> l = new ArrayList<>();
		for (Method m : Target.class.getMethods()) if (m.getName().equals(n)) l.add(m);
		return l;
	}

	static Object call(String name, List<?> args, Handles h, String sig) throws Exception {
		Method m = Overloads.choose(named(name), args, h, sig);
		return m.invoke(new Target(), Overloads.convertArgs(m, args, h));
	}

	@Test
	void picksIntForIntegralNumbers() throws Exception {
		assertEquals("int", call("f", List.of(5), null, null));
		assertEquals("int", call("f", List.of(5.0), null, null));
		assertEquals("double", call("f", List.of(5.5), null, null));
	}

	@Test
	void stringsAndEnums() throws Exception {
		assertEquals("string", call("f", List.of("hello"), null, null));
		// "RED" matches both String (cost 0) and enum (cost 1) -> String wins
		assertEquals("string", call("f", List.of("RED"), null, null));
		assertEquals("enum", call("f", List.of("RED"), null, "(OverloadsTest$Color)"));
		assertEquals("enum", call("f", List.of(Map.of("enum", "GREEN", "class", Color.class.getName())), null, null));
	}

	@Test
	void explicitSignature() throws Exception {
		assertEquals("double", call("f", List.of(5), null, "(double)"));
	}

	@Test
	void handlesResolveToObjects() throws Exception {
		Handles h = new Handles();
		StringBuilder sb = new StringBuilder("x");
		String ref = h.put(sb, true);
		assertEquals("sb", call("g", List.of(ref), h, null));
		assertEquals("sb", call("g", List.of(Map.of("handle", ref)), h, null));
		h.root("thing", () -> sb);
		assertEquals("sb", call("g", List.of("$thing"), h, null));
	}

	@Test
	void varargsAndArrays() throws Exception {
		assertEquals("a2", call("v", List.of("a", 1, "b"), null, null));
		assertEquals("a0", call("v", List.of("a"), null, null));
		assertEquals("sum6", call("arr", List.of(List.of(1, 2, 3)), null, null));
		assertEquals("list2", call("list", List.of(List.of(1, 2)), null, null));
	}

	@Test
	void noMatchGivesCandidates() {
		var ex = assertThrows(Overloads.NoMatchException.class, () -> Overloads.choose(named("f"), List.of(true), null, null));
		assertTrue(ex.getMessage().contains("Candidates"));
	}

	@Test
	void jsonBridgeMakesHandles() {
		Handles h = new Handles();
		JsonBridge j = new JsonBridge(h);
		assertEquals(5, j.toJson(5, false));
		assertEquals("s", j.toJson("s", false));
		Object o = j.toJson(new Object(), true);
		assertTrue(o instanceof Map<?, ?> m && String.valueOf(m.get("handle")).startsWith("h:"));
		Object list = j.toJson(List.of(1, 2), false);
		assertTrue(list instanceof Map<?, ?> m && m.get("items") instanceof List<?> items && items.size() == 2);
	}
}
