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

import org.jbpm.compiler.canonical.ProcessMetaData;
import org.jbpm.compiler.canonical.ProcessToExecModelGenerator;
import org.jbpm.ruleflow.core.RuleFlowProcessFactory;
import org.jbpm.ruleflow.core.WorkflowElementIdentifierFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kie.api.definition.process.WorkflowElementIdentifier;
import org.kie.api.definition.process.WorkflowProcess;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the "code too large" failure (apache/incubator-kie-issues#2229, third bullet):
 * a single BPMN process with enough nodes generates a {@code process()} method whose bytecode exceeds
 * the JVM's 64KB per-method limit.
 * <p>
 * 754 sequential trivial script tasks is the exact, previously verified threshold at which the old
 * single-method {@code process()} overflowed 64KB of bytecode (753 tasks compiled clean). This drives
 * {@link ProcessToExecModelGenerator} - the class whose output {@link ProcessGenerator} later transplants
 * verbatim into the customer-facing wrapper, see {@link ProcessGeneratorTest} for that transplant - and
 * compiles the result with the real {@code javac} compiler, the same bytecode emitter production
 * compilation goes through, to confirm the phase-split in {@code ProcessVisitor} actually keeps every
 * generated method under the limit rather than just moving the failure around.
 */
public class ProcessGeneratorCodeSizeTest {

    private static final int TASK_COUNT_KNOWN_TO_OVERFLOW_SINGLE_METHOD = 754;

    @Test
    void largeFlatProcessCompilesWithoutCodeTooLarge(@TempDir Path tempDir) throws IOException, InterruptedException {
        WorkflowProcess process = buildSequentialScriptTaskProcess(TASK_COUNT_KNOWN_TO_OVERFLOW_SINGLE_METHOD);

        ProcessMetaData metadata = ProcessToExecModelGenerator.INSTANCE.generate(process);
        CompilationUnit generatedClassModel = metadata.getGeneratedClassModel();
        String className = generatedClassModel.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow()
                .getNameAsString();

        File sourceFile = writeToDefaultPackageSourceFile(tempDir, className, generatedClassModel.toString());
        String javacOutput = compileWithJavac(sourceFile, tempDir);

        assertThat(javacOutput).doesNotContain("code too large");
    }

    private WorkflowProcess buildSequentialScriptTaskProcess(int taskCount) {
        RuleFlowProcessFactory factory = RuleFlowProcessFactory.createProcess("codesize.big");
        factory.name("big").packageName("codesize").dynamic(false).version("1.0");

        WorkflowElementIdentifier start = WorkflowElementIdentifierFactory.fromExternalFormat("start");
        factory.startNode(start).name("start").done();

        WorkflowElementIdentifier previous = start;
        for (int i = 0; i < taskCount; i++) {
            WorkflowElementIdentifier taskId = WorkflowElementIdentifierFactory.fromExternalFormat("task" + i);
            factory.actionNode(taskId).name("task" + i).action("java", "System.out.println(\"step " + i + "\");").done();
            factory.connection(previous, taskId);
            previous = taskId;
        }

        WorkflowElementIdentifier end = WorkflowElementIdentifierFactory.fromExternalFormat("end");
        factory.endNode(end).name("end").terminate(false).done();
        factory.connection(previous, end);

        return factory.validate().getProcess();
    }

    private File writeToDefaultPackageSourceFile(Path tempDir, String className, String source) throws IOException {
        // compiled standalone in the default package, deliberately independent of the source's own
        // package declaration, so this test needs nothing beyond the generated file itself
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
