package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ClientSbsPresentationRetirementTest {
    @Test
    public void successfulParkLeavesOldPictureVisibleUntilTargetProof() {
        List<String> events = new ArrayList<>();

        boolean success = ClientSbsPresentationRetirement.parkDecoderRetainingPresentation(
                () -> {
                    events.add("decoder parked");
                    return true;
                });

        assertTrue(success);
        assertEquals(List.of("decoder parked"), events);
    }

    @Test
    public void failedParkLeavesOldPictureVisible() {
        List<String> events = new ArrayList<>();

        boolean success = ClientSbsPresentationRetirement.parkDecoderRetainingPresentation(
                () -> {
                    events.add("decoder park failed");
                    return false;
                });

        assertFalse(success);
        assertEquals(List.of("decoder park failed"), events);
    }
}
