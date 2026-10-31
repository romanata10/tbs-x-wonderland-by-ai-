package dev.forgebridge.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflection helpers with "fail soft" semantics.
 *
 * <p>The bridge is built against NeoForge 21.1 as a compile-time API, but ships to
 * environments where individual classes may or may not exist (a legacy mod jar may
 * contribute a placeholder, a mod may be absent, a class may have moved inside a
 * patch release). Every reflective lookup therefore returns {@code null} instead of
 * throwing, and callers decide how loud to be.</p>
 */
public final class SafeReflect {

    private SafeReflect() {
    }

    public static Class<?> findClass(String name) {
        if (name == null) {
            return null;
        }
        ClassLoader[] loaders = new ClassLoader[]{
                Thread.currentThread().getContextClassLoader(),
                SafeReflect.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (int i = 0; i < loaders.length; i++) {
            ClassLoader loader = loaders[i];
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName(name, false, loader);
            } catch (Throwable ignored) {
                // try the next loader
            }
        }
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Class<?> findClass(String[] candidates) {
        for (int i = 0; i < candidates.length; i++) {
            Class<?> found = findClass(candidates[i]);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public static boolean classExists(String name) {
        return findClass(name) != null;
    }

    public static Method findMethod(Class<?> owner, String name, int parameterCount) {
        if (owner == null) {
            return null;
        }
        Method[] methods = owner.getMethods();
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].getName().equals(name) && methods[i].getParameterTypes().length == parameterCount) {
                return methods[i];
            }
        }
        return null;
    }

    /** Finds a method by name and arity, preferring an assignment-compatible signature. */
    public static Method findCompatibleMethod(Class<?> owner, String name, Object[] args) {
        if (owner == null) {
            return null;
        }
        Method[] methods = owner.getMethods();
        Method arityMatch = null;
        for (int i = 0; i < methods.length; i++) {
            Method candidate = methods[i];
            if (!candidate.getName().equals(name)) {
                continue;
            }
            Class<?>[] types = candidate.getParameterTypes();
            if (types.length != args.length) {
                continue;
            }
            boolean compatible = true;
            for (int p = 0; p < types.length; p++) {
                if (args[p] != null && !boxed(types[p]).isInstance(args[p])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                candidate.setAccessible(true);
                return candidate;
            }
            if (arityMatch == null) {
                arityMatch = candidate;
            }
        }
        if (arityMatch != null) {
            arityMatch.setAccessible(true);
        }
        return arityMatch;
    }

    public static Object invoke(Object target, String name, Object... args) {
        try {
            Method method = findCompatibleMethod(target.getClass(), name, args);
            if (method == null) {
                return null;
            }
            return method.invoke(target, args);
        } catch (Throwable error) {
            BridgeLog.debug("invoke " + name + " failed: " + error);
            return null;
        }
    }

    public static Object invokeStatic(Class<?> owner, String name, Object... args) {
        try {
            Method method = findCompatibleMethod(owner, name, args);
            if (method == null || !Modifier.isStatic(method.getModifiers())) {
                return null;
            }
            return method.invoke(null, args);
        } catch (Throwable error) {
            BridgeLog.debug("invokeStatic " + name + " failed: " + error);
            return null;
        }
    }

    /**
     * Invokes a method, reporting whether the call was actually made. Used by the
     * logging facade where a silent no-op would hide the SLF4J fallback.
     */
    public static boolean invokeBoolean(Object target, String name, Object[] args) {
        try {
            Method method = findCompatibleMethod(target.getClass(), name, args);
            if (method == null) {
                return false;
            }
            method.invoke(target, args);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    public static Object getField(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Field field = target.getClass().getField(name);
            return field.get(target);
        } catch (Throwable error) {
            return null;
        }
    }

    public static Object getStatic(Class<?> owner, String name) {
        if (owner == null) {
            return null;
        }
        try {
            Field field = owner.getField(name);
            return field.get(null);
        } catch (Throwable error) {
            return null;
        }
    }

    public static Object newInstance(Class<?> owner, Object... args) {
        if (owner == null) {
            return null;
        }
        Constructor<?>[] constructors = owner.getConstructors();
        for (int i = 0; i < constructors.length; i++) {
            Constructor<?> candidate = constructors[i];
            Class<?>[] types = candidate.getParameterTypes();
            if (types.length != args.length) {
                continue;
            }
            boolean compatible = true;
            for (int p = 0; p < types.length; p++) {
                if (args[p] != null && !boxed(types[p]).isInstance(args[p])) {
                    compatible = false;
                    break;
                }
            }
            if (!compatible) {
                continue;
            }
            try {
                return candidate.newInstance(args);
            } catch (Throwable error) {
                BridgeLog.debug("newInstance " + owner.getName() + " failed: " + error);
            }
        }
        return null;
    }

    public static List<Method> methodsAnnotatedWith(Class<?> owner, String annotationBinaryName) {
        List<Method> result = new ArrayList<Method>();
        if (owner == null) {
            return result;
        }
        Method[] methods = owner.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            java.lang.annotation.Annotation[] annotations = methods[i].getDeclaredAnnotations();
            for (int a = 0; a < annotations.length; a++) {
                if (annotations[a].annotationType().getName().equals(annotationBinaryName)) {
                    result.add(methods[i]);
                    break;
                }
            }
        }
        return result;
    }

    public static List<String> interfaceNames(Object instance) {
        List<String> names = new ArrayList<String>();
        if (instance == null) {
            return names;
        }
        Class<?>[] interfaces = instance.getClass().getInterfaces();
        for (int i = 0; i < interfaces.length; i++) {
            names.add(interfaces[i].getName());
        }
        return names;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return Void.class;
    }
}
