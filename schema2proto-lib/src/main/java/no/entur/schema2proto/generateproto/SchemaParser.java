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

import static com.squareup.wire.schema.MessageType.XSD_MESSAGE_OPTIONS_PACKAGE;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.xml.parsers.SAXParserFactory;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.squareup.wire.schema.EnumConstant;
import com.squareup.wire.schema.EnumType;
import com.squareup.wire.schema.Field;
import com.squareup.wire.schema.Field.Label;
import com.squareup.wire.schema.Location;
import com.squareup.wire.schema.MessageType;
import com.squareup.wire.schema.OneOf;
import com.squareup.wire.schema.Options;
import com.squareup.wire.schema.ProtoFile;
import com.squareup.wire.schema.ProtoFile.Syntax;
import com.squareup.wire.schema.ProtoType;
import com.squareup.wire.schema.Type;
import com.squareup.wire.schema.internal.parser.OptionElement;
import com.sun.xml.xsom.XSAttContainer;
import com.sun.xml.xsom.XSAttGroupDecl;
import com.sun.xml.xsom.XSAttributeDecl;
import com.sun.xml.xsom.XSAttributeUse;
import com.sun.xml.xsom.XSComplexType;
import com.sun.xml.xsom.XSComponent;
import com.sun.xml.xsom.XSElementDecl;
import com.sun.xml.xsom.XSFacet;
import com.sun.xml.xsom.XSListSimpleType;
import com.sun.xml.xsom.XSModelGroup;
import com.sun.xml.xsom.XSModelGroupDecl;
import com.sun.xml.xsom.XSParticle;
import com.sun.xml.xsom.XSRestrictionSimpleType;
import com.sun.xml.xsom.XSSchema;
import com.sun.xml.xsom.XSSchemaSet;
import com.sun.xml.xsom.XSSimpleType;
import com.sun.xml.xsom.XSTerm;
import com.sun.xml.xsom.XSType;
import com.sun.xml.xsom.parser.XSOMParser;
import com.sun.xml.xsom.util.DomAnnotationParserFactory;

public class SchemaParser implements ErrorHandler {

	public static final String TYPE_SUFFIX = "Type";
	public static final String GENERATED_NAME_PLACEHOLDER = "GeneratedTypePlaceholder";

	private static final Logger LOGGER = LoggerFactory.getLogger(SchemaParser.class);

	private final Map<String, ProtoFile> packageToProtoFileMap = new TreeMap<>();

	private final Map<MessageType, Set<Object>> elementDeclarationsPerMessageType = new HashMap<>();
	private Set<String> basicTypes;

	private int nestingLevel = 0;

	private final List<LocalType> localTypes = new ArrayList<>();

	private final Schema2ProtoConfiguration configuration;

	private PGVRuleFactory ruleFactory;

	private void init() {
		basicTypes = new TreeSet<>();
		basicTypes.addAll(TypeRegistry.getBasicTypes());
		ruleFactory = new PGVRuleFactory(configuration, this);
	}

	public SchemaParser(Schema2ProtoConfiguration configuration) {
		this.configuration = configuration;
		init();
	}

	public Map<String, ProtoFile> parse() throws SAXException, IOException {

		SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();
		saxParserFactory.setNamespaceAware(true);

		XSOMParser parser = new XSOMParser(saxParserFactory);
		parser.setErrorHandler(this);

		parser.setAnnotationParser(new DomAnnotationParserFactory());
		parser.parse(configuration.xsdFile);

		processSchemaSet(parser.getResult());

		return packageToProtoFileMap;
	}

	private void addType(String namespace, Type type) {
		ProtoFile file = getProtoFileForNamespace(namespace);
		file.types().add(type);
	}

	private ProtoFile getProtoFileForPackage(String packageName) {

		if (StringUtils.trimToNull(packageName) == null) {
			packageName = Schema2ProtoConfiguration.DEFAULT_PROTO_PACKAGE;
		}

		if (configuration.defaultProtoPackage != null) {
			packageName = configuration.defaultProtoPackage;
		}

		ProtoFile file = packageToProtoFileMap.get(packageName);
		if (file == null) {
			file = new ProtoFile(Syntax.PROTO_3, packageName);
			packageToProtoFileMap.put(packageName, file);
		}
		return file;

	}

	private ProtoFile getProtoFileForNamespace(String namespace) {
		String packageName = NamespaceHelper.xmlNamespaceToProtoPackage(namespace, configuration.forceProtoPackage);
		return getProtoFileForPackage(packageName);
	}

	private Type getType(String namespace, String typeName) {
		ProtoFile protoFileForNamespace = getProtoFileForNamespace(namespace);
		for (Type t : protoFileForNamespace.types()) {
			if (t instanceof MessageType) {
				if (((MessageType) t).getName().equals(typeName)) {
					return t;
				}
			} else if (t instanceof EnumType) {
				if (((EnumType) t).name().equals(typeName)) {
					return t;
				}
			}
		}

		return null;
	}

