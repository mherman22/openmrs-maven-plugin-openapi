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

import io.swagger.models.Model;
import io.swagger.models.ModelImpl;
import io.swagger.models.properties.ArrayProperty;
import io.swagger.models.properties.BooleanProperty;
import io.swagger.models.properties.DateProperty;
import io.swagger.models.properties.DoubleProperty;
import io.swagger.models.properties.IntegerProperty;
import io.swagger.models.properties.ObjectProperty;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.RefProperty;
import io.swagger.models.properties.StringProperty;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.logging.Log;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reflection-based SwaggerGenerationUtil that is completely independent of OpenMRS webservices module.
 * This class provides methods to dynamically generate schemas for GET, CREATE, and UPDATE operations
 * based on the resource's representations and properties using reflection.
 */
public class ReflectiveSwaggerGenerationUtil {

    private static final Map<Class<?>, Object> resourceHandlers = new HashMap<Class<?>, Object>();

    private static Log log;

    public static void setLog(Log log) {
        ReflectiveSwaggerGenerationUtil.log = log;
    }

    public static void addResourceHandler(Object resourceHandler) {
        resourceHandlers.put(resourceHandler.getClass(), resourceHandler);
    }

    /**
     * Generates the model for GET operations.
     */
    public static Model generateGETModel(Object resourceHandler, String representation) {
        ModelImpl model = new ModelImpl();

        if ("DEFAULT".equals(representation)) {
            model = addDefaultProperties(resourceHandler);
        } else if ("REF".equals(representation)) {
            model = addRefProperties(resourceHandler);
        } else if ("FULL".equals(representation)) {
            model = addFullProperties(resourceHandler);
        } else {
            throw new IllegalArgumentException("Unsupported representation: " + representation);
        }

        return model;
    }

    /**
     * Generates the model for CREATE operations.
     */
    public static Model generateCREATEModel(Object resourceHandler, String representation) {
        ModelImpl model = new ModelImpl();

        if ("DEFAULT".equals(representation)) {
            model = addCreatableProperties(resourceHandler, "Create");
        } else if ("FULL".equals(representation)) {
            model = addCreatableProperties(resourceHandler, "CreateFull");
        } else {
            throw new IllegalArgumentException("Unsupported representation: " + representation);
        }

        return model;
    }

    /**
     * Generates the model for UPDATE operations.
     */
    public static Model generateUPDATEModel(Object resourceHandler, String representation) {
        ModelImpl model = new ModelImpl();

        if ("DEFAULT".equals(representation)) {
            model = addUpdatableProperties(resourceHandler);
        } else {
            throw new IllegalArgumentException("Unsupported representation: " + representation);
        }

        return model;
    }

    /**
     * Adds creatable properties to the schema based on the resource handler's creatable properties.
     */
    private static ModelImpl addCreatableProperties(Object resourceHandler, String operationType) {
        ModelImpl model = new ModelImpl();
        addResourceHandler(resourceHandler);
        
        try {
            Method getCreatablePropertiesMethod = resourceHandler.getClass().getMethod("getCreatableProperties");
            Object description = getCreatablePropertiesMethod.invoke(resourceHandler);
            
            if (description != null) {
                Method getPropertiesMethod = description.getClass().getMethod("getProperties");
                @SuppressWarnings("unchecked")
                Map<String, Object> properties = (Map<String, Object>) getPropertiesMethod.invoke(description);
                
                for (String property : properties.keySet()) {
                    model.property(property, determinePropertyForField(resourceHandler, property, operationType));
                }
            }
        } catch (Exception e) {
            if (log != null) {
                log.warn("Could not get creatable properties for " + resourceHandler.getClass().getName(), e);
            }
        }

        return model;
    }

