package com.example.demo.service;

import com.example.demo.service.ai.AiAnswerService;
import com.example.demo.service.ai.AiContextService;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseMotionListener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@Slf4j
@Service
@RequiredArgsConstructor
public class ButtonListenerService implements NativeKeyListener, NativeMouseMotionListener {

    private final AiAnswerService aiAnswerService;
    private final AiContextService aiContextService;
    private final SimpMessagingTemplate messagingTemplate;

    private Robot robot;
    private boolean isScrollModeActive = true;

    // Position Tracking
    private int lastY = -1;
    private int yAccumulator = 0;
    private volatile int currentMouseY = -1;

    // Screen Dimensions
    private int screenWidth;
    private int screenHeight;

    // Scroll Settings
    private static final int DEADZONE = 2;
    private static final int SCROLL_THRESHOLD = 40;
    private static final int FIXED_SCROLL_AMOUNT = 60;

    // Margin Auto-Scroll Settings
    private static final int MARGIN = 100;
    private static final int AUTO_SCROLL_SPEED = 60;
    private static final int TICK_RATE_MS = 100;

    private final ScheduledExecutorService autoScrollExecutor = Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void init() {
        try {
            robot = new Robot();
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            screenWidth = screenSize.width;
            screenHeight = screenSize.height;
        } catch (AWTException e) {
            log.error("Failed to initialize Java Robot.", e);
        }

        try {
            Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
            logger.setLevel(Level.WARNING);
            logger.setUseParentHandlers(false);

            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
            GlobalScreen.addNativeMouseMotionListener(this);

            log.info("Global Hook started. Screen resolution: {}x{}", screenWidth, screenHeight);
        } catch (NativeHookException ex) {
            log.error("Problem registering native hook.", ex);
        }

        // Start the background heart-beat for auto-scrolling
        autoScrollExecutor.scheduleAtFixedRate(this::checkAutoScroll, 0, TICK_RATE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Background task that checks if the mouse is in a margin and sends scroll events.
     */
    private void checkAutoScroll() {
        if (!isScrollModeActive || currentMouseY == -1) return;

        // Auto-scroll UP (Top 100px)
        if (currentMouseY <= MARGIN) {
            messagingTemplate.convertAndSend("/topic/scroll", -AUTO_SCROLL_SPEED);
        }
        // Auto-scroll DOWN (Bottom 100px)
        else if (currentMouseY >= (screenHeight - MARGIN)) {
            messagingTemplate.convertAndSend("/topic/scroll", AUTO_SCROLL_SPEED);
        }
    }

    @Override
    public void nativeMouseMoved(NativeMouseEvent e) {
        currentMouseY = e.getY();

        if (!isScrollModeActive) return;

        if (lastY == -1) {
            lastY = currentMouseY;
            return;
        }

        int deltaY = currentMouseY - lastY;
        boolean inSafeZone = currentMouseY > MARGIN && currentMouseY < (screenHeight - MARGIN);

        if (inSafeZone && Math.abs(deltaY) > DEADZONE) {
            yAccumulator += deltaY;
            if (Math.abs(yAccumulator) >= SCROLL_THRESHOLD) {
                int direction = yAccumulator > 0 ? 1 : -1;
                messagingTemplate.convertAndSend("/topic/scroll", FIXED_SCROLL_AMOUNT * direction);
                yAccumulator = 0;
            }
        }

        lastY = currentMouseY;
    }

    @Override
    public void nativeMouseDragged(NativeMouseEvent e) {
        nativeMouseMoved(e);
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        int modifiers = e.getModifiers();
        boolean hasCtrl = (modifiers & NativeKeyEvent.CTRL_L_MASK) != 0 || (modifiers & NativeKeyEvent.CTRL_R_MASK) != 0;
        boolean hasAlt = (modifiers & NativeKeyEvent.ALT_L_MASK) != 0 || (modifiers & NativeKeyEvent.ALT_R_MASK) != 0;
        boolean hasShift = (modifiers & NativeKeyEvent.SHIFT_L_MASK) != 0 || (modifiers & NativeKeyEvent.SHIFT_R_MASK) != 0;

        if (hasCtrl && hasAlt && hasShift) {
            switch (e.getKeyCode()) {
                case NativeKeyEvent.VC_F1 -> {
                    log.info("Macro: Clear Context");
                    aiContextService.clearAnsweredQuestions();
                }
                case NativeKeyEvent.VC_F2 -> {
                    log.info("Macro: Text Analysis");
                    aiAnswerService.generateManualAnswer();
                }
                case NativeKeyEvent.VC_F4 -> {
                    log.info("Macro: Screenshot Analysis");
                    byte[] imageBytes = captureLeftMonitor();
                    CompletableFuture.runAsync(() -> aiAnswerService.processScreenshot(imageBytes));
                }
                case NativeKeyEvent.VC_F5 -> {
                    isScrollModeActive = !isScrollModeActive;
                    log.info("Scroll mode: {}", isScrollModeActive);
                    lastY = -1;
                }
            }
        }
    }

    @PreDestroy
    public void cleanup() {
        try {
            autoScrollExecutor.shutdown();
            GlobalScreen.unregisterNativeHook();
        } catch (NativeHookException e) {
            log.error("Failed cleanup", e);
        }
    }

    public byte[] captureLeftMonitor() {
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice[] screens = ge.getScreenDevices();
            if (screens.length == 0) return null;

            Rectangle leftScreenBounds = screens[0].getDefaultConfiguration().getBounds();
            for (GraphicsDevice screen : screens) {
                Rectangle bounds = screen.getDefaultConfiguration().getBounds();
                if (bounds.x < leftScreenBounds.x) leftScreenBounds = bounds;
            }

            BufferedImage screenCapture = robot.createScreenCapture(leftScreenBounds);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(screenCapture, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Capture failed", e);
            return null;
        }
    }
}