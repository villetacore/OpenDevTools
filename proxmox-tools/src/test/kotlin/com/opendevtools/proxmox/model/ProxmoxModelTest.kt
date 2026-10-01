package com.opendevtools.proxmox.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProxmoxTaskTest {

    private val upid = "UPID:pve1:0000A1B2:0034C5D6:65B0F1E0:qmstart:100:root@pam:"

    @Test
    fun `a UPID carries node, type, id and user`() {
        val task = ProxmoxTask.parseUpid(upid)
        assertNotNull(task)
        requireNotNull(task)
        assertEquals("pve1", task.node)
        assertEquals("qmstart", task.type)
        assertEquals("100", task.id)
        assertEquals("root@pam", task.user)
        // The start time is hexadecimal in the UPID.
        assertEquals(0x65B0F1E0L, task.startTime)
    }

    @Test
    fun `anything that is not a UPID is rejected rather than half-parsed`() {
        assertNull(ProxmoxTask.parseUpid("not a upid"))
        assertNull(ProxmoxTask.parseUpid("UPID:pve1:short"))
        assertNull(ProxmoxTask.parseUpid("TASK:pve1:0:0:0:qmstart:100:root@pam:"))
    }

    @Test
    fun `the description uses the human label of a known task type`() {
        val task = requireNotNull(ProxmoxTask.parseUpid(upid))
        assertEquals("Start VM 100", task.description)
    }

    @Test
    fun `an unknown task type falls back to its raw name`() {
        val task = requireNotNull(
            ProxmoxTask.parseUpid("UPID:pve1:1:1:1:somethingnew:7:root@pam:"),
        )
        assertEquals("somethingnew 7", task.description)
    }

    @Test
    fun `exit status decides success, failure and still-running`() {
        val base = requireNotNull(ProxmoxTask.parseUpid(upid))
        assertTrue(base.isRunning)

        val ok = base.copy(status = "stopped", exitStatus = "OK", endTime = base.startTime + 5)
        assertTrue(ok.succeeded)
        assertFalse(ok.failed)

        // Proxmox reports a partially successful backup as "WARNINGS: n".
        val warned = ok.copy(exitStatus = "WARNINGS: 2")
        assertTrue(warned.succeeded)

        val failed = ok.copy(exitStatus = "command failed with exit code 1")
        assertTrue(failed.failed)
        assertFalse(failed.succeeded)
    }
}

class GuestConfigTest {

    @Test
    fun `a device string splits into its volume and its options`() {
        val parsed = GuestConfig.parseDevice("local-lvm:vm-100-disk-0,size=32G,ssd=1")
        assertEquals("local-lvm:vm-100-disk-0", parsed["volume"])
        assertEquals("32G", parsed["size"])
        assertEquals("1", parsed["ssd"])
    }

    @Test
    fun `a network device keeps the model as its own key`() {
        val parsed = GuestConfig.parseDevice("virtio=AA:BB:CC:DD:EE:FF,bridge=vmbr0,tag=10")
        assertEquals("AA:BB:CC:DD:EE:FF", parsed["virtio"])
        assertEquals("vmbr0", parsed["bridge"])
        assertEquals("10", parsed["tag"])
    }

    @Test
    fun `disks and networks are picked out of a full configuration`() {
        val config = GuestConfig(
            vmid = 100,
            type = GuestType.QEMU,
            entries = linkedMapOf(
                "scsi0" to "local-lvm:vm-100-disk-0,size=32G",
                "scsi1" to "local-lvm:vm-100-disk-1,size=8G",
                "net0" to "virtio=AA:BB:CC:DD:EE:FF,bridge=vmbr0",
                "unused0" to "local-lvm:vm-100-disk-9",
                "cores" to "4",
                "name" to "web",
            ),
            digest = "abc",
        )
        assertEquals(setOf("scsi0", "scsi1"), config.disks().keys)
        assertEquals(setOf("net0"), config.networks().keys)
        assertEquals(setOf("unused0"), config.unusedDisks().keys)
        assertFalse(config.hasPendingChanges())
    }
}

class GuestActionTest {

    private fun guest(
        status: GuestStatus = GuestStatus.RUNNING,
        type: GuestType = GuestType.QEMU,
        template: Boolean = false,
        lock: String? = null,
    ) = ProxmoxGuest(
        vmid = 100,
        name = "web",
        type = type,
        node = "pve1",
        status = status,
        cpu = 0.0,
        maxCpu = 4,
        mem = 0,
        maxMem = 0,
        disk = 0,
        maxDisk = 0,
        uptime = 0,
        template = template,
        lock = lock,
        tags = emptyList(),
        pool = null,
    )