    /**
     * Adds updatable properties to the schema based on the resource handler's updatable properties.
     */
    private static ModelImpl addUpdatableProperties(Object resourceHandler) {
        ModelImpl model = new ModelImpl();
        addResourceHandler(resourceHandler);
        
        try {
            Method getUpdatablePropertiesMethod = resourceHandler.getClass().getMethod("getUpdatableProperties");
            Object description = getUpdatablePropertiesMethod.invoke(resourceHandler);
            
            if (description != null) {
                Method getPropertiesMethod = description.getClass().getMethod("getProperties");
                @SuppressWarnings("unchecked")
                Map<String, Object> properties = (Map<String, Object>) getPropertiesMethod.invoke(description);
                
                for (String property : properties.keySet()) {
                    model.property(property, determinePropertyForField(resourceHandler, property, "Update"));
                }
            }
        } catch (Exception e) {
            if (log != null) {
                log.warn("Could not get updatable properties for " + resourceHandler.getClass().getName(), e);
            }
        }

        return model;
    }

    /**
     * Adds default properties to the schema based on the resource handler's DEFAULT representation.
     */
    private static ModelImpl addDefaultProperties(Object resourceHandler) {
        ModelImpl model = new ModelImpl();
        addResourceHandler(resourceHandler);
        model.property("uuid", new StringProperty().description("Unique identifier of the resource"));
        model.property("display", new StringProperty().description("Display name of the resource"));

        try {
            Method getRepresentationDescriptionMethod = resourceHandler.getClass().getMethod("getRepresentationDescription", Object.class);
            Object description = getRepresentationDescriptionMethod.invoke(resourceHandler, getRepresentationConstant("DEFAULT"));
            
            if (description != null) {
                Method getPropertiesMethod = description.getClass().getMethod("getProperties");
                @SuppressWarnings("unchecked")
                Map<String, Object> properties = (Map<String, Object>) getPropertiesMethod.invoke(description);
                
                for (String property : properties.keySet()) {
                    model.property(property, determinePropertyForField(resourceHandler, property, "Get"));
                }
            }
        } catch (Exception e) {
            if (log != null) {
                log.warn("Could not get default properties for " + resourceHandler.getClass().getName(), e);
            }
        }

        return model;
    }

    /**
     * Adds reference properties to the schema based on the resource handler's REF representation.
     */
    private static ModelImpl addRefProperties(Object resourceHandler) {
        ModelImpl model = new ModelImpl();
        addResourceHandler(resourceHandler);
        model.property("uuid", new StringProperty().description("Unique identifier of the resource"));
        model.property("display", new StringProperty().description("Display name of the resource"));

        try {
            Method getRepresentationDescriptionMethod = resourceHandler.getClass().getMethod("getRepresentationDescription", Object.class);
            Object description = getRepresentationDescriptionMethod.invoke(resourceHandler, getRepresentationConstant("REF"));
            
            if (description != null) {
                Method getPropertiesMethod = description.getClass().getMethod("getProperties");
                @SuppressWarnings("unchecked")
                Map<String, Object> properties = (Map<String, Object>) getPropertiesMethod.invoke(description);
                
                for (String property : properties.keySet()) {
                    model.property(property, determinePropertyForField(resourceHandler, property, "GetRef"));
                }
            }
        } catch (Exception e) {
            if (log != null) {
                log.warn("Could not get ref properties for " + resourceHandler.getClass().getName(), e);
            }
        }

        return model;
    }

    /**
     * Adds full properties to the schema based on the resource handler's FULL representation.
     */
    private static ModelImpl addFullProperties(Object resourceHandler) {
        ModelImpl model = new ModelImpl();
        addResourceHandler(resourceHandler);

        try {
            Method getRepresentationDescriptionMethod = resourceHandler.getClass().getMethod("getRepresentationDescription", Object.class);
            Object description = getRepresentationDescriptionMethod.invoke(resourceHandler, getRepresentationConstant("FULL"));
            
            if (description != null) {
                Method getPropertiesMethod = description.getClass().getMethod("getProperties");
                @SuppressWarnings("unchecked")
                Map<String, Object> properties = (Map<String, Object>) getPropertiesMethod.invoke(description);
                
                for (String property : properties.keySet()) {
                    model.property(property, determinePropertyForField(resourceHandler, property, "GetFull"));
                }
            }
        } catch (Exception e) {
            if (log != null) {
                log.warn("Could not get full properties for " + resourceHandler.getClass().getName(), e);
            }
        }

        return model;
    }

