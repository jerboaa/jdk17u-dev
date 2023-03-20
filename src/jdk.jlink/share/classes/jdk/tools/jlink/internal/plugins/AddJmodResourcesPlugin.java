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

package jdk.tools.jlink.internal.plugins;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jdk.tools.jlink.plugin.ResourcePool;
import jdk.tools.jlink.plugin.ResourcePoolBuilder;
import jdk.tools.jlink.plugin.ResourcePoolEntry;


/**
 * Plugin to collect resources from jmod which aren't classes or
 * resources.
 */
public final class AddJmodResourcesPlugin extends AbstractPlugin {

    private static final String NAME = "add-jmod-resources";
    // This ought to be a package-less resource so as to not conflict with
    // packages listed in the module descriptors. Making it package-less ensures
    // it works for any module, regardless of packages present.
    private static final String RESPATH = "/%s/jmod_resources";
    private static final String TYPE_FILE_FORMAT = "%s|%s";
    private final Map<String, List<String>> nonClassResEntries;

    public AddJmodResourcesPlugin() {
        super(NAME);
        this.nonClassResEntries = new ConcurrentHashMap<>();
    }

    @Override
    public ResourcePool transform(ResourcePool in, ResourcePoolBuilder out) {
        in.transformAndCopy(e -> { ResourcePoolEntry retval = recordAndFilterEntry(e);
                                   return retval;}, out);
        addModuleResourceEntries(out);
        return out.build();
    }

    private void addModuleResourceEntries(ResourcePoolBuilder out) {
        for (String module: nonClassResEntries.keySet()) {
            String mResource = String.format(RESPATH, module);
            List<String> mResources = nonClassResEntries.get(module);
            if (mResources == null) {
                throw new AssertionError("Module listed, but no resources?");
            }
            String mResContent = mResources.stream().collect(Collectors.joining("\n"));
            out.add(ResourcePoolEntry.create(mResource,
                    mResContent.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private ResourcePoolEntry recordAndFilterEntry(ResourcePoolEntry entry) {
        // Note that the jmod_resources file is a resource file, so we cannot
        // add ourselves due to this condition. However, we want to not add
        // an old version of the resource file again.
        if (entry.type() != ResourcePoolEntry.Type.CLASS_OR_RESOURCE) {
            List<String> moduleResources = nonClassResEntries.computeIfAbsent(entry.moduleName(), a -> new ArrayList<>());
            String type = Integer.toString(entry.type().ordinal());
            String resPathWithoutMod = entry.path().substring(entry.moduleName().length() + 2 /* prefixed and suffixed '/' */);
            moduleResources.add(String.format(TYPE_FILE_FORMAT, type, resPathWithoutMod));
        } else if (entry.type() == ResourcePoolEntry.Type.CLASS_OR_RESOURCE &&
                String.format(RESPATH, entry.moduleName()).equals(entry.path())) {
            // Filter /<module>/jmod_resources file which we create later
            return null;
        }

        return entry;
    }

    @Override
    public Set<State> getState() {
        return EnumSet.of(State.AUTO_ENABLED, State.FUNCTIONAL);
    }

    @Override
    public boolean hasArguments() {
        return false;
    }

    @Override
    public Category getType() {
        return Category.ADDER;
    }

}
