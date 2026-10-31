package dev.forgebridge.tests;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Test runner.
 *
 * <p>Deliberately dependency free: the offline harness runs inside a plain JVM with only the
 * bridge jar and ASM on the class path, so no test framework is available (and adding one would
 * mean another jar the sandbox cannot download).</p>
 */
public final class TestMain {

    private static final Class<?>[] SUITES = new Class<?>[]{
            MappingTests.class,
            TomlTests.class,
            RemapTests.class,
            JarTests.class,
            PlaceholderTests.class,
            EventTests.class,
            RegistryTests.class,
            CompatTests.class,
            CrossVersionTests.class,
            CliTests.class
    };

    public static void main(String[] args) {
        int passed = 0;
        List<String> failures = new ArrayList<String>();
        System.out.println("ForgeBridge verification suite");
        System.out.println("-------------------------------------------------------------");
        for (int s = 0; s < SUITES.length; s++) {
            Class<?> suite = SUITES[s];
            System.out.println(suite.getSimpleName());
            Method[] methods = suite.getMethods();
            for (int m = 0; m < methods.length; m++) {
                Method method = methods[m];
                if (!method.getName().startsWith("test") || method.getParameterTypes().length != 0) {
                    continue;
                }
                long start = System.currentTimeMillis();
                try {
                    method.invoke(null);
                    passed++;
                    System.out.println("  PASS " + method.getName() + " (" + (System.currentTimeMillis() - start) + "ms)");
                } catch (InvocationTargetException error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    failures.add(suite.getSimpleName() + "." + method.getName() + ": " + cause);
                    System.out.println("  FAIL " + method.getName() + ": " + cause);
                    cause.printStackTrace(System.out);
                } catch (Throwable error) {
                    failures.add(suite.getSimpleName() + "." + method.getName() + ": " + error);
                    System.out.println("  FAIL " + method.getName() + ": " + error);
                }
            }
        }
        System.out.println("-------------------------------------------------------------");
        System.out.println("passed: " + passed + ", failed: " + failures.size());
        for (int i = 0; i < failures.size(); i++) {
            System.out.println("  ! " + failures.get(i));
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }
}