	private void processSchemaSet(XSSchemaSet schemaSet) {

		Iterator<XSSchema> schemas = schemaSet.iterateSchema();
		while (schemas.hasNext()) {
			XSSchema schema = schemas.next();
			if (!schema.getTargetNamespace().endsWith("/XMLSchema")) {
				final Map<String, XSSimpleType> sortedSimpleTypes = new TreeMap<>(schema.getSimpleTypes());
				for (XSSimpleType type : sortedSimpleTypes.values()) {
					processSimpleType(type, null);
				}

				final Map<String, XSComplexType> sortedComplexTypes = new TreeMap<>(schema.getComplexTypes());
				for (XSComplexType type : sortedComplexTypes.values()) {
					processComplexType(type, null, schemaSet, null, null);
				}

				final Map<String, XSElementDecl> sortedElements = new TreeMap<>(schema.getElementDecls());
				for (XSElementDecl elementDecl : sortedElements.values()) {
					if (elementDecl.getType().isLocal()) {
						processElement(elementDecl, schemaSet);
					} else {
						LOGGER.debug("Skipping global element " + elementDecl.getName() + " declaration with global type " + elementDecl.getType().getName());
					}
				}
			}
		}
	}

	private String processElement(XSElementDecl el, XSSchemaSet schemaSet) {
		XSComplexType cType;
		XSSimpleType xs;

		if (el.getType() instanceof XSComplexType && el.getType() != schemaSet.getAnyType()) {
			cType = (XSComplexType) el.getType();
			MessageType t = processComplexType(cType, el.getName(), schemaSet, null, null);
			if (cType.isGlobal()) {
				addType(el.getTargetNamespace(), t);
			}
			return t.getName();
		} else if (el.getType() instanceof XSSimpleType && el.getType() != schemaSet.getAnySimpleType()) {
			xs = el.getType().asSimpleType();
			return processSimpleType(xs, el.getName());
		} else {
			LOGGER.info("Unhandled element " + el + " at " + el.getLocator().getSystemId() + " at line/col " + el.getLocator().getLineNumber() + "/"
					+ el.getLocator().getColumnNumber());

			return null;
		}
	}

	private String processSimpleType(XSSimpleType xs, String elementName) {

		nestingLevel++;

		LOGGER.debug(StringUtils.leftPad(" ", nestingLevel) + "SimpleType " + xs);

		String typeName = xs.getName();

		if (typeName == null) {
			typeName = elementName + GENERATED_NAME_PLACEHOLDER;
		}

		if (xs.isRestriction() && xs.getFacet("enumeration") != null) {
			createEnum(typeName, xs.asRestriction(), null);
		}

		nestingLevel--;
		return typeName;
	}

	private void addField(MessageType message, Field newField) {
		addField(message, null, newField);
	}

	private void addField(MessageType message, OneOf oneOf, Field newField) {
		// Remove old fields of same type (element or attribute)
		Field existingField = null;
		for (Field f : message.fieldsAndOneOfFields()) {
			if (newField.name().equals(f.name())) {
				existingField = f;
				break;
			}
		}
		if (existingField != null) {
			// Override should happen
			if (existingField.isFromAttribute() && !newField.isFromAttribute()) {
				existingField.updateName("attr_" + existingField.name());
			} else if (!existingField.isFromAttribute() && newField.isFromAttribute()) {
				newField.updateName("attr_" + newField.name());
			} else {
				message.removeDeclaredField(existingField);
			}
		}

		if (oneOf != null) {
			oneOf.addField(newField);
		} else {
			message.addField(newField);
		}
	}

