package com.fufelshmertzpakostincorporated.kawaicare;

import org.junit.Test;

import com.fufelshmertzpakostincorporated.kawaicare.model.GestureSession;

import static org.junit.Assert.*;

public class GestureSessionTest {

    @Test
    public void durationIsZeroWhenNoFrames() {
        GestureSession s = new GestureSession();
        assertEquals(0, s.getDurationMillis());
        assertEquals(0, s.getFrameCount());
    }

    @Test
    public void createFrameAddsCorrectDurationAndCount() throws InterruptedException {
        GestureSession s = new GestureSession();
        long startNanos = System.nanoTime();
        GestureSession.GestureFrame f1 = s.createFrame(startNanos, startNanos); // relative 0
        s.addFrame(f1);

        // Wait 10 ms and add another frame
        Thread.sleep(10);
        long t2 = System.nanoTime();
        GestureSession.GestureFrame f2 = s.createFrame(t2, startNanos);
        s.addFrame(f2);

        assertEquals(2, s.getFrameCount());
        long duration = s.getDurationMillis();
        assertTrue("Duration should be at least 0", duration >= 0);
        assertTrue("Duration should be non-decreasing", duration >= (f1.timestampNanos / 1_000_000));
    }
}
