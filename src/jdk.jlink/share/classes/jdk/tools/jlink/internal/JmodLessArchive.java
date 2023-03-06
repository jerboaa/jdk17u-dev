/*
 * Copyright (c) 2023, Red Hat, Inc.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.tools.jlink.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jdk.tools.jlink.plugin.ResourcePoolEntry.Type;

public class JmodLessArchive implements Archive {

    private static final String OTHER_RESOURCES_FILE = "jmod_resources";
    private final String module;
    private final Path path;
    private final ModuleReference ref;
    private final List<JmodLessFile> files = new ArrayList<>();

    JmodLessArchive(String module, Path path) {
        this.module = module;
        this.path = path;
        this.ref = ModuleFinder.ofSystem()
                    .find(module)
                    .orElseThrow(() ->
                        new IllegalArgumentException("Module " + module + " not part of the JDK install"));
    }

    @Override
    public String moduleName() {
        return module;
    }

    @Override
    public Path getPath() {
        return path;
    }

    @Override
    public Stream<Entry> entries() {
        try {
            collectFiles();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files.stream().map(f -> { return f.toEntry();});
    }

    @Override
    public void open() throws IOException {
        if (files.isEmpty()) {
            collectFiles();
        }
    }

    @Override
    public void close() throws IOException {
        if (!files.isEmpty()) {
            files.clear();
        }
    }

    private void collectFiles() throws IOException {
        if (files.isEmpty()) {
            addNonClassResources();
            // Add classes/resources from image module
            files.addAll(ref.open().list().map(s -> {
                return new JmodLessFile(JmodLessArchive.this, s,
                        Type.CLASS_OR_RESOURCE);
            }).collect(Collectors.toList()));
        }
    }

    private void addNonClassResources() throws IOException {
        String input = null;
        try (InputStream inStream = ref.open().open(OTHER_RESOURCES_FILE).orElseThrow()) {
            input = new String(inStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        files.addAll(Arrays.asList(input.split("\n")).stream()
                .map(s -> {
                    TypePathPair pair = mappingResource(s);
                    return new JmodLessFile(JmodLessArchive.this, pair.resPath, pair.resType);
                })
                .filter(m -> m != null)
                .collect(Collectors.toList()));
    }

    /**
     *  line: <int>|<path>
     *
     *  Take the integer before '|' convert it to a Type, and then use the
     *  result to create a JmodLessFile.
     */
    private static TypePathPair mappingResource(String line) {
        if (line.isEmpty()) {
            return null;
        }
        String[] tokens = line.split("\\|", 2);
        Type type = null;
        try {
            Integer typeInt = Integer.valueOf(tokens[0]);
            type = Type.fromOrdinal(typeInt);
        } catch (NumberFormatException e) {
            throw new AssertionError(e); // must not happen
        }
        return new TypePathPair(tokens[1], type);
    }

    static class TypePathPair {
        final String resPath;
        final Type resType;
        TypePathPair(String resPath, Type resType) {
            this.resPath = resPath;
            this.resType = resType;
        }
    }

    static class JmodLessFile {
        private static final String JAVA_HOME = System.getProperty("java.home");
        private static final Path BASE = Paths.get(JAVA_HOME);
        final String resPath;
        final Archive.Entry.EntryType resType;
        final Archive archive;

        JmodLessFile(Archive archive, String resPath, Type resType) {
            this.resPath = resPath;
            this.resType = toEntryType(resType);
            this.archive = archive;
        }

        Entry toEntry() {
            return new Entry(archive, resPath, resPath, resType) {

                @Override
                public long size() {
                    try {
                        if (resType != Archive.Entry.EntryType.CLASS_OR_RESOURCE) {
                            // Read from the base JDK image.
                            return Files.size(BASE.resolve(resPath));
                        } else {
                            // Read from the module image.
                            return Files.size(archive.getPath().resolve(resPath));
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }

                @Override
                public InputStream stream() throws IOException {
                    if (resType != Archive.Entry.EntryType.CLASS_OR_RESOURCE) {
                        // Read from the base JDK image.
                        return Files.newInputStream(BASE.resolve(resPath));
                    } else {
                        // Read from the module image.
                        String module = archive.moduleName();
                        ModuleReference mRef = ModuleFinder.ofSystem().find(module).orElseThrow();
                        return mRef.open().open(resPath).orElseThrow();
                    }
                }

            };
        }

        private static Archive.Entry.EntryType toEntryType(Type input) {
            switch(input) {
            case CLASS_OR_RESOURCE:
                return Archive.Entry.EntryType.CLASS_OR_RESOURCE;
            case CONFIG:
                return Archive.Entry.EntryType.CONFIG;
            case HEADER_FILE:
                return Archive.Entry.EntryType.HEADER_FILE;
            case LEGAL_NOTICE:
                return Archive.Entry.EntryType.LEGAL_NOTICE;
            case MAN_PAGE:
                return Archive.Entry.EntryType.MAN_PAGE;
            case NATIVE_CMD:
                return Archive.Entry.EntryType.NATIVE_CMD;
            case NATIVE_LIB:
                return Archive.Entry.EntryType.NATIVE_LIB;
            case TOP:
                throw new IllegalArgumentException("TOP files should be handled by ReleaseInfoPlugin!");
            default:
                throw new IllegalArgumentException("Unknown type: " + input);
            }
        }
    }

}
