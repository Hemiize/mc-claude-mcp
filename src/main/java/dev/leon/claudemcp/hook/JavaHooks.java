package dev.leon.claudemcp.hook;

import dev.leon.claudemcp.ClaudeMcp;
import dev.leon.claudemcp.reflect.Overloads;
import groovy.lang.Script;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Installs and removes runtime method hooks. Uses a Java agent (self-attach via Byte Buddy) to
 * retransform already loaded game classes with {@link HookAdvice}.
 */
public final class JavaHooks {
	private Instrumentation inst;
	private String attachError;
	private final Map<String, ResettableClassFileTransformer> transformers = new ConcurrentHashMap<>();
	private final Map<String, HookDispatcher.JavaHook> byId = new ConcurrentHashMap<>();
	private int nextId = 1;

	public synchronized Instrumentation instrumentation() {
		if (inst != null) return inst;
		if (attachError != null) throw new IllegalStateException(attachError);
		try {
			inst = ByteBuddyAgent.install();
			ClaudeMcp.LOG.info("Java agent attached (retransform supported: {})", inst.isRetransformClassesSupported());
			return inst;
		} catch (Throwable t) {
			attachError = "Could not attach the Java agent needed for java_hook: " + t
					+ ". Add the JVM argument -Djdk.attach.allowAttachSelf=true to the instance (Prism: Settings > Java > JVM arguments) and restart.";
			throw new IllegalStateException(attachError, t);
		}
	}

	public boolean attached() {
		return inst != null;
	}

	/** Finds the methods to hook: declared in the class or the nearest superclass declaring it. */
	static List<Method> resolve(Class<?> cls, String name, String signature) {
		for (Class<?> k = cls; k != null; k = k.getSuperclass()) {
			List<Method> ms = new ArrayList<>();
			for (Method m : k.getDeclaredMethods()) {
				if (!m.getName().equals(name) || m.isBridge() || m.isSynthetic()) continue;
				if (Modifier.isAbstract(m.getModifiers()) || Modifier.isNative(m.getModifiers())) continue;
				ms.add(m);
			}
			if (ms.isEmpty()) continue;
			if (signature != null && !signature.isBlank()) {
				Method m = Overloads.choose(ms, List.of(), null, signature);
				return List.of(m);
			}
			return ms;
		}
		throw new IllegalArgumentException("No hookable (non-abstract, non-native) method '" + name + "' in " + cls.getName() + " or its superclasses");
	}

	public synchronized List<Map<String, Object>> add(Class<?> cls, String method, String signature, boolean allOverloads, String mode,
			String beforeCode, String afterCode, int maxCalls, String id) {
		if (!ClaudeMcp.config().enableScripts && (beforeCode != null || afterCode != null)) throw new IllegalStateException("Scripts are disabled (enableScripts=false)");
		if (!ClaudeMcp.config().enableReflection) throw new IllegalStateException("Reflection is disabled (enableReflection=false)");
		if (cls.getClassLoader() == null) throw new IllegalArgumentException(cls.getName() + " is a JDK bootstrap class; only game and mod classes can be hooked");
		if (cls.getName().startsWith("dev.leon.claudemcp.") || cls.getName().startsWith("net.bytebuddy.") || cls.getName().startsWith("groovy.")
				|| cls.getName().startsWith("org.codehaus.groovy.")) {
			throw new IllegalArgumentException("Hooking the mod's own classes or Groovy/Byte Buddy is not allowed (would recurse)");
		}
		List<Method> ms = resolve(cls, method, signature);
		if (ms.size() > 1 && !allOverloads) {
			throw new IllegalArgumentException("Method '" + method + "' has " + ms.size() + " overloads: " + Overloads.describe(ms)
					+ ". Pass 'signature' or all_overloads=true.");
		}
		Class<? extends Script> before = beforeCode == null || beforeCode.isBlank() ? null : ClaudeMcp.scripts().compile(beforeCode);
		Class<? extends Script> after = afterCode == null || afterCode.isBlank() ? null : ClaudeMcp.scripts().compile(afterCode);
		String m = (before == null && after == null) ? "trace" : (mode == null ? "code" : mode);
		Instrumentation in = instrumentation();
		List<Map<String, Object>> out = new ArrayList<>();
		for (Method target : ms) {
			Class<?> decl = target.getDeclaringClass();
			if (!in.isModifiableClass(decl)) throw new IllegalArgumentException(decl.getName() + " cannot be modified by the JVM");
			String desc = new MethodDescription.ForLoadedMethod(target).getDescriptor();
			String key = decl.getName() + target.getName() + desc;
			HookDispatcher.MethodHooks mh = HookDispatcher.HOOKS.computeIfAbsent(key, k -> new HookDispatcher.MethodHooks(k, target));
			String hid = id != null && !id.isBlank() ? (ms.size() > 1 ? id + "#" + out.size() : id) : "jh" + (nextId++);
			if (byId.containsKey(hid)) remove(hid);
			HookDispatcher.JavaHook h = new HookDispatcher.JavaHook(hid, m, mh, before, after, beforeCode, afterCode, maxCalls, 500);
			mh.hooks.add(h);
			byId.put(hid, h);
			if (!transformers.containsKey(key)) {
				try {
					transformers.put(key, install(in, decl, target.getName(), desc));
				} catch (RuntimeException e) {
					mh.hooks.remove(h);
					byId.remove(hid);
					if (mh.hooks.isEmpty()) HookDispatcher.HOOKS.remove(key);
					throw e;
				}
			}
			out.add(h.describe());
		}
		return out;
	}