	private void navigateSubTypes(XSParticle parentParticle, MessageType messageType, Set<Object> processedXmlObjects, XSSchemaSet schemaSet,
			String enclosingName, String targetNamespace, XSComplexType enclosingType) {

		XSTerm currTerm = parentParticle.getTerm();

		Label label = getRange(parentParticle) ? Label.REPEATED : null;
		Options fieldOptions = getFieldOptions(parentParticle);

		if (currTerm.isElementDecl()) {
			XSElementDecl currElementDecl = currTerm.asElementDecl();
			if (!processedXmlObjects.contains(currElementDecl)) {
				processedXmlObjects.add(currElementDecl);

				XSType type = currElementDecl.getType();
				String fieldDoc = resolveDocumentationAnnotation(currElementDecl);
				Location fieldLocation = getLocation(currElementDecl);

				String packageName = NamespaceHelper.xmlNamespaceToProtoFieldPackagename(type.getTargetNamespace(), configuration.forceProtoPackage);

				if (type.isSimpleType()) {

					if (type.asSimpleType().isRestriction() && type.asSimpleType().getFacet("enumeration") != null) {
						String enumName = createEnum(currElementDecl.getName(), type.asSimpleType().asRestriction(), type.isGlobal() ? null : messageType);
						Field field = new Field(packageName, fieldLocation, label, currElementDecl.getName(), fieldDoc, messageType.getNextFieldNum(), enumName,
								fieldOptions, true);
						addField(messageType, field);
					} else {
						String typeName = findFieldType(type);
						Field field = new Field(basicTypes.contains(typeName) ? null : packageName, fieldLocation, label, currElementDecl.getName(), fieldDoc,
								messageType.getNextFieldNum(), typeName, fieldOptions, true);
						addField(messageType, field);
					}

				} else {
					// Complex type

					if (type.isGlobal()) {

						Set<? extends XSElementDecl> substitutables = currElementDecl.getSubstitutables();
						if (substitutables.size() <= 1) {
							Field field = new Field(packageName, fieldLocation, label, currElementDecl.getName(), fieldDoc, messageType.getNextFieldNum(),
									type.getName(), fieldOptions, true);
							addField(messageType, field);
						} else {
							List<Field> fields = new ArrayList<>();
							OneOf oneOf = new OneOf(currElementDecl.getType().getName(), fieldDoc, fields);
							messageType.oneOfs().add(oneOf);
							for (XSElementDecl substitutable : substitutables) {
								if (substitutable.isAbstract()) {
									// No abstract concept in protobuf, only concrete messages
								} else {
									String substDoc = resolveDocumentationAnnotation(substitutable);

									String typeName = substitutable.getType().getName();
									if (typeName == null) {
										typeName = processElement(substitutable, schemaSet);
									}

									Field field = new Field(
											NamespaceHelper.xmlNamespaceToProtoFieldPackagename(substitutable.getType().getTargetNamespace(),
													configuration.forceProtoPackage),
											fieldLocation, null, substitutable.getName(), substDoc, messageType.getNextFieldNum(), typeName, fieldOptions,
											true);
									addField(messageType, oneOf, field); // Repeated oneOf not allowed
								}
							}
						}
					} else {
						// Local

						MessageType referencedMessageType = processComplexType(type.asComplexType(), currElementDecl.getName(), schemaSet, null, null);
						Field field = new Field(packageName, fieldLocation, label, currElementDecl.getName(), fieldDoc, messageType.getNextFieldNum(),
								referencedMessageType.getName(), fieldOptions, true);
						addField(messageType, field);

						if (!currElementDecl.isGlobal()) {
							messageType.nestedTypes().add(referencedMessageType);
							localTypes.add(new LocalType(type, referencedMessageType, messageType, field,
									NamespaceHelper.xmlNamespaceToProtoPackage(type.getTargetNamespace(), configuration.forceProtoPackage), enclosingType));

						}
					}
				}
			}
		} else if (currTerm.isWildcard()) {
			Location fieldLocation;
			if (currTerm.getLocator() != null) {
				fieldLocation = getLocation(currTerm.asWildcard());
			} else {
				fieldLocation = messageType.location();
			}

			Field field = new Field(null, fieldLocation, label, "any", resolveDocumentationAnnotation(currTerm.asWildcard()), messageType.getNextFieldNum(),
					"anyType", fieldOptions, true);
			addField(messageType, field);

		} else {
			XSModelGroup modelGroup = getModelGroup(currTerm);
			if (modelGroup != null) {
				groupProcessing(modelGroup, parentParticle, messageType, processedXmlObjects, schemaSet, enclosingName, targetNamespace, enclosingType);
			}

		}
	}

	@NotNull
	private Options getFieldOptions(XSParticle parentParticle) {
		List<OptionElement> optionElements = new ArrayList<>(ruleFactory.getValidationRule(parentParticle));
		return new Options(Options.FIELD_OPTIONS, optionElements);
	}

	@NotNull
	private Options getFieldOptions(XSAttributeDecl attributeDecl) {
		List<OptionElement> optionElements = new ArrayList<>();

		// First see if there are rules associated with attribute declaration
		List<OptionElement> validationRule = ruleFactory.getValidationRule(attributeDecl);
		if (validationRule.size() > 0) {
			optionElements.addAll(validationRule);
		} else {
			// Check attribute TYPE rules
			List<OptionElement> typeRule = ruleFactory.getValidationRule(attributeDecl.getType());
			optionElements.addAll(typeRule);
		}
		return new Options(Options.FIELD_OPTIONS, optionElements);
	}

	@NotNull
	private Options getFieldOptions(XSSimpleType attributeDecl) {
		List<OptionElement> optionElements = new ArrayList<>(ruleFactory.getValidationRule(attributeDecl));
		return new Options(Options.FIELD_OPTIONS, optionElements);
	}

