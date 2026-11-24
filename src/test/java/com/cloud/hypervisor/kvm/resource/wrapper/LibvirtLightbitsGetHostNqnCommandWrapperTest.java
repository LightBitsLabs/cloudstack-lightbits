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
import java.util.List;

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

    @Test
    void reportsTheSourceAddressTowardEachEndpointOnce() throws IOException {
        LibvirtLightbitsGetHostNqnCommandWrapper w = new LibvirtLightbitsGetHostNqnCommandWrapper() {
            @Override
            protected Path hostNqnPath() {
                return tmp.resolve("hostnqn");
            }

            @Override
            protected String resolve(String host) throws java.net.UnknownHostException {
                if ("nohost".equals(host)) {
                    throw new java.net.UnknownHostException(host);
                }
                return host;
            }

            @Override
            protected String routeSource(String address) {
                return address.startsWith("10.19.") ? "10.19.31.17" : address.startsWith("fd00") ? "fd00::17" : null;
            }
        };
        Files.writeString(tmp.resolve("hostnqn"), NQN);
        Answer answer = w.execute(new LightbitsGetHostNqnCommand(
                List.of("10.19.31.20:8009", "10.19.31.23:8009", "[fd00::1]:8009", "nohost:8009", "192.0.2.1:8009")), null);
        assertTrue(answer.getResult());
        assertEquals(List.of("10.19.31.17", "fd00::17"), ((LightbitsGetHostNqnAnswer) answer).getDataIps());
    }

    @Test
    void withoutEndpointsTheAnswerCarriesNoIps() throws IOException {
        LibvirtLightbitsGetHostNqnCommandWrapper w = wrapper(null);
        Files.createDirectories(w.hostNqnPath().getParent());
        Files.writeString(w.hostNqnPath(), NQN);
        assertEquals(List.of(), execute(w).getDataIps());
    }

    @Test
    void endpointHostsAndRouteSourcesAreParsed() {
        assertEquals("10.0.0.1", LibvirtLightbitsGetHostNqnCommandWrapper.hostOf("10.0.0.1:8009"));
        assertEquals("fd00::1", LibvirtLightbitsGetHostNqnCommandWrapper.hostOf("[fd00::1]:8009"));
        assertEquals("fd00::1", LibvirtLightbitsGetHostNqnCommandWrapper.hostOf("fd00::1"));
        assertEquals("node1.lab", LibvirtLightbitsGetHostNqnCommandWrapper.hostOf("node1.lab"));
        assertEquals("10.19.31.17", LibvirtLightbitsGetHostNqnCommandWrapper.parseRouteSource(
                "10.19.31.20 dev ens785f0np0 src 10.19.31.17 uid 0 \\    cache "));
        assertEquals(null, LibvirtLightbitsGetHostNqnCommandWrapper.parseRouteSource("RTNETLINK answers: Network is unreachable"));
    }

    // ---- in-band authentication ----

    private LibvirtLightbitsGetHostNqnCommandWrapper authWrapper(java.util.function.Supplier<String> key) throws IOException {
        LibvirtLightbitsGetHostNqnCommandWrapper base = wrapper(null);
        Files.createDirectories(base.hostNqnPath().getParent());
        Files.writeString(base.hostNqnPath(), NQN + "\n");
        return new LibvirtLightbitsGetHostNqnCommandWrapper() {
            @Override
            protected Path hostNqnPath() {
                return base.hostNqnPath();
            }

            @Override
            protected com.cloud.hypervisor.kvm.storage.LightbitsHostKey hostKey() {
                return new com.cloud.hypervisor.kvm.storage.LightbitsHostKey() {
                    @Override
                    public String ensure() {
                        return key.get();
                    }
                };
            }

            @Override
            protected int keylessPaths() {
                return 2;
            }
        };
    }

    @Test
    void theKeyIsReportedOnlyWhenAskedForAndNeverLogged() throws IOException {
        String secret = "DHHC-1:00:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh+KfiaR:";
        LibvirtLightbitsGetHostNqnCommandWrapper w = authWrapper(() -> secret);

        LightbitsGetHostNqnAnswer plain = (LightbitsGetHostNqnAnswer) w.execute(new LightbitsGetHostNqnCommand(), null);
        assertNull(plain.getDhchapKey());
        assertNull(plain.getKeylessPaths());

        LightbitsGetHostNqnAnswer a = (LightbitsGetHostNqnAnswer) w.execute(new LightbitsGetHostNqnCommand(null, true), null);
        assertTrue(a.getResult());
        assertEquals(NQN, a.getHostNqn());
        assertEquals(secret, a.getDhchapKey());
        assertEquals(Integer.valueOf(2), a.getKeylessPaths());
        assertNull(a.getInbandAuthError());

        // CloudStack logs commands and answers through this exclusion strategy, at debug level
        // or finer (below that it leaves the whole answer out); even at trace the key stays out
        org.apache.logging.log4j.core.config.Configurator.setLevel("lightbits.test.commands", org.apache.logging.log4j.Level.TRACE);
        String logged = new com.google.gson.GsonBuilder()
                .setExclusionStrategies(new com.cloud.agent.transport.LoggingExclusionStrategy(
                        org.apache.logging.log4j.LogManager.getLogger("lightbits.test.commands")))
                .create().toJson(a);
        assertTrue(logged.contains(NQN), logged);
        assertTrue(!logged.contains("DHHC-1"), "the key is left out of the logged answer: " + logged);
    }

    @Test
    void aKeyThatCannotBePreparedIsReportedButTheNqnStillAnswers() throws IOException {
        LibvirtLightbitsGetHostNqnCommandWrapper w = authWrapper(() -> {
            throw new com.cloud.utils.exception.CloudRuntimeException("disk full");
        });
        LightbitsGetHostNqnAnswer a = (LightbitsGetHostNqnAnswer) w.execute(new LightbitsGetHostNqnCommand(null, true), null);
        assertTrue(a.getResult());
        assertEquals(NQN, a.getHostNqn());
        assertNull(a.getDhchapKey());
        assertEquals("disk full", a.getInbandAuthError());
    }
}
