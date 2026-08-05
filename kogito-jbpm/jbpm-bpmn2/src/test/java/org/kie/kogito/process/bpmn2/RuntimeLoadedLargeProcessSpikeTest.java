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
package org.kie.kogito.process.bpmn2;

import java.io.File;

import org.drools.io.FileSystemResource;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.kie.kogito.Application;
import org.kie.kogito.Model;
import org.kie.kogito.internal.process.runtime.KogitoProcessInstance;
import org.kie.kogito.process.Process;
import org.kie.kogito.process.ProcessInstance;
import org.kie.kogito.process.Processes;
import org.kie.kogito.process.impl.StaticProcessConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Engineering spike for apache/incubator-kie-issues#2229 (evidence-gathering only, no production intent):
 * can a BPMN process that would overflow the JVM's 64KB {@code process()} method limit under the normal
 * AOT codegen path (ProcessCodegen -> ProcessToExecModelGenerator -> ProcessVisitor -> javac) instead be
 * loaded and *executed* at runtime via XmlProcessReader/BpmnProcess/StaticApplicationAssembler, with zero
 * Java source generated for the process at all?
 * <p>
 * This drives the same 754-task reproducer used to prove the codegen-side fix
 * (apache/incubator-kie-issues#2229's minimal repro), but through a completely different path: no
 * {@code ProcessToExecModelGenerator.generate()} call anywhere in this test, no {@code javac} invocation,
 * no generated {@code .java} file at all. "Code too large" is indeed structurally impossible via this
 * path - there is no generated method for the JVM's 64KB limit to apply to - but this test currently
 * FAILS, and that failure is itself the spike's key finding, not a bug to fix here.
 * <p>
 * {@code StaticApplicationAssembler} calls bare {@code XmlProcessReader.read(...)}, which parses BPMN
 * structure but never resolves embedded script actions into executable form - every action node ends up
 * with a null {@link org.jbpm.process.instance.impl.Action}, confirmed via the {@code processInstance.error()}
 * NullPointerException at the first script task. The class that actually resolves script/MVEL dialects into
 * executable actions, {@link org.jbpm.compiler.ProcessBuilderImpl}, requires a constructed
 * {@code org.drools.compiler.builder.impl.KnowledgeBuilderImpl} - the same heavyweight rule-compilation
 * object used to build an entire KIE module's worth of DRL. Wiring that in to make one runtime-loaded
 * process actually executable (essentially all real BPMN processes have script actions) means carrying
 * the full Drools KnowledgeBuilder/dialect-compilation stack as a runtime dependency - the exact thing
 * AOT compilation exists to keep off the runtime classpath, and directly opposed to native-image-friendly
 * design. See the accompanying technical assessment for the full evidence trail.
 */
public class RuntimeLoadedLargeProcessSpikeTest {

    private static final String REPRO_FAILS_BPMN =
            "/Users/alishamohamedali/Desktop/BAMOE/BUILD-CHAIN/CODE-TOO-LARGE-REPRO/repro-fails.bpmn";

    @Disabled("Spike evidence, not a bug to fix here - see class javadoc: script actions are never "
            + "resolved by bare XmlProcessReader, and the real fix (ProcessBuilderImpl) requires a full "
            + "KnowledgeBuilderImpl. Left failing and disabled intentionally as the recorded finding.")
    @Test
    void largeProcessExecutesViaRuntimeLoadingWithNoGeneratedJava() {
        File bpmnFile = new File(REPRO_FAILS_BPMN);
        assertThat(bpmnFile).exists();

        StaticProcessConfig processConfig = StaticProcessConfig.newStaticProcessConfigBuilder().build();

        // the only "build step" this process goes through: XML parsing, inside newStaticApplication.
        // No ProcessToExecModelGenerator, no ProcessVisitor, no javac. processInstancesFactory left
        // null deliberately - AbstractProcess.configure() falls back to an in-memory MapProcessInstances.
        Application application = StaticApplicationAssembler.instance()
                .newStaticApplication(null, processConfig, new FileSystemResource(bpmnFile));

        Processes container = application.get(Processes.class);
        assertThat(container).isNotNull();

        Process<? extends Model> processDefinition = container.processById("codeTooLargeRepro");
        assertThat(processDefinition)
                .as("BpmnProcess wrapper should be registered under the process's BPMN id, with no generated class involved")
                .isNotNull();

        ProcessInstance<? extends Model> processInstance = processDefinition.createInstance(processDefinition.createModel());
        processInstance.start();

        processInstance.error().ifPresent(e -> {
            System.out.println("SPIKE_ERROR nodeId=" + e.failedNodeId());
            System.out.println("SPIKE_ERROR message=" + e.errorMessage());
        });

        assertThat(processInstance.status())
                .as("all 754 sequential script tasks should have executed to completion")
                .isEqualTo(KogitoProcessInstance.STATE_COMPLETED);
    }
}