	public String findFieldType(XSType type) {
		String typeName = type.getName();
		if (typeName == null) {

			if (type.asSimpleType().isRestriction()) {

				try {
					return findFieldType(type.asSimpleType().asRestriction().getBaseType());

				} catch (ClassCastException e) {
					LOGGER.warn(
							"Error getting base type for restriction " + type + ". Appears to be a bug in xsom. Fallback to string field type (best guess)");
					return "string";
				}

				// findFieldType((findBaseType(restriction));
			} else if (type.asSimpleType().isPrimitive()) {
				typeName = type.asSimpleType().getName();
			} else if (type.asSimpleType().isList()) {
				XSListSimpleType asList = type.asSimpleType().asList();
				XSSimpleType itemType = asList.getItemType();
				typeName = itemType.getName();
			} else if (type.asSimpleType().isUnion()) {
				typeName = "string"; // Union always resolves to string
			} else {
				typeName = type.asSimpleType().getBaseType().getName();
			}

		} else {
			if (!basicTypes.contains(typeName)) {
				typeName = type.asSimpleType().getBaseType().getName();
			}
		}

		if ((typeName == null || !basicTypes.contains(typeName)) && type.isSimpleType() && type.asSimpleType().isRestriction()) {
			XSType restrictionBase = type.asSimpleType().asRestriction().getBaseType();
			return findFieldType(restrictionBase);
		}
		return typeName;
	}

	private boolean getRange(XSParticle part) {
		int max = 1;
		int min = 1;

		if (part.getMinOccurs() != null) {
			min = part.getMinOccurs().intValue();
		}

		if (part.getMaxOccurs() != null) {
			max = part.getMaxOccurs().intValue();
		}

		return max > 1 || max == -1 || min > 1;
	}

	private XSType getBaseType(XSSchemaSet schemaSet, XSType child) {
		XSType parent = child.getBaseType();

		if (parent != schemaSet.getAnyType()) {
			return parent;
		} else {
			return null;
		}
	}