    /**
     * Gets the representation constant using reflection.
     */
    private static Object getRepresentationConstant(String representation) {
        try {
            // Try to find the Representation class and get the constant
            Class<?> representationClass = Class.forName("org.openmrs.module.webservices.rest.web.representation.Representation");
            return representationClass.getField(representation).get(null);
        } catch (Exception e) {
            // If we can't find the class, return a simple object
            return new Object() {
                @Override
                public String toString() {
                    return representation;
                }
            };
        }
    }

    /**
     * Determines the property for a field based on the resource handler and property name.
     */
    public static Property determinePropertyForField(Object resourceHandler, String propertyName, String operationType) {
        Class<?> genericType = getGenericType(resourceHandler.getClass());
        if (genericType == null) {
            // Worst case scenario, no parameterized superclass / interface found in the class hierarchy
            throw new IllegalArgumentException("No generic type for resource handler");
        }

        try {
            Field field = genericType.getDeclaredField(propertyName);
            return createPropertyForType(field.getType(), operationType, field);
        } catch (NoSuchFieldException e) {
            if (log != null) {
                log.warn("Field {} not found in class {}");
            }
            return new StringProperty();
        }
    }

    /**
     * Maps Java types to their corresponding Swagger properties.
     */
    public static Property createPropertyForType(Class<?> type, String operationType, Field field) {
        if (String.class.equals(type)) {
            return new StringProperty();
        } else if (Integer.class.equals(type) || int.class.equals(type)) {
            return new IntegerProperty();
        } else if (Boolean.class.equals(type) || boolean.class.equals(type)) {
            return new BooleanProperty();
        } else if (UUID.class.equals(type)) {
            return new StringProperty().description("uuid");
        } else if (java.util.Date.class.equals(type)) {
            return new DateProperty();
        } else if (Double.class.equals(type)) {
            return new DoubleProperty();
        } else if (isOpenMRSResource(type)) {
            if (type.isEnum()) {
                return new StringProperty().description("enum");
            } else {
                String resourceName = getResourceNameBySupportedClass(type);
                if (resourceName == null) {
                    return new StringProperty();
                }
                return new RefProperty("#/definitions/" + StringUtils.capitalize(resourceName) + operationType);
            }
        } else if (Set.class.equals(type) || List.class.equals(type)) {
            Class<?> elementType = getGenericTypeFromField(field);
            if (isOpenMRSResource(elementType)) {
                String resourceName = getSubResourceNameBySupportedClass(elementType);
                if (resourceName == null) {
                    return new StringProperty();
                }
                return new ArrayProperty(new RefProperty("#/definitions/" + StringUtils.capitalize(resourceName) + operationType));
            }
            return new ArrayProperty();
        } else {
            return new ObjectProperty();
        }
    }

    /**
     * Retrieves the name of a resource or sub-resource associated with a given class.
     */
    public static String getResourceNameBySupportedClass(Class<?> supportedClass) {
        for (Object resourceHandler : resourceHandlers.values()) {
            try {
                Class<?> resourceClass = resourceHandler.getClass();
                Object resourceAnnotation = resourceClass.getAnnotation(getResourceAnnotationClass());
                Object subResourceAnnotation = resourceClass.getAnnotation(getSubResourceAnnotationClass());

                if (resourceAnnotation != null) {
                    Method supportedClassMethod = resourceAnnotation.getClass().getMethod("supportedClass");
                    Class<?> annotationSupportedClass = (Class<?>) supportedClassMethod.invoke(resourceAnnotation);
                    
                    if (annotationSupportedClass.equals(supportedClass)) {
                        Method nameMethod = resourceAnnotation.getClass().getMethod("name");
                        String name = (String) nameMethod.invoke(resourceAnnotation);
                        return name.substring(name.indexOf('/') + 1);
                    }
                } else if (subResourceAnnotation != null) {
                    Method supportedClassMethod = subResourceAnnotation.getClass().getMethod("supportedClass");
                    Class<?> annotationSupportedClass = (Class<?>) supportedClassMethod.invoke(subResourceAnnotation);
                    
                    if (annotationSupportedClass.equals(supportedClass)) {
                        Method parentMethod = subResourceAnnotation.getClass().getMethod("parent");
                        Class<?> parentClass = (Class<?>) parentMethod.invoke(subResourceAnnotation);
                        Object parentResourceAnnotation = parentClass.getAnnotation(getResourceAnnotationClass());
                        
                        Method pathMethod = subResourceAnnotation.getClass().getMethod("path");
                        String resourceName = (String) pathMethod.invoke(subResourceAnnotation);
                        
                        Method parentNameMethod = parentResourceAnnotation.getClass().getMethod("name");
                        String parentName = (String) parentNameMethod.invoke(parentResourceAnnotation);
                        String resourceParentName = parentName.substring(parentName.indexOf('/') + 1);

                        String combinedName = capitalize(resourceParentName) + capitalize(resourceName);
                        return combinedName.replace("/", "");
                    }
                }
            } catch (Exception e) {
                if (log != null) {
                    log.warn("Error getting resource name for class: " + supportedClass.getName(), e);
                }
            }
        }
        return null;
    }

