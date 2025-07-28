/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.maven.plugin.swagger.util;

import io.swagger.models.Contact;
import io.swagger.models.ExternalDocs;
import io.swagger.models.Info;
import io.swagger.models.License;
import io.swagger.models.Model;
import io.swagger.models.ModelImpl;
import io.swagger.models.Operation;
import io.swagger.models.Path;
import io.swagger.models.RefModel;
import io.swagger.models.Response;
import io.swagger.models.Scheme;
import io.swagger.models.SecurityRequirement;
import io.swagger.models.Swagger;
import io.swagger.models.auth.BasicAuthDefinition;
import io.swagger.models.parameters.BodyParameter;
import io.swagger.models.parameters.Parameter;
import io.swagger.models.parameters.PathParameter;
import io.swagger.models.parameters.QueryParameter;
import io.swagger.models.properties.ArrayProperty;
import io.swagger.models.properties.ObjectProperty;
import io.swagger.models.properties.RefProperty;
import io.swagger.models.properties.StringProperty;
import io.swagger.util.Json;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.logging.Log;
import org.atteo.evo.inflector.English;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Completely independent SwaggerSpecificationCreator that uses reflection to avoid OpenMRS dependencies.
 * This class generates Swagger specifications from resource handlers and search handlers
 * provided at construction time.
 */
public class SwaggerSpecificationCreator {

    private Swagger swagger;

    private Log log;

    private List<Object> resourceHandlers;

    private List<Object> searchHandlers;

    private String host;

    private String basePath;

    private List<Scheme> schemes;

    private String baseUrl;

    private QueryParameter subclassTypeParameter = new QueryParameter().name("t")
            .description("The type of Subclass Resource to return")
            .type("string");

    /**
     * Default constructor for runtime usage.
     */
    public SwaggerSpecificationCreator() {
        this.resourceHandlers = new ArrayList<>();
        this.searchHandlers = new ArrayList<>();
    }

    /**
     * Constructor for build-time usage.
     *
     * @param log Maven log interface
     * @param resourceHandlers List of resource handlers to process
     * @param searchHandlers List of search handlers to process
     */
    public SwaggerSpecificationCreator(Log log, List<Object> resourceHandlers, List<Object> searchHandlers) {
        this.log = log;
        this.resourceHandlers = resourceHandlers;
        this.searchHandlers = searchHandlers;
    }

    /**
     * Set the log interface for runtime usage.
     */
    public void setLog(Log log) {
        this.log = log;
    }

    /**
     * Set the resource handlers for runtime usage.
     */
    public void setResourceHandlers(List<Object> resourceHandlers) {
        this.resourceHandlers = resourceHandlers;
    }

    /**
     * Set the search handlers for runtime usage.
     */
    public void setSearchHandlers(List<Object> searchHandlers) {
        this.searchHandlers = searchHandlers;
    }

    public SwaggerSpecificationCreator host(String host) {
        this.host = host;
        return this;
    }

    public SwaggerSpecificationCreator basePath(String basePath) {
        this.basePath = basePath;
        return this;
    }

    public SwaggerSpecificationCreator scheme(Scheme scheme) {
        if (schemes == null) {
            this.schemes = new ArrayList<Scheme>();
        }
        if (!schemes.contains(scheme)) {
            this.schemes.add(scheme);
        }
        return this;
    }

    /**
     * Regenerate the swagger spec from scratch
     */
    private void buildJSON() {
        synchronized (this) {
            if (log != null) {
                log.info("Initiating Swagger specification creation");
            }
            try {
                initSwagger();
                addPaths();
                addDefaultDefinitions();
                // addSubclassOperations(); //FIXME uncomment after fixing the method
            }
            catch (Exception e) {
                if (log != null) {
                    log.error("Error while creating Swagger specification", e);
                }
            }
            finally {
                if (log != null) {
                    log.info("Swagger specification creation complete");
                }
            }
        }
    }

    /**
     * Initialize the swagger specification with basic configuration.
     * This method can be called explicitly for runtime usage.
     */
    public void initializeSwagger() {
        swagger = new Swagger();
        initSwagger();
        addDefaultDefinitions();
    }

    /**
     * Generate the complete swagger specification.
     * This method can be called explicitly for runtime usage.
     */
    public void generateSpecification() {
        if (swagger == null) {
            initializeSwagger();
        }
        addPaths();
    }

    public String getJSON() {
        if (swagger == null) {
            swagger = new Swagger();
            buildJSON();
        }
        return createJSON();
    }

    /**
     * Get the Swagger object directly.
     * This method can be called explicitly for runtime usage.
     */
    public Swagger getSwaggerObject() {
        if (swagger == null) {
            swagger = new Swagger();
            buildJSON();
        }
        return swagger;
    }

