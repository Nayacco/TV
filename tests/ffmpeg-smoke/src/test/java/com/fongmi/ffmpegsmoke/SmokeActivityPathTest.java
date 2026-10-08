package com.fongmi.ffmpegsmoke;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SmokeActivityPathTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void normalizesValidParentAliasesBeforeConstructingBothTsInputs() throws Exception {
        File files = temporary.newFolder("files");
        File child = new File(files, "child");
        assertTrue(child.mkdir());
        File aliasedFiles = new File(child, "..");
        assertTrue(aliasedFiles.isDirectory());

        assertCanonicalFixtures(aliasedFiles, files);
    }

    @Test
    public void normalizesADirectorySymlinkLikeAndroidsAppDataAlias() throws Exception {
        File files = temporary.newFolder("files");
        Path alias = temporary.getRoot().toPath().resolve("data-user-0");
        try {
            Files.createSymbolicLink(alias, files.toPath());
        } catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            Assume.assumeNoException("Host must support directory symlinks", unsupported);
        }
        try {
            assertCanonicalFixtures(alias.toFile(), files);
        } finally {
            Files.deleteIfExists(alias);
        }
    }

    @Test
    public void canonicalFixtureDirectoryIsIdempotent() throws Exception {
        File files = temporary.newFolder("files");
        File first = SmokeActivity.fixtureDirectory(files);
        assertEquals(first, SmokeActivity.fixtureDirectory(files));
        assertTrue(first.isDirectory());
    }

    @Test
    public void neverOverwritesAFileAtTheFixtureDirectoryPath() throws Exception {
        File files = temporary.newFolder("files");
        File existing = new File(files, "fixtures");
        byte[] original = {42, 17};
        Files.write(existing.toPath(), original);
        try {
            SmokeActivity.fixtureDirectory(files);
            fail("A regular file must not be replaced by a fixture directory");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Cannot create fixture directory"));
        }
        assertArrayEquals(original, Files.readAllBytes(existing.toPath()));
    }

    private static void assertCanonicalFixtures(File input, File realFiles) throws IOException {
        File directory = SmokeActivity.fixtureDirectory(input);
        assertTrue(directory.isDirectory());
        assertEquals(new File(realFiles, "fixtures").getCanonicalFile(), directory);
        assertEquals(directory.getCanonicalFile(), directory.getAbsoluteFile());
        // Both normalizer calls inherit work's path; protect the success and rejection cases.
        for (String name : new String[]{"png-wrapped.ts", "png-truncated.ts"}) {
            File source = new File(directory, name);
            assertEquals(source.getCanonicalFile(), source.getAbsoluteFile());
        }
    }
}
