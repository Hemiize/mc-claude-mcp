package dev.leon.claudemcp.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.leon.claudemcp.ClaudeMcp;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * End-to-end test: creates a world and drives the client through the real MCP HTTP endpoint
 * (initialize, tools/list, get_state, find_blocks, goto, memory, script_eval).
 */
public class McpClientGameTest implements FabricClientGameTest {
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private String session;
	private int id;
	private ClientGameTestContext ctx;

	/** The client only ticks while the test thread waits, so HTTP calls are async and we pump ticks. */
	private <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> h) throws Exception {
		var f = http.sendAsync(r, h);
		int n = 0;
		while (!f.isDone()) {
			ctx.waitTick();
			if (++n > 20 * 120) throw new AssertionError("HTTP call timed out");
		}
		return f.get();
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		this.ctx = ctx;
		try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
			ctx.waitFor(mc -> mc.player != null && mc.level != null && mc.gui.screen() == null, 20 * 60);
			ctx.waitTicks(60);

			JsonObject init = rpc("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"gametest\",\"version\":\"1\"}}");
			check(init.getAsJsonObject("result").getAsJsonObject("serverInfo").get("name").getAsString().equals("claude-mcp"), "serverInfo");
			notifyInitialized();

			int tools = rpc("tools/list", "{}").getAsJsonObject("result").getAsJsonArray("tools").size();
			check(tools >= 50, "expected >= 50 tools, got " + tools);

			JsonObject state = tool("get_state", "{}");
			check(state.get("in_world").getAsBoolean(), "in_world");
			int x = state.getAsJsonArray("block_pos").get(0).getAsInt();
			int y = state.getAsJsonArray("block_pos").get(1).getAsInt();
			int z = state.getAsJsonArray("block_pos").get(2).getAsInt();

			// walk 4 blocks along x (superflat default world in game tests)
			JsonObject go = tool("goto", "{\"x\":" + (x + 4) + ",\"y\":" + y + ",\"z\":" + z + ",\"wait\":30}");
			check("done".equals(go.get("status").getAsString()), "goto: " + go);

			tool("memory_put", "{\"kind\":\"kv\",\"key\":\"t\",\"text\":\"hello\"}");
			check("hello".equals(tool("memory_get", "{\"id\":\"t\"}").get("text").getAsString()), "memory");

			JsonObject s = tool("script_eval", "{\"sync\":true,\"code\":\"player.blockPosition().x\"}");
			check(s.get("value").getAsInt() >= x + 3, "script result " + s);

			JsonObject async = tool("script_eval", "{\"code\":\"api.sleepTicks(5); api.onMain { level.dimension().identifier().toString() }\",\"wait\":20}");
			check(async.toString().contains("minecraft:overworld"), "async script " + async);

			// runtime method hook (Java agent): trace LocalPlayer.tick
			tool("java_hook", "{\"class\":\"LocalPlayer\",\"method\":\"tick\",\"id\":\"gt\",\"max_calls\":3}");
			ctx.waitTicks(10);
			JsonObject calls = tool("java_hook_calls", "{\"id\":\"gt\"}");
			check(calls.getAsJsonArray("calls").size() == 3, "java_hook trace " + calls);
			tool("java_hook_remove", "{\"id\":\"all\"}");

			// NBT: give an item with components and read it back
			tool("nbt_give", "{\"snbt\":\"{id:\\\"minecraft:stick\\\",count:2,components:{\\\"minecraft:custom_name\\\":\\\"Magic Wand\\\"}}\",\"slot\":\"8\"}");
			ctx.waitTicks(5);
			JsonObject item = tool("nbt_get", "{\"target\":\"item\",\"slot\":\"8\"}");
			check(item.get("snbt").getAsString().contains("Magic Wand"), "nbt item " + item);

			// chat filter rule (outgoing rewrite) - just registration/listing here
			tool("chat_rule_add", "{\"pattern\":\"x\",\"id\":\"gt\"}");
			tool("chat_rule_remove", "{\"id\":\"gt\"}");

			ClaudeMcp.LOG.info("[gametest] Claude MCP end-to-end test passed ({} tools)", tools);
		} catch (Exception e) {
			throw new AssertionError("MCP game test failed: " + e, e);
		}
	}

	static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError("check failed: " + what);
	}

	private HttpRequest.Builder req() {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ClaudeMcp.config().port + "/mcp"))
				.timeout(Duration.ofSeconds(60))
				.header("Authorization", "Bearer " + ClaudeMcp.config().token())
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream");
		if (session != null) b.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-06-18");
		return b;
	}

	private JsonObject rpc(String method, String params) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":" + (++id) + ",\"method\":\"" + method + "\",\"params\":" + params + "}";
		HttpResponse<String> r = send(req().POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		if (session == null) session = r.headers().firstValue("mcp-session-id").orElse(null);
		check(r.statusCode() == 200, method + " HTTP " + r.statusCode() + ": " + r.body());
		String text = r.body().trim();
		// SSE framing: take the last data: line
		if (!text.startsWith("{")) {
			String last = null;
			for (String line : text.split("\\R")) if (line.startsWith("data:")) last = line.substring(5).trim();
			text = last;
		}
		return JsonParser.parseString(text).getAsJsonObject();
	}

	private void notifyInitialized() throws Exception {
		send(req().POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).build(),
				HttpResponse.BodyHandlers.discarding());
	}

	private JsonObject tool(String name, String args) throws Exception {
		JsonObject r = rpc("tools/call", "{\"name\":\"" + name + "\",\"arguments\":" + args + "}").getAsJsonObject("result");
		String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		check(!r.get("isError").getAsBoolean(), name + " -> " + text);
		return JsonParser.parseString(text).getAsJsonObject();
	}
}