	private MessageType processComplexType(XSComplexType complexType, String elementName, XSSchemaSet schemaSet, MessageType messageType,
			Set<Object> processedXmlObjects) {

		nestingLevel++;

		LOGGER.debug(StringUtils.leftPad(" ", nestingLevel) + "ComplexType " + complexType + ", proto " + messageType);

		boolean isBaseLevel = messageType == null;

		String typeName = null;
		if (messageType != null) {
			typeName = messageType.getName();
		}

		if (messageType == null) {
			if (configuration.skipEmptyTypeInheritance) {

				while (complexType.getContentType().asParticle() == null && complexType.getAttributeUses().size() == 0
						&& complexType.getBaseType().isComplexType()) {
					// Empty complex type
					complexType = complexType.getBaseType().asComplexType();
				}
			}
			typeName = complexType.getName();
			String nameSpace = complexType.getTargetNamespace();

			if (complexType.getScope() != null) {
				elementName = complexType.getScope().getName();
			}

			if (typeName == null) {
				typeName = elementName + GENERATED_NAME_PLACEHOLDER;
			}
			messageType = (MessageType) getType(nameSpace, typeName);

			if (messageType == null && !basicTypes.contains(typeName)) {

				String doc = resolveDocumentationAnnotation(complexType);
				Location location = getLocation(complexType);

				List<OptionElement> messageOptions = new ArrayList<>();

				if (configuration.includeXsdOptions) {
					XSType baseType = getBaseType(schemaSet, complexType);
					if (baseType != null) {
						String prefix = "";
						String packageName = NamespaceHelper.xmlNamespaceToProtoPackage(baseType.getTargetNamespace(), configuration.defaultProtoPackage);
						if (StringUtils.trimToNull(packageName) != null && !baseType.getTargetNamespace().equals(nameSpace)) {
							prefix = packageName + ".";
						}
						OptionElement e = new OptionElement(XSD_MESSAGE_OPTIONS_PACKAGE + "." + MessageType.BASE_TYPE_MESSAGE_OPTION, OptionElement.Kind.STRING,
								prefix + baseType.getName(), true);
						messageOptions.add(e);
					}
				}
				Options options = new Options(Options.MESSAGE_OPTIONS, messageOptions);

				// Add message type to file
				messageType = new MessageType(ProtoType.get(typeName), location, doc, typeName, options);

				if (complexType.isGlobal() | (complexType.getScope() != null && complexType.getScope().isGlobal())) {
					/*
					 * Type is global OR scope is global
					 */

					addType(nameSpace, messageType);
				}

				processedXmlObjects = new HashSet<>();

				elementDeclarationsPerMessageType.put(messageType, processedXmlObjects);

			} else {
				LOGGER.debug(StringUtils.leftPad(" ", nestingLevel) + "Already processed ComplexType " + typeName + ", ignored");
				nestingLevel--;
				return messageType;
			}
		}

		XSType parent = complexType.getBaseType();

		if (configuration.inheritanceToComposition && complexType.getContentType().asParticle() != null) {

			List<MessageType> parentTypes = new ArrayList<>();

			while (parent != schemaSet.getAnyType() && parent.isComplexType()) {

				// Ensure no duplicate element parsing
				MessageType parentType = processComplexType(parent.asComplexType(), elementName, schemaSet, null, null);
				processedXmlObjects.addAll(elementDeclarationsPerMessageType.get(parentType));

				parentTypes.add(parentType);
				parent = parent.getBaseType();
			}

			if (!isAbstract(complexType)) {
				Collections.reverse(parentTypes);
				for (MessageType parentMessageType : parentTypes) {
					String fieldDoc = parentMessageType.documentation();

					List<OptionElement> optionElements = new ArrayList<>();
					Options fieldOptions = new Options(Options.FIELD_OPTIONS, optionElements);
					int tag = messageType.getNextFieldNum();
					Label label = null;
					Location fieldLocation = getLocation(complexType);

					Field field = new Field(findPackageNameForType(parentMessageType), fieldLocation, null, "_" + parentMessageType.getName(), fieldDoc, tag,
							parentMessageType.getName(), fieldOptions, true);

					addField(messageType, field);
				}
				if (parentTypes.size() > 0) {
					messageType.advanceFieldNum();
				}
			}

		} else {
			if (parent != schemaSet.getAnyType() && parent.isComplexType()) {
				processComplexType(parent.asComplexType(), elementName, schemaSet, messageType, processedXmlObjects);
			}
		}

		if (complexType.getAttributeUses() != null) {
			processAttributes(complexType, messageType, processedXmlObjects);
		}

		if (complexType.getContentType().asParticle() != null) {
			XSParticle particle = complexType.getContentType().asParticle();

			XSTerm term = particle.getTerm();
			XSModelGroup modelGroup = getModelGroup(term);

			if (modelGroup != null) {
				String enclosingName = typeName;
				if (modelGroup.isModelGroupDecl()) {
					enclosingName = modelGroup.asModelGroupDecl().getName();
				}

				groupProcessing(modelGroup, particle, messageType, processedXmlObjects, schemaSet, enclosingName, complexType.getTargetNamespace(),
						complexType);
			}

		} else if (complexType.getContentType().asSimpleType() != null) {
			XSSimpleType xsSimpleType = complexType.getContentType().asSimpleType();

			if (isBaseLevel) { // Only add simpleContent from concrete type?
				/*
				 * if(!processedXmlObjects.contains(xsSimpleType)) processedXmlObjects.add(xsSimpleType);
				 */

				boolean isList = xsSimpleType.isList();
				if (isList) {
					xsSimpleType = xsSimpleType.asList().getItemType();
				}

				String name;
				if (xsSimpleType.isUnion()) {
					name = "string";
				} else {
					name = xsSimpleType.getName();
				}

				Location fieldLocation = getLocation(xsSimpleType);
				Label label = isList ? Label.REPEATED : null;
				Options fieldOptions = getFieldOptions(xsSimpleType);

				if (name == null) {
					String simpleTypeName = findFieldType(xsSimpleType);

					String packageName = NamespaceHelper.xmlNamespaceToProtoFieldPackagename(xsSimpleType.getTargetNamespace(),
							configuration.forceProtoPackage);
					Field field = new Field(basicTypes.contains(simpleTypeName) ? null : packageName, fieldLocation, label, "value",
							"SimpleContent value of element", messageType.getNextFieldNum(), simpleTypeName, fieldOptions, true);
					addField(messageType, field);

				} else if (basicTypes.contains(name)) {
					Field field = new Field(null, fieldLocation, label, "value", "SimpleContent value of element", messageType.getNextFieldNum(), name,
							fieldOptions, true);
					addField(messageType, field);

				} else {
					XSSimpleType primitiveType = xsSimpleType.getPrimitiveType();
					if (primitiveType != null) {
						Field field = new Field(null, fieldLocation, label, "value", "SimpleContent value of element", messageType.getNextFieldNum(),
								primitiveType.getName(), fieldOptions, true);
						addField(messageType, field);

					} else {
						LOGGER.warn("Unhandled simpleType " + xsSimpleType);
					}
				}
			}
		}

		nestingLevel--;
		return messageType;

	}

	private boolean isAbstract(XSComplexType complexType) {
		if (complexType.isAbstract()) {
			return true;
		} else {
			if (!complexType.isGlobal()) {
				return false;
			} else if (complexType.getElementDecls().size() > 0) {
				long numAbstractElementDecls = complexType.getElementDecls().stream().map(XSElementDecl::isAbstract).count();
				return complexType.getElementDecls().size() == numAbstractElementDecls;
			} else {
				return true;
			}

		}
	}

