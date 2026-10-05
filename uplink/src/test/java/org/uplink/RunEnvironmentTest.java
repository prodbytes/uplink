package org.uplink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class RunEnvironmentTest {

    @Test
    void githubActionsIsCi() {
        RunEnvironment env = RunEnvironment.detect(Map.of("GITHUB_ACTIONS", "true", "CI", "true"), true);
        assertEquals(RunEnvironment.Mode.CI, env.mode());
        assertTrue(env.reason().contains("GitHub Actions"), env.reason());
    }

    @Test
    void codeBuildIsCiEvenWithoutGenericCiVariable() {
        // CodeBuild does not set CI=true, only its own CODEBUILD_* variables.
        RunEnvironment env = RunEnvironment.detect(Map.of("CODEBUILD_BUILD_ID", "proj:1234"), true);
        assertEquals(RunEnvironment.Mode.CI, env.mode());
        assertTrue(env.reason().contains("CodeBuild"), env.reason());
    }

    @Test
    void genericCiVariable() {
        assertEquals(RunEnvironment.Mode.CI, RunEnvironment.detect(Map.of("CI", "1"), true).mode());
        assertEquals(RunEnvironment.Mode.CI, RunEnvironment.detect(Map.of("CI", "true"), true).mode());
    }

    @Test
    void ciExplicitlyDisabledOnTerminalIsDesktop() {
        assertEquals(RunEnvironment.Mode.DESKTOP, RunEnvironment.detect(Map.of("CI", "false"), true).mode());
        assertEquals(RunEnvironment.Mode.DESKTOP, RunEnvironment.detect(Map.of("CI", "0"), true).mode());
    }

    @Test
    void blankProviderVariableIsIgnored() {
        assertEquals(RunEnvironment.Mode.DESKTOP, RunEnvironment.detect(Map.of("GITHUB_ACTIONS", " "), true).mode());
    }

    @Test
    void noTerminalIsCi() {
        RunEnvironment env = RunEnvironment.detect(Map.of(), false);
        assertEquals(RunEnvironment.Mode.CI, env.mode());
        assertEquals("no interactive terminal", env.reason());
    }

    @Test
    void dumbTerminalIsCi() {
        assertEquals(RunEnvironment.Mode.CI, RunEnvironment.detect(Map.of("TERM", "dumb"), true).mode());
    }

    @Test
    void interactiveTerminalIsDesktop() {
        assertEquals(RunEnvironment.Mode.DESKTOP,
                RunEnvironment.detect(Map.of("TERM", "xterm-256color"), true).mode());
    }
}
