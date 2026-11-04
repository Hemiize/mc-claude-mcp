package dev.leon.claudemcp.mcp;

import java.util.*;

/** Declarative tool definition with a small JSON-schema builder. */
public final class Tool {
	public interface Handler {
		Object handle(Args a) throws Exception;
	}

	/** Image result (base64 data). */
	public record Image(String base64, String mimeType, Map<String, Object> info) {}

	public final String name;
	public final String description;
	private final Map<String, Object> properties = new LinkedHashMap<>();
	private final List<String> required = new ArrayList<>();
	public Handler handler;
	public String group = "misc";

	private Tool(String name, String description) {
		this.name = name;
		this.description = description;
	}

	public static Tool of(String name, String description) {
		return new Tool(name, description);
	}

	public Tool group(String g) {
		this.group = g;
		return this;
	}

	private Tool prop(String name, Map<String, Object> schema, boolean req) {
		properties.put(name, schema);
		if (req) required.add(name);
		return this;
	}

	private static Map<String, Object> s(String type, String desc) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", type);
		if (desc != null) m.put("description", desc);
		return m;
	}

	public Tool str(String name, String desc, boolean req) {
		return prop(name, s("string", desc), req);
	}

	public Tool enumStr(String name, String desc, boolean req, String... values) {
		Map<String, Object> m = s("string", desc);
		m.put("enum", List.of(values));
		return prop(name, m, req);
	}

	public Tool num(String name, String desc, boolean req) {
		return prop(name, s("number", desc), req);
	}

	public Tool integer(String name, String desc, boolean req) {
		return prop(name, s("integer", desc), req);
	}

	public Tool bool(String name, String desc, boolean req) {
		return prop(name, s("boolean", desc), req);
	}

	public Tool strList(String name, String desc, boolean req) {
		Map<String, Object> m = s("array", desc);
		m.put("items", Map.of("type", "string"));
		return prop(name, m, req);
	}

	public Tool anyList(String name, String desc, boolean req) {
		Map<String, Object> m = s("array", desc);
		m.put("items", Map.of());
		return prop(name, m, req);
	}

	public Tool obj(String name, String desc, boolean req) {
		Map<String, Object> m = s("object", desc);
		m.put("additionalProperties", true);
		return prop(name, m, req);
	}

	public Tool any(String name, String desc, boolean req) {
		Map<String, Object> m = new LinkedHashMap<>();
		if (desc != null) m.put("description", desc);
		return prop(name, m, req);
	}

	/** Adds x, y, z integer properties. */
	public Tool xyz(String desc, boolean req) {
		integer("x", desc + " X", req);
		integer("y", desc + " Y", req);
		return integer("z", desc + " Z", req);
	}

	public Tool handler(Handler h) {
		this.handler = h;
		return this;
	}

	public Map<String, Object> schema() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "object");
		m.put("properties", properties);
		if (!required.isEmpty()) m.put("required", required);
		return m;
	}
}
