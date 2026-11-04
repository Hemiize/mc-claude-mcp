package dev.leon.claudemcp.hook;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Byte Buddy advice inlined into hooked methods. It only forwards to {@link HookDispatcher}; the target
 * class' class loader (Knot) must be able to see the dispatcher, so only game/mod classes can be hooked.
 */
public final class HookAdvice {
	private HookAdvice() {}

	@Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
	public static boolean enter(@Advice.Origin("#t#m#d") String key,
			@Advice.This(optional = true, typing = Assigner.Typing.DYNAMIC) Object self,
			@Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] args) {
		Object[] a = args;
		boolean skip = HookDispatcher.enter(key, self, a);
		args = a;
		return skip;
	}

	@Advice.OnMethodExit(onThrowable = Throwable.class)
	public static void exit(@Advice.Origin("#t#m#d") String key,
			@Advice.This(optional = true, typing = Assigner.Typing.DYNAMIC) Object self,
			@Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) Object[] args,
			@Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object ret,
			@Advice.Thrown Throwable thrown,
			@Advice.Enter boolean skipped) {
		Object r = HookDispatcher.exit(key, self, args, ret, thrown, skipped);
		ret = r;
	}
}
