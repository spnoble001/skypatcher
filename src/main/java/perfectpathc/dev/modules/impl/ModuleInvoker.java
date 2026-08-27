package perfectpathc.dev.modules.impl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Reflection boundary between the base mod and embedded implementation classes. */
public final class ModuleInvoker {
    private ModuleInvoker() {
    }

    public static Object invoke(Module module, String name, Class<?>[] parameterTypes,
                                Object... arguments) {
        if (module == null) return null;
        try {
            Method method = module.getClass().getMethod(name, parameterTypes);
            return method.invoke(module, arguments);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Embedded module does not expose " + name, exception);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot invoke embedded module " + name, exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Embedded module failed in " + name, cause);
        }
    }

    public static boolean invokeBoolean(Module module, String name, Class<?>[] parameterTypes,
                                        Object... arguments) {
        Object value = invoke(module, name, parameterTypes, arguments);
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }
}
