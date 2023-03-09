/*
 * Copyright (c) 2015, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import tests.Helper;
import tests.JImageGenerator;
import tests.JImageGenerator.JLinkTask;
import tests.JImageValidator;

/*
 * @test
 * @summary Test image creation without jmods being present
 * @requires (vm.compMode != "Xcomp" & os.maxMemory >= 2g)
 * @library ../lib
 * @modules java.base/jdk.internal.jimage
 *          jdk.jdeps/com.sun.tools.classfile
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jlink.plugin
 *          jdk.jlink/jdk.tools.jimage
 *          jdk.compiler
 * @build tests.*
 * @run main/othervm -Xmx1g JLinkTestJmodsLess
 */
public class JLinkTestJmodsLess {

    private static final boolean DEBUG = true;

    public static void main(String[] args) throws Exception {

        Helper helper = Helper.newHelper();
        if (helper == null) {
            System.err.println("Test not run");
            return;
        }

        testBasicJlinking(helper);
        testCustomModuleJlinking(helper);
    }

    public static void testCustomModuleJlinking(Helper helper) throws Exception {
        String customModule = "leaf1";
        helper.generateDefaultJModule(customModule);

        // create a base image including jdk.jlink and the leaf1 module. This will
        // add the leaf1 module's module path.
        Path jlinkImage = createBaseJlinkImage(helper, "cmod-jlink", List.of("jdk.jlink", customModule));

        // Now that the base image already includes the 'leaf1' module, it should
        // be possible to jlink it again, asking for *only* the 'leaf1' plugin even
        // though we won't have any jmods directories present.
        jlinkUsingImage(helper, jlinkImage, customModule, List.of(customModule + ".com.foo.bar.X"));
    }

    public static void testBasicJlinking(Helper helper) throws Exception {
        // create a base image only containing the jdk.jlink module and its transitive closure
        Path jlinkJmodlessImage = createBaseJlinkImage(helper, "jdk-jlink", List.of("jdk.jlink"));

        jlinkUsingImage(helper, jlinkJmodlessImage, "java.base", List.of("java.lang.String"));
        System.out.println("testBasicJlinking PASSED!");
    }

    private static Path createBaseJlinkImage(Helper helper, String name, List<String> modules) throws Exception {
        // Jlink an image including jdk.jlink (i.e. the jlink tool). The
        // result must not contain a jmods directory.
        Path jlinkJmodlessImage = helper.createNewImageDir(name);
        JLinkTask task = JImageGenerator.getJLinkTask();
        if (modules.contains("leaf1")) {
            task.modulePath(helper.getJmodDir().toString());
        }
        task.output(jlinkJmodlessImage);
        for (String module: modules) {
            task.addMods(module);
        }
        task.option("--verbose")
            .call().assertSuccess();
        // Verify the base image is actually jmod-less
        if (Files.exists(jlinkJmodlessImage.resolve("jmods"))) {
            throw new AssertionError("Must not contain 'jmods' directory");
        }
        return jlinkJmodlessImage;
    }

    private static void jlinkUsingImage(Helper helper,
                                        Path jmodsLessImage,
                                        String module,
                                        List<String> expectedLocations) throws Exception {
        String jmodLessGeneratedImage = "target-jmodless-" + module;
        Path targetImageDir = helper.createNewImageDir(jmodLessGeneratedImage);
        Path targetJlink = jmodsLessImage.resolve("bin").resolve(getJlink());
        String[] jlinkCmdArray = new String[] {
                targetJlink.toString(),
                "--output", targetImageDir.toString(),
                "--verbose",
                "--add-modules", module,
                // For the time being generate-jli-classes and system-modules
                // plugins are not supported as they've been generated for the base
                // image already.
                "--disable-plugin", "generate-jli-classes",
                "--disable-plugin", "system-modules"
        };
        List<String> jlinkCmd = Arrays.asList(jlinkCmdArray);
        System.out.println("DEBUG: jmod-less jlink command: " + jlinkCmd.stream().collect(
                                                    Collectors.joining(" ")));
        ProcessBuilder builder = new ProcessBuilder(jlinkCmd);
        Process p = builder.start();
        BufferedReader errReader = p.errorReader();
        BufferedReader outReader = p.inputReader();
        int status = p.waitFor();
        if (status != 0) {
            // modules we asked for should be available in the image we used for jlinking
            if (DEBUG) {
                readAndPrintReaders(errReader, outReader);
            }
            throw new AssertionError("Expected jlink to pass given a jmodless image");
        }

        // validate the resulting image; Includes running 'java -version'
        JImageValidator validator = new JImageValidator(module, expectedLocations,
                targetImageDir.toFile(), Collections.emptyList(), Collections.emptyList());
        validator.validate();
    }

    private static void readAndPrintReaders(BufferedReader errReader,
            BufferedReader outReader) {
        System.err.println("Process error output:");
        errReader.lines().forEach(System.err::println);
        System.out.println("Process standard output:");
        outReader.lines().forEach(System.out::println);
    }

    private static String getJlink() {
        return isWindows() ? "jlink.exe" : "jlink";
    }
    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }
}
