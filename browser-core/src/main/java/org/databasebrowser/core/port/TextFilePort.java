package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Exports.Target;
import org.databasebrowser.core.domain.Exports.TextOptions;

public interface TextFilePort {
    void writeText(Target target, String text, TextOptions options);
}
