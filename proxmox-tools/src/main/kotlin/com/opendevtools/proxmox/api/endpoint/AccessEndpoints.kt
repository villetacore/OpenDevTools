package com.opendevtools.proxmox.api.endpoint

import com.google.gson.JsonObject
import com.opendevtools.proxmox.api.ProxmoxTransport

/** `/access/...` - shown read-only, so an operator can check who may do what without leaving the IDE. */
class AccessEndpoints(private val transport: ProxmoxTransport) {

    fun users(): List<JsonObject> = transport.getObjects("/access/users")

    fun acl(): List<JsonObject> = transport.getObjects("/access/acl")

    /** Effective privileges of the authenticated user, used to grey out actions that would fail. */
    fun permissions(): JsonObject? = transport.getObject("/access/permissions")
}