	private String findPackageNameForType(MessageType parentMessageType) {
		// Slow and dodgy
		for (Map.Entry<String, ProtoFile> packageAndProtoFile : packageToProtoFileMap.entrySet()) {
			ProtoFile file = packageAndProtoFile.getValue();
			for (Type t : file.types()) {
				if (t == parentMessageType) {
					return packageAndProtoFile.getKey();
				}
			}

		}
		return null;
	}

	private Location getLocation(XSComponent t) {
		Locator l = t.getLocator();
		try {
			URI absolute = URI.create(l.getSystemId()); // With scheme
			URI base = new URI("file", configuration.xsdFile.getAbsoluteFile().getParent(), null);
			URI relative = base.relativize(absolute);
			return new Location(base.toString(), relative.toString(), l.getLineNumber(), l.getColumnNumber());
		} catch (URISyntaxException e) {
			LOGGER.warn("Unable to relativise xsd file path: {}", e.getMessage());
			return new Location("", l.getSystemId(), l.getLineNumber(), l.getColumnNumber());
		}

	}

	private void processAttributes(XSAttContainer xsAttContainer, MessageType messageType, Set<Object> processedXmlObjects) {
		Iterator<? extends XSAttributeUse> iterator = xsAttContainer.iterateDeclaredAttributeUses();
		while (iterator.hasNext()) {
			XSAttributeUse attr = iterator.next();
			processAttribute(messageType, processedXmlObjects, attr);
		}

		Iterator<? extends XSAttGroupDecl> iterateAttGroups = xsAttContainer.iterateAttGroups();
		while (iterateAttGroups.hasNext()) {
			// Recursive
			processAttributes(iterateAttGroups.next(), messageType, processedXmlObjects);
		}

	}

	private void processAttribute(MessageType messageType, Set<Object> processedXmlObjects, XSAttributeUse attr) {
		if (!processedXmlObjects.contains(attr)) {
			processedXmlObjects.add(attr);

			XSAttributeDecl decl = attr.getDecl();
			XSSimpleType type = decl.getType();

			if (type.getPrimitiveType() != null || type.isList() || type.isUnion()) {
				String fieldName = decl.getName();
				String doc = resolveDocumentationAnnotation(decl);
				int tag = messageType.getNextFieldNum();
				Location fieldLocation = getLocation(decl);
				Options fieldOptions = getFieldOptions(decl);
				String packageName = NamespaceHelper.xmlNamespaceToProtoFieldPackagename(type.getTargetNamespace(), configuration.forceProtoPackage);
				Label label = type.isList() ? Label.REPEATED : null;

				if (type.isRestriction() && type.getFacet("enumeration") != null) {
					String enumName = createEnum(fieldName, type.asRestriction(), decl.isLocal() ? messageType : null);

					Field field = new Field(packageName, fieldLocation, label, fieldName, doc, tag, enumName, fieldOptions, false);
					field.setFromAttribute(true);
					addField(messageType, field);

				} else {
					String typeName = findFieldType(type);

					Field field = new Field(basicTypes.contains(typeName) ? null : packageName, fieldLocation, label, fieldName, doc, tag, typeName,
							fieldOptions, false);
					field.setFromAttribute(true);
					addField(messageType, field);

				}
			} else {
				LOGGER.error("Unhandled attribute use " + attr.getDecl().toString());
			}
		}
	}

	private XSModelGroup getModelGroup(XSTerm term) {
		XSModelGroupDecl modelGroupDecl = term.asModelGroupDecl();
		if (modelGroupDecl != null) {
			return modelGroupDecl.getModelGroup();
		} else if (term.isModelGroup()) {
			return term.asModelGroup();
		} else {
			return null;
		}
	}

	private String createWrapperName(MessageType enclosingType, XSModelGroup.Compositor compositor, String enclosingName, XSComplexType enclosingComplexType) {

		final String wrapperPrefix;
		if (XSModelGroup.SEQUENCE.equals(compositor)) {
			wrapperPrefix = "SequenceWrapper";
		} else if (XSModelGroup.CHOICE.equals(compositor)) {
			wrapperPrefix = "ChoiceWrapper";
		} else {
			throw new ConversionException("Cannot wrap message with compositor?" + compositor);
		}

		long numExistingWrappers = enclosingType.nestedTypes()
				.stream()
				.filter(e -> e instanceof MessageType)
				.map(e -> (MessageType) e)
				.filter(e -> e.getName().startsWith(wrapperPrefix))
				.count();
		String wrapperPostfix = (enclosingComplexType.isGlobal() ? enclosingComplexType.getName() : enclosingName);
		if (numExistingWrappers == 0) {
			return StringUtils.join(new Object[] { wrapperPrefix, StringUtils.capitalize(wrapperPostfix) }, "_");
		} else {
			return StringUtils.join(new Object[] { wrapperPrefix, StringUtils.capitalize(wrapperPostfix), (numExistingWrappers + 1) }, "_");
		}

	}

