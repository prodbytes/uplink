package org.uplink;

import org.eclipse.microprofile.config.ConfigProvider;

import picocli.CommandLine.IVersionProvider;

/**
 * {@code uplink --version}: the Maven project version, X.Y.Z from scripts/version.sh
 * (or "dev" when built without make.sh), which Quarkus records as quarkus.application.version.
 */
public class VersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        String version = ConfigProvider.getConfig()
                .getOptionalValue("quarkus.application.version", String.class)
                .orElse("dev");
        return new String[] {"uplink " + version};
    }
}
