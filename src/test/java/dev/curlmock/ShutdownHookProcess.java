package dev.curlmock;

import java.nio.file.Files;
import java.nio.file.Path;

/** Exercises the same JVM shutdown hooks as Ctrl-C, without terminating the test JVM. */
public final class ShutdownHookProcess {
    public static void main(String[] args) {
        Thread.ofPlatform().daemon().start(() -> {
            Path trigger = Path.of(args[1]);
            try {
                while (!Files.exists(trigger)) Thread.sleep(20);
                System.exit(130);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        Main.main(new String[] {"--config", args[0]});
    }
}
