package dev.leon.claudemcp.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Mod configuration, stored as JSON in config/claudemcp/config.json. */
public final class Config {
	public String host = "127.0.0.1";
	public int port = 25599;
	/** Allows Groovy script execution and hooks (full code execution). */
	public boolean enableScripts = true;
	/** Allows the java_* reflection tools. */
	public boolean enableReflection = true;
	public boolean hud = true;
	public int screenshotWidth = 960;
	public String screenshotFormat = "jpeg";
	/** Cancel Claude's movement tasks when the player presses movement/attack/use keys. */
	public boolean userOverride = true;
	/** Rotation speed limit in degrees per tick for smooth looking (0 = instant). */
	public float maxTurnPerTick = 40f;
	public int scriptTimeoutSeconds = 300;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private transient Path dir;
	private transient String token;

	public static Config load(Path dir) {
		Config c;
		Path file = dir.resolve("config.json");
		try {
			Files.createDirectories(dir);
			if (Files.exists(file)) {
				c = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Config.class);
				if (c == null) c = new Config();
			} else {
				c = new Config();
			}
			Files.writeString(file, GSON.toJson(c), StandardCharsets.UTF_8);
			Path tokenFile = dir.resolve("token.txt");
			if (!Files.exists(tokenFile) || Files.readString(tokenFile).isBlank()) {
				byte[] b = new byte[32];
				new SecureRandom().nextBytes(b);
				Files.writeString(tokenFile, HexFormat.of().formatHex(b), StandardCharsets.UTF_8);
			}
			c.token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
		} catch (IOException e) {
			throw new RuntimeException("Cannot load claudemcp config in " + dir, e);
		}
		c.dir = dir;
		return c;
	}

	public Path dir() {
		return dir;
	}

	public String token() {
		return token;
	}
}
