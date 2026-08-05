/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.kie.kogito.codegen.process;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

import org.drools.io.FileSystemResource;
import org.jbpm.compiler.canonical.ProcessMetaData;
import org.jbpm.compiler.canonical.ProcessToExecModelGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kie.api.definition.process.Process;
import org.kie.api.definition.process.WorkflowProcess;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the two BPMN files from apache/incubator-kie-issues#2229's minimal reproducer (754 sequential
 * script tasks, and the 753-task negative control one below the JVM's 64KB per-method threshold) through
 * the real production entry point - {@link ProcessCodegen#parseProcessFile(org.drools.io.Resource)}, i.e.
 * actual BPMN2 XML parsing via {@code XmlProcessReader}, not a hand-built process - then compiles the
 * generated class with the real {@code javac} compiler.
 * <p>
 * Complements {@link ProcessGeneratorCodeSizeTest}, which covers the same threshold but builds the process
 * programmatically via {@link org.jbpm.ruleflow.core.RuleFlowProcessFactory} and so never exercises XML
 * parsing. Together they cover both entry points production code actually uses.
 */
public class ProcessCodeTooLargeReproducerTest {

    private static final String REPRO_FAILS_BPMN = "/codetoolarge/repro-fails.bpmn";
    private static final String REPRO_CONTROL_PASSES_BPMN = "/codetoolarge/repro-control-passes.bpmn";

    @Test
    void reproducerAt754TasksCompilesWithoutCodeTooLarge(@TempDir Path tempDir) throws IOException, InterruptedException {
        assertCompilesWithoutCodeTooLarge(REPRO_FAILS_BPMN, tempDir);
    }

    @Test
    void negativeControlAt753TasksStillCompiles(@TempDir Path tempDir) throws IOException, InterruptedException {
        assertCompilesWithoutCodeTooLarge(REPRO_CONTROL_PASSES_BPMN, tempDir);
    }

    private void assertCompilesWithoutCodeTooLarge(String resourcePath, Path tempDir) throws IOException, InterruptedException {
        WorkflowProcess process = parseProcess(resourcePath);

        ProcessMetaData metadata = ProcessToExecModelGenerator.INSTANCE.generate(process);
        CompilationUnit generatedClassModel = metadata.getGeneratedClassModel();
        String className = generatedClassModel.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow()
                .getNameAsString();

        File sourceFile = writeToDefaultPackageSourceFile(tempDir, className, generatedClassModel.toString());
        String javacOutput = compileWithJavac(sourceFile, tempDir);

        assertThat(javacOutput).doesNotContain("code too large");
    }

    private WorkflowProcess parseProcess(String resourcePath) throws IOException {
        File file = new File(getClass().getResource(resourcePath).getFile());
        Collection<Process> processes = ProcessCodegen.parseProcessFile(new FileSystemResource(file));
        assertThat(processes).hasSize(1);
        return (WorkflowProcess) processes.iterator().next();
    }

    private File writeToDefaultPackageSourceFile(Path tempDir, String className, String source) throws IOException {
        String withoutPackageDeclaration = source.replaceFirst("(?m)^package .*;\\n", "");
        File sourceFile = tempDir.resolve(className + ".java").toFile();
        try (FileWriter writer = new FileWriter(sourceFile)) {
            writer.write(withoutPackageDeclaration);
        }
        return sourceFile;
    }

    private String compileWithJavac(File sourceFile, Path tempDir) throws IOException, InterruptedException {
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder processBuilder = new ProcessBuilder(
                "javac", "-d", tempDir.toString(), "-cp", classpath, sourceFile.getAbsolutePath());
        processBuilder.redirectErrorStream(true);
        java.lang.Process javac = processBuilder.start();
        String output = new String(javac.getInputStream().readAllBytes());
        javac.waitFor();
        return output;
    }
}
