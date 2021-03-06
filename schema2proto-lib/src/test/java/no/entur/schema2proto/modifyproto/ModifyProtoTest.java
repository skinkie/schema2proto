package no.entur.schema2proto.modifyproto;

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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import no.entur.schema2proto.AbstractMappingTest;
import no.entur.schema2proto.modifyproto.config.MergeFrom;
import no.entur.schema2proto.modifyproto.config.NewField;

public class ModifyProtoTest extends AbstractMappingTest {

	@Test
	public void testRemoveIndependentMessageType() throws IOException, InvalidProtobufException {

		File expected = new File("src/test/resources/modify/expected/nopackagename").getCanonicalFile();
		File source = new File("src/test/resources/modify/input/nopackagename").getCanonicalFile();

		List<String> excludes = new ArrayList<>();
		excludes.add("A");

		modifyProto(source, null, excludes, null, null, null, false);

		compareExpectedAndGenerated(expected, "missing_a.proto", generatedRootFolder, "simple.proto");

	}

	@Test
	public void testWhitelistMessageType() throws IOException, InvalidProtobufException {

		File expected = new File("src/test/resources/modify/expected/nopackagename").getCanonicalFile();
		File source = new File("src/test/resources/modify/input/nopackagename").getCanonicalFile();

		List<String> includes = new ArrayList<>();
		includes.add("A");
		modifyProto(source, includes, null, null, null, null, false);

		compareExpectedAndGenerated(expected, "only_a.proto", generatedRootFolder, "simple.proto");

	}

	@Test
	public void testWhitelistMessageTypeWithIncludeBaseTypeOptionEnabled() throws IOException, InvalidProtobufException {

		File expected = new File("src/test/resources/modify/expected/xsdbasetype").getCanonicalFile();
		File source = new File("src/test/resources/modify/input/xsdbasetype").getCanonicalFile();

		List<String> includes = new ArrayList<>();
		includes.add("B");
		modifyProto(source, includes, null, null, null, null, true);

		compareExpectedAndGenerated(expected, "enabled.proto", generatedRootFolder, "simple.proto");

	}

	@Test
	public void testWhitelistMessageTypeWithIncludeBaseTypeOptionDisabled() throws IOException, InvalidProtobufException {

		File expected = new File("src/test/resources/modify/expected/xsdbasetype").getCanonicalFile();
		File source = new File("src/test/resources/modify/input/xsdbasetype").getCanonicalFile();

		List<String> includes = new ArrayList<>();
		includes.add("B");
		modifyProto(source, includes, null, null, null, null, false);

		compareExpectedAndGenerated(expected, "disabled.proto", generatedRootFolder, "simple.proto");

	}

	@Test
	public void testRemoveFieldAndType() throws IOException, InvalidProtobufException {

		File expected = new File("src/test/resources/modify/expected/nopackagename").getCanonicalFile();
		File source = new File("src/test/resources/modify/input/nopackagename").getCanonicalFile();

		List<String> excludes = new ArrayList<>();
		excludes.add("LangType");
		modifyProto(source, null, excludes, null, null, null, false);
		compareExpectedAndGenerated(expected, "no_langtype.proto", generatedRootFolder, "simple.proto");
	}

	@Test
	public void removeEmptyFilesAndImports() throws IOException, InvalidProtobufException {
		File expected = new File("src/test/resources/modify/expected/emptyfile").getCanonicalFile();
		File source = new File("src/test/resources/modify/input/emptyfile").getCanonicalFile();

		List<String> excludes = new ArrayList<>();
		excludes.add("package.ExcludeMessage");
		excludes.add("package.ExcludeType");
		modifyProto(source, null, excludes, null, null, null, false);
		compareExpectedAndGenerated(expected, "package/no_exclusions.proto", generatedRootFolder, "package/importsexcluded.proto");

	}

	@Test
	public void testWhitelistMessageTypeAndDependency() throws IOException, InvalidProtobufException {

		File expected = new File("src/test/resources/modify/expected/nopackagename").getCanonicalFile();
		File source = new File("src/test/resources/modify/input/nopackagename").getCanonicalFile();

		List<String> includes = new ArrayList<>();
		includes.add("B");
		modifyProto(source, includes, null, null, null, null, false);

		compareExpectedAndGenerated(expected, "missing_a.proto", generatedRootFolder, "simple.proto");

	}

	@Test
	public void testInsidePackage() throws IOException, InvalidProtobufException {

		File expected = new File("src/test/resources/modify/expected/withpackagename").getCanonicalFile();
		File source = new File("src/test/resources/modify/input/withpackagename").getCanonicalFile();

		List<String> includes = new ArrayList<>();
		includes.add("package.B");
		modifyProto(source, includes, null, null, null, null, false);

		compareExpectedAndGenerated(expected, "package/insidepackage.proto", generatedRootFolder, "package/simple.proto");

	}

	@Test
	public void testAddField() throws IOException, InvalidProtobufException {

		File expected = new File("src/test/resources/modify/expected/nopackagename").getCanonicalFile();
		File source = new File("src/test/resources/modify/input/nopackagename").getCanonicalFile();

		List<NewField> newFields = new ArrayList<>();

		NewField newField = new NewField();
		newField.targetMessageType = "A";
		// newField.importProto = "importpackage/p.proto";
		newField.label = "repeated";
		newField.fieldNumber = 100;
		newField.name = "new_field";
		newField.type = "B";
		newFields.add(newField);

		modifyProto(source, null, null, newFields, null, null, false);

		compareExpectedAndGenerated(expected, "extrafield.proto", generatedRootFolder, "simple.proto");

	}

	@Test
	public void testAddEnumValue() throws IOException, InvalidProtobufException {

		File expected = new File("src/test/resources/modify/expected/nopackagename").getCanonicalFile();
		File source = new File("src/test/resources/modify/input/nopackagename").getCanonicalFile();

		List<NewField> newFields = new ArrayList<>();

		NewField newField = new NewField();
		newField.targetMessageType = "A";
		// newField.importProto = "importpackage/p.proto";
		newField.label = "repeated";
		newField.fieldNumber = 100;
		newField.name = "new_field";
		newField.type = "B";
		newFields.add(newField);

		List<MergeFrom> mergeFrom = new ArrayList<>();
		modifyProto(source, null, null, newFields, null, null, false);

		compareExpectedAndGenerated(expected, "extrafield.proto", generatedRootFolder, "simple.proto");

	}

	@Test
	public void testMergeProto() throws IOException, InvalidProtobufException {

		File expected = new File("src/test/resources/modify/expected/nopackagename").getCanonicalFile();
		File source = new File("src/test/resources/modify/input/nopackagename").getCanonicalFile();
		File mergefrom = new File("src/test/resources/modify/mergefrom/nopackagename").getCanonicalFile();

		List<MergeFrom> mergeFrom = new ArrayList<>();
		MergeFrom m = new MergeFrom();
		m.sourceFolder = mergefrom;
		m.protoFile = "mergefrom.proto";

		mergeFrom.add(m);
		modifyProto(source, null, null, null, mergeFrom, null, false);

		compareExpectedAndGenerated(expected, "mergefrom.proto", generatedRootFolder, "simple.proto");

	}

}
