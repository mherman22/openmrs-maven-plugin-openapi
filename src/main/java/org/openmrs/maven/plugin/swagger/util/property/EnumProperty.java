/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.maven.plugin.swagger.util.property;

import io.swagger.models.properties.StringProperty;

import java.util.Arrays;
import java.util.List;

/**
 * Custom Swagger property for enum types.
 * This is a build-time version that doesn't depend on runtime OpenMRS classes.
 */
public class EnumProperty extends StringProperty {
	
	public EnumProperty(Class<? extends Enum<?>> e) {
		_enum(getEnumsAsList(e));
	}
	
	private List<String> getEnumsAsList(Class<? extends Enum<?>> e) {
		return Arrays.asList(getEnums(e));
	}
	
	private String[] getEnums(Class<? extends Enum<?>> e) {
		return Arrays.toString(e.getEnumConstants())
		        .replaceAll("^.|.$", "").split(", ");
	}
} 