    public static String getSubResourceNameBySupportedClass(Class<?> supportedClass) {
        // For now, use the same logic as getResourceNameBySupportedClass
        // In a full implementation, this would use the RestService to get all handlers
        return getResourceNameBySupportedClass(supportedClass);
    }

    public static String capitalize(String name) {
        if (name == null || name.isEmpty()) return name;
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    /**
     * Checks whether a class is an OpenMRS resource (e.g., references an OpenMRS data object).
     */
    private static boolean isOpenMRSResource(Class<?> type) {
        if (type == null) {
            return false;
        }

        Package pkg = type.getPackage();
        return pkg != null && pkg.getName().startsWith("org.openmrs");
    }

    public static String getModelName(String qualifiedName) {
        if (qualifiedName == null || !qualifiedName.contains(".")) {
            return qualifiedName;
        }

        String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        simpleName = simpleName.replace("$", "");
        return simpleName.substring(0, 1).toUpperCase() + simpleName.substring(1);
    }

    /**
     * Extracts the generic type argument of a field that represents a parameterized collection (e.g., List<T>, Set<T>).
     * If the field is not parameterized or the generic type cannot be determined, it returns {@code null}.
     */
    private static Class<?> getGenericTypeFromField(Field field) {
        try {
            if (field.getGenericType() instanceof ParameterizedType) {
                ParameterizedType parameterizedType = (ParameterizedType) field.getGenericType();
                Type[] typeArguments = parameterizedType.getActualTypeArguments();
                if (typeArguments.length > 0 && typeArguments[0] instanceof Class) {
                    return (Class<?>) typeArguments[0];
                }
            }
        } catch (Exception e) {
            if (log != null) {
                log.warn("Could not determine the generic type for field: {}. This may not affect functionality.");
            }
        }
        return null;
    }

    /**
     * Extracts the generic type parameter from a specified class or its superclasses
     * that implement a parameterized interface or extend a parameterized class.
     */
    public static Class<?> getGenericType(Class<?> resourceHandlerClass) {
        Class<?> currentClass = resourceHandlerClass;

        while (currentClass != null) {
            Type[] genericInterfaces = currentClass.getGenericInterfaces();
            for (Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType parameterizedType = (ParameterizedType) genericInterface;
                    Type[] typeArguments = parameterizedType.getActualTypeArguments();

                    if (typeArguments.length > 0 && typeArguments[0] instanceof Class<?>) {
                        return (Class<?>) typeArguments[0];
                    }
                }
            }

            Type genericSuperclass = currentClass.getGenericSuperclass();
            if (genericSuperclass instanceof ParameterizedType) {
                ParameterizedType parameterizedType = (ParameterizedType) genericSuperclass;
                Type[] typeArguments = parameterizedType.getActualTypeArguments();

                if (typeArguments.length > 0 && typeArguments[0] instanceof Class<?>) {
                    return (Class<?>) typeArguments[0];
                }
            }

            currentClass = currentClass.getSuperclass();
        }

        return null;
    }

    /**
     * Gets the Resource annotation class using reflection.
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> getResourceAnnotationClass() {
        try {
            return (Class<? extends Annotation>) Class.forName("org.openmrs.module.webservices.rest.web.annotation.Resource");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Gets the SubResource annotation class using reflection.
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> getSubResourceAnnotationClass() {
        try {
            return (Class<? extends Annotation>) Class.forName("org.openmrs.module.webservices.rest.web.annotation.SubResource");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
} 