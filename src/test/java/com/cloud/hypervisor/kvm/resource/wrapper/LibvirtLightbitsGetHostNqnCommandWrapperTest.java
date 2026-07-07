/*
 * Copyright 2026 Lightbits Labs Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloud.hypervisor.kvm.resource.wrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.cloudstack.storage.datastore.command.LightbitsGetHostNqnAnswer;
import org.apache.cloudstack.storage.datastore.command.LightbitsGetHostNqnCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.cloud.agent.api.Answer;

class LibvirtLightbitsGetHostNqnCommandWrapperTest {

    private static final String NQN = "nqn.2014-08.org.nvmexpress:uuid:11111111-2222-3333-4444-555555555555";

    @TempDir
    Path tmp;

    /** Wrapper with the file path and nvme-cli seams under test control. */
    private LibvirtLightbitsGetHostNqnCommandWrapper wrapper(String showHostNqnOutput) {
        return new LibvirtLightbitsGetHostNqnCommandWrapper() {
            @Override
            protected Path hostNqnPath() {
                return tmp.resolve("nvme").resolve("hostnqn");
            }

            @Override
            protected String runShowHostNqn() {
                return showHostNqnOutput;
            }
        };
    }

    private LightbitsGetHostNqnAnswer execute(LibvirtLightbitsGetHostNqnCommandWrapper w) {
        Answer answer = w.execute(new LightbitsGetHostNqnCommand(), null);
        assertTrue(answer instanceof LightbitsGetHostNqnAnswer);
        return (LightbitsGetHostNqnAnswer) answer;
    }

    @Test
    void readsExistingHostnqnFileTrimmed() throws IOException {
        LibvirtLightbitsGetHostNqnCommandWrapper w = wrapper(null);
        Files.createDirectories(w.hostNqnPath().getParent());
        Files.writeString(w.hostNqnPath(), NQN + "\n");

        LightbitsGetHostNqnAnswer answer = execute(w);
        assertTrue(answer.getResult());
        assertEquals(NQN, answer.getHostNqn());
    }

    @Test
    void generatesAndPinsWhenFileMissing() throws IOException {
        LibvirtLightbitsGetHostNqnCommandWrapper w = wrapper(NQN + "\n");

        LightbitsGetHostNqnAnswer answer = execute(w);
        assertTrue(answer.getResult());
        assertEquals(NQN, answer.getHostNqn());
        // pinned for future connects (ours or manual nvme-cli)
        assertEquals(NQN, Files.readString(w.hostNqnPath()).trim());
    }

    @Test
    void failsWhenFileMissingAndNvmeCliAbsent() {
        LightbitsGetHostNqnAnswer answer = execute(wrapper(null));
        assertFalse(answer.getResult());
        assertNull(answer.getHostNqn());
        assertTrue(answer.getDetails().contains("nvme-cli"));
    }

    @Test
    void rejectsGarbageNqn() {
        LightbitsGetHostNqnAnswer answer = execute(wrapper("command not found"));
        assertFalse(answer.getResult());
        assertNull(answer.getHostNqn());
        assertTrue(answer.getDetails().contains("unexpected host NQN"));
    }

    @Test
    void emptyFileFallsBackToGeneration() throws IOException {
        LibvirtLightbitsGetHostNqnCommandWrapper w = wrapper(NQN);
        Files.createDirectories(w.hostNqnPath().getParent());
        Files.writeString(w.hostNqnPath(), "  \n");

        LightbitsGetHostNqnAnswer answer = execute(w);
        assertTrue(answer.getResult());
        assertEquals(NQN, answer.getHostNqn());
    }
}
