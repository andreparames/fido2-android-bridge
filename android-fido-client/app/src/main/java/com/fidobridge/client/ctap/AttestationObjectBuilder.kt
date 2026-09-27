package com.fidobridge.client.ctap

import com.upokecenter.cbor.CBORObject

object AttestationObjectBuilder {

    fun build(authData: ByteArray): ByteArray {
        val map = CBORObject.NewMap()
        map.set(CBORObject.FromObject("fmt"), CBORObject.FromObject("none"))
        map.set(CBORObject.FromObject("attStmt"), CBORObject.NewMap())
        map.set(CBORObject.FromObject("authData"), CBORObject.FromObject(authData))
        return map.EncodeToBytes()
    }
}
