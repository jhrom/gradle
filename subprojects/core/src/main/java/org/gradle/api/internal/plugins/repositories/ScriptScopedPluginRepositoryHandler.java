/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.plugins.repositories;

import org.gradle.api.Action;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.plugins.dsl.PluginRepositoryHandler;

/**
 * Bridges between a global PluginRepositoryHandler and a {@link org.gradle.api.Script}.
 */
public class ScriptScopedPluginRepositoryHandler implements PluginRepositoryHandler {
    private final PluginRepositoryRegistry pluginRepositoryRegistry;
    private final FileResolver fileResolver;

    public ScriptScopedPluginRepositoryHandler(PluginRepositoryRegistry pluginRepositoryRegistry, FileResolver fileResolver) {
        this.pluginRepositoryRegistry = pluginRepositoryRegistry;
        this.fileResolver = fileResolver;
    }

    @Override
    public MavenPluginRepository maven(Action<? super MavenPluginRepository> action) {
        return pluginRepositoryRegistry.maven(action, fileResolver);
    }

    @Override
    public IvyPluginRepository ivy(Action<? super IvyPluginRepository> action) {
        return pluginRepositoryRegistry.ivy(action, fileResolver);
    }

    @Override
    public GradlePluginPortal gradlePluginPortal() {
        return pluginRepositoryRegistry.gradlePluginPortal();
    }
}
