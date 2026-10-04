package org.uplink;

import java.util.List;
import java.util.Map;

/**
 * Decides whether uplink runs on an interactive desktop terminal (TUI) or in a
 * CI/CD pipeline (plain console logs).
 *
 * <p>Detection order, most reliable first:
 * <ol>
 *   <li>Provider-specific variables that CI systems always set (GitHub Actions,
 *       AWS CodeBuild, GitLab, Jenkins, ...). These are definitive.</li>
 *   <li>The de-facto generic {@code CI} variable, set by most providers.</li>
 *   <li>Whether stdin/stdout are attached to a real terminal. A pipe, a
 *       redirect or {@code TERM=dumb} cannot host a TUI either way.</li>
 * </ol>
 */
public record RunEnvironment(Mode mode, String reason) {

    public enum Mode { DESKTOP, CI }

    /** Variable name, and the CI system it identifies. Presence (non-blank) is enough. */
    private static final List<Map.Entry<String, String>> CI_MARKERS = List.of(
            Map.entry("GITHUB_ACTIONS", "GitHub Actions"),
            Map.entry("CODEBUILD_BUILD_ID", "AWS CodeBuild"),
            Map.entry("GITLAB_CI", "GitLab CI"),
            Map.entry("JENKINS_URL", "Jenkins"),
            Map.entry("TF_BUILD", "Azure Pipelines"),
            Map.entry("BUILDKITE", "Buildkite"),
            Map.entry("CIRCLECI", "CircleCI"),
            Map.entry("TRAVIS", "Travis CI"),
            Map.entry("BITBUCKET_BUILD_NUMBER", "Bitbucket Pipelines"),
            Map.entry("TEAMCITY_VERSION", "TeamCity"),
            Map.entry("DRONE", "Drone"),
            Map.entry("SEMAPHORE", "Semaphore"),
            Map.entry("APPVEYOR", "AppVeyor"),
            Map.entry("BUILD_BUILDID", "Azure Pipelines"),
            Map.entry("CF_BUILD_ID", "Codefresh"),
            Map.entry("HARNESS_BUILD_ID", "Harness"),
            Map.entry("BITRISE_IO", "Bitrise"),
            Map.entry("NETLIFY", "Netlify"),
            Map.entry("VERCEL", "Vercel"),
            Map.entry("CLOUDFLARE_PAGES", "Cloudflare Pages"),
            Map.entry("GOOGLE_CLOUD_BUILD", "Google Cloud Build"),
            Map.entry("BUILDER_OUTPUT", "Google Cloud Build"));

    public boolean isDesktop() {
        return mode == Mode.DESKTOP;
    }

    /** Detects the environment of the current process. */
    public static RunEnvironment detect() {
        return detect(System.getenv(), isInteractiveTerminal());
    }

    /**
     * Pure detection logic, separated from the process environment for testing.
     *
     * @param env         environment variables
     * @param interactive whether stdin and stdout are attached to a terminal
     */
    static RunEnvironment detect(Map<String, String> env, boolean interactive) {
        for (Map.Entry<String, String> marker : CI_MARKERS) {
            if (isSet(env.get(marker.getKey()))) {
                return new RunEnvironment(Mode.CI, marker.getValue() + " (" + marker.getKey() + ")");
            }
        }
        String ci = env.get("CI");
        if (isSet(ci) && !ci.equalsIgnoreCase("false") && !ci.equals("0")) {
            return new RunEnvironment(Mode.CI, "generic CI (CI=" + ci + ")");
        }
        if ("dumb".equalsIgnoreCase(env.get("TERM"))) {
            return new RunEnvironment(Mode.CI, "dumb terminal (TERM=dumb)");
        }
        if (!interactive) {
            return new RunEnvironment(Mode.CI, "no interactive terminal");
        }
        return new RunEnvironment(Mode.DESKTOP, "interactive terminal");
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * {@code System.console()} is non-null only when the JVM is attached to a
     * console; {@code isTerminal()} (JDK 22+) additionally requires both stdin
     * and stdout to be a TTY, which rules out pipes and redirects.
     */
    private static boolean isInteractiveTerminal() {
        var console = System.console();
        return console != null && console.isTerminal();
    }
}
