/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.maven.plugin.swagger;

import io.swagger.models.Scheme;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import org.openmrs.maven.plugin.swagger.util.SwaggerSpecificationCreator;


import java.io.File;
import java.io.FileWriter;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Maven plugin to generate OpenAPI (Swagger) specifications for OpenMRS REST API.
 * This plugin scans for REST resources and generates comprehensive OpenAPI documentation.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
@SuppressWarnings("unused")
public class OpenApiGeneratorMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(property = "scanPackages", defaultValue = "org.openmrs.module.webservices.rest.web.v1_0.resource")
    private String scanPackages;

    @Parameter(property = "outputDirectory", defaultValue = "${project.build.directory}/generated-sources/openapi")
    private File outputDirectory;

    @Parameter(property = "skip", defaultValue = "false")
    private boolean skip;

    @Parameter(property = "host", defaultValue = "localhost:8080/openmrs")
    private String host;

    @Parameter(property = "basePath", defaultValue = "/openmrs")
    private String basePath;

    @Parameter(property = "version", defaultValue = "2.4")
    private String version;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping OpenAPI generation as requested");
            return;
        }

        try {
            getLog().info("Starting OpenAPI specification generation...");
            getLog().info("Scanning packages: " + scanPackages);
            getLog().info("Output directory: " + outputDirectory.getAbsolutePath());

            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs();
            }

            getLog().info("Scanning for REST resources...");
            List<Class<?>> resourceClasses = scanForResourceClasses();
            getLog().info("Found " + resourceClasses.size() + " REST resources");

            // Create resource handlers from the found resource classes
            List<Object> resourceHandlers = new ArrayList<>();
            List<Object> searchHandlers = new ArrayList<>();

            SwaggerSpecificationCreator specificationCreator = new SwaggerSpecificationCreator(getLog(), resourceHandlers, searchHandlers);
            specificationCreator.host(host).basePath(basePath).scheme(Scheme.HTTP).scheme(Scheme.HTTPS);
            getLog().info("Generating OpenAPI specification...");
            String jsonSpec = specificationCreator.getJSON();

            File outputFile = new File(outputDirectory, "openapi-" + version + ".json");
            try (FileWriter writer = new FileWriter(outputFile)) {
                writer.write(jsonSpec);
            }

            getLog().info("OpenAPI specification generated successfully: " + outputFile.getAbsolutePath());

        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate OpenAPI specification", e);
        }
    }

    /**
     * Scans for classes annotated with @Resource or @SubResource in the specified packages.
     */
    private List<Class<?>> scanForResourceClasses() throws Exception {
        List<Class<?>> resourceClasses = new ArrayList<>();
        
        File targetClassesDir = new File(project.getBuild().getOutputDirectory());
        URLClassLoader classLoader = new URLClassLoader(
            new URL[]{targetClassesDir.toURI().toURL()},
            Thread.currentThread().getContextClassLoader()
        );
        
        for (String packageName : scanPackages.split(",")) {
            packageName = packageName.trim();
            getLog().info("Scanning package: " + packageName);
            
            getLog().info("Target classes directory: " + targetClassesDir.getAbsolutePath());
            getLog().info("Target classes directory exists: " + targetClassesDir.exists());
            
            if (targetClassesDir.exists() && targetClassesDir.isDirectory()) {
                String packagePath = packageName.replace('.', '/');
                File packageDir = new File(targetClassesDir, packagePath);
                getLog().info("Package directory: " + packageDir.getAbsolutePath());
                getLog().info("Package directory exists: " + packageDir.exists());
                
                if (packageDir.exists() && packageDir.isDirectory()) {
                    getLog().info("Starting recursive scan of package directory");
                    scanDirectoryForClasses(packageDir, packageName, resourceClasses, classLoader);
                } else {
                    getLog().warn("Package directory not found: " + packageDir.getAbsolutePath());
                }
            } else {
                getLog().warn("Target classes directory not found: " + targetClassesDir.getAbsolutePath());
            }
        }
        
        return resourceClasses;
    }

    /**
     * Recursively scans a directory for Java classes.
     */
    private void scanDirectoryForClasses(File directory, String packageName, List<Class<?>> resourceClasses, URLClassLoader classLoader) {
        File[] files = directory.listFiles();
        if (files == null) {
            getLog().warn("Directory is null: " + directory.getAbsolutePath());
            return;
        }
        
        getLog().info("Scanning directory: " + directory.getAbsolutePath() + " with package: " + packageName);
        getLog().info("Found " + files.length + " files/directories");
        
        for (File file : files) {
            getLog().info("Processing file: " + file.getName());
            if (file.isDirectory()) {
                String subPackage = packageName + "." + file.getName();
                getLog().info("Scanning subdirectory: " + subPackage);
                scanDirectoryForClasses(file, subPackage, resourceClasses, classLoader);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + "." + file.getName().substring(0, file.getName().length() - 6);
                getLog().info("Found class file: " + className);
                try {
                    Class<?> clazz = Class.forName(className, false, classLoader);
                    getLog().info("Loaded class: " + className);
                    if (isResourceClass(clazz)) {
                        resourceClasses.add(clazz);
                        getLog().info("Found resource class: " + className);
                    } else {
                        getLog().info("Class " + className + " is not a resource class");
                    }
                } catch (Exception e) {
                    getLog().warn("Could not load class " + className + ": " + e.getMessage());
                }
            } else {
                getLog().info("Skipping non-class file: " + file.getName());
            }
        }
    }

    /**
     * Checks if a class is a resource class (has @Resource or @SubResource annotation).
     */
    @SuppressWarnings("unchecked")
    private boolean isResourceClass(Class<?> clazz) throws ClassNotFoundException {
        boolean hasResource = clazz.isAnnotationPresent((Class<? extends Annotation>) Class.forName("org.openmrs.module.webservices.rest.web.annotation.Resource"));
        boolean hasSubResource = clazz.isAnnotationPresent((Class<? extends Annotation>) Class.forName("org.openmrs.module.webservices.rest.web.annotation.SubResource"));
        getLog().debug("Checking class " + clazz.getName() + ": @Resource=" + hasResource + ", @SubResource=" + hasSubResource);
        return hasResource || hasSubResource;
    }
}