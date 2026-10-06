package dev.curlmock;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Options whose arity and effects preserve one generated request and its artifacts. */
final class CurlArguments {
    private static final Set<String> FLAGS = Set.of(
            "--insecure", "-k", "--location", "-L", "--compressed",
            "--ipv4", "-4", "--ipv6", "-6", "--tlsv1.2", "--tlsv1.3",
            "--basic", "--digest", "--anyauth", "--ntlm", "--negotiate",
            "--proxy-insecure", "--proxy-basic", "--proxy-digest", "--proxy-anyauth",
            "--proxy-ntlm", "--proxy-negotiate", "--proxytunnel", "-p",
            "--ssl-no-revoke", "--ssl-revoke-best-effort", "--tcp-nodelay", "--tcp-fastopen");
    private static final Set<String> VALUES = Set.of(
            "--header", "-H", "--user", "-u", "--proxy", "-x", "--noproxy",
            "--proxy-user", "-U", "--proxy-header", "--cacert", "--capath",
            "--cert", "-E", "--cert-type", "--key", "--key-type", "--pass",
            "--proxy-cacert", "--proxy-capath", "--proxy-cert", "--proxy-cert-type",
            "--proxy-key", "--proxy-key-type", "--proxy-pass", "--resolve", "--connect-to",
            "--user-agent", "-A", "--referer", "-e", "--oauth2-bearer", "--cookie", "-b",
            "--interface", "--limit-rate", "--max-redirs", "--tls-max", "--ciphers",
            "--tls13-ciphers", "--pinnedpubkey", "--unix-socket");
    private static final Set<String> MANAGED_HEADERS = Set.of(
            "content-type", "content-length", "transfer-encoding", "content-encoding", "expect");

    private CurlArguments() {}

    static void validate(List<String> arguments) {
        for (String argument : arguments) {
            if (argument == null || argument.indexOf('\0') >= 0 || argument.indexOf('\r') >= 0 || argument.indexOf('\n') >= 0)
                throw new IllegalArgumentException("curlArguments must contain strings without NUL or line breaks");
        }
        for (int i = 0; i < arguments.size(); i++) {
            String option = arguments.get(i);
            if (FLAGS.contains(option)) continue;
            if (!VALUES.contains(option))
                throw new IllegalArgumentException("Unsupported or application-managed curl option: " + option
                        + "; use a supported full option and a separate value argument");
            if (++i >= arguments.size()) throw new IllegalArgumentException("Missing value for curl option: " + option);
            String value = arguments.get(i);
            if (value.isEmpty() || value.startsWith("-"))
                throw new IllegalArgumentException("curl option value must be nonempty and must not start with '-': " + option);
            if (option.equals("--header") || option.equals("-H") || option.equals("--proxy-header")) {
                int separator = value.indexOf(':');
                if (separator < 0) separator = value.indexOf(';');
                if (separator <= 0 || !value.substring(0, separator).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))
                    throw new IllegalArgumentException("curl headers must be literal Name: value arguments");
                if (!option.equals("--proxy-header")
                        && MANAGED_HEADERS.contains(value.substring(0, separator).toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("Application-managed header cannot be overridden: " + value.substring(0, separator));
            }
        }
    }
}
