/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.packaging.test;

import org.elasticsearch.packaging.util.FileUtils;
import org.elasticsearch.packaging.util.Installation;
import org.elasticsearch.packaging.util.Platforms;
import org.elasticsearch.packaging.util.Shell;
import org.junit.Before;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

@PackagingTestCase.AwaitsFix(bugUrl = "Needs to be re-enabled")
public class PluginCliTests extends PackagingTestCase {

    private static final String EXAMPLE_PLUGIN_NAME = "custom-settings";
    private static final Path EXAMPLE_PLUGIN_ZIP;

    static {
        // re-read before each test so the plugin path can be manipulated within tests
        EXAMPLE_PLUGIN_ZIP = Paths.get(System.getProperty("tests.example-plugin"));
    }

    @Before
    public void filterDistros() {
        assumeFalse("no docker", distribution.isDocker());
    }

    @FunctionalInterface
    public interface PluginAction {
        void run(Shell.Result installResult) throws Exception;
    }

    private Shell.Result assertWithPlugin(Installation.Executable pluginTool, Path pluginZip, String pluginName, PluginAction action)
        throws Exception {
        Shell.Result installResult = pluginTool.run("install --batch \"" + pluginZip.toUri() + "\"");
        action.run(installResult);
        return pluginTool.run("remove " + pluginName);
    }

    private void assertWithExamplePlugin(PluginAction action) throws Exception {
        assertWithPlugin(installation.executables().pluginTool, EXAMPLE_PLUGIN_ZIP, EXAMPLE_PLUGIN_NAME, action);
    }

    public void test10Install() throws Exception {
        install();
        setFileSuperuser("test_superuser", "test_superuser_password");
    }

    public void test20SymlinkPluginsDir() throws Exception {
        Path pluginsDir = installation.plugins;
        Path stashedPluginsDir = createTempDir("stashed-plugins");

        Files.delete(stashedPluginsDir); // delete so we can replace it
        Files.move(pluginsDir, stashedPluginsDir);
        Path linkedPlugins = createTempDir("symlinked-plugins");
        Platforms.onLinux(() -> sh.run("chown elasticsearch:elasticsearch " + linkedPlugins.toString()));
        Files.createSymbolicLink(pluginsDir, linkedPlugins);
        // Packaged installation don't get autoconfigured yet
        assertWithExamplePlugin(installResult -> {
            assertWhileRunning(() -> {
                final String pluginsResponse = makeRequest("https://localhost:9200/_cat/plugins?h=component").strip();
                assertThat(pluginsResponse, equalTo(EXAMPLE_PLUGIN_NAME));

                String settingsPath = "_cluster/settings?include_defaults&filter_path=defaults.custom.simple";
                final String settingsResponse = makeRequest("https://localhost:9200/" + settingsPath).strip();
                assertThat(settingsResponse, equalTo("{\"defaults\":{\"custom\":{\"simple\":\"foo\"}}}"));
            });
        });

        Files.delete(pluginsDir);
        Files.move(stashedPluginsDir, pluginsDir);
    }

    public void test21CustomConfDir() throws Exception {
        withCustomConfig(confPath -> assertWithExamplePlugin(installResult -> {}));
    }

    public void test22PluginZipWithSpace() throws Exception {
        Path spacedDir = createTempDir("spaced dir");
        Path plugin = Files.copy(EXAMPLE_PLUGIN_ZIP, spacedDir.resolve(EXAMPLE_PLUGIN_ZIP.getFileName()));
        assertWithPlugin(installation.executables().pluginTool, plugin, EXAMPLE_PLUGIN_NAME, installResult -> {});
    }

    public void test23ElasticsearchWithSpace() throws Exception {
        assumeTrue(distribution.isArchive());

        Path spacedDir = createTempDir("spaced dir");
        Path elasticsearch = spacedDir.resolve("elasticsearch");
        Files.move(installation.home, elasticsearch);
        Installation spacedInstallation = Installation.ofArchive(sh, distribution, elasticsearch);

        assertWithPlugin(spacedInstallation.executables().pluginTool, EXAMPLE_PLUGIN_ZIP, EXAMPLE_PLUGIN_NAME, installResult -> {});

        Files.move(elasticsearch, installation.home);
    }

    public void test24JavaOpts() throws Exception {
        sh.getEnv().put("ES_JAVA_OPTS", "-XX:+PrintFlagsFinal");
        assertWithExamplePlugin(installResult -> assertThat(installResult.stdout, containsString("MaxHeapSize")));
    }

    public void test25Umask() throws Exception {
        sh.setUmask("0077");
        assertWithExamplePlugin(installResult -> {});
    }

    /**
     * Check that the `install` subcommand cannot be used if a plugins config file exists.
     */
    public void test30InstallFailsIfConfigFilePresent() throws IOException {
        Files.writeString(installation.config.resolve("elasticsearch-plugins.yml"), "");

        Shell.Result result = installation.executables().pluginTool.run("install analysis-icu", null, true);
        assertThat(result.isSuccess(), is(false));
        assertThat(result.stderr, matchesPattern("^Plugins config \\[[^+]] exists.*"));
    }

    /**
     * Check that the `remove` subcommand cannot be used if a plugins config file exists.
     */
    public void test31RemoveFailsIfConfigFilePresent() throws IOException {
        Files.writeString(installation.config.resolve("elasticsearch-plugins.yml"), "");

        Shell.Result result = installation.executables().pluginTool.run("install analysis-icu", null, true);
        assertThat(result.isSuccess(), is(false));
        assertThat(result.stderr, matchesPattern("^Plugins config \\[[^+]] exists.*"));
    }

    /**
     * Check that when a plugins config file exists, Elasticsearch refuses to start up, since using
     * a config file is only supported in Docker.
     */
    public void test32FailsToStartWhenPluginsConfigExists() throws Exception {
        try {
            Files.writeString(installation.config("elasticsearch-plugins.yml"), "content doesn't matter for this test");
            Shell.Result result = runElasticsearchStartCommand(null, false, true);
            assertThat(result.isSuccess(), equalTo(false));
            assertThat(
                result.stderr,
                containsString("Can only use [elasticsearch-plugins.yml] config file with distribution type [docker]")
            );
        } finally {
            FileUtils.rm(installation.config("elasticsearch-plugins.yml"));
        }
    }
}
