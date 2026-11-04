package dev.leon.claudemcp.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.leon.claudemcp.ClaudeMcp;
import dev.leon.claudemcp.core.Config;
import dev.leon.claudemcp.core.TaskManager;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Embedded MCP server (Streamable HTTP) on 127.0.0.1, protected by a bearer token. */
public final class McpHost {
	static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

	private final Config config;
	private final List<Tool> tools;
	private Server jetty;
	private McpSyncServer mcp;
	private volatile long lastRequest;
	private volatile long requests;

	public McpHost(Config config, List<Tool> tools) {
		this.config = config;
		this.tools = tools;
	}

	public long lastRequestMillis() {
		return lastRequest;
	}

	public long requestCount() {
		return requests;
	}

	public void start() throws Exception {
		Thread cur = Thread.currentThread();
		ClassLoader old = cur.getContextClassLoader();
		cur.setContextClassLoader(McpHost.class.getClassLoader());
		try {
			// The SDK creates reactor schedulers with non-daemon threads (session sweeper) that would keep the
			// JVM alive after the game closes. Make all reactor threads of our bundled reactor daemon threads.
			reactor.core.scheduler.Schedulers.setFactory(new reactor.core.scheduler.Schedulers.Factory() {
				@Override
				public reactor.core.scheduler.Scheduler newSingle(java.util.concurrent.ThreadFactory tf) {
					return reactor.core.scheduler.Schedulers.Factory.super.newSingle(daemon(tf));
				}

				@Override
				public reactor.core.scheduler.Scheduler newParallel(int n, java.util.concurrent.ThreadFactory tf) {
					return reactor.core.scheduler.Schedulers.Factory.super.newParallel(n, daemon(tf));
				}

				@Override
				public reactor.core.scheduler.Scheduler newBoundedElastic(int cap, int queued, java.util.concurrent.ThreadFactory tf, int ttl) {
					return reactor.core.scheduler.Schedulers.Factory.super.newBoundedElastic(cap, queued, daemon(tf), ttl);
				}
			});
			JsonMapper jm = JsonMapper.builder().build();
			McpJsonMapper mapper = new JacksonMcpJsonMapper(jm);
			HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
					.jsonMapper(mapper)
					.mcpEndpoint("/mcp")
					.keepAliveInterval(Duration.ofSeconds(25))
					.build();

			List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
			for (Tool t : tools) specs.add(spec(t));

			mcp = McpServer.sync(transport)
					.serverInfo("claude-mcp", ClaudeMcp.version())
					.jsonMapper(mapper)
					.jsonSchemaValidator(new DefaultJsonSchemaValidator(jm))
					.instructions(INSTRUCTIONS)
					.requestTimeout(Duration.ofMinutes(10))
					.capabilities(McpSchema.ServerCapabilities.builder().tools(true).logging().build())
					.tools(specs)
					.build();

			QueuedThreadPool pool = new QueuedThreadPool(32, 4);
			pool.setName("claudemcp-http");
			pool.setDaemon(true);
			jetty = new Server(pool);
			ServerConnector conn = new ServerConnector(jetty);
			conn.setHost(config.host);
			conn.setPort(config.port);
			jetty.addConnector(conn);
			ServletContextHandler ctx = new ServletContextHandler();
			ctx.setContextPath("/");
			ServletHolder holder = new ServletHolder(transport);
			holder.setAsyncSupported(true);
			ctx.addServlet(holder, "/mcp");
			ctx.addServlet(new ServletHolder(new HealthServlet()), "/health");
			FilterHolder auth = new FilterHolder(new AuthFilter(config.token()));
			auth.setAsyncSupported(true);
			ctx.addFilter(auth, "/mcp", EnumSet.of(DispatcherType.REQUEST));
			jetty.setHandler(ctx);
			jetty.start();
			ClaudeMcp.LOG.info("Claude MCP server listening on http://{}:{}/mcp ({} tools)", config.host, config.port, tools.size());
		} finally {
			cur.setContextClassLoader(old);
		}
	}

