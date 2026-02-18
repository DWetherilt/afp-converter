package com.upland.connect.afp.engine;

public record AfpStructuredField(long offset,
                                 int length,
                                 String sfIdHex,
                                 int flags,
                                 int payloadLength,
                                 byte[] payload) {
    public AfpStructuredField {
        payload = payload == null ? new byte[0] : payload.clone();
        payloadLength = payloadLength >= 0 ? payloadLength : payload.length;
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
