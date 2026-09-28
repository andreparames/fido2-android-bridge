package com.fidobridge.client.protocol

object Ctap2Status {
    const val CTAP2_ERR_CMD_NOT_SUPPORTED = 0x01
    const val CTAP2_ERR_INVALID_OPTION = 0x26
    const val CTAP2_ERR_OPERATION_DENIED = 0x27
    const val CTAP2_ERR_NO_CREDENTIALS = 0x2E
    const val VERSION_MISMATCH = 0x7F
}
