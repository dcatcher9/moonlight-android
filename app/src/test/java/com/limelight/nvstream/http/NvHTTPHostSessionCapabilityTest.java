package com.limelight.nvstream.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class NvHTTPHostSessionCapabilityTest {
    private static String response(String body) {
        return "<root status_code=\"200\">" + body + "</root>";
    }

    @Test
    public void missingTagIsLegacyTokenlessHost() throws Exception {
        ComputerDetails details = new ComputerDetails();

        NvHTTP.populateHostSessionDetails(details, response("<currentgame>0</currentgame>"));

        assertFalse(details.hostSessionIdSupported);
        assertNull(details.hostSessionId);
    }

    @Test
    public void zeroTokenStillAdvertisesCapability() throws Exception {
        ComputerDetails details = new ComputerDetails();

        NvHTTP.populateHostSessionDetails(details,
                response("<hostsessionid>0</hostsessionid>"));

        assertTrue(details.hostSessionIdSupported);
        assertNull(details.hostSessionId);
    }

    @Test
    public void emptyTagStillAdvertisesCapability() throws Exception {
        assertTrue(NvHTTP.hasXmlTag(response("<hostsessionid/>"), "hostsessionid"));
    }

    @Test
    public void activeTokenIsTrimmedAndCopied() throws Exception {
        ComputerDetails parsed = new ComputerDetails();
        NvHTTP.populateHostSessionDetails(parsed,
                response("<hostsessionid> token-123 </hostsessionid>"));

        ComputerDetails copied = new ComputerDetails(parsed);

        assertTrue(copied.hostSessionIdSupported);
        assertEquals("token-123", copied.hostSessionId);
    }

    @Test
    public void tokenlessLaunchResponseIsAcceptedOnlyForLegacyHost() throws Exception {
        String launch = response("<gamesession>1</gamesession>");

        assertNull(NvHTTP.validateHostSessionResponse(launch, false, false, null));

        try {
            NvHTTP.validateHostSessionResponse(launch, true, false, null);
            fail("Token-capable host response must include a token");
        } catch (IOException expected) {
            // Expected.
        }
    }

    @Test
    public void tokenlessResumeResponseIsAcceptedForLegacyHost() throws Exception {
        String resume = response("<resume>1</resume>");

        assertNull(NvHTTP.validateHostSessionResponse(resume, false, true, null));
    }

    @Test
    public void tokenCapableLaunchAcceptsReturnedToken() throws Exception {
        String launch = response(
                "<gamesession>1</gamesession><hostsessionid>token-123</hostsessionid>");

        assertEquals("token-123",
                NvHTTP.validateHostSessionResponse(launch, true, false, null));
    }

    @Test
    public void tokenCapableResumeRequiresMatchingResponseToken() throws Exception {
        String matching = response("<resume>1</resume><hostsessionid>token-123</hostsessionid>");
        String changed = response("<resume>1</resume><hostsessionid>token-456</hostsessionid>");

        assertEquals("token-123",
                NvHTTP.validateHostSessionResponse(matching, true, true, "token-123"));

        try {
            NvHTTP.validateHostSessionResponse(changed, true, true, "token-123");
            fail("Mismatched token must be rejected");
        } catch (IOException expected) {
            // Expected.
        }
    }
}
