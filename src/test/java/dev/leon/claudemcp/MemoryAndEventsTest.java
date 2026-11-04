package dev.leon.claudemcp;

import dev.leon.claudemcp.core.EventBuffer;
import dev.leon.claudemcp.memory.MemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MemoryAndEventsTest {
	@TempDir
	Path tmp;

	@Test
	void memoryPersistsAndSearches() {
		MemoryStore m = new MemoryStore(tmp);
		m.put("sp-World 1", "note", null, "Iron mine next to the ravine", List.of("mine"), null);
		m.put("sp-World 1", "kv", "home", "House by the lake", null, null);
		m.put("sp-World 1", "kv", "home", "New house in the forest", null, null); // replaces
		m.put("sp-World 1", "waypoint", "base", null, null, Map.of("x", 1, "y", 64, "z", -3));

		MemoryStore again = new MemoryStore(tmp);
		assertEquals("New house in the forest", again.get("sp-World 1", "home").text);
		assertEquals(1, again.search("sp-World 1", "iron", null, null, 10).size());
		assertEquals(1, again.search("sp-World 1", null, List.of("mine"), null, 10).size());
		assertEquals(1, again.search("sp-World 1", null, null, "waypoint", 10).size());
		assertEquals(3, again.search("sp-World 1", null, null, null, 10).size());
		assertTrue(again.delete("sp-World 1", "base"));
		assertNull(again.get("sp-World 1", "base"));
		assertTrue(again.scopes().contains(MemoryStore.sanitize("sp-World 1")));
		assertTrue(again.search("global", null, null, null, 10).isEmpty());
	}

	@Test
	void kvNeedsKey() {
		MemoryStore m = new MemoryStore(tmp);
		assertThrows(IllegalArgumentException.class, () -> m.put("x", "kv", null, "t", null, null));
	}

	@Test
	void eventsSinceAndWait() throws Exception {
		EventBuffer b = new EventBuffer(3);
		b.emit("chat", "text", "a");
		b.emit("damage", "amount", 2);
		b.emit("chat", "text", "b");
		b.emit("chat", "text", "c"); // first one drops out
		assertEquals(3, b.since(0, null, 100, 0).size());
		assertEquals(2, b.since(0, Set.of("chat"), 100, 0).size());
		assertEquals(0, b.since(b.lastSeq(), null, 100, 0).size());
		new Thread(() -> {
			try {
				Thread.sleep(100);
			} catch (InterruptedException ignored) {
			}
			b.emit("death");
		}).start();
		long t0 = System.currentTimeMillis();
		var got = b.since(b.lastSeq(), null, 100, 3000);
		assertEquals(1, got.size());
		assertEquals("death", got.getFirst().type());
		assertTrue(System.currentTimeMillis() - t0 < 2500);
	}
}
