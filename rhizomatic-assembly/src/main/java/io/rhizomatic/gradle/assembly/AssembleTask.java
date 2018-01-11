package io.rhizomatic.gradle.assembly;

import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.compile.JavaCompile;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static io.rhizomatic.gradle.assembly.IOHelper.cleanDirectory;
import static io.rhizomatic.gradle.assembly.IOHelper.copyDirectory;
import static io.rhizomatic.gradle.assembly.IOHelper.copyFile;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

/**
 * Assembles the Rhizomatic runtime image.
 */
public class AssembleTask extends DefaultTask {
    private String appGroup = "";  // the group name for application modules
    private boolean appCopy = true; // true if application modules should be copied
    private String bootstrapModule; // the bootstrap module name (the bootstrap module is determined using the appGroup and bootstrapModule values.
    private String bootstrapName;  // the file name to copy the boostrap module to (exclusive of its extension)
    private boolean includeSourceDir = true;  // true if the src directories of the project containg the plugin configuration should be included
    private boolean useArchives = false;  // true if the app module archives are used instead of exploded format

    @Input
    public String getAppGroup() {
        return appGroup;
    }

    public void setAppGroup(String appGroup) {
        this.appGroup = appGroup;
    }

    @Input
    public boolean isAppCopy() {
        return appCopy;
    }

    public void setAppCopy(boolean appCopy) {
        this.appCopy = appCopy;
    }

    @Input
    public String getBootstrapModule() {
        return bootstrapModule;
    }

    public void setBootstrapModule(String bootstrapModule) {
        this.bootstrapModule = bootstrapModule;
    }

    @Input
    public String getBootstrapName() {
        return bootstrapName;
    }

    public void setBootstrapName(String bootstrapName) {
        this.bootstrapName = bootstrapName;
    }

    @Input
    public boolean isIncludeSourceDir() {
        return includeSourceDir;
    }

    public void setIncludeSourceDir(boolean includeSourceDir) {
        this.includeSourceDir = includeSourceDir;
    }

    @Input
    public boolean isUseArchives() {
        return useArchives;
    }

    public void setUseArchives(boolean useArchives) {
        this.useArchives = useArchives;
    }

    @TaskAction
    public void assemble() {
        Logger logger = Logging.getLogger("rhizomatic-assembly");

        Project project = getProject();

        Map<String, ResolvedDependency> transitiveDependencies = resolveDependencies(project);

        logger.info("Assembling Rhizomatic runtime image");

        createRuntimeImage(project, transitiveDependencies);
    }

    /**
     * Transitively resolves all runtime dependencies using breadth-first traversal. BFS will use the version numbers of dependencies that are "closest" to the root (first level)
     * dependencies if there are transitive duplicates.
     */
    private Map<String, ResolvedDependency> resolveDependencies(Project project) {
        Configuration configuration = project.getConfigurations().getByName("compile");

        ResolvedConfiguration resolvedConfiguration = configuration.getResolvedConfiguration();
        Map<String, ResolvedDependency> directDependencies = new HashMap<>();

        // resolve breadth-first
        resolvedConfiguration.getFirstLevelModuleDependencies().forEach(d -> directDependencies.put(getKey(d), d));

        Map<String, ResolvedDependency> transitiveDependencies = new HashMap<>(directDependencies);
        directDependencies.values().forEach(d -> calculateTransitive(d, transitiveDependencies));
        return transitiveDependencies;
    }

    /**
     * Recursively calculates transitive dependencies.
     */
    private void calculateTransitive(ResolvedDependency dependency, Map<String, ResolvedDependency> dependencies) {
        // breadth-first search guarantees nearest transitive dependencies are used
        Map<String, ResolvedDependency> currentLevel =
                dependency.getChildren().stream().filter(child -> !dependencies.containsKey(getKey(child))).collect(toMap(this::getKey, identity()));
        dependencies.putAll(currentLevel);
        currentLevel.values().forEach(child -> calculateTransitive(child, dependencies));
    }

