package org.openrewrite.maven.ui;

import org.apache.maven.plugin.MojoExecutionException;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.completer.ArgumentCompleter;
import org.jline.reader.impl.completer.NullCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.openrewrite.config.RecipeDescriptor;

import java.io.IOException;
import java.util.Collection;

/**
 * Experimentation with prompting using jline.
 * Starts a jline shell and processes input until the user makes a selection or quits with Ctl-C or Ctl-D
 */
// https://github.com/jline/jline3/blob/master/builtins/src/test/java/org/jline/example/Example.java
public class JLinePrompter {
    private static final String TEXT_PROMPT = "recipe> ";

    public static String select(Collection<RecipeDescriptor> recipeDescriptors) throws MojoExecutionException {
        Terminal term = null;
        try {
            term = TerminalBuilder.builder()
                    .system(true)
                    .signalHandler(Terminal.SignalHandler.SIG_IGN)
                    .build();
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        DefaultParser parser = new DefaultParser();
        parser.setEscapeChars(new char[]{});

        Completer recipeCompleter = new RecipeDescriptorCompleter(recipeDescriptors);
        Completer completer = new ArgumentCompleter(recipeCompleter, NullCompleter.INSTANCE);

        LineReader lineReader = LineReaderBuilder.builder()
                .terminal(term)
                .parser(parser)
                .completer(completer)
                .option(LineReader.Option.CASE_INSENSITIVE, true)
                .option(LineReader.Option.CASE_INSENSITIVE_SEARCH, true)
                .option(LineReader.Option.AUTO_FRESH_LINE, true) // todo
                .variable(LineReader.LIST_MAX, 150) // todo
                .variable(LineReader.COMPLETION_STYLE_DESCRIPTION, "fg:bright-blue")
                .build();
        lineReader.setAutosuggestion(LineReader.SuggestionType.COMPLETER);

        while (true) {
            try {
                String line = lineReader.readLine(TEXT_PROMPT).trim();
                return line;
            } catch (EndOfFileException | UserInterruptException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }

    }

}