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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.models.Swagger;
import io.swagger.util.Json;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

/**
 * Maven plugin goal for aggregating multiple OpenAPI/Swagger specifications into a single specification.
 * This plugin collects module specifications and merges them into a final specification.
 */
@Mojo(name = "aggregate", 
      defaultPhase = LifecyclePhase.PACKAGE)
public class OpenApiAggregatorMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Directory containing module specifications to aggregate
     */
    @Parameter(property = "openmrs.swagger.aggregator.inputDirectory", 
               defaultValue = "${project.build.directory}/module-specs")
    private File inputDirectory;

    /**
     * Output directory for the aggregated specification
     */
    @Parameter(property = "openmrs.swagger.aggregator.outputDirectory", 
               defaultValue = "${project.build.directory}/generated-sources/openapi")
    private File outputDirectory;

    /**
     * Output filename for the aggregated specification
     */
    @Parameter(property = "openmrs.swagger.aggregator.outputFilename", 
               defaultValue = "openapi-aggregated.json")
    private String outputFilename;

    /**
     * Base URL for the aggregated API
     */
    @Parameter(property = "openmrs.swagger.aggregator.baseUrl", 
               defaultValue = "http://localhost:8080/openmrs")
    private String baseUrl;

    /**
     * API version for the aggregated specification
     */
    @Parameter(property = "openmrs.swagger.aggregator.apiVersion", 
               defaultValue = "1.0.0")
    private String apiVersion;

    /**
     * API title for the aggregated specification
     */
    @Parameter(property = "openmrs.swagger.aggregator.apiTitle", 
               defaultValue = "OpenMRS REST API (Aggregated)")
    private String apiTitle;

    /**
     * API description for the aggregated specification
     */
    @Parameter(property = "openmrs.swagger.aggregator.apiDescription", 
               defaultValue = "Aggregated OpenMRS REST API specification")
    private String apiDescription;

    /**
     * Whether to skip the aggregation
     */
    @Parameter(property = "openmrs.swagger.aggregator.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Whether to validate specifications before aggregation
     */
    @Parameter(property = "openmrs.swagger.aggregator.validate", defaultValue = "true")
    private boolean validate;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping OpenAPI aggregation as requested");
            return;
        }

        try {
            getLog().info("Starting OpenAPI specification aggregation...");
            getLog().info("Input directory: " + inputDirectory.getAbsolutePath());
            getLog().info("Output directory: " + outputDirectory.getAbsolutePath());

            // Check if input directory exists
            if (!inputDirectory.exists() || !inputDirectory.isDirectory()) {
                getLog().warn("Input directory does not exist: " + inputDirectory.getAbsolutePath());
                return;
            }

            // Create output directory if it doesn't exist
            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs();
            }

            // Collect module specifications
            getLog().info("Collecting module specifications...");
            List<Swagger> moduleSpecs = collectModuleSpecs();
            getLog().info("Found " + moduleSpecs.size() + " module specifications");

            if (moduleSpecs.isEmpty()) {
                getLog().warn("No module specifications found to aggregate");
                return;
            }

            // Validate specifications if requested
            if (validate) {
                getLog().info("Validating specifications...");
                validateSpecifications(moduleSpecs);
            }

            // Merge specifications
            getLog().info("Merging specifications...");
            Swagger aggregatedSpec = mergeSpecifications(moduleSpecs);

            // Write the aggregated specification
            Path outputPath = Paths.get(outputDirectory.getAbsolutePath(), outputFilename);
            String jsonSpec = Json.mapper().writeValueAsString(aggregatedSpec);
            Files.write(outputPath, jsonSpec.getBytes());

            getLog().info("Aggregated OpenAPI specification generated successfully: " + outputPath.toAbsolutePath());
            getLog().info("Aggregated specification includes " + aggregatedSpec.getPaths().size() + " paths");

        } catch (Exception e) {
            throw new MojoExecutionException("Failed to aggregate OpenAPI specifications", e);
        }
    }

    /**
     * Collects module specifications from the input directory.
     */
    private List<Swagger> collectModuleSpecs() throws IOException {
        List<Swagger> moduleSpecs = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();

        File[] files = inputDirectory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return moduleSpecs;
        }

        for (File file : files) {
            try {
                getLog().debug("Reading specification from: " + file.getName());
                String content = new String(Files.readAllBytes(file.toPath()));
                Swagger swagger = Json.mapper().readValue(content, Swagger.class);
                moduleSpecs.add(swagger);
                getLog().debug("Successfully loaded specification: " + file.getName());
            } catch (Exception e) {
                getLog().warn("Failed to load specification from: " + file.getName(), e);
            }
        }

        return moduleSpecs;
    }

    /**
     * Validates the collected specifications.
     */
    private void validateSpecifications(List<Swagger> moduleSpecs) {
        for (int i = 0; i < moduleSpecs.size(); i++) {
            Swagger spec = moduleSpecs.get(i);
            if (spec.getPaths() == null || spec.getPaths().isEmpty()) {
                getLog().warn("Specification " + i + " has no paths");
            }
            if (spec.getDefinitions() == null || spec.getDefinitions().isEmpty()) {
                getLog().warn("Specification " + i + " has no definitions");
            }
        }
    }

    /**
     * Merges multiple specifications into a single aggregated specification.
     */
    private Swagger mergeSpecifications(List<Swagger> moduleSpecs) {
        // Use the first specification as the base
        Swagger aggregatedSpec = new Swagger();
        aggregatedSpec.setSwagger("2.0");
        aggregatedSpec.setInfo(createAggregatedInfo());
        aggregatedSpec.setHost(extractHost(baseUrl));
        aggregatedSpec.setBasePath(extractBasePath(baseUrl));
        aggregatedSpec.setSchemes(Arrays.asList(io.swagger.models.Scheme.HTTP, io.swagger.models.Scheme.HTTPS));

        // Merge paths
        Map<String, io.swagger.models.Path> aggregatedPaths = new HashMap<>();
        Map<String, io.swagger.models.Model> aggregatedDefinitions = new HashMap<>();

        for (Swagger moduleSpec : moduleSpecs) {
            // Merge paths
            if (moduleSpec.getPaths() != null) {
                for (Map.Entry<String, io.swagger.models.Path> entry : moduleSpec.getPaths().entrySet()) {
                    String path = entry.getKey();
                    io.swagger.models.Path pathObj = entry.getValue();
                    
                    // Check for conflicts
                    if (aggregatedPaths.containsKey(path)) {
                        getLog().warn("Path conflict detected: " + path + ". Using the last one.");
                    }
                    aggregatedPaths.put(path, pathObj);
                }
            }

            // Merge definitions
            if (moduleSpec.getDefinitions() != null) {
                for (Map.Entry<String, io.swagger.models.Model> entry : moduleSpec.getDefinitions().entrySet()) {
                    String definitionName = entry.getKey();
                    io.swagger.models.Model model = entry.getValue();
                    
                    // Check for conflicts
                    if (aggregatedDefinitions.containsKey(definitionName)) {
                        getLog().warn("Definition conflict detected: " + definitionName + ". Using the last one.");
                    }
                    aggregatedDefinitions.put(definitionName, model);
                }
            }

            // Merge security definitions
            if (moduleSpec.getSecurityDefinitions() != null && aggregatedSpec.getSecurityDefinitions() == null) {
                aggregatedSpec.setSecurityDefinitions(moduleSpec.getSecurityDefinitions());
            }
        }

        aggregatedSpec.setPaths(aggregatedPaths);
        aggregatedSpec.setDefinitions(aggregatedDefinitions);

        return aggregatedSpec;
    }

    /**
     * Creates the info section for the aggregated specification.
     */
    private io.swagger.models.Info createAggregatedInfo() {
        io.swagger.models.Info info = new io.swagger.models.Info();
        info.setTitle(apiTitle);
        info.setDescription(apiDescription);
        info.setVersion(apiVersion);
        info.setContact(new io.swagger.models.Contact().name("OpenMRS").url("https://openmrs.org"));
        info.setLicense(new io.swagger.models.License().name("MPL 2.0").url("https://www.mozilla.org/en-US/MPL/2.0/"));
        return info;
    }

    /**
     * Extracts host from base URL.
     */
    private String extractHost(String baseUrl) {
        if (baseUrl.startsWith("http://")) {
            return baseUrl.substring(7);
        } else if (baseUrl.startsWith("https://")) {
            return baseUrl.substring(8);
        }
        return baseUrl;
    }

    /**
     * Extracts base path from base URL.
     */
    private String extractBasePath(String baseUrl) {
        if (baseUrl.contains("/openmrs")) {
            return "/openmrs";
        }
        return "";
    }
} 