    private void addDefaultDefinitions() {
        // schema of the default response
        // received from fetchAll and search operations
        swagger.addDefinition("FetchAll", new ModelImpl()
                .property("results", new ArrayProperty()
                        .items(new ObjectProperty()
                                .property("uuid", new StringProperty())
                                .property("display", new StringProperty())
                                .property("links", new ArrayProperty()
                                        .items(new ObjectProperty()
                                                .property("rel", new StringProperty().example("self"))
                                                .property("uri", new StringProperty(StringProperty.Format.URI)))))));
    }

    private void initSwagger() {
        final Info info = new Info()
                .version("2.4.6") // Use a default version since we can't access OpenmrsConstants
                .title("OpenMRS API Docs")
                .description("OpenMRS RESTful API documentation generated by Swagger")
                .contact(new Contact().name("OpenMRS").url("http://openmrs.org"))
                .license(new License().name("MPL-2.0 w/ HD").url("http://openmrs.org/license"));

        swagger
                .info(info)
                .host(this.host)
                .basePath(this.basePath)
                .schemes(this.schemes)
                .securityDefinition("basic_auth", new BasicAuthDefinition())
                .security(new SecurityRequirement().requirement("basic_auth"))
                .consumes("application/json")
                .produces("application/json")
                .externalDocs(new ExternalDocs()
                        .description("Find more info on REST Module Wiki")
                        .url("https://wiki.openmrs.org/x/xoAaAQ"));
    }

    private boolean testOperationImplemented(Object resourceHandler) {
        Method method;
        try {
            // Test getAll method
            method = findMethod(resourceHandler.getClass(), "getAll", getRequestContextClass());
            if (method != null) {
                method.invoke(resourceHandler, getSwaggerImpossibleUniqueId(), createRequestContext());
                return true;
            }

            // Test getByUniqueId method
            method = findMethod(resourceHandler.getClass(), "getByUniqueId", String.class);
            if (method != null) {
                method.invoke(resourceHandler, getSwaggerImpossibleUniqueId());
                return true;
            }

            // Test search method
            method = findMethod(resourceHandler.getClass(), "search", getRequestContextClass());
            if (method != null) {
                method.invoke(resourceHandler, createRequestContext());
                return true;
            }

            // Test create method
            method = findMethod(resourceHandler.getClass(), "create", getSimpleObjectClass(), getRequestContextClass());
            if (method != null) {
                try {
                    method.invoke(resourceHandler, null, createRequestContext());
                } catch (Exception e) {
                    // Check if it's a ResourceDoesNotSupportOperationException
                    if (isResourceDoesNotSupportOperationException(e)) {
                        return false;
                    }
                }
                return true;
            }

            // Test update method
            method = findMethod(resourceHandler.getClass(), "update", String.class, getSimpleObjectClass(), getRequestContextClass());
            if (method != null) {
                method.invoke(resourceHandler, getSwaggerImpossibleUniqueId(), buildPOSTUpdateSimpleObject(resourceHandler), createRequestContext());
                return true;
            }

            // Test delete method
            method = findMethod(resourceHandler.getClass(), "delete", String.class, String.class, getRequestContextClass());
            if (method != null) {
                method.invoke(resourceHandler, getSwaggerImpossibleUniqueId(), "", createRequestContext());
                return true;
            }

            // Test purge method
            method = findMethod(resourceHandler.getClass(), "purge", String.class, getRequestContextClass());
            if (method != null) {
                method.invoke(resourceHandler, getSwaggerImpossibleUniqueId(), createRequestContext());
                return true;
            }

            return false;
        }
        catch (Exception e) {
            return !isResourceDoesNotSupportOperationException(e);
        }
    }

