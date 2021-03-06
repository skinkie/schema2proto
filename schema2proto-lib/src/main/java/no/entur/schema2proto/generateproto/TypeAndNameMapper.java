package no.entur.schema2proto.generateproto;

/*-
 * #%L
 * schema2proto-lib
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

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TypeAndNameMapper {
	private Map<Pattern, String> typeMappings = new LinkedHashMap<>();
	private Map<Pattern, String> typeReplacing = new LinkedHashMap<>();
	private Map<Pattern, String> nameMappings = new LinkedHashMap<>();
	private Set<String> reservedJavaKeywords = new HashSet<>();
	private List<FieldPath> ignoreFieldPaths;

	public TypeAndNameMapper(Schema2ProtoConfiguration configuration) {
		typeReplacing.putAll(getStandardXsdTypeMappings());
		// From external
		updateMappings(typeReplacing, configuration.customTypeReplacements);

		// From external configuration
		updateMappings(typeMappings, configuration.customTypeMappings);

		// From external configuration
		updateMappings(nameMappings, configuration.customNameMappings);

		reservedJavaKeywords.addAll(getReservedWords());

		this.ignoreFieldPaths = configuration.ignoreOutputFields;

	}

	private void updateMappings(Map<Pattern, String> existing, Map<Pattern, String> updated) {
		// Compiled patterns cannot be compared using equals(!)

		for (Pattern updatedKey : updated.keySet()) {
			Set<Pattern> keys = new HashSet<>(existing.keySet());
			for (Pattern key : keys) {
				if (key.pattern().equals(updatedKey.pattern())) {
					existing.remove(key); // Or it will remain
				}
			}
			existing.put(updatedKey, updated.get(updatedKey));
		}

	}

	private Map<Pattern, String> getStandardXsdTypeMappings() {
		Map<Pattern, String> standardTypeMappings = new HashMap<>();

		// From https://www.w3.org/TR/xmlschema-2/#d0e11239.
		// Same order as defined above

		standardTypeMappings.put(Pattern.compile("^string$"), "string");
		standardTypeMappings.put(Pattern.compile("^boolean$"), "bool");
		standardTypeMappings.put(Pattern.compile("^float$"), "float");
		standardTypeMappings.put(Pattern.compile("^double$"), "double");
		standardTypeMappings.put(Pattern.compile("^decimal$"), "double");
		standardTypeMappings.put(Pattern.compile("^duration$"), "string"); // XML duration string
		standardTypeMappings.put(Pattern.compile("^dateTime$"), "uint64"); // Number of milliseconds since January 1st), 1970
		standardTypeMappings.put(Pattern.compile("^time$"), "uint64"); // Number of milliseconds since midnight
		standardTypeMappings.put(Pattern.compile("^date$"), "uint32"); // Number of days since January 1st 1970

		standardTypeMappings.put(Pattern.compile("^gYearMonth$"), "string");
		standardTypeMappings.put(Pattern.compile("^gYear$"), "uint32");
		standardTypeMappings.put(Pattern.compile("^gMonthDay$"), "string");
		standardTypeMappings.put(Pattern.compile("^gDay$"), "uint32");
		standardTypeMappings.put(Pattern.compile("^gMonth$"), "uint32");

		standardTypeMappings.put(Pattern.compile("^hexBinary$"), "bytes");
		standardTypeMappings.put(Pattern.compile("^base64Binary$"), "bytes");
		standardTypeMappings.put(Pattern.compile("^anyURI$"), "string");
		standardTypeMappings.put(Pattern.compile("^QName$"), "string");
		standardTypeMappings.put(Pattern.compile("^NOTATION$"), "string"); // Unsure

		standardTypeMappings.put(Pattern.compile("^normalizedString$"), "string");
		standardTypeMappings.put(Pattern.compile("^token$"), "string");
		standardTypeMappings.put(Pattern.compile("^language$"), "string");

		standardTypeMappings.put(Pattern.compile("^IDREFS$"), "string");
		standardTypeMappings.put(Pattern.compile("^ENTITIES$"), "string");
		standardTypeMappings.put(Pattern.compile("^NMTOKEN$"), "string");
		standardTypeMappings.put(Pattern.compile("^NMTOKENS$"), "string"); // TODO: Fix this should be repeated string
		standardTypeMappings.put(Pattern.compile("^Name$"), "string");
		standardTypeMappings.put(Pattern.compile("^NCName$"), "string");
		standardTypeMappings.put(Pattern.compile("^ID$"), "string");
		standardTypeMappings.put(Pattern.compile("^IDREF$"), "string");
		standardTypeMappings.put(Pattern.compile("^ENTITY$"), "string");

		standardTypeMappings.put(Pattern.compile("^integer$"), "int32");
		standardTypeMappings.put(Pattern.compile("^nonPositiveInteger$"), "sint32");
		standardTypeMappings.put(Pattern.compile("^negativeInteger$"), "sint32");
		standardTypeMappings.put(Pattern.compile("^long$"), "int64");
		standardTypeMappings.put(Pattern.compile("^int$"), "int32");
		standardTypeMappings.put(Pattern.compile("^short$"), "int32"); // No 16-bit int in protobuf
		standardTypeMappings.put(Pattern.compile("^byte$"), "bytes");

		standardTypeMappings.put(Pattern.compile("^nonNegativeInteger$"), "uint32");
		standardTypeMappings.put(Pattern.compile("^unsignedLong$"), "uint64");
		standardTypeMappings.put(Pattern.compile("^unsignedInt$"), "uint32");
		standardTypeMappings.put(Pattern.compile("^unsignedShort$"), "uint32"); // No 16-bit int in protobuf
		standardTypeMappings.put(Pattern.compile("^unsignedByte$"), "uint32"); // No 8-bit int in protobuf
		standardTypeMappings.put(Pattern.compile("^positiveInteger$"), "uint32");

		standardTypeMappings.put(Pattern.compile("^anySimpleType$"), "string"); // base type of all primitive types
		standardTypeMappings.put(Pattern.compile("^anyType$"), "string"); // Wildcard

		return standardTypeMappings;
	}

	public String translateType(String type) {

		for (Map.Entry<Pattern, String> p : typeMappings.entrySet()) {
			Matcher m = p.getKey().matcher(type);
			if (m.matches()) {
				type = m.replaceAll(p.getValue());
				break;
			}
		}

		type = type.replaceAll("-", "");

		return type;

	}

	public String translateFieldName(String name) {
		for (Pattern p : nameMappings.keySet()) {
			Matcher m = p.matcher(name);
			if (m.matches()) {
				return m.replaceAll(nameMappings.get(p));
			}
		}

		return name;
	}

	public String escapeFieldName(String fieldName) {
		if (reservedJavaKeywords.contains(fieldName)) {
			return fieldName + "_field";
		} else {
			return fieldName;
		}
	}

	public boolean ignoreOutputField(String packageName, String messageName, String fieldName) {
		for (FieldPath f : ignoreFieldPaths) {
			if (f.matches(packageName, messageName, fieldName)) {
				return true;
			}
		}

		return false;
	}

	private Set<String> getReservedWords() {

		Set<String> reservedJavaKeywords = new HashSet<>();
		reservedJavaKeywords.add("abstract");
		reservedJavaKeywords.add("assert");
		reservedJavaKeywords.add("boolean");
		reservedJavaKeywords.add("break");
		reservedJavaKeywords.add("byte");
		reservedJavaKeywords.add("case");
		reservedJavaKeywords.add("catch");
		reservedJavaKeywords.add("char");
		reservedJavaKeywords.add("class");
		reservedJavaKeywords.add("const");
		reservedJavaKeywords.add("default");

		reservedJavaKeywords.add("do");
		reservedJavaKeywords.add("double");
		reservedJavaKeywords.add("else");
		reservedJavaKeywords.add("enum");
		reservedJavaKeywords.add("extends");
		reservedJavaKeywords.add("false");
		reservedJavaKeywords.add("final");
		reservedJavaKeywords.add("finally");
		reservedJavaKeywords.add("float");
		reservedJavaKeywords.add("for");
		reservedJavaKeywords.add("goto");

		reservedJavaKeywords.add("if");
		reservedJavaKeywords.add("implements");
		reservedJavaKeywords.add("import");
		reservedJavaKeywords.add("instanceof");
		reservedJavaKeywords.add("int");
		reservedJavaKeywords.add("interface");
		reservedJavaKeywords.add("long");
		reservedJavaKeywords.add("native");
		reservedJavaKeywords.add("new");
		reservedJavaKeywords.add("null");
		reservedJavaKeywords.add("package");

		reservedJavaKeywords.add("private");
		reservedJavaKeywords.add("protected");
		reservedJavaKeywords.add("public");
		reservedJavaKeywords.add("return");
		reservedJavaKeywords.add("short");
		reservedJavaKeywords.add("static");
		reservedJavaKeywords.add("strictfp");
		reservedJavaKeywords.add("super");
		reservedJavaKeywords.add("switch");
		reservedJavaKeywords.add("synchronized");

		reservedJavaKeywords.add("this");
		reservedJavaKeywords.add("throw");
		reservedJavaKeywords.add("throws");
		reservedJavaKeywords.add("transient");
		reservedJavaKeywords.add("true");
		reservedJavaKeywords.add("try");
		reservedJavaKeywords.add("void");
		reservedJavaKeywords.add("volatile");
		reservedJavaKeywords.add("while");
		reservedJavaKeywords.add("continue");

		return reservedJavaKeywords;
	}

	public String replaceType(String type) {
		for (Map.Entry<Pattern, String> p : typeReplacing.entrySet()) {
			Matcher m = p.getKey().matcher(type);
			if (m.matches()) {
				type = m.replaceAll(p.getValue());
				break;
			}
		}

		return type;

	}
}
