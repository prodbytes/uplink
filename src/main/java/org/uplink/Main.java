package org.uplink;

import io.quarkus.picocli.runtime.PicocliRunner;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class Main {

    public static void main(String... args) {
        // The log manager resolves the host name on its first record. Without one of
        // these properties or $HOSTNAME (not exported by zsh), it falls back to a DNS
        // lookup of the machine name, which on macOS can block startup for 5-10s.
        // uplink never logs the host name, so skip the lookup.
        if (System.getProperty("jboss.host.name") == null
                && System.getProperty("jboss.qualified.host.name") == null
                && System.getenv("HOSTNAME") == null) {
            System.setProperty("jboss.qualified.host.name", "localhost");
        }
        Quarkus.run(PicocliRunner.class, args);
    }
}
