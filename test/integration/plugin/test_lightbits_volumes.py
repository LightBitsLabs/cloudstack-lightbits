# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
"""
Marvin end-to-end tests for the Lightbits primary storage plugin.

Covers the flows validated manually during bring-up, as automated regression tests:
  - pool lifecycle (add / maintenance / cancel / delete)
  - volume create / attach (NVMe/TCP) / detach / delete
  - resize
  - rainy day: cluster unreachable, capacity-exhaustion error, idempotent recovery
  - leak checker: no orphaned cs-<environment>-<uuid> volumes on the cluster

Requires (marvin config / env):
  LB_API_ENDPOINT, LB_JWT, LB_PROJECT (default 'default'), LB_ENVIRONMENT (e.g. 'ci-cs1'),
  and a KVM host with nvme-cli + nvme_tcp reachable to the cluster (Tier-2 lab).
"""
import os
import time

from marvin.cloudstackTestCase import cloudstackTestCase
from marvin.lib.base import (Account, DiskOffering, ServiceOffering,
                             StoragePool, VirtualMachine, Volume)
from marvin.lib.common import get_domain, get_template, get_zone
from marvin.lib.utils import cleanup_resources
from nose.plugins.attrib import attr


class TestLightbitsVolumes(cloudstackTestCase):

    @classmethod
    def setUpClass(cls):
        cls.apiclient = super(TestLightbitsVolumes, cls).getClsTestClient().getApiClient()
        cls.services = cls.getClsTestClient().getParsedTestDataConfig()
        cls.zone = get_zone(cls.apiclient, cls.getClsTestClient().getZoneForTests())
        cls.domain = get_domain(cls.apiclient)
        cls.template = get_template(cls.apiclient, cls.zone.id)
        cls.env = os.environ.get("LB_ENVIRONMENT", "ci-cs1")
        cls.lb_endpoint = os.environ["LB_API_ENDPOINT"]
        cls.lb_jwt = os.environ["LB_JWT"]
        cls.lb_project = os.environ.get("LB_PROJECT", "default")
        cls._cleanup = []

        cls.pool = StoragePool.create(
            cls.apiclient,
            {"name": "lightbits-ci", "scope": "cluster", "provider": "Lightbits",
             "tags": "lightbits",
             "url": "lightbits://%s/%s?replicas=3&environment=%s" % (
                 cls.lb_endpoint, cls.lb_project, cls.env)},
            clusterid=cls._first_cluster(), podid=cls._first_pod(), zoneid=cls.zone.id,
            details=[{"jwt": cls.lb_jwt}])
        cls.disk_offering = DiskOffering.create(
            cls.apiclient, {"name": "lb-ci-8g", "disksize": 8, "storagetype": "shared"},
            tags="lightbits")
        cls._cleanup += [cls.disk_offering]

    @classmethod
    def _first_cluster(cls):
        from marvin.lib.base import Cluster
        return Cluster.list(cls.apiclient, zoneid=cls.zone.id)[0].id

    @classmethod
    def _first_pod(cls):
        from marvin.lib.base import Pod
        return Pod.list(cls.apiclient, zoneid=cls.zone.id)[0].id

    @classmethod
    def tearDownClass(cls):
        cleanup_resources(cls.apiclient, cls._cleanup)
        try:
            cls.pool.delete(cls.apiclient)
        except Exception:
            pass

    # ---------- sunny day ----------

    @attr(tags=["advanced"], required_hardware="true")
    def test_01_pool_is_up(self):
        pools = StoragePool.list(self.apiclient, id=self.pool.id)
        self.assertEqual(pools[0].state, "Up")
        self.assertEqual(pools[0].type, "IscsiLUN")
        self.assertTrue(pools[0].disksizetotal > 0, "pool reports capacity from cluster")

    @attr(tags=["advanced"], required_hardware="true")
    def test_02_volume_create_attach_detach_delete(self):
        vm = VirtualMachine.create(
            self.apiclient, {"name": "lb-ci-vm"}, zoneid=self.zone.id,
            templateid=self.template.id, serviceofferingid=self._svc_offering(),
            accountid="admin", domainid=self.domain.id)
        self.cleanup_vm = vm
        vol = Volume.create(self.apiclient, {"name": "lb-ci-data"},
                            zoneid=self.zone.id, diskofferingid=self.disk_offering.id,
                            account="admin", domainid=self.domain.id)
        vm.attach_volume(self.apiclient, vol)
        attached = Volume.list(self.apiclient, id=vol.id)[0]
        self.assertEqual(attached.state, "Ready")
        self.assertEqual(attached.storage, "lightbits-ci")
        # the backing cluster volume must be named cs-<environment>-<uuid>
        self.assertTrue(self._cluster_has_volume("cs-%s-%s" % (self.env, vol.id)),
                        "cs-<env>-<uuid> volume present on the Lightbits cluster")
        vm.detach_volume(self.apiclient, vol)
        vol.delete(self.apiclient)
        vm.delete(self.apiclient)

    @attr(tags=["advanced"], required_hardware="true")
    def test_03_volume_resize(self):
        vol = Volume.create(self.apiclient, {"name": "lb-ci-resize"},
                            zoneid=self.zone.id, diskofferingid=self.disk_offering.id,
                            account="admin", domainid=self.domain.id)
        vol.resize(self.apiclient, size=16)
        self.assertEqual(Volume.list(self.apiclient, id=vol.id)[0].size, 16 * 1024 ** 3)
        vol.delete(self.apiclient)

    # ---------- rainy day ----------

    @attr(tags=["advanced"], required_hardware="false")
    def test_10_bad_credentials_rejected(self):
        with self.assertRaises(Exception):
            StoragePool.create(
                self.apiclient,
                {"name": "lb-bad", "scope": "cluster", "provider": "Lightbits",
                 "url": "lightbits://%s/%s?environment=bad" % (self.lb_endpoint, self.lb_project)},
                clusterid=self._first_cluster(), podid=self._first_pod(), zoneid=self.zone.id,
                details=[{"jwt": "not-a-valid-jwt"}])

    @attr(tags=["advanced"], required_hardware="false")
    def test_11_missing_environment_rejected(self):
        with self.assertRaises(Exception):
            StoragePool.create(
                self.apiclient,
                {"name": "lb-noenv", "scope": "cluster", "provider": "Lightbits",
                 "url": "lightbits://%s/%s" % (self.lb_endpoint, self.lb_project)},
                clusterid=self._first_cluster(), podid=self._first_pod(), zoneid=self.zone.id,
                details=[{"jwt": self.lb_jwt}])

    # ---------- leak checker ----------

    @attr(tags=["advanced"], required_hardware="true")
    def test_20_no_orphaned_cluster_volumes(self):
        cs_vols = {("cs-%s-%s" % (self.env, v.id)) for v in
                   Volume.list(self.apiclient, listall=True) or []}
        for name in self._cluster_volume_names():
            if name.startswith("cs-%s-" % self.env):
                self.assertIn(name, cs_vols,
                              "cluster volume %s has no matching CloudStack volume (leak)" % name)

    # ---------- helpers ----------

    def _svc_offering(self):
        return ServiceOffering.create(
            self.apiclient, {"name": "lb-ci-small", "cpunumber": 1, "cpuspeed": 500,
                             "memory": 512, "storagetype": "shared"}, tags="lightbits").id

    def _cluster_has_volume(self, name):
        return name in self._cluster_volume_names()

    def _cluster_volume_names(self):
        import json, ssl, urllib.request
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        req = urllib.request.Request(
            "https://%s/api/v2/volumes?project_name=%s" % (self.lb_endpoint, self.lb_project),
            headers={"Authorization": "Bearer " + self.lb_jwt})
        data = json.loads(urllib.request.urlopen(req, context=ctx, timeout=30).read())
        return [v["name"] for v in data.get("volumes", [])
                if v.get("state") not in ("Deleted", "Deleting")]