	private void groupProcessing(XSModelGroup modelGroup, XSParticle particle, MessageType messageType, Set<Object> processedXmlObjects, XSSchemaSet schemaSet,
			String enclosingName, String targetNamespace, XSComplexType enclosingType) {
		XSModelGroup.Compositor compositor = modelGroup.getCompositor();

		XSParticle[] children = modelGroup.getChildren();

		if (compositor.equals(XSModelGroup.ALL)) {
			processGroupAsSequence(particle, messageType, processedXmlObjects, schemaSet, children, enclosingName, targetNamespace, enclosingType);
		} else if (compositor.equals(XSModelGroup.SEQUENCE)) {
			boolean repeated = getRange(particle);
			if (repeated) {

				String typeName = createWrapperName(messageType, XSModelGroup.SEQUENCE, enclosingName, enclosingType);
				createWrapperAndContinueProcessing(modelGroup, particle, messageType, processedXmlObjects, schemaSet, children, typeName, targetNamespace,
						"sequenceWrapper", enclosingType);

			} else {
				processGroupAsSequence(particle, messageType, processedXmlObjects, schemaSet, children, enclosingName, targetNamespace, enclosingType);
			}

		} else if (compositor.equals(XSModelGroup.CHOICE)) {
			// Check if choice is unbounded, if so create repeated wrapper class and then continue
			boolean repeated = getRange(particle);
			if (repeated) {

				String typeName = createWrapperName(messageType, XSModelGroup.CHOICE, enclosingName, enclosingType);
				createWrapperAndContinueProcessing(modelGroup, particle, messageType, processedXmlObjects, schemaSet, children, typeName, targetNamespace,
						"choiceWrapper", enclosingType);

			} else {
				processGroupAsSequence(particle, messageType, processedXmlObjects, schemaSet, children, enclosingName, targetNamespace, enclosingType);
			}
		}
		messageType.advanceFieldNum();

	}

	private void createWrapperAndContinueProcessing(XSModelGroup modelGroup, XSParticle particle, MessageType messageType, Set<Object> processedXmlObjects,
			XSSchemaSet schemaSet, XSParticle[] children, String typeName, String targetNamespace, String fieldName, XSComplexType enclosingType) {

		if (!processedXmlObjects.contains(particle)) {
			processedXmlObjects.add(particle);

			String enclosingName = typeName;
			if (modelGroup.isModelGroupDecl()) {
				enclosingName = modelGroup.asModelGroupDecl().getName();
			}

			// Create new message type enclosed in existing

			String doc = resolveDocumentationAnnotation(modelGroup);
			Location location = getLocation(modelGroup);

			// Add message type to file
			List<OptionElement> messageOptions = new ArrayList<>();
			Options options = new Options(Options.MESSAGE_OPTIONS, messageOptions);

			MessageType wrapperType = new MessageType(ProtoType.get(typeName), location, doc, typeName, options);
			wrapperType.setWrapperMessageType(true);
			messageType.nestedTypes().add(wrapperType);

			Options fieldOptions = getFieldOptions(particle);

			String fieldPackagename = NamespaceHelper.xmlNamespaceToProtoFieldPackagename(targetNamespace, configuration.forceProtoPackage);

			Field field = new Field(fieldPackagename, location, Label.REPEATED, fieldName, doc, messageType.getNextFieldNum(), typeName, fieldOptions, false);

			messageType.addField(field);

			localTypes.add(new LocalType(particle, wrapperType, messageType, field,
					NamespaceHelper.xmlNamespaceToProtoPackage(targetNamespace, configuration.forceProtoPackage), enclosingType));

			processGroupAsSequence(particle, wrapperType, processedXmlObjects, schemaSet, children, enclosingName, targetNamespace, enclosingType);
		}
	}

	private void processGroupAsSequence(XSParticle particle, MessageType messageType, Set<Object> processedXmlObjects, XSSchemaSet schemaSet,
			XSParticle[] children, String enclosingName, String targetNamespace, XSComplexType enclosingType) {
		for (XSParticle child : children) {
			XSTerm currTerm = child.getTerm();
			if (currTerm.isModelGroup()) {
				if (child.asParticle() != null) {
					groupProcessing(currTerm.asModelGroup(), child, messageType, processedXmlObjects, schemaSet, enclosingName, targetNamespace, enclosingType);
				} else {
					groupProcessing(currTerm.asModelGroup(), particle, messageType, processedXmlObjects, schemaSet, enclosingName, targetNamespace,
							enclosingType);
				}
			} else {
				// Create the new complex type for root types
				navigateSubTypes(child, messageType, processedXmlObjects, schemaSet, enclosingName, targetNamespace, enclosingType);
			}
		}
	}

