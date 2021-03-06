/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.wire.schema;

/*-
 * #%L
 * schema2proto-wire
 * %%
 * Copyright (C) 2019 Entur
 * %%
 * Licensed under the EUPL, Version 1.1 or – as soon they will be
 * approved by the European Commission - subsequent versions of the
 * EUPL (the "Licence");
 * 
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 * 
 * http://ec.europa.eu/idabc/eupl5
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 * #L%
 */

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.squareup.wire.schema.internal.parser.ExtendElement;

public final class Extend {
	private final Location location;
	private final String documentation;
	private final String name;
	private final List<Field> fields;
	private ProtoType protoType;

	public Extend(Location location, String documentation, String name, List<Field> fields) {
		this.location = location;
		this.documentation = documentation;
		this.name = name;
		this.fields = fields;
	}

	static ImmutableList<Extend> fromElements(String packageName, List<ExtendElement> extendElements) {
		ImmutableList.Builder<Extend> extendBuilder = new ImmutableList.Builder<>();
		for (ExtendElement extendElement : extendElements) {
			extendBuilder.add(new Extend(extendElement.getLocation(), extendElement.getDocumentation(), extendElement.getName(),
					Field.fromElements(packageName, extendElement.getFields(), true)));
		}
		return extendBuilder.build();
	}

	static ImmutableList<ExtendElement> toElements(List<Extend> extendList) {
		ImmutableList.Builder<ExtendElement> elements = new ImmutableList.Builder<>();
		for (Extend extend : extendList) {
			elements.add(new ExtendElement(extend.location, extend.name, extend.documentation, Field.toElements(extend.fields)));
		}
		return elements.build();
	}

	public Location location() {
		return location;
	}

	public ProtoType type() {
		return protoType;
	}

	public String documentation() {
		return documentation;
	}

	public List<Field> fields() {
		return fields;
	}

	void link(Linker linker) {
		linker = linker.withContext(this);
		protoType = linker.resolveMessageType(name);
		Type type = linker.get(protoType);
		if (type != null) {
			((MessageType) type).addExtensionFields(fields);
		}
	}

	void validate(Linker linker) {
		linker = linker.withContext(this);
		linker.validateImport(location(), type());
	}
}