    private Method findMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        try {
            return clazz.getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private String getSwaggerImpossibleUniqueId() {
        return "SWAGGER_IMPOSSIBLE_UNIQUE_ID";
    }

    private Object createRequestContext() {
        try {
            Class<?> requestContextClass = getRequestContextClass();
            return requestContextClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return new Object(); // Fallback
        }
    }

    private Class<?> getRequestContextClass() {
        try {
            return Class.forName("org.openmrs.module.webservices.rest.web.RequestContext");
        } catch (ClassNotFoundException e) {
            return Object.class;
        }
    }

    private Class<?> getSimpleObjectClass() {
        try {
            return Class.forName("org.openmrs.module.webservices.rest.SimpleObject");
        } catch (ClassNotFoundException e) {
            return Object.class;
        }
    }

    private boolean isResourceDoesNotSupportOperationException(Exception e) {
        if (e instanceof InvocationTargetException) {
            Throwable cause = e.getCause();
            return cause != null && cause.getClass().getName().contains("ResourceDoesNotSupportOperationException");
        }
        return e.getClass().getName().contains("ResourceDoesNotSupportOperationException");
    }

    @SuppressWarnings("unchecked")
    private void sortResourceHandlers(List<Object> resourceHandlers) {
        resourceHandlers.sort(new Comparator<Object>() {

            @Override
            public int compare(Object left, Object right) {
                return isSubclass(left).compareTo(isSubclass(right));
            }

            private Boolean isSubclass(Object resourceHandler) {
                try {
                    Class<? extends Annotation> subResourceClass = (Class<? extends Annotation>) Class.forName("org.openmrs.module.webservices.rest.web.annotation.SubResource");
                    return resourceHandler.getClass().getAnnotation(subResourceClass) != null;
                } catch (ClassNotFoundException e) {
                    return false;
                }
            }
        });
    }

    private Object buildPOSTUpdateSimpleObject(Object resourceHandler) {
        try {
            Method getUpdatablePropertiesMethod = resourceHandler.getClass().getMethod("getUpdatableProperties");
            Object description = getUpdatablePropertiesMethod.invoke(resourceHandler);
            
            if (description != null) {
                Method getPropertiesMethod = description.getClass().getMethod("getProperties");
                @SuppressWarnings("unchecked")
                Map<String, Object> properties = (Map<String, Object>) getPropertiesMethod.invoke(description);
                
                Object simpleObject = createSimpleObject();
                for (String property : properties.keySet()) {
                    Method putMethod = simpleObject.getClass().getMethod("put", Object.class, Object.class);
                    putMethod.invoke(simpleObject, property, property);
                }
                return simpleObject;
            }
        } catch (Exception e) {
            if (log != null) {
                log.warn("Could not build POST update simple object for " + resourceHandler.getClass().getName(), e);
            }
        }
        
        return createSimpleObject();
    }

    private Object createSimpleObject() {
        try {
            Class<?> simpleObjectClass = getSimpleObjectClass();
            return simpleObjectClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private Path buildFetchAllPath(Path path, Object resourceHandler, String resourceName, String resourceParentName) {
        Operation getOperation = null;
        if (resourceParentName == null) {
            getOperation = createOperation(resourceHandler, "get", resourceName, null, OperationEnum.get);
        } else {
            getOperation = createOperation(resourceHandler, "get", resourceName, resourceParentName, OperationEnum.getSubresource);
        }

        if (getOperation != null) {
            path.setGet(getOperation);
        }

        return path;
    }

    private Path buildGetWithUUIDPath(Path path, Object resourceHandler, String resourceName, String resourceParentName) {
        Operation getOperation = null;

        if (resourceParentName == null) {
            getOperation = createOperation(resourceHandler, "get", resourceName, null, OperationEnum.getWithUUID);
        } else {
            getOperation = createOperation(resourceHandler, "get", resourceName, resourceParentName, OperationEnum.getSubresourceWithUUID);
        }

        if (getOperation != null) {
            path.get(getOperation);
        }
        return path;
    }

    private Path buildCreatePath(Path path, Object resourceHandler, String resourceName, String resourceParentName) {
        Operation postCreateOperation = null;

        if (resourceParentName == null) {
            postCreateOperation = createOperation(resourceHandler, "post", resourceName, null, OperationEnum.postCreate);
        } else {
            postCreateOperation = createOperation(resourceHandler, "post", resourceName, resourceParentName, OperationEnum.postSubresource);
        }

        if (postCreateOperation != null) {
            path.post(postCreateOperation);
        }
        return path;
    }

    private Path buildUpdatePath(Path path, Object resourceHandler, String resourceName, String resourceParentName) {
        Operation postUpdateOperation = null;

        if (resourceParentName == null) {
            postUpdateOperation = createOperation(resourceHandler, "post", resourceName, resourceParentName, OperationEnum.postUpdate);
        } else {
            postUpdateOperation = createOperation(resourceHandler, "post", resourceName, resourceParentName, OperationEnum.postUpdateSubresouce);
        }

        if (postUpdateOperation != null) {
            path.post(postUpdateOperation);
        }
        return path;
    }

    private Path buildDeletePath(Path path, Object resourceHandler, String resourceName, String resourceParentName) {
        Operation deleteOperation = null;

        if (resourceParentName == null) {
            deleteOperation = createOperation(resourceHandler, "delete", resourceName, resourceParentName, OperationEnum.delete);
        } else {
            deleteOperation = createOperation(resourceHandler, "delete", resourceName, resourceParentName, OperationEnum.deleteSubresource);
        }

        if (deleteOperation != null) {
            path.delete(deleteOperation);
        }
        return path;
    }

    private Path buildPurgePath(Path path, Object resourceHandler, String resourceName, String resourceParentName) {
        if (path.getDelete() != null) {
            // just add optional purge parameter
            Operation deleteOperation = path.getDelete();

            deleteOperation.setSummary("Delete or purge resource by uuid");
            deleteOperation.setDescription("The resource will be voided/retired unless purge = 'true'");

            QueryParameter purgeParam = new QueryParameter().name("purge").type("boolean");
            deleteOperation.parameter(purgeParam);
        } else {
            // create standalone purge operation with required
            Operation purgeOperation = null;

            if (resourceParentName == null) {
                purgeOperation = createOperation(resourceHandler, "delete", resourceName, null, OperationEnum.purge);
            } else {
                purgeOperation = createOperation(resourceHandler, "delete", resourceName, resourceParentName, OperationEnum.purgeSubresource);
            }

            if (purgeOperation != null) {
                path.delete(purgeOperation);
            }
        }

        return path;
    }

    private void addIndividualPath(String resourceParentName, String resourceName, Path path, String pathSuffix) {
        if (!path.getOperations().isEmpty()) {
            if (resourceParentName == null) {
                swagger.path("/" + resourceName + pathSuffix, path);
            } else {
                swagger.path("/" + resourceParentName + "/{parent-uuid}/" + resourceName + pathSuffix, path);
            }
        }
    }

    private String buildSearchParameterDependencyString(List<Object> dependencies) {
        StringBuilder sb = new StringBuilder();

        sb.append("Must be used with ");

        List<String> searchParameterNames = new ArrayList<String>();
        for (Object dependency : dependencies) {
            try {
                Method getNameMethod = dependency.getClass().getMethod("getName");
                searchParameterNames.add((String) getNameMethod.invoke(dependency));
            } catch (Exception e) {
                if (log != null) {
                    log.warn("Could not get name for search parameter dependency", e);
                }
            }
        }
        sb.append(StringUtils.join(searchParameterNames, ", "));

        String ret = sb.toString();
        int ind = ret.lastIndexOf(", ");

        if (ind > -1) {
            ret = new StringBuilder(ret).replace(ind, ind + 2, " and ").toString();
        }

        return ret;
    }

    private void addSearchOperations(Object resourceHandler, String resourceName, String resourceParentName, Path getAllPath) {
        if (resourceName == null) {
            return;
        }
        boolean hasDoSearch = testOperationImplemented(resourceHandler);
        boolean hasSearchHandler = hasSearchHandler(resourceName, resourceParentName);
        boolean wasNew = false;

        if (hasSearchHandler || hasDoSearch) {
            Operation operation;
            // query parameter
            Parameter q = new QueryParameter().name("q")
                    .description("The search query")
                    .type("string");

            if (getAllPath.getOperations().isEmpty() || getAllPath.getGet() == null) {
                // create search-only operation
                operation = new Operation();
                operation.tag(resourceParentName == null ? resourceName : resourceParentName);
                operation.produces("application/json").produces("application/xml");

                // if the path has no operations, add a note that at least one search parameter must be specified
                operation.setSummary("Search for " + resourceName);
                operation.setDescription("At least one search parameter must be specified");

                // representations query parameter
                Parameter v = new QueryParameter().name("v")
                        .description("The representation to return (ref, default, full or custom)")
                        .type("string")
                        ._enum(Arrays.asList("ref", "default", "full", "custom"));

                // This implies that the resource has no custom SearchHandler or doGetAll, but has doSearch implemented
                // As there is only one query param 'q', mark it as required
                if (!hasSearchHandler) {
                    q.setRequired(true);
                }

                operation.setParameters(buildPagingParameters());
                operation.parameter(v).parameter(q);
                operation.addResponse("200", new Response()
                        .description(resourceName + " response")
                        .schema(new RefProperty("#/definitions/FetchAll")));
                
                // Check if resource has types defined
                if (hasTypesDefined(resourceHandler)) {
                    operation.parameter(subclassTypeParameter);
                }
                // since the path has no existing get operations then it is considered new
                wasNew = true;
            } else {
                operation = getAllPath.getGet();
                operation.setSummary("Fetch all non-retired " + resourceName + " resources or perform search");
                operation.setDescription("All search parameters are optional");
                operation.parameter(q);
            }

            Map<String, Parameter> parameterMap = new HashMap<String, Parameter>();

            if (hasSearchHandler) {
                // Add search parameters using reflection
                for (Object searchHandler : searchHandlers) {
                    try {
                        Method getSearchConfigMethod = searchHandler.getClass().getMethod("getSearchConfig");
                        Object searchConfig = getSearchConfigMethod.invoke(searchHandler);
                        
                        Method getSupportedResourceMethod = searchConfig.getClass().getMethod("getSupportedResource");
                        String supportedResourceWithVersion = (String) getSupportedResourceMethod.invoke(searchConfig);
                        String supportedResource = supportedResourceWithVersion.substring(supportedResourceWithVersion.indexOf('/') + 1);

                        if (resourceName.equals(supportedResource)) {
                            Method getSearchQueriesMethod = searchConfig.getClass().getMethod("getSearchQueries");
                            @SuppressWarnings("unchecked")
                            List<Object> searchQueries = (List<Object>) getSearchQueriesMethod.invoke(searchConfig);
                            
                            for (Object searchQuery : searchQueries) {
                                // parameters with no dependencies
                                Method getRequiredParametersMethod = searchQuery.getClass().getMethod("getRequiredParameters");
                                @SuppressWarnings("unchecked")
                                List<Object> requiredParameters = (List<Object>) getRequiredParametersMethod.invoke(searchQuery);
                                
                                for (Object requiredParameter : requiredParameters) {
                                    Method getNameMethod = requiredParameter.getClass().getMethod("getName");
                                    String name = (String) getNameMethod.invoke(requiredParameter);
                                    
                                    Parameter p = new QueryParameter().type("string");
                                    p.setName(name);
                                    parameterMap.put(name, p);
                                }
                                
                                // parameters with dependencies
                                Method getOptionalParametersMethod = searchQuery.getClass().getMethod("getOptionalParameters");
                                @SuppressWarnings("unchecked")
                                List<Object> optionalParameters = (List<Object>) getOptionalParametersMethod.invoke(searchQuery);
                                
                                for (Object optionalParameter : optionalParameters) {
                                    Method getNameMethod = optionalParameter.getClass().getMethod("getName");
                                    String name = (String) getNameMethod.invoke(optionalParameter);
                                    
                                    Parameter p = new QueryParameter().type("string");
                                    p.setName(name);
                                    p.setDescription(buildSearchParameterDependencyString(requiredParameters));
                                    parameterMap.put(name, p);
                                }
                            }
                        }
                    } catch (Exception e) {
                        if (log != null) {
                            log.warn("Error processing search handler", e);
                        }
                    }
                }
            }

            for (Parameter p : parameterMap.values()) {
                operation.parameter(p);
            }
            operation.setOperationId("getAll" + getOperationTitle(resourceHandler, true));

            if (wasNew) {
                getAllPath.setGet(operation);
            }
        }
    }

    private boolean hasTypesDefined(Object resourceHandler) {
        try {
            Method hasTypesDefinedMethod = resourceHandler.getClass().getMethod("hasTypesDefined");
            return (Boolean) hasTypesDefinedMethod.invoke(resourceHandler);
        } catch (Exception e) {
            return false;
        }
    }

    public void addPaths() {
        // Use the provided resource handlers instead of getting from Context
        List<Object> handlers = new ArrayList<>(resourceHandlers);
        sortResourceHandlers(handlers);

        // generate swagger JSON for each handler
        for (Object resourceHandler : handlers) {

            // get name and parent if it's a subresource
            Class<?> resourceClass = resourceHandler.getClass();
            Object resourceAnnotation = getResourceAnnotation(resourceClass);

            String resourceParentName = null;
            String resourceName = null;

            if (resourceAnnotation != null) {
                // top level resource
                try {
                    Method nameMethod = resourceAnnotation.getClass().getMethod("name");
                    String name = (String) nameMethod.invoke(resourceAnnotation);
                    resourceName = name.substring(name.indexOf('/') + 1);
                } catch (Exception e) {
                    if (log != null) {
                        log.warn("Could not get resource name from annotation", e);
                    }
                }
            } else {
                // subresource
                Object subResourceAnnotation = getSubResourceAnnotation(resourceClass);

                if (subResourceAnnotation != null) {
                    try {
                        Method parentMethod = subResourceAnnotation.getClass().getMethod("parent");
                        Class<?> parentClass = (Class<?>) parentMethod.invoke(subResourceAnnotation);
                        Object parentResourceAnnotation = getResourceAnnotation(parentClass);

                        Method pathMethod = subResourceAnnotation.getClass().getMethod("path");
                        resourceName = (String) pathMethod.invoke(subResourceAnnotation);
                        
                        Method parentNameMethod = parentResourceAnnotation.getClass().getMethod("name");
                        String parentName = (String) parentNameMethod.invoke(parentResourceAnnotation);
                        resourceParentName = parentName.substring(parentName.indexOf('/') + 1);
                    } catch (Exception e) {
                        if (log != null) {
                            log.warn("Could not get subresource information", e);
                        }
                    }
                }
            }

            // subclass operations are handled separately in another method
            if (isDelegatingSubclassHandler(resourceHandler))
                continue;

            // set up paths
            Path rootPath = new Path();
            Path uuidPath = new Path();

            /////////////////////////
            // GET all             //
            /////////////////////////
            Path rootPathGetAll = buildFetchAllPath(rootPath, resourceHandler, resourceName, resourceParentName);
            addIndividualPath(resourceParentName, resourceName, rootPathGetAll, "");

            /////////////////////////
            // GET search          //
            /////////////////////////
            addSearchOperations(resourceHandler, resourceName, resourceParentName, rootPathGetAll);

            /////////////////////////
            // POST create         //
            /////////////////////////
            Path rootPathPostCreate = buildCreatePath(rootPathGetAll, resourceHandler, resourceName, resourceParentName);
            addIndividualPath(resourceParentName, resourceName, rootPathPostCreate, "");

            /////////////////////////
            // GET with UUID       //
            /////////////////////////
            Path uuidPathGetAll = buildGetWithUUIDPath(uuidPath, resourceHandler, resourceName, resourceParentName);
            addIndividualPath(resourceParentName, resourceName, uuidPathGetAll, "/{uuid}");

            /////////////////////////
            // POST update         //
            /////////////////////////
            Path uuidPathPostUpdate = buildUpdatePath(uuidPathGetAll, resourceHandler, resourceName, resourceParentName);
            addIndividualPath(resourceParentName, resourceName, uuidPathPostUpdate, "/{uuid}");

            /////////////////////////
            // DELETE              //
            /////////////////////////
            Path uuidPathDelete = buildDeletePath(uuidPathPostUpdate, resourceHandler, resourceName, resourceParentName);

            /////////////////////////
            // DELETE (purge)      //
            /////////////////////////
            Path uuidPathPurge = buildPurgePath(uuidPathDelete, resourceHandler, resourceName, resourceParentName);
            addIndividualPath(resourceParentName, resourceName, uuidPathPurge, "/{uuid}");
        }
    }

    @SuppressWarnings("unchecked")
    private Object getResourceAnnotation(Class<?> clazz) {
        try {
            Class<? extends Annotation> resourceAnnotationClass =
                    (Class<? extends Annotation>) Class.forName("org.openmrs.module.webservices.rest.web.annotation.Resource");
            return clazz.getAnnotation(resourceAnnotationClass);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Object getSubResourceAnnotation(Class<?> clazz) {
        try {
            Class<? extends Annotation> subResourceAnnotationClass = (Class<? extends Annotation>) Class.forName("org.openmrs.module.webservices.rest.web.annotation.SubResource");
            return clazz.getAnnotation(subResourceAnnotationClass);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private boolean isDelegatingSubclassHandler(Object resourceHandler) {
        try {
            Class<?> delegatingSubclassHandlerClass = Class.forName("org.openmrs.module.webservices.rest.web.resource.impl.DelegatingSubclassHandler");
            return delegatingSubclassHandlerClass.isInstance(resourceHandler);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private String createJSON() {
        return Json.pretty(swagger);
    }

    private Parameter buildRequiredUUIDParameter(String name, String desc) {
        return new PathParameter().name(name).description(desc).type("string");
    }

    private List<Parameter> buildPagingParameters() {
        List<Parameter> params = new ArrayList<Parameter>();

        Parameter limit = new QueryParameter().name("limit")
                .description("The number of results to return").type("integer");

        Parameter startIndex = new QueryParameter().name("startIndex")
                .description("The offset at which to start").type("integer");

        params.add(limit);
        params.add(startIndex);

        return params;
    }

    private Parameter buildPOSTBodyParameter(String resourceName, String resourceParentName, OperationEnum operationEnum) {
        BodyParameter bodyParameter = new BodyParameter();
        bodyParameter.setRequired(true);

        switch (operationEnum) {
            case postCreate:
            case postSubresource:
                bodyParameter.setName("resource");
                bodyParameter.setDescription("Resource to create");
                break;
            case postUpdate:
            case postUpdateSubresouce:
                bodyParameter.setName("resource");
                bodyParameter.setDescription("Resource properties to update");
        }

        bodyParameter.schema(new RefModel(getSchemaRef(resourceName, resourceParentName, operationEnum)));

        return bodyParameter;
    }

    private String getSchemaName(String resourceName, String resourceParentName, OperationEnum operationEnum) {
        String suffix = "";

        switch (operationEnum) {
            case get:
            case getSubresource:
            case getWithUUID:
            case getSubresourceWithUUID:
                suffix = "Get";
                break;
            case postCreate:
            case postSubresource:
                suffix = "Create";
                break;
            case postUpdate:
            case postUpdateSubresouce:
                suffix = "Update";
                break;
        }

        String modelRefName;

        if (resourceParentName == null) {
            modelRefName = StringUtils.capitalize(resourceName) + suffix;
        } else {
            modelRefName = StringUtils.capitalize(resourceParentName) + StringUtils.capitalize(resourceName) + suffix;
        }

        // get rid of slashes in model names
        String[] split = modelRefName.split("\\/");
        StringBuilder ret = new StringBuilder();
        for (String s : split) {
            ret.append(StringUtils.capitalize(s));
        }

        return ret.toString();
    }

    private String getSchemaRef(String resourceName, String resourceParentName, OperationEnum operationEnum) {
        return "#/definitions/" + getSchemaName(resourceName, resourceParentName, operationEnum);
    }

    private String getOperationTitle(Object resourceHandler, Boolean pluralize) {
        StringBuilder ret = new StringBuilder();
        English inflector = new English();

        // get rid of slashes
        String simpleClassName = resourceHandler.getClass().getSimpleName();

        // get rid of 'Resource' and version number suffixes
        simpleClassName = simpleClassName.replaceAll("\\d_\\d{1,2}$", "");
        simpleClassName = simpleClassName.replaceAll("Resource$", "");

        // pluralize if require
        if (pluralize) {
            String[] words = simpleClassName.split("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])");
            String suffix = words[words.length - 1];

            for (int i = 0; i < words.length - 1; i++) {
                ret.append(words[i]);
            }

            ret.append(inflector.getPlural(suffix));
        } else {
            ret.append(simpleClassName);
        }

        return ret.toString();
    }

    private void createDefinition(OperationEnum operationEnum, String resourceName, String resourceParentName, Object resourceHandler) {
        String definitionName = getSchemaName(resourceName, resourceParentName, operationEnum);
        Model model = null;
        Model modelRef = null;
        Model modelFull = null;

        if (definitionName.endsWith("Get")) {
            model = ReflectiveSwaggerGenerationUtil.generateGETModel(resourceHandler, "DEFAULT");
            modelRef = ReflectiveSwaggerGenerationUtil.generateGETModel(resourceHandler, "REF");
            modelFull = ReflectiveSwaggerGenerationUtil.generateGETModel(resourceHandler, "FULL");
        } else if (definitionName.endsWith("Create")) {
            model = ReflectiveSwaggerGenerationUtil.generateCREATEModel(resourceHandler, "DEFAULT");
            modelFull = ReflectiveSwaggerGenerationUtil.generateCREATEModel(resourceHandler, "FULL");
        } else if (definitionName.endsWith("Update")) {
            model = ReflectiveSwaggerGenerationUtil.generateUPDATEModel(resourceHandler, "DEFAULT");
        }

        if (model != null) {
            swagger.addDefinition(definitionName, model);
        }
        if (modelRef != null) {
            swagger.addDefinition(definitionName + "Ref", modelRef);
        }
        if (modelFull != null) {
            swagger.addDefinition(definitionName + "Full", modelFull);
        }
    }

    private Operation createOperation(Object resourceHandler, String operationName, String resourceName, String resourceParentName, OperationEnum operationEnum) {
        Operation operation = new Operation()
                .tag(resourceParentName == null ? resourceName : resourceParentName)
                .consumes("application/json").produces("application/json");

        // create definition
        if (operationName.equals("post") || operationName.equals("get")) {
            createDefinition(operationEnum, resourceName, resourceParentName, resourceHandler);
        }

        // create all possible responses
        // 200 response (Successful operation)
        Response response200 = new Response().description(resourceName + " response");

        // 201 response (Successfully created)
        Response response201 = new Response().description(resourceName + " response");

        // 204 delete success
        Response response204 = new Response().description("Delete successful");

        // 401 response (User not logged in)
        Response response401 = new Response().description("User not logged in");

        // 404 (Object with given uuid doesn't exist)
        Response response404 = new Response()
                .description("Resource with given uuid doesn't exist");

        // create all possible query params
        // representations query parameter
        Parameter v = new QueryParameter().name("v")
                .description("The representation to return (ref, default, full or custom)")
                .type("string")
                ._enum(Arrays.asList("ref", "default", "full", "custom"));

        if (operationEnum == OperationEnum.get) {
            operation.setSummary("Fetch all non-retired");
            operation.setOperationId("getAll" + getOperationTitle(resourceHandler, true));
            operation.addResponse("200",
                    response200.schema(new ArrayProperty(
                            new RefProperty(getSchemaRef(resourceName, resourceParentName, OperationEnum.get)))));

            operation.setParameters(buildPagingParameters());
            operation.parameter(v);
            if (hasTypesDefined(resourceHandler)) {
                operation.parameter(subclassTypeParameter);
            }

        } else if (operationEnum == OperationEnum.getWithUUID) {
            operation.setSummary("Fetch by uuid");
            operation.setOperationId("get" + getOperationTitle(resourceHandler, false));
            operation.parameter(v);
            operation.parameter(buildRequiredUUIDParameter("uuid", "uuid to filter by"));
            operation.addResponse("200",
                    response200.schema(new RefProperty(getSchemaRef(resourceName, resourceParentName, OperationEnum.get))));
            operation.addResponse("404", response404);

        } else if (operationEnum == OperationEnum.postCreate) {
            operation.setSummary("Create with properties in request");
            operation.setOperationId("create" + getOperationTitle(resourceHandler, false));
            operation.parameter(buildPOSTBodyParameter(resourceName, resourceParentName, OperationEnum.postCreate));
            operation.addResponse("201", response201);

        } else if (operationEnum == OperationEnum.postUpdate) {
            operation.setSummary("Edit with given uuid, only modifying properties in request");
            operation.setOperationId("update" + getOperationTitle(resourceHandler, false));
            operation.parameter(buildRequiredUUIDParameter("uuid", "uuid of resource to update"));
            operation.parameter(buildPOSTBodyParameter(resourceName, resourceParentName, OperationEnum.postUpdate));
            operation.addResponse("201", response201);

        } else if (operationEnum == OperationEnum.getSubresource) {
            operation.setSummary("Fetch all non-retired " + resourceName + " subresources");
            operation.setOperationId("getAll" + getOperationTitle(resourceHandler, true));
            operation.setParameters(buildPagingParameters());
            operation.parameter(buildRequiredUUIDParameter("parent-uuid", "parent resource uuid"));
            operation.parameter(v);
            operation.addResponse("200", response200.schema(new ObjectProperty()
                    .property("results", new ArrayProperty(
                            new RefProperty(getSchemaRef(resourceName, resourceParentName, OperationEnum.get))))));

        } else if (operationEnum == OperationEnum.postSubresource) {
            operation.setSummary("Create " + resourceName + " subresource with properties in request");
            operation.setOperationId("create" + getOperationTitle(resourceHandler, false));
            operation.parameter(buildRequiredUUIDParameter("parent-uuid", "parent resource uuid"));
            operation.parameter(buildPOSTBodyParameter(resourceName, resourceParentName, OperationEnum.postSubresource));
            operation.addResponse("201", response201);

        } else if (operationEnum == OperationEnum.postUpdateSubresouce) {
            operation.setSummary("edit " + resourceName + " subresource with given uuid, only modifying properties in request");
            operation.setOperationId("update" + getOperationTitle(resourceHandler, false));
            operation.parameter(buildRequiredUUIDParameter("parent-uuid", "parent resource uuid"));
            operation.parameter(buildRequiredUUIDParameter("uuid", "uuid of resource to update"));
            operation.parameter(buildPOSTBodyParameter(resourceName, resourceParentName, OperationEnum.postUpdateSubresouce));
            operation.addResponse("201", response201);

        } else if (operationEnum == OperationEnum.getSubresourceWithUUID) {
            operation.setSummary("Fetch " + resourceName + " subresources by uuid");
            operation.setOperationId("get" + getOperationTitle(resourceHandler, false));
            operation.parameter(buildRequiredUUIDParameter("parent-uuid", "parent resource uuid"));
            operation.parameter(buildRequiredUUIDParameter("uuid", "uuid to filter by"));
            operation.parameter(v);
            operation.addResponse("200", response200.schema(new RefProperty(getSchemaRef(resourceName, resourceParentName, OperationEnum.getSubresourceWithUUID))));
            operation.addResponse("404", response404);

        } else if (operationEnum == OperationEnum.delete) {
            operation.setSummary("Delete resource by uuid");
            operation.setOperationId("delete" + getOperationTitle(resourceHandler, false));
            operation.parameter(buildRequiredUUIDParameter("uuid", "uuid to delete"));
            operation.response(204, response204);
            operation.response(404, response404);

        } else if (operationEnum == OperationEnum.deleteSubresource) {
            operation.setSummary("Delete " + resourceName + " subresource by uuid");
            operation.setOperationId("delete" + getOperationTitle(resourceHandler, false));
            operation.parameter(buildRequiredUUIDParameter("parent-uuid", "parent resource uuid"));
            operation.parameter(buildRequiredUUIDParameter("uuid", "uuid to delete"));
            operation.response(204, response204);
            operation.response(404, response404);

        } else if (operationEnum == OperationEnum.purge) {
            operation.setSummary("Purge resource by uuid");
            operation.setOperationId("purge" + getOperationTitle(resourceHandler, false));
            operation.parameter(buildRequiredUUIDParameter("uuid", "uuid to delete"));
            operation.response(204, response204);

        } else if (operationEnum == OperationEnum.purgeSubresource) {
            operation.setSummary("Purge " + resourceName + " subresource by uuid");
            operation.setOperationId("purge" + getOperationTitle(resourceHandler, false));
            operation.parameter(buildRequiredUUIDParameter("parent-uuid", "parent resource uuid"));
            operation.parameter(buildRequiredUUIDParameter("uuid", "uuid to delete"));
            operation.response(204, response204);
        }

        operation.response(401, response401);

        return operation;
    }

    public boolean hasSearchHandler(String resourceName, String resourceParentName) {
        if (resourceParentName != null) {
            resourceName = "v1/" + resourceParentName + "/" + resourceName;
        } else {
            resourceName = "v1/" + resourceName;
        }

        for (Object searchHandler : searchHandlers) {
            try {
                Method getSearchConfigMethod = searchHandler.getClass().getMethod("getSearchConfig");
                Object searchConfig = getSearchConfigMethod.invoke(searchHandler);
                
                Method getSupportedResourceMethod = searchConfig.getClass().getMethod("getSupportedResource");
                String supportedResource = (String) getSupportedResourceMethod.invoke(searchConfig);
                
                if (supportedResource.equals(resourceName)) {
                    return true;
                }
            } catch (Exception e) {
                if (log != null) {
                    log.warn("Error checking search handler", e);
                }
            }
        }
        return false;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public Swagger getSwagger() {
        return swagger;
    }

    /**
     * Enum for operation types
     */
    public enum OperationEnum {
        get, getSubresource, getWithUUID, getSubresourceWithUUID, getWithDoSearch,
        postCreate, postSubresource, postUpdate, postUpdateSubresouce,
        delete, deleteSubresource, purge, purgeSubresource
    }

    /**
     * Inner class for module version information
     */
    public static class ModuleVersion {
        private String moduleId;
        private String version;

        public ModuleVersion(String moduleId, String version) {
            this.moduleId = moduleId;
            this.version = version;
        }

        public String getModuleId() {
            return moduleId;
        }

        public String getVersion() {
            return version;
        }
    }
}