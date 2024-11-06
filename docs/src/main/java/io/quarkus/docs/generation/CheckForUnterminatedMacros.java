package io.quarkus.docs.generation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Options;
import org.asciidoctor.SafeMode;
import org.asciidoctor.ast.Document;
import org.asciidoctor.ast.StructuralNode;

public class CheckForUnterminatedMacros {

    private final Path srcDir;

    public CheckForUnterminatedMacros(Path srcDir) {
        if (!Files.exists(srcDir) || !Files.isDirectory(srcDir)) {
            throw new IllegalStateException(
                    String.format("Source directory (%s) does not exist", srcDir.toAbsolutePath()));
        }
        this.srcDir = srcDir;

        if (!Files.exists(srcDir) || !Files.isDirectory(srcDir)) {
            throw new IllegalStateException(
                    String.format("Source directory (%s) does not exist", srcDir.toAbsolutePath()));
        }
    }

    public static void main(String[] args) {
        CheckForUnterminatedMacros check = new CheckForUnterminatedMacros(Path.of(args[0]));
        check.check();
    }

    private void check() {
        Options options = Options.builder()
                .docType("book")
                .sourceDir(srcDir.toFile())
                .baseDir(srcDir.toFile())
                .safe(SafeMode.UNSAFE)
                .build();

        try (Asciidoctor asciidoctor = Asciidoctor.Factory.create()) {
            try (Stream<Path> pathStream = Files.list(srcDir)) {
                pathStream.filter(path -> includeFile(path.getFileName().toString()))
                        .forEach(path -> {
                            if (path.toString().contains("datasource")) {
                                System.out.println("PATH " + path);
                                String guideContent;
                                try {
                                    guideContent = Files.readString(path);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }

                                // Strip off YAML frontmatter, if present
                                //                                if (guideContent.startsWith(YAML_FRONTMATTER)) {
                                //                                    int end = guideContent.indexOf(YAML_FRONTMATTER, YAML_FRONTMATTER.length());
                                //                                    guideContent = guideContent.substring(end + YAML_FRONTMATTER.length());
                                //                                }

                                Document doc = asciidoctor.load(guideContent, options);
                                for (StructuralNode block : doc.getBlocks()) {
                                    System.out.println("BLOCK " + block);
                                    block.getBlocks().forEach(b -> {
                                        System.out.println("INNER BLOCK CONTENT " + b.getContent());
                                    });
                                }

                            }
                        });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private boolean includeFile(String fileName) {
        if (fileName.startsWith("_attributes") || fileName.equals("README.adoc")) {
            return false;
        }
        if (fileName.endsWith(".adoc")) {
            return true;
        }
        return false;
    }
}
