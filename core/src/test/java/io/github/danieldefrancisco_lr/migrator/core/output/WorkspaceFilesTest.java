package io.github.danieldefrancisco_lr.migrator.core.output;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class WorkspaceFilesTest {

    /** A playbook added to the resources must also be listed, or 'knowledge' and the agent skill would miss it. */
    @Test
    void knowledgeListMatchesTheBundledResources() throws Exception {
        Path root = Path.of("src/main/resources/knowledge");
        try (Stream<Path> files = Files.walk(root)) {
            List<String> bundled = files.filter(Files::isRegularFile)
                .map(p -> root.relativize(p).toString().replace('\\', '/'))
                .sorted()
                .collect(Collectors.toList());
            assertEquals(bundled, WorkspaceFiles.KNOWLEDGE.stream().sorted().collect(Collectors.toList()));
        }
    }

}
