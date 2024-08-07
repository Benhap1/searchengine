package searchengine.util;

import java.util.Set;

public class UnsupportedProtocols {
    public static final Set<String> PROTOCOLS = Set.of(
            "javascript", "mailto", "ftp", "file", "tel"
    );
}