    @Test
    fun `start is offered only to a stopped guest`() {
        assertTrue(GuestAction.START.isApplicable(guest(status = GuestStatus.STOPPED)))
        assertFalse(GuestAction.START.isApplicable(guest(status = GuestStatus.RUNNING)))
    }

    @Test
    fun `suspend and reset are QEMU only`() {
        assertTrue(GuestAction.SUSPEND.isApplicable(guest(type = GuestType.QEMU)))
        assertFalse(GuestAction.SUSPEND.isApplicable(guest(type = GuestType.LXC)))
        assertTrue(GuestAction.RESET.isApplicable(guest(type = GuestType.QEMU)))
        assertFalse(GuestAction.RESET.isApplicable(guest(type = GuestType.LXC)))
    }

    @Test
    fun `resume applies to a paused guest of either flavour`() {
        assertTrue(GuestAction.RESUME.isApplicable(guest(status = GuestStatus.PAUSED)))
        assertTrue(
            GuestAction.RESUME.isApplicable(
                guest(status = GuestStatus.SUSPENDED, type = GuestType.LXC),
            ),
        )
        assertFalse(GuestAction.RESUME.isApplicable(guest(status = GuestStatus.RUNNING)))
    }

    @Test
    fun `a template or a locked guest accepts nothing`() {
        GuestAction.entries.forEach { action ->
            assertFalse(action.isApplicable(guest(template = true)), action.name)
            assertFalse(action.isApplicable(guest(lock = "backup")), action.name)
        }
    }
}

class SnapshotNameTest {

    @Test
    fun `a valid name is accepted`() {
        assertNull(GuestSnapshot.validateName("before_upgrade"))
        assertNull(GuestSnapshot.validateName("snap-2026-01-01"))
    }

    @Test
    fun `the reserved name of the live state is refused`() {
        assertNotNull(GuestSnapshot.validateName("current"))
    }

    @Test
    fun `a name must start with a letter and stay in the allowed alphabet`() {
        assertNotNull(GuestSnapshot.validateName(""))
        assertNotNull(GuestSnapshot.validateName("1st"))
        assertNotNull(GuestSnapshot.validateName("has space"))
        assertNotNull(GuestSnapshot.validateName("a"))
        assertNotNull(GuestSnapshot.validateName("a".repeat(41)))
    }
}

class LifecycleRequestTest {

    private val template = ProxmoxGuest(
        vmid = 900, name = "tmpl", type = GuestType.QEMU, node = "pve1",
        status = GuestStatus.STOPPED, cpu = 0.0, maxCpu = 2, mem = 0, maxMem = 0,
        disk = 0, maxDisk = 0, uptime = 0, template = true, lock = null,
        tags = emptyList(), pool = null,
    )

    @Test
    fun `a linked clone never sends a target storage`() {
        val parameters = CloneRequest(
            source = template,
            newVmid = 901,
            name = "clone",
            targetNode = "pve1",
            pool = null,
            description = null,
            mode = CloneMode.LINKED,
            targetStorage = "local-lvm",
            snapshotName = null,
        ).toParameters()

        assertEquals("0", parameters["full"])
        assertFalse(parameters.containsKey("storage"))
        // The target node equals the source, so Proxmox must not be asked to move the clone.
        assertFalse(parameters.containsKey("target"))
    }

    @Test
    fun `a container clone names the guest with hostname, not name`() {
        val container = template.copy(type = GuestType.LXC, template = false)
        val parameters = CloneRequest(
            source = container,
            newVmid = 902,
            name = "ct-clone",
            targetNode = "pve2",
            pool = "dev",
            description = "spun up for a test",
            mode = CloneMode.FULL,
            targetStorage = "local-lvm",
            snapshotName = "base",
        ).toParameters()

        assertEquals("ct-clone", parameters["hostname"])
        assertNull(parameters["name"])
        assertEquals("pve2", parameters["target"])
        assertEquals("local-lvm", parameters["storage"])
        assertEquals("base", parameters["snapname"])
        assertEquals("dev", parameters["pool"])
    }

    @Test
    fun `a container migration asks for a restart rather than a live move`() {
        val container = template.copy(type = GuestType.LXC, template = false)
        val parameters = MigrateRequest(
            guest = container,
            targetNode = "pve2",
            online = true,
            withLocalDisks = true,
            targetStorage = null,
            force = false,
        ).toParameters()

        assertEquals("1", parameters["restart"])
        assertNull(parameters["online"])
        // with-local-disks is a QEMU parameter and would be rejected for a container.
        assertNull(parameters["with-local-disks"])
    }

