/*******************************************************************************
 * Copyright 2017-2026 TAXTELECOM, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.pgcodekeeper.core.database.base.loader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.loader.ILoaderFactory;
import org.pgcodekeeper.core.database.api.loader.IProjectLoader;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Factory adapters shared by the core comparison coordinator and CLI module.
 */
public final class LoaderFactories {

    /**
     * Creator-only function used to build one loader from side-local settings.
     * <p>
     * If an implementation throws before returning a loader, it remains the
     * owner of every partially allocated resource and must clean it up itself.
     */
    @FunctionalInterface
    public interface LoaderCreator {

        ILoader create(ISettings settings) throws IOException, InterruptedException;
    }

    private LoaderFactories() {
    }

    /**
     * Adapts an ordinary loader creator to the comparison factory contract.
     */
    public static ILoaderFactory of(LoaderCreator creator) {
        Objects.requireNonNull(creator, "creator");
        return settings -> createValidated(creator, settings, false);
    }

    /**
     * Adapts a project loader creator and captures its project root for the
     * common-configuration contribution phase.
     */
    public static ILoaderFactory project(Path projectPath, LoaderCreator creator) {
        Objects.requireNonNull(projectPath, "projectPath");
        Objects.requireNonNull(creator, "creator");

        return new ILoaderFactory() {
            @Override
            public ILoader create(ISettings settings) throws IOException, InterruptedException {
                return createValidated(creator, settings, true);
            }

            @Override
            public void contributeCommonConfiguration(ISettings settings) throws IOException {
                Objects.requireNonNull(settings, "settings");
                if (!settings.isDisableAutoLoad()) {
                    AbstractProjectLoader.contributeCommonConfiguration(projectPath, settings);
                }
            }
        };
    }

    private static ILoader createValidated(LoaderCreator creator, ISettings settings,
            boolean requireProject) throws IOException, InterruptedException {
        Objects.requireNonNull(settings, "settings");
        ILoader loader = Objects.requireNonNull(creator.create(settings), "loader");

        try {
            IProjectLoader projectLoader = null;
            if (requireProject) {
                if (!(loader instanceof IProjectLoader compatibleProjectLoader)) {
                    throw new IllegalArgumentException("Project factory created a non-project loader");
                }
                projectLoader = compatibleProjectLoader;
            }

            if (loader.getSettings() != settings) {
                throw new IllegalArgumentException(
                        "Loader must retain the supplied settings instance");
            }

            if (projectLoader != null) {
                projectLoader.markCommonConfigurationContributed();
            }
            return loader;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(loader, failure);
            throw failure;
        }
    }

    private static void closeAfterFailure(ILoader loader, Throwable primary) {
        try {
            loader.close();
        } catch (IOException | RuntimeException | Error closeFailure) {
            if (closeFailure != primary && !isSuppressed(primary, closeFailure)) {
                primary.addSuppressed(closeFailure);
            }
        }
    }

    private static boolean isSuppressed(Throwable primary, Throwable candidate) {
        for (Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == candidate) {
                return true;
            }
        }
        return false;
    }
}
