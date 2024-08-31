package org.openrewrite.maven;

import org.apache.maven.plugin.logging.Log;

final class MavenLog {

    enum Level {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    static void logTo(Log log, Level level, CharSequence content) {
        switch (level) {
            case DEBUG:
                log.debug(content);
                break;
            case INFO:
                log.info(content);
                break;
            case WARN:
                log.warn(content);
                break;
            case ERROR:
                log.error(content);
                break;
            default:
                throw new IllegalArgumentException("unsupported log level: " + level);
        }
    }


    private MavenLog() {
    }
}