	private ResettableClassFileTransformer install(Instrumentation in, Class<?> decl, String name, String desc) {
		List<String> errors = new ArrayList<>();
		ResettableClassFileTransformer t = new AgentBuilder.Default(new ByteBuddy().with(TypeValidation.DISABLED))
				.disableClassFormatChanges()
				.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
				.with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
				.with(AgentBuilder.TypeStrategy.Default.REDEFINE)
				.with(new AgentBuilder.Listener.Adapter() {
					@Override
					public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
						errors.add(typeName + ": " + throwable);
					}
				})
				.ignore(none())
				.type(is(decl))
				.transform((builder, type, loader, module, pd) ->
						builder.visit(Advice.to(HookAdvice.class).on(named(name).and(hasDescriptor(desc)))))
				.installOn(in);
		if (!errors.isEmpty()) {
			t.reset(in, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
			throw new IllegalStateException("Retransformation failed: " + String.join("; ", errors));
		}
		return t;
	}

	public synchronized boolean remove(String id) {
		HookDispatcher.JavaHook h = byId.remove(id);
		if (h == null) return false;
		HookDispatcher.MethodHooks mh = h.target;
		mh.hooks.remove(h);
		if (mh.hooks.isEmpty()) {
			HookDispatcher.HOOKS.remove(mh.key);
			ResettableClassFileTransformer t = transformers.remove(mh.key);
			if (t != null && inst != null) {
				try {
					t.reset(inst, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
				} catch (Throwable e) {
					ClaudeMcp.LOG.warn("Could not reset transformer for {}", mh.key, e);
				}
			}
		}
		return true;
	}

	public synchronized int removeAll() {
		int n = 0;
		for (String id : new ArrayList<>(byId.keySet())) if (remove(id)) n++;
		return n;
	}

	public List<Map<String, Object>> list() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (HookDispatcher.JavaHook h : byId.values()) out.add(h.describe());
		out.sort(Comparator.comparing(m -> String.valueOf(m.get("id"))));
		return out;
	}

	public HookDispatcher.JavaHook get(String id) {
		HookDispatcher.JavaHook h = byId.get(id);
		if (h == null) throw new IllegalArgumentException("Unknown java hook " + id);
		return h;
	}

	public void setEnabled(String id, boolean on) {
		HookDispatcher.JavaHook h = get(id);
		h.enabled = on;
		h.consecutiveErrors = 0;
	}

	static String typeName(TypeDescription t) {
		return t.getName();
	}
}