	private String resolveDocumentationAnnotation(XSComponent xsComponent) {
		String doc = "";
		if (xsComponent.getAnnotation() != null && xsComponent.getAnnotation().getAnnotation() != null) {
			if (xsComponent.getAnnotation().getAnnotation() instanceof Node) {
				Node annotationEl = (Node) xsComponent.getAnnotation().getAnnotation();
				NodeList annotations = annotationEl.getChildNodes();

				for (int i = 0; i < annotations.getLength(); i++) {
					Node annotation = annotations.item(i);
					if ("documentation".equals(annotation.getLocalName())) {

						NodeList childNodes = annotation.getChildNodes();
						for (int j = 0; j < childNodes.getLength(); j++) {
							if (childNodes.item(j) != null && childNodes.item(j) instanceof Text) {
								doc = childNodes.item(j).getNodeValue();
							}
						}
					}
				}
			}
		}

		String[] lines = doc.split("\n");
		StringBuilder b = new StringBuilder();
		for (String line : lines) {
			b.append(StringUtils.trimToEmpty(line));
			b.append(" ");
		}

		if (configuration.includeSourceLocationInDoc) {
			if (xsComponent != null && xsComponent.getLocator() != null) {
				Location loc = getLocation(xsComponent);
				b.append(" [");
				b.append(loc.withoutBase());
				b.append("]");
			}
		}
		return StringUtils.trimToEmpty(b.toString());
	}

	private String createEnum(String elementName, XSRestrictionSimpleType type, MessageType enclosingType) {
		Iterator<? extends XSFacet> it;

		String typeNameToUse;

		if (type.getName() != null) {
			typeNameToUse = type.getName();
			enclosingType = null;
		} else {

			String baseTypeName = type.getBaseType().getName();
			if (baseTypeName != null && !basicTypes.contains(baseTypeName)) {
				typeNameToUse = baseTypeName;
			} else if (enclosingType != null) {
				typeNameToUse = elementName + TYPE_SUFFIX;
			} else {
				typeNameToUse = elementName + GENERATED_NAME_PLACEHOLDER;
			}
		}

		Type protoType = getType(type.getTargetNamespace(), typeNameToUse);
		if (protoType == null) {

			type = type.asRestriction();

			Location location = getLocation(type);

			List<EnumConstant> constants = new ArrayList<>();
			it = type.getDeclaredFacets().iterator();

			int counter = 1;
			Set<String> addedValues = new HashSet<>();
			while (it.hasNext()) {
				List<OptionElement> optionElements = new ArrayList<>();
				XSFacet next = it.next();
				String doc = resolveDocumentationAnnotation(next);
				String enumValue = next.getValue().value;

				if (!addedValues.contains(enumValue)) {
					addedValues.add(enumValue);
					constants.add(new EnumConstant(location, enumValue, counter++, doc, new Options(Options.ENUM_VALUE_OPTIONS, optionElements)));
				}
			}

			List<OptionElement> enumOptionElements = new ArrayList<>();
			Options enumOptions = new Options(Options.ENUM_OPTIONS, enumOptionElements);

			String doc = resolveDocumentationAnnotation(type);

			ProtoType definedProtoType;
			if (enclosingType == null) {
				definedProtoType = ProtoType.get(typeNameToUse);
			} else {
				definedProtoType = ProtoType.get(enclosingType.getName(), typeNameToUse);
			}

			EnumType enumType = new EnumType(definedProtoType, location, doc, typeNameToUse, constants, enumOptions);

			if (enclosingType != null) {
				// if not already present
				boolean alreadyPresentAsNestedType = false;
				for (Type t : enclosingType.nestedTypes()) {
					if (t instanceof EnumType && ((EnumType) t).name().equals(typeNameToUse)) {
						alreadyPresentAsNestedType = true;
						break;
					}
				}
				if (!alreadyPresentAsNestedType) {
					enclosingType.nestedTypes().add(enumType);
				}
			} else {
				addType(type.getTargetNamespace(), enumType);
			}
		}
		return typeNameToUse;
	}

	@Override
	public void error(SAXParseException exception) {
		LOGGER.error(exception.getMessage() + " at " + exception.getSystemId());
		exception.printStackTrace();
	}

	@Override
	public void fatalError(SAXParseException exception) {
		LOGGER.error(exception.getMessage() + " at " + exception.getSystemId());
		exception.printStackTrace();
	}

	@Override
	public void warning(SAXParseException exception) {
		LOGGER.warn(exception.getMessage() + " at " + exception.getSystemId());
		exception.printStackTrace();
	}

	public List<LocalType> getLocalTypes() {
		return localTypes;
	}
}
