package dev.leon.claudemcp.core;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

import java.text.SimpleDateFormat;
import java.util.*;

/** Captures the game log (log4j) into a ring buffer for the get_log tool. */
public final class LogCapture {
	public record Line(long seq, long time, String level, String logger, String thread, String message) {}

	private static final int CAPACITY = 5000;
	private static final ArrayDeque<Line> LINES = new ArrayDeque<>();
	private static long seq;
	private static EventBuffer events;

	private LogCapture() {}

	public static void install(EventBuffer eventBuffer) {
		events = eventBuffer;
		try {
			LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
			Configuration cfg = ctx.getConfiguration();
			AbstractAppender app = new AbstractAppender("ClaudeMcpCapture", null, null, true, Property.EMPTY_ARRAY) {
				@Override
				public void append(LogEvent e) {
					String msg = e.getMessage() == null ? "" : e.getMessage().getFormattedMessage();
					if (e.getThrown() != null) msg += " | " + e.getThrown();
					add(e.getLevel().name(), e.getLoggerName(), e.getThreadName(), msg);
					if (e.getLevel().isMoreSpecificThan(Level.ERROR) && events != null && !"claudemcp".equals(e.getLoggerName())) {
						events.emit("log_error", "logger", e.getLoggerName(), "message", truncate(msg, 500));
					}
				}
			};
			app.start();
			cfg.addAppender(app);
			LoggerConfig root = cfg.getRootLogger();
			root.addAppender(app, Level.INFO, null);
			ctx.updateLoggers();
			// the embedded HTTP/MCP libraries are very chatty on DEBUG
			org.apache.logging.log4j.core.config.Configurator.setLevel("org.eclipse.jetty", Level.INFO);
			org.apache.logging.log4j.core.config.Configurator.setLevel("io.modelcontextprotocol", Level.INFO);
			org.apache.logging.log4j.core.config.Configurator.setLevel("reactor", Level.INFO);
		} catch (Throwable t) {
			add("WARN", "claudemcp", Thread.currentThread().getName(), "Log capture not available: " + t);
		}
	}

	static String truncate(String s, int n) {
		return s.length() <= n ? s : s.substring(0, n) + "...";
	}

	public static synchronized void add(String level, String logger, String thread, String msg) {
		LINES.addLast(new Line(++seq, System.currentTimeMillis(), level, logger, thread, msg));
		while (LINES.size() > CAPACITY) LINES.removeFirst();
	}

	public static synchronized List<Map<String, Object>> query(int lines, String minLevel, String contains, long sinceSeq) {
		Level min = minLevel == null ? Level.ALL : Level.toLevel(minLevel, Level.ALL);
		List<Map<String, Object>> out = new ArrayList<>();
		SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
		Iterator<Line> it = LINES.descendingIterator();
		while (it.hasNext() && out.size() < lines) {
			Line l = it.next();
			if (l.seq <= sinceSeq) break;
			if (!Level.toLevel(l.level, Level.INFO).isMoreSpecificThan(min)) continue;
			if (contains != null && !contains.isEmpty() && !l.message.toLowerCase(Locale.ROOT).contains(contains.toLowerCase(Locale.ROOT))) continue;
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("seq", l.seq);
			m.put("time", fmt.format(new Date(l.time)));
			m.put("level", l.level);
			m.put("logger", l.logger);
			m.put("message", truncate(l.message, 2000));
			out.add(m);
		}
		Collections.reverse(out);
		return out;
	}
}