    @Test
    fun `a live VM migration passes the QEMU flags through`() {
        val parameters = MigrateRequest(
            guest = template.copy(template = false, status = GuestStatus.RUNNING),
            targetNode = "pve2",
            online = true,
            withLocalDisks = true,
            targetStorage = "fast",
            force = true,
        ).toParameters()

        assertEquals("1", parameters["online"])
        assertEquals("1", parameters["with-local-disks"])
        assertEquals("fast", parameters["targetstorage"])
        assertEquals("1", parameters["force"])
    }

    @Test
    fun `creating a VM composes the disk and network device strings`() {
        val parameters = CreateVmRequest(
            node = "pve1",
            vmid = 110,
            name = "web",
            cores = 2,
            sockets = 1,
            memoryMib = 2048,
            diskGib = 32,
            diskStorage = "local-lvm",
            isoVolume = "local:iso/debian.iso",
            bridge = "vmbr0",
            vlanTag = 20,
            osType = "l26",
            scsiHw = "virtio-scsi-single",
            bios = "seabios",
            machine = null,
            startAfterCreate = true,
            agent = true,
            pool = null,
            description = null,
        ).toParameters()

        assertEquals("local-lvm:32", parameters["scsi0"])
        assertEquals("virtio,bridge=vmbr0,tag=20", parameters["net0"])
        assertEquals("local:iso/debian.iso,media=cdrom", parameters["ide2"])
        assertEquals("1", parameters["agent"])
        assertEquals("1", parameters["start"])
        assertNull(parameters["machine"])
    }

    @Test
    fun `a container without an address falls back to DHCP`() {
        val parameters = CreateContainerRequest(
            node = "pve1",
            vmid = 120,
            hostname = "ct",
            template = "local:vztmpl/debian.tar.zst",
            password = "secret",
            sshPublicKey = null,
            cores = 1,
            memoryMib = 512,
            swapMib = 512,
            diskGib = 8,
            diskStorage = "local-lvm",
            bridge = "vmbr0",
            ipv4 = "dhcp",
            gateway = "192.168.1.1",
            vlanTag = null,
            nesting = true,
            unprivileged = true,
            startAfterCreate = false,
            pool = null,
            description = null,
        ).toParameters()

        assertEquals("name=eth0,bridge=vmbr0,ip=dhcp", parameters["net0"])
        assertEquals("nesting=1", parameters["features"])
        assertEquals("local-lvm:8", parameters["rootfs"])
        assertNull(parameters["start"])
    }
}

class BackupRequestTest {

    @Test
    fun `a backup without a mail address sends no notification settings`() {
        val parameters = BackupRequest(
            node = "pve1",
            vmids = listOf(100, 101),
            storage = "backup",
            mode = BackupMode.SNAPSHOT,
            compression = BackupCompression.ZSTD,
            notes = "{{guestname}}",
            protectBackup = true,
            removeOlder = false,
            mailTo = null,
            notification = BackupNotification.FAILURE,
        ).toParameters()

        assertEquals("100,101", parameters["vmid"])
        assertEquals("snapshot", parameters["mode"])
        assertEquals("zstd", parameters["compress"])
        assertEquals("1", parameters["protected"])
        assertEquals("0", parameters["remove"])
        assertNull(parameters["mailto"])
        assertNull(parameters["mailnotification"])
    }

    @Test
    fun `restoring without overwrite omits the force flag`() {
        val parameters = RestoreRequest(
            node = "pve1",
            archive = "backup:backup/vzdump-qemu-100.vma.zst",
            vmid = 130,
            type = GuestType.QEMU,
            storage = "local-lvm",
            force = false,
            unique = true,
            startAfterRestore = false,
        ).toParameters()

        assertEquals("1", parameters["restore"])
        assertEquals("1", parameters["unique"])
        assertNull(parameters["force"])
        assertNull(parameters["start"])
    }
}

class StorageContentTypeTest {

    @Test
    fun `a comma separated content list maps onto the known types`() {
        val parsed = StorageContentType.parseList("images,rootdir,backup")
        assertEquals(
            listOf(
                StorageContentType.IMAGES,
                StorageContentType.ROOTDIR,
                StorageContentType.BACKUP,
            ),
            parsed,
        )
    }

    @Test
    fun `unknown entries are dropped instead of failing the storage`() {
        assertEquals(listOf(StorageContentType.ISO), StorageContentType.parseList("iso,somethingnew"))
        assertTrue(StorageContentType.parseList(null).isEmpty())
    }
}
