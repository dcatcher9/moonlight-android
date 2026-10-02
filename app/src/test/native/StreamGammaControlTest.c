// Exact production wire parser and serializer, without opening native sockets.
#define LC_CONTROL_TELEMETRY_TEST
#include "../../main/jni/moonlight-core/moonlight-common-c/src/ControlStream.c"
#undef assert
#define assert(c) do { if (!(c)) { fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #c); abort(); } } while (0)

CONNECTION_LISTENER_CALLBACKS ListenerCallbacks;
unsigned int StreamGammaVersion;
static int sends, acknowledgements;
static uint8_t request[8];

bool LiTestControlSend(short type, short length, const void* payload,
                      uint8_t channel, uint32_t flags, bool moreData) {
    assert(type == 0x300C && length == 8 && channel == CTRL_CHANNEL_SERVERCTL);
    assert(flags == ENET_PACKET_FLAG_RELIABLE && !moreData);
    memcpy(request, payload, 8);
    sends++;
    return true;
}

static void ack(uint8_t status, uint8_t requested, uint8_t applied,
                uint32_t id, uint32_t generation, float white) {
    assert(status == 0 && requested == 2 && applied == 2);
    assert(id == 0x87654321 && generation == 7 && white == 203);
    acknowledgements++;
}

int main(void) {
    assert(supportsStreamGammaV1Sdp("a=x-ss-video.streamGammaVersion:1\r\n"));
    assert(supportsStreamGammaV1Sdp("a=x-ss-video.streamGammaVersion:1\n"));
    assert(!supportsStreamGammaV1Sdp("a=x-ss-video.streamGammaVersion:0\r\n"));
    assert(!supportsStreamGammaV1Sdp("a=x-ss-video.streamGammaVersion:2\r\n"));
    assert(!supportsStreamGammaV1Sdp("a=x-ss-video.streamGammaVersion:1junk\r\n"));
    assert(!supportsStreamGammaV1Sdp("a=x-ss-video.streamGammaVersion:01\r\n"));
    assert(!supportsStreamGammaV1Sdp("a=x-ss-video.streamGammaVersion:1\r\na=x-ss-video.streamGammaVersion:1\r\n"));
    assert(!supportsStreamGammaV1Sdp("a=x-ss-general.featureFlags:0\r\n"));
    packetTypes = (short*)packetTypesGen7Enc;
    ListenerCallbacks.streamGammaAck = ack;
    assert(LiSendStreamGamma(2, 1) == -1 && sends == 0);
    StreamGammaVersion = 1;
    assert(LiSendStreamGamma(3, 1) == -1);
    assert(LiSendStreamGamma(2, 0) == -1);
    assert(LiSendStreamGamma(2, 0x87654321) == 1);
    const uint8_t expected[] = {1, 2, 0, 0, 0x21, 0x43, 0x65, 0x87};
    assert(memcmp(request, expected, 8) == 0 && sends == 1);
    packetTypes = (short*)packetTypesGen7;
    assert(LiSendStreamGamma(2, 1) == -1 && sends == 1);
    packetTypes = (short*)packetTypesGen7Enc;

    uint8_t bytes[18] = {0};
    PNVCTL_ENET_PACKET_HEADER_V1 packet = (void*)bytes;
    packet->type = 0x300D;
    uint8_t* body = bytes + sizeof(*packet);
    body[0] = 1; body[1] = 0; body[2] = 2; body[3] = 2;
    BYTE_BUFFER bb;
    uint32_t whiteBits;
    float white = 203;
    memcpy(&whiteBits, &white, 4);
    BbInitializeWrappedBuffer(&bb, (char*)body, 4, 12, BYTE_ORDER_LITTLE);
    BbPut32(&bb, 0x87654321); BbPut32(&bb, 7); BbPut32(&bb, whiteBits);
    assert(dispatchStreamGammaAck(packet, 18) && acknowledgements == 1);
    for (int length = 2; length < 18; length++) {
        assert(dispatchStreamGammaAck(packet, length));
    }
    assert(dispatchStreamGammaAck(packet, 19));
    body[0] = 2; assert(dispatchStreamGammaAck(packet, 18)); body[0] = 1;
    body[1] = 4; assert(dispatchStreamGammaAck(packet, 18)); body[1] = 0;
    body[2] = 3; assert(dispatchStreamGammaAck(packet, 18)); body[2] = 2;
    body[3] = 3; assert(dispatchStreamGammaAck(packet, 18)); body[3] = 2;
    body[3] = 0; assert(dispatchStreamGammaAck(packet, 18)); body[3] = 2;
    body[8] = 0; assert(dispatchStreamGammaAck(packet, 18)); body[8] = 7;
    for (int i = 0; i < 4; i++) {
        float invalidWhite[] = {NAN, INFINITY, -1, 1001};
        memcpy(&whiteBits, &invalidWhite[i], 4);
        BbInitializeWrappedBuffer(&bb, (char*)body, 12, 4, BYTE_ORDER_LITTLE);
        BbPut32(&bb, whiteBits);
        assert(dispatchStreamGammaAck(packet, 18));
    }
    assert(acknowledgements == 1);
    StreamGammaVersion = 0;
    assert(dispatchStreamGammaAck(packet, 18) && acknowledgements == 1);
    puts("Stream gamma native control contracts passed");
    return 0;
}