	static java.util.concurrent.ThreadFactory daemon(java.util.concurrent.ThreadFactory tf) {
		return r -> {
			Thread t = tf.newThread(r);
			t.setDaemon(true);
			return t;
		};
	}

	public void stop() {
		try {
			if (mcp != null) mcp.closeGracefully();
		} catch (Throwable ignored) {
		}
		try {
			if (jetty != null) jetty.stop();
		} catch (Throwable ignored) {
		}
	}

	private McpServerFeatures.SyncToolSpecification spec(Tool t) {
		McpSchema.Tool tool = McpSchema.Tool.builder()
				.name(t.name)
				.description(t.description)
				.inputSchema(t.schema())
				.build();
		return McpServerFeatures.SyncToolSpecification.builder()
				.tool(tool)
				.callHandler((exchange, req) -> call(t, req.arguments()))
				.build();
	}

	McpSchema.CallToolResult call(Tool t, Map<String, Object> args) {
		lastRequest = System.currentTimeMillis();
		requests++;
		try {
			Object r = t.handler.handle(new Args(args));
			McpSchema.CallToolResult.Builder b = McpSchema.CallToolResult.builder();
			if (r instanceof Tool.Image img) {
				b.addContent(new McpSchema.ImageContent(null, img.base64(), img.mimeType()));
				if (img.info() != null) b.addTextContent(GSON.toJson(img.info()));
			} else if (r instanceof String s) {
				b.addTextContent(s);
			} else {
				b.addTextContent(GSON.toJson(r == null ? Map.of("ok", true) : r));
			}
			return b.isError(false).build();
		} catch (Throwable e) {
			String msg = TaskManager.describe(e);
			if (!(e instanceof IllegalArgumentException) && !(e instanceof IllegalStateException)) {
				ClaudeMcp.LOG.warn("Tool {} failed", t.name, e);
			}
			return McpSchema.CallToolResult.builder().addTextContent("Error: " + msg).isError(true).build();
		}
	}

	static final String INSTRUCTIONS = """
			Controls a running Minecraft Java client (26.3) of the user. Coordinates are block coordinates.
			Workflow: call get_state first; use find_blocks/get_blocks/get_entities to perceive; long actions
			(goto, mine_block, place_block, craft, attack, script_eval without wait) return a task id - poll with
			task_status/task_wait, cancel with task_cancel or stop_all. Poll get_events(since=<last seq>) for chat,
			damage, death and task completion. The player can always take over: their input cancels movement tasks.
			Use memory_* and waypoint_* to remember things per world. java_* tools give reflection access to every
			class/object of the game (Mojang names), script_eval runs Groovy inside the client with variables
			mc, player, level, api, memory.""";

	/** Requires "Authorization: Bearer &lt;token&gt;". */
	static final class AuthFilter implements Filter {
		private final byte[] expected;

		AuthFilter(String token) {
			this.expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
		}

		@Override
		public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
			HttpServletRequest req = (HttpServletRequest) request;
			String h = req.getHeader("Authorization");
			byte[] got = h == null ? new byte[0] : h.trim().getBytes(StandardCharsets.UTF_8);
			if (!MessageDigest.isEqual(expected, got)) {
				HttpServletResponse res = (HttpServletResponse) response;
				res.setStatus(401);
				res.setContentType("application/json");
				res.getWriter().write("{\"error\":\"unauthorized: missing or wrong bearer token (config/claudemcp/token.txt)\"}");
				return;
			}
			chain.doFilter(request, response);
		}
	}

	static final class HealthServlet extends jakarta.servlet.http.HttpServlet {
		@Override
		protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
			resp.setContentType("application/json");
			resp.getWriter().write("{\"ok\":true,\"name\":\"claude-mcp\",\"version\":\"" + ClaudeMcp.version() + "\"}");
		}
	}
}
