// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.EngineConfigState;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * S4 T11 (R-A6): a node-local stop that a restart cannot fix halts with 78, everything else keeps 1. In a forked JVM
 * through the production seams of a real {@link AppClusteredService}: the restore cross-check of a recorded behaviour
 * the jar lacks halts 78 (check a), a recorded wire ceiling above the jar halts 78 (check b), and the egress-init
 * fail-fast still halts 1.
 *
 * <p>Negative control: {@code -Ds4.control=both1} wires the incompatible seam to the exit-1 halt in the child; the
 * 78 rows then fail on the exit status.</p>
 */
public class ExitCodeSeamProcessTest {

    /** Child main: builds the service with its production seams and takes one path. */
    public static final class Child {
        public static void main(final String[] args) throws Exception {
            final AppClusteredService s = new AppClusteredService();
            if (args.length > 1 && "both1".equals(args[1])) {
                s.failFastIncompatible = AppClusteredService::failFastHalt; // negative control: both seams exit 1
            }
            final java.lang.reflect.Field f = AppClusteredService.class.getDeclaredField("engine");
            f.setAccessible(true);
            final com.match.application.engine.Engine engine = (com.match.application.engine.Engine) f.get(s);
            final java.lang.reflect.Field sm = AppClusteredService.class.getDeclaredField("engineConfigStateMachine");
            sm.setAccessible(true);
            final com.match.application.engine.EngineConfigStateMachine machine =
                    (com.match.application.engine.EngineConfigStateMachine) sm.get(s);
            System.out.println("S4-CHILD-READY " + args[0]); // the service was built: the exit below is the seam's
            System.out.flush();
            switch (args[0]) {
                case "behaviour" -> {
                    // a restored config whose behaviour this jar does not run (check a)
                    engine.setEngineConfig(EngineConfigState.of(1, EngineConfigState.IMPL_ARRAY, 1024, 100, 0,
                            com.match.application.engine.BehaviourTwoHarness.btcMarket(), 63, 50_000,
                            com.match.application.engine.BehaviourTwoHarness.G, 12));
                    machine.checkAfterRestore();
                }
                case "ceiling" -> {
                    // a restored behaviour-2 config whose wire ceiling is above this jar (check b)
                    engine.setEngineConfig(EngineConfigState.of(1, EngineConfigState.IMPL_ARRAY, 1024, 100, 0,
                            com.match.application.engine.BehaviourTwoHarness.btcMarket(), 2, 50_000,
                            com.match.application.engine.BehaviourTwoHarness.G,
                            MatchMemberCompat.WIRE_TOP + 1));
                    machine.checkAfterRestore();
                }
                case "egress" -> s.failFast.run(); // what the egress-init catch blocks run
                default -> throw new IllegalArgumentException(args[0]);
            }
            Thread.sleep(30_000);
            System.exit(99); // the seam did not halt
        }
    }

    private static int runForked(final String path) throws Exception {
        final File javaBin = new File(new File(System.getProperty("java.home"), "bin"), "java");
        final File out = File.createTempFile("s4-t11-" + path, ".log");
        out.deleteOnExit();
        final Process p = new ProcessBuilder(javaBin.getAbsolutePath(),
                "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "--add-opens=java.base/java.nio=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"), Child.class.getName(), path,
                System.getProperty("s4.control", ""))
                .redirectErrorStream(true).redirectOutput(out).start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("forked JVM (" + path + ") did not terminate within 60s");
        }
        final String log = java.nio.file.Files.readString(out.toPath());
        System.out.println(log);
        assertTrue("the child built the service before the seam ran: " + log, log.contains("S4-CHILD-READY " + path));
        if (!"egress".equals(path)) {
            assertTrue("the CRITICAL line precedes the halt: " + log, log.contains("[ENGINE-CONFIG] CRITICAL"));
        }
        return p.exitValue();
    }

    @Test
    public void aBehaviourTheJarLacksHalts78() throws Exception {
        assertEquals(78, runForked("behaviour"));
    }

    @Test
    public void aJarBelowTheRecordedWireCeilingHalts78() throws Exception {
        assertEquals(78, runForked("ceiling"));
    }

    @Test
    public void anEgressInitFailureStillHalts1() throws Exception {
        assertEquals(1, runForked("egress"));
    }
}
