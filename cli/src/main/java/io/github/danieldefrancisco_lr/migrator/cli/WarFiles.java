package io.github.danieldefrancisco_lr.migrator.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Extracts a WAR (a zip) so the analyzer can read it like a module folder. */
class WarFiles {

    /** Replaces target with the WAR's contents; entries that would escape target are rejected. */
    static void extract(Path war, Path target) throws IOException {
        if (Files.exists(target)) {
            try (Stream<Path> old = Files.walk(target)) {
                for (Path p : (Iterable<Path>) old.sorted(Comparator.reverseOrder())::iterator) {
                    Files.delete(p);
                }
            }
        }
        Files.createDirectories(target);
        Path root = target.toRealPath();
        try (InputStream in = Files.newInputStream(war); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                Path out = root.resolve(entry.getName()).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("Unsafe entry in " + war.getFileName() + ": " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
