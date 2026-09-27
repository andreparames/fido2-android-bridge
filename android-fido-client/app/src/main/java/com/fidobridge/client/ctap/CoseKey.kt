package com.fidobridge.client.ctap

import com.upokecenter.cbor.CBORObject

object CoseKey {

    private const val LABEL_KTY = 1
    private const val LABEL_ALG = 3
    private const val LABEL_CRV = -1
    private const val LABEL_X = -2
    private const val LABEL_Y = -3

    private const val KTY_EC2 = 2
    private const val ALG_ES256 = -7
    private const val CRV_P256 = 1

    fun encode(publicKey: PublicKeyCoords): ByteArray {
        val map = CBORObject.NewMap()
        map.set(CBORObject.FromObject(LABEL_KTY), CBORObject.FromObject(KTY_EC2))
        map.set(CBORObject.FromObject(LABEL_ALG), CBORObject.FromObject(ALG_ES256))
        map.set(CBORObject.FromObject(LABEL_CRV), CBORObject.FromObject(CRV_P256))
        map.set(CBORObject.FromObject(LABEL_X), CBORObject.FromObject(publicKey.x))
        map.set(CBORObject.FromObject(LABEL_Y), CBORObject.FromObject(publicKey.y))
        return map.EncodeToBytes()
    }
}