    /**
     * Creates the runtime image from the list of transitive dependencies. The dependencies are composed of app modules, library modules, Rhizomatic system modules, and and a
     * bootstrap module.
     */
    private void createRuntimeImage(Project project, Map<String, ResolvedDependency> transitiveDependencies) {
        if (getAppGroup().trim().length() == 0) {
            Logging.getLogger("rhizomatic-assembly").info("No application module group specified. Application modules will not be copied.");
        }
        File imageDir = new File(project.getBuildDir(), "image");
        if (imageDir.exists()) {
            // remove previously created image
            cleanDirectory(imageDir);
        }

        File systemDir = new File(imageDir, "system");
        systemDir.mkdirs();
        File librariesDir = new File(imageDir, "libraries");
        librariesDir.mkdirs();
        File appDir = new File(imageDir, "app");
        appDir.mkdirs();

        Configuration configuration = project.getConfigurations().getByName("compile");
        Map<String, ProjectDependency> projectDependencies = new HashMap<>();
        configuration.getDependencies().forEach(dependency -> {
            if (dependency instanceof ProjectDependency) {
                projectDependencies.put(dependency.getGroup() + ":" + dependency.getName(), (ProjectDependency) dependency);
            }
        });
        // Copy runtime image: Rhizomatic modules to /system, application modules to /app; otherwise to /libraries
        for (ResolvedDependency dependency : transitiveDependencies.values()) {
            if ("io.rhizomatic".equals(dependency.getModuleGroup())) {
                // Rhizomatic module
                copy(dependency, systemDir);
            } else if (getAppGroup().equals(dependency.getModuleGroup())) {
                if (!appCopy) {
                    continue;
                    // only copy app modules if enabled
                }
                if (getBootstrapModule() != null && getBootstrapModule().equals(dependency.getModuleName())) {
                    // bootstrap module
                    for (ResolvedArtifact artifact : dependency.getModuleArtifacts()) {
                        File target;
                        String name = getBootstrapName();
                        if (name == null) {
                            target = new File(imageDir, artifact.getFile().getName());
                        } else {
                            target = name.endsWith(".jar") ? new File(imageDir, name) : new File(imageDir, name + ".jar");
                        }
                        copyFile(artifact.getFile(), target);
                    }
                } else {
                    // app module
                    if (useArchives) {
                        // using archives, copy them
                        copy(dependency, appDir);
                    } else {
                        // using exploded format, get the other subproject and copy its compiled output
                        ProjectDependency projectDependency = projectDependencies.get(getKey(dependency));
                        if (projectDependency != null) {
                            Project dependencyProject = projectDependency.getDependencyProject();
                            JavaCompile compileTask = (JavaCompile) dependencyProject.getTasks().getByName("compileJava");
                            File classesDir = compileTask.getDestinationDir();

                            File buildDir = dependencyProject.getBuildDir();
                            File resourcesDir = new File(buildDir, "resources" + File.separator + "main");
                            if (classesDir.exists()) {
                                copyDirectory(classesDir, new File(appDir, dependencyProject.getName()));
                            }
                            if (resourcesDir.exists()) {
                                copyDirectory(resourcesDir, new File(appDir, dependencyProject.getName()));
                            }
                        }
                    }
                }
            } else {
                // library module
                copy(dependency, librariesDir);
            }
        }

        // copy src/main/resources contents if configured
        if (isIncludeSourceDir()) {
            File projectDir = project.getProjectDir();
            File resourcesDir = new File(projectDir, "src" + File.separator + "main" + File.separator + "resources");
            if (resourcesDir.isDirectory()) {
                File[] files = resourcesDir.listFiles();
                if (files != null) {
                    for (File entry : files) {
                        if (entry.isFile()) {
                            copyFile(entry, new File(imageDir, entry.getName()));
                        } else {
                            copyDirectory(entry, new File(imageDir, entry.getName()));
                        }
                    }
                }
            }
        }
    }

    private void copy(ResolvedDependency dependency, File target) {
        for (ResolvedArtifact artifact : dependency.getModuleArtifacts()) {
            copyFile(artifact.getFile(), new File(target, artifact.getFile().getName()));
        }
    }

    private String getKey(ResolvedDependency dependency) {
        return dependency.getModuleGroup() + ":" + dependency.getModuleName();
    }

}

